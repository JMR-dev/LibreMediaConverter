package org.libremediaconverter.work

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The retry-versus-fail rule.
 *
 * Isolated from the worker precisely so it can be tested: the condition that triggers
 * a retry in production is the foreground-service budget running out, six hours per
 * twenty-four, which no test can reach. Extracting the decision means the rule is still
 * verified even though its trigger cannot be reproduced.
 */
class FailureOutcomeTest {

    @Test
    fun `a foreground service timeout is a retry, not a failure`() {
        // The work is still valid; there is simply no budget right now. Telling the
        // user their conversion failed would be wrong.
        assertEquals(
            FailureOutcome.RETRY,
            FailureOutcome.forStopReason(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT),
        )
    }

    @Test
    fun `an ordinary failure is reported as a failure`() {
        assertEquals(
            FailureOutcome.FAIL,
            FailureOutcome.forStopReason(WorkInfo.STOP_REASON_NOT_STOPPED),
        )
    }

    @Test
    fun `every other stop reason fails rather than retrying forever`() {
        // Retrying on, say, a user cancellation or a battery constraint would either
        // ignore the user or spin. Only the timeout earns a retry.
        val others = listOf(
            WorkInfo.STOP_REASON_CANCELLED_BY_APP,
            WorkInfo.STOP_REASON_USER,
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE,
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW,
            WorkInfo.STOP_REASON_DEVICE_STATE,
            WorkInfo.STOP_REASON_QUOTA,
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_APP_STANDBY,
            WorkInfo.STOP_REASON_TIMEOUT,
            WorkInfo.STOP_REASON_UNKNOWN,
        )
        others.forEach {
            assertEquals("stop reason $it should fail", FailureOutcome.FAIL, FailureOutcome.forStopReason(it))
        }
    }
}
