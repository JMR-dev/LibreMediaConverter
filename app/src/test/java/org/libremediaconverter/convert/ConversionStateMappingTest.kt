package org.libremediaconverter.convert

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner

/**
 * Every answer [conversionStateFrom] can give, chosen rather than stumbled into.
 *
 * ## What this revises
 *
 * The mapping is not cold code and never was: `ConversionViewModel$observe$1$1` reported 28 covered
 * lines before this file existed, because every test that drives a real worker runs it. What no
 * test did was **choose which arm it took**. A real worker reaches a terminal state with
 * well-formed output, so `SUCCEEDED`-with-a-path and `FAILED`-with-a-message were the only arms any
 * test had ever produced — the other six ran never.
 *
 * A `grep` for `WorkInfo.State.` across the JVM suite makes that look untrue: all six constants are
 * there. They are in `ReattachmentTest`, driven into **`Reattachment.choose`** — a different
 * function that encodes the same enqueued-means-retry rule. So that rule had a test in one of its
 * two homes, and the copy the user's screen reads had none.
 *
 * ## Why the seam, and why these assertions
 *
 * `WorkManager.getInstance` is called in the ViewModel's constructor and `observe` is private, so
 * nothing could hand this a chosen `WorkInfo`. Cutting the `when` out as a pure function over
 * [ConversionUpdate] is the answer #141 took for `MediaProbe`, and `JobSnapshot` beside
 * `Reattachment.choose` is the same shape again.
 *
 * The assertions are on the whole state, not on its type. `Converting(input, 40)` and
 * `Converting(input, 0)` are both `Converting`, and a mapping that dropped the progress read would
 * pass any test that only asked which class came back.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConversionStateMappingTest {

    // --- running ------------------------------------------------------------

    @Test
    fun `a running job reports the progress it published`() {
        // The percent is read from `progress`, not from `outputData`, and not from the settings.
        // A mapping that returned Converting(input, 0) for every RUNNING would leave the bar
        // pinned at zero for the whole conversion.
        val state = map(WorkInfo.State.RUNNING, progress = 40)

        assertEquals(ConversionState.Converting(INPUT, 40), state)
    }

    @Test
    fun `a running job with no published progress reports zero rather than failing`() {
        // getInt's default. A worker that has started but not yet called setProgress is ordinary,
        // and must not read as an error.
        val state = map(WorkInfo.State.RUNNING, progress = null)

        assertEquals(ConversionState.Converting(INPUT, 0), state)
    }

    // --- enqueued: the rule that had a test only in its other home ----------

    @Test
    fun `an enqueued job that has already run is waiting to retry`() {
        val state = map(WorkInfo.State.ENQUEUED, runAttemptCount = 1)

        assertEquals(
            "an ENQUEUED after a run is a pending retry, which the user is told about",
            ConversionState.Waiting(INPUT),
            state,
        )
    }

    @Test
    fun `an enqueued job that has never run is simply starting`() {
        // The other side, and the reason the test above is not enough on its own: a mapping that
        // ignored runAttemptCount and always answered Waiting would pass that one and fail this.
        val state = map(WorkInfo.State.ENQUEUED, runAttemptCount = 0)

        assertEquals(ConversionState.Converting(INPUT, 0), state)
    }

    // --- succeeded ----------------------------------------------------------

    @Test
    fun `a success that named no file is a failure, not an empty success`() {
        // The job said it finished and named nothing. There is no file to offer, so `Converted`
        // would put a Save button over a path that does not exist.
        val state = map(WorkInfo.State.SUCCEEDED, data = Data.EMPTY)

        assertEquals(ConversionState.Failed(SUCCEEDED_WITHOUT_A_FILE_MESSAGE), state)
    }

    @Test
    fun `a success carries the worker's own name and type, not the current settings`() {
        val state = map(
            WorkInfo.State.SUCCEEDED,
            data = workDataOf(
                ConversionWorker.KEY_OUTPUT_PATH to "/cache/conversions/out.mkv",
                ConversionWorker.KEY_SUGGESTED_NAME to "holiday.mkv",
                ConversionWorker.KEY_MIME_TYPE to "video/x-matroska",
                ConversionWorker.KEY_ENGINE_USED to "FFMPEG",
                ConversionWorker.KEY_ROUTE_REASON to "container needs FFmpeg",
            ),
        )

        val converted = state as ConversionState.Converted
        assertEquals("holiday.mkv", converted.suggestedName)
        assertEquals("video/x-matroska", converted.mimeType)
        assertEquals("FFMPEG", converted.engineUsed)
        assertEquals("container needs FFmpeg", converted.routeReason)
    }

    @Test
    fun `a success from older work falls back to the current settings for name and type`() {
        // WorkManager keeps finished work about a week, so a job enqueued before the worker
        // reported these is ordinary for a few days rather than a corner case.
        val state = map(
            WorkInfo.State.SUCCEEDED,
            data = workDataOf(ConversionWorker.KEY_OUTPUT_PATH to "/cache/conversions/out.mp4"),
        )

        val converted = state as ConversionState.Converted
        assertEquals(FALLBACK_SPEC.mimeType, converted.mimeType)
        assertEquals(
            ConversionWorker.outputNameFor(INPUT.displayName, FALLBACK_SPEC),
            converted.suggestedName,
        )
    }

    @Test
    fun `a blank name or type falls back the same way a missing one does`() {
        // A blank string is not an answer. Without takeIf, the save dialog opens named "" and
        // registered for a MIME type of "", which no provider will accept.
        val state = map(
            WorkInfo.State.SUCCEEDED,
            data = workDataOf(
                ConversionWorker.KEY_OUTPUT_PATH to "/cache/conversions/out.mp4",
                ConversionWorker.KEY_SUGGESTED_NAME to "",
                ConversionWorker.KEY_MIME_TYPE to "   ",
            ),
        )

        val converted = state as ConversionState.Converted
        assertEquals(FALLBACK_SPEC.mimeType, converted.mimeType)
        assertEquals(
            ConversionWorker.outputNameFor(INPUT.displayName, FALLBACK_SPEC),
            converted.suggestedName,
        )
    }

    // --- failed -------------------------------------------------------------

    @Test
    fun `a failure carries the reason the worker gave`() {
        val state = map(
            WorkInfo.State.FAILED,
            data = workDataOf(ConversionWorker.KEY_ERROR to "Not enough free space to convert."),
        )

        assertEquals(ConversionState.Failed("Not enough free space to convert."), state)
    }

    @Test
    fun `a failure with nothing said still says something`() {
        // A worker killed before it could write output data leaves none at all -- a refused
        // foreground start after a process restart is one way. Failed("") would render as a blank
        // error card.
        val state = map(WorkInfo.State.FAILED, data = Data.EMPTY)

        assertEquals(ConversionState.Failed(ConversionWorker.GENERIC_FAILURE_MESSAGE), state)
    }

    @Test
    fun `a failure whose message is blank falls back like a missing one`() {
        val state = map(
            WorkInfo.State.FAILED,
            data = workDataOf(ConversionWorker.KEY_ERROR to "   "),
        )

        assertEquals(ConversionState.Failed(ConversionWorker.GENERIC_FAILURE_MESSAGE), state)
    }

    // --- cancelled and blocked ---------------------------------------------

    @Test
    fun `a cancellation lands wherever the caller said it should`() {
        // Not a fixed state: a conversion started here goes back to Ready with the picked file,
        // while one picked up by reattach goes to Idle, because the URI that job holds belongs to
        // a process that no longer exists. `observe`'s KDoc is where that distinction is set.
        val toReady = map(WorkInfo.State.CANCELLED, cancelled = ConversionState.Ready(INPUT))
        val toIdle = map(WorkInfo.State.CANCELLED, cancelled = ConversionState.Idle)

        assertEquals(ConversionState.Ready(INPUT), toReady)
        assertEquals(ConversionState.Idle, toIdle)
    }

    @Test
    fun `a blocked job looks like one that is starting`() {
        // BLOCKED is a job waiting on a prerequisite. There is nothing useful to say about it that
        // differs from "starting", and inventing a state for it would put a word on screen the
        // user cannot act on.
        val state = map(WorkInfo.State.BLOCKED)

        assertEquals(ConversionState.Converting(INPUT, 0), state)
    }

    private fun map(
        state: WorkInfo.State,
        progress: Int? = null,
        runAttemptCount: Int = 0,
        data: Data = Data.EMPTY,
        cancelled: ConversionState = ConversionState.Ready(INPUT),
    ): ConversionState = conversionStateFrom(
        ConversionUpdate(
            state = state,
            // Modelled on the call site, which reads `getInt(KEY_PROGRESS, 0)` -- so "no progress
            // published" is the default reaching the mapping, not a null it has to handle.
            progressPercent = progress ?: 0,
            runAttemptCount = runAttemptCount,
            outputData = data,
        ),
        input = INPUT,
        cancelled = cancelled,
        fallbackSpec = FALLBACK_SPEC,
    )

    private companion object {
        val INPUT = InputFile(Uri.parse("content://test/holiday.mov"), "holiday.mov", 4096L)
        val FALLBACK_SPEC = OutputFormat.MP4_H265.spec
    }
}
