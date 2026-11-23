package do_.linux.lindo.core.network

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class QrLoginPayload(
    val version: Int,
    val apiKey: String,
    val otp: String,
    val username: String,
    val expiresAtEpochMs: Long
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs > 0L && nowMs > expiresAtEpochMs
}

sealed class QrParseResult {
    data class Valid(val payload: QrLoginPayload) : QrParseResult()
    data class Invalid(val message: String) : QrParseResult()
}

/**
 * 跨设备扫码登录协议与编解码器（对齐 FluxDO / CFluxDo v2 协议）：
 * `lindo://qr-login?v=2&k=<api_key>&o=<otp>&u=<username>&exp=<unix_ms|0>`
 * - 兼容扫描 FluxDO / CFluxDo 生成的 `fluxdo://qr-login?...`；
 * - 待登录端用 `o` (OTP) 兑换出 `_t` 后，立即在后台调用 `/user-api-key/revoke.json` 销毁临时 `k`。
 */
object QrLoginManager {
    const val CURRENT_VERSION: Int = 2
    const val QR_APPLICATION_NAME: String = "Lindo QR Login"

    @Volatile
    private var pendingRevokeApiKey: String? = null
    private val revokeLock = Any()

    fun encodePayload(
        apiKey: String,
        otp: String,
        username: String,
        expiresAtEpochMs: Long = 0L
    ): String {
        fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")
        return "lindo://qr-login?v=$CURRENT_VERSION" +
            "&k=${enc(apiKey)}" +
            "&o=${enc(otp)}" +
            "&u=${enc(username)}" +
            "&exp=${expiresAtEpochMs.coerceAtLeast(0L)}"
    }

    private fun extractSchemeAndAction(uri: URI): Pair<String, String> {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val firstSeg = uri.path.orEmpty().trim('/').substringBefore('/').lowercase()
        val action = if (host.isNotEmpty()) host else firstSeg
        return scheme to action
    }

    fun isQrLoginUri(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val (scheme, action) = extractSchemeAndAction(uri)
        return scheme in setOf("lindo", "fluxdo") && action == "qr-login"
    }

    fun parsePayload(raw: String, nowMs: Long = System.currentTimeMillis()): QrParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return QrParseResult.Invalid("无效的登录二维码")
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return QrParseResult.Invalid("无效的登录二维码")
        val (scheme, action) = extractSchemeAndAction(uri)
        if (scheme !in setOf("lindo", "fluxdo") || action != "qr-login") {
            return QrParseResult.Invalid("不是 Lindo 登录二维码")
        }
        val params = LinkedHashMap<String, String>()
        val rawQuery = uri.rawQuery.orEmpty()
        if (rawQuery.isNotEmpty()) {
            for (part in rawQuery.split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = runCatching { URLDecoder.decode(part.substring(0, eq), "UTF-8") }.getOrNull() ?: continue
                val value = runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull() ?: continue
                params[key] = value
            }
        }
        val version = params["v"]?.trim()?.toIntOrNull()
            ?: return QrParseResult.Invalid("无效的登录二维码")
        if (version != CURRENT_VERSION) {
            return QrParseResult.Invalid("二维码版本不受支持，请升级客户端")
        }
        val apiKey = params["k"]?.trim().orEmpty()
        val otp = params["o"]?.trim().orEmpty()
        val username = params["u"]?.trim().orEmpty()
        val expMs = params["exp"]?.trim()?.ifEmpty { "0" }?.toLongOrNull()
        if (apiKey.isEmpty() || otp.isEmpty() || expMs == null || expMs < 0L) {
            return QrParseResult.Invalid("无效的登录二维码")
        }
        val payload = QrLoginPayload(
            version = version,
            apiKey = apiKey,
            otp = otp,
            username = username,
            expiresAtEpochMs = expMs
        )
        if (payload.isExpired(nowMs)) {
            return QrParseResult.Invalid("登录二维码已过期，请重新生成")
        }
        return QrParseResult.Valid(payload)
    }

    fun acceptScannedQrPayload(raw: String, nowMs: Long = System.currentTimeMillis()): Result<QrLoginPayload> {
        return when (val parsed = parsePayload(raw, nowMs)) {
            is QrParseResult.Invalid -> Result.failure(IllegalArgumentException(parsed.message))
            is QrParseResult.Valid -> {
                synchronized(revokeLock) {
                    pendingRevokeApiKey = parsed.payload.apiKey
                }
                LinDoDiagnosticLogger.info(
                    DiagnosticCategory.AUTH,
                    "QrLogin",
                    "QR login payload accepted (user=${parsed.payload.username.ifBlank { "unknown" }})"
                )
                UserApiKeyAuthManager.acceptExternalOtpLogin(parsed.payload.otp)
                Result.success(parsed.payload)
            }
        }
    }

    fun consumePendingRevokeApiKey(): String? = synchronized(revokeLock) {
        val key = pendingRevokeApiKey?.takeIf { it.isNotBlank() }
        pendingRevokeApiKey = null
        key
    }

    internal fun resetForTest() {
        synchronized(revokeLock) {
            pendingRevokeApiKey = null
        }
    }

    fun encodeQrBooleanMatrix(content: String, quietZone: Int = 2): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to quietZone.coerceAtLeast(0)
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(matrix.height) { y ->
            BooleanArray(matrix.width) { x -> matrix.get(x, y) }
        }
    }

    fun decodeQrFromArgbPixels(width: Int, height: Int, pixels: IntArray): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = QRCodeReader()
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitmaps = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            BinaryBitmap(HybridBinarizer(source.invert()))
        )
        for (bitmap in bitmaps) {
            val decoded = runCatching {
                reader.decode(bitmap, hints).text?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (decoded != null) return decoded
            reader.reset()
        }
        return null
    }
}
