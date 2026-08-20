package dev.jasonmross.mediaconverter.model

/** Container families, used by the router to decide which engine can mux the result. */
enum class Container { MP4, WEBM, MKV, OGG, WAV, AAC_ADTS, MP3, GIF, IMAGE_SEQUENCE }

/** Codecs this app can be asked to produce. */
enum class VideoCodec { H264, H265, VP9, VP8, AV1, NONE }
enum class AudioCodec { AAC, OPUS, VORBIS, MP3, FLAC, PCM, NONE }

/**
 * A user-selectable output format.
 *
 * Deliberately a closed set rather than a free-form codec/container matrix: most
 * combinations are either invalid or pointless, and the closed set is what makes the
 * routing rules decidable.
 */
enum class OutputFormat(
    val label: String,
    val container: Container,
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec,
    val extension: String,
    val mimeType: String,
) {
    MP4_H264("MP4 (H.264)", Container.MP4, VideoCodec.H264, AudioCodec.AAC, "mp4", "video/mp4"),
    MP4_H265("MP4 (H.265)", Container.MP4, VideoCodec.H265, AudioCodec.AAC, "mp4", "video/mp4"),
    WEBM_VP9("WebM (VP9)", Container.WEBM, VideoCodec.VP9, AudioCodec.OPUS, "webm", "video/webm"),
    MKV_H264("MKV (H.264)", Container.MKV, VideoCodec.H264, AudioCodec.AAC, "mkv", "video/x-matroska"),
    MKV_H265("MKV (H.265)", Container.MKV, VideoCodec.H265, AudioCodec.AAC, "mkv", "video/x-matroska"),

    MP3("MP3", Container.MP3, VideoCodec.NONE, AudioCodec.MP3, "mp3", "audio/mpeg"),
    M4A_AAC("M4A (AAC)", Container.MP4, VideoCodec.NONE, AudioCodec.AAC, "m4a", "audio/mp4"),
    OPUS("Opus", Container.OGG, VideoCodec.NONE, AudioCodec.OPUS, "opus", "audio/opus"),
    FLAC("FLAC", Container.MKV, VideoCodec.NONE, AudioCodec.FLAC, "flac", "audio/flac"),
    WAV("WAV", Container.WAV, VideoCodec.NONE, AudioCodec.PCM, "wav", "audio/wav"),

    GIF("GIF", Container.GIF, VideoCodec.NONE, AudioCodec.NONE, "gif", "image/gif"),
    FRAMES_PNG("PNG frames", Container.IMAGE_SEQUENCE, VideoCodec.NONE, AudioCodec.NONE, "png", "image/png");

    val isAudioOnly: Boolean get() = videoCodec == VideoCodec.NONE && audioCodec != AudioCodec.NONE
    val isImageOutput: Boolean
        get() = container == Container.GIF || container == Container.IMAGE_SEQUENCE
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

/** What we know about the input, as far as routing is concerned. */
data class InputProbe(
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val hasVideo: Boolean = true,
    val durationMs: Long = 0,
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
    val format: OutputFormat,
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
)
