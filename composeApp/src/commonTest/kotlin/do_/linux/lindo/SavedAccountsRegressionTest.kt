package do_.linux.lindo

import do_.linux.lindo.core.network.*
import do_.linux.lindo.core.compose.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SavedAccountsRegressionTest {
    private var stored = ""
    private var browserCookies = ""
    private fun user(name: String) = AuthenticatedUserProfile(userId = if (name == "alice") 1 else 2, username = name, displayName = name)
    private fun authenticate(name: String) {
        browserCookies = "_t=test-$name; _forum_session=forum-$name"
        UserApiKeyAuthManager.onSessionAuthenticated(user(name), browserCookies)
    }
    @BeforeTest fun setup() {
        SavedAccounts.resetForTest()
        UserApiKeyAuthManager.onPersistSession = null
        UserApiKeyAuthManager.onAuthStateChanged = null
        UserApiKeyAuthManager.onLogoutPlatformClear = null
        UserApiKeyAuthManager.onPersistReadTopics = null
        LocalComposerDrafts.onPersist = null
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
        SavedAccounts.configure(null) { stored = it }
        SavedAccounts.replaceBrowserCookies = { browserCookies = it }
    }
    @AfterTest fun cleanup() {
        SavedAccounts.resetForTest()
        UserApiKeyAuthManager.logout()
        CookieSentinel.clearAll()
    }
    @Test fun addSwitchAndReturnKeepCredentialsDraftsAndReadingSeparate() = runTest {
        authenticate("alice")
        LinuxDoRepository.restoreReadTopics("100:7")
        LocalComposerDrafts.save(ComposerDraft("alice", "new_topic", ComposerAction.NEW_TOPIC, raw = "Alice private draft"))
        CookieSentinel.ingestRawCookieString("cf_clearance=browser-trust", domain = "linux.do")
        SavedAccounts.beginAdd()
        assertFalse(UserApiKeyAuthManager.isAuthorized())
        assertTrue(SavedAccounts.adding)
        assertEquals("", browserCookies)
        assertTrue(LocalComposerDrafts.all("alice").isEmpty())
        authenticate("bob")
        assertFalse(SavedAccounts.adding)
        LinuxDoRepository.restoreReadTopics("200:3")
        LocalComposerDrafts.save(ComposerDraft("bob", "new_topic", ComposerAction.NEW_TOPIC, raw = "Bob private draft"))
        UserApiKeyAuthManager.updateCachedUnreadNotifications(8)
        assertTrue(SavedAccounts.switchTo("alice"))
        assertEquals("alice", UserApiKeyAuthManager.currentUser?.username)
        assertEquals("test-alice", CookieSentinel.resolveWinningCookies()["_t"]?.value)
        assertTrue(browserCookies.contains("test-alice"))
        assertEquals("Alice private draft", LocalComposerDrafts.all("alice").single().raw)
        assertTrue(LocalComposerDrafts.all("bob").isEmpty())
        assertEquals(7, LinuxDoRepository.getServerConfirmedReadFloor(100))
        assertEquals(0, UserApiKeyAuthManager.cachedUnreadNotificationCount)
        assertTrue(SavedAccounts.switchTo("bob"))
        assertEquals("Bob private draft", LocalComposerDrafts.all("bob").single().raw)
        assertEquals(3, LinuxDoRepository.getServerConfirmedReadFloor(200))
        assertEquals(2, SavedAccounts.accounts.size)
    }
    @Test fun cancelledAdditionRestoresOriginalAccount() = runTest {
        authenticate("alice")
        SavedAccounts.beginAdd()
        SavedAccounts.cancelAdd()
        assertEquals("alice", UserApiKeyAuthManager.currentUser?.username)
        assertTrue(browserCookies.contains("test-alice"))
        assertFalse(SavedAccounts.adding)
        assertEquals(1, SavedAccounts.accounts.size)
    }
    @Test fun authenticationExpiryRetainsAReLoginEntryButLogoutOnlyRemovesCurrent() = runTest {
        authenticate("alice"); SavedAccounts.beginAdd(); authenticate("bob")
        UserApiKeyAuthManager.handleServerAuthInvalidated()
        assertTrue(SavedAccounts.accounts.single { it.profile.username == "bob" }.needsLogin)
        assertFalse(SavedAccounts.switchTo("bob"))
        assertTrue(SavedAccounts.switchTo("alice"))
        UserApiKeyAuthManager.logout()
        assertEquals(listOf("bob"), SavedAccounts.accounts.map { it.profile.username })
    }
    @Test fun duplicateLoginUpdatesAnEntryAndPersistenceRestoresAllAccounts() = runTest {
        authenticate("alice"); authenticate("alice")
        assertEquals(1, SavedAccounts.accounts.size)
        SavedAccounts.beginAdd(); authenticate("bob")
        val serialized = stored
        SavedAccounts.resetForTest(); UserApiKeyAuthManager.logout(); CookieSentinel.clearAll()
        SavedAccounts.configure(serialized) { stored = it }
        SavedAccounts.replaceBrowserCookies = { browserCookies = it }
        assertEquals(2, SavedAccounts.accounts.size)
        assertTrue(SavedAccounts.switchTo("alice"))
        assertEquals("alice", UserApiKeyAuthManager.currentUser?.username)
    }
    @Test fun browserReplacementFailureRollsBackBeforePublishingNewAccount() = runTest {
        authenticate("alice"); SavedAccounts.beginAdd(); authenticate("bob"); SavedAccounts.switchTo("alice")
        SavedAccounts.replaceBrowserCookies = {
            if (it.contains("test-bob")) error("simulated adapter failure")
            browserCookies = it
        }
        assertFailsWith<IllegalStateException> { SavedAccounts.switchTo("bob") }
        assertEquals("alice", UserApiKeyAuthManager.currentUser?.username)
        assertTrue(browserCookies.contains("test-alice"))
        assertFalse(SavedAccounts.busy)
        assertFalse(SavedAccounts.blocked)
    }
    @Test fun failedRollbackBlocksRequestsUntilAnExplicitSuccessfulSwitch() = runTest {
        authenticate("alice"); SavedAccounts.beginAdd(); authenticate("bob"); SavedAccounts.switchTo("alice")
        SavedAccounts.replaceBrowserCookies = { error("unavailable browser") }
        assertFailsWith<IllegalStateException> { SavedAccounts.switchTo("bob") }
        assertTrue(SavedAccounts.blocked)
        assertNull(UserApiKeyAuthManager.currentUser)
        assertFailsWith<CancellationException> { AccountSessionGate.read { error("Must not send") } }
        SavedAccounts.replaceBrowserCookies = { browserCookies = it }
        assertTrue(SavedAccounts.switchTo("alice"))
        assertFalse(SavedAccounts.blocked)
    }
    @Test fun interruptedReplacementResumesFromDurableTargetOnRestart() = runTest {
        authenticate("alice"); SavedAccounts.beginAdd(); authenticate("bob"); SavedAccounts.switchTo("alice")
        SavedAccounts.replaceBrowserCookies = { throw AssertionError("simulated process interruption") }
        assertFailsWith<AssertionError> { SavedAccounts.switchTo("bob") }
        val journal = stored
        SavedAccounts.resetForTest()
        SavedAccounts.configure(journal) { stored = it }
        assertTrue(SavedAccounts.busy)
        SavedAccounts.replaceBrowserCookies = { browserCookies = it }
        SavedAccounts.recoverPendingSwitch()
        assertEquals("bob", UserApiKeyAuthManager.currentUser?.username)
        assertTrue(browserCookies.contains("test-bob"))
        assertFalse(SavedAccounts.busy)
    }
    @Test fun switchingWaitsForReadsAndPreventsOldQueuedRequestsFromUsingNewCookies() = runTest {
        authenticate("alice"); SavedAccounts.beginAdd(); authenticate("bob"); SavedAccounts.switchTo("alice")
        val finishRead = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val request = launch { AccountSessionGate.read { started.complete(Unit); finishRead.await() } }
        started.await()
        val switching = async { SavedAccounts.switchTo("bob") }
        runCurrent()
        assertEquals("alice", UserApiKeyAuthManager.currentUser?.username)
        var sent = false
        val queued = launch { AccountSessionGate.read { sent = true } }
        runCurrent(); assertFalse(sent)
        finishRead.complete(Unit); request.join()
        assertTrue(switching.await()); queued.join()
        assertFalse(sent)
        assertTrue(queued.isCancelled)
        assertEquals("bob", UserApiKeyAuthManager.currentUser?.username)
    }
}
