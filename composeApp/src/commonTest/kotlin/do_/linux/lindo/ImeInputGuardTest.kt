package do_.linux.lindo

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import do_.linux.lindo.ui.ImeInputGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImeInputGuardTest {

    @Test
    fun blocksSubmissionWhileImeCompositionIsActive() {
        val composing = TextFieldValue(
            text = "自信点，gemini",
            selection = TextRange(10),
            composition = TextRange(4, 10)
        )
        assertFalse(ImeInputGuard.canSubmitAction(composing))

        val committed = composing.copy(composition = null)
        assertTrue(ImeInputGuard.canSubmitAction(committed))
    }

    @Test
    fun singleLineNormalizationPreservesActiveCompositionWhenNoNewlinePresent() {
        val composing = TextFieldValue(
            text = "自信点，gemini",
            selection = TextRange(10),
            composition = TextRange(4, 10)
        )
        val kept = ImeInputGuard.normalizeSingleLine(composing)
        assertEquals(composing.text, kept.text)
        assertEquals(TextRange(4, 10), kept.composition)

        val pastedWithNewline = TextFieldValue(
            text = "第一行\n第二行",
            selection = TextRange(7),
            composition = null
        )
        val normalized = ImeInputGuard.normalizeSingleLine(pastedWithNewline)
        assertEquals("第一行 第二行", normalized.text)
        assertNull(normalized.composition)
    }

    @Test
    fun freezesCommittedSearchQueryDuringPinyinCompositionUntilSelectionCommitted() {
        // Previous committed query was "antigravity"
        val composingPinyin = TextFieldValue(
            text = "antigravityhuan'cun",
            selection = TextRange(19),
            composition = TextRange(11, 19)
        )
        // Must stay frozen on "antigravity" while Pinyin is still underlined!
        assertEquals(
            "antigravity",
            ImeInputGuard.resolveCommittedQuery("antigravity", composingPinyin)
        )

        // Once user presses Space / selects "缓存", composition becomes null -> commits immediately!
        val selectedChinese = TextFieldValue(
            text = "antigravity缓存",
            selection = TextRange(13),
            composition = null
        )
        assertEquals(
            "antigravity缓存",
            ImeInputGuard.resolveCommittedQuery("antigravity", selectedChinese)
        )

        // Clearing to empty always commits immediately even if composition flag lingers
        val cleared = TextFieldValue(text = "", selection = TextRange(0), composition = null)
        assertEquals("", ImeInputGuard.resolveCommittedQuery("antigravity", cleared))
    }
}
