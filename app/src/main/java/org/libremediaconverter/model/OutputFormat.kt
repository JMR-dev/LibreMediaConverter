package org.libremediaconverter.model

/**
 * Container families.
 *
 * Each entry owns the three things that used to be duplicated per preset: the FFmpeg muxer name,
 * the file extension, and the MIME type handed to the SAF `CreateDocument` contract. Keeping them
 * here is what makes an open container × codec matrix possible — a preset no longer has to exist
 * for every combination someone might want.
 *
 * Extension and MIME type depend on whether a video track survives: Matroska with video is `.mkv`
 * and without it `.mka`, MP4 is `.mp4` or `.m4a`. That distinction is why they are functions rather
 * than properties.
 *
 * @param ffmpegFormat the `-f` value. Named explicitly rather than left to extension inference,
 *   which is unreliable for MPEG-TS and ASF.
 */
enum class Container(
    val label: String,
    val ffmpegFormat: String,
    private val videoExtension: String?,
    private val audioExtension: String,
    private val videoMime: String?,
    private val audioMime: String,
) {
    MP4("MP4", "mp4", "mp4", "m4a", "video/mp4", "audio/mp4"),
    MOV("MOV", "mov", "mov", "m4a", "video/quicktime", "audio/mp4"),
    MKV("Matroska", "matroska", "mkv", "mka", "video/x-matroska", "audio/x-matroska"),
    WEBM("WebM", "webm", "webm", "weba", "video/webm", "audio/webm"),
    MPEG_TS("MPEG-TS", "mpegts", "ts", "ts", "video/mp2t", "video/mp2t"),
    AVI("AVI", "avi", "avi", "avi", "video/x-msvideo", "video/x-msvideo"),
    FLV("FLV", "flv", "flv", "flv", "video/x-flv", "video/x-flv"),
    ASF("WMV/ASF", "asf", "wmv", "wma", "video/x-ms-wmv", "audio/x-ms-wma"),

    OGG("Ogg", "ogg", null, "opus", null, "audio/ogg"),
    WAV("WAV", "wav", null, "wav", null, "audio/wav"),
    AAC_ADTS("AAC", "adts", null, "aac", null, "audio/aac"),
    MP3("MP3", "mp3", null, "mp3", null, "audio/mpeg"),
    FLAC("FLAC", "flac", null, "flac", null, "audio/flac"),

    GIF("GIF", "gif", "gif", "gif", "image/gif", "image/gif"),
    IMAGE_SEQUENCE("PNG frames", "image2", "png", "png", "image/png", "image/png"),
    ;

    /** Whether this container can hold a video track at all. */
    val canHoldVideo: Boolean get() = videoExtension != null

    fun extensionFor(hasVideo: Boolean): String = if (hasVideo) videoExtension ?: audioExtension else audioExtension

    fun mimeTypeFor(hasVideo: Boolean): String = if (hasVideo) videoMime ?: audioMime else audioMime
}

/**
 * Codecs this app can be asked to produce.
 *
 * [COPY] is a first-class value rather than a flag alongside them: "keep whatever the source has"
 * sits in exactly the same slot as "make it H.264", and modelling it as a codec means every
 * exhaustive `when` in the codebase is forced to say what it does about copying.
 */
enum class VideoCodec(val label: String) {
    COPY("Copy"),
    H264("H.264"),
    H265("H.265"),
    VP9("VP9"),
    VP8("VP8"),
    AV1("AV1"),
    NONE("None"),
}

enum class AudioCodec(val label: String) {
    COPY("Copy"),
    AAC("AAC"),
    OPUS("Opus"),
    VORBIS("Vorbis"),
    MP3("MP3"),
    FLAC("FLAC"),
    PCM("PCM"),
    NONE("None"),
}

/**
 * A concrete output: one container, one choice per track.
 *
 * This replaces the closed enum of twelve triples that used to be the only way to describe an
 * output. The closed set was defended on the grounds that it made routing decidable; decidability
 * now comes from [ContainerCapabilities] instead, which is explicit and unit-tested rather than
 * implicit in which combinations someone remembered to enumerate.
 */
data class OutputSpec(val container: Container, val videoCodec: VideoCodec, val audioCodec: AudioCodec) {
    /** Whether the output keeps a video track — the thing extension and MIME type turn on. */
    val hasVideo: Boolean get() = videoCodec != VideoCodec.NONE

    val isAudioOnly: Boolean
        get() = videoCodec == VideoCodec.NONE && audioCodec != AudioCodec.NONE

    val isImageOutput: Boolean
        get() = container == Container.GIF || container == Container.IMAGE_SEQUENCE

    /** True when neither track is re-encoded, i.e. this is a pure container change. */
    val isPureRemux: Boolean
        get() = videoCodec.isCopyOrAbsent() &&
            audioCodec.isCopyOrAbsent() &&
            (videoCodec == VideoCodec.COPY || audioCodec == AudioCodec.COPY)

    val extension: String get() = container.extensionFor(hasVideo)
    val mimeType: String get() = container.mimeTypeFor(hasVideo)

    private fun VideoCodec.isCopyOrAbsent() = this == VideoCodec.COPY || this == VideoCodec.NONE
    private fun AudioCodec.isCopyOrAbsent() = this == AudioCodec.COPY || this == AudioCodec.NONE
}

