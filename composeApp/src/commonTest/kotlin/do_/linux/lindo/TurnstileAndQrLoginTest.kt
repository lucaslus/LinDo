package do_.linux.lindo

import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.DesktopNetworkPolicy
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.NetworkRecoveryPolicy
import do_.linux.lindo.core.network.QrLoginManager
import do_.linux.lindo.core.network.QrParseResult
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnstileAndQrLoginTest {

    @BeforeTest
    fun resetState() {
        DesktopNetworkPolicy.enabled = false
        DesktopNetworkPolicy.verifying = false
        NetworkRecoveryPolicy.androidEnabled = false
        NetworkRecoveryPolicy.androidVerifying = false
        NetworkRecoveryPolicy.reads.clear()
        NetworkPolicyEngine.resetForTesting()
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onPersistPendingKey = null
        UserApiKeyAuthManager.onPersistQrClientId = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.onSyncCookieToPlatform = null
        UserApiKeyAuthManager.onPrepareFreshAuthExchange = null
        UserApiKeyAuthManager.onRequestClearancePrewarm = null
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.webViewJsonFetcher = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        QrLoginManager.resetForTest()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
    }

    @AfterTest
    fun cleanupState() = resetState()

    @Test
    fun qrLoginPayloadAndZxingMatrixRoundtripWorkOnLightAndDarkImages() {
        val lindoUri = QrLoginManager.encodePayload(
            apiKey = "key+1/2=3",
            otp = "otp_abc/xyz",
            username = "lucas",
            expiresAtEpochMs = 0L
        )
        assertTrue(QrLoginManager.isQrLoginUri(lindoUri))
        val parsedLindo = QrLoginManager.parsePayload(lindoUri, nowMs = 1_700_000_000_000L)
        assertTrue(parsedLindo is QrParseResult.Valid)
        assertEquals(2, parsedLindo.payload.version)
        assertEquals("key+1/2=3", parsedLindo.payload.apiKey)
        assertEquals("otp_abc/xyz", parsedLindo.payload.otp)
        assertEquals("lucas", parsedLindo.payload.username)

        val fluxdoUri = lindoUri.replaceFirst("lindo://", "fluxdo://")
        assertTrue(QrLoginManager.isQrLoginUri(fluxdoUri))
        assertTrue(QrLoginManager.parsePayload(fluxdoUri, nowMs = 1_700_000_000_000L) is QrParseResult.Valid)

        assertTrue(QrLoginManager.parsePayload("lindo://qr-login?v=1&k=a&o=b&u=lucas&exp=0") is QrParseResult.Invalid)
        assertTrue(QrLoginManager.parsePayload("lindo://qr-login?v=2&o=b&u=lucas&exp=0") is QrParseResult.Invalid)
        assertTrue(QrLoginManager.parsePayload("lindo://qr-login?v=2&k=a&u=lucas&exp=0") is QrParseResult.Invalid)
        assertTrue(
            QrLoginManager.parsePayload(
                "lindo://qr-login?v=2&k=a&o=b&u=lucas&exp=1000",
                nowMs = 2000L
            ) is QrParseResult.Invalid
        )

        val matrix = QrLoginManager.encodeQrBooleanMatrix(lindoUri, quietZone = 2)
        val size = matrix.size
        val scale = 4
        val width = size * scale
        val height = width

        fun renderPixels(foreground: Int, background: Int): IntArray {
            val px = IntArray(width * height)
            for (y in 0 until height) {
                val my = y / scale
                for (x in 0 until width) {
                    val mx = x / scale
                    px[y * width + x] = if (matrix[my][mx]) foreground else background
                }
            }
            return px
        }

        val lightDecoded = QrLoginManager.decodeQrFromArgbPixels(
            width = width,
            height = height,
            pixels = renderPixels(foreground = 0xFF000000.toInt(), background = 0xFFFFFFFF.toInt())
        )
        assertEquals(lindoUri, lightDecoded)

        val darkInvertedDecoded = QrLoginManager.decodeQrFromArgbPixels(
            width = width,
            height = height,
            pixels = renderPixels(foreground = 0xFFFFFFFF.toInt(), background = 0xFF141414.toInt())
        )
        assertEquals(lindoUri, darkInvertedDecoded)
    }

    @Test
    fun crossDeviceQrLoginGeneratesUriAcceptsOtpAndRevokesTempKeyInBackground() = runBlocking {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.recordWebSessionLogin(
            username = "lucas",
            displayName = "Lucas",
            avatarUrl = "",
            trustLevel = 3,
            userId = 42
        )

        val tempApiKey = "qr_temp_api_key_42"
        val issuedOtp = "qr_one_time_password_99"
        val revokeHeadersDeferred = CompletableDeferred<Map<String, String>>()

        fun encryptWithPem(pem: String, plain: String): String {
            val base64Body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace(Regex("\\s+"), "")
            val keyBytes = Base64.getDecoder().decode(base64Body)
            val pubKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, pubKey)
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        }

        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, headers ->
            when {
                url == "https://linux.do/user-api-key.json" && method == "POST" -> {
                    val pem = formParams?.get("public_key").orEmpty()
                    val nonce = formParams?.get("nonce").orEmpty()
                    val payloadB64 = encryptWithPem(pem, """{"key":"$tempApiKey","nonce":"$nonce"}""")
                    val otpB64 = encryptWithPem(pem, issuedOtp)
                    val redirectUrl = "discourse://auth_redirect?payload=${
                        URLEncoder.encode(payloadB64, "UTF-8")
                    }&oneTimePassword=${URLEncoder.encode(otpB64, "UTF-8")}"
                    200 to """{"redirect_url":"$redirectUrl"}"""
                }
                url.contains("/session/otp/$issuedOtp") && method == "POST" -> {
                    CookieSentinel.ingestRawCookieString("_t=qr_redeemed_session_t", domain = "linux.do", hostOnly = true)
                    200 to "<html>Welcome</html>"
                }
                url.endsWith("/session/current.json") && method == "GET" -> {
                    200 to """{"current_user":{"id":42,"username":"lucas","name":"Lucas","avatar_template":"","trust_level":3}}"""
                }
                url == "https://linux.do/user-api-key/revoke.json" && method == "POST" -> {
                    revokeHeadersDeferred.complete(headers)
                    200 to """{"success":"OK"}"""
                }
                else -> 200 to "{}"
            }
        }

        val generated = LinuxDoRepository.createCrossDeviceQrLoginUri().getOrThrow()
        val (qrUri, generatedPayload) = generated
        assertEquals(tempApiKey, generatedPayload.apiKey)
        assertEquals(issuedOtp, generatedPayload.otp)
        assertEquals("lucas", generatedPayload.username)

        // 模拟在另一台未登录设备上扫描该二维码
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()

        val acceptedPayload = QrLoginManager.acceptScannedQrPayload(qrUri).getOrThrow()
        assertEquals(issuedOtp, acceptedPayload.otp)
        assertTrue(LinuxDoRepository.redeemOtpSilently(acceptedPayload.otp))

        val revokeHeaders = withTimeout(5_000L) {
            revokeHeadersDeferred.await()
        }
        assertEquals(tempApiKey, revokeHeaders["User-Api-Key"])
        assertEquals("true", revokeHeaders["Discourse-Background"])
        assertNull(QrLoginManager.consumePendingRevokeApiKey())
    }

    @Test
    fun backgroundSideEffectWritesSendBackgroundHeaderAndDoNotTripForegroundChallenge() = runTest {
        NetworkRecoveryPolicy.androidEnabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        val recordedCalls = mutableListOf<Triple<String, String, Map<String, String>>>()

        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, _, headers ->
            recordedCalls += Triple(url, method, headers)
            403 to "${CF_MITIGATED_CHALLENGE_PREFIX}<html>challenge</html>"
        }

        val readResult = LinuxDoRepository.markSingleNotificationRead(777)
        assertFalse(readResult)

        val draftOutcome = runCatching {
            LinuxDoRepository.deleteCloudDraft(draftKey = "topic_42", sequence = 3, background = true)
        }
        assertTrue(draftOutcome.isFailure)

        val notifCall = recordedCalls.firstOrNull { it.first.contains("/notifications/mark-read.json") }
        assertNotNull(notifCall)
        assertEquals("PUT", notifCall.second)
        assertEquals("true", notifCall.third["Discourse-Background"])

        val draftDeleteCall = recordedCalls.firstOrNull {
            it.first.contains("/drafts/topic_42.json") && it.second == "DELETE"
        }
        assertNotNull(draftDeleteCall)
        assertEquals("true", draftDeleteCall.third["Discourse-Background"])

        assertFalse(LinuxDoRepository.isChallengeCooldownActive())
        assertFalse(NetworkRecoveryPolicy.androidVerifying)
    }

    @Test
    fun diagnosticLoggerRedactsQrSecrets() {
        val rawQr = "lindo://qr-login?v=2&k=secret_api_key_value&o=secret_otp_value&u=lucas&exp=0"
        val sanitizedUrl = LinDoDiagnosticLogger.sanitizeUrl(rawQr)
        val redacted = LinDoDiagnosticLogger.redactSecrets(rawQr)

        assertFalse(sanitizedUrl.contains("secret_api_key_value"))
        assertFalse(sanitizedUrl.contains("secret_otp_value"))
        assertTrue(sanitizedUrl.contains("k=<REDACTED>"))
        assertTrue(sanitizedUrl.contains("o=<REDACTED>"))
        assertTrue(sanitizedUrl.contains("u=lucas"))

        assertFalse(redacted.contains("secret_api_key_value"))
        assertFalse(redacted.contains("secret_otp_value"))
        assertTrue(redacted.contains("k=<REDACTED>"))
        assertTrue(redacted.contains("o=<REDACTED>"))
    }
}
