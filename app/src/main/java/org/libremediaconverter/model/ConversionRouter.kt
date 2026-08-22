package org.libremediaconverter.model

/**
 * Chooses the engine for a conversion.
 *
 * Media3 Transformer is preferred wherever it is capable, because it is hardware
 * accelerated end to end (MediaCodec decode -> GL surface -> MediaCodec encode) and
 * runs several times faster than software encoding at a fraction of the battery cost.
 * FFmpeg handles everything Media3 structurally cannot.
 *
 * The rules below are capability boundaries, not preferences. Each one exists because
 * Media3 would fail or silently produce the wrong thing.
 */
object ConversionRouter {

    /**
     * Containers Media3 can mux. Anything else has to go to FFmpeg.
     *
     * Not private: `Media3Muxers` has to supply a `Muxer.Factory` for every entry, and a test
     * asserts the two agree. They drifted once already — this set was correct while the engine
     * silently wrote MP4 for all five.
     */
    internal val MEDIA3_CONTAINERS = setOf(
        Container.MP4,
        Container.WEBM,
        Container.OGG,
        Container.WAV,
        Container.AAC_ADTS,
    )

    /** WebM is codec-restricted: Media3's WebmMuxer writes only these. */
    private val WEBM_AUDIO = setOf(AudioCodec.OPUS, AudioCodec.VORBIS)
    private val WEBM_VIDEO = setOf(VideoCodec.VP8, VideoCodec.VP9, VideoCodec.NONE)

    /** Video codecs Media3 can be asked to encode (`Transformer.setVideoMimeType`). */
    private val MEDIA3_VIDEO = setOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.NONE)

    /** Audio codecs Media3 can encode. Notably absent: MP3 and FLAC. */
    private val MEDIA3_AUDIO = setOf(AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.PCM, AudioCodec.NONE)

    fun route(request: ConversionRequest, device: DeviceCodecs): Decision {
        when (request.enginePreference) {
            EnginePreference.FORCE_SOFTWARE ->
                return Decision(Engine.FFMPEG, Reason.USER_FORCED_SOFTWARE)
            else -> Unit
        }

        // Order matters below: the specific reasons are checked before the general
        // ones, because the reason string is shown to the user. "Android has no
        // encoder for this format" tells them something actionable about MP3;
        // "this container needs FFmpeg" does not.

        // MP3 has no encoder anywhere on Android, at any API level. That is a platform
        // gap rather than a Media3 limitation, and libmp3lame is the only way the app
        // can produce MP3 at all.
        if (request.format.audioCodec !in MEDIA3_AUDIO) {
            return Decision(Engine.FFMPEG, Reason.NO_PLATFORM_ENCODER)
        }

        // GIF and frame sequences are image outputs; Media3 has no muxer for them.
        if (request.format.isImageOutput) {
            return Decision(Engine.FFMPEG, Reason.IMAGE_OUTPUT)
        }

        // Containers Media3 cannot mux at all, chiefly Matroska.
        if (request.format.container !in MEDIA3_CONTAINERS) {
            return Decision(Engine.FFMPEG, Reason.CONTAINER_UNSUPPORTED)
        }

        // WebM is codec-restricted even though the container itself is supported.
        if (request.format.container == Container.WEBM &&
            (request.format.audioCodec !in WEBM_AUDIO || request.format.videoCodec !in WEBM_VIDEO)
        ) {
            return Decision(Engine.FFMPEG, Reason.WEBM_CODEC_UNSUPPORTED)
        }

        // Media3 does not bundle ExoPlayer's software decoders, so an input codec with
        // no platform decoder cannot be read at all. The dav1d extension does not
        // rescue this: Transformer ignores bundled software decoder modules.
        val inputCodec = request.probe.videoCodec
        if (inputCodec != null && !device.canDecode(inputCodec)) {
            return Decision(Engine.FFMPEG, Reason.NO_PLATFORM_DECODER)
        }

        // CRF and two-pass are the whole point of the quality tier, and MediaCodec
        // exposes neither, so BEST always means software encoding.
        if (request.quality == QualityTier.BEST) {
            return Decision(Engine.FFMPEG, Reason.QUALITY_TIER_REQUIRES_CRF)
        }

        // Transformer.setVideoMimeType accepts only H.263/H.264/H.265/MP4V, so VP9 and
        // AV1 targets cannot be encoded by Media3 regardless of what the device can do.
        if (request.format.videoCodec !in MEDIA3_VIDEO) {
            return Decision(Engine.FFMPEG, Reason.NO_PLATFORM_ENCODER)
        }

        // Finally, the target codec has to be hardware-encodable on this specific
        // device. HEVC is near-universal; AV1 encode is rare.
        if (request.format.videoCodec != VideoCodec.NONE &&
            !device.canEncode(request.format.videoCodec)
        ) {
            return Decision(Engine.FFMPEG, Reason.NO_HARDWARE_ENCODER)
        }

        return Decision(Engine.MEDIA3, Reason.HARDWARE_CAPABLE)
    }

    data class Decision(val engine: Engine, val reason: Reason)

    enum class Reason(val explanation: String) {
        HARDWARE_CAPABLE("Hardware accelerated"),
        CONTAINER_UNSUPPORTED("This container needs FFmpeg"),
        WEBM_CODEC_UNSUPPORTED("WebM only supports VP8/VP9 with Opus or Vorbis"),
        NO_PLATFORM_ENCODER("Android has no encoder for this format"),
        NO_PLATFORM_DECODER("This device cannot decode the input in hardware"),
        NO_HARDWARE_ENCODER("This device has no hardware encoder for that codec"),
        IMAGE_OUTPUT("Image output needs FFmpeg"),
        QUALITY_TIER_REQUIRES_CRF("Best quality uses software encoding"),
        USER_FORCED_SOFTWARE("Software encoding was requested"),
        MEDIA3_FAILED("Hardware conversion failed; retrying in software"),
    }
}

/**
 * What this device's codecs can actually do.
 *
 * An interface so the routing rules can be unit tested against fabricated device
 * profiles rather than whatever hardware the test happens to run on.
 */
interface DeviceCodecs {
    fun canEncode(codec: VideoCodec): Boolean
    fun canDecode(codecName: String): Boolean

    companion object {
        /**
         * Assumes everything works, except an input the platform could not parse.
         *
         * That exception matters: a device double that claims it can decode an
         * unparseable file would let the router send a doomed job to Media3.
         */
        val PERMISSIVE = object : DeviceCodecs {
            override fun canEncode(codec: VideoCodec) = true
            override fun canDecode(codecName: String) = codecName != InputProbe.UNPARSEABLE
        }
    }
}
