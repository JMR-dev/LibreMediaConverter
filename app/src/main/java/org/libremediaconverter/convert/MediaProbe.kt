package org.libremediaconverter.convert

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import org.libremediaconverter.model.ConcatInput
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe

/**
 * Reads what the router, the copy planner and the source-info card each need to know.
 *
 * ## Two probes, deliberately
 *
 * The platform extractor answers the routing question directly: a file `MediaExtractor` cannot open
 * is a file Media3 cannot convert, so a failure there is itself the answer. It needs no native
 * library and works straight off a `content://` URI.
 *
 * What it cannot do is name the *container*. There is no `MediaExtractor` API for it at all — and
 * [org.libremediaconverter.model.CopyPlanner] needs the source container to tell a remux (change the
 * container, keep the streams) from a re-encode of something already in the target container. It
 * also refuses to open images, which the picker has to describe rather than reject.
 *
 * So FFprobe runs too, and the two are merged. FFprobe is not a fallback here: on the common path,
 * where the extractor succeeds, it is still the only source of the container name. That costs a
 * native process spawn per file pick, which is why callers run this off the main thread.
 */
object MediaProbe {

    fun probe(context: Context, uri: Uri): InputProbe {
        val extracted = probeWithExtractor(context, uri)
        val info = probeWithFFprobe(context, uri)

        val videoCodec = extracted?.videoCodec ?: info?.videoCodec
        val audioCodec = extracted?.audioCodec ?: info?.audioCodec
        val kind = classify(extracted, info)

        if (kind == InputKind.UNPARSEABLE) {
            // Not a failure: an unparseable input is a strong signal that this job belongs on
            // FFmpeg. Reporting an unknown codec makes the router say so.
            Log.i(TAG, "Neither MediaExtractor nor FFprobe could read $uri; routing to FFmpeg.")
            return InputProbe(
                videoCodec = InputProbe.UNPARSEABLE,
                hasVideo = true,
                durationMs = 0,
                kind = InputKind.UNPARSEABLE,
            )
        }

        return InputProbe(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            hasVideo = videoCodec != null,
            durationMs = maxOf(extracted?.durationMs ?: 0L, info?.durationMs ?: 0L),
            kind = kind,
            container = info?.container,
            width = extracted?.width ?: info?.width ?: 0,
            height = extracted?.height ?: info?.height ?: 0,
        )
    }

    /**
     * Distinguishes "no video track" from "could not parse".
     *
     * The old code collapsed both into `hasVideo = true, videoCodec = UNPARSEABLE`, which made an
     * audio file and a corrupt file indistinguishable. The source-info card cannot describe either
     * honestly until they are separate, and neither can the copy planner.
     */
    private fun classify(extracted: Extracted?, info: FFprobeInfo?): InputKind = when {
        info?.isImage == true -> InputKind.IMAGE
        extracted == null && info == null -> InputKind.UNPARSEABLE
        (extracted?.videoCodec ?: info?.videoCodec) != null -> InputKind.VIDEO
        (extracted?.audioCodec ?: info?.audioCodec) != null -> InputKind.AUDIO_ONLY
        // Parsed, but with no stream either probe recognised. Nothing to convert.
        else -> InputKind.UNPARSEABLE
    }

    private class Extracted(
        val videoCodec: String?,
        val audioCodec: String?,
        val durationMs: Long,
        val width: Int,
        val height: Int,
    )

