package org.libremediaconverter.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe

/**
 * Which of the two probes wins, when they disagree.
 *
 * [MediaProbe.probe] runs `MediaExtractor` and FFprobe independently and then merges the two, and
 * every rule in that merge is a decision. None of them had a test, for a reason that is structural
 * rather than an oversight: `RemuxTest` drives the whole thing on a device against committed
 * fixtures, but only ever with **one probe answering and the other agreeing or also failing**.
 * Nothing on any source set can arrange for a real extractor and a real FFprobe to disagree, so
 * every elvis in the merge was taken in one direction and never the other.
 *
 * Cutting `merge` out of `probe` is what makes the question askable. Both halves of its signature
 * had to become `internal` for that -- `Extracted` already was, with a KDoc giving this exact
 * reason; `FFprobeInfo` simply never got the same treatment.
 */
class MediaProbeMergeTest {

    /**
     * The rule with the loudest failure mode, and `isImageFormat`'s own KDoc names it: a false
     * positive here "makes the source-info card describe a video as an image". So the image verdict
     * has to beat a real video codec from the extractor, and the ordering that makes it do so is
     * the first arm of `classify` rather than anything a reader would infer from the fields.
     */
    @Test
    fun `an image verdict from FFprobe beats a video codec from the extractor`() {
        val merged = MediaProbe.merge(
            extracted = extracted(video = "h264"),
            info = info(video = "mjpeg", isImage = true),
        )

        assertEquals(InputKind.IMAGE, merged.kind)
    }

    @Test
    fun `the extractor wins on codecs, because it is the view the router will act on`() {
        val merged = MediaProbe.merge(
            extracted = extracted(video = "h264", audio = "aac"),
            info = info(video = "hevc", audio = "mp3"),
        )

        assertEquals("h264", merged.videoCodec)
        assertEquals("aac", merged.audioCodec)
    }

    @Test
    fun `FFprobe answers for a file the extractor could not open`() {
        val merged = MediaProbe.merge(extracted = null, info = info(video = "vp9", audio = "opus"))

        assertEquals("vp9", merged.videoCodec)
        assertEquals("opus", merged.audioCodec)
        assertEquals(InputKind.VIDEO, merged.kind)
    }

    @Test
    fun `the extractor answers for a file FFprobe could not read`() {
        val merged = MediaProbe.merge(extracted = extracted(video = "h264", audio = "aac"), info = null)

        assertEquals("h264", merged.videoCodec)
        assertEquals("aac", merged.audioCodec)
        assertNull("only FFprobe can name the container, so it stays unknown here", merged.container)
    }

    /**
     * The larger of the two, not the first non-zero.
     *
     * Either probe can report zero for a file the other times correctly, and a zero duration makes
     * the FFmpeg progress percentage undefined -- `FFmpegEngine` divides by it. Both orderings are
     * asserted because "take the extractor's" and "take the larger" agree in one direction and not
     * the other, and only one of them is the rule.
     */
    @Test
    fun `duration is the longer of the two readings, whichever probe supplied it`() {
        assertEquals(
            5_000L,
            MediaProbe.merge(extracted(duration = 0L), info(duration = 5_000L)).durationMs,
        )
        assertEquals(
            5_000L,
            MediaProbe.merge(extracted(duration = 5_000L), info(duration = 0L)).durationMs,
        )
    }

    @Test
    fun `dimensions come from the extractor, and from FFprobe only when it has none`() {
        assertEquals(1920, MediaProbe.merge(extracted(width = 1920), info(width = 640)).width)
        assertEquals(640, MediaProbe.merge(extracted = null, info = info(width = 640)).width)
        assertEquals(0, MediaProbe.merge(extracted(width = 0), info(width = 0)).width)
    }

    @Test
    fun `the container comes from FFprobe, which is the only probe that can name one`() {
        val merged = MediaProbe.merge(extracted(video = "h264"), info(container = Container.MKV))

        assertEquals(Container.MKV, merged.container)
    }

    @Test
    fun `a file with audio and no video is audio-only, not unparseable`() {
        val merged = MediaProbe.merge(extracted(video = null, audio = "mp3"), info = null)

        assertEquals(InputKind.AUDIO_ONLY, merged.kind)
        assertFalse(merged.hasVideo)
    }

    @Test
    fun `a file neither probe could open is the one unreadable answer`() {
        val merged = MediaProbe.merge(extracted = null, info = null)

        assertEquals(MediaProbe.UNREADABLE, merged)
        assertEquals(InputProbe.UNPARSEABLE, merged.videoCodec)
    }

    /**
     * The arm the ticket was filed for: parsed, and carrying no stream either probe recognised.
     *
     * Distinct from "neither probe could open it" -- here the extractor opened the file happily and
     * found nothing convertible, which is what a container holding only subtitles looks like. It
     * has to reach the same [MediaProbe.UNREADABLE] answer, because the router keys off that and
     * there is nothing here for Media3 to do either way.
     *
     * Its input was already being built elsewhere in the suite -- `MediaProbeTrackWalkTest` calls
     * `extractedFrom(emptyList())` and gets exactly this -- and had simply never been handed to the
     * merge.
     */
    @Test
    fun `a file that parsed but carries no recognised stream is unreadable too`() {
        val merged = MediaProbe.merge(extracted = MediaProbe.extractedFrom(emptyList()), info = null)

        assertEquals(InputKind.UNPARSEABLE, merged.kind)
        assertEquals(MediaProbe.UNREADABLE, merged)
    }

    @Test
    fun `hasVideo follows the codec that survived the merge, not either probe alone`() {
        assertTrue(MediaProbe.merge(extracted(video = null), info(video = "vp9")).hasVideo)
        assertFalse(MediaProbe.merge(extracted(video = null, audio = "aac"), info(video = null)).hasVideo)
    }

    private fun extracted(
        video: String? = "h264",
        audio: String? = "aac",
        duration: Long = 1_000L,
        width: Int = 1280,
        height: Int = 720,
    ) = MediaProbe.Extracted(video, audio, duration, width, height)

    private fun info(
        container: Container? = null,
        video: String? = "h264",
        audio: String? = "aac",
        duration: Long = 1_000L,
        width: Int = 1280,
        height: Int = 720,
        isImage: Boolean = false,
    ) = MediaProbe.FFprobeInfo(container, video, audio, duration, width, height, isImage)
}
