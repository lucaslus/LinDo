package do_.linux.lindo.ui

import androidx.compose.ui.test.*
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.network.*
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalTestApi::class)
class NotificationJumpAndSessionExpiryTest {

    private val includeOpBoost = AtomicBoolean(false)
    private val topicFetchCount = AtomicInteger(0)

    @BeforeTest
    fun setUp() {
        resetGlobals()
        includeOpBoost.set(false)
        topicFetchCount.set(0)
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ -> handleHttp(url) }
    }

    @AfterTest
    fun tearDown() {
        resetGlobals()
    }

    private fun resetGlobals() {
        SavedAccounts.resetForTest()
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.webViewBinaryFetcher = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.clearExpiredSessionNotice()
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LocalComposerDrafts.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        LinuxDoRepository.clearAllLocalCaches()
        ScreenTrackEngine.resetSessionState()
        UserPresenceService.setAppForeground(true)
    }

    private fun <T> edt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var res: Result<T>? = null
        SwingUtilities.invokeAndWait { res = runCatching(block) }
        return res!!.getOrThrow()
    }

    @Test
    fun notificationClickRefreshesAlreadyOpenTopicAndShowsNewBoostAndSessionExpiryShowsBanner() =
        runDesktopComposeUiTest(width = 1100, height = 780) {
            SavedAccounts.configure(null) {}
            UserApiKeyAuthManager.onSessionAuthenticated(
                AuthenticatedUserProfile(userId = 88, username = "jedilus", displayName = "Jedilus", trustLevel = 3),
                rawCookies = "_t=valid_test_session"
            )
            setContent { LinDoApp(systemAnimationsEnabled = false) }

            waitUntil(timeoutMillis = 5_000L) {
                edt { onAllNodesWithText("喜提新车，在L站跟大家分享", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            // Wide layout auto-opens Topic 501 at Floor #1 (initial fetch #1 without boost)
            waitUntil(timeoutMillis = 5_000L) {
                edt { onAllNodesWithText("喜提新车发个大红包", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            val initialFetches = topicFetchCount.get()
            assertTrue(initialFetches >= 1)

            // Make posts tall enough that Floor #5 is below the fold unless jumped to directly
            // OP now boosts user's reply (#5), and user switches to Messages -> clicks notification
            includeOpBoost.set(true)
            edt { onAllNodesWithText("消息").onFirst().performClick() }
            waitUntil(timeoutMillis = 5_000L) {
                edt { onAllNodesWithText("喜提新车，在L站跟大家分享", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            edt { onAllNodesWithText("喜提新车，在L站跟大家分享", substring = true).onFirst().performClick() }

            // Notification click must re-fetch Topic 501, land directly on Floor #5, and display OP's new boost phrase
            waitUntil(timeoutMillis = 5_000L) {
                edt { onAllNodesWithText("感谢老哥沾喜气", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            edt { onAllNodesWithText("恭喜提车大吉", substring = true).onFirst().assertIsDisplayed() }
            assertTrue(topicFetchCount.get() > initialFetches, "Notification jump must refresh topic detail")

            // Passive session expiry preserves reading context and shows the unified reminder bar
            edt { UserApiKeyAuthManager.handleServerAuthInvalidated() }
            waitUntil(timeoutMillis = 5_000L) {
                edt { onAllNodesWithText("@jedilus 登录已过期，当前为访客模式", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            edt {
                onNodeWithText("重新登录").assertIsDisplayed()
                // Public topic title remains visible instead of wiping back to welcome screen
                onAllNodesWithText("喜提新车，在L站跟大家分享", substring = true).onFirst().assertIsDisplayed()
            }
        }

    private fun handleHttp(rawUrl: String): Pair<Int, String> {
        val path = URI(rawUrl).path ?: "/"
        val nowIso = "2026-11-16T07:30:00.000Z"
        return when {
            path == "/session/current.json" -> 200 to buildJsonObject {
                putJsonObject("current_user") {
                    put("id", 88); put("username", "jedilus"); put("name", "Jedilus")
                    put("trust_level", 3); put("unread_notifications", 1)
                }
            }.toString()

            path == "/categories.json" || path == "/site.json" -> 200 to buildJsonObject {
                putJsonObject("category_list") {
                    putJsonArray("categories") {
                        add(buildJsonObject { put("id", 11); put("name", "搞七捻三"); put("slug", "gossip"); put("color", "22C55E"); put("read_restricted", false) })
                    }
                }
            }.toString()

            path == "/notifications.json" -> 200 to buildJsonObject {
                putJsonArray("notifications") {
                    add(buildJsonObject {
                        put("id", 9001)
                        put("notification_type", 6)
                        put("read", false)
                        put("created_at", nowIso)
                        put("topic_id", 501)
                        put("post_number", 5)
                        put("fancy_title", "喜提新车，在L站跟大家分享")
                        putJsonObject("data") {
                            put("topic_title", "喜提新车，在L站跟大家分享")
                            put("display_username", "car_owner")
                        }
                    })
                }
            }.toString()

            path == "/latest.json" -> 200 to buildJsonObject {
                putJsonArray("users") {
                    add(buildJsonObject { put("id", 10); put("username", "car_owner"); put("name", "车主楼主"); put("avatar_template", "/letter_avatar_proxy/v4/letter/c/3b82f6/{size}.png") })
                }
                putJsonObject("topic_list") {
                    putJsonArray("topics") {
                        add(buildJsonObject {
                            put("id", 501); put("title", "喜提新车，在L站跟大家分享"); put("fancy_title", "喜提新车，在L站跟大家分享")
                            put("category_id", 11); put("posts_count", 5); put("highest_post_number", 5); put("reply_count", 4)
                            put("views", 680); put("like_count", 45); put("created_at", nowIso); put("last_posted_at", nowIso)
                            putJsonArray("posters") { add(buildJsonObject { put("extras", "latest"); put("user_id", 10) }) }
                        })
                    }
                }
            }.toString()

            path.startsWith("/t/501") -> {
                topicFetchCount.incrementAndGet()
                200 to buildJsonObject {
                    put("id", 501); put("title", "喜提新车，在L站跟大家分享"); put("category_id", 11)
                    put("posts_count", 5); put("highest_post_number", 5); put("views", 680); put("like_count", 45)
                    putJsonObject("details") {
                        put("notification_level", 1)
                        putJsonObject("created_by") { put("id", 10); put("username", "car_owner") }
                    }
                    putJsonObject("post_stream") {
                        putJsonArray("stream") { for (i in 1..5) add(JsonPrimitive(5000 + i)) }
                        putJsonArray("posts") {
                            add(buildJsonObject {
                                put("id", 5001); put("topic_id", 501); put("post_number", 1); put("user_id", 10)
                                put("username", "car_owner"); put("name", "车主楼主")
                                put("cooked", "<p>🧧 喜提新车发个大红包沾沾喜气！</p>")
                                put("post_type", 1); put("created_at", nowIso)
                            })
                            for (p in 2..4) {
                                add(buildJsonObject {
                                    put("id", 5000 + p); put("topic_id", 501); put("post_number", p); put("user_id", 10 + p)
                                    put("username", "user_$p"); put("name", "User $p")
                                    put("cooked", "<p>前排恭喜第 $p 楼！</p>")
                                    put("post_type", 1); put("created_at", nowIso)
                                })
                            }
                            add(buildJsonObject {
                                put("id", 5005); put("topic_id", 501); put("post_number", 5); put("user_id", 88)
                                put("username", "jedilus"); put("name", "Jedilus"); put("yours", true)
                                put("cooked", "<p>恭喜提车大吉，一路平安！</p>")
                                put("post_type", 1); put("created_at", nowIso)
                                putJsonArray("boosts") {
                                    if (includeOpBoost.get()) {
                                        add(buildJsonObject {
                                            put("id", 77701L)
                                            put("cooked", "<p>感谢老哥沾喜气</p>")
                                            putJsonObject("user") {
                                                put("id", 10); put("username", "car_owner"); put("name", "车主楼主")
                                            }
                                        })
                                    }
                                }
                            })
                        }
                    }
                }.toString()
            }

            else -> 200 to "{}"
        }
    }
}
