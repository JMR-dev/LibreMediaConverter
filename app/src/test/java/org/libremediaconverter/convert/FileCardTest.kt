package org.libremediaconverter.convert

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.util.UnstableApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner

/**
 * What the source-info card says when it does not know something.
 *
 * The defect is a card that invents an answer instead of admitting it has none. Two of them are
 * live here and neither had a test before this file:
 *
 * - **`InputFile.sizeBytes` is nullable and the card is the reader that has to say so in words.**
 *   `sizeBytes` used to be `0L` for "nobody told me", and [UnknownInputSizeTest] records what that
 *   cost at the space check. The card is the other reader, and its failure mode is the mirror
 *   image: hand the null to `formatBytes` and it renders `"0 B"` -- a measurement, shown to the
 *   user, that no provider ever made. It renders **independently of the probe**, which is why the
 *   same assertion appears twice below, with the probe present and absent. That independence is
 *   the contract; a test covering only the probed case would leave the branch a user actually hits
 *   first -- the card is on screen before the probe finishes -- unguarded.
 * - **The codec rows degrade in words too.** `CodecNames.describeVideo`/`describeAudio` answer
 *   `"Unknown"` for a codec nothing named, the `VIDEO` branch answers `"No audio track"` for a file
 *   with no audio, and the two `> 0` guards drop the dimension and length rows rather than printing
 *   `0` and `0:00`. Each of those has a case below on **both** sides of the guard, because a test
 *   of the present side alone stays green with the guard deleted.
 *
 * ### What cannot be asserted here, so that it is a decision rather than an omission
 *
 * The `probe == null` branch exits before `HorizontalDivider`, and **the divider's absence is not
 * observable from a test**: Material 3 renders it as a `Box` with no semantics modifier, so it
 * contributes no node to the semantics tree at all. What is asserted instead is everything the
 * divider precedes -- no detail row for any label the four kind branches can emit -- plus the
 * card's child count, which pins "these three texts and nothing else" without having to enumerate.
 *
 * The early exit itself is enforced by the compiler rather than by this file, which the PR body
 * records: deleting `return@Column` un-smart-casts `probe`, and the `probe.kind` below it stops
 * compiling. The mutation that reddens the test here is the compilable form of that regression --
 * defaulting the null away with `?: InputProbe()` and letting the kind rows render.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class FileCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a file no provider could measure says so in words rather than showing a zero`() {
        setFileCard(input(sizeBytes = null, probe = VIDEO_PROBE))

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_BYTES)
            .assertTextEquals("Size unknown")
    }

    /**
     * The same line, with no probe at all. Separate from the case above rather than folded into
     * it because `setContent` may only be called once per rule, and because two independent reds
     * are the evidence that the size line does not depend on the probe.
     */
    @Test
    fun `the size line says the same thing while the probe is still running`() {
        setFileCard(input(sizeBytes = null, probe = null))

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_BYTES)
            .assertTextEquals("Size unknown")
    }

    @Test
    fun `a size that was reported is formatted rather than replaced by the unknown line`() {
        setFileCard(input(sizeBytes = 12_345_678L, probe = VIDEO_PROBE))

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NAME).assertTextEquals("clip.mkv")
        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_BYTES).assertTextEquals("12.3 MB")
    }

    /**
     * The note and the emptiness are one behaviour, so they are one test: a regression that keeps
     * the note but renders the rows anyway would leave a note-only test green.
     */
    @Test
    fun `while the probe is still running the card shows the reading note and nothing else`() {
        setFileCard(input(probe = null))

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NOTE)
            .assertTextEquals("Reading…")
        assertNoDetailRows()
        // Name, size, note. Catches a row whose label is not in EVERY_ROW_LABEL as well.
        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD).onChildren().assertCountEquals(3)
    }

    @Test
    fun `a file nothing could read gets the explanatory line instead of unknown codecs`() {
        setFileCard(input(probe = InputProbe(kind = InputKind.UNPARSEABLE)))

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NOTE)
            .assertTextEquals("Could not identify this file. It will be converted with FFmpeg.")
        assertNoDetailRows()
    }

    @Test
    fun `an image gets its type and its pixel dimensions`() {
        setFileCard(input(probe = InputProbe(kind = InputKind.IMAGE, width = 1920, height = 1080)))

        assertRow("Type", "Image")
        assertRow("Size", "1920×1080")
    }

    /** The `width > 0` guard, from the side that would print `0×0` if it were dropped. */
    @Test
    fun `an image whose dimensions nothing reported gets the type row alone`() {
        setFileCard(input(probe = InputProbe(kind = InputKind.IMAGE)))

        assertRow("Type", "Image")
        assertNoRow("Size")
    }

    @Test
    fun `an audio-only file says it has no video track rather than leaving the row blank`() {
        setFileCard(
            input(
                probe = InputProbe(
                    audioCodec = "aac",
                    hasVideo = false,
                    durationMs = 90_000,
                    kind = InputKind.AUDIO_ONLY,
                    container = Container.MP3,
                ),
            ),
        )

        assertRow("Container", Container.MP3.label)
        assertRow("Video", "No video track")
        assertRow("Audio", AudioCodec.AAC.label)
        assertRow("Length", "1:30")
        assertNoRow("Type")
        assertNoRow("Size")
    }

    /**
     * Everything the audio branch can fail to know, at once: no container, no codec name, no
     * duration. Each degrades in its own words, and the length row disappears rather than
     * claiming `0:00`.
     */
    @Test
    fun `an audio-only file nothing else could describe degrades one row at a time`() {
        setFileCard(input(probe = InputProbe(hasVideo = false, kind = InputKind.AUDIO_ONLY)))

        assertRow("Container", "Unknown")
        assertRow("Video", "No video track")
        assertRow("Audio", "Unknown")
        assertNoRow("Length")
    }

    @Test
    fun `a video file composes its codec with its dimensions on one row`() {
        setFileCard(input(probe = VIDEO_PROBE))

        assertRow("Container", Container.MP4.label)
        assertRow("Video", "${VideoCodec.H264.label} · 1920×1080")
        assertRow("Audio", AudioCodec.AAC.label)
        assertRow("Length", "1:30")
    }

    /**
     * `"No audio track"` rather than `describeAudio(null)`'s `"Unknown"`. The video branch knows
     * the difference between a track it could not name and a track that is not there; the audio
     * branch above cannot, because a file with no audio is not audio-only.
     */
    @Test
    fun `a video file with no audio track says so instead of naming an unknown codec`() {
        setFileCard(input(probe = VIDEO_PROBE.copy(audioCodec = null)))

        assertRow("Audio", "No audio track")
    }

    /** Both `> 0` guards on the video branch, plus the codec name nothing supplied. */
    @Test
    fun `a video file missing its codec, dimensions and duration omits them rather than faking them`() {
        setFileCard(
            input(
                probe = VIDEO_PROBE.copy(
                    videoCodec = null,
                    width = 0,
                    height = 0,
                    durationMs = 0,
                ),
            ),
        )

        assertRow("Video", "Unknown")
        assertNoRow("Length")
    }

    /**
     * The row is one node, not a label node beside a value node. A test matching on `"Container"`
     * alone would pass against either shape.
     */
    @Test
    fun `a detail row renders its label and its value as a single node`() {
        composeRule.setContent { DetailRow("Container", "Matroska") }

        composeRule.onNodeWithTag(TestTags.Converter.detailRow("Container"))
            .assertTextEquals("Container: Matroska")
    }

    private fun setFileCard(input: InputFile) = composeRule.setContent { FileCard(input) }

    private fun input(sizeBytes: Long? = 12_345_678L, probe: InputProbe? = VIDEO_PROBE) = InputFile(
        uri = Uri.parse("content://test/clip.mkv"),
        displayName = "clip.mkv",
        sizeBytes = sizeBytes,
        probe = probe,
    )

    private fun assertRow(label: String, value: String) {
        composeRule.onNodeWithTag(TestTags.Converter.detailRow(label))
            .assertTextEquals("$label: $value")
    }

    private fun assertNoRow(label: String) {
        composeRule.onNodeWithTag(TestTags.Converter.detailRow(label)).assertDoesNotExist()
    }

    private fun assertNoDetailRows() = EVERY_ROW_LABEL.forEach(::assertNoRow)

    private companion object {
        /** Every label the four kind branches can emit, so absence can be asserted exhaustively. */
        val EVERY_ROW_LABEL = listOf("Container", "Video", "Audio", "Length", "Type", "Size")

        val VIDEO_PROBE = InputProbe(
            videoCodec = "h264",
            audioCodec = "aac",
            durationMs = 90_000,
            kind = InputKind.VIDEO,
            container = Container.MP4,
            width = 1920,
            height = 1080,
        )
    }
}
