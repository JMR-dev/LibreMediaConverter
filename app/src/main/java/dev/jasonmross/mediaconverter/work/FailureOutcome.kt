package dev.jasonmross.mediaconverter.work

import androidx.work.WorkInfo

/**
 * Decides whether a failed job should be retried or reported as failed.
 *
 * A pure function rather than a branch inside the worker, because the case that matters
 * cannot be provoked in a test: the foreground-service budget is six hours per
 * twenty-four, and no test is going to exhaust it. Isolating the decision means the
 * rule itself can still be verified on the JVM, even though the condition that triggers
 * it in production cannot be reproduced.
 */
enum class FailureOutcome {
    /** Budget exhausted, not a real failure — the work is still valid, so try later. */
    RETRY,

    /** A genuine failure; report it to the user. */
    FAIL;

    companion object {
        fun forStopReason(stopReason: Int): FailureOutcome =
            if (stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) RETRY else FAIL
    }
}
