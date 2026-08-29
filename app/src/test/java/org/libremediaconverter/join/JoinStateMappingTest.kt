package org.libremediaconverter.join

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.InputFile
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.work.ConcatWorker
import org.robolectric.RobolectricTestRunner

/**
 * Every answer [joinStateFrom] can give, chosen rather than stumbled into.
 *
 * The join-side twin of `ConversionStateMappingTest`, and the argument is the same one: the mapping
 * ran on every test that drove a real `ConcatWorker`, but a real worker only ever reaches a terminal
 * state with well-formed output, so five arms had never been *chosen* by anything.
 *
 * ## The one that is not just coverage
 *
 * `an unknown strategy name is read as a re-encode rather than thrown` covers a real defect this
 * seam exposed. The line it replaces was:
 *
 * ```kotlin
 * .getString(ConcatWorker.KEY_STRATEGY)?.let(ConcatStrategy::valueOf) ?: ConcatStrategy.REENCODE
 * ```
 *
 * `valueOf` throws on a name this build does not define, and this runs inside a `viewModelScope`
 * collect with no handler — so it does not become a `Failed` state, it takes the process down.
 * `ConcatWorker.kt` had already made this exact change for `KEY_FORMAT` and written down why; the
 * matching read on this side had not been changed with it.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JoinStateMappingTest {

    @Test
    fun `a running join is joining`() {
        assertEquals(JoinState.Joining(INPUTS), map(WorkInfo.State.RUNNING))
    }

    @Test
    fun `a blocked join looks like one that is starting`() {
        // Folded into the RUNNING arm deliberately: a job waiting on a prerequisite is nothing the
        // user can act on, and a separate word for it would be noise.
        assertEquals(JoinState.Joining(INPUTS), map(WorkInfo.State.BLOCKED))
    }

    @Test
    fun `an enqueued join that has already run is waiting to retry`() {
        assertEquals(JoinState.Waiting(INPUTS), map(WorkInfo.State.ENQUEUED, runAttemptCount = 1))
    }

    @Test
    fun `an enqueued join that has never run is simply starting`() {
        // The other side. Without it, a mapping that ignored runAttemptCount passes the test above.
        assertEquals(JoinState.Joining(INPUTS), map(WorkInfo.State.ENQUEUED, runAttemptCount = 0))
    }

    @Test
    fun `a success that named no file is a failure, not an empty success`() {
        assertEquals(
            JoinState.Failed(JOINED_WITHOUT_A_FILE_MESSAGE),
            map(WorkInfo.State.SUCCEEDED, data = Data.EMPTY),
        )
    }

    @Test
    fun `a success carries the strategy the worker actually used`() {
        // Not cosmetic: the join screen tells the user whether their files were stream-copied or
        // re-encoded, which is the difference between lossless and lossy.
        val joined = map(
            WorkInfo.State.SUCCEEDED,
            data = workDataOf(
                ConcatWorker.KEY_OUTPUT_PATH to "/cache/conversions/joined.mp4",
                ConcatWorker.KEY_STRATEGY to ConcatStrategy.STREAM_COPY.name,
            ),
        ) as JoinState.Joined

        assertEquals(ConcatStrategy.STREAM_COPY, joined.strategy)
    }

    @Test
    fun `an unknown strategy name is read as a re-encode rather than thrown`() {
        // The defect. A build that added a third strategy leaves finished joins in the queue naming
        // it, and WorkManager keeps those about a week -- the premise WorkerEnumFallbackTest and
        // JobTags are both written on. With `valueOf` this throws IllegalArgumentException inside a
        // viewModelScope collect that has no handler, so it is not a Failed state, it is a crash.
        //
        // REENCODE rather than STREAM_COPY because it is the conservative answer: describing an
        // unknown join as lossless would be a claim the app cannot support.
        val joined = map(
            WorkInfo.State.SUCCEEDED,
            data = workDataOf(
                ConcatWorker.KEY_OUTPUT_PATH to "/cache/conversions/joined.mp4",
                ConcatWorker.KEY_STRATEGY to "SMART_CONCAT_V2",
            ),
        ) as JoinState.Joined

        assertEquals(ConcatStrategy.REENCODE, joined.strategy)
    }

    @Test
    fun `a success with no strategy at all falls back the same way`() {
        val joined = map(
            WorkInfo.State.SUCCEEDED,
            data = workDataOf(ConcatWorker.KEY_OUTPUT_PATH to "/cache/conversions/joined.mp4"),
        ) as JoinState.Joined

        assertEquals(ConcatStrategy.REENCODE, joined.strategy)
    }

    @Test
    fun `a success from older work falls back to the format such a job really used`() {
        val joined = map(
            WorkInfo.State.SUCCEEDED,
            data = workDataOf(ConcatWorker.KEY_OUTPUT_PATH to "/cache/conversions/joined.mp4"),
        ) as JoinState.Joined

        assertEquals(ConcatWorker.outputNameFor(ConcatWorker.DEFAULT_FORMAT), joined.suggestedName)
        assertEquals(ConcatWorker.DEFAULT_FORMAT.mimeType, joined.mimeType)
    }

    @Test
    fun `a blank name or type falls back the same way a missing one does`() {
        val joined = map(
            WorkInfo.State.SUCCEEDED,
            data = workDataOf(
                ConcatWorker.KEY_OUTPUT_PATH to "/cache/conversions/joined.mp4",
                ConcatWorker.KEY_SUGGESTED_NAME to "",
                ConcatWorker.KEY_MIME_TYPE to "  ",
            ),
        ) as JoinState.Joined

        assertEquals(ConcatWorker.outputNameFor(ConcatWorker.DEFAULT_FORMAT), joined.suggestedName)
        assertEquals(ConcatWorker.DEFAULT_FORMAT.mimeType, joined.mimeType)
    }

    @Test
    fun `a failure carries the reason the worker gave`() {
        assertEquals(
            JoinState.Failed("Not enough free space to join these files."),
            map(
                WorkInfo.State.FAILED,
                data = workDataOf(
                    ConcatWorker.KEY_ERROR to "Not enough free space to join these files.",
                ),
            ),
        )
    }

    @Test
    fun `a failure with nothing said still says something`() {
        assertEquals(
            JoinState.Failed(ConcatWorker.GENERIC_FAILURE_MESSAGE),
            map(WorkInfo.State.FAILED, data = Data.EMPTY),
        )
    }

    @Test
    fun `a failure whose message is blank falls back like a missing one`() {
        assertEquals(
            JoinState.Failed(ConcatWorker.GENERIC_FAILURE_MESSAGE),
            map(WorkInfo.State.FAILED, data = workDataOf(ConcatWorker.KEY_ERROR to " ")),
        )
    }

    @Test
    fun `a cancellation lands wherever the caller said it should`() {
        // A join started here goes back to Ready with the picked files; one picked up by reattach
        // goes to Idle, because those URIs belong to a process that no longer exists.
        assertEquals(
            JoinState.Ready(INPUTS),
            map(WorkInfo.State.CANCELLED, cancelled = JoinState.Ready(INPUTS)),
        )
        assertEquals(JoinState.Idle, map(WorkInfo.State.CANCELLED, cancelled = JoinState.Idle))
    }

    private fun map(
        state: WorkInfo.State,
        runAttemptCount: Int = 0,
        data: Data = Data.EMPTY,
        cancelled: JoinState = JoinState.Ready(INPUTS),
    ): JoinState = joinStateFrom(
        JoinUpdate(state = state, runAttemptCount = runAttemptCount, outputData = data),
        inputs = INPUTS,
        cancelled = cancelled,
    )

    private companion object {
        val INPUTS = listOf(
            InputFile(Uri.parse("content://test/a.mp4"), "a.mp4", 1024L),
            InputFile(Uri.parse("content://test/b.mp4"), "b.mp4", 2048L),
        )
    }
}
