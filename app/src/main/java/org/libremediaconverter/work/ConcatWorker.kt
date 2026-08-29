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
import androidx.work.hasKeyWithValueOfType
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.InputQuery
import org.libremediaconverter.convert.StagingNames
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
            return Result.failure(workDataOf(KEY_ERROR to TOO_FEW_INPUTS_MESSAGE))
        }
        // Absent, not zero, when the picker could not size every input -- see the same read in
        // ConversionWorker and InputQuery for why the two are no longer one number.
        val declaredTotal = inputData
            .takeIf { it.hasKeyWithValueOfType<Long>(KEY_TOTAL_BYTES) }
            ?.getLong(KEY_TOTAL_BYTES, 0L)
        // Looked up rather than `valueOf` -- see the same three reads in ConversionWorker. This one
        // is above the try as well, so a format name this build does not define used to throw past
        // the catch: FAILED with no error in the output Data, and no staged.delete().
        val format = inputData.getString(KEY_FORMAT)
            ?.let { name -> OutputFormat.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_FORMAT

        if (!hasRoomFor(declaredTotal, uris)) {
            return Result.failure(workDataOf(KEY_ERROR to "Not enough free space to join these files."))
        }

        // Named before anything below can throw, so every exit has the handle to clean up with.
        // See the same line in ConversionWorker.
        //
        // Keyed on this job's id. The constant "joined.<ext>" this replaces meant any two joins of
        // the same format wrote one file, and ConcatEngine's list file collided harder still.
        val staged = publisher.createStagingFile(StagingNames.forJob(id, format.extension))

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
                    // See the same two in ConversionWorker. The join screen has no format picker
                    // today, so `joined.mp4` was right by accident everywhere it was written out;
                    // reporting them means the accident is not what holds it up.
                    KEY_SUGGESTED_NAME to outputNameFor(format),
                    KEY_MIME_TYPE to format.mimeType,
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
                    Result.failure(workDataOf(KEY_ERROR to (e.message ?: GENERIC_FAILURE_MESSAGE)))
                }
            }
        }
    }

    /**
     * Whether staging can take this join, measuring the inputs when nothing else has.
     *
     * The same shape as `ConversionWorker.hasRoomFor` and for the same reasons, with one
     * difference worth naming: a join's total is [InputQuery.total], which is null the moment a
     * *single* input cannot be sized. Summing the ones that answered would produce a lower bound
     * indistinguishable from a real total, which is the conflation this change exists to end.
     */
    private fun hasRoomFor(declared: Long?, uris: List<Uri>): Boolean {
        val bytes = declared ?: InputQuery.total(uris.map { InputQuery.sizeOf(applicationContext, it) })
        if (bytes == null) {
            Log.i(TAG, "Nothing could size every input; checking headroom only.")
            return publisher.hasSpaceForUnknownSize()
        }
        return publisher.hasSpaceFor(bytes)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        notifications.build(id, "Joining files", 0, indeterminate = true),
        ConversionForegroundType.current(),
    )

    companion object {
        /**
         * What the user is told when a join arrives with fewer than two inputs.
         *
         * Shared with `JoinViewModel`, which refuses the same condition one layer up so the picker
         * can answer without enqueueing anything. Two copies of this sentence existed before, and
         * only the one here was pinned by a test (#139) — so the wording could drift on the screen
         * without a single test noticing, for one message the user sees from one condition.
         *
         * Here rather than in the ViewModel because the rule is the worker's: `request(...)` takes
         * a `List<Uri>` and checks nothing about its length, so this is the guard that always runs.
         */
        const val TOO_FEW_INPUTS_MESSAGE: String = "Pick at least two files to join."

        /**
         * The last resort when a join fails and the exception says nothing.
         *
         * Shared with `JoinViewModel`, whose `FAILED` arm falls back to the same sentence when the
         * output `Data` carries no error at all — a worker killed before it could write one. The two
         * are a chain rather than a coincidence: this is what the worker puts *in* `KEY_ERROR`, and
         * that is what the ViewModel says when `KEY_ERROR` never arrived. The user cannot tell the
         * two apart and should not have to, so they are one sentence.
         *
         * The `Log.e` above deliberately keeps its own literal. A log line has a different audience
         * and carries the exception with it; coupling it to the user-facing wording would mean
         * rewording the screen to change a log.
         */
        const val GENERIC_FAILURE_MESSAGE: String = "Joining failed."

        const val KEY_INPUT_URIS = "input_uris"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FORMAT = "format"
        const val KEY_OUTPUT_PATH = "output_path"
        const val KEY_STRATEGY = "strategy"

        /** The name to offer in the save dialog, and the type to open it with. */
        const val KEY_SUGGESTED_NAME = "suggested_name"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_ERROR = "error"

        /**
         * What a join produces when nothing says otherwise.
         *
         * Named once rather than repeated at the three places that need it -- the input-Data
         * default, [request]'s parameter default, and the fallback a ViewModel uses for a job
         * enqueued before this worker reported its format. Those three disagreeing is the shape
         * this whole entry is about.
         */
        val DEFAULT_FORMAT: OutputFormat = OutputFormat.MP4_H264

        /**
         * The name to suggest in the save dialog for a join of this [format].
         *
         * A function rather than the literal `joined.mp4` it replaces: that literal appeared in
         * the ViewModel and twice in the screen, and all three were correct only because the join
         * screen has no format picker yet.
         */
        fun outputNameFor(format: OutputFormat): String = "joined.${format.extension}"

        private const val NOTIFICATION_ID = 1002
        private const val TAG = "ConcatWorker"

        /**
         * How many files are being joined is tagged as well as passed as input `Data`, because
         * `WorkInfo` gives a job's tags back and its input `Data` never. It is the one thing the
         * join screen says about a job in flight, and after a restart nothing else can supply
         * it. See [JobTags].
         */
        fun request(inputs: List<Uri>, totalBytes: Long?, format: OutputFormat = DEFAULT_FORMAT) =
            OneTimeWorkRequestBuilder<ConcatWorker>()
                .addTag(JobTags.inputCount(inputs.size))
                .setInputData(
                    Data.Builder()
                        .putStringArray(KEY_INPUT_URIS, inputs.map(Uri::toString).toTypedArray())
                        // Omitted rather than zeroed when a total could not be worked out; a
                        // `Data` has no null, so the missing key is the unknown.
                        .apply { totalBytes?.let { putLong(KEY_TOTAL_BYTES, it) } }
                        .putString(KEY_FORMAT, format.name)
                        .build(),
                )
                .build()
    }
}
