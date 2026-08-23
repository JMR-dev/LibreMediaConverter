package org.libremediaconverter.convert

import java.util.UUID

/**
 * Gives every job a staging path of its own.
 *
 * `<cacheDir>/conversions/` is shared by the convert tab, the join tab and
 * [org.libremediaconverter.ffmpeg.ConcatEngine]'s list file, and until this existed none of the
 * three named a file that belonged to one job. A conversion derived its name from the input's
 * display name, so two `holiday.mp4` from different folders collided; a join used the constant
 * `joined.<ext>`, so any two joins of one format collided; the list file was the constant
 * `concat_list.txt`, so any two joins at all collided.
 *
 * The collision was not theoretical. Two independent conversions on a Pixel each produced
 * `cache/conversions/input_converted.mp4`, and a tag query in a fresh process returned two
 * SUCCEEDED `WorkInfo`s naming that one file — which is what leaves reattachment unable to say
 * which job the bytes on disk belong to.
 *
 * ## Why the job id, and why opaque
 *
 * The WorkManager request id is stable across retries: `WorkerWrapper` builds `WorkerParameters`
 * from the `WorkSpec` id and only increments `runAttemptCount`. That matters more than uniqueness
 * does — a retry runs `doWork()` from the top, and the delete on the way out of a failed attempt
 * only collects the previous attempt's partial when the name has not moved.
 *
 * The staged name is never shown to anyone: `save()` recomputes a suggested name from the job's
 * own spec, and the user picks the real one in the SAF dialog. So there is nothing to lose by
 * making it opaque, and something to gain — the alternative was sanitising a provider-supplied
 * display name, which can contain a separator, be empty, or be four kilobytes long. Naming the job
 * retires that question instead of answering it.
 *
 * The extension is kept, and is not decoration. `FFmpegConcatCommand` names no output muxer, so
 * FFmpeg infers it from the path; a name without the right extension would quietly produce the
 * wrong container.
 */
object StagingNames {

    /** The staging filename for the job with this [jobId], producing a file of type [extension]. */
    fun forJob(jobId: UUID, extension: String): String = "$jobId.$extension"

    /**
     * The concat list file that belongs to the output staged as [outputName].
     *
     * Derived from the output rather than taken as another parameter, so the two cannot drift
     * apart and so a directory listing shows which list belongs to which join. It ages with its
     * output too, which is what [StagingSweep] needs.
     */
    fun concatListFor(outputName: String): String = outputName.substringBeforeLast('.', outputName) + CONCAT_LIST_SUFFIX

    private const val CONCAT_LIST_SUFFIX = ".concat_list.txt"
}
