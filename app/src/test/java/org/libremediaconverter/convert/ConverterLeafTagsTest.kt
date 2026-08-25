package org.libremediaconverter.convert

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.Validation
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner

/**
 * Each leaf of the converter screen renders, and each tag it claims resolves to exactly one node.
 *
 * The defect this bites on is a tag that is not where the table says it is: dropped by a refactor
 * that rewrote a `Modifier` chain, applied to the wrong one of two siblings, or duplicated onto a
 * leaf that is rendered twice. None of that is visible at compile time -- a `testTag` is a string
 * handed to a modifier -- and none of it shows up in the app either, because nothing but a test
 * ever reads one.
 *
 * It has to be caught here rather than by the children that consume the tags. R38.2, R38.3 and
 * R38.4 all *begin* by locating a node through one of these, so a tag that had quietly moved would
 * surface as three unrelated PRs failing on a line their own diffs do not touch. Counting the nodes
 * rather than asserting existence is deliberate: `onNodeWithTag` on two matches throws about
 * ambiguity in one place and passes in another, so "exactly one" is the property worth pinning.
 *
 * Deliberately *not* the state matrix. Which affordances each `ConversionState` renders is R38.6,
 * and it needs the state seam R38.5 extracts -- the branch buttons tagged in this change (Convert,
 * Cancel, Save file, Start over, ...) therefore have no bite yet, which the PR body records.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConverterLeafTagsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun assertResolvesToOneNode(tag: String) {
        composeRule.onAllNodesWithTag(tag).assertCountEquals(1)
    }

    private fun input(sizeBytes: Long? = 12_345_678L, probe: InputProbe? = VIDEO_PROBE) = InputFile(
        uri = Uri.parse("content://test/clip.mkv"),
        displayName = "clip.mkv",
        sizeBytes = sizeBytes,
        probe = probe,
    )

    @Test
    fun `the format picker tags its chip row`() {
        composeRule.setContent { FormatPicker(OutputFormat.MP4_H264) {} }

        assertResolvesToOneNode(TestTags.Converter.FORMAT_CHIPS)
    }

    @Test
    fun `the quality picker tags its chip row`() {
        composeRule.setContent { QualityPicker(QualityTier.FAST) {} }

        assertResolvesToOneNode(TestTags.Converter.QUALITY_CHIPS)
    }

    @Test
    fun `the engine picker tags its chip row`() {
        composeRule.setContent { EnginePicker(EnginePreference.AUTO) {} }

        assertResolvesToOneNode(TestTags.Converter.ENGINE_CHIPS)
    }

    @Test
    fun `the advanced picker tags its toggle, which is all it renders while collapsed`() {
        setAdvancedPicker()

        assertResolvesToOneNode(TestTags.Converter.ADVANCED_TOGGLE)
        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_PANEL).assertDoesNotExist()
    }

    /**
     * The panel and its three rows only exist once the toggle has been clicked, which is R38.4's
     * subject. Expanding is the only way to reach the tags at all, so the smoke test has to do it.
     */
    @Test
    fun `expanding the advanced picker tags the panel and each of its three chip rows`() {
        setAdvancedPicker()

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()

        assertResolvesToOneNode(TestTags.Converter.ADVANCED_PANEL)
        assertResolvesToOneNode(TestTags.Converter.ADVANCED_CONTAINER_CHIPS)
        assertResolvesToOneNode(TestTags.Converter.ADVANCED_VIDEO_CHIPS)
        assertResolvesToOneNode(TestTags.Converter.ADVANCED_AUDIO_CHIPS)
    }

    @Test
    fun `the validation card tags itself and every suggestion on it`() {
        composeRule.setContent {
            ValidationError(
                Validation.Invalid(
                    message = "WebM cannot hold H.264 video.",
                    suggestions = listOf(
                        OutputSpec(Container.MKV, VideoCodec.H264, AudioCodec.AAC),
                        OutputSpec(Container.WEBM, VideoCodec.VP9, AudioCodec.OPUS),
                    ),
                ),
            ) {}
        }

        assertResolvesToOneNode(TestTags.Converter.VALIDATION_ERROR)
        assertResolvesToOneNode(TestTags.Converter.suggestion(0))
        assertResolvesToOneNode(TestTags.Converter.suggestion(1))
    }

    @Test
    fun `the file card tags itself, its name and its size line`() {
        composeRule.setContent { FileCard(input()) }

        assertResolvesToOneNode(TestTags.Converter.FILE_CARD)
        assertResolvesToOneNode(TestTags.Converter.FILE_CARD_NAME)
        assertResolvesToOneNode(TestTags.Converter.FILE_CARD_BYTES)
    }

    /**
     * Both writers of the note line get their own case. They are two separate `Text` calls in two
     * branches that share one tag, so a test of either alone would leave the other unguarded.
     */
    @Test
    fun `the file card tags the note it shows while the probe is still running`() {
        composeRule.setContent { FileCard(input(probe = null)) }

        assertResolvesToOneNode(TestTags.Converter.FILE_CARD_NOTE)
    }

    @Test
    fun `the file card tags the note it shows when nothing could read the file`() {
        composeRule.setContent { FileCard(input(probe = InputProbe(kind = InputKind.UNPARSEABLE))) }

        assertResolvesToOneNode(TestTags.Converter.FILE_CARD_NOTE)
    }

    @Test
    fun `a detail row tags itself with the label it renders`() {
        composeRule.setContent { DetailRow("Container", "Matroska") }

        assertResolvesToOneNode(TestTags.Converter.detailRow("Container"))
    }

    /** The rows the file card builds carry the same per-label tags, one per row it renders. */
    @Test
    fun `the file card's detail rows are each tagged by their own label`() {
        composeRule.setContent { FileCard(input()) }

        assertResolvesToOneNode(TestTags.Converter.detailRow("Container"))
        assertResolvesToOneNode(TestTags.Converter.detailRow("Video"))
        assertResolvesToOneNode(TestTags.Converter.detailRow("Audio"))
        assertResolvesToOneNode(TestTags.Converter.detailRow("Length"))
    }

    private fun setAdvancedPicker() {
        composeRule.setContent {
            AdvancedPicker(
                spec = OutputSpec(Container.MP4, VideoCodec.H264, AudioCodec.AAC),
                validation = Validation.Valid,
                onContainer = {},
                onVideoCodec = {},
                onAudioCodec = {},
                onSuggestion = {},
            )
        }
    }

    private companion object {
        val VIDEO_PROBE = InputProbe(
            videoCodec = "video/avc",
            audioCodec = "audio/mp4a-latm",
            durationMs = 90_000,
            kind = InputKind.VIDEO,
            container = Container.MKV,
            width = 1920,
            height = 1080,
        )
    }
}
