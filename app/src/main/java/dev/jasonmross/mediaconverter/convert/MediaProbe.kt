package dev.jasonmross.mediaconverter.convert

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import dev.jasonmross.mediaconverter.model.ConcatInput
import dev.jasonmross.mediaconverter.model.InputProbe

/**
 * Reads just enough about an input to route it.
 *
 * Uses the platform extractor rather than FFprobe: it needs no native library, works
 * directly on a content:// URI, and the router only needs the codec and duration. If
 * the platform cannot parse the file at all, that is itself the answer — a file
 * MediaExtractor cannot open is one Media3 cannot convert, so it routes to FFmpeg.
 */
object MediaProbe {

    fun probe(context: Context, uri: Uri): InputProbe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var video: String? = null
            var audio: String? = null
            var durationUs = 0L

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
                when {
                    mime.startsWith("video/") && video == null -> video = shortName(mime)
                    mime.startsWith("audio/") && audio == null -> audio = shortName(mime)
                }
            }

            InputProbe(
                videoCodec = video,
                audioCodec = audio,
                hasVideo = video != null,
                durationMs = durationUs / 1000,
            )
        } catch (e: Exception) {
            // Not a failure: an unparseable input is a strong signal that this job
            // belongs on FFmpeg. Reporting an unknown codec makes the router say so.
            Log.i(TAG, "Platform extractor could not read $uri; routing to FFmpeg.", e)
            InputProbe(videoCodec = InputProbe.UNPARSEABLE, hasVideo = true, durationMs = 0)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Reads the properties that decide whether inputs can be joined without
     * re-encoding. Unknown values stay null, which [ConcatPlanner] treats as "cannot
     * prove a match" rather than as agreement.
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
        else -> mime.substringAfter('/')
    }

    private const val TAG = "MediaProbe"
}
