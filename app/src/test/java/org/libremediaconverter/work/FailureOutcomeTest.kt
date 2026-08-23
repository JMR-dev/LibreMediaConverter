package org.libremediaconverter.work

import android.app.ForegroundServiceStartNotAllowedException
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The retry-versus-fail rule.
 *
 * Isolated from the worker precisely so it can be tested: neither condition that triggers a retry
 * in production is reproducible. One is the foreground-service budget running out, six hours per
 * twenty-four, which no test can reach. The other is the system refusing a background
 * foreground-service start, which needs a process death and a WorkManager recovery on a real
 * device. Extracting the decision means the rule is still verified even though its triggers are
 * not.
 *
 * Robolectric only for [ForegroundServiceStartNotAllowedException]: it is a platform class, and
 * the stub `android.jar` the JVM tests compile against throws from every constructor. Nothing else
 * here needs an Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
class FailureOutcomeTest {

    @Test
    fun `a foreground service timeout is a retry, not a failure`() {
        // The work is still valid; there is simply no budget right now. Telling the
        // user their conversion failed would be wrong.
        assertEquals(
            FailureOutcome.RETRY,
            FailureOutcome.forFailure(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT),
        )
    }

    @Test
    fun `an ordinary failure is reported as a failure`() {
        assertEquals(
            FailureOutcome.FAIL,
            FailureOutcome.forFailure(WorkInfo.STOP_REASON_NOT_STOPPED),
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
            assertEquals("stop reason $it should fail", FailureOutcome.FAIL, FailureOutcome.forFailure(it))
        }
    }

    // --- a refused foreground-service start ---------------------------------------------------

    @Test
    fun `a denied foreground start is a retry, not a terminal failure`() {
        // The device pass caught this returning FAILURE with reschedule = false, which loses an
        // hour of transcoding to a condition that clears the moment the user opens the app.
        assertEquals(
            FailureOutcome.RETRY,
            FailureOutcome.forFailure(WorkInfo.STOP_REASON_NOT_STOPPED, denied(), runAttemptCount = 0),
        )
    }

    @Test
    fun `a denied foreground start keeps retrying up to the bound`() {
        (0 until FailureOutcome.MAX_FOREGROUND_START_ATTEMPTS).forEach { attempt ->
            assertEquals(
                "attempt $attempt should still retry",
                FailureOutcome.RETRY,
                FailureOutcome.forFailure(WorkInfo.STOP_REASON_NOT_STOPPED, denied(), attempt),
            )
        }
    }

    @Test
    fun `a denied foreground start gives up once the bound is reached`() {
        // The alternative is a job that is never told to stop and never tells the user anything:
        // WorkManager retries forever, and the screen says "paused" for as long as the app lives.
        assertEquals(
            FailureOutcome.FOREGROUND_DENIED,
            FailureOutcome.forFailure(
                WorkInfo.STOP_REASON_NOT_STOPPED,
                denied(),
                FailureOutcome.MAX_FOREGROUND_START_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `an unrelated IllegalStateException is not mistaken for a denied start`() {
        // ForegroundServiceStartNotAllowedException extends IllegalStateException, and plenty of
        // ordinary failures are IllegalStateExceptions -- a muxer that was never started, a
        // provider that closed. Matching the supertype would retry all of them forever.
        assertEquals(
            FailureOutcome.FAIL,
            FailureOutcome.forFailure(
                WorkInfo.STOP_REASON_NOT_STOPPED,
                IllegalStateException("muxer was not started"),
                runAttemptCount = 0,
            ),
        )
    }

    @Test
    fun `a stop the system asked for wins over the exception it caused`() {
        // Precedence, stated rather than left to fall out of the branch order. Once something
        // stopped the worker, the exception it was holding at the time describes the stop, not a
        // reason of its own -- and retrying past a cancellation would ignore the user.
        assertEquals(
            FailureOutcome.FAIL,
            FailureOutcome.forFailure(WorkInfo.STOP_REASON_CANCELLED_BY_APP, denied(), runAttemptCount = 0),
        )
    }

    @Test
    fun `the foreground budget still earns a retry however many attempts have been made`() {
        // The bound belongs to the denial, not to the timeout: a six-hour transcode legitimately
        // outlives more than ten daily budgets, and failing it for that would be the opposite of
        // what the timeout branch exists for.
        assertEquals(
            FailureOutcome.RETRY,
            FailureOutcome.forFailure(
                WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT,
                denied(),
                FailureOutcome.MAX_FOREGROUND_START_ATTEMPTS * 2,
            ),
        )
    }

    private fun denied() = ForegroundServiceStartNotAllowedException(
        "startForegroundService() not allowed: service " +
            "org.libremediaconverter/androidx.work.impl.foreground.SystemForegroundService",
    )
}
