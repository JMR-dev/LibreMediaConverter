package org.libremediaconverter.work

import android.app.ForegroundServiceStartNotAllowedException
import androidx.work.WorkInfo

/**
 * Decides what a worker should do about a failure: try again, give up, or report it.
 *
 * A pure function rather than a branch inside the worker, because none of the cases that matter
 * can be provoked in a test. The foreground-service budget is six hours per twenty-four, and no
 * test is going to exhaust it. A refused foreground-service start needs a process death, a
 * WorkManager recovery and a real system to do the refusing. Isolating the decision means the
 * rules themselves can still be verified on the JVM, even though the conditions that trigger them
 * in production cannot be reproduced.
 */
enum class FailureOutcome {
    /** Not a real failure — the work is still valid, so try later. */
    RETRY,

    /**
     * The system would not let the job start, and has refused often enough that another retry
     * would only postpone the same answer.
     *
     * Distinct from [FAIL] because nothing about the *job* is wrong: the file is fine, the settings
     * are fine, and running the same job with the app open would work. What the user needs is that
     * instruction, not "conversion failed", which is why the message comes from here rather than
     * from the exception.
     */
    FOREGROUND_DENIED,

    /** A genuine failure; report it to the user. */
    FAIL,

    ;

    companion object {

        /**
         * What to tell the user once a denied start has stopped being worth retrying.
         *
         * Deliberately actionable rather than descriptive. The single thing that grants an app
         * permission to start a foreground service is being in the foreground, so "open the app"
         * is not filler — it is the fix.
         */
        const val FOREGROUND_DENIED_MESSAGE: String =
            "Android would not let this run in the background. Open the app and start it again."

        /**
         * How many attempts a denied foreground start gets before the job is failed.
         *
         * Retrying is right — the denial says *not now*, and the allowance arrives the moment the
         * user next opens the app — but unbounded retrying is not. WorkManager never gives up on
         * its own, so a job nobody comes back for would sit in the queue waking the device forever
         * while the screen said "paused" and never explained itself.
         *
         * Ten, against the default backoff rather than against a round number. Backoff is
         * exponential from `WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS` (30 s), doubling per attempt
         * and clamped at `MAX_BACKOFF_MILLIS` (5 h), so ten attempts span
         * 30 s + 1 m + 2 m + … + 4 h 16 m ≈ **8 h 30 m** — long enough to cover a normal day's
         * gap between opening the app.
         *
         * What giving up buys is bounded, and worth stating rather than assuming. The message is
         * carried on a FAILED job, and [Reattachment] excludes FAILED, so a user who was not
         * watching when the eleventh attempt ran will find an empty screen rather than the
         * explanation. What the bound reliably buys is the *end* of the retrying: no job waking
         * the device every five hours for a device state that is not going to change on its own.
         *
         * The counter is [androidx.work.ListenableWorker.getRunAttemptCount], which counts *every*
         * attempt, not only denied ones — WorkManager exposes no other. So a very long transcode
         * that has already been retried ten times by the foreground-service budget will fail on its
         * first denial rather than getting ten of its own. That is accepted rather than overlooked:
         * separating the two would mean persisting a counter of our own, and a job that has already
         * been attempted ten times has had its chances by any measure. The budget's own retries are
         * unaffected — see the timeout branch in [forFailure], which ignores the count entirely.
         */
        const val MAX_FOREGROUND_START_ATTEMPTS: Int = 10

        /**
         * @param stopReason [androidx.work.ListenableWorker.getStopReason], which reports what (if
         *   anything) asked the worker to stop.
         * @param cause the exception that ended the attempt, when there was one.
         * @param runAttemptCount how many times this job has already run.
         */
        fun forFailure(stopReason: Int, cause: Throwable? = null, runAttemptCount: Int = 0): FailureOutcome = when {
            // `mediaProcessing` allows six hours out of every twenty-four, shared across the app.
            // When that runs out the right response is to retry later rather than tell the user
            // the conversion failed -- the work is still valid, there is simply no budget now.
            stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> RETRY

            // Something else asked the worker to stop, so whatever exception it was holding at the
            // time describes that stop rather than a reason of its own. Retrying past a
            // cancellation would ignore the user; retrying past a constraint would spin.
            stopReason != WorkInfo.STOP_REASON_NOT_STOPPED -> FAIL

            // Matched on the exact class, never on its supertype. It extends IllegalStateException,
            // and so do plenty of ordinary failures from the muxers and the platform extractor --
            // catching the supertype would retry every one of them for eight hours.
            cause is ForegroundServiceStartNotAllowedException ->
                if (runAttemptCount < MAX_FOREGROUND_START_ATTEMPTS) RETRY else FOREGROUND_DENIED

            else -> FAIL
        }
    }
}
