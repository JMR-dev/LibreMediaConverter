package org.libremediaconverter.work

import androidx.work.WorkInfo
import java.util.UUID

/**
 * One of the app's own jobs, as WorkManager last reported it.
 *
 * Only the fields the reattachment decision reads. [outputExists] is deliberately a
 * `Boolean` rather than a `File`: whether the staged output is still on disk is the one
 * input to that decision that cannot be answered without touching the filesystem, so the
 * edge answers it and the rule stays testable on the JVM.
 */
data class JobSnapshot(
    val id: UUID,
    val state: WorkInfo.State,
    val runAttemptCount: Int,
    /** Where the worker said it left the output, for a job that got that far. */
    val outputPath: String?,
    /** Whether [outputPath] still names a non-empty file. Answered from disk by the caller. */
    val outputExists: Boolean,
    /**
     * When that file was last written, or 0 when there is none.
     *
     * The only ordering available anywhere in this data: [WorkInfo] carries no timestamp, and
     * the caller is already stat'ing the file.
     */
    val outputModifiedAt: Long = 0L,
    /** The job's tags, carrying what [JobTags] put there. Not read by the decision. */
    val tags: Set<String> = emptySet(),
)

/**
 * Which of the app's own jobs a freshly created ViewModel should pick up, if any, and whether
 * that job can be trusted to describe itself.
 *
 * A pure function rather than a branch inside the ViewModel, for the same reason as
 * [FailureOutcome]: the condition that matters cannot be provoked in a test. It needs the
 * process to be reclaimed while a job or its unsaved result is still around, which means a
 * device, an `am kill` and a wait. Isolating the choice means the rule itself is verified on
 * the JVM even though the situation that calls for it is not reproducible here.
 *
 * "Unfinished" here means unfinished *from the user's point of view*, not
 * [WorkInfo.State.isFinished]. A conversion that succeeded and was never saved is finished
 * work with a full-size file sitting in the cache and no route to it — that is the case this
 * whole mechanism exists for, and it is why [WorkInfo.State.SUCCEEDED] is a candidate here.
 */
sealed interface Reattachment {

    /** The job the UI should pick up. */
    val job: JobSnapshot

    /** Exactly one job explains what is on screen, so its tags describe it. */
    data class Certain(override val job: JobSnapshot) : Reattachment

    /**
     * Several finished jobs name the same staged file, so the file is reachable but nothing can
     * say which job produced it.
     *
     * Observed on a device rather than imagined: a tag query in a fresh process returned two
     * SUCCEEDED jobs whose output paths were both `…/conversions/input_converted.mp4`, with one
     * file on disk. Nothing gives a job a staging path of its own — the name is derived from the
     * input's display name — so a later conversion overwrites an earlier one's output while both
     * jobs go on reporting that path as their result.
     *
     * The file is not the ambiguous part: whichever entry is picked, the user is offered the
     * bytes actually on disk, which is the thing that would otherwise be lost. What cannot be
     * recovered is which job wrote them, so the caller is told not to describe it. A card
     * labelled with the other job's input would be a confident lie, where a neutral label is
     * merely thin. It resolves on its own once each job stages under a name of its own.
     */
    data class Ambiguous(override val job: JobSnapshot) : Reattachment

