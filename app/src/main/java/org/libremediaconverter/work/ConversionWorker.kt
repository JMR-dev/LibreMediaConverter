package org.libremediaconverter.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKitConfig
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.MediaProbe
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.ConversionRouter
import org.libremediaconverter.model.Engine
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import java.io.File

/**
 * Runs one conversion as durable, cancellable background work.
 *
 * WorkManager rather than a bare foreground service: the queue survives process death,
 * cancellation and progress are already modelled, and `WorkInfo` gives the UI a Flow to
 * observe. That durability is what makes the six-hour foreground-service timeout
 * recoverable instead of fatal.
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
    // Resolved through ConversionDependencies so tests can force the failure paths.
    private val publisher = ConversionDependencies.publisher(applicationContext)

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No input file."))
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "input"
        val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)
        val format = OutputFormat.valueOf(
            inputData.getString(KEY_FORMAT) ?: OutputFormat.MP4_H265.name
        )
        val quality = QualityTier.valueOf(
            inputData.getString(KEY_QUALITY) ?: QualityTier.FAST.name
        )
        val preference = EnginePreference.valueOf(
            inputData.getString(KEY_ENGINE_PREFERENCE) ?: EnginePreference.AUTO.name
        )

        if (!publisher.hasSpaceFor(sizeBytes)) {
            return Result.failure(workDataOf(KEY_ERROR to "Not enough free space to convert."))
        }

        setForeground(foregroundInfo(displayName, percent = 0, indeterminate = true))

        val probe = MediaProbe.probe(applicationContext, inputUri)
        val devices = ConversionDependencies.deviceCodecs()
        val request = ConversionRequest(
            format = format,
            quality = quality,
            enginePreference = preference,
            probe = probe,
            hardwareEncodeAvailable = devices.canEncode(format.videoCodec),
        )
        val decision = ConversionRouter.route(request, devices)
        Log.i(TAG, "Routing $displayName -> ${format.name} via ${decision.engine} (${decision.reason})")

        val staged = publisher.createStagingFile(outputNameFor(displayName, format))

        return try {
            when (decision.engine) {
                Engine.MEDIA3 -> runMedia3OrFallBack(request, inputUri, staged, displayName)
                Engine.FFMPEG -> runFFmpeg(request, inputUri, staged, displayName)
            }
            Result.success(
                workDataOf(
                    KEY_OUTPUT_PATH to staged.absolutePath,
                    KEY_ENGINE_USED to decision.engine.name,
                    KEY_ROUTE_REASON to decision.reason.explanation,
                )
            )
        } catch (e: Throwable) {
            staged.delete()
            handleTimeoutIfNeeded(e)
        }
    }

    /**
     * The dynamic half of the routing rules.
     *
     * The static predicates catch what is knowably unsupported, but hardware encoders
     * are vendor-declared and, per the platform's own documentation, "cannot be tested
     * for correctness". A device that claims HEVC support and then fails mid-export is
     * a real and common failure. Rather than surface that to the user as a failed
     * conversion, retry the same job in software — slower, but it produces the file.
     */
    private suspend fun runMedia3OrFallBack(
        request: ConversionRequest,
        inputUri: Uri,
        staged: File,
        displayName: String,
    ) {
        val engine = ConversionDependencies.hardware(applicationContext)
        try {
            engine.transcode(inputUri, staged, request.format) { percent ->
                publishProgress(displayName, percent)
            }
            return
        } catch (e: Throwable) {
            if (isCancellation(e)) throw e
            Log.w(TAG, "Hardware conversion failed; retrying in software.", e)
        } finally {
            engine.close()
        }

        staged.delete()
        runFFmpeg(request, inputUri, staged, displayName)
    }

    private suspend fun runFFmpeg(
        request: ConversionRequest,
        inputUri: Uri,
        staged: File,
        displayName: String,
    ) {
        // FFmpeg needs a path. ffkitsaf bridges a content:// URI for reading; the read
        // side is seekable for local providers, which is all the demuxer needs. Output
        // still goes to a real cache path — see OutputPublisher.
        val inputPath = if (inputUri.scheme == "content") {
            FFmpegKitConfig.getSafParameterForRead(applicationContext, inputUri)
        } else {
            inputUri.path
        } ?: error("Could not open the input file.")

        ConversionDependencies.software()
            .run(request, inputPath, staged, request.probe.durationMs) { percent ->
                publishProgress(displayName, percent)
            }
    }

    private var lastNotified = 0L

    private fun publishProgress(displayName: String, percent: Int) {
        setProgressAsync(workDataOf(KEY_PROGRESS to percent))
        // Throttle to ~1/sec: progress arrives several times a second and pushing every
        // update janks the system UI.
        val now = System.currentTimeMillis()
        if (now - lastNotified >= NOTIFICATION_INTERVAL_MS) {
            lastNotified = now
            applicationContext.getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notifications.build(id, displayName, percent))
        }
    }

    private fun isCancellation(e: Throwable): Boolean =
        e is kotlinx.coroutines.CancellationException || isStopped

    /**
     * Distinguishes a genuine failure from the foreground-service budget expiring.
     *
     * `mediaProcessing` allows six hours out of every twenty-four, shared across the
     * app. When that runs out WorkManager reports
     * `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`, and the right response is to retry
     * later rather than tell the user the conversion failed — the work is still valid,
     * there is simply no budget right now.
     */
    private fun handleTimeoutIfNeeded(cause: Throwable): Result =
        when (FailureOutcome.forStopReason(stopReason)) {
            FailureOutcome.RETRY -> {
                Log.w(TAG, "Foreground service budget exhausted; will retry.", cause)
                Result.retry()
            }
            FailureOutcome.FAIL -> {
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

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_FORMAT = "format"
        const val KEY_QUALITY = "quality"
        const val KEY_ENGINE_PREFERENCE = "engine_preference"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ENGINE_USED = "engine_used"
        const val KEY_ROUTE_REASON = "route_reason"
        const val KEY_ERROR = "error"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val TAG = "ConversionWorker"

        fun outputNameFor(inputName: String, format: OutputFormat): String =
            inputName.substringBeforeLast('.', inputName) +
                "_converted.${format.extension}"

        fun request(
            inputUri: Uri,
            displayName: String,
            sizeBytes: Long,
            format: OutputFormat = OutputFormat.MP4_H265,
            quality: QualityTier = QualityTier.FAST,
            enginePreference: EnginePreference = EnginePreference.AUTO,
        ) = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_INPUT_URI, inputUri.toString())
                    .putString(KEY_DISPLAY_NAME, displayName)
                    .putLong(KEY_SIZE_BYTES, sizeBytes)
                    .putString(KEY_FORMAT, format.name)
                    .putString(KEY_QUALITY, quality.name)
                    .putString(KEY_ENGINE_PREFERENCE, enginePreference.name)
                    .build()
            )
            .build()
    }
}
