package do_.linux.lindo

import do_.linux.lindo.core.network.*
import kotlin.test.*

class WebPagePurposeTest {
    @Test fun loginIntentSurvivesHomeRedirectAndBrowsedSitesRemainOpen() {
        assertEquals(WebPagePurpose.LOGIN, webPagePurpose("https://linux.do/login"))
        assertEquals("登录", webPageToolbarLabel("https://linux.do/login", "https://linux.do/latest"))
        assertEquals(WebPagePurpose.BROWSE, webPagePurpose("https://connect.linux.do/login"))
        assertEquals(WebPagePurpose.BROWSE, webPagePurpose("https://linux.do/t/login/42"))
        assertEquals(WebPagePurpose.BROWSE, webPagePurpose("https://linux.do.example/login"))
        assertEquals(WebPagePurpose.BROWSE, webPagePurpose("https://linux.do/"))
    }
    @Test fun toolbarShowsOriginInsteadOfRepeatingConnectPageHeading() {
        assertEquals("connect.linux.do", webPageToolbarLabel("https://connect.linux.do/", "https://connect.linux.do/"))
        assertEquals("accounts.google.com", webPageToolbarLabel("https://linux.do/login", "https://accounts.google.com/"))
        assertEquals(WebPagePurpose.VERIFICATION, webPagePurpose("https://linux.do/#lindo-verify"))
        assertEquals("linux.do", webPageToolbarLabel("https://linux.do/#lindo-verify", "https://linux.do/latest.json"))
    }
}
