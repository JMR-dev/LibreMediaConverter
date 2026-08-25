package org.libremediaconverter.convert

import android.net.Uri
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.createDrainedComposeRule
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.Validation
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every `ConversionState` renders its own affordances, and only its own.
 *
 * The defect this bites on is a `when` arm that has drifted from the state it names: a button
 * offered in a state where it cannot work, a state's own data never reaching the node that is
 * supposed to display it, or an affordance wired to the wrong callback. None of that is a compile
 * error -- every arm of the `when` returns `Unit`, so an arm can render anything at all -- and none
 * of it is visible from the leaf tests, which compose `FileCard`, `AdvancedPicker` and the three
 * pickers directly and never see a `ConversionState`.
 *
 * The arm most worth guarding is `Ready`'s `enabled = validation.isValid`. The Advanced picker
 * deliberately lets an impossible container / codec combination be selected -- `AdvancedPicker`'s
 * KDoc says teaching the constraint beats hiding it -- so that single expression is the only thing
 * standing between an invalid spec and a job that cannot succeed. `enabled = true` compiles, renders
 * an identical screen apart from one colour, and passes every other test in this suite.
 *
 * Callbacks are asserted by **identity, over the whole log**: [fired] records all twelve of them and
 * each assertion compares the complete list against one expected entry. A bare "the callback ran"
 * check stays green when an arm fires the right callback for the wrong reason, and a check on one
 * callback alone stays green when an arm fires two.
 *
 * ### Not asserted here, so that each is a decision rather than an omission
 *
 * - **`Failed`'s error colour.** #62's table asks for the message "in the error colour". Compose
 *   publishes no text colour to the semantics tree -- there is no `SemanticsProperties` entry for
 *   it -- so it is unobservable from a JVM test, the same limit `FileCardTest` records for
 *   `HorizontalDivider`. The message text itself is asserted; the colour would need a screenshot.
 * - **The three `assertDoesNotExist` checks on [TestTags.Converter.FILE_CARD] are compile-guarded,
 *   not guarded by this file.** `Idle` is a `data object`, and `Saved` and `Failed` carry only a
 *   `displayName` and a `message`; none of the three has an `input`, so `FileCard(s.input)` does not
 *   compile in those arms. The lines stay because they state the intent cheaply, but they are not
 *   what stops a `FileCard` appearing there and this file does not claim they are.
 * - **Which constant each chip hands back** belongs to `ConverterPickerSelectionTest`, and **what
 *   the file card says about an unknown size** to `FileCardTest`. This file asserts that `Ready`
 *   puts those leaves on screen at all, not what they then do.
 * - **The suggested name `Converted` hands to the save dialog** is pinned by
 *   `ConverterScreenContentTest`; repeating it here would be a second copy of one assertion.
 * - **`ConverterScreen`'s permission dance.** `requestNotifications` calls `convert()` on both grant
 *   and deny, deliberately -- the KDoc explains that the foreground service runs either way -- and
 *   it lives in the entry point, above the seam this file composes.
 * - **`is ConversionState.Idle -> Unit` in the nested `when`.** The outer `when` peels `Idle` off
 *   first, so that arm is permanently unreachable and no test can reach it.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConverterStateAffordancesTest {

    // Not `createComposeRule()` directly: see [org.libremediaconverter.drainEscapedCoroutineErrors].
    @get:Rule
    val composeRule = createDrainedComposeRule()

    /**
     * Every callback the screen fired, in order, tagged with the value it carried.
     *
     * All twelve are recorded rather than only the one under test, so an assertion can be
     * `assertEquals(listOf("cancel"), fired)` -- which says "this one and nothing else".
     */
    private val fired = mutableListOf<String>()

    // -------------------------------------------------------------------- Idle

    @Test
    fun `an idle screen offers the prompt and the picker, and nothing to act on yet`() {
        setContent(ConversionState.Idle)

        composeRule.onNodeWithText("Pick a file to convert.").assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.CONVERT).assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.CANCEL).assertDoesNotExist()
        // Compile-guarded rather than guarded here -- `Idle` has no `input`. See the class KDoc.
        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD).assertDoesNotExist()
    }

    /**
     * No [performScrollTo] on this one, unlike every other click below. `Idle` is the centred
     * branch outside the `verticalScroll` column, so it has no scrollable ancestor to scroll in.
     */
    @Test
    fun `tapping choose file on an idle screen asks for a file and does nothing else`() {
        setContent(ConversionState.Idle)

        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).performClick()

        assertEquals(listOf("pickInput"), fired)
    }

    // ------------------------------------------------------------------- Ready

    /**
     * All four pickers, the card above them and both buttons below, in one assertion each.
     *
     * A superset of #62's "all five pickers": which four or five of these count as a picker is not
     * worth arguing about, so the case names everything the arm emits.
     */
    @Test
    fun `a picked file offers its card, all four pickers and both buttons`() {
        setContent(ConversionState.Ready(input()))

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD).assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.FORMAT_CHIPS).assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.QUALITY_CHIPS).assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.ENGINE_CHIPS).assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.CONVERT).assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_DIFFERENT_FILE).assertExists()
    }

    /** The card is handed `s.input`, so the name on it is how the state is shown to have arrived. */
    @Test
    fun `the file card on a picked file names the file that was picked`() {
        setContent(ConversionState.Ready(input()))

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NAME).assertTextEquals("holiday.mkv")
    }

    @Test
    fun `convert is offered for a spec that can be produced`() {
        setContent(ConversionState.Ready(input()), validation = Validation.Valid)

        composeRule.onNodeWithTag(TestTags.Converter.CONVERT).assertIsEnabled()
    }

    @Test
    fun `tapping convert starts the job and does nothing else`() {
        setContent(ConversionState.Ready(input()), validation = Validation.Valid)

        composeRule.onNodeWithTag(TestTags.Converter.CONVERT).performScrollTo().performClick()

        assertEquals(listOf("convert"), fired)
    }

    /**
     * The bite named in #62. Reverting `enabled = validation.isValid` to `enabled = true` reddens
     * exactly this case, and nothing else in the repository.
     */
    @Test
    fun `convert is withheld for a spec that cannot be produced`() {
        setContent(ConversionState.Ready(input()), validation = INVALID)

        composeRule.onNodeWithTag(TestTags.Converter.CONVERT).assertIsNotEnabled()
    }

    /** The other button on the arm goes back to the picker rather than starting anything. */
    @Test
    fun `tapping choose a different file asks for a file rather than converting`() {
        setContent(ConversionState.Ready(input()))

        composeRule
            .onNodeWithTag(TestTags.Converter.CHOOSE_DIFFERENT_FILE)
            .performScrollTo()
            .performClick()

        assertEquals(listOf("pickInput"), fired)
    }

    // -------------------------------------------------------------- Converting

    /**
     * Two independent readings of the same `percent`, on purpose.
     *
     * The heading is a string and the bar is a float, and the arm computes them from the state
     * separately -- `"${s.percent}%"` against `s.percent / 100f`. A hardcoded bar and a hardcoded
     * heading are different mistakes, so neither assertion covers the other.
     */
    @Test
    fun `a running job reports how far it has got, in words and on the bar`() {
        setContent(ConversionState.Converting(input(), percent = 42))

        composeRule.onNodeWithText("Converting… 42%").assertExists()
        composeRule
            .onNodeWithTag(TestTags.Converter.PROGRESS)
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.42f, 0f..1f))
    }

    @Test
    fun `a running job offers cancel and not start over`() {
        setContent(ConversionState.Converting(input(), percent = 42))

        composeRule.onNodeWithTag(TestTags.CANCEL).assertExists()
        composeRule.onNodeWithTag(TestTags.START_OVER).assertDoesNotExist()
    }

    @Test
    fun `tapping cancel on a running job cancels it and does nothing else`() {
        setContent(ConversionState.Converting(input(), percent = 42))

        composeRule.onNodeWithTag(TestTags.CANCEL).performScrollTo().performClick()

        assertEquals(listOf("cancel"), fired)
    }

    // ----------------------------------------------------------------- Waiting

    /**
     * The second bite named in #62. Deleting the `Cancel` button from the `Waiting` arm reddens
     * this case and the one below it.
     *
     * The paragraph is asserted in full rather than by a fragment because it is the only thing the
     * arm renders besides the card and the button, and because its wording is the arm's whole
     * job -- `FailureOutcome` records that two different causes land here and the state cannot tell
     * them apart, so the text has to cover both. A reword should redden one test, and this is it.
     */
    @Test
    fun `a paused job explains why and still offers cancel`() {
        setContent(ConversionState.Waiting(input()))

        composeRule.onNodeWithText(PAUSED_PARAGRAPH).assertExists()
        composeRule.onNodeWithTag(TestTags.CANCEL).assertExists()
    }

    @Test
    fun `tapping cancel on a paused job cancels it and does nothing else`() {
        setContent(ConversionState.Waiting(input()))

        composeRule.onNodeWithTag(TestTags.CANCEL).performScrollTo().performClick()

        assertEquals(listOf("cancel"), fired)
    }

    // --------------------------------------------------------------- Converted

    @Test
    fun `a finished job offers save and start over, and no longer offers cancel`() {
        setContent(converted())

        composeRule.onNodeWithTag(TestTags.SAVE_FILE).assertExists()
        composeRule.onNodeWithTag(TestTags.START_OVER).assertExists()
        composeRule.onNodeWithTag(TestTags.CANCEL).assertDoesNotExist()
    }

    @Test
    fun `tapping start over on a finished job resets and does not save`() {
        setContent(converted())

        composeRule.onNodeWithTag(TestTags.START_OVER).performScrollTo().performClick()

        assertEquals(listOf("reset"), fired)
    }

    /**
     * The chip carries the job's own explanation, so its text is the assertion rather than its
     * presence: a chip showing the engine name, or the previous job's reason, would still exist.
     */
    @Test
    fun `a finished job shows the routing decision the job reported`() {
        setContent(converted(routeReason = "Software — the MKV input needed a re-encode"))

        composeRule
            .onNodeWithTag(TestTags.Converter.ROUTE_REASON)
            .assertTextEquals("Software — the MKV input needed a re-encode")
    }

    /** The other side of the `isNotBlank` guard, which is unguarded without a case of its own. */
    @Test
    fun `a finished job that reported no routing decision shows no chip`() {
        setContent(converted(routeReason = ""))

        composeRule.onNodeWithTag(TestTags.Converter.ROUTE_REASON).assertDoesNotExist()
    }

    // ------------------------------------------------------------------- Saved

    @Test
    fun `a saved file names itself and offers another conversion`() {
        setContent(ConversionState.Saved(displayName = "holiday.mp4"))

        composeRule.onNodeWithText("Saved holiday.mp4.").assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.CONVERT_ANOTHER).assertExists()
        // Compile-guarded rather than guarded here -- `Saved` has no `input`. See the class KDoc.
        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD).assertDoesNotExist()
    }

    @Test
    fun `tapping convert another after a save resets and does nothing else`() {
        setContent(ConversionState.Saved(displayName = "holiday.mp4"))

        composeRule
            .onNodeWithTag(TestTags.Converter.CONVERT_ANOTHER)
            .performScrollTo()
            .performClick()

        assertEquals(listOf("reset"), fired)
    }

    // ------------------------------------------------------------------ Failed

    /**
     * The message is the arm's only output that carries information, and it comes from the state.
     * An arm rendering a fixed apology would look right and say nothing, which is why the assertion
     * is on the text handed in rather than on a node existing.
     */
    @Test
    fun `a failed job renders the reason it was given and offers a restart`() {
        setContent(ConversionState.Failed(message = "Ran out of space while writing the output."))

        composeRule.onNodeWithText("Ran out of space while writing the output.").assertExists()
        composeRule.onNodeWithTag(TestTags.START_OVER).assertExists()
        composeRule.onNodeWithTag(TestTags.SAVE_FILE).assertDoesNotExist()
        // Compile-guarded rather than guarded here -- `Failed` has no `input`. See the class KDoc.
        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD).assertDoesNotExist()
    }

    @Test
    fun `tapping start over after a failure resets and does nothing else`() {
        setContent(ConversionState.Failed(message = "Ran out of space while writing the output."))

        composeRule.onNodeWithTag(TestTags.START_OVER).performScrollTo().performClick()

        assertEquals(listOf("reset"), fired)
    }

    // ------------------------------------------------------------------ Harness

    private fun input() = InputFile(
        uri = Uri.parse("content://test/holiday.mkv"),
        displayName = "holiday.mkv",
        sizeBytes = 12_345_678L,
    )

    /**
     * `staged` names a path that does not exist, deliberately: `File.length()` answers `0L` for a
     * missing file rather than throwing, so the size line reads `0 B` and no temporary folder is
     * needed to render the arm.
     */
    private fun converted(routeReason: String = "") = ConversionState.Converted(
        input = input(),
        staged = File("no-such-staged-output.mp4"),
        routeReason = routeReason,
        suggestedName = "holiday.mp4",
        mimeType = "video/mp4",
    )

    private fun setContent(state: ConversionState, validation: Validation = Validation.Valid) {
        composeRule.setContent {
            ConverterScreenContent(
                state = state,
                settings = ConversionSettings(),
                validation = validation,
                actions = ConverterActions(
                    onPickInput = { fired += "pickInput" },
                    onPreset = { fired += "preset:$it" },
                    onContainer = { fired += "container:$it" },
                    onVideoCodec = { fired += "videoCodec:$it" },
                    onAudioCodec = { fired += "audioCodec:$it" },
                    onSuggestion = { fired += "suggestion:$it" },
                    onQuality = { fired += "quality:$it" },
                    onEnginePreference = { fired += "engine:$it" },
                    onConvert = { fired += "convert" },
                    onCancel = { fired += "cancel" },
                    onSave = { fired += "save:$it" },
                    onReset = { fired += "reset" },
                ),
            )
        }
    }

    private companion object {

        /**
         * A spec no container can hold, with somewhere to go instead.
         *
         * Built here rather than run through `ContainerCapabilities` because what makes a spec
         * invalid is that class's subject; all this arm needs is a `Validation` that answers
         * `isValid == false`.
         */
        val INVALID = Validation.Invalid(
            message = "WebM cannot hold H.264 video.",
            suggestions = listOf(OutputSpec(Container.MKV, VideoCodec.H264, AudioCodec.AAC)),
        )

        /** Copied from the `Waiting` arm, where it is written as two concatenated fragments. */
        const val PAUSED_PARAGRAPH =
            "Paused. Android limits background media processing, so this will " +
                "resume automatically — keeping the app open helps it along."
    }
}
