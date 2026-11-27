package do_.linux.lindo

import do_.linux.lindo.core.network.*
import kotlinx.coroutines.*
import kotlin.test.*

class AndroidForumConnectionTest {
    private val url = "https://linux.do/t/482293/12.json?track_visit=false"
    private val topic = 200 to """{"id":482293,"post_stream":{"posts":[]}}"""
    private val feed = 200 to """{"topic_list":{"topics":[]}}"""
    private val challenge = 403 to "<html>challenge</html>"
    private val headers = mapOf("Discourse-Track-View-Topic-Id" to "482293")
    private fun original() = assertNotNull(AndroidForumRead.fromRequest(url, "GET", headers))

    @Test fun acceptsOnlyForumReadShapesAndReturnsActualForumErrors() {
        val original = original()
        assertTrue(original.reachedForum(topic))
        assertFalse(original.reachedForum(200 to """{"id":42,"post_stream":{"posts":[]}}"""))
        assertFalse(original.reachedForum(feed))
        assertFalse(original.reachedForum(200 to "<html>maintenance</html>"))
        assertFalse(original.reachedForum(403 to """{"error_type":null}"""))
        assertTrue(original.reachedForum(404 to """{"errors":["话题不存在"]}"""))
        assertTrue(AndroidForumRead.latest().reachedForum(feed))
        listOf("POST", "PUT", "DELETE").forEach { assertNull(AndroidForumRead.fromRequest(url, it, headers)) }
        for (target in listOf("https://other.test/t/482293.json", "https://linux.do/session/current.json",
            "https://linux.do/c/4/show.json", "https://linux.do/tags/filter/search.json", "https://linux.do/session/otp/123", "https://linux.do/t/1/notifications.json")) {
            assertNull(AndroidForumRead.fromRequest(target, "GET", headers))
        }
        for (path in listOf("read", "latest", "c/a/2/l/top", "tag/a/l/hot", "tags/c/a/2/tag")) {
            assertTrue(assertNotNull(AndroidForumRead.fromRequest("https://linux.do/$path.json", "GET", emptyMap())).reachedForum(feed))
        }
    }

    @Test fun foregroundJoiningBackgroundRoundKeepsOneOriginalAndItsResponse() = runBlocking {
        val targets = AndroidVerificationTarget()
        val original = original()
        targets.offer(7, 3, null)
        targets.offer(7, 3, original)
        targets.offer(7, 3, AndroidForumRead.latest())
        assertSame(original, targets.get(7, 3))
        assertNull(targets.get(7, 4))
        val state = AndroidForumConnection()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val leader = async(start = CoroutineStart.UNDISPATCHED) {
            state.check(3, targets.get(7, 3), false, { 3L }, { it == challenge }) { request ->
                reads++
                assertEquals(url, request.url)
                assertEquals(headers, request.headers)
                release.await()
                topic
            }
        }
        val follower = async(start = CoroutineStart.UNDISPATCHED) {
            state.check(3, null, false, { 3L }, { false }) { error("unexpected concurrent read") }
        }
        assertEquals(ForumConnectionStage.Checking, state.stage)
        release.complete(Unit)
        assertTrue(leader.await())
        assertTrue(follower.await())
        assertEquals(1, reads)
        assertSame(topic, original.response)
        assertEquals(ForumConnectionStage.Idle, state.stage)
        targets.release(7)
        assertNull(targets.get(7, 3))
    }

    @Test fun lossOfConnectionDoesNotReopenVerificationAndExplicitRetryUsesSameTopic() = runBlocking {
        val state = AndroidForumConnection()
        val original = original()
        suspend fun retry(response: Pair<Int, String>?, explicit: Boolean) =
            state.check(1, null, explicit, { 1L }, { it == challenge }) { assertSame(original, it); response }
        assertFalse(state.check(1, original, false, { 1L }, { it == challenge }) { challenge })
        assertEquals(ForumConnectionStage.Retry, state.stage)
        assertFalse(state.check(1, null, false, { 1L }, { false }) { error("automatic loop") })
        assertFalse(retry(null, true))
        assertEquals(ForumConnectionStage.Retry, state.stage)
        assertFalse(retry(challenge, true))
        assertEquals(ForumConnectionStage.VerifyAgain, state.stage)
        assertFalse(state.check(1, null, false, { 1L }, { false }) { error("cannot auto-reopen") })
        state.beginRequestedVerification()
        assertTrue(retry(topic, false))
        assertEquals(ForumConnectionStage.Idle, state.stage)
    }

    @Test fun manualCheckUsesLatestAndStaleAccountCannotRestoreAnotherSession() = runBlocking {
        val state = AndroidForumConnection()
        var owner = 1L
        val pending = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            state.check(owner, original(), false, { owner }, { false }) { pending.await(); topic }
        }
        owner = 2L
        assertTrue(state.check(owner, null, false, { owner }, { false }) {
            assertEquals("https://linux.do/latest.json?no_definitions=true", it.url)
            feed
        })
        pending.complete(Unit)
        assertFalse(old.await())
        assertEquals(ForumConnectionStage.Idle, state.stage)
    }

    @Test fun pageUrlMapsJsonEndpointsToRealForumHtmlRoutesAndClearIfNotCheckingIsFlightSafe() = runBlocking {
        assertEquals("https://linux.do/t/482293/12", original().pageUrl())
        assertEquals("https://linux.do/latest", AndroidForumRead.latest().pageUrl())

        val state = AndroidForumConnection()
        assertFalse(state.check(1, original(), false, { 1L }, { true }) { challenge })
        assertEquals(ForumConnectionStage.Retry, state.stage)

        // When not actively checking, a healthy foreground read recovery clears stale Retry stage
        state.clearIfNotChecking()
        assertEquals(ForumConnectionStage.Idle, state.stage)

        // While actively checking, clearIfNotChecking must not abort the in-flight check
        val gate = CompletableDeferred<Unit>()
        val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
            state.check(1, original(), false, { 1L }, { false }) {
                gate.await()
                topic
            }
        }
        assertEquals(ForumConnectionStage.Checking, state.stage)
        state.clearIfNotChecking()
        assertEquals(ForumConnectionStage.Checking, state.stage)
        gate.complete(Unit)
        assertTrue(inFlight.await())
        assertEquals(ForumConnectionStage.Idle, state.stage)
    }
}
