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
 *
 * ## Every rule asks the plan, not the request
 *
 * [CopyPlanner] resolves [VideoCodec.COPY] into a concrete per-track decision *before* any rule
 * runs, and each rule below consults that plan rather than the user's raw choice. The ordering is
 * load-bearing: `COPY` belongs to none of the capability sets, so a rule that tested the request
 * directly would send every remux to FFmpeg on the first check and Media3's transmux path would
 * never run. Worse, nothing would notice — FFmpeg's `-c copy` produces a perfectly correct file,
 * just more slowly and on the CPU.
 */
object ConversionRouter {

    /**
     * Containers Media3 can mux. Anything else has to go to FFmpeg.
     *
     * MP4 alone. This set used to name WebM, Ogg, WAV and AAC-ADTS as well, on the strength of
     * `media3-muxer` shipping a muxer for each — but none of those four can be driven by
     * Transformer at all, for the reasons `Media3Muxers` records. Nothing caught it because the
     * engine ignored the container entirely and wrote MP4 regardless, so the set being wrong and
     * the engine being wrong cancelled out.
     *
     * Not private: `Media3Muxers` has to supply a `Muxer.Factory` for every entry, and a test
     * asserts the two agree.
     */
    internal val MEDIA3_CONTAINERS = setOf(Container.MP4)

