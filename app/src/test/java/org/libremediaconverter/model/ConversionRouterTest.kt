package org.libremediaconverter.model

import org.libremediaconverter.model.ConversionRouter.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One test per routing predicate.
 *
 * These run on the JVM against fabricated device profiles rather than on hardware, so
 * every branch is reachable — including "this device cannot encode AV1", which would
 * otherwise depend on which phone the suite happened to run on.
 */
class ConversionRouterTest {

    private fun route(
        format: OutputFormat,
        quality: QualityTier = QualityTier.FAST,
        preference: EnginePreference = EnginePreference.AUTO,
        probe: InputProbe = InputProbe(videoCodec = "h264"),
        device: DeviceCodecs = DeviceCodecs.PERMISSIVE,
    ) = ConversionRouter.route(
        ConversionRequest(format.spec, quality, preference, probe),
        device,
    )

    private fun route(
        spec: OutputSpec,
        quality: QualityTier = QualityTier.FAST,
        preference: EnginePreference = EnginePreference.AUTO,
        probe: InputProbe = InputProbe(videoCodec = "h264"),
        device: DeviceCodecs = DeviceCodecs.PERMISSIVE,
    ) = ConversionRouter.route(
        ConversionRequest(spec, quality, preference, probe),
        device,
    )

    // --- the happy path -----------------------------------------------------

    @Test
    fun `mp4 h264 on a capable device uses hardware`() {
        val d = route(OutputFormat.MP4_H264)
        assertEquals(Engine.MEDIA3, d.engine)
        assertEquals(Reason.HARDWARE_CAPABLE, d.reason)
    }

    @Test
    fun `mp4 h265 uses hardware`() {
        assertEquals(Engine.MEDIA3, route(OutputFormat.MP4_H265).engine)
    }

