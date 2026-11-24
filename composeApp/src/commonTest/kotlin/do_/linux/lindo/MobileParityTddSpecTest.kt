package do_.linux.lindo

import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.model.TopicFilterTransition
import do_.linux.lindo.core.model.TopicRepliesFilterMode
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.render.CodeSyntaxHighlighter
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.MarkdownPreview
import do_.linux.lindo.core.render.PostBlockNode
import do_.linux.lindo.core.site.AdvancedSearchFilter
import do_.linux.lindo.core.site.AuthorFeedbackSpec
import do_.linux.lindo.core.site.buildAdvancedSearchQuery
import do_.linux.lindo.core.site.parseShortPostPermalink
import do_.linux.lindo.ui.parseSmartTopicUrlTarget
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Specification-driven TDD test suite for Android/KMP parity with Linux.do Web & PC Lindo.
 * Every assertion models real Linux.do HTML payloads, Discourse APIs, link formats, and failure modes.
 */
class MobileParityTddSpecTest {

    @Test
    fun t1_sluglessTopicUrlWithFloorMustNotSwallowTopicIdAsSlug() {
        val slugless = parseSmartTopicUrlTarget("https://linux.do/t/3155790/2")
        assertEquals(3155790 to 2, slugless, "Greedy regex must not treat 3155790 as slug and 2 as topicId!")

        val protocolRelative = parseSmartTopicUrlTarget("//linux.do/t/topic/2496114/18")
        assertEquals(2496114 to 18, protocolRelative, "Protocol-relative //linux.do/t/... must resolve cleanly")

        val withSlugAndLast = parseSmartTopicUrlTarget("https://linux.do/t/relayrouter-gemini/3155790/last?u=jedilus")
        assertEquals(3155790 to 999_999, withSlugAndLast)

        val topicOnly = parseSmartTopicUrlTarget("https://linux.do/t/3209343")
        assertEquals(3209343 to null, topicOnly)

        assertEquals(9876543, parseShortPostPermalink("https://linux.do/p/9876543"))
        assertEquals(9876543, parseShortPostPermalink("/p/9876543"))
        assertNull(parseShortPostPermalink("https://example.com/p/9876543"))
    }

    @Test
    fun t2_obsidianFoldableCalloutFromRealLinuxDoPostMustRenderAsCalloutBlock() {
        val realHtml = """
            <blockquote>
              <p>[!Note]+ ~/.claude/settings.json</p>
              <pre><code class="lang-json">{"model": "opus[1m]"}</code></pre>
            </blockquote>
            <blockquote>
              <p>[!Warning]- 高危操作默认折叠</p>
              <p>请务必提前备份配置</p>
            </blockquote>
        """.trimIndent()

        val nodes = DiscourseAstParser.parseCookedHtml(realHtml)
        val callouts = nodes.filterIsInstance<PostBlockNode.CalloutBlock>()
        assertEquals(2, callouts.size, "Both [!Note]+ and [!Warning]- blockquotes must parse as CalloutBlock")

        val note = callouts[0]
        assertEquals("note", note.kind)
        assertEquals("~/.claude/settings.json", note.title)
        assertTrue(note.collapsible, "[!Note]+ must mark collapsible=true")
        assertTrue(note.defaultExpanded, "[!Note]+ must mark defaultExpanded=true")
        assertTrue(note.children.any { it is PostBlockNode.CodeBlock }, "Callout body must preserve inner JSON CodeBlock")

        val warn = callouts[1]
        assertEquals("warning", warn.kind)
        assertEquals("高危操作默认折叠", warn.title)
        assertTrue(warn.collapsible, "[!Warning]- must mark collapsible=true")
        assertFalse(warn.defaultExpanded, "[!Warning]- must mark defaultExpanded=false")
    }

    @Test
    fun t3_bbcodeAndFontColorsMustSurviveAstParsingAndRejectUnsafeCss() {
        val realHtml = """
            <p>env对象属性值全部必须写为 <span class="bbcode-color" style="color: #ef4444"><strong>字符串</strong></span> 而不是布尔值！</p>
            <p><font color="green">允许绿色</font> 和 <span style="background-color: #fef08a">背景高亮</span> <span style="color: url(javascript:alert(1))">安全文本</span></p>
        """.trimIndent()

        val nodes = DiscourseAstParser.parseCookedHtml(realHtml)
        val paragraphs = nodes.filterIsInstance<PostBlockNode.Paragraph>()
        assertEquals(2, paragraphs.size)

        val redRun = paragraphs[0].rich.runs.firstOrNull { it.text.contains("字符串") }
        assertNotNull(redRun)
        assertEquals("#EF4444", redRun.colorHex)
        assertTrue(redRun.bold)

        val greenRun = paragraphs[1].rich.runs.firstOrNull { it.text.contains("允许绿色") }
        assertNotNull(greenRun)
        assertEquals("#22C55E", greenRun.colorHex)

        val bgRun = paragraphs[1].rich.runs.firstOrNull { it.text.contains("背景高亮") }
        assertNotNull(bgRun)
        assertEquals("#FEF08A", bgRun.bgColorHex)

        val unsafeRun = paragraphs[1].rich.runs.firstOrNull { it.text.contains("安全文本") }
        assertNotNull(unsafeRun)
        assertNull(unsafeRun.colorHex, "Unsafe CSS color value must be rejected")
    }

