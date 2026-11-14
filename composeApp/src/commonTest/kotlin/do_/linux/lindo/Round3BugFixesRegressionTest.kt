package do_.linux.lindo

import androidx.compose.ui.graphics.Color
import do_.linux.lindo.core.compose.ComposerAction
import do_.linux.lindo.core.compose.ComposerDraft
import do_.linux.lindo.core.compose.ComposerDraftSession
import do_.linux.lindo.core.compose.LocalComposerDrafts
import do_.linux.lindo.core.compose.toCloudItem
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.PrivateMessageSendOutcome
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserFullProfileSummary
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.model.resolveJumpPostIndex
import do_.linux.lindo.core.network.ApiFailureKind
import do_.linux.lindo.core.network.ApiMutationException
import do_.linux.lindo.core.network.ApiReadException
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.CF_MITIGATED_CHALLENGE_PREFIX
import do_.linux.lindo.core.network.CloudDraftItem
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.PlatformMediaPicker
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.network.looksLikeCloudflareChallenge
import do_.linux.lindo.core.network.readApiObject
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.MarkdownPreview
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.core.site.LinuxDoSiteRules
import do_.linux.lindo.ui.FeedSortFilter
import do_.linux.lindo.ui.DEFAULT_SPLIT_PANE_FRACTION
import do_.linux.lindo.ui.MAX_SPLIT_PANE_FRACTION
import do_.linux.lindo.ui.MIN_LEFT_PANE_WIDTH_DP
import do_.linux.lindo.ui.MIN_RIGHT_PANE_WIDTH_DP
import do_.linux.lindo.ui.MIN_SPLIT_PANE_FRACTION
import do_.linux.lindo.ui.buildWorkbenchPollBbCode
import do_.linux.lindo.ui.clampSplitPaneFraction
import do_.linux.lindo.ui.computeSplitPaneBoundsDp
import do_.linux.lindo.ui.computeSplitPaneLeftWidthDp
import do_.linux.lindo.ui.computeUpdatedSplitFractionAfterDrag
import do_.linux.lindo.ui.isTransientRecoverableLoadError
import do_.linux.lindo.ui.shouldUseWideSplitLayout
import io.ktor.client.request.get
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.insertSnippetAtCursorWithMentionBoundary
import do_.linux.lindo.ui.mergeCloudAndLocalDraftLists
import do_.linux.lindo.ui.optimizePanguSpacingText
import do_.linux.lindo.ui.parseSmartTopicUrlTarget
import do_.linux.lindo.ui.resolveDraftKindLabel
import do_.linux.lindo.ui.resolveNotificationIcon
import do_.linux.lindo.ui.stripWebOneQueryParam
import do_.linux.lindo.ui.toggleMarkdownLinePrefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Round3BugFixesRegressionTest {

    private var savedHttpExecutor = UserApiKeyAuthManager.webViewHttpExecutor
    private var savedUploadExecutor = PlatformMediaPicker.webViewUploadExecutor
    private var savedAuthCallback = UserApiKeyAuthManager.onAuthStateChanged
    private var savedPersistSession = UserApiKeyAuthManager.onPersistSession
    private var savedLogoutClear = UserApiKeyAuthManager.onLogoutPlatformClear

    @BeforeTest
    fun setUp() {
        savedHttpExecutor = UserApiKeyAuthManager.webViewHttpExecutor
        savedUploadExecutor = PlatformMediaPicker.webViewUploadExecutor
        savedAuthCallback = UserApiKeyAuthManager.onAuthStateChanged
        savedPersistSession = UserApiKeyAuthManager.onPersistSession
        savedLogoutClear = UserApiKeyAuthManager.onLogoutPlatformClear

        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        ScreenTrackEngine.resetForTest()
        UserPresenceService.setAppForeground(true)
    }

    @AfterTest
    fun tearDown() {
        UserApiKeyAuthManager.webViewHttpExecutor = savedHttpExecutor
        PlatformMediaPicker.webViewUploadExecutor = savedUploadExecutor
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        LinuxDoRepository.clearUserSessionCaches()
        ScreenTrackEngine.resetForTest()
        UserApiKeyAuthManager.onAuthStateChanged = savedAuthCallback
        UserApiKeyAuthManager.onPersistSession = savedPersistSession
        UserApiKeyAuthManager.onLogoutPlatformClear = savedLogoutClear
    }

    private suspend fun withMockHttp(
        handler: suspend (url: String, method: String, form: Map<String, String>?, headers: Map<String, String>) -> Pair<Int, String>?,
        block: suspend () -> Unit
    ) {
        val prev = UserApiKeyAuthManager.webViewHttpExecutor
        try {
            UserApiKeyAuthManager.webViewHttpExecutor = handler
            block()
        } finally {
            UserApiKeyAuthManager.webViewHttpExecutor = prev
        }
    }

    // -------------------------------------------------------------------------
    // H-01: Guest -> Login increments sessionGeneration & invalidates /site.json
    // -------------------------------------------------------------------------
    @Test
    fun h01_guestCategorySyncInvalidatedUponLoginAllowsLvSubcategories() = runTest {
        var siteSyncCalls = 0
        var loggedIn = false
        withMockHttp({ url, _, _, _ ->
            when {
                url.endsWith("/site.json") -> {
                    siteSyncCalls++
                    if (!loggedIn) {
                        200 to """{"categories":[{"id":4,"name":"开发调优","slug":"develop","color":"32C3C3"}]}"""
                    } else {
                        200 to """
                            {"categories":[
                                {"id":4,"name":"开发调优","slug":"develop","color":"32C3C3"},
                                {"id":20,"name":"开发调优, Lv1","slug":"develop-lv1","color":"32C3C3","parent_category_id":4,"read_restricted":true}
                            ]}
                        """.trimIndent()
                    }
                }
                url.contains("/c/develop/develop-lv1/20") || url.contains("/c/develop-lv1/20") -> {
                    200 to """{"users":[{"id":1,"username":"alice","avatar_template":"/a/{size}.png"}],"topic_list":{"topics":[{"id":901,"title":"Lv1 专属讨论","category_id":20,"posters":[{"user_id":1}]}]}}"""
                }
                else -> 404 to """{"errors":["not found"]}"""
            }
        }) {
            LinuxDoRepository.syncSiteCategories(force = true)
            assertEquals(1, siteSyncCalls)
            val guestErr = assertFailsWith<ApiReadException> {
                LinuxDoRepository.fetchTopics(path = "latest.json", categoryId = 20)
            }
            assertEquals(ApiFailureKind.PermissionDenied, guestErr.kind)

            val genBeforeLogin = UserApiKeyAuthManager.sessionGeneration
            loggedIn = true
            UserApiKeyAuthManager.onSessionAuthenticated(
                profile = AuthenticatedUserProfile(userId = 7, username = "alice", displayName = "Alice", trustLevel = 2),
                rawCookies = "_t=valid_cookie_token"
            )
            assertTrue(UserApiKeyAuthManager.sessionGeneration > genBeforeLogin)

            val topics = LinuxDoRepository.fetchTopics(path = "latest.json", categoryId = 20)
            assertEquals(2, siteSyncCalls, "Login must invalidate Guest /site.json cache and re-sync Lv subcategories")
            assertEquals(1, topics.size)
            assertEquals(901, topics.single().id)
        }
    }

    // -------------------------------------------------------------------------
    // H-02: Confirmed revoked server session evicts auth, while transient glitches never kick user out
    // -------------------------------------------------------------------------
    @Test
    fun h02_revokedServerSessionEvictsAuthWithoutResurrectingAndNeverFalseLogoutsOnGlitch() = runTest {
        UserApiKeyAuthManager.restorePersistedSession(
            profile = AuthenticatedUserProfile(userId = 7, username = "alice", displayName = "Alice"),
            apiKey = null,
            rawCookies = "_t=stale_revoked_cookie; cf_clearance=keep_cf_token"
        )
        assertTrue(UserApiKeyAuthManager.isAuthorized())

        var publicUserCalled = false
        var sessionProbeCount = 0
        withMockHttp({ url, _, _, _ ->
            when {
                url.contains("/session/current.json") -> {
                    sessionProbeCount++
                    404 to """{"errors":["not logged in"]}"""
                }
                url.contains("/u/alice.json") -> {
                    publicUserCalled = true
                    200 to """{"user":{"id":7,"username":"alice","name":"Alice","trust_level":2}}"""
                }
                else -> 404 to "{}"
            }
        }) {
            val profile = LinuxDoRepository.fetchCurrentUserProfile()
            assertNull(profile, "Confirmed 404 JSON on /session/current.json must never fall back to public /u/{username}.json")
            assertEquals(2, sessionProbeCount, "Must double-check /session/current.json before destroying user session")
            assertFalse(publicUserCalled)
            assertFalse(UserApiKeyAuthManager.isAuthorized())
            assertNull(UserApiKeyAuthManager.currentUser)
            assertTrue(CookieSentinel.resolveWinningCookies().containsKey("cf_clearance"))
            assertFalse(CookieSentinel.resolveWinningCookies().containsKey("_t"))
        }

        // Single transient 404 or HTML 404 gateway error MUST NOT kick a logged-in user back to login page!
        UserApiKeyAuthManager.onSessionAuthenticated(
            profile = AuthenticatedUserProfile(userId = 8, username = "bob", displayName = "Bob"),
            rawCookies = "_t=bob_token"
        )
        var bobProbe = 0
        withMockHttp({ url, _, _, _ ->
            when {
                url.contains("/session/current.json") -> {
                    bobProbe++
                    if (bobProbe == 1) {
                        404 to """{"errors":["transient cookie miss"]}"""
                    } else {
                        200 to """{"current_user":{"id":8,"username":"bob","name":"Bob","trust_level":2}}"""
                    }
                }
                else -> 200 to """{"user":{"id":8,"username":"bob"},"user_summary":{}}"""
            }
        }) {
            val recovered = LinuxDoRepository.fetchCurrentUserProfile()
            assertNotNull(recovered, "Transient single 404 recovered on confirmation probe must keep user logged in")
            assertTrue(UserApiKeyAuthManager.isAuthorized())
            assertEquals("bob", UserApiKeyAuthManager.currentUser?.username)
        }

        // HTML 404 / CDN edge page MUST NEVER be treated as Discourse unauthenticated JSON
        withMockHttp({ url, _, _, _ ->
            if (url.contains("/session/current.json")) {
                404 to "<html><body>404 Not Found (Edge Gateway)</body></html>"
            } else 404 to ""
        }) {
            LinuxDoRepository.fetchCurrentUserProfile()
            assertTrue(UserApiKeyAuthManager.isAuthorized(), "HTML gateway 404 must never log out user")
        }

        // Transient empty CookieManager snapshot (e.g. WebView renderer swap / onPause) MUST NOT wipe _t or user!
        CookieSentinel.syncFromAuthoritativeBrowserCookieString("", domain = "linux.do")
        assertTrue(CookieSentinel.resolveWinningCookies().containsKey("_t"))
        assertTrue(UserApiKeyAuthManager.isAuthorized())

        // Guest 401 must not trigger redundant logout callbacks
        UserApiKeyAuthManager.logout()
        var guestLogoutTriggered = false
        UserApiKeyAuthManager.onAuthStateChanged = { guestLogoutTriggered = true }
        assertFailsWith<ApiReadException> {
            readApiObject(401 to """{"errors":["unauthenticated"]}""")
        }
        assertFalse(guestLogoutTriggered, "Guest 401 must not trigger redundant logout loops")
    }

    // -------------------------------------------------------------------------
    // H-03: redeemOtpSilently rethrows CancellationException & hydrates profile first
    // -------------------------------------------------------------------------
    @Test
    fun h03_redeemOtpSilentlyRethrowsCancellationAndPopulatesProfileBeforeCallback() = runTest {
        withMockHttp({ _, _, _, _ -> throw CancellationException("cancelled") }) {
            assertFailsWith<CancellationException> {
                LinuxDoRepository.redeemOtpSilently("otp_token_123")
            }
        }

        UserApiKeyAuthManager.setApiKey("temp_api_key_before_otp")
        var observedUsernameInsideCallback: String? = null
        var observedApiKeyInsideCallback: String? = "non_null"
        var callbackCount = 0
        UserApiKeyAuthManager.onAuthStateChanged = {
            callbackCount++
            observedUsernameInsideCallback = UserApiKeyAuthManager.currentUser?.username
            observedApiKeyInsideCallback = UserApiKeyAuthManager.getApiKey()
        }

        withMockHttp({ url, method, _, _ ->
            when {
                url.contains("/session/otp/") && method == "POST" -> {
                    CookieSentinel.ingestRawCookieString("_t=fresh_otp_session", domain = "linux.do")
                    200 to "{}"
                }
                url.contains("/session/current.json") -> {
                    200 to """{"current_user":{"id":42,"username":"otp_user","name":"OTP User","avatar_template":"/u/{size}.png","trust_level":3}}"""
                }
                else -> 200 to "{}"
            }
        }) {
            val ok = LinuxDoRepository.redeemOtpSilently("valid_otp_999")
            assertTrue(ok)
            assertEquals("otp_user", observedUsernameInsideCallback)
            assertNull(observedApiKeyInsideCallback)
            assertEquals(1, callbackCount)
        }
    }

    // -------------------------------------------------------------------------
    // H-04: Browser auth uses Linux.do whitelisted `one_time_password` scope & key-only auth injects headers
    // -------------------------------------------------------------------------
    @Test
    fun h04_userApiKeyOnlyAuthIsAuthorizedAndInjectsHeadersEvenOverWebViewExecutor() = runTest {
        val (authUrl, _) = UserApiKeyAuthManager.generateAuthorizationUrl()
        assertTrue(
            authUrl.contains("scopes=one_time_password") && !authUrl.contains("read,write"),
            "linux.do disables read/write User-Api-Key issuance; browser auth must strictly request scopes=one_time_password"
        )
        assertTrue(authUrl.contains("otp=true"))

        UserApiKeyAuthManager.setApiKey("live_user_api_key_xyz")
        assertNull(UserApiKeyAuthManager.currentUser)
        assertFalse(CookieSentinel.resolveWinningCookies().containsKey("_t"))
        assertTrue(UserApiKeyAuthManager.isAuthorized(), "Key-only session must be recognized as authorized")

        var capturedHeaders: Map<String, String> = emptyMap()
        withMockHttp({ url, _, _, headers ->
            capturedHeaders = headers
            if (url.contains("/session/current.json")) {
                200 to """{"current_user":{"id":77,"username":"key_only_user","name":"Key Only","trust_level":2}}"""
            } else {
                200 to "{}"
            }
        }) {
            val summary = LinuxDoRepository.fetchCurrentUserProfile()
            assertNotNull(summary)
            assertEquals("key_only_user", summary.username)
            assertEquals("live_user_api_key_xyz", capturedHeaders["User-Api-Key"])
            assertEquals(UserApiKeyAuthManager.getClientId(), capturedHeaders["User-Api-Client-Id"])
        }
    }

    // -------------------------------------------------------------------------
    // H-05 & H-06: Reactive confirmed read floor tracks new replies accurately
    // -------------------------------------------------------------------------
    @Test
    fun h06_reactiveReadFloorTracksNewRepliesAndBumpsVersion() {
        val v0 = LinuxDoRepository.readTopicsVersion
        assertFalse(LinuxDoRepository.isTopicConfirmedReadReactive(topicId = 501, highestPostNumber = 3))

        LinuxDoRepository.recordServerConfirmedReadFloor(topicId = 501, floor = 3, notify = false, persist = false)
        assertTrue(LinuxDoRepository.readTopicsVersion > v0)
        assertTrue(LinuxDoRepository.isTopicConfirmedReadReactive(topicId = 501, highestPostNumber = 3))

        // New replies arrive on server raising highestPostNumber to 15 -> topic has unread posts again
        assertFalse(LinuxDoRepository.isTopicConfirmedReadReactive(topicId = 501, highestPostNumber = 15))

        LinuxDoRepository.recordServerConfirmedReadFloor(topicId = 501, floor = 15, notify = false, persist = false)
        assertTrue(LinuxDoRepository.isTopicConfirmedReadReactive(topicId = 501, highestPostNumber = 15))
    }

    // -------------------------------------------------------------------------
    // H-07: Real `discourse-follow` Following feed + cursor pagination + filters
    // -------------------------------------------------------------------------
    @Test
    fun h07_followTabFetchesRealDiscourseFollowFeedWithCursorPaginationAndFilters() = runTest {
        assertEquals("关注", FeedSortFilter.FOLLOW.label)
        assertEquals("follow.json", FeedSortFilter.FOLLOW.apiPath)

        UserApiKeyAuthManager.onSessionAuthenticated(
            profile = AuthenticatedUserProfile(userId = 10, username = "alice", displayName = "Alice"),
            rawCookies = "_t=alice_cookie"
        )

        val requestedUrls = mutableListOf<String>()
        withMockHttp({ url, _, _, _ ->
            requestedUrls += url
            when {
                url.contains("/follow/posts/alice.json") && !url.contains("created_before=") -> {
                    200 to """
                        {
                          "posts": [
                            {
                              "id": 1001,
                              "topic_id": 300,
                              "post_number": 2,
                              "created_at": "2026-11-11T08:00:00.000Z",
                              "excerpt": "First followed post",
                              "category_id": 4,
                              "user": {"id": 2, "username": "mentor", "avatar_template": "/a/{size}.png"},
                              "topic": {"id": 300, "title": "Followed Topic A", "slug": "topic-a", "posts_count": 5, "tags": ["kotlin"]}
                            },
                            {
                              "id": 1002,
                              "topic_id": 300,
                              "post_number": 1,
                              "created_at": "2026-11-11T07:30:00.000Z",
                              "excerpt": "Duplicate topic post",
                              "category_id": 4,
                              "user": {"id": 2, "username": "mentor", "avatar_template": "/a/{size}.png"},
                              "topic": {"id": 300, "title": "Followed Topic A", "slug": "topic-a", "posts_count": 5, "tags": ["kotlin"]}
                            },
                            {
                              "id": 1003,
                              "topic_id": 301,
                              "post_number": 1,
                              "created_at": "2026-11-11T07:00:00.000Z",
                              "excerpt": "Second followed topic",
                              "category_id": 14,
                              "user": {"id": 3, "username": "builder", "avatar_template": "/b/{size}.png"},
                              "topic": {"id": 301, "title": "Followed Topic B", "slug": "topic-b", "posts_count": 2, "tags": ["rust"]}
                            }
                          ],
                          "extras": {"has_more": true}
                        }
                    """.trimIndent()
                }
                url.contains("/follow/posts/alice.json") && url.contains("created_before=2026-11-11T07%3A00%3A00.000Z") -> {
                    200 to """
                        {
                          "posts": [
                            {
                              "id": 990,
                              "topic_id": 299,
                              "post_number": 1,
                              "created_at": "2026-11-11T06:00:00.000Z",
                              "excerpt": "Older followed post",
                              "category_id": 4,
                              "user": {"id": 2, "username": "mentor", "avatar_template": "/a/{size}.png"},
                              "topic": {"id": 299, "title": "Older Topic", "slug": "older", "posts_count": 1}
                            }
                          ],
                          "extras": {"has_more": false}
                        }
                    """.trimIndent()
                }
                else -> 404 to """{"errors":["not found"]}"""
            }
        }) {
            val page0 = LinuxDoRepository.fetchTopics(path = "follow.json", page = 0)
            assertEquals(listOf(300, 301), page0.map { it.id }, "Topics with multiple recent posts must be deduplicated")

            val page1 = LinuxDoRepository.fetchTopics(path = "follow.json", page = 1)
            assertEquals(listOf(299), page1.map { it.id })

            val urlsBeforePage2 = requestedUrls.size
            val page2 = LinuxDoRepository.fetchTopics(path = "follow.json", page = 2)
            assertTrue(page2.isEmpty(), "When extras.has_more is false, next page must terminate cleanly")
            assertEquals(urlsBeforePage2, requestedUrls.size, "No extra HTTP request when cursor ended")

            val filteredByTag = LinuxDoRepository.fetchTopics(path = "follow.json", page = 0, tag = "rust")
            assertEquals(listOf(301), filteredByTag.map { it.id })

            val filteredByCat = LinuxDoRepository.fetchTopics(path = "follow.json", page = 0, categoryId = 4)
            assertEquals(listOf(300), filteredByCat.map { it.id })
        }
    }

    // -------------------------------------------------------------------------
    // H-08, H-09, M-07, L-03: TopicDetail canEditTopic, canEdit default & floor jump
    // -------------------------------------------------------------------------
    @Test
    fun h08_h09_m07_topicDetailCanEditMetadataAndNearestFloorResolution() = runTest {
        withMockHttp({ url, _, _, _ ->
            if (url.contains("/t/777.json")) {
                200 to """
                    {
                      "id": 777,
                      "title": "元数据编辑测试",
                      "category_id": 14,
                      "posts_count": 3,
                      "details": {"can_edit": true},
                      "post_stream": {
                        "stream": [10, 11, 12],
                        "posts": [
                          {"id": 10, "post_number": 1, "username": "other_author", "cooked": "<p>op</p>", "yours": false},
                          {"id": 11, "post_number": 3, "username": "alice", "cooked": "<p>r3</p>", "yours": true, "can_edit": true},
                          {"id": 12, "post_number": 6, "username": "bob", "cooked": "<p>r6</p>", "yours": false}
                        ]
                      }
                    }
                """.trimIndent()
            } else 404 to "{}"
        }) {
            val detail = LinuxDoRepository.fetchTopicDetail(777)
            assertNotNull(detail)
            assertTrue(detail.canEditTopic, "details.can_edit must map to TopicDetailModel.canEditTopic")
            assertEquals(14, detail.categoryId)
            assertFalse(detail.posts[0].canEdit, "Missing can_edit when yours=false must never default to true")
            assertTrue(detail.posts[1].canEdit)

            // M-07: Deleted floor #4 falls back to nearest floor (#6 at index 2) when allowNearestFallback = true
            assertNull(resolveJumpPostIndex(detail.posts, targetPostNumber = 4, allowNearestFallback = false))
            assertEquals(2, resolveJumpPostIndex(detail.posts, targetPostNumber = 4, allowNearestFallback = true))
            assertEquals(2, resolveJumpPostIndex(detail.posts, targetPostNumber = 99, allowNearestFallback = true))
        }
    }

    // -------------------------------------------------------------------------
    // H-10: Poll AST min/max on `number`/`regular` polls & vote / removePollVote
    // -------------------------------------------------------------------------
    @Test
    fun h10_pollAstMinMaxForNumberTypeAndVoteRemoval() = runTest {
        val numberPollHtml = """
            <div class="poll" data-poll-name="score_poll" data-poll-type="number" data-poll-min="2" data-poll-max="10">
              <div class="poll-container"><ul>
                <li data-poll-option-id="opt2">2</li>
                <li data-poll-option-id="opt10">10</li>
              </ul></div>
            </div>
        """.trimIndent()
        val pollNode = DiscourseAstParser.parseCookedHtml(numberPollHtml).filterIsInstance<PostBlockNode.Poll>().single()
        assertFalse(pollNode.multiple)
        assertEquals(1, pollNode.min, "Single-choice number poll must require 1 option, not data-poll-min numeric value")
        assertEquals(1, pollNode.max, "Single-choice number poll must cap selection at 1, not data-poll-max=10")

        var observedDelete = false
        withMockHttp({ url, method, form, _ ->
            when {
                url.endsWith("/polls/vote.json") && method == "PUT" -> {
                    assertEquals("99", form?.get("post_id"))
                    assertEquals("score_poll", form?.get("poll_name"))
                    200 to """{"poll":{"name":"score_poll","voters":5,"options":[{"id":"opt10","votes":5}]," status":"open"},"vote":["opt10"]}"""
                }
                url.contains("/polls/vote.json") && method == "DELETE" -> {
                    observedDelete = true
                    assertTrue(url.contains("post_id=99") && url.contains("poll_name=score_poll"))
                    assertEquals("99", form?.get("post_id"))
                    200 to """{"poll":{"name":"score_poll","voters":4,"options":[{"id":"opt10","votes":4}],"status":"open"}}"""
                }
                else -> 404 to "{}"
            }
        }) {
            val voted = LinuxDoRepository.votePoll(99, "score_poll", listOf("opt10"))
            assertNotNull(voted)
            assertTrue(voted.hasVoted)
            assertEquals(setOf("opt10"), voted.selectedIds)

            val removed = LinuxDoRepository.removePollVote(99, "score_poll")
            assertTrue(observedDelete)
            assertNotNull(removed)
            assertFalse(removed.hasVoted)
            assertTrue(removed.selectedIds.isEmpty())
            assertEquals(4, removed.optionVotes["opt10"])
        }
    }

    // -------------------------------------------------------------------------
    // H-11: Private message cloud drafts preserve action & recipients
    // -------------------------------------------------------------------------
    @Test
    fun h11_privateMessageCloudDraftPreservesRecipientsAndKindLabel() = runTest {
        var savedPayloadJson: String? = null
        val pmData = buildJsonObject {
            put("reply", "你好，私信草稿正文")
            put("action", "privateMessage")
            put("title", "私信主题")
            put("recipients", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("bob"))
                add(kotlinx.serialization.json.JsonPrimitive("carol"))
            })
        }.toString()
        withMockHttp({ url, method, form, _ ->
            when {
                url.endsWith("/drafts.json") && method == "POST" -> {
                    savedPayloadJson = form?.get("data")
                    200 to """{"draft_sequence":4}"""
                }
                url.endsWith("/drafts.json") && method == "GET" -> {
                    200 to buildJsonObject {
                        put("drafts", buildJsonArray {
                            add(buildJsonObject {
                                put("draft_key", "new_private_message")
                                put("sequence", 4)
                                put("data", pmData)
                            })
                        })
                    }.toString()
                }
                else -> 404 to "{}"
            }
        }) {
            val seq = LinuxDoRepository.saveCloudDraft(
                draftKey = "new_private_message",
                sequence = 3,
                replyRaw = "你好，私信草稿正文",
                title = "私信主题",
                recipients = "@bob， carol ; @bob"
            )
            assertEquals(4, seq)
            val wireObj = Json.parseToJsonElement(assertNotNull(savedPayloadJson)).jsonObject
            assertEquals("privateMessage", wireObj["action"]?.jsonPrimitive?.content)
            assertEquals("bob,carol", wireObj["recipients"]?.jsonPrimitive?.content)

            val fetched = LinuxDoRepository.fetchCloudDrafts().single()
            assertEquals("privateMessage", fetched.action)
            assertEquals("bob,carol", fetched.recipients)
            assertEquals("私信", resolveDraftKindLabel(fetched))
        }
    }

    // -------------------------------------------------------------------------
    // H-12: Remote draft hydration, conflict detection & offline discard
    // -------------------------------------------------------------------------
    @Test
    fun h12_composerDraftRemoteHydrationConflictDetectionAndOfflineDiscard() = runTest {
        val owner = "user-${UUID.randomUUID()}"
        val remoteDraftJson = buildJsonObject {
            put("reply", "来自网页端的草稿内容")
            put("action", "reply")
            put("topicId", 42)
        }.toString()

        // 1. Untouched local draft auto-hydrates remote Web draft
        withMockHttp({ url, _, _, _ ->
            if (url.contains("/drafts/topic_42.json")) {
                200 to buildJsonObject {
                    put("draft_sequence", 11)
                    put("draft", remoteDraftJson)
                }.toString()
            } else 404 to "{}"
        }) {
            val emptySession = ComposerDraftSession(
                ComposerDraft(owner = owner, key = "topic_42", action = ComposerAction.REPLY, topicId = 42)
            )
            val conflict = emptySession.prepareWithRemote()
            assertNull(conflict)
            assertEquals("来自网页端的草稿内容", emptySession.current.raw)
            assertEquals(11, emptySession.savedSequence)
            assertTrue(emptySession.existsOnServer)
        }

        // 2. Local edited draft vs differing remote draft surfaces conflict without overwriting local or stamping sequence prematurely
        val owner2 = "user-${UUID.randomUUID()}"
        val localEdited = ComposerDraft(
            owner = owner2,
            key = "topic_42",
            action = ComposerAction.REPLY,
            raw = "本机离线修改内容",
            topicId = 42
        )
        LocalComposerDrafts.save(localEdited)
        var syncedSeqSent: String? = null
        withMockHttp({ url, method, form, _ ->
            when {
                url.contains("/drafts/topic_42.json") && method == "GET" -> {
                    200 to buildJsonObject {
                        put("draft_sequence", 15)
                        put("draft", remoteDraftJson)
                    }.toString()
                }
                url.endsWith("/drafts.json") && method == "POST" -> {
                    syncedSeqSent = form?.get("sequence")
                    200 to """{"draft_sequence":16}"""
                }
                else -> 404 to "{}"
            }
        }) {
            val conflictSession = ComposerDraftSession(localEdited)
            val conflict = conflictSession.prepareWithRemote()
            assertNotNull(conflict, "Differing remote & local drafts must surface conflict item")
            assertEquals("来自网页端的草稿内容", conflict.raw)
            assertEquals("本机离线修改内容", conflictSession.current.raw)
            assertNull(LocalComposerDrafts.sequence(owner2, "topic_42"), "Must not stamp remote sequence before user resolves conflict")

            // User chooses to keep local over remote
            conflictSession.keepLocalOverRemote(conflict.sequence)
            assertTrue(conflictSession.sync())
            assertEquals("15", syncedSeqSent)
        }

        // 3. Offline discard removes local draft immediately and returns true without trapping user
        val owner3 = "user-${UUID.randomUUID()}"
        val offlineDraft = ComposerDraft(owner3, "new_topic", ComposerAction.NEW_TOPIC, raw = "offline body", title = "offline title", sequence = 3)
        LocalComposerDrafts.save(offlineDraft)
        withMockHttp({ _, _, _, _ -> null }) {
            val session = ComposerDraftSession(offlineDraft, initialExistsOnServer = true)
            assertTrue(session.discard(), "Offline discard must clear local state and allow closing sheet")
            assertNull(LocalComposerDrafts.get(owner3, "new_topic"))
        }

        // 4. DraftsSheet merge keeps unsynced local draft (!sequenceKnown) alongside cloud list
        val owner4 = "user-${UUID.randomUUID()}"
        val unsyncedLocal = ComposerDraft(owner4, "topic_88", ComposerAction.REPLY, raw = "最新未同步本地内容", topicId = 88)
        LocalComposerDrafts.save(unsyncedLocal)
        val cloudList = listOf(
            CloudDraftItem(draftKey = "topic_88", sequence = 5, title = "话题 #88", excerpt = "旧云端", topicId = 88, createdAtText = "1m", raw = "旧云端")
        )
        val merged = mergeCloudAndLocalDraftLists(owner4, cloudList, listOf(unsyncedLocal.toCloudItem()))
        assertEquals("最新未同步本地内容", merged.single().raw)
        assertFalse(merged.single().sequenceKnown)
    }

    // -------------------------------------------------------------------------
    // H-13: Workbench Pangu protection, Poll BBCode, Heading toggle, @mention & Categories
    // -------------------------------------------------------------------------
    @Test
    fun h13_workbenchPanguPollBbCodeHeadingToggleMentionBoundaryAndPostableCategories() {
        val rawMarkdown = "在Linux中使用Kotlin开发2026应用，参考[文档v2](https://linux.do/t/测试123/99)和![截图1|690x388](upload://abc123XYZ.png)以及`代码abc123`联系@张三123"
        val spaced = optimizePanguSpacingText(rawMarkdown)
        assertTrue(spaced.startsWith("在 Linux 中使用 Kotlin 开发 2026 应用"))
        assertTrue(spaced.contains("[文档v2](https://linux.do/t/测试123/99)"), "Markdown link must not be broken by Pangu")
        assertTrue(spaced.contains("![截图1|690x388](upload://abc123XYZ.png)"), "Upload image dimensions & URL must stay intact")
        assertTrue(spaced.contains("`代码abc123`"), "Inline code must stay intact")
        assertTrue(spaced.contains("@张三123"), "@mention handle must stay intact")

        val numberPollBb = buildWorkbenchPollBbCode(
            pollType = "number",
            pollResults = "always",
            pollPublic = true,
            pollTitle = "打分投票",
            options = emptyList(),
            numMin = 1,
            numMax = 10,
            numStep = 2
        )
        assertEquals(
            "\n[poll type=number results=always min=1 max=10 step=2 public=true]\n# 打分投票\n[/poll]\n",
            numberPollBb
        )
        assertNull(buildWorkbenchPollBbCode("regular", "always", false, "单选", listOf("仅一项")))

        // Heading replacement instead of stacking `## # 标题`
        val (h2Text, _) = toggleMarkdownLinePrefix("# 一级标题", cursorPos = 2, prefix = "## ")
        assertEquals("## 一级标题", h2Text)
        val (plainText, _) = toggleMarkdownLinePrefix(h2Text, cursorPos = 2, prefix = "## ")
        assertEquals("一级标题", plainText)

        // Email address must not be treated as @mention replacement target
        val emailInput = "请发邮件至 contact@linux"
        val (afterEmailInsert, _) = insertSnippetAtCursorWithMentionBoundary(
            text = emailInput,
            selectionMin = emailInput.length,
            selectionMax = emailInput.length,
            snippet = "@linuxdo ",
            replaceMention = true
        )
        assertEquals("请发邮件至 contact@linux@linuxdo ", afterEmailInsert)

        val mentionInput = "你好 @lin"
        val (afterMentionInsert, _) = insertSnippetAtCursorWithMentionBoundary(
            text = mentionInput,
            selectionMin = mentionInput.length,
            selectionMax = mentionInput.length,
            snippet = "@linuxdo ",
            replaceMention = true
        )
        assertEquals("你好 @linuxdo ", afterMentionInsert)

        // Unverified default subcategories must never appear in postable subcategory list before /site.json verification
        assertTrue(LinuxDoRepository.getPostableSubCategoriesForParent(4).isEmpty())
    }

    // -------------------------------------------------------------------------
    // M-01 & M-03: ScreenTrackEngine session reset, FIFO queue & retry cap
    // -------------------------------------------------------------------------
    @Test
    fun m01_m03_screenTrackSessionResetFifoQueueAndBoundedRetries() {
        val t0 = 1_000_000L
        ScreenTrackEngine.consolidateBatch(101, 1200, mapOf(1 to 1200))
        ScreenTrackEngine.consolidateBatch(202, 1500, mapOf(1 to 1500))
        assertEquals(2, ScreenTrackEngine.pendingBatchesCount)

        // Entering topic 303 and running a sub-1s non-rush tick must return null and NOT pop consolidatedQueue
        ScreenTrackEngine.onTopicEntered(303, setOf(1), t0 + 2_000)
        assertNull(ScreenTrackEngine.tick(t0 + 2_500))
        assertEquals(2, ScreenTrackEngine.pendingBatchesCount)

        // FIFO order: topic 101 pops first, topic 202 second
        val firstPop = assertNotNull(ScreenTrackEngine.popNextSendableBatch(t0 + 3_100))
        assertEquals(101, firstPop.topicId)
        // Simulate transient 502 on topic 101 -> moves to tail behind topic 202
        ScreenTrackEngine.onBatchSendResult(firstPop, statusCode = 502, nowMs = t0 + 3_100)
        val afterBackoff = t0 + 10_000L
        val secondPop = assertNotNull(ScreenTrackEngine.popNextSendableBatch(afterBackoff))
        assertEquals(202, secondPop.topicId, "Failing topic must move to tail so other topics are not starved")
        // Permanent 422 drops immediately without re-queuing
        ScreenTrackEngine.onBatchSendResult(secondPop, statusCode = 422, nowMs = afterBackoff)
        assertEquals(1, ScreenTrackEngine.pendingBatchesCount)

        // Topic 101 already failed once; after 2 more transient failures (total 3 = MAX_TOPIC_RETRIES), it is dropped
        val retry2 = assertNotNull(ScreenTrackEngine.popNextSendableBatch(afterBackoff + 20_000))
        assertEquals(101, retry2.topicId)
        ScreenTrackEngine.onBatchSendResult(retry2, statusCode = 503, nowMs = afterBackoff + 20_000)
        val retry3 = assertNotNull(ScreenTrackEngine.popNextSendableBatch(afterBackoff + 50_000))
        assertEquals(101, retry3.topicId)
        ScreenTrackEngine.onBatchSendResult(retry3, statusCode = 503, nowMs = afterBackoff + 50_000)
        assertEquals(0, ScreenTrackEngine.pendingBatchesCount, "Poison batch must drop after reaching MAX_TOPIC_RETRIES")

        // Session reset clears everything
        ScreenTrackEngine.consolidateBatch(404, 1000, mapOf(1 to 1000))
        ScreenTrackEngine.resetSessionState()
        assertEquals(0, ScreenTrackEngine.pendingBatchesCount)
    }

    // -------------------------------------------------------------------------
    // M-02: Smart URL web=1 query stripping & trailing-slash / `/last` topic match
    // -------------------------------------------------------------------------
    @Test
    fun m02_smartUrlStripsWebOnePreservingQueryAndFragmentAndMatchesTopicUrls() {
        assertEquals(
            true to "https://linux.do/t/topic/123?u=alice&page=2#post_5",
            stripWebOneQueryParam("https://linux.do/t/topic/123?u=alice&web=1&page=2#post_5")
        )
        assertEquals(
            true to "https://linux.do/t/topic/123#bottom",
            stripWebOneQueryParam("https://linux.do/t/topic/123?web=1#bottom")
        )
        assertEquals(
            false to "https://linux.do/t/topic/123?page=2",
            stripWebOneQueryParam("https://linux.do/t/topic/123?page=2")
        )

        assertEquals(123 to null, parseSmartTopicUrlTarget("https://linux.do/t/some-slug/123/"))
        assertEquals(123 to 8, parseSmartTopicUrlTarget("https://linux.do/t/some-slug/123/8/"))
        assertEquals(123 to 999_999, parseSmartTopicUrlTarget("https://linux.do/t/some-slug/123/last"))
        assertEquals(123 to 999_999, parseSmartTopicUrlTarget("https://linux.do/t/123/last?u=bob"))
    }

    // -------------------------------------------------------------------------
    // M-06: Plain-text `/raw/{topicId}/{postNumber}` fallback & `updateTopicMetadata`
    // -------------------------------------------------------------------------
    @Test
    fun m06_fetchPostRawMarkdownPlainTextFallbackAndUpdateTopicMetadata() = runTest {
        var rawAcceptHeader: String? = null
        var putUrl: String? = null
        var putForm: Map<String, String>? = null

        withMockHttp({ url, method, form, headers ->
            when {
                url.endsWith("/posts/55.json") -> 200 to """{"id":55,"cooked":"<p>only cooked</p>"}"""
                url.endsWith("/raw/10/1") -> {
                    rawAcceptHeader = headers["Accept"]
                    200 to "# 原始标题\n\n这是纯文本 Markdown 内容"
                }
                url.endsWith("/raw/10/2") -> 200 to "<!DOCTYPE html><html><title>Just a moment...</title></html>"
                url.endsWith("/t/-/10.json") && method == "PUT" -> {
                    putUrl = url
                    putForm = form
                    200 to """{"basic_topic":{"id":10,"title":"新标题"}}"""
                }
                else -> 404 to "{}"
            }
        }) {
            val md = LinuxDoRepository.fetchPostRawMarkdown(postId = 55, topicId = 10, postNumber = 1)
            assertEquals("# 原始标题\n\n这是纯文本 Markdown 内容", md)
            assertEquals("text/plain", rawAcceptHeader)

            val challengeMd = LinuxDoRepository.fetchPostRawMarkdown(postId = 55, topicId = 10, postNumber = 2)
            assertNull(challengeMd, "HTML challenge page must never be treated as post Markdown")

            assertTrue(LinuxDoRepository.updateTopicMetadata(10, " 新标题 ", 14, listOf("kotlin", "kmp")))
            assertEquals("https://linux.do/t/-/10.json", putUrl)
            assertEquals("新标题", putForm?.get("title"))
            assertEquals("14", putForm?.get("category_id"))
            assertEquals("kotlin", putForm?.get("tags[0]"))
            assertEquals("kmp", putForm?.get("tags[1]"))
        }
    }

    // -------------------------------------------------------------------------
    // M-08 & M-09: Notifications plugin types, unread cache & PM delivery outcomes
    // -------------------------------------------------------------------------
    @Test
    fun m08_m09_notificationsPluginTypesUnreadCacheAndPrivateMessageDelivery() = runTest {
        UserApiKeyAuthManager.fullProfileCache = UserFullProfileSummary(
            username = "alice",
            displayName = "Alice",
            avatarTemplate = "",
            trustLevel = 2,
            unreadNotifications = 5
        )
        UserApiKeyAuthManager.updateCachedUnreadNotifications(0)
        assertEquals(0, UserApiKeyAuthManager.fullProfileCache?.unreadNotifications)

        val nFollow = UserNotificationItem(id = 1, notificationType = 800, read = false, createdAtText = "1m", title = "", actingUsername = "bob")
        val nFollowTopic = UserNotificationItem(id = 2, notificationType = 801, read = false, createdAtText = "1m", title = "新帖", actingUsername = "bob")
        val nFollowPost = UserNotificationItem(id = 3, notificationType = 802, read = false, createdAtText = "1m", title = "新回复", actingUsername = "bob")
        val nBoost = UserNotificationItem(id = 4, notificationType = 803, read = false, createdAtText = "1m", title = "助力", actingUsername = "bob")
        assertEquals("关注了你", nFollow.typeLabel)
        assertEquals("发布了新话题", nFollowTopic.typeLabel)
        assertEquals("发表了新回复", nFollowPost.typeLabel)
        assertEquals(LinDoIcons.User, resolveNotificationIcon(nFollow))
        assertEquals(LinDoIcons.FileText, resolveNotificationIcon(nFollowTopic))
        assertEquals(LinDoIcons.Reply, resolveNotificationIcon(nFollowPost))
        assertEquals(LinDoIcons.BoostRocket, resolveNotificationIcon(nBoost))

        var capturedRecipients: String? = null
        var mode = "sent"
        withMockHttp({ url, method, form, _ ->
            if (url.endsWith("/posts.json") && method == "POST") {
                capturedRecipients = form?.get("target_recipients")
                when (mode) {
                    "sent" -> 200 to """{"post":{"id":1,"topic_id":888}}"""
                    "enqueued" -> 200 to """{"action":"enqueued","pending_post":{"id":9}}"""
                    else -> 422 to """{"errors":["对方已关闭私信接收"]}"""
                }
            } else 404 to "{}"
        }) {
            val sent = LinuxDoRepository.sendPrivateMessageDetailed("@bob， carol ; @bob", "问候", "你好呀")
            assertEquals("bob,carol", capturedRecipients)
            assertEquals(PrivateMessageSendOutcome.SENT, sent.outcome)
            assertEquals(888, sent.topicId)

            mode = "enqueued"
            val enqueued = LinuxDoRepository.sendPrivateMessageDetailed("bob", "审核私信", "需要审核的正文")
            assertEquals(PrivateMessageSendOutcome.ENQUEUED, enqueued.outcome)
            assertTrue(enqueued.isSuccess)

            mode = "rejected"
            val rejected = LinuxDoRepository.sendPrivateMessageDetailed("bob", "失败私信", "正文")
            assertEquals(PrivateMessageSendOutcome.FAILED, rejected.outcome)
            assertEquals("对方已关闭私信接收", rejected.errorMessage)
        }
    }

    // -------------------------------------------------------------------------
    // M-10 & M-11: Titles, mutual followers, bookmark delete, search hasMore & upload sanitize
    // -------------------------------------------------------------------------
    @Test
    fun m10_m11_accountTitlesMutualFollowersBookmarkDeleteSearchHasMoreAndUploadSanitize() = runTest {
        UserApiKeyAuthManager.onSessionAuthenticated(
            profile = AuthenticatedUserProfile(userId = 1, username = "alice", displayName = "Alice"),
            rawCookies = "_t=alice_cookie"
        )

        var deletedBookmarkUrl: String? = null
        withMockHttp({ url, method, _, _ ->
            when {
                url.endsWith("/u/alice.json") && method == "GET" -> {
                    200 to """
                        {
                          "user": {
                            "id": 1,
                            "username": "alice",
                            "name": "Alice",
                            "title": "破界者",
                            "bio_excerpt": "不应被当成bio_raw的截断摘要",
                            "groups": [{"id": 10, "name": "g-merchant", "title": "认证商家"}],
                            "user_option": {"hide_profile_and_presence": false, "allow_private_messages": true}
                          }
                        }
                    """.trimIndent()
                }
                url.contains("/user-badges/alice.json") -> {
                    200 to """{"badges":[{"id":5,"name":"百日筑基","allow_title":true},{"id":6,"name":"普通徽章","allow_title":false}]}"""
                }
                url.endsWith("/u/alice.json") && method == "PUT" -> {
                    422 to """{"errors":["个人网站格式不正确"]}"""
                }
                url.endsWith("/u/alice/follow/followers.json") -> {
                    200 to """{"users":[{"id":2,"username":"bob","avatar_template":"/b.png"},{"id":3,"username":"stranger","avatar_template":"/s.png"}]}"""
                }
                url.endsWith("/u/alice/follow/following.json") -> {
                    200 to """{"users":[{"id":2,"username":"bob","avatar_template":"/b.png"}]}"""
                }
                url.endsWith("/bookmarks/321.json") && method == "DELETE" -> {
                    deletedBookmarkUrl = url
                    200 to """{"success":"OK"}"""
                }
                url.contains("/search.json") -> {
                    200 to """
                        {
                          "posts": [{"id":1,"topic_id":50,"post_number":1,"blurb":"hit","username":"bob"}],
                          "topics": [{"id":50,"title":"Search Topic","posts_count":2}],
                          "grouped_search_result": {"more_full_page_results": true}
                        }
                    """.trimIndent()
                }
                else -> 404 to "{}"
            }
        }) {
            val prefs = assertNotNull(LinuxDoRepository.fetchUserAccountPreferences("alice"))
            assertEquals(null, prefs.twoFactorEnabled)
            assertEquals(null, prefs.activeDeviceCount)
            assertEquals(listOf("百日筑基", "认证商家", "破界者"), prefs.availableTitles)
            assertEquals("", prefs.bioRaw, "Missing bio_raw must not be overwritten with truncated bio_excerpt")

            val saveRes = LinuxDoRepository.updateUserProfileInfoResult("alice", website = "not-a-url")
            assertTrue(saveRes.isFailure)
            assertTrue(saveRes.exceptionOrNull()?.message?.contains("个人网站格式不正确") == true)

            val followers = LinuxDoRepository.fetchUserFollowList("alice", followers = true)
            assertEquals(2, followers.size)
            assertTrue(followers.first { it.username == "bob" }.isFollowing, "Mutual follower must be marked isFollowing=true")
            assertFalse(followers.first { it.username == "stranger" }.isFollowing)

            assertTrue(LinuxDoRepository.deleteBookmark(321))
            assertEquals("https://linux.do/bookmarks/321.json", deletedBookmarkUrl)

            val searchPage = LinuxDoRepository.searchCommunityPage(query = "kotlin", page = 1)
            assertEquals(1, searchPage.items.size)
            assertTrue(searchPage.hasMore)
        }

        // M-11: Filename bracket/pipe sanitization in upload Markdown
        PlatformMediaPicker.webViewUploadExecutor = { _, _, _ ->
            200 to """{"original_filename":"my[shot]|v2].png","short_url":"upload://img123.png","url":"/uploads/img123.png","width":800,"height":600,"human_filesize":"42 KB"}"""
        }
        val uploaded = assertNotNull(
            LinuxDoRepository.uploadMediaToLinuxDo("my[shot]|v2].png", "image/png", byteArrayOf(1, 2, 3))
        )
        assertFalse(uploaded.markdownSnippet.substringBefore("](").drop(2).any { it in setOf('[', ']', '|') && it != '|' })
        assertEquals("![my(shot)-v2).png|800x600](upload://img123.png)", uploaded.markdownSnippet)
    }

    // -------------------------------------------------------------------------
    // L-01, L-02, L-03: AST image linkUrls, safeUrl, whitespace code, emoji `:8080:`, nested quotes & CSS 8-digit hex
    // -------------------------------------------------------------------------
    @Test
    fun l01_l02_l03_astImageLinksCodeWhitespaceEmojiNumericPortNestedQuotesAndCssHexAlpha() {
        // L-01: Linked badge/banner image keeps external href in linkUrls while lightbox image keeps null
        val linkedImgHtml = """
            <p><a href="https://github.com/linux-do"><img src="https://linux.do/uploads/badge.png" alt="GitHub"></a></p>
            <p><a class="lightbox" href="https://linux.do/uploads/full.png"><img src="https://linux.do/uploads/thumb.png" alt="photo"></a></p>
            <p>分隔符<code>   </code>结束</p>
        """.trimIndent()
        val nodes = DiscourseAstParser.parseCookedHtml(linkedImgHtml)
        val gallery = nodes.filterIsInstance<PostBlockNode.ImageGallery>().single()
        assertEquals(2, gallery.urls.size)
        assertEquals(listOf("https://github.com/linux-do", null), gallery.linkUrls)

        val para = nodes.filterIsInstance<PostBlockNode.Paragraph>().single()
        val codeRun = para.rich.runs.first { it.code }
        assertEquals("   ", codeRun.text, "Inline <code> containing spaces must not be stripped")

        val encodedUrl = DiscourseAstParser.safeUrl("https://example.com/search?q=a|b c[1]")
        assertEquals("https://example.com/search?q=a%7Cb%20c%5B1%5D", encodedUrl)

        // L-02: Numeric `:8080:` must not become a broken custom emoji image, and nested `[quote]` renders cleanly
        assertFalse(LinuxDoEmojiCatalog.isCustomImageEmoji("8080"))
        val renderedPort = MarkdownPreview.render("本地服务端口:8080:已开放，开心 :smile:")
        assertFalse(renderedPort.contains("8080.png"), "Numeric :8080: must remain literal text")
        assertTrue(renderedPort.contains("本地服务端口:8080:已开放"))
        assertTrue(renderedPort.contains("class=\"emoji\""))

        val nestedQuotesHtml = MarkdownPreview.render(
            "[quote=\"alice, post:1, topic:9\"]外层内容\n[quote=\"bob, post:2, topic:9\"]内层内容[/quote]\n外层结尾[/quote]"
        )
        val parsedNested = DiscourseAstParser.parseCookedHtml(nestedQuotesHtml)
        val outerQuote = parsedNested.filterIsInstance<PostBlockNode.QuoteBlock>().single()
        assertEquals("alice", outerQuote.username)
        val innerQuote = outerQuote.children.filterIsInstance<PostBlockNode.QuoteBlock>().single()
        assertEquals("bob", innerQuote.username)

        // L-03: CSS 8-digit #RRGGBBAA & "transparent"
        assertEquals(Color.Transparent, LinuxDoSiteRules.parseHexColor("transparent"))
        val semiRed = LinuxDoSiteRules.parseHexColor("#FF000080")
        assertTrue(abs(semiRed.red - 1f) < 0.01f)
        assertTrue(abs(semiRed.green - 0f) < 0.01f)
        assertTrue(abs(semiRed.blue - 0f) < 0.01f)
        assertTrue(abs(semiRed.alpha - (0x80 / 255f)) < 0.01f, "CSS #RRGGBBAA must place trailing AA into alpha channel")
    }

    // -------------------------------------------------------------------------
    // Round 4 Real-Device Defect Fixes: CF false-positive guard, WebView read retry, ScreenTrack 403 recovery
    // -------------------------------------------------------------------------
    @Test
    fun r4_cloudflareChallengeDetectorAvoidsFalsePositivesOnDiscourseRateLimitHtml() = runTest {
        val discourse429Html = """
            <!DOCTYPE html>
            <html><head><title>Too Many Requests - LINUX DO</title>
            <meta name="discourse-theme" content="verify-turnstile-plugin">
            </head><body><h1>您执行此操作的次数过多，请稍后再试。</h1></body></html>
        """.trimIndent()
        assertFalse(
            looksLikeCloudflareChallenge(discourse429Html),
            "Ordinary Discourse HTML 429 mentioning verify/turnstile must NOT be misclassified as Cloudflare site verification"
        )
        val err = assertFailsWith<ApiReadException> {
            readApiObject(429 to discourse429Html)
        }
        assertEquals(ApiFailureKind.RateLimited, err.kind)

        assertTrue(looksLikeCloudflareChallenge("${CF_MITIGATED_CHALLENGE_PREFIX}<html></html>"))
        assertTrue(looksLikeCloudflareChallenge("<!DOCTYPE html><html><head><title>Just a moment...</title></head><body><script>window._cf_chl_opt={};</script></body></html>"))
    }

    @Test
    fun r4_foregroundWebViewReadsAutoRetryTransient429AndNetworkGlitches() = runTest {
        var feedAttempts = 0
        var detailAttempts = 0
        withMockHttp({ url, _, _, _ ->
            when {
                url.contains("latest.json") -> {
                    feedAttempts++
                    if (feedAttempts == 1) {
                        429 to "<html><body>Too Many Requests</body></html>"
                    } else {
                        200 to """{"users":[{"id":1,"username":"alice","avatar_template":"/a/{size}.png"}],"topic_list":{"topics":[{"id":88,"title":"自动重试恢复成功","category_id":4,"posters":[{"user_id":1}]}]}}"""
                    }
                }
                url.contains("/t/88.json") -> {
                    detailAttempts++
                    if (detailAttempts == 1) {
                        0 to ""
                    } else {
                        200 to """{"id":88,"title":"自动重试恢复成功","posts_count":1,"post_stream":{"stream":[1],"posts":[{"id":1,"post_number":1,"username":"alice","cooked":"<p>ok</p>"}]}}"""
                    }
                }
                else -> 200 to "{}"
            }
        }) {
            val topics = LinuxDoRepository.fetchTopics(path = "latest.json", page = 1)
            assertEquals(2, feedAttempts)
            assertEquals(1, topics.size)
            assertEquals(88, topics.single().id)

            val detail = LinuxDoRepository.fetchTopicDetail(88)
            assertEquals(2, detailAttempts)
            assertNull(detail.loadError)
            assertEquals(1, detail.posts.size)
        }
    }

    @Test
    fun r4_screenTrackTickAndEnqueueRetriesTransient403AndConfirmsUnreadFloors() {
        ScreenTrackEngine.resetForTest()
        val v0 = ScreenTrackEngine.serverConfirmedReadVersion
        ScreenTrackEngine.onTopicEntered(topicId = 555, initialVisiblePosts = setOf(1, 2, 3), nowMs = 1_000L)
        val enqueued = ScreenTrackEngine.tickAndEnqueue(nowMs = 2_200L)
        assertTrue(enqueued)
        assertTrue(ScreenTrackEngine.hasPendingWork())

        val firstBatch = ScreenTrackEngine.popNextSendableBatch(nowMs = 2_200L)
        assertNotNull(firstBatch)
        assertEquals(setOf(1, 2, 3), firstBatch.timingsMs.keys)

        // Transient 403 (e.g. stale CSRF before refresh) must re-enqueue for retry, NOT permanently drop!
        ScreenTrackEngine.onBatchSendResult(firstBatch, statusCode = 403, nowMs = 2_300L)
        assertTrue(ScreenTrackEngine.hasPendingWork(), "403 on timings must retry after backoff rather than permanently dropping unread floors")
        assertFalse(ScreenTrackEngine.isPostConfirmedReadByServer(555, 2))

        val retryBatch = ScreenTrackEngine.popNextSendableBatch(nowMs = 8_000L)
        assertNotNull(retryBatch)
        ScreenTrackEngine.onBatchSendResult(retryBatch, statusCode = 200, nowMs = 8_100L)
        assertTrue(ScreenTrackEngine.isPostConfirmedReadByServer(555, 2))
        assertTrue(ScreenTrackEngine.isPostConfirmedReadByServer(555, 3))
        assertTrue(ScreenTrackEngine.serverConfirmedReadVersion > v0)
    }

    // -------------------------------------------------------------------------
    // Round 5 Avatar Speed, Consolidated Follow Notifications & Official Glow Alignment
    // -------------------------------------------------------------------------
    @Test
    fun r5_consolidatedFollowNotificationsResolveNeoAuthorWithoutTouchingGroupedReplies() = runTest {
        LinuxDoRepository.clearAvatarAndActorCachesForTest()
        var postLookupCount = 0
        withMockHttp({ url, _, _, _ ->
            when {
                url.contains("latest.json") -> {
                    200 to """
                        {
                          "users": [
                            {
                              "id": 1,
                              "username": "neo",
                              "name": "Neo",
                              "avatar_template": "/user_avatar/linux.do/neo/{size}/136369_2.png",
                              "primary_group_name": "admins"
                            }
                          ],
                          "topic_list": {
                            "topics": [
                              {
                                "id": 7702,
                                "title": "大 Vibe coding 时代，勇敢的人先站上舞台",
                                "category_id": 4,
                                "posters": [{"description": "Original Poster", "user_id": 1}]
                              }
                            ]
                          }
                        }
                    """.trimIndent()
                }
                url.contains("/posts/99001.json") -> {
                    postLookupCount++
                    200 to """
                        {
                          "id": 99001,
                          "topic_id": 7701,
                          "post_number": 1,
                          "username": "neo",
                          "name": "Neo",
                          "avatar_template": "/user_avatar/linux.do/neo/{size}/136369_2.png",
                          "primary_group_name": "admins",
                          "cooked": "<p>Version 1.0.5 Build 6</p>"
                        }
                    """.trimIndent()
                }
                url.contains("/notifications.json") -> {
                    200 to """
                        {
                          "notifications": [
                            {
                              "id": 501,
                              "notification_type": 801,
                              "read": true,
                              "created_at": "2026-11-11T09:00:00Z",
                              "topic_id": 7701,
                              "post_number": 1,
                              "fancy_title": "Version 1.0.5 Build 6",
                              "data": {
                                "topic_title": "Version 1.0.5 Build 6",
                                "original_post_id": 99001,
                                "original_post_type": 1,
                                "display_username": "2 个回复"
                              }
                            },
                            {
                              "id": 502,
                              "notification_type": 801,
                              "read": true,
                              "created_at": "2026-11-11T08:00:00Z",
                              "topic_id": 7702,
                              "post_number": 1,
                              "fancy_title": "大 Vibe coding 时代，勇敢的人先站上舞台",
                              "data": {
                                "topic_title": "大 Vibe coding 时代，勇敢的人先站上舞台",
                                "original_post_id": 99002,
                                "original_post_type": 1,
                                "display_username": "3 个回复"
                              }
                            },
                            {
                              "id": 503,
                              "notification_type": 802,
                              "read": false,
                              "created_at": "2026-11-11T07:00:00Z",
                              "topic_id": 7703,
                              "post_number": 5,
                              "acting_user_avatar_template": "/user_avatar/linux.do/neo/48/136369_2.png",
                              "data": {
                                "topic_title": "集中讨论帖",
                                "original_post_id": 99003,
                                "display_username": "2 个回复"
                              }
                            },
                            {
                              "id": 504,
                              "notification_type": 2,
                              "read": false,
                              "created_at": "2026-11-11T06:00:00Z",
                              "topic_id": 7704,
                              "post_number": 10,
                              "data": {
                                "topic_title": "我的提问帖",
                                "display_username": "3 条回复"
                              }
                            }
                          ]
                        }
                    """.trimIndent()
                }
                else -> 404 to "{}"
            }
        }) {
            // Seed topic 7702 into topicOpCache via normal feed load
            LinuxDoRepository.fetchTopics(path = "latest.json", page = 0)

            val list = LinuxDoRepository.fetchUserNotifications()
            assertEquals(4, list.size)

            // 501: Cold consolidated 801 -> resolved asynchronously via /posts/99001.json
            val n501 = list[0]
            assertEquals(99001, n501.originalPostId)
            assertNull(n501.actorUsername)
            val resolved501 = assertNotNull(LinuxDoRepository.resolveNotificationActor(n501))
            assertEquals("neo", resolved501.username)
            assertEquals("/user_avatar/linux.do/neo/{size}/136369_2.png", resolved501.avatarTemplate)
            assertEquals(1, postLookupCount)

            // 502: Warm topicOpCache consolidated 801 -> resolved synchronously in 0ms without extra HTTP call!
            val n502 = list[1]
            assertEquals("neo", n502.actorUsername)
            assertEquals("/user_avatar/linux.do/neo/{size}/136369_2.png", n502.actingAvatarTemplate)
            assertEquals("发布了新话题", n502.typeLabel)
            assertEquals(1, postLookupCount)

            // 503: Consolidated 802 with avatar path -> extracts "neo" directly from avatar URL
            val n503 = list[2]
            assertEquals("neo", n503.actorUsername)
            assertEquals("等发表了 2 条新回复", n503.typeLabel)

            // 504: Grouped reply to own post (type=2) -> must NOT resolve post_number as actor!
            val n504 = list[3]
            assertNull(n504.actorUsername)
            assertEquals("收到 3 条回复", n504.typeLabel)
            assertNull(LinuxDoRepository.resolveNotificationActor(n504))
        }
    }

    @Test
    fun r5_avatarCdnNormalizationValidDiscourseSizesAndOfficialGlowAlignment() {
        // Valid Discourse avatar_sizes snapping (eliminates 302 redirects on 120 / 240)
        assertEquals(144, LinuxDoSiteRules.normalizeAvatarSize(120))
        assertEquals(144, LinuxDoSiteRules.normalizeAvatarSize(144))
        assertEquals(288, LinuxDoSiteRules.normalizeAvatarSize(240))

        // Unified static avatar CDN URL across {size}, /48/, /96/, /240/, and relative/absolute forms
        val expectedNeo144 = "https://cdn.ldstatic.com/user_avatar/linux.do/neo/144/136369_2.png"
        assertEquals(expectedNeo144, LinuxDoSiteRules.resolveStaticAvatarUrl("/user_avatar/linux.do/neo/{size}/136369_2.png", 120))
        assertEquals(expectedNeo144, LinuxDoSiteRules.resolveStaticAvatarUrl("/user_avatar/linux.do/neo/48/136369_2.png", 144))
        assertEquals(expectedNeo144, LinuxDoSiteRules.resolveStaticAvatarUrl("https://linux.do/user_avatar/linux.do/neo/240/136369_2.png", 144))
        assertEquals(expectedNeo144, LinuxDoSiteRules.resolveStaticAvatarUrl("https://cdn.ldstatic.com/user_avatar/linux.do/neo/96/136369_2.png", 144))

        // Direct Discourse letter_avatar_proxy -> avatars.discourse-cdn.com (eliminates CORS redirect failure)
        val expectedLetter144 = "https://avatars.discourse-cdn.com/v4/letter/v/90ced4/144.png"
        assertEquals(expectedLetter144, LinuxDoSiteRules.resolveStaticAvatarUrl("/letter_avatar_proxy/v4/letter/v/90ced4/{size}.png", 120))
        assertEquals(expectedLetter144, LinuxDoSiteRules.resolveStaticAvatarUrl("https://linux.do/letter_avatar_proxy/v4/letter/v/90ced4/48.png", 144))
        assertEquals(expectedLetter144, LinuxDoSiteRules.resolveStaticAvatarUrl(expectedLetter144, 144))

        // Official common_theme_138 glow & square rules
        val blueGlow = Color(0xFF00AEFF)
        val goldGlow = Color(0xFFF5BF03)
        assertTrue(LinuxDoSiteRules.isSquareAvatar("neo"))
        assertTrue(LinuxDoSiteRules.isSquareAvatar("@NEO"))
        assertEquals(blueGlow, LinuxDoSiteRules.matchAvatarGlowColor("@neo", null))
        assertEquals(blueGlow, LinuxDoSiteRules.matchAvatarGlowColor(null, null, expectedNeo144))

        val circularBlueUsers = listOf(
            "kevin", "delph1s", "King-Huiwen-of-Qin", "NullUser", "DOS.DO", "Musifei", "SMNET", "syokatu"
        )
        for (uname in circularBlueUsers) {
            assertFalse(LinuxDoSiteRules.isSquareAvatar(uname), "$uname must stay circular (50%)")
            assertEquals(blueGlow, LinuxDoSiteRules.matchAvatarGlowColor(uname, null), "$uname must receive official #00AEFF glow")
        }
        assertEquals(goldGlow, LinuxDoSiteRules.matchAvatarGlowColor("seller", "g-merchant"))
        assertEquals(goldGlow, LinuxDoSiteRules.matchAvatarGlowColor("seller2", "g-merchant2"))
        assertNull(LinuxDoSiteRules.matchAvatarGlowColor("normal_user", "trust_level_3"))
    }

    @Test
    fun r6_wideScreenSplitPaneResizingClampingZeroDeadZoneAndPersistence() {
        // 1. Safe clamping against out-of-bounds and NaN/Infinity
        assertEquals(DEFAULT_SPLIT_PANE_FRACTION, clampSplitPaneFraction(Float.NaN))
        assertEquals(DEFAULT_SPLIT_PANE_FRACTION, clampSplitPaneFraction(Float.POSITIVE_INFINITY))
        assertEquals(MIN_SPLIT_PANE_FRACTION, clampSplitPaneFraction(0.05f))
        assertEquals(MAX_SPLIT_PANE_FRACTION, clampSplitPaneFraction(0.95f))

        // 2. Phone landscape (~816dp available content width after side rail):
        //    Respects MIN_LEFT_PANE_WIDTH_DP (280dp) and MIN_RIGHT_PANE_WIDTH_DP (320dp)
        val phoneContentDp = 816f
        val phoneDefaultLeft = computeSplitPaneLeftWidthDp(phoneContentDp, DEFAULT_SPLIT_PANE_FRACTION)
        assertTrue(phoneDefaultLeft in 340f..345f, "Expected ~342.3dp on phone landscape, got $phoneDefaultLeft")

        val phoneMinLeft = computeSplitPaneLeftWidthDp(phoneContentDp, 0.10f)
        assertEquals(MIN_LEFT_PANE_WIDTH_DP, phoneMinLeft)

        val phoneMaxLeft = computeSplitPaneLeftWidthDp(phoneContentDp, 0.90f)
        assertEquals(phoneContentDp - 1f - MIN_RIGHT_PANE_WIDTH_DP, phoneMaxLeft)

        // 3. Zero dead-zone when dragging back from clamped edge
        val reversedFromMaxFraction = computeUpdatedSplitFractionAfterDrag(
            availableContentWidthDp = phoneContentDp,
            currentLeftWidthDp = phoneMaxLeft,
            dragDeltaDp = -40f
        )
        val reversedLeft = computeSplitPaneLeftWidthDp(phoneContentDp, reversedFromMaxFraction)
        assertTrue(reversedLeft < phoneMaxLeft - 38f, "Dragging left from max bound must respond immediately without dead-zone")

        // 4. Desktop/tablet wide screen (~1280dp available content width)
        val desktopContentDp = 1280f
        val desktopLeft50 = computeSplitPaneLeftWidthDp(desktopContentDp, 0.50f)
        assertTrue(desktopLeft50 in 638f..641f)

        // 5. Persistence hook in UserApiKeyAuthManager
        val prevPersist = UserApiKeyAuthManager.onPersistSplitFraction
        val prevFraction = UserApiKeyAuthManager.uiSplitFraction
        var persistedValue: Float? = null
        try {
            UserApiKeyAuthManager.onPersistSplitFraction = { persistedValue = it }
            UserApiKeyAuthManager.updateSplitFractionPref(0.51f)
            assertEquals(0.51f, UserApiKeyAuthManager.uiSplitFraction)
            assertEquals(0.51f, persistedValue)

            UserApiKeyAuthManager.updateSplitFractionPref(0.99f)
            assertEquals(MAX_SPLIT_PANE_FRACTION, UserApiKeyAuthManager.uiSplitFraction)
            assertEquals(MAX_SPLIT_PANE_FRACTION, persistedValue)
        } finally {
            UserApiKeyAuthManager.onPersistSplitFraction = prevPersist
            UserApiKeyAuthManager.uiSplitFraction = prevFraction
        }
    }

    @Test
    fun r6_backgroundSyncIsolationForegroundRetryAndNetworkRestoreRecovery() = runTest {
        val observedRequests = mutableListOf<Pair<String, Boolean>>()
        var latestAttemptCount = 0
        withMockHttp({ url, _, _, headers ->
            val isBg = headers["Discourse-Background"] == "true"
            synchronized(observedRequests) {
                observedRequests.add(url to isBg)
            }
            when {
                url.contains("/site.json") -> 200 to """{"categories":[]}"""
                url.contains("/emojis.json") -> 200 to """{"default":[{"name":"smile","url":"/images/emoji/twitter/smile.png"}]}"""
                url.contains("/badges.json") -> 200 to """{"badges":[],"badge_groupings":[]}"""
                url.contains("/user-badges/") -> 200 to """{"user_badges":[],"badges":[],"badge_types":[]}"""
                url.contains("/latest.json") -> {
                    latestAttemptCount++
                    if (latestAttemptCount == 1) {
                        // Simulate transient VPN/5G drop on attempt 0
                        0 to ""
                    } else {
                        200 to """
                            {
                              "users":[{"id":1,"username":"neo","avatar_template":"/user_avatar/linux.do/neo/{size}/136369_2.png"}],
                              "topic_list":{"topics":[{"id":9901,"title":"Resilient Topic","posts_count":5,"highest_post_number":5,"posters":[{"description":"Original Poster","user_id":1}]}]}
                            }
                        """.trimIndent()
                    }
                }
                url.contains("/429_probe.json") -> 429 to """{"errors":["Slow down"]}"""
                else -> 404 to """{"errors":["not found"]}"""
            }
        }) {
            // 1. Background sync endpoints must carry Discourse-Background: true so Android isolates them in background semaphore
            LinuxDoRepository.syncSiteCategories(force = true, background = true)
            LinuxDoRepository.syncSiteEmojis(force = true, background = true)
            LinuxDoRepository.fetchAllSiteBadges(force = true, background = true)
            LinuxDoRepository.fetchUserBadges("neo_bg_probe", background = true)

            val bgByPath = synchronized(observedRequests) { observedRequests.toList() }
            assertTrue(bgByPath.any { it.first.contains("/site.json") && it.second }, "syncSiteCategories must mark Discourse-Background=true")
            assertTrue(bgByPath.any { it.first.contains("/emojis.json") && it.second }, "syncSiteEmojis must mark Discourse-Background=true")
            assertTrue(bgByPath.any { it.first.contains("/badges.json") && it.second }, "fetchAllSiteBadges(background=true) must mark Discourse-Background=true")
            assertTrue(bgByPath.any { it.first.contains("/user-badges/") && it.second }, "fetchUserBadges(background=true) must mark Discourse-Background=true")

            // 2. Foreground fetchTopics must automatically retry transient connection drop (status 0) and succeed without throwing NetworkError
            val topics = LinuxDoRepository.fetchTopics(path = FeedSortFilter.LATEST.apiPath, categoryId = null, page = 0)
            assertEquals(2, latestAttemptCount, "Foreground read must retry once after transient drop")
            assertEquals(1, topics.size)
            assertEquals(9901, topics.first().id)

            // 3. Network restore signal clears transient rate limit lock and notifies UI listener
            runCatching {
                LinuxDoRepository.fetchTopics(path = "/429_probe.json", categoryId = null, page = 0)
            }
            assertTrue(LinuxDoRepository.isRateLimitedNow())
            var restoreCount = 0
            val prevRestore = UserApiKeyAuthManager.onNetworkRestored
            try {
                UserApiKeyAuthManager.onNetworkRestored = { restoreCount++ }
                UserApiKeyAuthManager.notifyNetworkRestored()
                assertFalse(LinuxDoRepository.isRateLimitedNow(), "notifyNetworkRestored must clear transient rate limit cooldown")
                assertEquals(1, restoreCount)
            } finally {
                UserApiKeyAuthManager.onNetworkRestored = prevRestore
            }
        }
    }

    @Test
    fun r7_barPhoneAndDualFoldLayoutAdaptationAndSplitDragBounds() {
        // 1. Android bar phones portrait -> Single-column mobile layout
        assertFalse(shouldUseWideSplitLayout(widthDp = 360f, heightDp = 800f))
        assertFalse(shouldUseWideSplitLayout(widthDp = 393f, heightDp = 873f))
        assertFalse(shouldUseWideSplitLayout(widthDp = 412f, heightDp = 915f))

        // 2. Android dual-fold folded cover screens -> Single-column mobile layout
        assertFalse(shouldUseWideSplitLayout(widthDp = 344f, heightDp = 832f)) // Galaxy Z Fold cover
        assertFalse(shouldUseWideSplitLayout(widthDp = 390f, heightDp = 844f)) // Mate X / Find N cover

        // 3. Android dual-fold unfolded inner screens (BOTH portrait ~673-760dp and landscape ~765-880dp) -> Dual-pane master-detail
        assertTrue(shouldUseWideSplitLayout(widthDp = 673f, heightDp = 829f))  // Galaxy Z Fold unfolded portrait
        assertTrue(shouldUseWideSplitLayout(widthDp = 712f, heightDp = 765f))  // Find N / OnePlus Open unfolded portrait
        assertTrue(shouldUseWideSplitLayout(widthDp = 736f, heightDp = 816f))  // Mate X / vivo X Fold unfolded portrait
        assertTrue(shouldUseWideSplitLayout(widthDp = 829f, heightDp = 673f))  // Dual-fold unfolded landscape

        // 4. Dual-fold split-screen multi-window half-pane -> Single-column mobile layout
        assertFalse(shouldUseWideSplitLayout(widthDp = 356f, heightDp = 829f))

        // 5. Dual-fold portrait inner screen (~673dp - 64dp compact rail = 609dp content width) must have smooth drag range >= 70dp
        val zFoldContentDp = 609f
        val (minLeft, maxLeft) = computeSplitPaneBoundsDp(zFoldContentDp)
        assertTrue(maxLeft - minLeft >= 70f, "Dual-fold portrait inner screen must provide >=70dp drag range, got ${maxLeft - minLeft}")
        val defaultLeft = computeSplitPaneLeftWidthDp(zFoldContentDp, DEFAULT_SPLIT_PANE_FRACTION)
        assertTrue(defaultLeft in minLeft..maxLeft)
        val draggedFraction = computeUpdatedSplitFractionAfterDrag(zFoldContentDp, defaultLeft, 36f)
        val afterDragLeft = computeSplitPaneLeftWidthDp(zFoldContentDp, draggedFraction)
        assertTrue(afterDragLeft > defaultLeft + 30f)
    }

    @Test
    fun r7_transientErrorClassificationAndSelfHealingManualRecovery() = runTest {
        // 1. Every transient ApiFailureKind message must be recognized for automatic & manual recovery
        assertTrue(isTransientRecoverableLoadError(ApiReadException(ApiFailureKind.Connection).message))
        assertTrue(isTransientRecoverableLoadError(ApiReadException(ApiFailureKind.RateLimited).message))
        assertTrue(isTransientRecoverableLoadError(ApiReadException(ApiFailureKind.Server).message))
        assertTrue(isTransientRecoverableLoadError(ApiReadException(ApiFailureKind.InvalidPayload).message))
        assertTrue(isTransientRecoverableLoadError("网络异常"))
        assertTrue(isTransientRecoverableLoadError("加载失败"))
        assertTrue(isTransientRecoverableLoadError("请求太频繁，请稍后再试"))

        // 2. Deterministic auth / permission / Cloudflare verification / 404 errors must NOT loop-retry blindly
        assertFalse(isTransientRecoverableLoadError(ApiReadException(ApiFailureKind.Verification).message))
        assertFalse(isTransientRecoverableLoadError("需要完成站点验证"))
        assertFalse(isTransientRecoverableLoadError(ApiReadException(ApiFailureKind.Authentication).message))
        assertFalse(isTransientRecoverableLoadError(ApiReadException(ApiFailureKind.PermissionDenied).message))
        assertFalse(isTransientRecoverableLoadError("当前信任等级暂无权访问该版块"))
        assertFalse(isTransientRecoverableLoadError("这篇话题已不可用"))
        assertFalse(isTransientRecoverableLoadError(null))

        // 3. Poisoned transport self-heals via onPrepareManualRecovery between attempt 0 (status 0) and attempt 1 (status 200)
        var transportPoisoned = true
        var recoveryCalls = 0
        val prevRecovery = UserApiKeyAuthManager.onPrepareManualRecovery
        try {
            UserApiKeyAuthManager.onPrepareManualRecovery = {
                recoveryCalls++
                transportPoisoned = false
            }
            withMockHttp({ url, _, _, _ ->
                if (url.contains("/latest.json")) {
                    if (transportPoisoned) {
                        0 to ""
                    } else {
                        200 to """
                            {
                              "users":[{"id":1,"username":"neo","avatar_template":"/user_avatar/linux.do/neo/{size}/136369_2.png"}],
                              "topic_list":{"topics":[{"id":9902,"title":"Recovered After Poisoned Bridge","posts_count":2,"highest_post_number":2,"posters":[{"description":"Original Poster","user_id":1}]}]}
                            }
                        """.trimIndent()
                    }
                } else if (url.contains("/429_seed.json")) {
                    429 to """{"errors":["Slow down"]}"""
                } else {
                    404 to """{"errors":["not found"]}"""
                }
            }) {
                val recoveredTopics = LinuxDoRepository.fetchTopics(path = FeedSortFilter.LATEST.apiPath, categoryId = null, page = 0)
                assertEquals(1, recoveryCalls, "Transport failure on attempt 0 must invoke onPrepareManualRecovery before attempt 1")
                assertEquals(1, recoveredTopics.size)
                assertEquals(9902, recoveredTopics.first().id)

                // Manual refresh preparation also clears transient 429 cooldown and invokes transport health check
                runCatching { LinuxDoRepository.fetchTopics(path = "/429_seed.json", categoryId = null, page = 0) }
                assertTrue(LinuxDoRepository.isRateLimitedNow())
                LinuxDoRepository.prepareForManualRecovery()
                assertFalse(LinuxDoRepository.isRateLimitedNow())
                assertEquals(2, recoveryCalls)
            }
        } finally {
            UserApiKeyAuthManager.onPrepareManualRecovery = prevRecovery
        }
    }

    @Test
    fun r7_linuxDoImagesNeverLeakToOkHttpWhenWebViewBinaryBridgeMounted() = runTest {
        val prevBinary = UserApiKeyAuthManager.webViewBinaryFetcher
        var bridgeCalls = 0
        try {
            // Simulate WebView binary bridge timeout returning null for a linux.do emoji/upload asset
            UserApiKeyAuthManager.webViewBinaryFetcher = {
                bridgeCalls++
                null
            }
            val resp = LinuxDoRepository.imageHttpClient.get(
                "https://linux.do/images/emoji/twitter/rocket.png?v=12&_r=12345"
            )
            // Must immediately return synthetic 504 from interceptor rather than dialing linux.do with OkHttp Java TLS + Chrome UA
            assertEquals(504, resp.status.value)
            assertEquals(1, bridgeCalls)
        } finally {
            UserApiKeyAuthManager.webViewBinaryFetcher = prevBinary
        }
    }
}