    @Test
    fun `webm vp9 routes to ffmpeg because media3 cannot encode vp9`() {
        // Transformer.setVideoMimeType accepts only H.263/H.264/H.265/MP4V. The WebM
        // muxer exists, but there is no VP9 *encoder* behind it, so producing WebM
        // video is FFmpeg's job even though the container is nominally supported.
        val d = route(OutputFormat.WEBM_VP9)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.NO_PLATFORM_ENCODER, d.reason)
    }

    @Test
    fun `aac audio extraction stays on hardware`() {
        assertEquals(Engine.MEDIA3, route(OutputFormat.M4A_AAC).engine)
    }

    // --- 1. container ------------------------------------------------------

    @Test
    fun `mkv routes to ffmpeg because media3 has no matroska muxer`() {
        val d = route(OutputFormat.MKV_H264)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.CONTAINER_UNSUPPORTED, d.reason)
    }

    // --- 3. no platform encoder -------------------------------------------

    @Test
    fun `mp3 routes to ffmpeg because android has no mp3 encoder at any api level`() {
        val d = route(OutputFormat.MP3)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.NO_PLATFORM_ENCODER, d.reason)
    }

    @Test
    fun `flac routes to ffmpeg`() {
        assertEquals(Engine.FFMPEG, route(OutputFormat.FLAC).engine)
    }

    // --- 4. image output ---------------------------------------------------

    @Test
    fun `gif routes to ffmpeg as an image output`() {
        val d = route(OutputFormat.GIF)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.IMAGE_OUTPUT, d.reason)
    }

    @Test
    fun `png frame export routes to ffmpeg`() {
        assertEquals(Engine.FFMPEG, route(OutputFormat.FRAMES_PNG).engine)
    }

    // --- 5. no platform decoder -------------------------------------------

    @Test
    fun `av1 input on a device without av1 decode routes to ffmpeg`() {
        val noAv1 = object : DeviceCodecs {
            override fun canEncode(codec: VideoCodec) = true
            override fun canDecode(codecName: String) = codecName != "av1"
        }
        val d = route(OutputFormat.MP4_H264, probe = InputProbe(videoCodec = "av1"), device = noAv1)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.NO_PLATFORM_DECODER, d.reason)
    }

    @Test
    fun `av1 input on a device with av1 decode stays on hardware`() {
        val d = route(OutputFormat.MP4_H264, probe = InputProbe(videoCodec = "av1"))
        assertEquals(Engine.MEDIA3, d.engine)
    }

    @Test
    fun `an input the platform cannot parse routes to ffmpeg`() {
        // MediaProbe reports this when the platform extractor fails to open the file.
        // Media3 cannot convert what the platform cannot read, so the job must not be
        // sent down the hardware path only to fail there.
        val d = route(
            OutputFormat.MP4_H264,
            probe = InputProbe(videoCodec = InputProbe.UNPARSEABLE),
        )
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.NO_PLATFORM_DECODER, d.reason)
    }

    @Test
    fun `a null input codec is treated as no information, not as a failure`() {
        // Nothing known about the input is different from "known to be unreadable":
        // the former should still take the fast path.
        val d = route(OutputFormat.MP4_H264, probe = InputProbe(videoCodec = null))
        assertEquals(Engine.MEDIA3, d.engine)
    }

    // --- 6. quality tier ---------------------------------------------------

    @Test
    fun `best quality routes to ffmpeg because mediacodec exposes no crf`() {
        val d = route(OutputFormat.MP4_H264, quality = QualityTier.BEST)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.QUALITY_TIER_REQUIRES_CRF, d.reason)
    }

    @Test
    fun `fast quality is the hardware path`() {
        assertEquals(Engine.MEDIA3, route(OutputFormat.MP4_H264, quality = QualityTier.FAST).engine)
    }

    // --- 7. hardware encoder availability ---------------------------------

    @Test
    fun `h265 target on a device without hevc encode falls back to ffmpeg`() {
        val noHevc = object : DeviceCodecs {
            override fun canEncode(codec: VideoCodec) = codec != VideoCodec.H265
            override fun canDecode(codecName: String) = true
        }
        val d = route(OutputFormat.MP4_H265, device = noHevc)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.NO_HARDWARE_ENCODER, d.reason)
    }

    // --- user override -----------------------------------------------------

    @Test
    fun `forcing software overrides an otherwise hardware-capable job`() {
        val d = route(OutputFormat.MP4_H264, preference = EnginePreference.FORCE_SOFTWARE)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.USER_FORCED_SOFTWARE, d.reason)
    }

    @Test
    fun `forcing software wins even for formats ffmpeg would take anyway`() {
        val d = route(OutputFormat.MP3, preference = EnginePreference.FORCE_SOFTWARE)
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.USER_FORCED_SOFTWARE, d.reason)
    }

    // --- remux --------------------------------------------------------------

    /**
     * The test that keeps the Media3 transmux path alive.
     *
     * `COPY` belongs to none of the router's capability sets, so the obvious implementation — test
     * the request's codecs directly — sends every remux to FFmpeg on the very first check. Nothing
     * would fail: FFmpeg's `-c copy` produces a correct file, just on the CPU. Only an assertion
     * about the *engine* catches it, which is why this one exists.
     */
    @Test
    fun `a full stream copy into a Media3 container stays on hardware`() {
        val d = route(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = "h264", audioCodec = "aac", container = Container.MKV),
        )
        assertEquals(Engine.MEDIA3, d.engine)
        assertEquals(Reason.REMUX_NO_REENCODE, d.reason)
    }

    /** A copy needs no encoder at all, so a device with none must not push it to software. */
    @Test
    fun `a stream copy ignores the device encoder capabilities`() {
        val noEncoders = object : DeviceCodecs {
            override fun canEncode(codec: VideoCodec) = false
            override fun canDecode(codecName: String) = true
        }
        val d = route(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = "h264", audioCodec = "aac", container = Container.MKV),
            device = noEncoders,
        )
        assertEquals(Engine.MEDIA3, d.engine)
    }

    /** Nor does it decode, so a codec the device cannot decode is still copyable. */
    @Test
    fun `a stream copy ignores the device decoder capabilities`() {
        val noDecoders = object : DeviceCodecs {
            override fun canEncode(codec: VideoCodec) = true
            override fun canDecode(codecName: String) = false
        }
        val d = route(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = "av1", audioCodec = "aac", container = Container.MKV),
            device = noDecoders,
        )
        assertEquals(Engine.MEDIA3, d.engine)
    }

    /**
     * Copying MP3 audio into MP4 needs no encoder — but Media3's MP4 muxer cannot carry MP3.
     *
     * Two separate limits that used to be indistinguishable because nothing could reach this
     * combination. The encoder gap is real and permanent (Android has no MP3 encoder); the muxer
     * gap is what actually decides this job, and the reason has to say so rather than blaming an
     * encoder nobody asked for.
     */
    @Test
    fun `copying MP3 audio into MP4 needs FFmpeg because Media3 cannot mux it`() {
        val d = route(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = "h264", audioCodec = "mp3", container = Container.MKV),
        )
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.CONTAINER_CODEC_UNSUPPORTED, d.reason)
    }

    /** Opus, by contrast, Media3's MP4 muxer does carry. */
    @Test
    fun `copying Opus audio into MP4 stays on hardware`() {
        val d = route(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = "h264", audioCodec = "opus", container = Container.MKV),
        )
        assertEquals(Engine.MEDIA3, d.engine)
        assertEquals(Reason.REMUX_NO_REENCODE, d.reason)
    }

    /** But Best quality is meaningless for a copy, so it must not force software. */
    @Test
    fun `best quality does not force software for a pure remux`() {
        val d = route(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            quality = QualityTier.BEST,
            probe = InputProbe(videoCodec = "h264", audioCodec = "aac", container = Container.MKV),
        )
        assertEquals(Engine.MEDIA3, d.engine)
    }

    /** An unreadable file cannot be copied either — there is nothing to demux. */
    @Test
    fun `an unparseable input still goes to FFmpeg even when a copy was asked for`() {
        val d = route(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            probe = InputProbe(videoCodec = InputProbe.UNPARSEABLE),
        )
        assertEquals(Engine.FFMPEG, d.engine)
        assertEquals(Reason.NO_PLATFORM_DECODER, d.reason)
    }

    @Test
    fun `the new containers are all FFmpeg-only`() {
        listOf(
            Container.MOV, Container.MKV, Container.MPEG_TS,
            Container.AVI, Container.FLV, Container.ASF,
        ).forEach { container ->
            val d = route(
                OutputSpec(container, VideoCodec.COPY, AudioCodec.COPY),
                probe = InputProbe(
                    videoCodec = "h264",
                    audioCodec = "aac",
                    container = Container.MP4,
                ),
            )
            assertEquals("$container should need FFmpeg", Engine.FFMPEG, d.engine)
            assertEquals(Reason.CONTAINER_UNSUPPORTED, d.reason)
        }
    }

    // --- exhaustiveness ----------------------------------------------------

    @Test
    fun `every output format routes somewhere without throwing`() {
        OutputFormat.entries.forEach { format ->
            val decision = route(format)
            assertEquals(
                "format $format produced no engine",
                true,
                decision.engine == Engine.MEDIA3 || decision.engine == Engine.FFMPEG,
            )
        }
    }

    @Test
    fun `audio-only formats are flagged as such`() {
        assertEquals(true, OutputFormat.MP3.isAudioOnly)
        assertEquals(true, OutputFormat.FLAC.isAudioOnly)
        assertEquals(false, OutputFormat.MP4_H264.isAudioOnly)
        assertEquals(false, OutputFormat.GIF.isAudioOnly)
    }
}