    @Test
    fun t4_crossTopicQuoteMustPreserveTitleAndProtocolRelativeLinkAndExpandFlag() {
        val realHtml = """
            <aside class="quote quote-modified" data-post="1" data-topic="2496114">
              <div class="title">
                <div class="quote-controls"></div>
                <img loading="lazy" alt="" width="24" height="24" src="https://cdn.ldstatic.com/user_avatar/linux.do/harley/48/1_2.png" class="avatar">
                <a href="//linux.do/t/any-router-guide/2496114">Any牌路由器使用清障！</a>
              </div>
              <blockquote>
                <p>3s内报403 你就等，别瞎琢磨哪配错了</p>
              </blockquote>
            </aside>
        """.trimIndent()

        val nodes = DiscourseAstParser.parseCookedHtml(realHtml)
        val quote = nodes.filterIsInstance<PostBlockNode.QuoteBlock>().firstOrNull()
        assertNotNull(quote)
        assertEquals(2496114, quote.topicId)
        assertEquals(1, quote.postNumber)
        assertEquals("Any牌路由器使用清障！", quote.title)
        assertEquals("https://linux.do/t/any-router-guide/2496114", quote.titleUrl)
        assertFalse(quote.isFull, "Partial quote must expose isFull=false so inline expand is available")
    }

    @Test
    fun t5_markdownPreviewMustSupportFoldableCalloutColorMathTableAndQuote() {
        val md = """
            > [!Note]+ ~/.claude/settings.json
            > ```json
            > {"model": "opus"}
            > ```

            必须写为 [color=red]**字符串**[/color] 且公式 ${'$'}E = mc^2${'$'}

            - 紧跟表格无空行
            | 项目 | 状态 |
            | --- | --- |
            | 预览 | 正常 |

            [quote="neo, post:1, topic:100"]
            欢迎来到社区
            [/quote]
        """.trimIndent()

        val renderedHtml = MarkdownPreview.render(md)
        val nodes = DiscourseAstParser.parseCookedHtml(renderedHtml)

        val callout = nodes.filterIsInstance<PostBlockNode.CalloutBlock>().firstOrNull()
        assertNotNull(callout, "MarkdownPreview must parse Obsidian [!Note]+ custom-title callout")
        assertEquals("~/.claude/settings.json", callout.title)
        assertTrue(callout.collapsible)
        assertTrue(callout.defaultExpanded)

        val coloredRun = nodes.filterIsInstance<PostBlockNode.Paragraph>()
            .flatMap { it.rich.runs }
            .firstOrNull { !it.colorHex.isNullOrBlank() }
        assertNotNull(coloredRun, "MarkdownPreview must preserve BBCode [color=red]")

        assertTrue(nodes.any { it is PostBlockNode.Table }, "GFM table after list without blank line must render as Table")

        val quote = nodes.filterIsInstance<PostBlockNode.QuoteBlock>().firstOrNull()
        assertNotNull(quote)
        assertEquals("neo", quote.username)
        assertEquals(1, quote.postNumber)
        assertEquals(100, quote.topicId)
    }

