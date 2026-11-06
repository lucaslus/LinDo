package do_.linux.lindo

import do_.linux.lindo.core.compose.*
import do_.linux.lindo.core.model.*
import do_.linux.lindo.core.network.*
import do_.linux.lindo.core.render.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import kotlin.test.*

class ComposerAndSafetyTest {
    private fun owner() = "test-${UUID.randomUUID()}"
    private suspend fun withTransport(handler: suspend (String, String, Map<String, String>?) -> Pair<Int, String>, block: suspend () -> Unit) {
        val previous = UserApiKeyAuthManager.webViewHttpExecutor
        try { UserApiKeyAuthManager.webViewHttpExecutor = { url, method, form, _ -> handler(url, method, form) }; block() }
        finally { UserApiKeyAuthManager.webViewHttpExecutor = previous }
    }

    @Test fun rawEditingRequiresTheActualSourceAndKeepsItsTopic() = runTest {
        withTransport({ _, _, _ -> 200 to """{"id":88,"topic_id":11,"post_number":3,"username":"writer","raw":"**source**","cooked":"<p><strong>source</strong></p>"}""" }) {
            val post = LinuxDoRepository.fetchPostForEditing(88)
            assertEquals("**source**", post.raw)
            assertEquals(11, post.topicId)
        }
        withTransport({ _, _, _ -> 200 to """{"id":88,"topic_id":11,"post_number":3,"username":"writer","cooked":"<p>source</p>"}""" }) {
            assertFailsWith<ApiMutationException> { LinuxDoRepository.fetchPostForEditing(88) }
        }
    }

    @Test fun draftRestorationPreservesActionAndPostIdentity() = runTest {
        val data = buildJsonObject { put("reply", "edited source"); put("action", "edit"); put("postId", 88); put("topicId", 11) }.toString()
        val response = buildJsonObject { put("drafts", buildJsonArray { add(buildJsonObject {
            put("draft_key", "edit_post_88"); put("sequence", 7); put("data", data)
        }) }) }.toString()
        withTransport({ _, _, _ -> 200 to response }) {
            val draft = LinuxDoRepository.fetchCloudDrafts().single()
            assertEquals("edit", draft.action); assertEquals(88, draft.postId); assertEquals(11, draft.topicId); assertEquals(7, draft.sequence)
            assertNull(draft.replyToPostNumber)
        }
    }

    @Test fun localDraftsRecoverImmediatelyAndAreAccountScoped() {
        val alice = owner(); val bob = owner()
        val draft = ComposerDraft(alice, "topic_1", ComposerAction.REPLY, raw = "not yet synced", topicId = 1)
        LocalComposerDrafts.save(draft)
        assertEquals(draft.raw, LocalComposerDrafts.get(alice, "topic_1")?.raw)
        assertNull(LocalComposerDrafts.get(bob, "topic_1"))
        LocalComposerDrafts.remove(alice, "topic_1")
        assertNull(LocalComposerDrafts.get(alice, "topic_1"))
    }

    @Test fun cloudDraftUsesTheRealSequenceAndValidJson() = runTest {
        val account = owner()
        val draft = ComposerDraft(account, "new_topic", ComposerAction.NEW_TOPIC, raw = "before\tafter\n\"quote\"", title = "draft title", tags = listOf("tag"))
        val session = ComposerDraftSession(draft)
        session.update(draft)
        var observedSequence: String? = null
        withTransport({ _, method, form ->
            if (method == "GET") 200 to """{"draft":null,"draft_sequence":9}"""
            else {
                observedSequence = form?.get("sequence")
                val data = Json.parseToJsonElement(form!!.getValue("data")).jsonObject
                assertEquals(draft.raw, data["reply"]?.jsonPrimitive?.content)
                assertFalse(form.getValue("data").contains('\t'), "Wire JSON must escape literal tab characters")
                200 to """{"draft_sequence":10}"""
            }
        }) { assertTrue(session.sync()) }
        assertEquals("9", observedSequence); assertEquals(10, session.savedSequence); assertTrue(session.isSynced())
    }

