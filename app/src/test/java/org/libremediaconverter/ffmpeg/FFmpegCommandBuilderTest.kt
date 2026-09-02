package org.libremediaconverter.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.VideoCodec

class FFmpegCommandBuilderTest {

    private fun cmd(
        format: OutputFormat,
        quality: QualityTier = QualityTier.BEST,
        hardwareEncodeAvailable: Boolean = true,
    ): List<String> = cmd(format.spec, quality, hardwareEncodeAvailable)

    private fun cmd(
        spec: OutputSpec,
        quality: QualityTier = QualityTier.BEST,
        hardwareEncodeAvailable: Boolean = true,
        probe: InputProbe = InputProbe(videoCodec = "h264", audioCodec = "aac"),
    ): List<String> = FFmpegCommandBuilder.build(
        ConversionRequest(
            spec = spec,
            quality = quality,
            probe = probe,
            hardwareEncodeAvailable = hardwareEncodeAvailable,
        ),
        inputPath = "/cache/in.mp4",
        outputPath = "/cache/out.${spec.extension}",
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
            OutputFormat.MP4_H264,
            OutputFormat.MP4_H265,
            OutputFormat.MKV_H264,
            OutputFormat.MKV_H265,
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

    /**
     * The arm most conversions actually take, and the only one in `audioArgs` with no test.
     *
     * `flac wav and opus select the right encoders` above covers the three named arms; MP3 has its
     * own. AAC arrives through the `else`, so nothing named it and nothing pinned either half of
     * what it emits -- neither `aac` nor `192k` appeared anywhere in this file. Both are shipped
     * defaults: MP4 and M4A are the formats the picker offers first, so this is the audio
     * every ordinary conversion gets.
     *
     * The bitrate is asserted as well as the encoder because it is the half a refactor is likelier
     * to lose. An `-b:a` that quietly changed would not fail anything, would not look wrong in a
     * command line, and would show up only as files that sound different from the ones the app
     * produced last month.
     */
    @Test
    fun `aac is the default encoder, at the bitrate the app ships`() {
        assertPair(cmd(OutputFormat.MP4_H264), "-c:a", "aac")
        assertPair(cmd(OutputFormat.MP4_H264), "-b:a", "192k")
        // Through the `else` rather than through a named arm, so an AAC branch added above it later
        // has to keep answering the same way.
        assertPair(cmd(OutputFormat.M4A_AAC), "-c:a", "aac")
        assertPair(cmd(OutputFormat.M4A_AAC), "-b:a", "192k")
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
            FFmpegCommandBuilder.outputPattern(Container.IMAGE_SEQUENCE, "shot"),
        )
    }

    @Test
    fun `single file formats keep their plain name`() {
        assertEquals(
            "clip.mp4",
            FFmpegCommandBuilder.outputPattern(Container.MP4, "clip.mp4"),
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

    // --- stream copy --------------------------------------------------------

    @Test
    fun `a pure remux copies both tracks and encodes neither`() {
        val args = cmd(OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY))

        assertPair(args, "-c:v", "copy")
        assertPair(args, "-c:a", "copy")
        assertFalse("a copy must not name an encoder: $args", args.contains("libx264"))
        assertFalse(args.contains("libx265"))
        assertFalse(args.contains("aac"))
    }

    /**
     * A stream copy decodes nothing, so there are no frames to convert.
     *
     * Every encode path pins yuv420p for good reason, but carrying the flag onto the copy path
     * would be meaningless at best and would force a decode at worst.
     */
    @Test
    fun `a stream copy does not pin a pixel format`() {
        val args = cmd(OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY))
        assertFalse("copying needs no -pix_fmt: $args", args.contains("-pix_fmt"))
    }