/**
 * The one-tap presets.
 *
 * Still a closed list, but it is now a convenience layer over [OutputSpec] rather than the only
 * vocabulary available. Anything not here is reachable through the Advanced picker.
 */
enum class OutputFormat(val label: String, val spec: OutputSpec) {
    MP4_H264("MP4 (H.264)", OutputSpec(Container.MP4, VideoCodec.H264, AudioCodec.AAC)),
    MP4_H265("MP4 (H.265)", OutputSpec(Container.MP4, VideoCodec.H265, AudioCodec.AAC)),
    WEBM_VP9("WebM (VP9)", OutputSpec(Container.WEBM, VideoCodec.VP9, AudioCodec.OPUS)),
    MKV_H264("MKV (H.264)", OutputSpec(Container.MKV, VideoCodec.H264, AudioCodec.AAC)),
    MKV_H265("MKV (H.265)", OutputSpec(Container.MKV, VideoCodec.H265, AudioCodec.AAC)),

    /** Container change only. The headline of the remux feature, given a preset of its own. */
    REMUX_MP4("Remux to MP4", OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY)),
    REMUX_MKV("Remux to MKV", OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY)),

    MP3("MP3", OutputSpec(Container.MP3, VideoCodec.NONE, AudioCodec.MP3)),
    M4A_AAC("M4A (AAC)", OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.AAC)),
    OPUS("Opus", OutputSpec(Container.OGG, VideoCodec.NONE, AudioCodec.OPUS)),
    FLAC("FLAC", OutputSpec(Container.FLAC, VideoCodec.NONE, AudioCodec.FLAC)),
    WAV("WAV", OutputSpec(Container.WAV, VideoCodec.NONE, AudioCodec.PCM)),

    GIF("GIF", OutputSpec(Container.GIF, VideoCodec.NONE, AudioCodec.NONE)),
    FRAMES_PNG("PNG frames", OutputSpec(Container.IMAGE_SEQUENCE, VideoCodec.NONE, AudioCodec.NONE)),
    ;

    val container: Container get() = spec.container
    val videoCodec: VideoCodec get() = spec.videoCodec
    val audioCodec: AudioCodec get() = spec.audioCodec
    val extension: String get() = spec.extension
    val mimeType: String get() = spec.mimeType
    val isAudioOnly: Boolean get() = spec.isAudioOnly
    val isImageOutput: Boolean get() = spec.isImageOutput
}

/**
 * How hard to work for quality.
 *
 * This is the user-facing form of the engine split. [BEST] is the entire reason the
 * shipped binary is GPL: CRF and two-pass rate control come from x264/x265 and are not
 * exposed by any Android hardware encoder.
 */
enum class QualityTier(val label: String, val description: String) {
    FAST("Fast", "Hardware accelerated. Best for sharing and batches."),
    BEST("Best quality", "Software encode with CRF. Slower, smaller files."),
}

/** Escape hatch, mostly for debugging and for devices with broken encoders. */
enum class EnginePreference { AUTO, PREFER_HARDWARE, FORCE_SOFTWARE }

enum class Engine { MEDIA3, FFMPEG }

/** What kind of file the input turned out to be. */
enum class InputKind {
    VIDEO,
    AUDIO_ONLY,
    IMAGE,

    /** Nothing could read it. Distinct from the others, and never evidence of a codec match. */
    UNPARSEABLE,
}

/** What we know about the input, as far as routing and stream copy are concerned. */
data class InputProbe(
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hasVideo: Boolean = true,
    val durationMs: Long = 0,
    val kind: InputKind = InputKind.VIDEO,
    /**
     * The source container, when it could be identified.
     *
     * `MediaExtractor` cannot report this at all, so it comes from FFprobe. It matters because
     * [CopyPlanner] only upgrades a matching codec to a stream copy when the container is actually
     * changing — see its `plan` KDoc.
     */
    val container: Container? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    companion object {
        /**
         * Codec name used when the platform could not parse the input at all.
         *
         * Distinct from `null` (nothing known, assume the platform copes) and from a
         * real codec name. [DeviceCodecs] treats it as undecodable, which routes the
         * job to FFmpeg — the correct answer when the platform extractor has already
         * failed to open the file.
         */
        const val UNPARSEABLE = "\u0000unparseable"
    }
}

data class ConversionRequest(
    val spec: OutputSpec,
    val quality: QualityTier = QualityTier.FAST,
    val enginePreference: EnginePreference = EnginePreference.AUTO,
    val probe: InputProbe = InputProbe(),
    /**
     * Whether this device has a real hardware encoder for the target codec.
     *
     * When it does not, FFmpeg's `*_mediacodec` wrappers still appear to work: they
     * bind to the platform's *software* codec (`c2.android.*`) and encode at a crawl
     * while presenting as the fast path. Knowing this lets the Fast tier choose a
     * genuinely fast software preset instead of a mislabelled slow one.
     */
    val hardwareEncodeAvailable: Boolean = true,
) {
    val container: Container get() = spec.container
    val videoCodec: VideoCodec get() = spec.videoCodec
    val audioCodec: AudioCodec get() = spec.audioCodec
}
