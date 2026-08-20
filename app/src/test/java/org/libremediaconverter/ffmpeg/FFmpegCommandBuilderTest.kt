package org.libremediaconverter.ffmpeg

import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FFmpegCommandBuilderTest {

    private fun cmd(
        format: OutputFormat,
        quality: QualityTier = QualityTier.BEST,
        hardwareEncodeAvailable: Boolean = true,
    ): List<String> = FFmpegCommandBuilder.build(
        ConversionRequest(
            format = format,
            quality = quality,
            hardwareEncodeAvailable = hardwareEncodeAvailable,
        ),
        inputPath = "/cache/in.mp4",
        outputPath = "/cache/out.${format.extension}",
    )

    /** Asserts `flag` is present and immediately followed by `value`. */
    private fun assertPair(args: List<String>, flag: String, value: String) {
        val i = args.indexOf(flag)
        assertTrue("missing $flag in $args", i >= 0)
        assertEquals("wrong value for $flag in $args", value, args[i + 1])
    }

    // --- structure ---------------------------------------------------------

    @Test
    fun `input precedes output and both are present`() {
        val args = cmd(OutputFormat.MP4_H264)
        assertPair(args, "-i", "/cache/in.mp4")
        assertEquals("/cache/out.mp4", args.last())
    }

    @Test
    fun `overwrite is enabled because the output path is our own cache file`() {
        assertTrue(cmd(OutputFormat.MP4_H264).contains("-y"))
    }

    // --- the GPL quality path ---------------------------------------------

    @Test
    fun `best quality h264 uses libx264 with crf, not a bitrate target`() {
        val args = cmd(OutputFormat.MP4_H264, QualityTier.BEST)
        assertPair(args, "-c:v", "libx264")
        assertPair(args, "-crf", "20")
        assertFalse("CRF and -b:v are mutually exclusive", args.contains("-b:v"))
    }

    @Test
    fun `best quality h265 uses libx265 with a higher crf than h264`() {
        val args = cmd(OutputFormat.MP4_H265, QualityTier.BEST)
        assertPair(args, "-c:v", "libx265")
        // x265 is roughly a step stronger at the same number, so sharing a constant
        // with x264 would silently change the size target.
        assertPair(args, "-crf", "24")
    }

    @Test
    fun `hevc in mp4 is tagged hvc1 for player compatibility`() {
        assertPair(cmd(OutputFormat.MP4_H265, QualityTier.BEST), "-tag:v", "hvc1")
    }

    /**
     * Regression test for a failure found with real footage on a Pixel 10 Pro XL.
     *
     * The source was H.264 High 4:4:4 Predictive. FFmpeg decodes that to yuv444p and,
     * without an explicit pixel format, hands those frames straight to an encoder that
     * cannot accept them — hevc_mediacodec died with "Invalid to call at Released
     * state" partway through. Every video encode path has to name the format so FFmpeg
     * inserts the conversion, not just the one path that happened to have it.
     */
    @Test
    fun `every video encode path forces yuv420p`() {
        val videoFormats = listOf(
            OutputFormat.MP4_H264, OutputFormat.MP4_H265,
            OutputFormat.MKV_H264, OutputFormat.MKV_H265,
            OutputFormat.WEBM_VP9,
        )
        videoFormats.forEach { format ->
            listOf(QualityTier.FAST, QualityTier.BEST).forEach { quality ->
                listOf(true, false).forEach { hw ->
                    val args = cmd(format, quality, hardwareEncodeAvailable = hw)
                    assertPair(args, "-pix_fmt", "yuv420p")
                }
            }
        }
    }

    @Test
    fun `audio only outputs do not set a pixel format`() {
        listOf(OutputFormat.MP3, OutputFormat.FLAC, OutputFormat.WAV).forEach {
            assertFalse("${it.name} should not set -pix_fmt", cmd(it).contains("-pix_fmt"))
        }
    }

    // --- the hardware fallback path ---------------------------------------

    /**
     * Regression test for a defect found on a device with no hardware HEVC encoder.
     *
     * FFmpeg's *_mediacodec wrappers do not fail on such a device — they quietly bind
     * to the platform software codec (c2.android.*) and encode far slower than
     * libx264/libx265 would, while still presenting as the fast path. When no hardware
     * encoder exists, a real software encoder on a fast preset is both quicker and
     * honest about what it is doing.
     */
    @Test
    fun `the encoder choice no longer depends on hardware availability`() {
        // Once FFmpeg stopped selecting MediaCodec encoders, this flag only affects
        // whether the router sends the job to Media3 at all -- not what FFmpeg does.
        listOf(OutputFormat.MP4_H264, OutputFormat.MP4_H265).forEach { format ->
            assertEquals(
                cmd(format, QualityTier.FAST, hardwareEncodeAvailable = true),
                cmd(format, QualityTier.FAST, hardwareEncodeAvailable = false),
            )
        }
    }

    @Test
    fun `fast software preset is faster than the best quality preset`() {
        // Both use libx264; the distinction between the tiers has to survive the
        // fallback, otherwise Fast and Best become the same slow thing.
        val fast = cmd(OutputFormat.MP4_H264, QualityTier.FAST, hardwareEncodeAvailable = false)
        val best = cmd(OutputFormat.MP4_H264, QualityTier.BEST)
        assertEquals("veryfast", fast[fast.indexOf("-preset") + 1])
        assertEquals("medium", best[best.indexOf("-preset") + 1])
    }

    // --- audio -------------------------------------------------------------

    @Test
    fun `mp3 uses libmp3lame and drops video`() {
        val args = cmd(OutputFormat.MP3)
        assertPair(args, "-c:a", "libmp3lame")
        assertTrue("audio-only output must drop the video stream", args.contains("-vn"))
    }

    @Test
    fun `flac wav and opus select the right encoders`() {
        assertPair(cmd(OutputFormat.FLAC), "-c:a", "flac")
        assertPair(cmd(OutputFormat.WAV), "-c:a", "pcm_s16le")
        assertPair(cmd(OutputFormat.OPUS), "-c:a", "libopus")
    }

    @Test
    fun `audio only formats never carry a video encoder`() {
        listOf(OutputFormat.MP3, OutputFormat.FLAC, OutputFormat.WAV, OutputFormat.OPUS)
            .forEach { format ->
                val args = cmd(format)
                assertFalse("$format should not set -c:v", args.contains("-c:v"))
                assertTrue("$format should set -vn", args.contains("-vn"))
            }
    }

    // --- image outputs -----------------------------------------------------

    @Test
    fun `gif generates a palette to avoid banding and drops audio`() {
        val args = cmd(OutputFormat.GIF)
        val filter = args[args.indexOf("-vf") + 1]
        assertTrue("gif needs palettegen: $filter", filter.contains("palettegen"))
        assertTrue("gif needs paletteuse: $filter", filter.contains("paletteuse"))
        assertTrue(args.contains("-an"))
    }

    @Test
    fun `gif loops forever`() {
        assertPair(cmd(OutputFormat.GIF), "-loop", "0")
    }

    @Test
    fun `frame export sets an fps filter and numbered output`() {
        val args = cmd(OutputFormat.FRAMES_PNG)
        assertPair(args, "-vf", "fps=1")
        assertEquals(
            "shot_%04d.png",
            FFmpegCommandBuilder.outputPattern(OutputFormat.FRAMES_PNG, "shot"),
        )
    }

    @Test
    fun `single file formats keep their plain name`() {
        assertEquals(
            "clip.mp4",
            FFmpegCommandBuilder.outputPattern(OutputFormat.MP4_H264, "clip.mp4"),
        )
    }

    // --- containers --------------------------------------------------------

    @Test
    fun `mp4 output enables faststart`() {
        assertPair(cmd(OutputFormat.MP4_H264), "-movflags", "+faststart")
    }

    @Test
    fun `non mp4 containers do not get faststart`() {
        assertFalse(cmd(OutputFormat.MKV_H264).contains("-movflags"))
        assertFalse(cmd(OutputFormat.WAV).contains("-movflags"))
    }

    @Test
    fun `mkv accepts h264 and h265 without faststart`() {
        assertPair(cmd(OutputFormat.MKV_H264, QualityTier.BEST), "-c:v", "libx264")
        assertPair(cmd(OutputFormat.MKV_H265, QualityTier.BEST), "-c:v", "libx265")
    }

    // --- exhaustiveness ----------------------------------------------------

    @Test
    fun `every format builds a well formed command`() {
        OutputFormat.entries.forEach { format ->
            listOf(QualityTier.FAST, QualityTier.BEST).forEach { quality ->
                val args = cmd(format, quality)
                assertTrue("$format/$quality has no input", args.contains("-i"))
                assertTrue("$format/$quality has too few args", args.size >= 5)
                // Every flag that takes a value must actually have one.
                assertFalse("$format/$quality ends on a dangling flag", args.last().startsWith("-"))
            }
        }
    }
}
