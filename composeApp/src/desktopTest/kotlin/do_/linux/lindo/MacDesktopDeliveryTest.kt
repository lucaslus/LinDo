package do_.linux.lindo

import do_.linux.lindo.core.network.*
import do_.linux.lindo.ui.MacDesktopExperience
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MacDesktopDeliveryTest {
    @BeforeTest fun setup() {
        cleanup()
    }
    @AfterTest fun cleanup() {
        DesktopNetworkPolicy.enabled = false
        DesktopNetworkPolicy.verifying = false
        NetworkRecoveryPolicy.androidEnabled = false
        NetworkRecoveryPolicy.reads.clear()
        MacDesktopExperience.enabled = false
        MacDesktopExperience.onPersist = null
        UserApiKeyAuthManager.webViewHttpExecutor = null
        UserApiKeyAuthManager.requireBrowserTransportOnly = false
        LinuxDoRepository.clearChallengeCooldown()
        LinuxDoRepository.clearTransientRateLimit()
    }
    @Test fun simultaneousReadsShareResponseAndRateLimitSurvivesManualRefresh() = runBlocking {
        DesktopNetworkPolicy.enabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        val calls = AtomicInteger()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ -> calls.incrementAndGet(); delay(100); 429 to "{}" }
        coroutineScope { List(8) { async { LinuxDoRepository.fetchCurrentUserProfile() } }.awaitAll() }
        assertEquals(2, calls.get(), "The shared foreground read gets one delayed retry")
        LinuxDoRepository.prepareForManualRecovery()
        LinuxDoRepository.fetchCurrentUserProfile()
        assertEquals(2, calls.get(), "Refresh must not ignore the server's rate limit")
    }
    @Test fun challengeStopsRepeatedRequestsWhileBrowserOwnsVerification() = runBlocking {
        DesktopNetworkPolicy.enabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        val calls = AtomicInteger()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ -> calls.incrementAndGet(); 403 to "<html><title>Just a moment...</title></html>" }
        LinuxDoRepository.fetchCurrentUserProfile()
        LinuxDoRepository.prepareForManualRecovery()
        LinuxDoRepository.fetchCurrentUserProfile()
        assertEquals(1, calls.get())
        DesktopNetworkPolicy.verifying = true
        LinuxDoRepository.fetchCurrentUserProfile()
        assertEquals(1, calls.get(), "Background session checks must not cancel the browser's login recovery")
    }
    @Test fun serverRetryAfterSupportsSecondsAndDates() {
        assertEquals(120_000, NetworkRecoveryPolicy.retryAfterMillis("120"))
        assertEquals(60_000, NetworkRecoveryPolicy.retryAfterMillis("Fri, 13 Nov 2026 10:01:00 GMT", java.time.Instant.parse("2026-11-13T10:00:00Z").toEpochMilli()))
        assertEquals(2_000, NetworkRecoveryPolicy.retryAfterMillis("invalid"))
        assertEquals(1_000, NetworkRecoveryPolicy.retryAfterMillis("1"))
        assertEquals(604_800_000, NetworkRecoveryPolicy.retryAfterMillis("604800"))
    }
    @Test fun anInFlightSuccessDoesNotClearAnotherRequestsCooldown() = runBlocking {
        DesktopNetworkPolicy.enabled = true
        UserApiKeyAuthManager.requireBrowserTransportOnly = true
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        UserApiKeyAuthManager.webViewHttpExecutor = { _, _, _, _ ->
            calls.incrementAndGet(); started.complete(Unit); release.await()
            200 to """{"current_user":{"id":1,"username":"rate_test","name":"Rate test","avatar_template":"","trust_level":1}}"""
        }
        val request = async { LinuxDoRepository.fetchCurrentUserProfile() }
        started.await()
        LinuxDoRepository.noteRetryAfter("60")
        release.complete(Unit)
        request.await()
        assertTrue(LinuxDoRepository.isRateLimitedNow())
        assertEquals(1, calls.get())
    }
    @Test fun preferenceWriterPreservesUpdatesAndFlushesLatestSnapshot() {
        val dir = Files.createTempDirectory("lindo-preferences-test").toFile()
        try {
            val file = java.io.File(dir, "session.properties")
            val store = DesktopPreferenceStore(file)
            repeat(100) { index -> store.update { setProperty("key$index", "$index") } }
            store.update(immediate = true) { setProperty("pending_auth", "test-only") }
            store.update { remove("pending_auth"); setProperty("theme", "system") }
            store.flush()
            val loaded = Properties().apply { file.inputStream().use { load(it) } }
            assertEquals("system", loaded.getProperty("theme")); assertNull(loaded.getProperty("pending_auth"))
            repeat(100) { assertEquals("$it", loaded.getProperty("key$it")) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun macTrayUsesOnlyBlackAlphaMaskAtAllDensities() {
        for (size in listOf(22,44,66)) for (unread in listOf(0,3)) {
            val image = DesktopTrayController.renderMacTrayCanvas(size,unread)
            var visible=0
            for (y in 0 until size) for (x in 0 until size) {
                val pixel=image.getRGB(x,y)
                if ((pixel ushr 24)>16) { visible++; assertEquals(0,pixel and 0xFFFFFF) }
            }
            assertTrue(visible>size, "Template must retain a visible mascot")
            assertEquals(0,image.getRGB(0,size-1) ushr 24)
        }
    }
    @Test fun appearanceAndIconFollowSystemIndependently() {
        MacDesktopExperience.appearance="system"; MacDesktopExperience.iconAppearance="light"; MacDesktopExperience.systemDark=true
        assertTrue(MacDesktopExperience.dark); assertFalse(MacDesktopExperience.darkIcon)
        MacDesktopExperience.systemDark=false
        assertFalse(MacDesktopExperience.dark)
        MacDesktopExperience.selectAppearance("dark")
        assertTrue(MacDesktopExperience.dark)
    }
}
