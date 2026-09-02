package org.libremediaconverter.convert

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.MediaInformation
import org.libremediaconverter.ffmpeg.isNativeLoadFailure
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

    /**
     * What [probe] reports when nothing could read the input.
     *
     * Named rather than inlined because a caller that has to handle [probe] itself failing
     * needs to land on the same answer — see `ConversionViewModel.onInputPicked`. Two
     * different spellings of "unreadable" would be two different behaviours downstream, since
     * the router keys off [InputProbe.UNPARSEABLE] and the source-info card off the kind.
     */
    val UNREADABLE = InputProbe(
        videoCodec = InputProbe.UNPARSEABLE,
        hasVideo = true,
        durationMs = 0,
        kind = InputKind.UNPARSEABLE,
    )

    fun probe(context: Context, uri: Uri): InputProbe {
        val merged = merge(probeWithExtractor(context, uri), probeWithFFprobe(context, uri))
        if (merged.kind == InputKind.UNPARSEABLE) {
            // Not a failure: an unparseable input is a strong signal that this job belongs on
            // FFmpeg. Reporting an unknown codec makes the router say so.
            Log.i(TAG, "Neither MediaExtractor nor FFprobe could read $uri; routing to FFmpeg.")
        }
        return merged
    }

    /**
     * What the two probes together say about one input.
     *
     * A pure function, and `internal` for the same reason [extractedFrom] is: the precedence rules
     * below are the answer to "which probe wins", and until this was pulled out of [probe] the only
     * way to ask was to have a real `MediaExtractor` and a real FFprobe **disagree**, which nothing
     * on any source set can arrange. `RemuxTest` drives this on a device against committed
     * fixtures, but only ever with one probe answering and the other agreeing or also failing --
     * so every elvis here was taken in one direction and never the other.
     *
     * The rules, each of which is a decision rather than an accident:
     *
     * - **The extractor wins on codecs.** It is the platform's own view of what it can decode,
     *   which is the thing the router is about to ask about. FFprobe's name for the same track can
     *   differ, and the copy planner keys off these strings.
     * - **FFprobe alone reports the container.** `MediaExtractor` cannot, which is why [InputProbe]
     *   carries a nullable one and `CopyPlanner` treats null as "container unknown".
     * - **Duration is the larger of the two**, not the first non-zero. Either probe can report zero
     *   for a file the other times correctly, and a zero duration makes the FFmpeg progress
     *   percentage undefined.
     */
    internal fun merge(extracted: Extracted?, info: FFprobeInfo?): InputProbe {
        val videoCodec = extracted?.videoCodec ?: info?.videoCodec
        val audioCodec = extracted?.audioCodec ?: info?.audioCodec
        val kind = classify(extracted, info)

        if (kind == InputKind.UNPARSEABLE) return UNREADABLE

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
    internal fun classify(extracted: Extracted?, info: FFprobeInfo?): InputKind = when {
        info?.isImage == true -> InputKind.IMAGE
        extracted == null && info == null -> InputKind.UNPARSEABLE
        (extracted?.videoCodec ?: info?.videoCodec) != null -> InputKind.VIDEO
        (extracted?.audioCodec ?: info?.audioCodec) != null -> InputKind.AUDIO_ONLY
        // Parsed, but with no stream either probe recognised. Nothing to convert.
        else -> InputKind.UNPARSEABLE
    }

    /**
     * `internal` rather than `private` so [extractedFrom] can be named from a test. The JVM test
     * source set is a friend of `main`, so this stays invisible outside the module — the precedent
     * is `MainActivity`'s `Destination`, and [containerFrom] beside it.
     */
    internal class Extracted(
        val videoCodec: String?,
        val audioCodec: String?,
        val durationMs: Long,
        val width: Int,
        val height: Int,
    )

    /**
     * What a set of track formats says about a file.
     *
     * Split out of [probeWithExtractor] so the rules below can be tested against tracks a test
     * *chooses*, rather than against whatever the committed fixtures happen to contain. The device
     * tests exercise this through real files; none of them can construct a two-video-track input,
     * a track that omits its duration, or an audio-before-video ordering on purpose.
     *
     * Three rules live here, and each is a decision rather than plumbing:
     *
     * - **First track of a type wins.** `video == null` is the whole guard. A file with two video
     *   tracks must report the first, because that is the one an engine will transcode.
     * - **Duration is the maximum across tracks**, not the first one found or the last. A file
     *   whose audio outlasts its video is ordinary, and reporting the video's length would cut the
     *   progress bar short.
     * - **A track that omits `KEY_DURATION` contributes nothing** rather than zero. `MediaExtractor`
     *   omits it for plenty of real tracks — see `MediaProbeTrackFieldsTest` — and `maxOf` against a
     *   fabricated 0 would still be correct here, but reading a key that is absent is not.
     */
    internal fun extractedFrom(formats: List<MediaFormat>): Extracted {
        var video: String? = null
        var audio: String? = null
        var durationUs = 0L
        var width = 0
        var height = 0

        for (format in formats) {
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
        return Extracted(video, audio, durationUs / US_PER_MS, width, height)
    }

    private fun probeWithExtractor(context: Context, uri: Uri): Extracted? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            extractedFrom(extractor.trackFormats())
        } catch (e: Exception) {
            Log.i(TAG, "Platform extractor could not read $uri.", e)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * `internal` rather than `private` for the same reason [Extracted] is, and it should have been
     * from the start: [merge] cannot be named from a test while half its signature is private.
     */
    internal class FFprobeInfo(
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
    } catch (e: Error) {
        // Touching FFmpegKit at all loads its native library, and a failure there arrives as
        // an Error, which the clause above cannot see -- so an unloadable library used to
        // take the whole file pick down instead of reporting an unreadable file. Anything
        // that is not that library failing to load is still this JVM's problem, not this
        // file's, and is rethrown: see isNativeLoadFailure.
        if (!isNativeLoadFailure(e)) throw e
        Log.w(TAG, "FFmpegKit's native library could not be loaded; probing $uri without FFprobe.", e)
        null
    }

    /**
     * The thin edge: spawn FFprobe, hand what it said to [ffprobeInfoFrom].
     *
     * Everything device-bound is on this line and the null check under it. What FFprobe *said* is a
     * `MediaInformation`, which is an ordinary object over a `JSONObject` — so the reading of it is
     * a decision a test can choose the inputs for, and it lives below rather than here.
     */
    private fun readMediaInformation(path: String): FFprobeInfo? =
        FFprobeKit.getMediaInformation(path).getMediaInformation()?.let(::ffprobeInfoFrom)

    /**
     * What FFprobe's answer means, as a function of the answer alone.
     *
     * `internal` for the same reason [Extracted] and [FFprobeInfo] are: a test cannot name it
     * otherwise, and the JVM test source set is a friend of `main`.
     *
     * **JVM-safe, verified rather than assumed.** `javap` over the committed AAR's runtime jar:
     * `MediaInformation(JSONObject, List<StreamInformation>, List<Chapter>)` and
     * `StreamInformation(JSONObject)` are plain public constructors, and neither class's `<clinit>`
     * touches the native library — so a test builds its own without `libffmpegkit` being present.
     * That is the whole reason this split is worth making: `readMediaInformation` was 114 missed
     * instructions and 24 missed branches, of which exactly one line needed a device.
     *
     * The subtle part is the **second argument to [containerFrom]**. `matroska,webm` is reported
     * for both MKV and WebM — they share a demuxer — so the video codec is the only thing that
     * separates them, and dropping it silently turns every VP9 WebM into an MKV. `containerFrom`
     * has thirty-three covered branches of its own and none of them can notice that, because the
     * mistake is at the call rather than in the callee.
     *
     * ffmpeg-kit-next is compiled from Kotlin with private backing fields, so these go through the
     * Java getters rather than property syntax.
     */
    internal fun ffprobeInfoFrom(info: MediaInformation): FFprobeInfo {
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

    /**
     * FFprobe describes still images through the image demuxers rather than a media container.
     *
     * The two halves of the rule are not interchangeable. `image2` is a whole name — what FFprobe
     * reports for a numbered image sequence — while `_pipe` has to be a *suffix* test, because the
     * piped demuxers are named one per image codec: `png_pipe`, `jpeg_pipe`, `webp_pipe`, and
     * thirty more. Relaxing that suffix to a substring would swallow `yuv4mpegpipe`, which is raw
     * video, and `classify` checks this before anything else — so a false positive makes the
     * source-info card describe a video as an image.
     *
     * `internal` so the unit tests can name both halves; the JVM test source set is a friend of
     * `main`, so this stays invisible outside the module.
     */
    internal fun isImageFormat(formatName: String): Boolean {
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
            concatInputFrom(extractor.trackFormats())
        } catch (e: Exception) {
            Log.i(TAG, "Could not probe $uri for concat; will re-encode.", e)
            ConcatInput(null, null, 0, 0, 0)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * The join flow's read of the same track formats. See [extractedFrom] for why this is separate
     * from the extractor.
     *
     * Deliberately **not** folded into [extractedFrom] despite the overlap. This one reads frame
     * rate and does not read duration; that one reads duration and does not read frame rate. A
     * merged version would have to compute both for every caller, and `ConcatPlanner` treats an
     * unknown frame rate as "cannot prove a match" — so a field this flow does not need must not
     * start arriving as a number.
     */
    internal fun concatInputFrom(formats: List<MediaFormat>): ConcatInput {
        var video: String? = null
        var audio: String? = null
        var width = 0
        var height = 0
        var fps = 0

        for (format in formats) {
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
        return ConcatInput(video, audio, width, height, fps)
    }

    /**
     * Every track format this extractor holds, read once.
     *
     * The thin edge the two pure functions above leave behind: a `trackCount` and a
     * `getTrackFormat` per index, which is the whole of what needs a real `MediaExtractor`.
     */
    private fun MediaExtractor.trackFormats(): List<MediaFormat> = (0 until trackCount).map(::getTrackFormat)

    /**
     * One track property as an Int, or [fallback] when the format has no Int to give.
     *
     * `containsKey` alone is not enough, because `MediaFormat` is a heterogeneous map: a key it
     * holds as a Float answers `getInteger` with a `ClassCastException` rather than a coercion, and
     * `KEY_FRAME_RATE` — which [probeForConcat] reads — is legitimately set either way. The
     * `runCatching` is therefore load-bearing rather than defensive. Without it a single
     * oddly-typed field throws past the whole track loop, and the catch there answers with an empty
     * [ConcatInput], discarding the codec and dimensions that had already been read.
     *
     * `internal` for the unit tests, as [shortName].
     */
    internal fun MediaFormat.intOr(key: String, fallback: Int = 0): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    /**
     * MediaFormat MIME -> the short codec names the router and FFmpeg both speak.
     *
     * A lookup table over platform constants is the shape that rots quietly. Most of these arms are
     * translations rather than trimming — `video/avc` is `h264`, `audio/mp4a-latm` is `aac`,
     * `video/x-vnd.on2.vp9` is `vp9` — so a dropped arm does not fail. It falls through to
     * `substringAfter('/')` and reports a different, plausible-looking string that
     * `CodecNames` may or may not still recognise, and an unrecognised codec is how a
     * stream-copyable file quietly becomes a re-encode.
     *
     * `internal` so the unit tests can name every arm; the JVM test source set is a friend of
     * `main`, so this stays invisible outside the module.
     */
    internal fun shortName(mime: String): String = when (mime) {
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
