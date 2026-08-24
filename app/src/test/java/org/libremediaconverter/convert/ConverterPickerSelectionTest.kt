package org.libremediaconverter.convert

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.createDrainedComposeRule
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner

/**
 * Each picker lights the chip it was handed and reports the constant that was pressed.
 *
 * The defect this bites on is a picker that renders perfectly and answers wrongly. All three are
 * the same dozen lines with a different enum substituted, so the failure mode is a copy-paste that
 * survives review: an `onClick` that closes over the picker's `selected` parameter instead of the
 * chip's own entry hands back one constant no matter which chip was tapped, and an inverted
 * `entry == selected` lights every chip except the right one. Neither throws, neither changes the
 * set of labels on screen, and a test that only asserted "the callback ran" would pass over both.
 *
 * Clicking every chip in turn and comparing the whole recorded list against `entries` is what makes
 * the constant load-bearing rather than the click count -- a hardcoded `onSelect` fires the same
 * number of times as a correct one. Selection is asserted over every chip for the same reason: the
 * one that should be lit proves nothing on its own, because `!=` lights it too whenever the enum
 * has exactly one entry, and lights all its siblings whenever it has more.
 *
 * Labels come from `OutputFormat.label` and `QualityTier.label`; [label], which the screen owns
 * because `EnginePreference` carries no label of its own, supplies the third set. Retyping any of
 * them here would turn a rename into a red test that named the wrong cause.
 *
 * Not covered, deliberately: the `"Output format"`, `"Quality"` and `"Engine"` headings, which are
 * untagged `Text` calls with no enum behind them and no behaviour to bite on.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConverterPickerSelectionTest {

    @get:Rule
    val composeRule = createDrainedComposeRule()

    /**
     * The chip carrying [label] inside the row tagged [rowTag].
     *
     * By ancestor rather than by direct child: how many semantics nodes Material 3 puts between a
     * `FlowRow` and its chips is that library's business, and a matcher that assumed "one" would
     * break on an upgrade that changed nothing this test is about.
     */
    private fun chipIn(rowTag: String, label: String): SemanticsNodeInteraction =
        composeRule.onNode(hasAnyAncestor(hasTestTag(rowTag)) and hasText(label))

    private fun assertOnlySelected(rowTag: String, labels: List<String>, selected: String?) {
        labels.forEach { label ->
            val chip = chipIn(rowTag, label)
            if (label == selected) chip.assertIsSelected() else chip.assertIsNotSelected()
        }
    }

    @Test
    fun `the format picker lights the selected format and no other`() {
        composeRule.setContent { FormatPicker(OutputFormat.WEBM_VP9) {} }

        assertOnlySelected(
            rowTag = TestTags.Converter.FORMAT_CHIPS,
            labels = OutputFormat.entries.map { it.label },
            selected = OutputFormat.WEBM_VP9.label,
        )
    }

    /** A spec no preset can express lights nothing, which is what the custom line stands in for. */
    @Test
    fun `the format picker lights nothing when the spec is custom`() {
        composeRule.setContent { FormatPicker(null) {} }

        assertOnlySelected(
            rowTag = TestTags.Converter.FORMAT_CHIPS,
            labels = OutputFormat.entries.map { it.label },
            selected = null,
        )
        composeRule.onNodeWithText(CUSTOM_SPEC_NOTE).assertExists()
    }

    @Test
    fun `a selected format hides the custom line`() {
        composeRule.setContent { FormatPicker(OutputFormat.MP3) {} }

        composeRule.onNodeWithText(CUSTOM_SPEC_NOTE).assertDoesNotExist()
    }

    @Test
    fun `clicking a format chip reports that format`() {
        val picked = mutableListOf<OutputFormat>()
        composeRule.setContent { FormatPicker(null) { picked += it } }

        OutputFormat.entries.forEach { chipIn(TestTags.Converter.FORMAT_CHIPS, it.label).performClick() }

        assertEquals(OutputFormat.entries.toList(), picked)
    }

    @Test
    fun `the quality picker lights the selected tier and no other`() {
        composeRule.setContent { QualityPicker(QualityTier.BEST) {} }

        assertOnlySelected(
            rowTag = TestTags.Converter.QUALITY_CHIPS,
            labels = QualityTier.entries.map { it.label },
            selected = QualityTier.BEST.label,
        )
    }

    /** The line under the chips describes what was chosen, not whichever tier was written first. */
    @Test
    fun `the quality picker explains the tier that is selected`() {
        composeRule.setContent { QualityPicker(QualityTier.BEST) {} }

        composeRule.onNodeWithText(QualityTier.BEST.description).assertExists()
        composeRule.onNodeWithText(QualityTier.FAST.description).assertDoesNotExist()
    }

    @Test
    fun `clicking a quality chip reports that tier`() {
        val picked = mutableListOf<QualityTier>()
        composeRule.setContent { QualityPicker(QualityTier.FAST) { picked += it } }

        QualityTier.entries.forEach { chipIn(TestTags.Converter.QUALITY_CHIPS, it.label).performClick() }

        assertEquals(QualityTier.entries.toList(), picked)
    }

    @Test
    fun `the engine picker lights the selected preference and no other`() {
        composeRule.setContent { EnginePicker(EnginePreference.FORCE_SOFTWARE) {} }

        assertOnlySelected(
            rowTag = TestTags.Converter.ENGINE_CHIPS,
            labels = EnginePreference.entries.map { it.label() },
            selected = EnginePreference.FORCE_SOFTWARE.label(),
        )
    }

    @Test
    fun `clicking an engine chip reports that preference`() {
        val picked = mutableListOf<EnginePreference>()
        composeRule.setContent { EnginePicker(EnginePreference.AUTO) { picked += it } }

        EnginePreference.entries.forEach { chipIn(TestTags.Converter.ENGINE_CHIPS, it.label()).performClick() }

        assertEquals(EnginePreference.entries.toList(), picked)
    }

    private companion object {
        /**
         * Copied byte for byte out of `ConverterScreen.kt` -- it holds a U+2014 em dash, which
         * retyped as ASCII would match nothing and fail as "no node found" rather than as a reword.
         */
        const val CUSTOM_SPEC_NOTE: String = "Custom — set below."
    }
}
