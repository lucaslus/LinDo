package do_.linux.lindo

import do_.linux.lindo.ui.AppAppearance
import do_.linux.lindo.core.copy.ReaderFontScalePreset
import kotlin.test.*

class SharedAppearanceTest {
    @AfterTest fun cleanup() {
        AppAppearance.onApplyIconAppearance = null
        AppAppearance.onPersist = null
        AppAppearance.appearance = "system"
        AppAppearance.iconAppearance = "system"
        AppAppearance.iconChangeFailed = false
    }
    @Test fun legacyPresetsMigrateOnceAndCurrentPreferencesRemainStable() {
        assertEquals(.95f, AppAppearance.migrateFontScale(null, .85f))
        assertEquals(1f, AppAppearance.migrateFontScale(null, .92f))
        assertEquals(1.12f, AppAppearance.migrateFontScale(null, 1f))
        assertEquals(1f, AppAppearance.migrateFontScale(1f, 1f))
        ReaderFontScalePreset.entries.forEach { assertEquals(it, ReaderFontScalePreset.fromScale(it.scale)) }
    }
    @Test fun systemModeTracksSystemWhileExplicitChoiceStaysFixed() {
        for (system in listOf(false, true)) {
            assertEquals(system, AppAppearance.resolveDark("system", system))
            assertTrue(AppAppearance.resolveDark("dark", system))
            assertFalse(AppAppearance.resolveDark("light", system))
        }
    }
    @Test fun failedNativeIconChangeDoesNotPersistAFalseSuccess() {
        AppAppearance.iconAppearance = "light"
        var persisted = false
        AppAppearance.onPersist = { persisted = true }
        AppAppearance.onApplyIconAppearance = { false }
        AppAppearance.selectIconAppearance("system")
        assertEquals("light", AppAppearance.iconAppearance)
        assertTrue(AppAppearance.iconChangeFailed)
        assertFalse(persisted)
    }
}
