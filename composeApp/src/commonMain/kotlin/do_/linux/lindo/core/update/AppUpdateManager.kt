package do_.linux.lindo.core.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.runUiSafeStateWrite
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class MobileUpdateInfo(
    val hasUpdate: Boolean,
    val currentVersionName: String,
    val currentVersionCode: Int,
    val latestVersionName: String,
    val latestVersionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: List<String>,
    val updatedAt: String
)

data class UpdateDownloadProgress(
    val stage: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val message: String
)

/**
 * 纯 GitHub Releases 跨端检查更新与包体下载校验引擎 (`https://api.github.com/repos/lucaslus/LinDo/releases/latest`)：
 * 1. 全链路纯 HTTPS 直连 GitHub 官方接口与 Release Assets，绝不触碰社区会话；
 * 2. 当仓库尚未发布 Release (`HTTP 404`) 时平滑视为当前已是最新版本，零报错；
 * 3. 原生兼容两种发布形态：直接上传 `.apk` 到 GitHub Release（自动提取 `digest` SHA-256 与 Markdown 日志）或附带 `latest.json`；
 * 4. 支持已下载包秒级复用、SHA-256 完整性校验与调起系统 `FileProvider` PackageInstaller 覆盖升级。
 */
object AppUpdateManager {
    const val GITHUB_LATEST_RELEASE_API: String =
        "https://api.github.com/repos/lucaslus/LinDo/releases/latest"
    const val DOWNLOAD_PAGE_URL: String =
        "https://github.com/lucaslus/LinDo/releases"

    private val json = Json { ignoreUnknownKeys = true }
    private val buildNumberRegex = Regex("""build[-_.]?(\d+)""", RegexOption.IGNORE_CASE)
    private val semverInNameRegex = Regex("""v?(\d+\.\d+\.\d+)""", RegexOption.IGNORE_CASE)

    var latestInfo by mutableStateOf<MobileUpdateInfo?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    var downloading by mutableStateOf(false)
        private set

    var progress by mutableStateOf<UpdateDownloadProgress?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var dialogVisible by mutableStateOf(false)
        private set

    @Volatile
    var cacheDirProvider: (() -> File)? = null

    @Volatile
    var apkInstaller: ((File) -> Result<Unit>)? = null

    fun openDialog() {
        runUiSafeStateWrite {
            errorMessage = null
            dialogVisible = true
        }
    }

    fun dismissDialog() {
        if (downloading) return
        runUiSafeStateWrite {
            dialogVisible = false
            errorMessage = null
        }
    }

