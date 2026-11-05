package do_.linux.lindo

import do_.linux.lindo.core.connect.ConnectStatsParser
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.connect.UserPresenceService
import do_.linux.lindo.core.network.CookieSentinel
import do_.linux.lindo.core.network.CookieVariant
import do_.linux.lindo.core.network.executeApiRequest
import do_.linux.lindo.core.network.isSuccessfulApiResponse
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.site.LinuxDoSiteRules
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LinDoRegressionTest {
    @Test
    fun differentContentsWithIdenticalHashesNeverShareParsedPosts() {
        val first = "<p>Aa</p>"
        val second = "<p>BB</p>"
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(DiscourseAstParser.parseOrGetCached(9001, first),
            DiscourseAstParser.parseOrGetCached(9001, second))
    }

    @Test
    fun officialConnectLayoutIncludesAllTenMetricsAndUpperBoundQuotas() {
        val html = javaClass.getResource("/connect-current-layout.html")!!.readText()
        val report = assertNotNull(ConnectStatsParser.parseConnectHtml(html))
        assertTrue(report.isLiveConnectData)
        assertEquals(100, report.timePeriodDays)
        assertEquals(listOf(58, 1821, 25313, 202, 645, 621, 46, 530, 0, 0), report.metrics.map { it.currentValue })
        assertEquals(listOf(50, 500, 20000, 10, 30, 20, 7, 5, 5, 5), report.metrics.map { it.targetValue })
        assertTrue(report.metrics.all { it.isMet })
        val exceeded = assertNotNull(ConnectStatsParser.parseConnectHtml(html
            .replace("已达到", "未达到").replace("0 / 5", "6 / 5")))
        assertFalse(exceeded.isQualified)
        assertFalse(exceeded.metrics.last().isMet)
    }

    @Test
    fun currentOfficialThemeGroupsAndTitlesAreRecognized() {
        assertNotNull(LinuxDoSiteRules.matchAvatarGlowColor("sample", "g-merchant2"))
        assertTrue(LinuxDoSiteRules.isHolographicTitle("破界者"))
        assertTrue(LinuxDoSiteRules.isHolographicTitle("premium"))
    }
    @BeforeTest
    fun resetTracking() {
        ScreenTrackEngine.resetForTest()
        UserPresenceService.setAppForeground(true)
    }

    @Test
    fun mutatingRequestsPreserveTheirActualHttpVerb() = runTest {
        val observed = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            observed += request.method.value
            respond("{}", HttpStatusCode.OK)
        })
        try {
            for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
                executeApiRequest(client, "https://example.test/resource", method,
                    if (method in listOf("POST", "PUT", "PATCH")) mapOf("raw" to "a & b = c") else null,
                    emptyMap())
            }
            assertEquals(listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"), observed)
        } finally { client.close() }
    }

    @Test
    fun htmlChallengesAndRedirectsAreNotSuccessfulApiResponses() {
        assertFalse(isSuccessfulApiResponse(200, "<!doctype html><title>Just a moment</title>"))
        assertFalse(isSuccessfulApiResponse(302, "{}"))
        assertFalse(isSuccessfulApiResponse(403, "{}"))
        assertTrue(isSuccessfulApiResponse(200, "{}"))
        assertTrue(isSuccessfulApiResponse(204, ""))
    }

    @Test
    fun fractionalReadingTicksAccumulateWithoutRoundingEachTick() {
        val start = 1_000_000L
        ScreenTrackEngine.onTopicEntered(1, setOf(1), start)
        for (step in 1..6) ScreenTrackEngine.tick(start + step * 200)
        assertEquals(1L, ScreenTrackEngine.sessionReadingSeconds)
    }

    @Test
    fun identicalFloorNumbersInDifferentTopicsAreDistinctReads() {
        val start = 1_000_000L
        ScreenTrackEngine.onTopicEntered(1, setOf(1), start)
        ScreenTrackEngine.tick(start + 1_200)
        ScreenTrackEngine.onTopicExited(1, start + 1_300)
        ScreenTrackEngine.onTopicEntered(2, setOf(1), start + 2_000)
        ScreenTrackEngine.tick(start + 3_200)
        assertEquals(2, ScreenTrackEngine.sessionPostsRead)
    }

    @Test
    fun pollingTheSameViewportDoesNotFabricateContinuousScrolling() {
        val start = 1_000_000L
        ScreenTrackEngine.onTopicEntered(1, setOf(1), start)
        for (second in 1..200) {
            ScreenTrackEngine.updateVisiblePosts(setOf(1), start + second * 1000)
            ScreenTrackEngine.tick(start + second * 1000)
        }
        assertEquals(180L, ScreenTrackEngine.sessionReadingSeconds)
    }

    @Test
    fun leavingATopicRetainsUnsentReadingTime() {
        val start = 1_000_000L
        ScreenTrackEngine.onTopicEntered(1, setOf(1), start)
        ScreenTrackEngine.tick(start + 500)
        ScreenTrackEngine.onTopicExited(1, start + 600)
        val batch = ScreenTrackEngine.popNextSendableBatch(start + 1000)
        assertNotNull(batch)
        assertEquals(500, batch.timingsMs[1])
    }

    @Test
    fun backgroundTimeIsExcludedWhenReadingResumes() {
        val start = 1_000_000L
        ScreenTrackEngine.onTopicEntered(1, setOf(1), start)
        ScreenTrackEngine.tick(start + 1000)
        UserPresenceService.setAppForeground(false)
        ScreenTrackEngine.tick(start + 100_000)
        UserPresenceService.setAppForeground(true)
        ScreenTrackEngine.onUserScrolled(start + 100_000)
        ScreenTrackEngine.tick(start + 101_000)
        assertEquals(2L, ScreenTrackEngine.sessionReadingSeconds)
    }

    @Test
    fun profileTotalsAreNeverPresentedAsLiveConnectQualification() {
        val report = ConnectStatsParser.buildRealProfileConnectReport("sample", 3, 100, 5000, 10000, 10, 100, 900, 1200, 36000)
        assertFalse(report.isLiveConnectData)
        assertFalse(report.isQualified)
        assertEquals(0, report.timePeriodDays)
        assertEquals(0, report.likesReceivedDays)
        assertEquals(0, report.likesReceivedUsers)
        assertTrue(report.metrics.all { it.targetValue == 0 })
    }

    @Test
    fun missingConnectDataHasNoInventedMinimumCounts() {
        val report = ConnectStatsParser.buildSessionBaselineReport(0, 0)
        assertTrue(report.metrics.isEmpty())
        assertEquals(0, report.daysVisited)
        assertEquals(0, report.postsRead)
        assertFalse(report.isLiveConnectData)
    }

    @Test
    fun negativeQualificationBadgeCannotMatchPositiveChineseSubstring() {
        val report = ConnectStatsParser.parseConnectHtml("""
            <span class="badge">不符合信任等级 3</span>
            <table><tr><td>访问天数</td><td>100</td><td>50</td></tr></table>
        """.trimIndent())
        assertNotNull(report)
        assertFalse(report.isQualified)
    }

    @Test
    fun concurrentCookieReadsAndWritesDoNotCorruptTheSharedStore() {
        CookieSentinel.clear()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val threads = (1..6).map { worker -> Thread {
            try {
                repeat(150) { index ->
                    CookieSentinel.ingestAndDeduplicate(listOf(CookieVariant("test_$worker", "value_$index", "linux.do")))
                    CookieSentinel.buildCookieHeader()
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertNull(failure.get())
        assertEquals(6, CookieSentinel.resolveWinningCookies().size)
        CookieSentinel.clear()
    }
}
