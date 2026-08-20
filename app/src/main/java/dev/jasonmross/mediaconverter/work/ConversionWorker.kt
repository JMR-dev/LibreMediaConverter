package dev.jasonmross.mediaconverter.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.jasonmross.mediaconverter.convert.Media3Engine
import dev.jasonmross.mediaconverter.convert.OutputPublisher

/**
 * Runs one conversion as durable, cancellable background work.
 *
 * WorkManager rather than a bare foreground service: the queue survives process
 * death, cancellation and progress are already modelled, and `WorkInfo` gives the UI
 * a Flow to observe. That durability is what makes the six-hour foreground-service
 * timeout recoverable instead of fatal — see [handleTimeoutIfNeeded].
 *
 * Expedited work is deliberately *not* used. It maps to JobScheduler expedited jobs
 * with a short quota, which is the wrong shape for a multi-minute transcode.
 */
@UnstableApi
class ConversionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val notifications = ConversionNotifications(applicationContext)
    private val publisher = OutputPublisher(applicationContext)

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI)?.toUri() ?: return Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "input"
        val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)
        val mimeType = inputData.getString(KEY_VIDEO_MIME) ?: MimeTypes.VIDEO_H265

        if (!publisher.hasSpaceFor(sizeBytes)) {
            return Result.failure(workDataOf(KEY_ERROR to "Not enough free space to convert."))
        }

        setForeground(foregroundInfo(displayName, percent = 0, indeterminate = true))

        val staged = publisher.createStagingFile(outputNameFor(displayName))
        // The engine owns its own Looper thread, so it is safe to call from this
        // Looper-less worker thread. See Media3Engine.
        val engine = Media3Engine(applicationContext)

        return try {
            var lastPublished = 0L
            engine.transcode(inputUri, staged, mimeType) { percent ->
                setProgressAsync(workDataOf(KEY_PROGRESS to percent))
                // Throttle the notification to ~1/sec. The underlying progress updates
                // several times a second, and pushing every one janks the system UI.
                val now = System.currentTimeMillis()
                if (now - lastPublished >= NOTIFICATION_INTERVAL_MS) {
                    lastPublished = now
                    notifications
                        .build(id, displayName, percent)
                        .let { notificationManager().notify(NOTIFICATION_ID, it) }
                }
            }
            Result.success(workDataOf(KEY_OUTPUT_PATH to staged.absolutePath))
        } catch (e: Throwable) {
            staged.delete()
            handleTimeoutIfNeeded(e)
        } finally {
            engine.close()
        }
    }

    /**
     * Distinguishes a genuine failure from the foreground-service budget expiring.
     *
     * `mediaProcessing` allows six hours out of every twenty-four, shared across the
     * app. When that runs out WorkManager reports
     * `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`, and the correct response is to retry
     * later rather than tell the user the conversion failed — the work is still valid,
     * there is simply no budget right now.
     */
    private fun handleTimeoutIfNeeded(cause: Throwable): Result {
        val timedOut = stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT
        return if (timedOut) {
            Log.w(TAG, "Foreground service budget exhausted; will retry.", cause)
            Result.retry()
        } else {
            Log.e(TAG, "Conversion failed.", cause)
            Result.failure(workDataOf(KEY_ERROR to (cause.message ?: "Conversion failed.")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(
            inputData.getString(KEY_DISPLAY_NAME) ?: "input",
            percent = 0,
            indeterminate = true,
        )

    private fun foregroundInfo(title: String, percent: Int, indeterminate: Boolean) =
        ForegroundInfo(
            NOTIFICATION_ID,
            notifications.build(id, title, percent, indeterminate),
            ConversionForegroundType.current(),
        )

    private fun notificationManager() =
        applicationContext.getSystemService(android.app.NotificationManager::class.java)

    private fun String.toUri(): Uri? = runCatching { Uri.parse(this) }.getOrNull()

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_VIDEO_MIME = "video_mime"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ERROR = "error"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val TAG = "ConversionWorker"

        fun outputNameFor(inputName: String): String =
            inputName.substringBeforeLast('.', inputName) + "_converted.mp4"

        fun request(
            inputUri: Uri,
            displayName: String,
            sizeBytes: Long,
            videoMimeType: String = MimeTypes.VIDEO_H265,
        ) = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_INPUT_URI, inputUri.toString())
                    .putString(KEY_DISPLAY_NAME, displayName)
                    .putLong(KEY_SIZE_BYTES, sizeBytes)
                    .putString(KEY_VIDEO_MIME, videoMimeType)
                    .build()
            )
            .build()
    }
}
