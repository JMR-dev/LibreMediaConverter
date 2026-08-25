package org.libremediaconverter.convert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image-demuxer rule, which looks arbitrary until it is read as a suffix.
 *
 * `MediaProbe.classify` asks [MediaProbe.isImageFormat] before anything else, so this one boolean
 * overrides everything both probes found: true and the source-info card says "Image" and a size,
 * false and it says container, codec and length. Neither mistake fails loudly.
 *
 * The rule has two halves and they are not the same shape. `image2` is a whole format name —
 * FFprobe reports it for a numbered image sequence — while the piped demuxers are named one per
 * image codec, so `_pipe` has to be matched as a *suffix*: `png_pipe`, `jpeg_pipe`, `webp_pipe`
 * and some thirty more. Widening that suffix to a substring is the tempting simplification and it
 * is wrong, because `yuv4mpegpipe` is raw video.
 *
 * Every format name asserted here was read back from `ffprobe -show_entries format=format_name`
 * rather than guessed: a picked `.png` reports `png_pipe`, a picked `.jpg` reports `jpeg_pipe`, a
 * `.y4m` reports `yuv4mpegpipe`, and `image2` needs the demuxer named explicitly.
 *
 * One known gap, deliberately not asserted either way: `image2pipe` is a real FFprobe format name
 * for an image read from a stream, and this rule answers false for it — it is not `image2` and
 * does not end in `_pipe`. Whether that is worth fixing is a question about picked-file behaviour
 * on a device, not something to settle by pinning today's answer here.
 */
class MediaProbeImageFormatTest {

    @Test
    fun `a numbered image sequence is an image`() {
        assertTrue(MediaProbe.isImageFormat("image2"))
    }

    /** What a picked PNG or JPEG actually reports, and the reason the suffix rule exists. */
    @Test
    fun `the per-codec piped demuxers are images`() {
        assertTrue(MediaProbe.isImageFormat("png_pipe"))
        assertTrue(MediaProbe.isImageFormat("jpeg_pipe"))
        assertTrue(MediaProbe.isImageFormat("webp_pipe"))
    }

    /**
     * The half that a substring match would break.
     *
     * `yuv4mpegpipe` contains `pipe` and is not an image: it is raw uncompressed video, and
     * describing it as an image would hide its codec, its size and its length from the card while
     * leaving the file perfectly convertible.
     */
    @Test
    fun `a format that merely contains pipe is not an image`() {
        assertFalse(MediaProbe.isImageFormat("yuv4mpegpipe"))
    }

    /** The ordinary media containers, which is what the false answer is mostly for. */
    @Test
    fun `a real container is not an image`() {
        assertFalse(MediaProbe.isImageFormat("mov,mp4,m4a,3gp,3g2,mj2"))
        assertFalse(MediaProbe.isImageFormat("matroska,webm"))
        assertFalse(MediaProbe.isImageFormat("mp3"))
    }

    /**
     * FFprobe names every format sharing the demuxer, so the entry that matters can be anywhere in
     * the list — and the padding and case are normalised the same way [MediaProbe.containerFrom]
     * normalises them.
     */
    @Test
    fun `an image entry is found anywhere in the list, whatever its spacing or case`() {
        assertTrue(MediaProbe.isImageFormat("PNG_PIPE"))
        assertTrue(MediaProbe.isImageFormat(" image2 "))
        assertTrue(MediaProbe.isImageFormat("something_else, tiff_pipe"))
    }

    /** Nothing to go on is not an image; the card falls back to describing an unknown container. */
    @Test
    fun `an empty format name is not an image`() {
        assertFalse(MediaProbe.isImageFormat(""))
    }
}