    @Test
    fun `copying video while re-encoding audio does both`() {
        val args = cmd(OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.OPUS))
        assertPair(args, "-c:v", "copy")
        assertPair(args, "-c:a", "libopus")
    }

    /**
     * The brand matters on the copy path too.
     *
     * Remuxing HEVC out of Matroska into MP4 moves byte-identical samples, but without the hvc1
     * brand Apple devices and many hardware players refuse the result.
     */
    @Test
    fun `copying HEVC into MP4 still tags it hvc1`() {
        val args = cmd(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = "hevc", audioCodec = "aac", container = Container.MKV),
        )
        assertPair(args, "-c:v", "copy")
        assertPair(args, "-tag:v", "hvc1")
    }

    @Test
    fun `copying HEVC into Matroska does not tag it`() {
        val args = cmd(
            OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = "hevc", audioCodec = "aac", container = Container.MP4),
        )
        assertFalse("hvc1 is an ISO-BMFF brand, not a Matroska one: $args", args.contains("-tag:v"))
    }

    @Test
    fun `copying H264 into MP4 does not tag it hvc1`() {
        val args = cmd(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = "h264", audioCodec = "aac", container = Container.MKV),
        )
        assertFalse(args.contains("hvc1"))
    }

    // --- explicit muxer selection -------------------------------------------

    /**
     * Every container names its muxer.
     *
     * The builder used to rely on FFmpeg inferring the format from the output extension, which is
     * unreliable for MPEG-TS and ASF and became untenable once the container stopped being implied
     * by the preset.
     */
    @Test
    fun `every container passes an explicit -f`() {
        Container.entries.forEach { container ->
            val spec = when {
                container == Container.GIF || container == Container.IMAGE_SEQUENCE ->
                    OutputSpec(container, VideoCodec.NONE, AudioCodec.NONE)

                container.canHoldVideo -> OutputSpec(container, VideoCodec.COPY, AudioCodec.COPY)
                else -> OutputSpec(container, VideoCodec.NONE, AudioCodec.COPY)
            }
            assertPair(cmd(spec), "-f", container.ffmpegFormat)
        }
    }

    @Test
    fun `the new containers name the muxers FFmpeg actually uses`() {
        assertEquals("matroska", Container.MKV.ffmpegFormat)
        assertEquals("mpegts", Container.MPEG_TS.ffmpegFormat)
        assertEquals("asf", Container.ASF.ffmpegFormat)
        assertEquals("mov", Container.MOV.ffmpegFormat)
        assertEquals("adts", Container.AAC_ADTS.ffmpegFormat)
    }

    @Test
    fun `faststart applies to the MP4 family only`() {
        assertTrue(cmd(OutputFormat.MP4_H264).contains("+faststart"))
        assertTrue(
            cmd(OutputSpec(Container.MOV, VideoCodec.COPY, AudioCodec.COPY))
                .contains("+faststart"),
        )
        assertFalse(
            cmd(OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY))
                .contains("+faststart"),
        )
    }

    // --- no silent substitution ---------------------------------------------

    /**
     * Asking for a codec this app cannot encode must fail, not quietly become H.264.
     *
     * The builder used to end its codec `when` with `else -> libx264`, which is the same shape as
     * the `media3MimeType()` bug that put an HEVC video track in a file named `.m4a`: the user asks
     * for one thing, gets another, and nothing reports it. VP8 and AV1 are copyable but not
     * encodable, so they are exactly the requests that would land in that branch.
     */
    @Test
    fun `requesting an unencodable codec fails instead of substituting H264`() {
        listOf(VideoCodec.VP8, VideoCodec.AV1).forEach { codec ->
            val failure = runCatching {
                cmd(OutputSpec(Container.MKV, codec, AudioCodec.AAC))
            }.exceptionOrNull()

            assertTrue(
                "$codec should be refused, not silently encoded as something else",
                failure != null,
            )
            assertTrue(
                "the message should name the codec, got: ${failure?.message}",
                failure?.message?.contains(codec.label) == true,
            )
        }
    }

    @Test
    fun `the codecs this app does encode still produce their own encoder`() {
        assertPair(cmd(OutputSpec(Container.MP4, VideoCodec.H264, AudioCodec.AAC)), "-c:v", "libx264")
        assertPair(cmd(OutputSpec(Container.MP4, VideoCodec.H265, AudioCodec.AAC)), "-c:v", "libx265")
        assertPair(
            cmd(OutputSpec(Container.WEBM, VideoCodec.VP9, AudioCodec.OPUS)),
            "-c:v",
            "libvpx-vp9",
        )
    }
}
