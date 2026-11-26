package do_.linux.lindo

import do_.linux.lindo.core.connect.ConnectMetricKind
import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.NetworkPolicyEngine
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.isFakeOrTunIpv4Bytes
import do_.linux.lindo.core.network.isFakeOrTunIpv4String
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Wave1ParityRegressionTest {

    @BeforeTest
    fun setUp() {
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
        NetworkPolicyEngine.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        NetworkPolicyEngine.resetForTesting()
    }

    @Test
    fun p0_7_discourseSolvedParsingAndAcceptUnacceptEndpoints() = runTest {
        val calls = CopyOnWriteArrayList<Triple<String, String, Map<String, String>?>>()
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.recordWebSessionLogin("alice", "Alice", "", trustLevel = 3, userId = 10)
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, formParams, _ ->
            calls.add(Triple(method, url, formParams))
            when {
                url.contains("/t/777.json") -> 200 to """
                    {
                      "id": 777,
                      "title": "如何配置 ECH 路由？",
                      "category_id": 4,
                      "posts_count": 3,
                      "has_accepted_answer": true,
                      "accepted_answer": { "post_number": 2, "username": "bob" },
                      "post_stream": {
                        "stream": [7771, 7772, 7773],
                        "posts": [
                          {
                            "id": 7771,
                            "post_number": 1,
                            "user_id": 10,
                            "username": "alice",
                            "cooked": "<p>求助配置方法</p>",
                            "accepted_answer": false,
                            "can_accept_answer": false
                          },
                          {
                            "id": 7772,
                            "post_number": 2,
                            "user_id": 20,
                            "username": "bob",
                            "cooked": "<p>打开智能自动模式即可</p>",
                            "accepted_answer": true,
                            "can_accept_answer": false,
                            "can_unaccept_answer": true
                          },
                          {
                            "id": 7773,
                            "post_number": 3,
                            "user_id": 30,
                            "username": "charlie",
                            "cooked": "<p>也可以手动设置上游</p>",
                            "accepted_answer": false,
                            "can_accept_answer": true,
                            "can_unaccept_answer": false
                          }
                        ]
                      }
                    }
                """.trimIndent()
                url.contains("/solution/accept.json") && method == "POST" -> 200 to """{"success":"OK"}"""
                url.contains("/solution/unaccept.json") && method == "POST" -> 200 to """{"success":"OK"}"""
                else -> 404 to "{}"
            }
        }

        val detail = LinuxDoRepository.fetchTopicDetail(777)
        assertTrue(detail.hasAcceptedAnswer)
        assertEquals(2, detail.acceptedAnswerPostNumber)
        assertEquals(3, detail.posts.size)
        assertFalse(detail.posts[0].acceptedAnswer)
        assertFalse(detail.posts[0].canAcceptAnswer)
        assertTrue(detail.posts[1].acceptedAnswer)
        assertTrue(detail.posts[1].canAcceptAnswer, "can_unaccept_answer=true must expose unaccept action")
        assertFalse(detail.posts[2].acceptedAnswer)
        assertTrue(detail.posts[2].canAcceptAnswer)

        assertTrue(LinuxDoRepository.acceptPostSolution(7773))
        assertTrue(LinuxDoRepository.unacceptPostSolution(7772))
        assertTrue(calls.any { it.first == "POST" && it.second == "https://linux.do/solution/accept.json" && it.third?.get("id") == "7773" })
        assertTrue(calls.any { it.first == "POST" && it.second == "https://linux.do/solution/unaccept.json" && it.third?.get("id") == "7772" })
    }

    @Test
    fun p1_12_connectStatsParserExtractsKindsComplianceCardsAndFooterSummary() {
        val modernHtml = """
            <div class="card">
              <h2 class="card-title">信任级别 3 的要求</h2>
              <div class="card-subtitle">过去 100 天内的数据</div>
              <span class="badge">已达到</span>
              <div class="tl3-ring">
                <div class="tl3-ring-circle met" style="--val: 62; --max: 50"></div>
                <div class="tl3-ring-label">访问天数</div>
              </div>
              <div class="tl3-bar-item">
                <span class="tl3-bar-label">回复话题</span>
                <span class="tl3-bar-nums met">18/10</span>
                <div class="tl3-bar-fill met" style="--val: 18; --max: 10"></div>
              </div>
              <div class="tl3-quota-card met">
                <span class="tl3-quota-label">被举报帖子</span>
                <span class="tl3-quota-nums">1 / 5</span>
              </div>
              <div class="tl3-status-grid">
                <div class="tl3-status-card met">
                  <span class="tl3-status-label">被禁言</span>
                  <span class="tl3-status-sub">过去 6 个月</span>
                  <span class="tl3-status-val">0</span>
                </div>
                <div class="tl3-status-card unmet">
                  <span class="tl3-status-label">被封禁</span>
                  <span class="tl3-status-sub">过去 6 个月</span>
                  <span class="tl3-status-val">1</span>
                </div>
              </div>
              <p class="card-footer">恭喜，您已符合信任级别 3 要求。</p>
            </div>
        """.trimIndent()

        val report = assertNotNull(ConnectStatsParser.parseConnectHtml(modernHtml))
        assertTrue(report.isLiveConnectData)
        assertEquals(ConnectMetricKind.RING, report.metrics.first { it.label == "访问天数" }.kind)
        assertEquals(ConnectMetricKind.BAR, report.metrics.first { it.label == "回复话题" }.kind)
        assertEquals(ConnectMetricKind.QUOTA, report.metrics.first { it.label == "被举报帖子" }.kind)

        val muted = report.metrics.first { it.label == "被禁言" }
        assertEquals(ConnectMetricKind.STATUS, muted.kind)
        assertEquals(0, muted.currentValue)
        assertEquals("过去 6 个月", muted.sublabel)
        assertTrue(muted.isMet)

        val suspended = report.metrics.first { it.label == "被封禁" }
        assertEquals(ConnectMetricKind.STATUS, suspended.kind)
        assertEquals(1, suspended.currentValue)
        assertEquals("过去 6 个月", suspended.sublabel)
        assertFalse(suspended.isMet)

        assertNotNull(report.footerSummary)
        assertTrue(report.footerSummary!!.contains("信任级别 3"))

        // Classic table fallback must preserve 禁言 / 封禁 as STATUS and 举报 as QUOTA
        val tableHtml = """
            <h2>信任等级 3 要求</h2>
            <table>
              <tr><td>访问天数</td><td class="green">55</td><td>50</td></tr>
              <tr><td>被举报帖子</td><td>2</td><td>5</td></tr>
              <tr><td>过去 6 个月内被禁言</td><td>0</td><td>0</td></tr>
              <tr><td>过去 6 个月内被封禁</td><td class="red">1</td><td>0</td></tr>
            </table>
        """.trimIndent()
        val tableReport = assertNotNull(ConnectStatsParser.parseConnectHtml(tableHtml))
        assertEquals(ConnectMetricKind.QUOTA, tableReport.metrics.first { it.label == "被举报帖子" }.kind)
        assertTrue(tableReport.metrics.first { it.label == "被举报帖子" }.isMet)
        val tblMuted = tableReport.metrics.first { it.label == "被禁言" }
        assertEquals(ConnectMetricKind.STATUS, tblMuted.kind)
        assertTrue(tblMuted.isMet)
        val tblSuspended = tableReport.metrics.first { it.label == "被封禁" }
        assertEquals(ConnectMetricKind.STATUS, tblSuspended.kind)
        assertFalse(tblSuspended.isMet)

        // Cumulative fallback keeps STAT kind & isQualified=false
        val fallback = ConnectStatsParser.buildRealProfileConnectReport("alice", 3, 120, 400, 1000, 12, 80, 300, 500, 7200)
        assertFalse(fallback.isLiveConnectData)
        assertFalse(fallback.isQualified)
        assertTrue(fallback.metrics.all { it.kind == ConnectMetricKind.STAT })
    }

    @Test
    fun publicUserCardPreservesStatusEmojiDescriptionTrustLevelAndWebsite() = runTest {
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
            when {
                url.contains("/u/alice.json") -> 200 to """
                    {
                      "user": {
                        "id": 42,
                        "username": "alice",
                        "name": "Alice Lin",
                        "trust_level": 3,
                        "website": "https://alice.dev",
                        "website_name": "alice.dev",
                        "status": {
                          "emoji": "rocket",
                          "description": "专注内核开发"
                        }
                      }
                    }
                """.trimIndent()
                url.contains("/u/alice/summary.json") -> 200 to """{"user_summary":{"days_visited":88,"solved_count":9}}"""
                else -> 404 to "{}"
            }
        }

        val card = assertNotNull(LinuxDoRepository.fetchPublicUserCard("alice"))
        assertEquals(3, card.trustLevel)
        assertEquals("rocket", card.statusEmoji)
        assertEquals("专注内核开发", card.statusText)
        assertEquals("https://alice.dev", card.websiteUrl)
        assertEquals("alice.dev", card.websiteName)
    }

    @Test
    fun p1_21_networkPolicyParityLogoutRevokeRetryAfterHoursDaysRfc3339AndFakeIp17219() {
        // 1. POST /user-api-key/revoke exempt from session recovery
        assertTrue(NetworkPolicyEngine.isLogoutExemptFromSessionRecovery("DELETE", "https://linux.do/session/alice"))
        assertTrue(NetworkPolicyEngine.isLogoutExemptFromSessionRecovery("POST", "https://linux.do/user-api-key/revoke"))
        assertTrue(NetworkPolicyEngine.isLogoutExemptFromSessionRecovery("post", "https://linux.do/user-api-key/revoke.json"))
        assertFalse(NetworkPolicyEngine.isLogoutExemptFromSessionRecovery("GET", "https://linux.do/user-api-key/revoke"))
        assertFalse(NetworkPolicyEngine.isLogoutExemptFromSessionRecovery("POST", "https://linux.do/posts.json"))

        // 2. Retry-After hours / days (CN & EN) and RFC 3339 timestamp
        val nowMs = 1_700_000_000_000L // 2023-11-14T22:13:20Z
        assertEquals(7_200_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, null, "请等待 2 小时后再试", nowMs))
        assertEquals(86_400_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, null, "请等待 1 天后再试", nowMs))
        assertEquals(10_800_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, null, "操作太频繁，3 小时后再试", nowMs))
        assertEquals(7_200_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, null, "Please try again in 2 hours", nowMs))
        assertEquals(86_400_000L, NetworkPolicyEngine.parseRetryAfterMillis(null, null, "Please wait 1 day before retrying", nowMs))
        assertEquals(60_000L, NetworkPolicyEngine.parseRetryAfterMillis("2023-11-14T22:14:20Z", null, null, nowMs))
        assertEquals(120_000L, NetworkPolicyEngine.parseRetryAfterMillis("2023-11-15T06:15:20+08:00", null, null, nowMs))

        // 3. 172.19.0.0/16 Fake-IP / TUN detection alongside 198.18.0.0/15
        assertTrue(isFakeOrTunIpv4Bytes(byteArrayOf(172.toByte(), 19, 0, 1)))
        assertTrue(isFakeOrTunIpv4Bytes(byteArrayOf(172.toByte(), 19, 255.toByte(), 254.toByte())))
        assertFalse(isFakeOrTunIpv4Bytes(byteArrayOf(172.toByte(), 18, 0, 1)))
        assertFalse(isFakeOrTunIpv4Bytes(byteArrayOf(172.toByte(), 20, 0, 1)))
        assertTrue(isFakeOrTunIpv4String("172.19.0.1"))
        assertTrue(isFakeOrTunIpv4String("172.19.255.254"))
        assertFalse(isFakeOrTunIpv4String("172.18.0.1"))
        assertFalse(isFakeOrTunIpv4String("172.20.0.1"))
        assertTrue(isFakeOrTunIpv4String("198.18.1.1"))
    }
}
