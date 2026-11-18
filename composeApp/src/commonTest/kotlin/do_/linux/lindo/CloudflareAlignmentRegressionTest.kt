package do_.linux.lindo

import do_.linux.lindo.core.network.CfClearanceAuthority
import do_.linux.lindo.core.network.ClientHints
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.SmartDohDns
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareAlignmentRegressionTest {

    @BeforeTest
    fun reset() {
        CookieSentinel.clearAll()
        CfClearanceAuthority.reset()
        SmartDohDns.vpnOrProxyDetector = null
        SmartDohDns.clearCache()
    }

    @AfterTest
    fun cleanup() {
        reset()
    }

    @Test
    fun cfAuthorityProtectsStickyIncumbentAndPromotesFreshClearanceAfterChallenge() {
        CfClearanceAuthority.confirmHealthy("live_cf_v1", nowMs = 1_700_000_000_000L)

        // Even when CookieManager returns a stray host-only cookie after the live incumbent, sticky incumbent wins
        CookieSentinel.syncFromAuthoritativeBrowserCookieString(
            "_t=tok1; cf_clearance=live_cf_v1; cf_clearance=stray_old_cf"
        )
        assertEquals(
            "live_cf_v1",
            CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"]?.value
        )

        // Hitting a challenge immediately invalidates live_cf_v1 so it cannot win again
        CfClearanceAuthority.noteChallenged("live_cf_v1")
        assertNull(CfClearanceAuthority.incumbent)
        assertEquals("live_cf_v1", CfClearanceAuthority.challenged)

        // After user completes verification, even if live_cf_v1 lingers alongside fresh_cf_v2, fresh_cf_v2 wins!
        CookieSentinel.syncFromAuthoritativeBrowserCookieString(
            "_t=tok1; cf_clearance=live_cf_v1; cf_clearance=fresh_cf_v2",
            verified = true
        )
        assertEquals(
            "fresh_cf_v2",
            CookieSentinel.resolveWinningCookies(targetHost = "linux.do")["cf_clearance"]?.value
        )
        assertNull(CfClearanceAuthority.challenged)
        assertEquals("fresh_cf_v2", CfClearanceAuthority.incumbent)

        // Even after verified clears active challenged, historical challenged tokens remain blocked in bounded history
        assertTrue(CfClearanceAuthority.isChallenged("live_cf_v1"))
        CfClearanceAuthority.noteChallenged("fresh_cf_v2")
        assertTrue(CfClearanceAuthority.isChallenged("live_cf_v1"))
        assertTrue(CfClearanceAuthority.isChallenged("fresh_cf_v2"))
        assertNull(CfClearanceAuthority.selectWinner(listOf("live_cf_v1", "fresh_cf_v2")))

        // HTTP 429 with challenge prefix/body must classify as RateLimited, NEVER Verification
        val rateLimitErr = kotlin.test.assertFailsWith<do_.linux.lindo.core.network.ApiReadException> {
            do_.linux.lindo.core.network.readApiObject(
                429 to "${do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX}<html><title>Just a moment...</title></html>"
            )
        }
        assertEquals(do_.linux.lindo.core.network.ApiFailureKind.RateLimited, rateLimitErr.kind)
    }

    @Test
    fun clientHintsMatchChromiumGreaseAlgorithmAndOmitSafari() {
        val chromeAndroidUa =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"
        val ch = ClientHints.fromUserAgent(chromeAndroidUa)
        assertEquals("?1", ch["sec-ch-ua-mobile"])
        assertEquals("\"Android\"", ch["sec-ch-ua-platform"])
        val secChUa = ch["sec-ch-ua"].orEmpty()
        assertTrue(secChUa.contains("\"Chromium\";v=\"131\""))
        assertTrue(secChUa.contains("\"Google Chrome\";v=\"131\""))
        assertTrue(secChUa.contains("Brand\""))

        val webViewUa =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP3A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36"
        val wvCh = ClientHints.fromUserAgent(webViewUa)
        assertTrue(wvCh["sec-ch-ua"].orEmpty().contains("\"Android WebView\";v=\"131\""))

        val safariMacUa =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15"
        assertTrue(ClientHints.fromUserAgent(safariMacUa).isEmpty())
    }

    @Test
    fun smartDohDetectsPoisonedIpsAndParsesCleanJsonAnswersWhileRespectingVpn() {
        assertTrue(SmartDohDns.isPoisonedDirectIp(InetAddress.getByName("127.0.0.1")))
        assertTrue(SmartDohDns.isPoisonedDirectIp(InetAddress.getByName("0.0.0.0")))
        assertTrue(SmartDohDns.isPoisonedDirectIp(InetAddress.getByName("198.18.0.12")))
        assertFalse(SmartDohDns.isPoisonedDirectIp(InetAddress.getByName("104.21.32.10")))

        SmartDohDns.vpnOrProxyDetector = { true }
        assertTrue(SmartDohDns.isProxyOrVpnActive())
        SmartDohDns.vpnOrProxyDetector = { false }

        val sampleDoh = """
            {
              "Status": 0,
              "Answer": [
                { "name": "cdn.ldstatic.com.", "type": 5, "TTL": 300, "data": "cdn.ldstatic.com.cdn.cloudflare.net." },
                { "name": "cdn.ldstatic.com.", "type": 1, "TTL": 180, "data": "104.21.32.10" },
                { "name": "cdn.ldstatic.com.", "type": 1, "TTL": 180, "data": "127.0.0.1" }
              ]
            }
        """.trimIndent()
        val (addrs, ttlMs) = SmartDohDns.parseDohJsonAnswers("cdn.ldstatic.com", sampleDoh)
        assertEquals(1, addrs.size)
        assertEquals("104.21.32.10", addrs.single().hostAddress)
        assertEquals(180_000L, ttlMs)
    }
}