    @Test fun publicationWaitsForDraftWriteAndNeverRecreatesIt() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val draft = ComposerDraft(owner(), "new_topic", ComposerAction.NEW_TOPIC, raw = "body text", title = "draft title", sequence = 4)
        val session = ComposerDraftSession(draft); session.update(draft)
        val events = mutableListOf<String>()
        withTransport({ _, method, _ -> when (method) {
            "POST" -> { events += "save"; started.complete(Unit); release.await(); 200 to """{"draft_sequence":5}""" }
            "DELETE" -> { events += "delete"; 200 to """{"success":true}""" }
            else -> 200 to """{"draft":null,"draft_sequence":5}"""
        } }) {
            val saving = async { session.sync() }; started.await()
            val publishing = async { session.publish { events += "publish"; 101 } }
            assertEquals(listOf("save"), events)
            release.complete(Unit); assertTrue(saving.await()); assertEquals(101, publishing.await())
            assertTrue(session.sync())
            assertEquals(listOf("save", "publish", "delete"), events)
            assertNull(LocalComposerDrafts.get(draft.owner, draft.key))
        }
    }

    @Test fun changingOnlyTagsMarksTheSessionUnsynced() = runTest {
        val draft = ComposerDraft(owner(), "new_topic", ComposerAction.NEW_TOPIC, raw = "body text", sequence = 1)
        val session = ComposerDraftSession(draft)
        var writtenTags = emptyList<String>()
        withTransport({ _, _, form ->
            writtenTags = Json.parseToJsonElement(form!!.getValue("data")).jsonObject["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
            200 to """{"draft_sequence":2}"""
        }) {
            session.update(draft); assertTrue(session.sync()); session.update(draft.copy(tags = listOf("changed")))
            assertFalse(session.isSynced()); assertTrue(session.sync()); assertEquals(listOf("changed"), writtenTags)
        }
    }

    @Test fun deletionCannotPretendSuccessForAnOldSequence() = runTest {
        withTransport({ _, method, _ -> 200 to if (method == "DELETE") """{"success":true}""" else """{"draft":"still present","draft_sequence":12}""" }) {
            assertFalse(LinuxDoRepository.deleteCloudDraft("new_topic", 0))
        }
    }

    @Test fun discardingUnsyncedDraftSucceedsEvenWhenOffline() = runTest {
        val draft = ComposerDraft(owner(), "topic_42", ComposerAction.REPLY, raw = "offline local draft", topicId = 42)
        val session = ComposerDraftSession(draft)
        session.update(draft)
        assertNotNull(LocalComposerDrafts.get(draft.owner, draft.key))
        val previous = UserApiKeyAuthManager.webViewHttpExecutor
        try {
            UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ -> null }
            assertTrue(session.discard())
            assertNull(LocalComposerDrafts.get(draft.owner, draft.key))
        } finally {
            UserApiKeyAuthManager.webViewHttpExecutor = previous
        }
    }

    @Test fun postingKeepsIndentationAndExplainsServerRejection() = runTest {
        val raw = "    indented code\n"
        withTransport({ _, _, form -> assertEquals(raw, form?.get("raw")); 422 to """{"errors":["本版块需要一个标签"]}""" }) {
            val error = assertFailsWith<ApiMutationException> { LinuxDoRepository.createPost(11, raw) }
            assertEquals("本版块需要一个标签", error.message)
        }
    }

    @Test fun formArraysDoNotTurnIntoRailsHashesOrBreakTimingKeys() {
        val pairs = formEntries(mapOf("options[0]" to "a", "options[1]" to "b", "tags[0]" to "tag", "timings[42]" to "1000"))
        assertEquals(listOf("options[]", "options[]", "tags[]", "timings[42]"), pairs.map { it.first })
    }

    @Test fun nativeVotingNeedsAConfirmedServerVote() = runTest {
        withTransport({ url, method, params ->
            assertEquals("PUT", method); assertTrue(url.endsWith("/polls/vote.json")); assertEquals("survey", params?.get("poll_name"))
            200 to """{"poll":{"name":"survey","voters":12,"options":[{"id":"option-id","votes":8}]},"vote":["option-id"]}"""
        }) {
            assertTrue(LinuxDoRepository.voteInPoll(88, "survey", listOf("option-id")))
            val snapshot = LinuxDoRepository.getPollVoteSnapshot(88, "survey")
            assertNotNull(snapshot)
            assertEquals(setOf("option-id"), snapshot.selectedIds)
            assertEquals(8, snapshot.optionVotes["option-id"])
            assertEquals(12, snapshot.voters)
        }
        withTransport({ _, _, _ -> 200 to """{"success":true}""" }) { assertFalse(LinuxDoRepository.voteInPoll(88, "survey", listOf("option-id"))) }
    }

    @Test fun middleWindowPostsAndLaterEditsMergeInReadingOrder() {
        fun post(n: Int, raw: String = "v1") = PostItemModel(n, n, 1, "writer", raw = raw)
        val merged = mergeTopicPosts(listOf(post(90), post(91)), listOf(post(1), post(91, "v2"), post(110)))
        assertEquals(listOf(1, 90, 91, 110), merged.map { it.postNumber }); assertEquals("v2", merged.first { it.id == 91 }.raw)
        val stream = (1..10).toList()
        val nonContiguousLoaded = setOf(1, 2, 9, 10)
        assertEquals(emptyList(), previousUnloadedStreamIds(stream, nonContiguousLoaded))
        assertEquals(listOf(3, 4, 5, 6, 7, 8), remainingUnloadedStreamIds(stream, nonContiguousLoaded))
        assertNull(resolveJumpPostIndex(merged, 50, allowNearestFallback = false))
        assertEquals(1, resolveJumpPostIndex(merged, 50, allowNearestFallback = true))
    }

    @Test fun markdownPreviewPreservesFormattingAndLiteralCode() {
        val rendered = MarkdownPreview.render("**bold** [reference](https://example.com/docs)\n\n```xml\n<item>:smile:</item>\n```\n\n1. first\n2. second\n\n> [!NOTE]\n> 提示内容\n\n[poll]\n# 开发环境调查\n- 选项 A\n[/poll]")
        val nodes = DiscourseAstParser.parseCookedHtml(rendered)
        assertTrue(nodes.filterIsInstance<PostBlockNode.Paragraph>().flatMap { it.rich.runs }.any { it.bold && it.text == "bold" })
        assertTrue(nodes.filterIsInstance<PostBlockNode.Paragraph>().flatMap { it.rich.runs }.any { it.href == "https://example.com/docs" })
        assertEquals("<item>:smile:</item>", nodes.filterIsInstance<PostBlockNode.CodeBlock>().single().code)
        assertTrue(nodes.filterIsInstance<PostBlockNode.BulletList>().single().ordered)
        assertEquals("note", nodes.filterIsInstance<PostBlockNode.CalloutBlock>().single().kind)
        assertEquals("开发环境调查", nodes.filterIsInstance<PostBlockNode.Unsupported>().single { it.kind == "poll" }.label)
    }

    @Test fun hiddenAndVectorContentRemainExplicitNodes() {
        val nodes = DiscourseAstParser.parseCookedHtml("<p>Visible <span class=\"spoiler\">secret</span></p><svg role=\"img\"><path d=\"M0 0L2 2\"/></svg>")
        assertFalse(nodes.filterIsInstance<PostBlockNode.Paragraph>().any { "secret" in it.text })
        assertEquals("secret", nodes.filterIsInstance<PostBlockNode.Spoiler>().single().children.filterIsInstance<PostBlockNode.Paragraph>().single().text)
        assertTrue(nodes.any { it is PostBlockNode.Unsupported && it.kind == "svg" })
    }

    @Test fun pollOptionIdsAreRetainedForRealSubmission() {
        val poll = DiscourseAstParser.parseCookedHtml("""<div class="poll" data-poll-name="survey" data-poll-type="multiple" data-poll-max="2"><ul><li data-poll-option-id="hash-a">A</li><li data-poll-option-id="hash-b">B</li></ul></div>""").single() as PostBlockNode.Poll
        assertEquals("survey", poll.name); assertEquals(listOf("hash-a", "hash-b"), poll.options.map { it.id }); assertTrue(poll.multiple)
    }

    @Test fun authorizationRejectsWrongNonceAndConsumesAValidCallback() {
        val oldKey = UserApiKeyAuthManager.getApiKey()
        try {
            val (url, nonce) = UserApiKeyAuthManager.generateAuthorizationUrl()
            val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val publicDer = params.getValue("public_key").lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicDer)))
            fun callback(value: String): String {
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, publicKey) }
                val data = buildJsonObject { put("key", "fixture-key"); put("nonce", value) }.toString()
                return "discourse://auth_redirect?payload=" + URLEncoder.encode(Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray())), "UTF-8")
            }
            assertFalse(UserApiKeyAuthManager.handleAuthRedirectUri(callback("wrong")))
            val valid = callback(nonce)
            assertTrue(UserApiKeyAuthManager.handleAuthRedirectUri(valid)); assertFalse(UserApiKeyAuthManager.handleAuthRedirectUri(valid))
            assertNull(UserApiKeyAuthManager.exportPendingPrivateKeyBase64())
        } finally { UserApiKeyAuthManager.setApiKey(oldKey) }
    }

    @Test fun boostInputStatsCountShortcodesAndUnicodeEmojisAccurately() {
        val preset = BoostInputStats.compute("✋ 俺也一样")
        assertTrue(preset.isValid)
        assertEquals(6, preset.visibleLength)
        assertEquals(1, preset.emojiCount)

        val customEmojiStats = BoostInputStats.compute(":tieba_087: :bili_057: 太强了")
        assertTrue(customEmojiStats.isValid)
        assertEquals(7, customEmojiStats.visibleLength)
        assertEquals(2, customEmojiStats.emojiCount)

        val overLength = BoostInputStats.compute("一二三四五六七八九十十一十二十三十四十五十六十七")
        assertTrue(overLength.exceedsLength)
        assertFalse(overLength.isValid)

        val overEmojis = BoostInputStats.compute("🚀🔥🎉❤️👍😆")
        assertTrue(overEmojis.exceedsEmojiLimit)
        assertFalse(overEmojis.isValid)
    }

    @Test fun reactionToggleParsesServerSnapshotAndSurfacesErrors() = runTest {
        LinuxDoRepository.clearPollVoteState()
        withTransport({ url, method, _ ->
            assertEquals("PUT", method)
            assertTrue(url.endsWith("/discourse-reactions/posts/501/custom-reactions/heart/toggle.json"))
            200 to """{"id":501,"reactions":[{"id":"heart","type":"emoji","count":5}],"current_user_reaction":{"id":"heart","type":"emoji","can_undo":true}}"""
        }) {
            val res = LinuxDoRepository.togglePostReactionDetailed(501, "heart")
            assertTrue(res.ok)
            assertEquals("heart", res.myReactionId)
            assertEquals(5, res.reactions?.single()?.count)
            val snap = LinuxDoRepository.getPostReactionSnapshot(501)
            assertNotNull(snap)
            assertEquals("heart", snap.myReactionId)
            assertEquals(5, snap.reactions.single().count)
        }

        withTransport({ _, _, _ ->
            422 to """{"errors":["不能给自己的帖子表态"]}"""
        }) {
            val failed = LinuxDoRepository.togglePostReactionDetailed(502, "heart")
            assertFalse(failed.ok)
            assertEquals("不能给自己的帖子表态", failed.errorMessage)
        }
    }

    @Test fun boostCreateAndDeleteSyncWithDiscourseBoostsRoutes() = runTest {
        LinuxDoRepository.clearPollVoteState()
        withTransport({ url, method, form ->
            assertEquals("POST", method)
            assertTrue(url.endsWith("/discourse-boosts/posts/601/boosts.json"))
            assertEquals("✋ 俺也一样 :tieba_087:", form?.get("raw"))
            200 to """{"boost":{"id":9001,"post_id":601,"cooked":"<p>✋ 俺也一样 <img src=\"/uploads/default/original/1X/tieba.png\" title=\":tieba_087:\" alt=\":tieba_087:\" class=\"emoji\"></p>","can_delete":true,"can_flag":false,"user":{"id":7,"username":"lucas","name":"Lucas","avatar_template":"/user_avatar/linux.do/lucas/{size}/1.png"}}}"""
        }) {
            val res = LinuxDoRepository.createPostBoost(601, "✋ 俺也一样 :tieba_087:")
            assertTrue(res.ok)
            assertNotNull(res.boost)
            assertEquals(9001L, res.boost?.id)
            assertEquals("✋ 俺也一样 :tieba_087:", res.boost?.plainText)
            val snap = LinuxDoRepository.getPostBoostSnapshot(601)
            assertNotNull(snap)
            assertFalse(snap.canBoost)
            assertEquals(1, snap.boosts.size)
        }

        withTransport({ url, method, _ ->
            assertEquals("DELETE", method)
            assertTrue(url.endsWith("/discourse-boosts/boosts/9001.json"))
            204 to ""
        }) {
            val del = LinuxDoRepository.deletePostBoost(601, 9001L)
            assertTrue(del.ok)
            val snap = LinuxDoRepository.getPostBoostSnapshot(601)
            assertNotNull(snap)
            assertTrue(snap.canBoost)
            assertTrue(snap.boosts.isEmpty())
        }
    }

    @Test fun cookieSentinelPreservesFreshSessionCookiesOverOlderRecords() {
        CookieSentinel.clearAll()
        CookieSentinel.putCookie(
            StoredCookieRecord(
                key = CookieQuadrupleKey(name = "_t", domain = "linux.do", path = "/", hostOnly = true),
                value = "old_expired_token",
                updatedAtEpochMs = 1000L
            )
        )
        CookieSentinel.ingestRawCookieString("_t=fresh_live_token; _forum_session=fresh_session", domain = "linux.do", hostOnly = false)
        val winners = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
        assertEquals("fresh_live_token", winners["_t"]?.value)
        assertEquals("fresh_session", winners["_forum_session"]?.value)
    }
}
