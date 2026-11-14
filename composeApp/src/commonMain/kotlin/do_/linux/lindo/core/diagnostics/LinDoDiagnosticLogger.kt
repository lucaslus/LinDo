package do_.linux.lindo.core.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.runUiSafeStateWrite
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class DiagnosticLevel {
    INFO,
    WARN,
    ERROR,
    FATAL
}

enum class DiagnosticCategory(val labelZh: String) {
    NETWORK(LinDoMessagesCopy.DIAG_TAB_NETWORK.text),
    AUTH(LinDoMessagesCopy.DIAG_TAB_AUTH_SHORT.text),
    CRASH(LinDoMessagesCopy.DIAG_TAB_CRASH.text),
    SYSTEM(LinDoMessagesCopy.DIAG_TAB_SYSTEM.text)
}

data class DiagnosticLogEntry(
    val id: Long,
    val timestampMs: Long,
    val timestampIso: String,
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val tag: String,
    val summary: String,
    val detail: String? = null
)

/**
 * 全端统一本地诊断与崩溃日志引擎（Android / macOS / Windows）：
 * 1. 记录所有非 2xx 网络请求、连接超时、Cloudflare 挑战（含 `cf-ray` / `cf-mitigated`）、JSON 解析异常与未捕获崩溃栈；
 * 2. 自动对 Cookie（`_t` / `_forum_session` / `cf_clearance`）、`User-Api-Key`、`X-CSRF-Token`、Bearer、OTP 与 RSA 载荷执行严格脱敏；
 * 3. 本地双文件滚动持久化（上限约 1.5 MB），支持跨重启保留上次崩溃现场，并支持一键导出 `.log` 或在清理缓存时一并清空。
 */
object LinDoDiagnosticLogger {
    const val APP_VERSION_NAME: String = "0.2.32"
    const val APP_BUILD: Int = 42
    const val APP_VERSION_DISPLAY: String = "Lindo v$APP_VERSION_NAME · build $APP_BUILD"

    private const val MAX_MEMORY_ENTRIES = 320
    private const val MAX_SINGLE_LOG_FILE_BYTES = 768L * 1024L
    private const val LOG_RECORD_HEADER_PREFIX = "=== LINDO_LOG_V2 |"

    private val lock = Any()
    private val nextId = AtomicLong(1L)
    private val crashHandlerInstalled = AtomicBoolean(false)
    private val entries = ArrayDeque<DiagnosticLogEntry>(MAX_MEMORY_ENTRIES)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var platformSummary: String = buildDefaultPlatformSummary()

    var logVersion by mutableIntStateOf(0)
        private set

