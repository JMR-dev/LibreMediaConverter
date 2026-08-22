package org.libremediaconverter.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.libremediaconverter.model.Container

/**
 * Reading FFprobe's `format_name`, which is the only source of the source container.
 *
 * The trap is that FFprobe names every format sharing the demuxer, not the one the file actually
 * is: a plain MP4 comes back as `mov,mp4,m4a,3gp,3g2,mj2`. Matching the whole string against a
 * container name never succeeds, and taking the first entry reports every MP4 as a MOV.
 *
 * Getting this wrong fails silently rather than loudly — a null container just means `CopyPlanner`
 * cannot tell the container is changing, so it declines to upgrade to a stream copy and everything
 * is re-encoded. The feature would look like it simply did not work.
 */
class MediaProbeFormatTest {

    @Test
    fun `an MP4 is not mistaken for a MOV`() {
        assertEquals(
            Container.MP4,
            MediaProbe.containerFrom("mov,mp4,m4a,3gp,3g2,mj2"),
        )
    }

    @Test
    fun `a real QuickTime file is recognised`() {
        assertEquals(Container.MOV, MediaProbe.containerFrom("mov"))
    }

    /**
     * WebM and Matroska share a demuxer and report the same format name.
     *
     * Both committed fixtures — an H.264 `.mkv` and a VP9 `.webm` — come back as `matroska,webm`,
     * so the string alone cannot separate them. The codec is the only remaining signal: WebM
     * permits VP8/VP9/AV1 and nothing else.
     */
    @Test
    fun `a Matroska file carrying H264 is not reported as WebM`() {
        assertEquals(Container.MKV, MediaProbe.containerFrom("matroska,webm", "h264"))
    }

    @Test
    fun `a WebM-legal codec in that container is reported as WebM`() {
        assertEquals(Container.WEBM, MediaProbe.containerFrom("matroska,webm", "vp9"))
        assertEquals(Container.WEBM, MediaProbe.containerFrom("matroska,webm", "av1"))
    }

    /** With no video track there is nothing to disambiguate on; Matroska is the general case. */
    @Test
    fun `an audio-only Matroska file is reported as Matroska`() {
        assertEquals(Container.MKV, MediaProbe.containerFrom("matroska,webm", null))
    }

    @Test
    fun `the remaining containers map to themselves`() {
        assertEquals(Container.MPEG_TS, MediaProbe.containerFrom("mpegts"))
        assertEquals(Container.AVI, MediaProbe.containerFrom("avi"))
        assertEquals(Container.FLV, MediaProbe.containerFrom("flv"))
        assertEquals(Container.ASF, MediaProbe.containerFrom("asf"))
        assertEquals(Container.WAV, MediaProbe.containerFrom("wav"))
        assertEquals(Container.MP3, MediaProbe.containerFrom("mp3"))
        assertEquals(Container.FLAC, MediaProbe.containerFrom("flac"))
        assertEquals(Container.OGG, MediaProbe.containerFrom("ogg"))
    }

    @Test
    fun `whitespace and case do not matter`() {
        assertEquals(Container.MP4, MediaProbe.containerFrom("MOV, MP4, M4A"))
    }

    /**
     * Unknown means unknown, not a guess.
     *
     * A wrong container would let the copy planner believe the container is changing when it is
     * not, or the reverse — and the planner's whole discipline is that it never acts on a guess.
     */
    @Test
    fun `an unrecognised format name yields null rather than a guess`() {
        assertNull(MediaProbe.containerFrom("some_new_format"))
        assertNull(MediaProbe.containerFrom(""))
    }
}