    @Test
    fun t6_authorizedPrivateMessageTransient404MustSelfHealAndRetryOnce() = runTest {
        val prevHttp = UserApiKeyAuthManager.webViewHttpExecutor
        val prevRecovery = UserApiKeyAuthManager.onPrepareManualRecovery
        val prevUser = UserApiKeyAuthManager.currentUser
        CookieSentinel.clearAll()
        LinuxDoRepository.prepareForManualRecovery()
        try {
            CookieSentinel.ingestRawCookieString("_t=valid_pm_session_token; cf_clearance=cf_ok", domain = "linux.do")
            UserApiKeyAuthManager.recordWebSessionLogin(
                username = "jedilus",
                displayName = "Jedilus",
                avatarUrl = "https://linux.do/user_avatar/linux.do/jedilus/144/1.png",
                trustLevel = 3,
                userId = 1001
            )
            var attempts = 0
            var recoveryCalls = 0
            UserApiKeyAuthManager.onPrepareManualRecovery = { recoveryCalls++ }
            UserApiKeyAuthManager.webViewHttpExecutor = { url, method, _, _ ->
                if (url.contains("/t/3209343.json") && method == "GET") {
                    attempts++
                    if (attempts == 1) {
                        404 to """{"errors":["The requested URL or resource could not be found."],"error_type":"not_found"}"""
                    } else {
                        200 to """
                            {
                              "id": 3209343,
                              "title": "LINUX DO Mail 密码信息",
                              "archetype": "private_message",
                              "posts_count": 1,
                              "post_stream": {
                                "stream": [5001],
                                "posts": [{
                                  "id": 5001,
                                  "post_number": 1,
                                  "user_id": 1,
                                  "username": "neo",
                                  "cooked": "<p>您的邮箱账号已开通</p>",
                                  "created_at": "2026-11-20T06:00:00Z"
                                }]
                              },
                              "details": {
                                "notification_level": 3,
                                "allowed_users": [{"id": 1, "username": "neo", "name": "Neo"}]
                              }
                            }
                        """.trimIndent()
                    }
                } else {
                    200 to "{}"
                }
            }

            val detail = LinuxDoRepository.fetchTopicDetail(topicId = 3209343, trackVisit = false)
            assertEquals(2, attempts, "Authorized /t/{id}.json 404 must recover cookies and retry once!")
            assertTrue(recoveryCalls >= 1, "Must invoke cookie/transport recovery before retrying PM 404")
            assertNull(detail.loadError)
            assertFalse(detail.unavailable)
            assertEquals("private_message", detail.archetype)
            assertEquals(1, detail.allowedUsers.size)
            assertEquals("neo", detail.allowedUsers.first().username)
        } finally {
            UserApiKeyAuthManager.webViewHttpExecutor = prevHttp
            UserApiKeyAuthManager.onPrepareManualRecovery = prevRecovery
            if (prevUser == null) UserApiKeyAuthManager.logout()
            CookieSentinel.clearAll()
        }
    }

