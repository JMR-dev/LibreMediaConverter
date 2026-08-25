package org.libremediaconverter.join

import android.net.Uri
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.InputFile
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every `JoinState` renders its own affordances, wired to its own callback.
 *
 * The defect is a branch of `JoinScreenContent`'s `when` that reads the wrong thing: a count taken
 * from a literal rather than from `inputs`, a strategy line that describes the other strategy, a
 * button wired to the neighbouring branch's callback, a `Failed` that drops the message it carries.
 * None of that is visible at compile time -- every branch of the `when` type-checks against the
 * same `JoinScreenContent` signature -- and none of it is visible from the leaf tests either, which
 * compose `FileRow` on its own and never see a state.
 *
 * `JoinScreenContentTest` deliberately asks only whether the seam exists, using `Joined`. This is
 * the matrix behind it: seven states, each pinned to what it lets the user do next.
 *
 * ### Two assertions here that nothing else in the suite makes
 *
 * **Order.** A join is the one flow where the order of the inputs is the content of the output --
 * the empty state promises "in the order you want them" -- so the rows are read back sorted by
 * their position on screen and compared as a list, not as a set. `JoinLeafTagsTest` proves a row
 * tags itself with the file it shows; nothing proved the rows come out in the order they went in.
 *
 * **Indeterminate.** The join progress bar carries no percentage, on purpose: FFmpeg reports
 * progress against one input's duration, which means nothing across a concatenation. The converter
 * screen's bar is determinate, so "it has a progress bar" is the assertion that would not notice a
 * fabricated percentage arriving here.
 *
 * ### Not asserted here, deliberately
 *
 * `JoinState.Joined.mimeType` is not rendered by this composable at all -- it is read by the entry
 * point, to open the save dialog with a type that matches the finished job. The colour of the
 * `Failed` message is `MaterialTheme.colorScheme.error`, which is theme lookup rather than state
 * logic, so it is left to the eye. The `is JoinState.Idle -> Unit` arm inside the scrolling branch
 * is unreachable by construction: the outer `when` peels `Idle` off first.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JoinStateAffordancesTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Which callback the screen invoked, in order, with what it passed. Empty until one fires. */
    private val events = mutableListOf<String>()

    @Test
    fun `the empty state asks for files in order and offers the picker`() {
        setContent(JoinState.Idle)

        composeRule.onNodeWithText("Pick two or more files to join, in the order you want them.").assertExists()
        // No `performScrollTo` on this one: `Idle` is the centred branch, outside the scrolling
        // column every other state renders into, so there is nothing to scroll.
        composeRule.onNodeWithTag(TestTags.Join.CHOOSE_FILES).performClick()

        assertEquals(listOf("pickInputs"), events)
    }

    /**
     * The rows come out in the order the inputs went in.
     *
     * Sorted by position rather than trusting the order `fetchSemanticsNodes` happens to return, so
     * the assertion is about what the user sees down the screen. Three inputs, with names whose
     * alphabetical order is not their picked order, so a list that had been sorted anywhere on the
     * way through would not be able to pass this.
     */
    @Test
    fun `the picked inputs are listed in the order they were picked`() {
        val picked = listOf("intro.mp4", "middle.mp4", "outro.mp4")
        setContent(JoinState.Ready(inputs = picked.map(::input)))

        val topToBottom = composeRule.onAllNodes(isFileRow)
            .fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
            .map { it.config[SemanticsProperties.TestTag] }

        assertEquals(picked.map(TestTags.Join::fileRow), topToBottom)
    }

    /**
     * Three inputs, not two: two is the minimum a join accepts, so a button that had been
     * hardcoded to the smallest legal join would still read correctly with two on screen.
     */
    @Test
    fun `the join button counts the files it will join`() {
        setContent(JoinState.Ready(inputs = listOf(input("intro.mp4"), input("middle.mp4"), input("outro.mp4"))))

        composeRule.onNodeWithTag(TestTags.Join.JOIN).assertTextEquals("Join 3 files")
        composeRule.onNodeWithTag(TestTags.Join.JOIN).performScrollTo().performClick()

        assertEquals(listOf("join"), events)
    }

    /** `Ready` is the one working state that still offers the picker, to replace the selection. */
    @Test
    fun `a ready join can be repicked`() {
        setContent(JoinState.Ready(inputs = listOf(input("intro.mp4"), input("outro.mp4"))))

        composeRule.onNodeWithTag(TestTags.Join.CHOOSE_DIFFERENT_FILES).performScrollTo().performClick()

        assertEquals(listOf("pickInputs"), events)
    }

    @Test
    fun `a running join names the count and shows a bar with no percentage`() {
        setContent(JoinState.Joining(inputs = listOf(input("intro.mp4"), input("outro.mp4"))))

        composeRule.onNodeWithText("Joining 2 files…").assertExists()
        composeRule.onNodeWithTag(TestTags.Join.PROGRESS).assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        composeRule.onNodeWithTag(TestTags.CANCEL).performScrollTo().performClick()

        assertEquals(listOf("cancel"), events)
    }

    /**
     * The paragraph is byte-identical to the converter screen's, which is the point of asserting
     * the whole of it rather than a fragment: the two branches were worded together, and a reword
     * that lands on one screen only is the failure this notices.
     */
    @Test
    fun `a paused join explains itself and still offers cancel`() {
        setContent(JoinState.Waiting(inputs = listOf(input("intro.mp4"), input("outro.mp4"))))

        composeRule.onNodeWithText(PAUSED_PARAGRAPH).assertExists()
        composeRule.onNodeWithTag(TestTags.CANCEL).performScrollTo().performClick()

        assertEquals(listOf("cancel"), events)
    }

    @Test
    fun `a stream copied join says nothing was re-encoded`() {
        setContent(joined(ConcatStrategy.STREAM_COPY))

        composeRule.onNodeWithText(STREAM_COPY_EXPLANATION).assertExists()
        composeRule.onNodeWithText(REENCODE_EXPLANATION).assertDoesNotExist()
    }

    /**
     * The other half of the pair. Asserting the absence of the stream-copy line as well, because a
     * branch that had collapsed to one answer would still render *an* explanation.
     */
    @Test
    fun `a re-encoded join says the files differed`() {
        setContent(joined(ConcatStrategy.REENCODE))

        composeRule.onNodeWithText(REENCODE_EXPLANATION).assertExists()
        composeRule.onNodeWithText(STREAM_COPY_EXPLANATION).assertDoesNotExist()
    }

    /** The size comes from the staged file, which is missing here, so `length()` answers `0L`. */
    @Test
    fun `a finished join reports the size of what it produced`() {
        setContent(joined(ConcatStrategy.STREAM_COPY))

        composeRule.onNodeWithText("Joined — 0 MB.").assertExists()
    }

    @Test
    fun `a finished join offers save and start over, and they are not the same button`() {
        setContent(joined(ConcatStrategy.STREAM_COPY))

        composeRule.onNodeWithTag(TestTags.SAVE_FILE).performScrollTo().performClick()
        composeRule.onNodeWithTag(TestTags.START_OVER).performScrollTo().performClick()

        assertEquals(listOf("save:joined.mp4", "reset"), events)
    }

    @Test
    fun `a saved join names the file and offers to join more`() {
        setContent(JoinState.Saved(displayName = "holiday-joined.mp4"))

        composeRule.onNodeWithText("Saved holiday-joined.mp4.").assertExists()
        composeRule.onNodeWithTag(TestTags.Join.JOIN_MORE).assertTextEquals("Join more")
        composeRule.onNodeWithTag(TestTags.Join.JOIN_MORE).performScrollTo().performClick()

        assertEquals(listOf("reset"), events)
    }

    /**
     * The message is the whole content of this state -- it is the only thing that says why the job
     * stopped -- and it arrives as a string the failure produced, so a branch that rendered a fixed
     * apology instead would look correct on screen.
     */
    @Test
    fun `a failed join renders the message it carries`() {
        setContent(JoinState.Failed(message = "The second file has no audio track, so joining stopped."))

        composeRule.onNodeWithText("The second file has no audio track, so joining stopped.").assertExists()
    }

    @Test
    fun `a failed join offers start over`() {
        setContent(JoinState.Failed(message = "The second file has no audio track, so joining stopped."))

        composeRule.onNodeWithTag(TestTags.START_OVER).performScrollTo().performClick()

        assertEquals(listOf("reset"), events)
    }

    /** Anything `FileRow` tagged, whichever file it is showing. The prefix comes from the table. */
    private val isFileRow = SemanticsMatcher("is a join file row") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(TestTags.Join.fileRow("")) == true
    }

    private fun input(displayName: String) = InputFile(
        uri = Uri.parse("content://test/$displayName"),
        displayName = displayName,
        sizeBytes = 4_000_000L,
    )

    /** `staged` names a missing file deliberately -- see the same helper in `JoinScreenContentTest`. */
    private fun joined(strategy: ConcatStrategy) = JoinState.Joined(
        staged = File("no-such-staged-output.mp4"),
        strategy = strategy,
        suggestedName = "joined.mp4",
        mimeType = "video/mp4",
    )

    private fun setContent(state: JoinState) {
        composeRule.setContent {
            JoinScreenContent(
                state = state,
                actions = JoinActions(
                    onPickInputs = { events += "pickInputs" },
                    onJoin = { events += "join" },
                    onCancel = { events += "cancel" },
                    onSave = { suggestedName -> events += "save:$suggestedName" },
                    onReset = { events += "reset" },
                ),
            )
        }
    }

    private companion object {
        /** Byte-identical to the converter screen's, and split the same way `main` splits it. */
        const val PAUSED_PARAGRAPH =
            "Paused. Android limits background media processing, so this will " +
                "resume automatically — keeping the app open helps it along."

        const val STREAM_COPY_EXPLANATION =
            "Files matched, so they were joined without " +
                "re-encoding — no quality loss."

        const val REENCODE_EXPLANATION =
            "Files differed in format, so they were re-encoded " +
                "to match."
    }
}
