package org.libremediaconverter.convert

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.AudioPlan
import org.libremediaconverter.model.ConversionPlan
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.CopyPlanner
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.model.VideoPlan
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hardware conversion via Media3 Transformer.
 *
 * ## Why the HandlerThread
 *
 * Transformer documents that instances "must be accessed from a single application
 * thread", and both [Transformer.start] and [Transformer.cancel] throw
 * [IllegalStateException] when called from anywhere else. The thread it binds to is
 * whichever one had a Looper when the Builder was constructed — falling back to the
 * *main* Looper if the constructing thread has none.
 *
 * That default is a trap for background execution. A WorkManager `Worker` runs on an
 * executor thread with no Looper, so a Transformer built there silently binds to the
 * main thread, and the subsequent `start()` from the worker thread throws.
 *
 * This class therefore owns a dedicated [HandlerThread], passes its Looper explicitly
 * via `setLooper`, and marshals every Transformer call onto it. Callers get a plain
 * suspending function and never have to think about it.
 */
@UnstableApi
class Media3Engine(private val context: Context) : HardwareTranscoder {

    private val thread = HandlerThread("media3-transformer").apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * Transcodes [input] to [output], reporting progress 0..100.
     *
     * [output] must be a real filesystem path, not a SAF document. Writing through a
     * SAF file descriptor breaks MP4 muxing, because faststart needs to seek back and
     * rewrite the moov atom, and a SAF fd is not reliably seekable. Callers stage into
     * app-private storage and publish afterwards.
     */
    override suspend fun transcode(
        input: Uri,
        output: File,
        request: ConversionRequest,
        onProgress: (Int) -> Unit,
    ): Unit = suspendCancellableCoroutine { cont ->
        val plan = CopyPlanner.plan(request.spec, request.probe)
        handler.post {
            // One guard around the whole body, deliberately.
            //
            // This used to be two narrow ones — around `buildTransformer` and around
            // `transformer.start` — with the two Media3 builders sitting unguarded between them.
            // On this thread that is not a small gap: nothing here has a caller to throw back to,
            // so an escaping exception reaches the HandlerThread's uncaught handler and takes the
            // process down, while [cont] is never resumed either way. `EditedMediaItem.Builder`
            // does exactly that for a plan that drops both tracks
            // ("Audio and video cannot both be removed"), which a queued job can still carry.
            // Widening the guard costs nothing on success and turns every such refusal into a
            // failed job with a reason.
            runCatching { startExport(input, output, plan, cont, onProgress) }
                .onFailure { if (cont.isActive) cont.resumeWithException(it) }
        }
    }

    /**
     * Builds the export and hands it to Transformer. Runs on the HandlerThread; may throw.
     *
     * Everything Transformer's single-thread contract covers lives here, so that the caller has
     * exactly one place to catch. Returning normally means the export is running and [cont] belongs
     * to the listener; throwing means it never started and the caller owns resuming.
     */
    private fun startExport(
        input: Uri,
        output: File,
        plan: ConversionPlan,
        cont: CancellableContinuation<Unit>,
        onProgress: (Int) -> Unit,
    ) {
        val transformer = buildTransformer(plan, cont)

        // Dropping the tracks the target does not have is what stops an audio-only export
        // from carrying a re-encoded video track. Without setRemoveVideo, asking for M4A
        // produced an HEVC stream in a file named .m4a.
        val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
            .setRemoveVideo(plan.video == VideoPlan.Drop)
            .setRemoveAudio(plan.audio == AudioPlan.Drop)
            .build()

        // A Composition is the only way to ask for transmuxing; the plain
        // start(EditedMediaItem, path) overload always re-encodes. This is the remux path.
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            .setTransmuxVideo(plan.video == VideoPlan.Copy)
            .setTransmuxAudio(plan.audio == AudioPlan.Copy)
            .build()

        // Registered before start(), so a cancellation racing the export always finds a
        // transformer to cancel.
        cont.invokeOnCancellation {
            // cancel() has the same single-thread requirement as start().
            handler.post { runCatching { transformer.cancel() } }
        }

        transformer.start(composition, output.absolutePath)
        pollProgress(transformer, cont, onProgress)
    }

