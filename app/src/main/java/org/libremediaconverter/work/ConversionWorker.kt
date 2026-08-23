package org.libremediaconverter.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.CancellationException
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.StagingNames
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ContainerCapabilities
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.ConversionRouter
import org.libremediaconverter.model.Engine
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.Validation
import org.libremediaconverter.model.VideoCodec
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
 *
 * That durability is not free, and the queue surviving is not the same as the job surviving.
 * When WorkManager recovers a job after process death the app is by definition in the background,
 * where the system refuses to start a foreground service — so the recovered attempt's
 * `setForeground` throws. Handling that inside [doWork] rather than letting it escape is what
 * turns the recovery into a retry instead of a terminal failure; see [FailureOutcome].
 */
@UnstableApi
class ConversionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = ConversionNotifications(applicationContext)

    // Resolved through ConversionDependencies so tests can force the failure paths.
    private val publisher = ConversionDependencies.publisher(applicationContext)

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No input file."))
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "input"
        val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)
        val spec = readSpec()
        val quality = QualityTier.valueOf(
            inputData.getString(KEY_QUALITY) ?: QualityTier.FAST.name,
        )
        val preference = EnginePreference.valueOf(
            inputData.getString(KEY_ENGINE_PREFERENCE) ?: EnginePreference.AUTO.name,
        )

        if (!publisher.hasSpaceFor(sizeBytes)) {
            return Result.failure(workDataOf(KEY_ERROR to "Not enough free space to convert."))
        }

        // Named before anything below can throw, so every exit has the handle to clean up with.
        // This only builds a path -- nothing is written until an engine opens it -- so naming it
        // early costs nothing, and it is what lets the catch collect a partial an earlier attempt
        // left behind under the same name.
        //
        // Keyed on this job's id rather than on the input's display name: two conversions of files
        // that happen to share a name are two jobs, and used to be one file. See StagingNames.
        val staged = publisher.createStagingFile(StagingNames.forJob(id, spec.extension))

        return try {
            // Inside the try, and that placement is the whole point. setForeground() throws
            // ForegroundServiceStartNotAllowedException when the system refuses a background
            // foreground-service start -- which is exactly what a WorkManager restart after
            // process death is. With it above the try that throw escaped doWork() entirely: no
            // retry, no error in the output Data, and no staged.delete(). MediaProbe.probe below
            // was outside for the same reason and had the same problem.
            setForeground(foregroundInfo(displayName, percent = 0, indeterminate = true))

            // Through the seam rather than MediaProbe directly. The seam already existed for the
            // ViewModel and the worker was the last caller bypassing it, which is why nothing on
            // the JVM could reach a line below this one: FFprobe's loader throws a bare
            // java.lang.Error with no native library present. The app and the instrumented tests
            // get the real probe, exactly as before.
            val probe = ConversionDependencies.probe(applicationContext, inputUri)
            val devices = ConversionDependencies.deviceCodecs()
            val request = ConversionRequest(
                spec = spec,
                quality = quality,
                enginePreference = preference,
                probe = probe,
                hardwareEncodeAvailable = devices.canEncode(spec.videoCodec),
            )
            // The picker refuses an impossible combination before Convert is tappable, but a job
            // can also arrive from a queued request made before the settings changed, or from a
            // direct ConversionWorker.request(...) call. Checking here means an invalid spec fails
            // with the reason rather than being silently coerced into something else.
            val validation = ContainerCapabilities.validate(spec, probe)
            if (validation is Validation.Invalid) {
                Log.w(TAG, "Refusing $spec for $displayName: ${validation.message}")
                return Result.failure(workDataOf(KEY_ERROR to validation.message))
            }

            val decision = ConversionRouter.route(request, devices)
            Log.i(TAG, "Routing $displayName -> $spec via ${decision.engine} (${decision.reason})")

            when (decision.engine) {
                Engine.MEDIA3 -> runMedia3OrFallBack(request, inputUri, staged, displayName)
                Engine.FFMPEG -> runFFmpeg(request, inputUri, staged, displayName)
            }
            Result.success(
                workDataOf(
                    KEY_OUTPUT_PATH to staged.absolutePath,
                    KEY_ENGINE_USED to decision.engine.name,
                    KEY_ROUTE_REASON to decision.reason.explanation,
                    // Reported rather than left to be recomputed. The spec arrives here as input
                    // Data, and WorkInfo never hands input Data back -- so this is the only moment
                    // at which anything knows both the input's name and the spec that ran. A
                    // ViewModel deriving it later has only its own picker, which is not the same
                    // thing and is not the same thing in two different ways: a reattached job's
                    // spec was never in those settings, and a live picker can move mid-job.
                    KEY_SUGGESTED_NAME to outputNameFor(displayName, spec),
                    KEY_MIME_TYPE to spec.mimeType,
                ),
            )
        } catch (e: CancellationException) {
            // Cancellation is not a result, and answering it with one breaks structured
            // concurrency: this coroutine would report completion inside a scope that has already
            // been cancelled. Invisible today only because WorkManager marks the work CANCELLED
            // itself and ignores whatever the worker returned.
            //
            // The delete still has to happen, and has to happen here. A cancelled attempt leaves a
            // partial in staging, the next attempt starts from the top rather than resuming it,
            // and this is the only code holding the handle.
            staged.delete()
            throw e
        } catch (e: Throwable) {
            staged.delete()
            outcomeFor(e)
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
            engine.transcode(inputUri, staged, request) { percent ->
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

    private suspend fun runFFmpeg(request: ConversionRequest, inputUri: Uri, staged: File, displayName: String) {
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

    private fun isCancellation(e: Throwable): Boolean = e is CancellationException || isStopped

    /**
     * Turns whatever ended the attempt into a `Result`. [FailureOutcome] owns the rules.
     *
     * Three answers, because there are three genuinely different situations: the work is still
     * valid and should run later, the system will not let it run and the user has to be told how
     * to unblock it, or the conversion itself failed and the reason belongs on screen.
     */
    private fun outcomeFor(cause: Throwable): Result =
        when (FailureOutcome.forFailure(stopReason, cause, runAttemptCount)) {
            FailureOutcome.RETRY -> {
                Log.w(TAG, "Conversion interrupted; will retry.", cause)
                Result.retry()
            }
            FailureOutcome.FOREGROUND_DENIED -> {
                Log.e(TAG, "Foreground start refused $runAttemptCount times; giving up.", cause)
                Result.failure(workDataOf(KEY_ERROR to FailureOutcome.FOREGROUND_DENIED_MESSAGE))
            }
            FailureOutcome.FAIL -> {
                Log.e(TAG, "Conversion failed.", cause)
                Result.failure(workDataOf(KEY_ERROR to (cause.message ?: "Conversion failed.")))
            }
        }

    /**
     * Reads the output spec out of the worker's input Data.
     *
     * Carried as three separate strings rather than one preset name: the picker can now produce
     * combinations no preset covers, so there is no enum entry to name. Unknown or missing values
     * fall back to the default preset rather than throwing — a worker that crashes on malformed
     * input reports "conversion failed" with no useful message.
     */
    private fun readSpec(): OutputSpec {
        val fallback = OutputFormat.MP4_H265.spec
        val container = inputData.getString(KEY_CONTAINER)
            ?.let { name -> Container.entries.firstOrNull { it.name == name } }
            ?: return fallback
        val video = inputData.getString(KEY_VIDEO_CODEC)
            ?.let { name -> VideoCodec.entries.firstOrNull { it.name == name } }
            ?: return fallback
        val audio = inputData.getString(KEY_AUDIO_CODEC)
            ?.let { name -> AudioCodec.entries.firstOrNull { it.name == name } }
            ?: return fallback
        return OutputSpec(container, video, audio)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(
        inputData.getString(KEY_DISPLAY_NAME) ?: "input",
        percent = 0,
        indeterminate = true,
    )

    private fun foregroundInfo(title: String, percent: Int, indeterminate: Boolean) = ForegroundInfo(
        NOTIFICATION_ID,
        notifications.build(id, title, percent, indeterminate),
        ConversionForegroundType.current(),
    )

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_CONTAINER = "container"
        const val KEY_VIDEO_CODEC = "video_codec"
        const val KEY_AUDIO_CODEC = "audio_codec"
        const val KEY_QUALITY = "quality"
        const val KEY_ENGINE_PREFERENCE = "engine_preference"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_ENGINE_USED = "engine_used"
        const val KEY_ROUTE_REASON = "route_reason"

        /** The name to offer in the save dialog, and the type to open it with. */
        const val KEY_SUGGESTED_NAME = "suggested_name"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_ERROR = "error"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val TAG = "ConversionWorker"

        /**
         * The name to suggest in the save dialog.
         *
         * The extension comes from the container and whether a video track survives, so Matroska
         * yields `.mkv` or `.mka` and MP4 yields `.mp4` or `.m4a` without a preset having to
         * enumerate both.
         *
         * No longer the staged name as well. Staging is keyed on the job id -- see [StagingNames]
         * -- so this is only ever the string offered to the user, which is also what makes it safe
         * for it to carry a display name the app does not control.
         */
        fun outputNameFor(inputName: String, spec: OutputSpec): String = inputName.substringBeforeLast('.', inputName) +
            "_converted.${spec.extension}"

        /**
         * The name and size are tagged as well as passed as input `Data`, and that is not
         * redundant. `WorkInfo` hands back a job's tags and its output but never the `Data` it
         * was enqueued with, so after a restart the tags are the only way for the UI to say
         * *which file* a job it did not start is working on. See [JobTags].
         */
        fun request(
            inputUri: Uri,
            displayName: String,
            sizeBytes: Long,
            spec: OutputSpec = OutputFormat.MP4_H265.spec,
            quality: QualityTier = QualityTier.FAST,
            enginePreference: EnginePreference = EnginePreference.AUTO,
        ) = OneTimeWorkRequestBuilder<ConversionWorker>()
            .addTag(JobTags.displayName(displayName))
            .addTag(JobTags.sizeBytes(sizeBytes))
            .setInputData(
                Data.Builder()
                    .putString(KEY_INPUT_URI, inputUri.toString())
                    .putString(KEY_DISPLAY_NAME, displayName)
                    .putLong(KEY_SIZE_BYTES, sizeBytes)
                    .putString(KEY_CONTAINER, spec.container.name)
                    .putString(KEY_VIDEO_CODEC, spec.videoCodec.name)
                    .putString(KEY_AUDIO_CODEC, spec.audioCodec.name)
                    .putString(KEY_QUALITY, quality.name)
                    .putString(KEY_ENGINE_PREFERENCE, enginePreference.name)
                    .build(),
            )
            .build()
    }
}