    @Test
    fun t7_compactFeedAvatarAndNoQiangShaFaPolicyAndAuthorFeedbackEntry() {
        val projectRoot = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "composeApp/src/commonMain/kotlin/do_/linux/lindo/ui/LinDoApp.kt").exists() }
            ?: error("Cannot locate repo root")

        val linDoAppSrc = File(projectRoot, "composeApp/src/commonMain/kotlin/do_/linux/lindo/ui/LinDoApp.kt").readText()
        val aboutSrc = File(projectRoot, "composeApp/src/commonMain/kotlin/do_/linux/lindo/ui/LindoAboutPage.kt").readText()

        val compactBlockIdx = linDoAppSrc.indexOf("if (isCompact) {")
        assertTrue(compactBlockIdx > 0)
        val compactSnippet = linDoAppSrc.substring(compactBlockIdx, (compactBlockIdx + 1200).coerceAtMost(linDoAppSrc.length))
        val avatarSizeMatch = Regex("""size\s*=\s*(\d+)\.dp""").find(compactSnippet)
        assertNotNull(avatarSizeMatch)
        assertTrue(avatarSizeMatch.groupValues[1].toInt() >= 36)

        val commonMainDir = File(projectRoot, "composeApp/src/commonMain/kotlin")
        val forbiddenHits = commonMainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("抢沙发") }
            .map { it.name }
            .toList()
        assertTrue(forbiddenHits.isEmpty(), "Forbidden copy '抢沙发' found in: $forbiddenHits")

        assertTrue(linDoAppSrc.contains("问题与建议反馈"))
        assertTrue(linDoAppSrc.contains("扫码 / 口令登录"))
        assertTrue(linDoAppSrc.contains("LinDoMessagesCopy.DIAGNOSTICS_SHEET_TITLE.text"))
        assertFalse(linDoAppSrc.contains("CommunityActivitySummary(it)"))
        assertTrue(aboutSrc.contains("站内搜索"))
        assertFalse(aboutSrc.contains("检查更新"))
        assertFalse(aboutSrc.contains("诊断与崩溃日志"))
    }

    @Test
    fun t8_hotSummaryFilterAndSeamlessInPlaceTransition() = runTest {
        val prevHttp = UserApiKeyAuthManager.webViewHttpExecutor
        LinuxDoRepository.prepareForManualRecovery()
        try {
            var capturedUrl = ""
            UserApiKeyAuthManager.webViewHttpExecutor = { url, _, _, _ ->
                capturedUrl = url
                200 to """
                    {
                      "id": 3155790,
                      "title": "Gemini 4 Carbon",
                      "posts_count": 403,
                      "has_summary": true,
                      "post_stream": {
                        "stream": [1001, 1018, 1042, 1099],
                        "posts": [
                          {"id": 1001, "post_number": 1, "user_id": 10, "username": "RelayRouter", "cooked": "<p>Main</p>", "version": 3, "can_view_edit_history": true},
                          {"id": 1018, "post_number": 18, "user_id": 20, "username": "alice", "cooked": "<p>Hot 1</p>"},
                          {"id": 1042, "post_number": 42, "user_id": 21, "username": "bob", "cooked": "<p>Hot 2</p>"}
                        ]
                      },
                      "suggested_topics": [
                        {"id": 3155999, "title": "相关实测贴", "posts_count": 12, "category_id": 4}
                      ]
                    }
                """.trimIndent()
            }

            val freshSummary = LinuxDoRepository.fetchTopicDetail(
                topicId = 3155790,
                trackVisit = false,
                filterSummary = true
            )
            assertTrue(capturedUrl.contains("filter=summary"), "Expected filter=summary query param, got: $capturedUrl")
            assertTrue(freshSummary.hasSummary)
            assertEquals(1, freshSummary.suggestedTopics.size)
            assertEquals(3, freshSummary.posts.first().version)
            assertTrue(freshSummary.posts.first().canViewEditHistory)

            val prevPost1 = PostItemModel(id = 1001, postNumber = 1, userId = 10, username = "RelayRouter", cookedHtml = "<p>Main</p>", version = 3)
            val prevAll = TopicDetailModel(
                id = 3155790,
                title = "Gemini 4 Carbon",
                categoryName = "福利羊毛",
                postsCount = 403,
                posts = listOf(
                    prevPost1,
                    PostItemModel(id = 1002, postNumber = 2, userId = 22, username = "u2", cookedHtml = "<p>old2</p>"),
                    PostItemModel(id = 1003, postNumber = 3, userId = 23, username = "u3", cookedHtml = "<p>old3</p>")
                ),
                streamIds = listOf(1001, 1002, 1003)
            )

            val merged = TopicFilterTransition.applyFilterResult(prevAll, freshSummary)
            // Post #1 object reference MUST be preserved so Compose LazyColumn keeps item #1 untouched!
            assertSame(prevPost1, merged.posts.first())
            // Old unfiltered replies #2 and #3 MUST NOT leak into summary replies!
            assertEquals(listOf(1, 18, 42), merged.posts.map { it.postNumber })

            val bar = TopicFilterTransition.describeFilterBar(merged, TopicRepliesFilterMode.SUMMARY)
            assertEquals(3, bar.filteredReplyCount)
            assertTrue(bar.label.contains("热门精选"))
            assertTrue(bar.label.contains("3"))
        } finally {
            UserApiKeyAuthManager.webViewHttpExecutor = prevHttp
        }
    }

    @Test
    fun t9_codeSyntaxHighlighterAndMermaidParser() {
        val json = """{"model": "opus[1m]", "enabled": true, "days": 720}"""
        val highlightedJson = CodeSyntaxHighlighter.highlight(json, "json", isDark = true)
        assertTrue(highlightedJson.spanStyles.size >= 5, "JSON highlighter must emit distinct spans for keys/strings/booleans/numbers")

        val kt = """
            // Sample Kotlin
            fun greet(name: String): Boolean {
                val count = 42
                return true
            }
        """.trimIndent()
        val highlightedKt = CodeSyntaxHighlighter.highlight(kt, "kotlin", isDark = false)
        assertTrue(highlightedKt.spanStyles.size >= 5, "Kotlin highlighter must emit distinct spans for comments/keywords/numbers/literals")

        assertTrue(CodeSyntaxHighlighter.isMermaidLanguage("mermaid"))
        val mermaid = CodeSyntaxHighlighter.parseMermaidPreview("flowchart LR\n  A[PC] --> B[Android]\n  B -.-> C[Web]")
        assertTrue(mermaid.diagramType.contains("流程图"))
        assertEquals(2, mermaid.lines.size)
        assertTrue(mermaid.lines[0].contains("→"))
    }

    @Test
    fun t10_authorFeedbackBuilderTargetsJedilusAndRedactsSecrets() {
        assertEquals("jedilus", AuthorFeedbackSpec.AUTHOR_USERNAME)
        val (title, body) = AuthorFeedbackSpec.buildPayload(
            category = AuthorFeedbackSpec.Category.BUG,
            subject = "热门回复滚动问题",
            body = "点击热门回复时会跳回顶楼",
            appVersion = "Lindo 0.2.41",
            platformInfo = "Android 15",
            includeDeviceInfo = true,
            diagnosticSnippet = "Cookie: _t=super_secret_token; cf_clearance=cf_secret_val; _forum_session=sess_123"
        )
        assertEquals("[Bug 反馈] 热门回复滚动问题", title)
        assertTrue(body.contains("Lindo 0.2.41"))
        assertFalse(body.contains("super_secret_token"), "Must never leak _t token!")
        assertFalse(body.contains("cf_secret_val"), "Must never leak cf_clearance!")
        assertFalse(body.contains("sess_123"), "Must never leak _forum_session!")
        assertTrue(body.contains("[REDACTED]"))
    }

    @Test
    fun t11_readerEnhancementApisAndAdvancedSearchQuery() = runTest {
        val q = buildAdvancedSearchQuery(
            keyword = "Claude",
            filter = AdvancedSearchFilter(
                categorySlug = "develop",
                tag = "人工智能",
                author = "@neo",
                inTitle = true,
                solved = true,
                afterDateIso = "2026-11-01"
            )
        )
        assertEquals("Claude #develop tags:人工智能 @neo in:title status:solved after:2026-11-01", q)

        val prevHttp = UserApiKeyAuthManager.webViewHttpExecutor
        LinuxDoRepository.prepareForManualRecovery()
        try {
            UserApiKeyAuthManager.webViewHttpExecutor = { url, method, _, _ ->
                when {
                    url.contains("/posts/777/revisions/latest.json") -> 200 to """
                        {
                          "current_revision": 3,
                          "previous_revision": 2,
                          "version_count": 3,
                          "username": "jedilus",
                          "edit_reason": "补充配置截图",
                          "body_changes": {"inline": "<ins>新增说明</ins>"}
                        }
                    """.trimIndent()
                    url.contains("/discourse-reactions/posts/777/reactions-users.json") -> 200 to """
                        {
                          "reaction_users": [
                            {"id": "heart", "count": 2, "users": [{"username": "neo", "name": "Neo"}]}
                          ]
                        }
                    """.trimIndent()
                    url.contains("/polls/voters.json") -> 200 to """
                        {
                          "voters": {"opt1": [{"username": "alice", "name": "Alice"}]}
                        }
                    """.trimIndent()
                    url.contains("/posts/9876543.json") -> 200 to """{"id": 9876543, "topic_id": 2496114, "post_number": 12}"""
                    url.contains("/bookmarks.json") && method == "POST" -> 200 to """{"id": 5566}"""
                    else -> 404 to "{}"
                }
            }

            val rev = LinuxDoRepository.fetchPostRevisions(777)
            assertNotNull(rev)
            assertEquals(3, rev.currentRevision)
            assertEquals("补充配置截图", rev.editReason)

            val reactGroups = LinuxDoRepository.fetchReactionUsers(777)
            assertEquals(1, reactGroups.size)
            assertEquals("neo", reactGroups.first().users.first().username)

            val voters = LinuxDoRepository.fetchPollVoters(777, "poll", "opt1")
            assertEquals(1, voters.size)
            assertEquals("alice", voters.first().username)

            val loc = LinuxDoRepository.fetchPostLocationById(9876543)
            assertEquals(2496114 to 12, loc)

            val bmId = LinuxDoRepository.saveBookmarkWithReminder(777, name = "周末再看", reminderAtIso = "2026-11-22T09:00:00Z")
            assertEquals(5566, bmId)
        } finally {
            UserApiKeyAuthManager.webViewHttpExecutor = prevHttp
        }
    }

    @Test
    fun t12_iframeFriendlyLabelAndInPageFootnotePreservation() {
        val html = """
            <p>详情见脚注<a class="footnote-ref" href="#fn1">[1]</a></p>
            <iframe src="https://player.bilibili.com/player.html?bvid=BV1xx411c7mD"></iframe>
        """.trimIndent()
        val nodes = DiscourseAstParser.parseCookedHtml(html)
        val p = nodes.filterIsInstance<PostBlockNode.Paragraph>().first()
        val fnRun = p.rich.runs.firstOrNull { it.href == "#fn1" }
        assertNotNull(fnRun, "In-page footnote anchor #fn1 must be preserved without turning into external link")

        val media = nodes.filterIsInstance<PostBlockNode.Media>().firstOrNull()
        assertNotNull(media)
        assertTrue(media.label.contains("Bilibili"), "Bilibili iframe must display friendly platform label, got: ${media.label}")
    }
}