    /**
     * @throws IllegalArgumentException if [plan] names a container Media3 cannot mux. That is a
     *   routing bug rather than a runtime condition — [org.libremediaconverter.model.ConversionRouter]
     *   is supposed to have sent such a job to FFmpeg — so it fails loudly instead of quietly
     *   writing MP4, which is what the old code did.
     */
    private fun buildTransformer(plan: ConversionPlan, cont: CancellableContinuation<Unit>): Transformer {
        val muxerFactory = requireNotNull(Media3Muxers.factoryFor(plan.container)) {
            "Media3 cannot mux ${plan.container}; this job should have routed to FFmpeg."
        }

        val builder = Transformer.Builder(context)
            .setLooper(thread.looper)
            .setMuxerFactory(muxerFactory)

        // Name a MIME type only for a track that is actually being encoded. Setting one for a
        // transmuxed track contradicts setTransmuxVideo/Audio, and setting one for a removed
        // track makes Transformer build an encoder for samples that will never arrive.
        (plan.video as? VideoPlan.Encode)?.let { videoMimeTypeFor(it.codec)?.let(builder::setVideoMimeType) }
        (plan.audio as? AudioPlan.Encode)?.let { audioMimeTypeFor(it.codec)?.let(builder::setAudioMimeType) }

        return builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })
            .build()
    }

    /**
     * Polls export progress on the Transformer's own thread.
     *
     * Deliberately ~4x/second: the underlying value updates far more often than a UI
     * or a notification can usefully consume, and over-frequent notification updates
     * will jank the system UI.
     */
    private fun pollProgress(
        transformer: Transformer,
        cont: CancellableContinuation<Unit>,
        onProgress: (Int) -> Unit,
    ) {
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
        handler.postDelayed(tick, PROGRESS_INTERVAL_MS)
    }

    override fun close() {
        thread.quitSafely()
    }

    /**
     * The progress interval, and the two enum-to-MIME tables.
     *
     * The tables are pure functions of a codec enum, so they sit here rather than on the instance:
     * a JVM test can then exercise every arm without constructing an engine, which would start a
     * real [HandlerThread] to answer a lookup. `internal` rather than `private` for the reason
     * `MainActivity`'s `Destination` records — the JVM test source set is a friend of `main`, so
     * these stay invisible to anything outside the module.
     */
    internal companion object {
        const val PROGRESS_INTERVAL_MS = 250L

        /**
         * Media3 encodes only H.264 and H.265 of the codecs this app offers.
         *
         * VP8/VP9/AV1 targets never reach here — the router sends them to FFmpeg because
         * `Transformer.setVideoMimeType` rejects them — so anything unexpected returns null and
         * lets Transformer pick, rather than silently substituting H.265 as the old mapping did.
         */
        internal fun videoMimeTypeFor(codec: VideoCodec): String? = when (codec) {
            VideoCodec.H264 -> MimeTypes.VIDEO_H264
            VideoCodec.H265 -> MimeTypes.VIDEO_H265
            // Never reached, and no longer only asserted: `Media3EngineMimeTypesTest` drives
            // `CopyPlanner` over every spec it can be handed and shows that no Encode plan carries
            // either, which is what turns "COPY/NONE are not Encode" into a checked claim.
            VideoCodec.COPY, VideoCodec.NONE -> null
            VideoCodec.VP8, VideoCodec.VP9, VideoCodec.AV1 -> null
        }

        /**
         * Media3 encodes AAC, Opus and PCM. Three arms below are dead, not two.
         *
         * The comment this replaces named MP3 and FLAC as the exceptions, which reads as though
         * every other arm were live. **Vorbis is not.** A single router rule diverts every audio
         * codec outside {AAC, Opus, PCM} to FFmpeg, and Vorbis is outside it, so
         * `VORBIS -> AUDIO_VORBIS` names a MIME type Transformer is never actually asked for.
         *
         * The arm stays because the mapping is correct — deleting a right answer out of
         * unreachable code buys nothing — but it is an entry waiting on a routing change rather
         * than a live one. `Media3EngineMimeTypesTest` routes all six encodable codecs and asserts
         * which three arrive, so if that set moves, the disagreement fails rather than surprises.
         */
        internal fun audioMimeTypeFor(codec: AudioCodec): String? = when (codec) {
            AudioCodec.AAC -> MimeTypes.AUDIO_AAC
            AudioCodec.OPUS -> MimeTypes.AUDIO_OPUS
            AudioCodec.VORBIS -> MimeTypes.AUDIO_VORBIS
            AudioCodec.PCM -> MimeTypes.AUDIO_RAW
            AudioCodec.COPY, AudioCodec.NONE -> null
            // MP3 and FLAC have no Android encoder at any API level, so the router sends them to
            // FFmpeg before an encoder is ever asked for.
            AudioCodec.MP3, AudioCodec.FLAC -> null
        }
    }
}