    suspend fun checkForUpdates(
        openDialogWhenAvailable: Boolean = false,
        apiEndpointOverride: String? = null
    ): Result<MobileUpdateInfo> {
        if (checking) {
            latestInfo?.let { return Result.success(it) }
        }
        runUiSafeStateWrite {
            checking = true
            if (openDialogWhenAvailable) errorMessage = null
        }
        return try {
            val info = withContext(Dispatchers.IO) {
                val baseEndpoint = apiEndpointOverride ?: GITHUB_LATEST_RELEASE_API
                val sep = if (baseEndpoint.contains('?')) '&' else '?'
                val targetEndpoint = "$baseEndpoint${sep}_t=${System.currentTimeMillis()}"
                val conn = (URI.create(targetEndpoint).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 10000
                    readTimeout = 12000
                    useCaches = false
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "Lindo-Android/${LinDoDiagnosticLogger.APP_VERSION_NAME}")
                }
                try {
                    val status = conn.responseCode
                    // HTTP 404 on `/releases/latest` means GitHub has no published Release yet -> cleanly report up-to-date!
                    if (status == 404) {
                        return@withContext MobileUpdateInfo(
                            hasUpdate = false,
                            currentVersionName = LinDoDiagnosticLogger.APP_VERSION_NAME,
                            currentVersionCode = LinDoDiagnosticLogger.APP_BUILD,
                            latestVersionName = LinDoDiagnosticLogger.APP_VERSION_NAME,
                            latestVersionCode = LinDoDiagnosticLogger.APP_BUILD,
                            apkUrl = "",
                            sha256 = "",
                            sizeBytes = 0L,
                            notes = emptyList(),
                            updatedAt = ""
                        )
                    }
                    if (status !in 200..299) {
                        throw IllegalStateException("GitHub Release 接口响应异常 (HTTP $status)")
                    }
                    val releaseRaw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val releaseObj = json.parseToJsonElement(releaseRaw).jsonObject

                    // Optional `latest.json` asset inside GitHub Release assets
                    val assetsArr = releaseObj["assets"]?.jsonArray
                        ?.mapNotNull { it as? JsonObject }
                        .orEmpty()

                    val manifestUrl = assetsArr
                        .firstOrNull {
                            it["name"]?.jsonPrimitive?.content.equals("latest.json", ignoreCase = true)
                        }
                        ?.get("browser_download_url")?.jsonPrimitive?.content
                        ?.takeIf { it.isNotBlank() }

                    val manifestRaw = manifestUrl?.let { fetchOptionalText(it) }
                    var parsed = parseGithubRelease(releaseRaw, manifestRaw)
                    if (parsed.apkUrl.isNotBlank() && parsed.sha256.isBlank()) {
                        val sumsUrl = assetsArr
                            .firstOrNull {
                                it["name"]?.jsonPrimitive?.content.equals("SHA256SUMS.txt", ignoreCase = true)
                            }
                            ?.get("browser_download_url")?.jsonPrimitive?.content
                            ?.takeIf { it.isNotBlank() }
                        val sumsRaw = sumsUrl?.let { fetchOptionalText(it) }
                        if (!sumsRaw.isNullOrBlank()) {
                            parsed = parseGithubRelease(releaseRaw, manifestRaw, sumsRaw)
                        }
                    }
                    parsed
                } finally {
                    conn.disconnect()
                }
            }
            runUiSafeStateWrite {
                latestInfo = info
                if (openDialogWhenAvailable && info.hasUpdate) {
                    dialogVisible = true
                }
            }
            Result.success(info)
        } catch (t: Throwable) {
            LinDoDiagnosticLogger.warn(
                category = DiagnosticCategory.SYSTEM,
                tag = "AppUpdate",
                message = "GitHub Release 检查更新未成功: ${t.message ?: t::class.simpleName}"
            )
            Result.failure(t)
        } finally {
            runUiSafeStateWrite {
                checking = false
            }
        }
    }

    private fun fetchOptionalText(url: String): String? = runCatching {
        val c = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Lindo-Android/${LinDoDiagnosticLogger.APP_VERSION_NAME}")
        }
        try {
            if (c.responseCode in 200..299) {
                c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    suspend fun downloadAndInstall(
        info: MobileUpdateInfo,
        onFallbackOpenBrowser: (String) -> Unit
    ) {
        if (downloading) return
        val installer = apkInstaller
        if (installer == null) {
            onFallbackOpenBrowser(info.apkUrl.ifBlank { DOWNLOAD_PAGE_URL })
            return
        }
        if (info.apkUrl.isBlank()) {
            runUiSafeStateWrite { errorMessage = "未找到可用的安装包地址" }
            return
        }

        runUiSafeStateWrite {
            downloading = true
            errorMessage = null
            progress = UpdateDownloadProgress(
                stage = "downloading",
                downloadedBytes = 0L,
                totalBytes = info.sizeBytes,
                percent = 1,
                message = "正在连接 GitHub Release 下载节点..."
            )
        }

        try {
            val verifiedApk = downloadAndVerifyApkToCache(info)
            val installRes = installer(verifiedApk)
            installRes.onFailure { err ->
                runUiSafeStateWrite {
                    errorMessage = err.message ?: "无法调起系统安装器，请点击左下角前往 GitHub 下载"
                }
            }
            if (installRes.isSuccess) {
                runUiSafeStateWrite {
                    downloading = false
                    dialogVisible = false
                }
                return
            }
        } catch (t: Throwable) {
            runUiSafeStateWrite {
                errorMessage = t.message ?: "下载更新失败，请稍后重试或前往 GitHub 下载"
            }
        } finally {
            runUiSafeStateWrite {
                downloading = false
            }
        }
    }

    internal suspend fun downloadAndVerifyApkToCache(info: MobileUpdateInfo): File =
        withContext(Dispatchers.IO) {
            val baseDir = cacheDirProvider?.invoke()
                ?: File(System.getProperty("java.io.tmpdir"), "lindo_updates")
            if (!baseDir.exists()) baseDir.mkdirs()

            val fileName = "Lindo-v${info.latestVersionName}-build${info.latestVersionCode}.apk"
            val targetFile = File(baseDir, fileName)

            baseDir.listFiles()?.forEach { f ->
                if (f.name != fileName && (f.name.endsWith(".apk") || f.name.endsWith(".part"))) {
                    runCatching { f.delete() }
                }
            }

            if (targetFile.exists() && targetFile.length() > 100_000L) {
                val cachedValid = info.sha256.isBlank() ||
                    computeSha256(targetFile).equals(info.sha256.trim(), ignoreCase = true)
                if (cachedValid) {
                    runUiSafeStateWrite {
                        progress = UpdateDownloadProgress(
                            stage = "installing",
                            downloadedBytes = targetFile.length(),
                            totalBytes = targetFile.length(),
                            percent = 100,
                            message = "安装包已就绪，正在打开系统安装器..."
                        )
                    }
                    return@withContext targetFile
                } else {
                    runCatching { targetFile.delete() }
                }
            }

            val partFile = File(baseDir, "$fileName.part")
            runCatching { partFile.delete() }

            val conn = (URI.create(info.apkUrl).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 45000
                useCaches = false
                setRequestProperty("User-Agent", "Lindo-Android/${LinDoDiagnosticLogger.APP_VERSION_NAME}")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val status = conn.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("下载安装包失败 (HTTP $status)")
                }
                val total = conn.contentLengthLong.takeIf { it > 0L } ?: info.sizeBytes
                var downloaded = 0L
                var lastEmitMs = 0L
                val buf = ByteArray(32 * 1024)

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 90L) {
                                lastEmitMs = now
                                val pct = if (total > 0L) {
                                    ((downloaded.toDouble() / total.toDouble()) * 95.0).toInt().coerceIn(1, 95)
                                } else {
                                    45
                                }
                                runUiSafeStateWrite {
                                    progress = UpdateDownloadProgress(
                                        stage = "downloading",
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        percent = pct,
                                        message = "正在从 GitHub 下载新版本安装包..."
                                    )
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }

                runUiSafeStateWrite {
                    progress = UpdateDownloadProgress(
                        stage = "verifying",
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        percent = 97,
                        message = "正在校验安装包 SHA-256 签名完整性..."
                    )
                }

                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256.isNotBlank() && !actualSha.equals(info.sha256.trim(), ignoreCase = true)) {
                    runCatching { partFile.delete() }
                    throw IllegalStateException("安装包完整性校验不一致，请重试")
                }

                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }

                runUiSafeStateWrite {
                    progress = UpdateDownloadProgress(
                        stage = "installing",
                        downloadedBytes = targetFile.length(),
                        totalBytes = targetFile.length(),
                        percent = 100,
                        message = "校验通过，正在调起系统安装程序..."
                    )
                }
                targetFile
            } finally {
                conn.disconnect()
            }
        }

    internal fun parseGithubRelease(
        releaseRawJson: String,
        manifestRawJson: String? = null,
        sha256SumsRaw: String? = null
    ): MobileUpdateInfo {
        val root = json.parseToJsonElement(releaseRawJson).jsonObject
        val tagName = root["tag_name"]?.jsonPrimitive?.content.orEmpty().trim()
        val publishedAt = root["published_at"]?.jsonPrimitive?.content.orEmpty().take(10)
        val bodyMd = root["body"]?.jsonPrimitive?.content.orEmpty()

        val apkAsset = root["assets"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull {
                it["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true
            }

        val apkName = apkAsset?.get("name")?.jsonPrimitive?.content.orEmpty()
        var apkUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.content.orEmpty()
        var sizeBytes = apkAsset?.get("size")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        var sha256 = apkAsset?.get("digest")?.jsonPrimitive?.content.orEmpty()
            .removePrefix("sha256:")
            .trim()

        val buildInAssetName = buildNumberRegex.find(apkName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val versionInAssetName = semverInNameRegex.find(apkName)?.groupValues?.getOrNull(1)
        val cleanTagVersion = tagName.trimStart('v', 'V').substringBefore('-')

        var latestVersionName = versionInAssetName
            ?: cleanTagVersion.ifBlank { LinDoDiagnosticLogger.APP_VERSION_NAME }
        var latestVersionCode = buildInAssetName ?: LinDoDiagnosticLogger.APP_BUILD
        var updatedAt = publishedAt
        var notes = extractNotesFromMarkdown(bodyMd)

        // If an optional `latest.json` asset is attached to the GitHub Release, merge its exact fields
        if (!manifestRawJson.isNullOrBlank()) {
            runCatching {
                val mRoot = json.parseToJsonElement(manifestRawJson).jsonObject
                mRoot["updatedAt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                    updatedAt = it
                }
                val androidObj = mRoot["android"]?.jsonObject
                if (androidObj != null) {
                    androidObj["versionName"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                        latestVersionName = it
                    }
                    androidObj["versionCode"]?.jsonPrimitive?.content?.toIntOrNull()?.let {
                        latestVersionCode = it
                    }
                    if (apkUrl.isBlank()) {
                        androidObj["apkUrl"]?.jsonPrimitive?.content
                            ?.takeIf { it.startsWith("https://github.com/") }
                            ?.let { apkUrl = it }
                    }
                    androidObj["sha256"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                        sha256 = it.trim()
                    }
                    androidObj["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0L }?.let {
                        sizeBytes = it
                    }
                    val mNotes = androidObj["notes"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content.trim().takeIf(String::isNotEmpty) }
                        .orEmpty()
                    if (mNotes.isNotEmpty()) notes = mNotes
                }
            }
        }

        if (sha256.isBlank() && apkName.isNotBlank() && !sha256SumsRaw.isNullOrBlank()) {
            sha256 = extractSha256FromSums(sha256SumsRaw, apkName)
        }

        val isNewerByCode = latestVersionCode > LinDoDiagnosticLogger.APP_BUILD
        val isNewerBySemver = buildInAssetName == null && manifestRawJson.isNullOrBlank() &&
            compareSemver(latestVersionName, LinDoDiagnosticLogger.APP_VERSION_NAME) > 0

        if (isNewerBySemver && latestVersionCode <= LinDoDiagnosticLogger.APP_BUILD) {
            latestVersionCode = LinDoDiagnosticLogger.APP_BUILD + 1
        }

        val hasUpdate = apkUrl.isNotBlank() && (isNewerByCode || isNewerBySemver)

        return MobileUpdateInfo(
            hasUpdate = hasUpdate,
            currentVersionName = LinDoDiagnosticLogger.APP_VERSION_NAME,
            currentVersionCode = LinDoDiagnosticLogger.APP_BUILD,
            latestVersionName = latestVersionName,
            latestVersionCode = latestVersionCode,
            apkUrl = apkUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            notes = notes,
            updatedAt = updatedAt
        )
    }

    internal fun extractSha256FromSums(sumsRaw: String, assetFileName: String): String {
        val target = assetFileName.trim().substringAfterLast('/')
        if (target.isEmpty()) return ""
        for (rawLine in sumsRaw.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val wsIdx = line.indexOfFirst { it.isWhitespace() }
            if (wsIdx != 64) continue
            val hash = line.substring(0, 64)
            if (!hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) continue
            val fileToken = line.substring(wsIdx)
                .trim()
                .removePrefix("*")
                .removePrefix("./")
                .substringAfterLast('/')
            if (fileToken.equals(target, ignoreCase = true)) {
                return hash.lowercase()
            }
        }
        return ""
    }

    private val markdownLinkRegex = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")
    private val prAuthorSuffixRegex = Regex("""\s+by\s+@\S+\s+in\s+https?://\S+""", RegexOption.IGNORE_CASE)
    private val bareUrlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val numberedBulletRegex = Regex("""^\d+\.\s+""")

    internal fun extractNotesFromMarkdown(body: String): List<String> =
        body.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("#") &&
                    !line.startsWith("```") &&
                    !line.startsWith("---") &&
                    !line.startsWith("***") &&
                    !line.contains("Full Changelog", ignoreCase = true) &&
                    !line.contains("What's Changed", ignoreCase = true) &&
                    !line.contains("New Contributors", ignoreCase = true) &&
                    !line.contains("made their first contribution", ignoreCase = true)
            }
            .mapNotNull { raw ->
                val cleaned = raw
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .removePrefix("• ")
                    .replace(numberedBulletRegex, "")
                    .replace(markdownLinkRegex, "$1")
                    .replace(prAuthorSuffixRegex, "")
                    .replace(bareUrlRegex, "")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
                    .trim()
                    .trimEnd(':', '：', '-', '·')
                    .trim()
                cleaned.takeIf {
                    it.isNotEmpty() && !it.startsWith("chore(release)", ignoreCase = true)
                }
            }
            .distinct()
            .take(6)
            .toList()

    private fun compareSemver(a: String, b: String): Int {
        fun parts(s: String): List<Int> =
            s.trim().trimStart('v', 'V').split('.').map { token ->
                token.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0..2) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        FileInputStream(file).use { fis ->
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
