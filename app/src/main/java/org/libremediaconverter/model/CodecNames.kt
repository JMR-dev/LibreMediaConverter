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

    fun videoFromName(name: String?): VideoCodec? = when (name?.lowercase()) {
        null, InputProbe.UNPARSEABLE -> null
        "h264", "avc", "avc1", "x264" -> VideoCodec.H264
        "hevc", "h265", "hvc1", "hev1", "x265" -> VideoCodec.H265
        "vp8" -> VideoCodec.VP8
        "vp9", "vp09" -> VideoCodec.VP9
        "av1", "av01" -> VideoCodec.AV1
        else -> null
    }

    fun audioFromName(name: String?): AudioCodec? = when (name?.lowercase()) {
        null -> null
        "aac", "mp4a", "aac_latm" -> AudioCodec.AAC
        "opus" -> AudioCodec.OPUS
        "vorbis" -> AudioCodec.VORBIS
        "mp3", "mp3float", "mpga" -> AudioCodec.MP3
        "flac" -> AudioCodec.FLAC
        "pcm", "raw", "pcm_s16le", "pcm_s24le", "pcm_f32le" -> AudioCodec.PCM
        else -> null
    }

    /** Human-readable name for the source-info card. Falls back to the raw probe string. */
    fun describeVideo(name: String?): String = when {
        name == null -> "Unknown"
        name == InputProbe.UNPARSEABLE -> "Unrecognised"
        else -> videoFromName(name)?.label ?: name
    }

    fun describeAudio(name: String?): String = when {
        name == null -> "Unknown"
        else -> audioFromName(name)?.label ?: name
    }
}