    companion object {

        /**
         * Picks the one job to reattach to, or null when there is nothing worth showing.
         *
         * Excluded outright:
         *
         * - **[WorkInfo.State.CANCELLED]** — the user already said no. Reattaching would undo
         *   that.
         * - **[WorkInfo.State.FAILED]** — nothing to act on, and nothing marks a failure as seen,
         *   so it would reappear on every launch. That matters more than it looks: a worker
         *   interrupted by process death can come back FAILED rather than retried, because the
         *   restart's `setForeground` is refused as a background foreground-service start, so
         *   failures left behind by earlier sessions are ordinary rather than rare.
         * - **[WorkInfo.State.SUCCEEDED] with no output file** — either it was saved, which
         *   deletes the staged copy, or the OS reclaimed the cache. Offering a Save button for a
         *   file that is gone turns a recoverable job into a failed save.
         *
         * Nothing filters by age, and nothing can. WorkManager keeps finished work for about a
         * week and prunes on its own schedule, so a tag query in a fresh process routinely
         * returns completed jobs from earlier sessions, and [WorkInfo] carries no timestamp to
         * sort them by. Whether the staged file is still there is the only signal separating a
         * result still worth offering from one already dealt with, which is why that check
         * carries the weight here.
         *
         * It is also the seam for a neighbouring defect: a result the user dismissed with "Start
         * over" currently keeps its staged file, so today it can be offered again on the next
         * launch. Nothing here changes when that is fixed — the file stops existing and the job
         * stops qualifying.
         *
         * Ranked, when more than one qualifies:
         *
         * 1. a job that is running now,
         * 2. a job waiting to be retried, which has already done part of the work,
         * 3. a job queued and not yet started,
         * 4. a finished result still on disk.
         *
         * Live work outranks a finished result because a running job is holding a foreground
         * notification: someone opening the app while that notification is in the shade expects
         * to find that conversion, not a result from yesterday. It is also the right answer when
         * the running job is overwriting the older one's staged file, which a shared staging name
         * allows.
         *
         * Within a rank the **newest staged file** wins, and that is not a detail. Losing a tie
         * is not the same as waiting for the next launch: the query has no `ORDER BY`, so its
         * order is unspecified but stable, and an arbitrary winner would keep winning every
         * launch while the other result stayed unreachable for as long as its file existed. Two
         * results at different paths is reachable — dismiss one with "Start over", which leaves
         * its file behind, then convert something else and do not save it.
         *
         * The ordering is the file's own modification time because there is nothing else:
         * [WorkInfo] carries no timestamp at all, and the file is already being stat'ed for
         * [JobSnapshot.outputExists]. The job that wrote most recently is the one the user is
         * likeliest to be waiting for. It is a heuristic to the extent that a clock can move
         * backwards, which is a better failure than an order that is unspecified and
         * systematically repeats itself.
         *
         * Two kinds of tie survive that and both are meant to. Live jobs have written no file, so
         * they have no timestamp and keep the query's order — and two of them are not reachable
         * from the UI today, since every state that can start a job is left the moment it does.
         * Aliases share a file and therefore share its timestamp, so they stay tied, which is
         * exactly right: the pick decides nothing about which bytes the user gets, and
         * [Ambiguous] answers the part that is genuinely unknown.
         */
        fun choose(jobs: List<JobSnapshot>): Reattachment? {
            // minWithOrNull keeps the first of equal elements, so the query's order is what
            // breaks a tie the comparator leaves — deliberately, per the ordering notes above.
            val chosen = jobs
                .mapNotNull { job -> rank(job)?.let { rank -> rank to job } }
                .minWithOrNull(
                    compareBy<Pair<Int, JobSnapshot>> { (rank, _) -> rank }
                        .thenByDescending { (_, job) -> job.outputModifiedAt },
                )
                ?.second
                ?: return null

            // Only a job that finished names a file, so only one of those can be aliased.
            val path = chosen.outputPath ?: return Certain(chosen)
            val aliases = jobs.filter { it.id != chosen.id && it.outputPath == path && rank(it) != null }
            // Aliases carrying identical tags describe the same input, so nothing turns on which
            // of them wrote the file and the label is safe either way. That is the ordinary case:
            // the same file converted twice.
            return if (aliases.all { it.tags == chosen.tags }) Certain(chosen) else Ambiguous(chosen)
        }

        private fun rank(job: JobSnapshot): Int? = when (job.state) {
            WorkInfo.State.RUNNING -> RUNNING
            // ENQUEUED after a run means a retry is pending — the reading observe() takes too.
            WorkInfo.State.ENQUEUED -> if (job.runAttemptCount > 0) RETRYING else QUEUED
            WorkInfo.State.BLOCKED -> QUEUED
            WorkInfo.State.SUCCEEDED -> if (job.outputPath != null && job.outputExists) RESULT else null
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> null
        }

        private const val RUNNING = 0
        private const val RETRYING = 1
        private const val QUEUED = 2
        private const val RESULT = 3
    }
}
