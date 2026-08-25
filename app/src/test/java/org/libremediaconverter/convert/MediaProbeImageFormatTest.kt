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
 * The image names were measured rather than recalled. `ffprobe -show_entries format=format_name`
 * reports `png_pipe` for a `.png`, `jpeg_pipe` for a `.jpg`, `yuv4mpegpipe` for a `.y4m`, and
 * `image2` only when that demuxer is named explicitly. The container names come from
 * [MediaProbeFormatTest], and the case and spacing variants are synthetic — those exercise the
 * normalisation rather than anything FFprobe emits.
 *
 * One real format name is deliberately not asserted either way. `image2pipe` gets a false answer
 * here, being neither `image2` nor a `_pipe` suffix, and that is inert rather than a latent bug:
 * FFprobe only selects it when the demuxer is named with `-f image2pipe`, while `probeWithFFprobe`
 * forces no format at all, so a picked image arrives as `png_pipe` or its own codec's equivalent.
 * Pinning today's answer for a name this app cannot receive would be a test about FFmpeg's command
 * line rather than about this rule.
 */
class MediaProbeImageFormatTest {

    @Test
    fun `a numbered image sequence is an image`() {
        assertIsImage("image2")
    }

    /** What a picked PNG or JPEG actually reports, and the reason the suffix rule exists. */
    @Test
    fun `the per-codec piped demuxers are images`() {
        assertIsImage("png_pipe")
        assertIsImage("jpeg_pipe")
        assertIsImage("webp_pipe")
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
        assertNotImage("yuv4mpegpipe")
    }

    /** The ordinary media containers, which is what the false answer is mostly for. */
    @Test
    fun `a real container is not an image`() {
        assertNotImage("mov,mp4,m4a,3gp,3g2,mj2")
        assertNotImage("matroska,webm")
        assertNotImage("mp3")
    }

    /**
     * FFprobe names every format sharing the demuxer, so the entry that matters can be anywhere in
     * the list — and the padding and case are normalised the same way [MediaProbe.containerFrom]
     * normalises them.
     */
    @Test
    fun `an image entry is found anywhere in the list, whatever its spacing or case`() {
        assertIsImage("PNG_PIPE")
        assertIsImage(" image2 ")
        assertIsImage("something_else, tiff_pipe")
    }

    /** Nothing to go on is not an image; the card falls back to describing an unknown container. */
    @Test
    fun `an empty format name is not an image`() {
        assertNotImage("")
    }

    private fun assertIsImage(formatName: String) =
        assertTrue("isImageFormat(\"$formatName\")", MediaProbe.isImageFormat(formatName))

    private fun assertNotImage(formatName: String) =
        assertFalse("isImageFormat(\"$formatName\")", MediaProbe.isImageFormat(formatName))
}
