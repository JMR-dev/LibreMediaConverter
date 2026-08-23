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
import kotlinx.coroutines.CancellationException
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.ffmpeg.ConcatEngine
import org.libremediaconverter.model.OutputFormat

/**
 * Joins several files into one, as durable foreground work.
 *
 * Separate from [ConversionWorker] rather than folded into it: joining takes a list of
 * inputs, has its own stream-copy-versus-re-encode decision, and reports a different
 * result. Overloading one worker with both would make the input contract ambiguous.
 *
 * Progress is not reported. FFmpeg's statistics callback gives a timestamp against a
 * single input's duration, which is meaningless once several files are being
 * concatenated; showing a fabricated percentage would be worse than showing none.
 */
@UnstableApi
class ConcatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = ConversionNotifications(applicationContext)
    private val publisher = ConversionDependencies.publisher(applicationContext)

    override suspend fun doWork(): Result {
        val uris = inputData.getStringArray(KEY_INPUT_URIS)?.map(Uri::parse)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No input files."))
        if (uris.size < 2) {
            return Result.failure(workDataOf(KEY_ERROR to "Pick at least two files to join."))
        }
        val totalBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val format = OutputFormat.valueOf(
            inputData.getString(KEY_FORMAT) ?: OutputFormat.MP4_H264.name,
        )

        if (!publisher.hasSpaceFor(totalBytes)) {
            return Result.failure(workDataOf(KEY_ERROR to "Not enough free space to join these files."))
        }

        // Named before anything below can throw, so every exit has the handle to clean up with.
        // See the same line in ConversionWorker.
        val staged = publisher.createStagingFile("joined.${format.extension}")

        return try {
            // Inside the try: a foreground start refused because the app is in the background --
            // which is where a WorkManager restart after process death always begins -- used to
            // throw straight past this catch, taking the retry, the error message and the delete
            // with it. See ConversionWorker.doWork and FailureOutcome.
            setForeground(
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notifications.build(id, "Joining ${uris.size} files", 0, indeterminate = true),
                    ConversionForegroundType.current(),
                ),
            )

            val result = ConcatEngine(applicationContext).join(uris, staged, format)
            Result.success(
                workDataOf(
                    KEY_OUTPUT_PATH to staged.absolutePath,
                    KEY_STRATEGY to result.strategy.name,
                ),
            )
        } catch (e: CancellationException) {
            // Rethrown rather than answered with a Result -- see the same branch in
            // ConversionWorker for why, and for why the delete stays.
            staged.delete()
            throw e
        } catch (e: Throwable) {
            staged.delete()
            when (FailureOutcome.forFailure(stopReason, e, runAttemptCount)) {
                FailureOutcome.RETRY -> {
                    Log.w(TAG, "Joining interrupted; will retry.", e)
                    Result.retry()
                }
                FailureOutcome.FOREGROUND_DENIED -> {
                    Log.e(TAG, "Foreground start refused $runAttemptCount times; giving up.", e)
                    Result.failure(workDataOf(KEY_ERROR to FailureOutcome.FOREGROUND_DENIED_MESSAGE))
                }
                FailureOutcome.FAIL -> {
                    Log.e(TAG, "Joining failed.", e)
                    Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Joining failed.")))
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notifications.build(id, "Joining files", 0, indeterminate = true),
        ConversionForegroundType.current(),
    )

    companion object {
        const val KEY_INPUT_URIS = "input_uris"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FORMAT = "format"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_STRATEGY = "strategy"
        const val KEY_ERROR = "error"

        private const val NOTIFICATION_ID = 1002
        private const val TAG = "ConcatWorker"

        /**
         * How many files are being joined is tagged as well as passed as input `Data`, because
         * `WorkInfo` gives a job's tags back and its input `Data` never. It is the one thing the
         * join screen says about a job in flight, and after a restart nothing else can supply
         * it. See [JobTags].
         */
        fun request(inputs: List<Uri>, totalBytes: Long, format: OutputFormat = OutputFormat.MP4_H264) =
            OneTimeWorkRequestBuilder<ConcatWorker>()
                .addTag(JobTags.inputCount(inputs.size))
                .setInputData(
                    Data.Builder()
                        .putStringArray(KEY_INPUT_URIS, inputs.map(Uri::toString).toTypedArray())
                        .putLong(KEY_TOTAL_BYTES, totalBytes)
                        .putString(KEY_FORMAT, format.name)
                        .build(),
                )
                .build()
    }
}
