package dev.jasonmross.mediaconverter.convert

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
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
class Media3Engine(private val context: Context) : AutoCloseable {

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
    suspend fun transcode(
        input: Uri,
        output: File,
        videoMimeType: String = MimeTypes.VIDEO_H265,
        onProgress: (Int) -> Unit = {},
    ): ExportResult = suspendCancellableCoroutine { cont ->
        handler.post {
            val transformer = buildTransformer(videoMimeType, cont)
            val item = EditedMediaItem.Builder(MediaItem.fromUri(input)).build()

            cont.invokeOnCancellation {
                // cancel() has the same single-thread requirement as start().
                handler.post { runCatching { transformer.cancel() } }
            }

            runCatching { transformer.start(item, output.absolutePath) }
                .onFailure { cont.resumeWithException(it); return@post }

            pollProgress(transformer, cont, onProgress)
        }
    }

    private fun buildTransformer(
        videoMimeType: String,
        cont: CancellableContinuation<ExportResult>,
    ): Transformer = Transformer.Builder(context)
        .setLooper(thread.looper)
        .setVideoMimeType(videoMimeType)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                if (cont.isActive) cont.resume(result)
            }

            override fun onError(
                composition: Composition,
                result: ExportResult,
                exception: ExportException,
            ) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
        })
        .build()

    /**
     * Polls export progress on the Transformer's own thread.
     *
     * Deliberately ~4x/second: the underlying value updates far more often than a UI
     * or a notification can usefully consume, and over-frequent notification updates
     * will jank the system UI.
     */
    private fun pollProgress(
        transformer: Transformer,
        cont: CancellableContinuation<ExportResult>,
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

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
