package org.libremediaconverter.convert

import android.net.Uri
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.createDrainedComposeRule
import org.libremediaconverter.model.Validation
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The seam carries a `ConversionState` in and an action back out.
 *
 * The defect this bites on is the extraction having quietly stopped being an extraction: a
 * `ConverterScreenContent` that ignores the `state` it was handed, or renders the finished job's
 * affordances without wiring them to the callbacks the entry point supplies. Neither shows up at
 * compile time -- an unread parameter compiles, and a `Button` whose `onClick` does nothing is a
 * valid `Button` -- and neither is visible from the leaf tests, which compose `FileCard`,
 * `AdvancedPicker` and the pickers directly and never see a state at all.
 *
 * **Both assertions were unreachable before R38.5**, which is the point of the ticket rather than
 * a remark about it. `ConversionState.Converted` is produced only by a `ConversionWorker` run that
 * has already succeeded, so no test can drive a real `ConversionViewModel` into it: it would need
 * a `WorkManager`, a media probe, a staged output file and a completed job. Handing the state in
 * is the only way to ask what the screen does with it.
 *
 * Deliberately not the state matrix. Which affordances each of the six `ConversionState`s renders
 * is R38.6 (#62); this file asserts only that the injection point exists and works in both
 * directions, so the two PRs cannot collide over the same cases.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConverterScreenContentTest {

    // Not `createComposeRule()` directly: see [org.libremediaconverter.drainEscapedCoroutineErrors].
    @get:Rule
    val composeRule = createDrainedComposeRule()

    /** What the screen asked to save, in the order it asked. Empty until Save is tapped. */
    private val savedAs = mutableListOf<String>()

    @Test
    fun `a converted job renders the save button`() {
        setContent(converted())

        composeRule.onNodeWithTag(TestTags.SAVE_FILE).assertExists()
    }

    /**
     * The direction that did not exist before this change.
     *
     * Asserting the *name* rather than just that something was called: the suggested name comes
     * from the job -- `ConversionWorker.KEY_SUGGESTED_NAME` -- and is what the save dialog opens
     * with, so a Save button wired to the wrong branch's state would hand over the wrong one and
     * a bare "was called" check would stay green.
     */
    @Test
    fun `tapping save hands back the name the finished job chose`() {
        setContent(converted())

        composeRule.onNodeWithTag(TestTags.SAVE_FILE).performScrollTo().performClick()

        assertEquals(listOf("holiday.mp4"), savedAs)
    }

    /**
     * `staged` names a file that does not exist, on purpose.
     *
     * The branch renders `formatBytes(s.staged.length())`, and `length()` answers `0L` for a
     * missing path rather than throwing, so the size line reads `0 B` and no temporary folder is
     * needed. `routeReason` stays blank, which is what keeps the routing chip out of the tree --
     * that chip is R38.6's case, not this file's.
     */
    private fun converted() = ConversionState.Converted(
        input = InputFile(
            uri = Uri.parse("content://test/holiday.mkv"),
            displayName = "holiday.mkv",
            sizeBytes = 12_345_678L,
        ),
        staged = File("no-such-staged-output.mp4"),
        suggestedName = "holiday.mp4",
        mimeType = "video/mp4",
    )

    private fun setContent(state: ConversionState) {
        composeRule.setContent {
            ConverterScreenContent(
                state = state,
                settings = ConversionSettings(),
                validation = Validation.Valid,
                actions = ConverterActions(
                    onPickInput = {},
                    onPreset = {},
                    onContainer = {},
                    onVideoCodec = {},
                    onAudioCodec = {},
                    onSuggestion = {},
                    onQuality = {},
                    onEnginePreference = {},
                    onConvert = {},
                    onCancel = {},
                    onSave = { suggestedName -> savedAs += suggestedName },
                    onReset = {},
                ),
            )
        }
    }
}