    @Volatile
    var exportSaveHandler: ((suggestedFileName: String, content: String, onResult: (Boolean, String) -> Unit) -> Unit)? = null

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault())

    private val fileNameTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    private val sensitiveQueryParamRegex = Regex(
        """(?i)([?&](?:payload|oneTimePassword|one_time_password|public_key|nonce|api_key|user_api_key|token|csrf|password|secret)=)([^&#\s"']+)"""
    )
    private val otpPathRegex = Regex("""(?i)(/session/otp/)([^/?#\s"']+)""")
    private val sensitiveCookiePairRegex = Regex(
        """(?i)\b(_t|_forum_session|cf_clearance|__cf_bm|_cfuvid|cf_chl_rc_i|cf_chl_rc_ni)\s*=\s*([^;\r\n"'\s]+)"""
    )
    private val sensitiveHeaderInlineRegex = Regex(
        """(?i)\b(User-Api-Key|X-CSRF-Token|Authorization|Set-Cookie|Cookie)\s*:\s*([^;\r\n]+)"""
    )
    private val bearerTokenRegex = Regex("""(?i)\b(Bearer)\s+([A-Za-z0-9._\-+/=]+)""")

    private fun buildDefaultPlatformSummary(): String {
        val osName = System.getProperty("os.name").orEmpty()
        val osVer = System.getProperty("os.version").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty()
        val javaVer = System.getProperty("java.version").orEmpty()
        return "$osName $osVer ($osArch) · JVM $javaVer".trim()
    }

    private fun bumpVersion() {
        runUiSafeStateWrite {
            logVersion++
        }
    }

    fun sanitizeUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        return rawUrl.trim()
            .replace(otpPathRegex) { "${it.groupValues[1]}<REDACTED_OTP>" }
            .replace(sensitiveQueryParamRegex) { "${it.groupValues[1]}<REDACTED>" }
            .take(420)
    }

    fun redactSecrets(raw: String): String {
        if (raw.isEmpty()) return ""
        return raw
            .replace(otpPathRegex) { "${it.groupValues[1]}<REDACTED_OTP>" }
            .replace(sensitiveQueryParamRegex) { "${it.groupValues[1]}<REDACTED>" }
            .replace(sensitiveCookiePairRegex) { "${it.groupValues[1]}=<REDACTED>" }
            .replace(bearerTokenRegex) { "${it.groupValues[1]} <REDACTED>" }
            .replace(sensitiveHeaderInlineRegex) { "${it.groupValues[1]}: <REDACTED>" }
    }

    fun configurePersistence(directory: File, platformInfo: String? = null) {
        synchronized(lock) {
            if (!platformInfo.isNullOrBlank()) {
                platformSummary = platformInfo.trim()
            }
            runCatching {
                if (!directory.exists()) directory.mkdirs()
                logDir = directory
                loadPersistedEntriesLocked(directory)
            }
        }
        bumpVersion()
    }

    fun installUncaughtExceptionHandler() {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                recordUncaughtCrash(thread, throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun recordUncaughtCrash(thread: Thread?, throwable: Throwable, contextNote: String? = null) {
        val threadName = thread?.name ?: "unknown-thread"
        val sw = StringWriter(1024)
        PrintWriter(sw).use { pw ->
            throwable.printStackTrace(pw)
        }
        val title = buildString {
            append(
                LinDoNetworkCopy.DIAGNOSTIC_UNCAUGHT_CRASH.format(
                    throwable::class.java.simpleName.ifBlank { "Throwable" },
                    threadName
                )
            )
            if (!contextNote.isNullOrBlank()) {
                append(" · ")
                append(contextNote.trim())
            }
        }
        val msg = throwable.message?.takeIf { it.isNotBlank() }?.let { "message: $it\n" }.orEmpty()
        appendEntryInternal(
            level = DiagnosticLevel.FATAL,
            category = DiagnosticCategory.CRASH,
            tag = "UncaughtException",
            summary = redactSecrets(title).take(260),
            detail = redactSecrets(msg + sw.toString()).take(6_000),
            forceSyncDisk = true
        )
    }

    fun info(
        category: DiagnosticCategory,
        tag: String,
        message: String,
        detail: String? = null
    ) {
        appendEntryInternal(
            level = DiagnosticLevel.INFO,
            category = category,
            tag = tag,
            summary = redactSecrets(message).take(300),
            detail = detail?.let { redactSecrets(it).take(3_200) },
            forceSyncDisk = false
        )
    }

    fun warn(
        category: DiagnosticCategory,
        tag: String,
        message: String,
        detail: String? = null,
        throwable: Throwable? = null
    ) {
        val fullDetail = buildThrowableDetail(detail, throwable)
        appendEntryInternal(
            level = DiagnosticLevel.WARN,
            category = category,
            tag = tag,
            summary = redactSecrets(message).take(300),
            detail = fullDetail?.let { redactSecrets(it).take(3_200) },
            forceSyncDisk = false
        )
    }

    fun error(
        category: DiagnosticCategory,
        tag: String,
        message: String,
        detail: String? = null,
        throwable: Throwable? = null
    ) {
        val fullDetail = buildThrowableDetail(detail, throwable)
        appendEntryInternal(
            level = DiagnosticLevel.ERROR,
            category = category,
            tag = tag,
            summary = redactSecrets(message).take(300),
            detail = fullDetail?.let { redactSecrets(it).take(4_000) },
            forceSyncDisk = false
        )
    }

    private fun buildThrowableDetail(detail: String?, throwable: Throwable?): String? {
        if (detail.isNullOrBlank() && throwable == null) return null
        return buildString {
            if (!detail.isNullOrBlank()) {
                appendLine(detail.trim())
            }
            if (throwable != null) {
                val sw = StringWriter(512)
                PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
                append(sw.toString().trimEnd())
            }
        }.trim().takeIf { it.isNotEmpty() }
    }

    fun recordHttpFailure(
        method: String,
        url: String,
        statusCode: Int,
        transport: String,
        elapsedMs: Long,
        cfMitigated: String? = null,
        cfRay: String? = null,
        responseSnippet: String? = null,
        error: Throwable? = null
    ) {
        val safeUrl = sanitizeUrl(url)
        val statusLabel = if (statusCode > 0) "HTTP $statusCode" else "IO_ERR"
        val cfFlags = buildList {
            if (!cfMitigated.isNullOrBlank()) add("cfMitigated=${cfMitigated.trim()}")
            if (!cfRay.isNullOrBlank()) add("cfRay=${cfRay.trim()}")
        }.joinToString(", ")
        val summary = buildString {
            append(method.uppercase())
            append(' ')
            append(safeUrl)
            append(" -> ")
            append(statusLabel)
            if (cfFlags.isNotEmpty()) {
                append(" [")
                append(cfFlags)
                append(']')
            }
            append(" (")
            append(elapsedMs.coerceAtLeast(0L))
            append("ms, ")
            append(transport)
            append(')')
        }
        val detail = buildString {
            if (error != null) {
                appendLine("exception: ${error::class.java.name}: ${error.message.orEmpty()}")
            }
            val cleanBody = responseSnippet
                ?.replace("\r", " ")
                ?.replace("\n", " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
            if (cleanBody.isNotEmpty()) {
                append("body: ")
                append(cleanBody.take(420))
            }
        }.trim().takeIf { it.isNotEmpty() }
        val level = if (statusCode in 500..599 || statusCode <= 0) DiagnosticLevel.ERROR else DiagnosticLevel.WARN
        appendEntryInternal(
            level = level,
            category = DiagnosticCategory.NETWORK,
            tag = transport,
            summary = redactSecrets(summary).take(340),
            detail = detail?.let { redactSecrets(it).take(2_400) },
            forceSyncDisk = false
        )
    }

    fun recordHttpFailure(
        method: String,
        rawUrl: String,
        statusCode: Int,
        transport: String,
        durationMs: Long,
        isChallenge: Boolean = false,
        cfRay: String? = null,
        bodyPreview: String? = null,
        throwable: Throwable? = null
    ) {
        recordHttpFailure(
            method = method,
            url = rawUrl,
            statusCode = statusCode,
            transport = transport,
            elapsedMs = durationMs,
            cfMitigated = if (isChallenge) "challenge" else null,
            cfRay = cfRay,
            responseSnippet = bodyPreview,
            error = throwable
        )
    }

    private fun appendEntryInternal(
        level: DiagnosticLevel,
        category: DiagnosticCategory,
        tag: String,
        summary: String,
        detail: String?,
        forceSyncDisk: Boolean
    ) {
        val now = System.currentTimeMillis()
        val iso = runCatching { isoFormatter.format(Instant.ofEpochMilli(now)) }
            .getOrDefault(now.toString())
        val entry = DiagnosticLogEntry(
            id = nextId.getAndIncrement(),
            timestampMs = now,
            timestampIso = iso,
            level = level,
            category = category,
            tag = tag.replace('|', '/').trim().ifBlank { "App" },
            summary = summary,
            detail = detail?.takeIf { it.isNotBlank() }
        )
        synchronized(lock) {
            while (entries.size >= MAX_MEMORY_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(entry)
            persistEntryLocked(entry, forceSyncDisk)
        }
        bumpVersion()
    }

    private fun activeLogFileLocked(dir: File): File = File(dir, "lindo_diagnostic.log")
    private fun backupLogFileLocked(dir: File): File = File(dir, "lindo_diagnostic.log.1")

    private fun persistEntryLocked(entry: DiagnosticLogEntry, forceSyncDisk: Boolean) {
        val dir = logDir ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val primary = activeLogFileLocked(dir)
            if (primary.isFile && primary.length() >= MAX_SINGLE_LOG_FILE_BYTES) {
                val backup = backupLogFileLocked(dir)
                runCatching { if (backup.exists()) backup.delete() }
                runCatching { primary.renameTo(backup) }
            }
            val serialized = buildString {
                append(LOG_RECORD_HEADER_PREFIX)
                append(' ')
                append(entry.timestampMs)
                append(" | ")
                append(entry.timestampIso)
                append(" | ")
                append(entry.level.name)
                append(" | ")
                append(entry.category.name)
                append(" | ")
                append(entry.tag)
                append(" | ")
                appendLine(entry.summary.replace('\n', ' ').replace('\r', ' '))
                if (!entry.detail.isNullOrBlank()) {
                    appendLine(entry.detail.trimEnd())
                }
            }
            FileOutputStream(primary, true).use { fos ->
                fos.write(serialized.toByteArray(Charsets.UTF_8))
                fos.flush()
                if (forceSyncDisk) {
                    runCatching { fos.fd.sync() }
                }
            }
        }
    }

    private fun loadPersistedEntriesLocked(dir: File) {
        val files = listOf(backupLogFileLocked(dir), activeLogFileLocked(dir)).filter { it.isFile }
        if (files.isEmpty()) return
        val loaded = ArrayList<DiagnosticLogEntry>(MAX_MEMORY_ENTRIES)
        for (file in files) {
            val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: continue
            var curTs = 0L
            var curIso = ""
            var curLevel = DiagnosticLevel.INFO
            var curCat = DiagnosticCategory.SYSTEM
            var curTag = "App"
            var curSummary: String? = null
            val detailBuf = StringBuilder()

            fun flushCurrent() {
                val sum = curSummary ?: return
                loaded.add(
                    DiagnosticLogEntry(
                        id = nextId.getAndIncrement(),
                        timestampMs = curTs,
                        timestampIso = curIso,
                        level = curLevel,
                        category = curCat,
                        tag = curTag,
                        summary = sum,
                        detail = detailBuf.toString().trimEnd().takeIf { it.isNotEmpty() }
                    )
                )
                curSummary = null
                detailBuf.setLength(0)
            }

            for (line in lines) {
                if (line.startsWith(LOG_RECORD_HEADER_PREFIX)) {
                    flushCurrent()
                    val parts = line.removePrefix(LOG_RECORD_HEADER_PREFIX).split('|', limit = 6)
                    if (parts.size == 6) {
                        curTs = parts[0].trim().toLongOrNull() ?: System.currentTimeMillis()
                        curIso = parts[1].trim()
                        curLevel = runCatching { DiagnosticLevel.valueOf(parts[2].trim()) }
                            .getOrDefault(DiagnosticLevel.INFO)
                        curCat = runCatching { DiagnosticCategory.valueOf(parts[3].trim()) }
                            .getOrDefault(DiagnosticCategory.SYSTEM)
                        curTag = parts[4].trim().ifBlank { "App" }
                        curSummary = parts[5].trim()
                    }
                } else if (curSummary != null) {
                    if (detailBuf.isNotEmpty()) detailBuf.append('\n')
                    detailBuf.append(line)
                }
            }
            flushCurrent()
        }
        entries.clear()
        val tail = if (loaded.size > MAX_MEMORY_ENTRIES) loaded.takeLast(MAX_MEMORY_ENTRIES) else loaded
        entries.addAll(tail)
    }

    fun snapshotEntries(categoryFilter: DiagnosticCategory? = null): List<DiagnosticLogEntry> {
        @Suppress("UNUSED_VARIABLE")
        val observed = logVersion
        synchronized(lock) {
            val list = if (categoryFilter == null) {
                entries.toList()
            } else {
                entries.filter { it.category == categoryFilter }
            }
            return list.asReversed()
        }
    }

    fun snapshot(categoryFilter: DiagnosticCategory? = null): List<DiagnosticLogEntry> =
        snapshotEntries(categoryFilter)

    fun entryCount(): Int {
        @Suppress("UNUSED_VARIABLE")
        val observed = logVersion
        synchronized(lock) {
            return entries.size
        }
    }

    fun persistedFileSizeBytes(): Long {
        @Suppress("UNUSED_VARIABLE")
        val observed = logVersion
        synchronized(lock) {
            val dir = logDir ?: return 0L
            val f1 = activeLogFileLocked(dir).takeIf { it.isFile }?.length() ?: 0L
            val f2 = backupLogFileLocked(dir).takeIf { it.isFile }?.length() ?: 0L
            return f1 + f2
        }
    }

    fun persistedFilePath(): String? {
        @Suppress("UNUSED_VARIABLE")
        val observed = logVersion
        synchronized(lock) {
            val dir = logDir ?: return null
            return activeLogFileLocked(dir).absolutePath
        }
    }

    fun clearAllLogs() {
        synchronized(lock) {
            entries.clear()
            val dir = logDir
            if (dir != null) {
                runCatching { activeLogFileLocked(dir).delete() }
                runCatching { backupLogFileLocked(dir).delete() }
            }
        }
        bumpVersion()
    }

    fun resetForTest() {
        synchronized(lock) {
            entries.clear()
            logDir = null
            exportSaveHandler = null
        }
        bumpVersion()
    }

    fun defaultExportFileName(nowMs: Long = System.currentTimeMillis()): String {
        val ts = runCatching { fileNameTimeFormatter.format(Instant.ofEpochMilli(nowMs)) }
            .getOrDefault(nowMs.toString())
        return "lindo-diagnostics-$ts.log"
    }

    fun requestExport(
        fileName: String,
        content: String,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ): Boolean {
        val handler = exportSaveHandler ?: return false
        handler(fileName, content, onResult)
        return true
    }

    fun buildExportReport(categoryFilter: DiagnosticCategory? = null): String {
        val snap = synchronized(lock) {
            if (categoryFilter == null) entries.toList() else entries.filter { it.category == categoryFilter }
        }
        val winningCookies = runCatching { CookieSentinel.resolveWinningCookies(targetHost = "linux.do") }.getOrDefault(emptyMap())
        val hasT = winningCookies["_t"]?.value?.let { it.isNotBlank() && it != "demo_session_token" } == true
        val hasForumSession = winningCookies["_forum_session"]?.value?.isNotBlank() == true
        val hasCfClearance = winningCookies["cf_clearance"]?.value?.isNotBlank() == true
        val hasApiKey = !UserApiKeyAuthManager.getApiKey().isNullOrBlank()
        val transportMode = if (UserApiKeyAuthManager.webViewHttpExecutor != null) "webview_bridge" else "okhttp_direct"
        val nowIso = runCatching { isoFormatter.format(Instant.now()) }.getOrDefault("")

        return buildString(4096) {
            appendLine("============================================================")
            appendLine("Lindo Diagnostic & Crash Log Report")
            appendLine("Version: $APP_VERSION_DISPLAY")
            appendLine("Generated At : $nowIso")
            appendLine("Platform     : $platformSummary")
            appendLine("Transport    : $transportMode (browserOnly=${UserApiKeyAuthManager.requireBrowserTransportOnly})")
            appendLine(
                "Auth Flags   : authorized=${UserApiKeyAuthManager.isAuthorized()}, " +
                    "cookie_t=$hasT, forum_session=$hasForumSession, cf_clearance=$hasCfClearance, api_key=$hasApiKey"
            )
            appendLine(
                "Guard State  : rateLimited=${LinuxDoRepository.isRateLimitedNow()}, " +
                    "cfCooldown=${LinuxDoRepository.isChallengeCooldownActive()}"
            )
            appendLine("Log Entries  : ${snap.size} (All tokens & cookies redacted)")
            appendLine("============================================================")
            appendLine()
            if (snap.isEmpty()) {
                appendLine("(No diagnostic or crash events recorded.)")
            } else {
                for (item in snap) {
                    append('[')
                    append(item.timestampIso)
                    append("] [")
                    append(item.level.name)
                    append("] [")
                    append(item.category.name)
                    append("] [")
                    append(item.tag)
                    append("] ")
                    appendLine(item.summary)
                    if (!item.detail.isNullOrBlank()) {
                        item.detail.lines().forEach { dLine ->
                            append("    ")
                            appendLine(dLine)
                        }
                    }
                }
            }
        }
    }
}
