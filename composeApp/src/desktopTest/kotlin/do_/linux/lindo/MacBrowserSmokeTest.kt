package do_.linux.lindo

import do_.linux.lindo.core.network.DesktopNetworkPolicy
import do_.linux.lindo.ui.AppAppearance
import do_.linux.lindo.ui.PlatformSessionRecovery
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Explicit opt-in only: opens Lindo's dedicated browser profile, never the user's normal profile. */
class MacBrowserSmokeTest {
    @Test fun guestVerificationReturnsToApp() = runBlocking {
        assumeTrue(System.getenv("LINDO_VERIFY_BROWSER_SMOKE") == "1")
        DesktopNetworkPolicy.enabled = true

        MacBrowserTransport.install(false, {}, {})
        val success = withTimeoutOrNull(45_000) { PlatformSessionRecovery.verifySession!!.invoke() } == true
        assertTrue(success, "${PlatformSessionRecovery.verificationMessage}; ${MacBrowserTransport.verificationDiagnostic}")
    }
}
