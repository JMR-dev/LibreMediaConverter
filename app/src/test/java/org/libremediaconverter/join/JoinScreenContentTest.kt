package org.libremediaconverter.join

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The join screen's half of the same seam, and the same two directions.
 *
 * The defect is the one `ConverterScreenContentTest` describes -- a content composable that
 * ignores the state handed to it, or renders the finished job's affordances unwired -- and it has
 * to be asked separately here because the two screens share no code. `JoinScreen` and
 * `ConverterScreen` were extracted in the same commit by the same hand, which is exactly the
 * circumstance in which one of them gets the wiring right and the other does not.
 *
 * `JoinState.Joined` is unreachable through a real `JoinViewModel` for the same reason
 * `ConversionState.Converted` is: only a `ConcatWorker` run that has already succeeded produces
 * one, carrying the strategy it chose and the name it picked.
 *
 * `JoinScreenKt` is the honest remaining coverage gap on this repo, and closing it is R38.7 (#63),
 * not this file. Which affordances each `JoinState` renders belongs there; this asserts only that
 * the injection point exists.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JoinScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** What the screen asked to save, in the order it asked. Empty until Save is tapped. */
    private val savedAs = mutableListOf<String>()

    @Test
    fun `a finished join renders the save button`() {
        setContent(joined())

        composeRule.onNodeWithTag(TestTags.SAVE_FILE).assertExists()
    }

    @Test
    fun `tapping save hands back the name the finished join chose`() {
        setContent(joined())

        composeRule.onNodeWithTag(TestTags.SAVE_FILE).performScrollTo().performClick()

        assertEquals(listOf("joined.mp4"), savedAs)
    }

    /** `staged` names a missing file deliberately -- see the same helper on the converter side. */
    private fun joined() = JoinState.Joined(
        staged = File("no-such-staged-output.mp4"),
        strategy = ConcatStrategy.STREAM_COPY,
        suggestedName = "joined.mp4",
        mimeType = "video/mp4",
    )

    private fun setContent(state: JoinState) {
        composeRule.setContent {
            JoinScreenContent(
                state = state,
                actions = JoinActions(
                    onPickInputs = {},
                    onJoin = {},
                    onCancel = {},
                    onSave = { suggestedName -> savedAs += suggestedName },
                    onReset = {},
                ),
            )
        }
    }
}