    private fun probeWithExtractor(context: Context, uri: Uri): Extracted? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var video: String? = null
            var audio: String? = null
            var durationUs = 0L
            var width = 0
            var height = 0

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
                when {
                    mime.startsWith("video/") && video == null -> {
                        video = shortName(mime)
                        width = format.intOr(MediaFormat.KEY_WIDTH)
                        height = format.intOr(MediaFormat.KEY_HEIGHT)
                    }

                    mime.startsWith("audio/") && audio == null -> audio = shortName(mime)
                }
            }
            Extracted(video, audio, durationUs / US_PER_MS, width, height)
        } catch (e: Exception) {
            Log.i(TAG, "Platform extractor could not read $uri.", e)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private class FFprobeInfo(
        val container: Container?,
        val videoCodec: String?,
        val audioCodec: String?,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val isImage: Boolean,
    )

    private fun probeWithFFprobe(context: Context, uri: Uri): FFprobeInfo? = try {
        // The same SAF bridge ConversionWorker uses for the FFmpeg read side.
        val path = if (uri.scheme == "content") {
            FFmpegKitConfig.getSafParameterForRead(context, uri)
        } else {
            uri.path
        }
        path?.let { readMediaInformation(it) }
    } catch (e: Exception) {
        Log.i(TAG, "FFprobe could not read $uri.", e)
        null
    }

    private fun readMediaInformation(path: String): FFprobeInfo? {
        // ffmpeg-kit-next is compiled from Kotlin with private backing fields, so these have to go
        // through the Java getters rather than property syntax.
        val info: MediaInformation = FFprobeKit.getMediaInformation(path).getMediaInformation()
            ?: return null

        val streams = info.getStreams().orEmpty()
        val video = streams.firstOrNull { it.getType() == "video" }
        val audio = streams.firstOrNull { it.getType() == "audio" }
        val formatName = info.getFormat().orEmpty()

        return FFprobeInfo(
            container = containerFrom(formatName, video?.getCodec()),
            videoCodec = video?.getCodec(),
            audioCodec = audio?.getCodec(),
            durationMs = info.getDuration()?.toDoubleOrNull()?.times(MS_PER_SECOND)?.toLong() ?: 0L,
            width = video?.getWidth()?.toInt() ?: 0,
            height = video?.getHeight()?.toInt() ?: 0,
            isImage = isImageFormat(formatName),
        )
    }

    /**
     * Maps FFprobe's `format_name` onto a [Container].
     *
     * FFprobe reports a comma-separated list of every format that shares the demuxer, so a plain
     * MP4 comes back as `mov,mp4,m4a,3gp,3g2,mj2` — the first entry is not authoritative and the
     * whole string never equals one container name. Matching against the set is the only correct
     * reading, and getting this wrong silently disables the remux fast path rather than failing.
     *
     * ## Matroska and WebM are genuinely indistinguishable here
     *
     * WebM *is* a Matroska profile and they share a demuxer, so FFprobe reports `matroska,webm` for
     * both — a `.mkv` of H.264 and a `.webm` of VP9 give byte-identical format names, which the
     * committed fixtures confirm. The only signal left is the codec: WebM permits VP8/VP9/AV1 and
     * nothing else, so anything outside that set is certainly Matroska. A VP9 file could still be
     * either, and is reported as WebM, which is right far more often than not.
     *
     * That residual ambiguity is safe for the copy planner: each container accepts the codecs the
     * other holds, so a wrong guess still reaches a valid decision and only the label on the
     * source-info card suffers.
     *
     * Returns null for anything unrecognised, which the copy planner treats as "cannot prove the
     * container is changing" and therefore declines to upgrade to a stream copy.
     */
    internal fun containerFrom(formatName: String, videoCodec: String? = null): Container? {
        val names = formatName.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return null

        // Order matters: the MP4 demuxer also claims mov, so check the more specific membership
        // first or every MP4 is reported as QuickTime.
        return when {
            "matroska" in names || "webm" in names -> matroskaOrWebm(videoCodec)
            "mp4" in names -> Container.MP4
            "mov" in names || "qt" in names -> Container.MOV
            "mpegts" in names || "mpegtsraw" in names -> Container.MPEG_TS
            "avi" in names -> Container.AVI
            "flv" in names -> Container.FLV
            "asf" in names || "asf_o" in names -> Container.ASF
            "ogg" in names -> Container.OGG
            "wav" in names -> Container.WAV
            "aac" in names || "adts" in names -> Container.AAC_ADTS
            "mp3" in names -> Container.MP3
            "flac" in names -> Container.FLAC
            "gif" in names -> Container.GIF
            else -> null
        }
    }

    /** WebM's codec whitelist is the only thing separating it from Matroska. See [containerFrom]. */
    private fun matroskaOrWebm(videoCodec: String?): Container = when (videoCodec?.lowercase()) {
        "vp8", "vp9", "vp09", "av1", "av01" -> Container.WEBM
        else -> Container.MKV
    }

    /** FFprobe describes still images through the image demuxers rather than a media container. */
    private fun isImageFormat(formatName: String): Boolean {
        val names = formatName.split(',').map { it.trim().lowercase() }
        return names.any { it == "image2" || it.endsWith("_pipe") }
    }

    /**
     * Reads the properties that decide whether inputs can be joined without
     * re-encoding. Unknown values stay null, which [org.libremediaconverter.model.ConcatPlanner]
     * treats as "cannot prove a match" rather than as agreement.
     */
    fun probeForConcat(context: Context, uri: Uri): ConcatInput {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var video: String? = null
            var audio: String? = null
            var width = 0
            var height = 0
            var fps = 0

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video == null) {
                    video = shortName(mime)
                    width = format.intOr(MediaFormat.KEY_WIDTH)
                    height = format.intOr(MediaFormat.KEY_HEIGHT)
                    fps = format.intOr(MediaFormat.KEY_FRAME_RATE)
                } else if (mime.startsWith("audio/") && audio == null) {
                    audio = shortName(mime)
                }
            }
            ConcatInput(video, audio, width, height, fps)
        } catch (e: Exception) {
            Log.i(TAG, "Could not probe $uri for concat; will re-encode.", e)
            ConcatInput(null, null, 0, 0, 0)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.intOr(key: String, fallback: Int = 0): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    /** MediaFormat MIME -> the short codec names the router and FFmpeg both speak. */
    private fun shortName(mime: String): String = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> "h264"
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc"
        MediaFormat.MIMETYPE_VIDEO_VP8 -> "vp8"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "vp9"
        MediaFormat.MIMETYPE_VIDEO_AV1 -> "av1"
        MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "mpeg4"
        MediaFormat.MIMETYPE_AUDIO_AAC -> "aac"
        MediaFormat.MIMETYPE_AUDIO_OPUS -> "opus"
        MediaFormat.MIMETYPE_AUDIO_FLAC -> "flac"
        MediaFormat.MIMETYPE_AUDIO_VORBIS -> "vorbis"
        MediaFormat.MIMETYPE_AUDIO_RAW -> "pcm"
        else -> mime.substringAfter('/')
    }

    private const val TAG = "MediaProbe"

    /** MediaExtractor reports KEY_DURATION in microseconds; InputProbe carries milliseconds. */
    private const val US_PER_MS = 1000

    /** MediaMetadataRetriever's ffprobe-style duration is in seconds, as a decimal string. */
    private const val MS_PER_SECOND = 1000
}
