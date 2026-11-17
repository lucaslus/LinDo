package do_.linux.lindo

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MacWebReplyTest {
    @Test fun missingCallbackIsFailureAndAllowsUiToFinish() = runBlocking {
        var loading = true
        val result = try {
            awaitMacWebReply(CompletableDeferred(), 10)
            true
        } catch (cancelled: CancellationException) {
            throw AssertionError("A command timeout must not cancel the screen", cancelled)
        } catch (_: IllegalStateException) {
            false
        }
        loading = false // The caller's result handler is reached.
        assertFalse(result)
        assertFalse(loading)
    }
    @Test fun realScreenCancellationStillPropagates() = runBlocking {
        val pending = CompletableDeferred<JsonElement>()
        val worker = async { awaitMacWebReply(pending, 10_000) }
        yield()
        worker.cancel()
        assertFailsWith<CancellationException> { worker.await() }
        assertFalse(pending.isCompleted)
    }
    @Test fun aNullJavascriptResultIsNotATimeout() = runBlocking {
        assertEquals(JsonNull, awaitMacWebReply(CompletableDeferred(JsonNull), 100))
    }
}
