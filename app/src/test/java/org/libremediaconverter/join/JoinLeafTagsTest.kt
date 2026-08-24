package org.libremediaconverter.join

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.media3.common.util.UnstableApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.InputFile
import org.libremediaconverter.createDrainedComposeRule
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner

/**
 * The join screen's one leaf renders, and tags itself with the file it is showing.
 *
 * `FileRow` is the only place on either screen where the same leaf is rendered more than once at a
 * time -- one row per picked input -- so it is the only tag that cannot be a constant. It is
 * derived from `displayName`, inside `FileRow` itself, and that is the part worth a test: a row
 * that took its tag from the call site would let R38.7 pass a tag in and assert nothing, which is
 * the vacuous shape `CLAUDE.md` records nine of in one review.
 *
 * Two rows are rendered here rather than one, because a tag derived from the wrong thing -- a
 * constant, an index the row does not have -- would still resolve to one node with a single input
 * on screen.
 *
 * Deliberately not the state matrix: which affordances each `JoinState` renders is R38.7.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JoinLeafTagsTest {

    @get:Rule
    val composeRule = createDrainedComposeRule()

    private fun input(displayName: String) = InputFile(
        uri = Uri.parse("content://test/$displayName"),
        displayName = displayName,
        sizeBytes = 4_000_000L,
    )

    @Test
    fun `each file row is tagged with the name it displays`() {
        composeRule.setContent {
            FileRow(input("first.mp4"))
            FileRow(input("second.mp4"))
        }

        composeRule.onAllNodesWithTag(TestTags.Join.fileRow("first.mp4")).assertCountEquals(1)
        composeRule.onAllNodesWithTag(TestTags.Join.fileRow("second.mp4")).assertCountEquals(1)
    }
}