    /**
     * What Media3's muxers can *carry*, as distinct from what Media3 can encode.
     *
     * Only stream copy makes the difference visible, and it is not a small one: Media3's MP4 muxer
     * accepts AAC, Opus, Vorbis and PCM but neither MP3 nor FLAC, so remuxing an MP3 track into MP4
     * — legal, and something FFmpeg does without complaint — has to leave the hardware path. Before
     * remuxing existed nothing could reach that combination, so nothing had to know.
     *
     * One entry, because MP4 is the only container Transformer can write — see `Media3Muxers` for
     * why the four other muxers in `media3-muxer` cannot be driven by it. Every other container
     * has already been sent to FFmpeg by the time these are consulted.
     *
     * Transcribed from `Muxer.Factory.getSupportedSampleMimeTypes` in `Media3Muxers`;
     * `Media3MuxersTest` asserts the transcription still matches.
     */
    internal val MEDIA3_MUXABLE_VIDEO: Map<Container, Set<VideoCodec>> = mapOf(
        Container.MP4 to setOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.VP9, VideoCodec.AV1),
    )

    internal val MEDIA3_MUXABLE_AUDIO: Map<Container, Set<AudioCodec>> = mapOf(
        Container.MP4 to setOf(
            AudioCodec.AAC,
            AudioCodec.OPUS,
            AudioCodec.VORBIS,
            AudioCodec.PCM,
        ),
    )

    /** Video codecs Media3 can encode (`Transformer.setVideoMimeType`). */
    private val MEDIA3_VIDEO = setOf(VideoCodec.H264, VideoCodec.H265)

    /** Audio codecs Media3 can encode. Notably absent: MP3 and FLAC. */
    private val MEDIA3_AUDIO = setOf(AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.PCM)

    fun route(request: ConversionRequest, device: DeviceCodecs): Decision {
        when (request.enginePreference) {
            EnginePreference.FORCE_SOFTWARE ->
                return Decision(Engine.FFMPEG, Reason.USER_FORCED_SOFTWARE)
            else -> Unit
        }

        val plan = CopyPlanner.plan(request.spec, request.probe)
        val videoEncode = (plan.video as? VideoPlan.Encode)?.codec
        val audioEncode = (plan.audio as? AudioPlan.Encode)?.codec

        // Order matters below: the specific reasons are checked before the general
        // ones, because the reason string is shown to the user. "Android has no
        // encoder for this format" tells them something actionable about MP3;
        // "this container needs FFmpeg" does not.

        // MP3 has no encoder anywhere on Android, at any API level. That is a platform
        // gap rather than a Media3 limitation, and libmp3lame is the only way the app
        // can produce MP3 at all. Asked only of a track being encoded: copying an MP3
        // stream into a different container needs no encoder and stays on hardware.
        if (audioEncode != null && audioEncode !in MEDIA3_AUDIO) {
            return Decision(Engine.FFMPEG, Reason.NO_PLATFORM_ENCODER)
        }

        // GIF and frame sequences are image outputs; Media3 has no muxer for them.
        if (request.spec.isImageOutput) {
            return Decision(Engine.FFMPEG, Reason.IMAGE_OUTPUT)
        }

        // Containers Media3 cannot mux at all, chiefly Matroska. This is about the *output*:
        // Media3 reads Matroska perfectly well, which is what makes MKV -> MP4 a hardware remux.
        if (plan.container !in MEDIA3_CONTAINERS) {
            return Decision(Engine.FFMPEG, Reason.CONTAINER_UNSUPPORTED)
        }

        // The container is supported but its muxer is codec-restricted. What matters is what ends
        // up in the file, so a copied track is judged by its source codec rather than the request.
        // Only MP4 reaches here now — WebM is rejected by the container check above — so the
        // WebM-specific wording no longer applies.
        if (!media3CanMux(plan, request.probe)) {
            return Decision(Engine.FFMPEG, Reason.CONTAINER_CODEC_UNSUPPORTED)
        }

        // A file the platform extractor could not open cannot be read at all, copied or not.
        if (request.probe.videoCodec == InputProbe.UNPARSEABLE) {
            return Decision(Engine.FFMPEG, Reason.NO_PLATFORM_DECODER)
        }

        // Media3 does not bundle ExoPlayer's software decoders, so an input codec with
        // no platform decoder cannot be read at all. The dav1d extension does not
        // rescue this: Transformer ignores bundled software decoder modules.
        //
        // Only relevant for a track being re-encoded — a stream copy never decodes, which is
        // precisely why remuxing an exotic codec into a new container still works on hardware.
        val inputCodec = request.probe.videoCodec
        if (videoEncode != null && inputCodec != null && !device.canDecode(inputCodec)) {
            return Decision(Engine.FFMPEG, Reason.NO_PLATFORM_DECODER)
        }

        // Nothing is re-encoded, so none of the encoder rules below apply and neither does the
        // quality tier: there is no quality decision to make when no samples are recompressed.
        // This is the fast path the remux feature exists for.
        if (plan.isPureRemux) {
            return Decision(Engine.MEDIA3, Reason.REMUX_NO_REENCODE)
        }

        // CRF and two-pass are the whole point of the quality tier, and MediaCodec
        // exposes neither, so BEST always means software encoding.
        if (request.quality == QualityTier.BEST) {
            return Decision(Engine.FFMPEG, Reason.QUALITY_TIER_REQUIRES_CRF)
        }

        // Transformer.setVideoMimeType accepts only H.263/H.264/H.265/MP4V, so VP9 and
        // AV1 targets cannot be encoded by Media3 regardless of what the device can do.
        if (videoEncode != null && videoEncode !in MEDIA3_VIDEO) {
            return Decision(Engine.FFMPEG, Reason.NO_PLATFORM_ENCODER)
        }

        // Finally, the target codec has to be hardware-encodable on this specific
        // device. HEVC is near-universal; AV1 encode is rare.
        if (videoEncode != null && !device.canEncode(videoEncode)) {
            return Decision(Engine.FFMPEG, Reason.NO_HARDWARE_ENCODER)
        }

        return Decision(Engine.MEDIA3, Reason.HARDWARE_CAPABLE)
    }

    /** Whether Media3's muxer for this container can carry what the plan will produce. */
    private fun media3CanMux(plan: ConversionPlan, probe: InputProbe): Boolean {
        val video = when (val v = plan.video) {
            is VideoPlan.Encode -> v.codec
            VideoPlan.Copy -> CodecNames.videoFromName(probe.videoCodec)
            VideoPlan.Drop -> null
        }
        val audio = when (val a = plan.audio) {
            is AudioPlan.Encode -> a.codec
            AudioPlan.Copy -> CodecNames.audioFromName(probe.audioCodec)
            AudioPlan.Drop -> null
        }

        // A copied track whose codec we could not name is unproven, not permitted. CopyPlanner
        // should already have refused it, so this is the second line of defence.
        if (plan.video == VideoPlan.Copy && video == null) return false
        if (plan.audio == AudioPlan.Copy && audio == null) return false

        val muxableVideo = MEDIA3_MUXABLE_VIDEO[plan.container].orEmpty()
        val muxableAudio = MEDIA3_MUXABLE_AUDIO[plan.container].orEmpty()
        if (video != null && video !in muxableVideo) return false
        if (audio != null && audio !in muxableAudio) return false
        return true
    }

    data class Decision(val engine: Engine, val reason: Reason)

    enum class Reason(val explanation: String) {
        HARDWARE_CAPABLE("Hardware accelerated"),
        REMUX_NO_REENCODE("Remuxed — streams copied, nothing re-encoded"),
        CONTAINER_UNSUPPORTED("This container needs FFmpeg"),
        CONTAINER_CODEC_UNSUPPORTED("That codec needs FFmpeg for this container"),
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
