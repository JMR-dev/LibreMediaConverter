package org.libremediaconverter.model

/**
 * Maps the short codec names probing produces onto the app's codec enums.
 *
 * Three vocabularies meet here: `MediaExtractor` MIME types (normalised to short names by
 * `MediaProbe.shortName`), FFprobe's `codec_name`, and this app's enums. Stream copy needs the
 * round trip — "the source says `hevc`; is that the same thing as [VideoCodec.H265], and can the
 * target container hold it?" — so the mapping has to live somewhere both [CopyPlanner] and
 * [ContainerCapabilities] can reach, and somewhere a JVM test can exercise it.
 *
 * An unrecognised name returns null. That is deliberately not "no match": a copy planner that
 * treated unknown as compatible would stream-copy a codec into a container that cannot hold it,
 * and the failure would land on the user as a file that will not play.
 */
object CodecNames {

    /**
     * The video vocabulary, as data rather than a `when`.
     *
     * This is not the only place the app spells these names. `AndroidDeviceCodecs` reads the same
     * FFprobe strings to decide what the device can decode, and answers in platform MIME types,
     * which `model` cannot name without depending on Android. The two copies drifted apart:
     * `x264`, `hev1`, `x265` and `vp09` resolved here and returned null there, so the app
     * identified the codec for display and routing and then ran the device check blind, attempting
     * a hardware path it had enough information to skip (#87).
     *
     * The reason this is a map is that **a `when` cannot be enumerated**, so nothing could compare
     * the two tables. `CodecVocabularyTest` walks both key sets, so a name added to or removed
     * from one side alone now fails the build rather than waiting for a wasted transcode to show
     * it.
     *
     * Keys are lowercase; [videoFromName] lowercases before looking one up.
     */
    internal val VIDEO_ALIASES: Map<String, VideoCodec> = mapOf(
        "h264" to VideoCodec.H264,
        "avc" to VideoCodec.H264,
        "avc1" to VideoCodec.H264,
        "x264" to VideoCodec.H264,
        "hevc" to VideoCodec.H265,
        "h265" to VideoCodec.H265,
        "hvc1" to VideoCodec.H265,
        "hev1" to VideoCodec.H265,
        "x265" to VideoCodec.H265,
        "vp8" to VideoCodec.VP8,
        "vp9" to VideoCodec.VP9,
        "vp09" to VideoCodec.VP9,
        "av1" to VideoCodec.AV1,
        "av01" to VideoCodec.AV1,
    )

    /**
     * The audio vocabulary, data for the same reason.
     *
     * Nothing cross-checks this one yet, and that is a gap rather than a decision: the device
     * capability check is video-only, so this module holds no second audio table to compare it
     * against. `Media3Engine.audioMimeTypeFor` is the other half, and #85 owns that file.
     */
    internal val AUDIO_ALIASES: Map<String, AudioCodec> = mapOf(
        "aac" to AudioCodec.AAC,
        "mp4a" to AudioCodec.AAC,
        "aac_latm" to AudioCodec.AAC,
        "opus" to AudioCodec.OPUS,
        "vorbis" to AudioCodec.VORBIS,
        "mp3" to AudioCodec.MP3,
        "mp3float" to AudioCodec.MP3,
        "mpga" to AudioCodec.MP3,
        "flac" to AudioCodec.FLAC,
        "pcm" to AudioCodec.PCM,
        "raw" to AudioCodec.PCM,
        "pcm_s16le" to AudioCodec.PCM,
        "pcm_s24le" to AudioCodec.PCM,
        "pcm_f32le" to AudioCodec.PCM,
    )

    fun videoFromName(name: String?): VideoCodec? = asCodecName(name)?.let(VIDEO_ALIASES::get)

    fun audioFromName(name: String?): AudioCodec? = asCodecName(name)?.let(AUDIO_ALIASES::get)

    /** Human-readable name for the source-info card. Falls back to the raw probe string. */
    fun describeVideo(name: String?): String = describe(name) { videoFromName(it)?.label }

    fun describeAudio(name: String?): String = describe(name) { audioFromName(it)?.label }

    /**
     * Lowercases a probe string, and answers null for the two inputs that are not codec names at
     * all: absent, and the [InputProbe.UNPARSEABLE] sentinel.
     *
     * The sentinel would miss every key anyway, so naming it changes no answer. Naming it is still
     * the point: `videoFromName` excluded it explicitly and `audioFromName` did not, which read as
     * though the two disagreed about what the sentinel means — the same asymmetry as #74 one
     * function further up.
     */
    private fun asCodecName(name: String?): String? =
        if (name == null || name == InputProbe.UNPARSEABLE) null else name.lowercase()

    /**
     * The shared body of [describeVideo] and [describeAudio].
     *
     * They are one function apiece over one vocabulary, and they had stopped matching:
     * `describeVideo` answered "Unrecognised" for [InputProbe.UNPARSEABLE] and `describeAudio` fell
     * through to `?: name` instead. The sentinel opens with a NUL, so that fallback would have put
     * a U+0000 into a `Text` on the source-info card (#74). Sharing the arms is what stops the next
     * one being added to one side only.
     */
    private fun describe(name: String?, label: (String) -> String?): String = when {
        name == null -> "Unknown"
        name == InputProbe.UNPARSEABLE -> "Unrecognised"
        else -> label(name) ?: name
    }
}
