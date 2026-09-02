package org.libremediaconverter.convert

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.join.JoinState
import org.libremediaconverter.join.JoinViewModel
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.work.ConcatWorker
import org.libremediaconverter.work.ConversionWorker
import org.libremediaconverter.work.JobTags
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * That `cancel()` cancels the job, on both screens.
 *
 * ## Why this was missing, which is the interesting part
 *
 * Both `cancel()` methods are one line — `activeWorkId?.let(workManager::cancelWorkById)` — and
 * **JaCoCo reports every line of both as covered**. `SettingsEditsTest`'s
 * `cancelling with no active job does nothing rather than throwing` runs the method, and its own
 * comment names which half it drives: "`activeWorkId?.let(...)` -- the null side". The other side
 * had never been entered, and `JoinViewModel.cancel()` had no test at all.
 *
 * So no line-level coverage filter could see this. What surfaces it is a method-level read —
 * `mi=11, ci=7, mb=1, cb=1` on both — a covered method with an arm nothing takes. That is the
 * second of the two filters #194 records, and this is the gap that argued for it.
 *
 * The affordance tests are not this. `ConverterStateAffordancesTest` and `JoinStateAffordancesTest`
 * click `TestTags.CANCEL` and assert the *action* fires into a stub; `ScreenWiringTest` asserts the
 * action calls `viewModel.cancel()`. Both halves were pinned and the join between them was not, so
 * nothing in 584 tests connected the button to WorkManager.
 *
 * ## Why the job is enqueued with a delay
 *
 * The test WorkManager runs on a `SynchronousExecutor`, so an ordinary request finishes inline —
 * which is exactly why only the null half was ever covered: by the time a test could call
 * `cancel()`, `convert()`'s job was already terminal. `setInitialDelay` is what `TestScheduler`
 * honours, so the job sits in `ENQUEUED` until the test lets it go, and it never does.
 *
 * **Production never sets a delay**, so the request is built here rather than through
 * `ConversionWorker.request`. The *state* is not synthetic: `ENQUEUED` at `runAttemptCount == 0` is
 * what every job passes through before the scheduler picks it up, `Reattachment.choose` ranks it
 * `QUEUED`, and `conversionStateFrom` maps it to `Converting(input, 0)`. The delay changes how long
 * the job stays in a real state, not which state it is in.
 *
 * ## What is asserted, and in which order
 *
 * WorkManager's own record first, then the screen. The screen alone would be a weaker claim than it
 * looks: `CANCELLED` maps to `Idle` for a reattached job, and `Idle` is also where a ViewModel that
 * did nothing at all would sit.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class CancelReachesWorkManagerTest {

    private lateinit var app: Application
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ConversionDependencies.publisher = { RecordingPublisher(app) }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        installTestWorkManager(app, workDataOf())
        workManager = WorkManager.getInstance(app)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `cancelling a queued conversion cancels that job`() {
        val id = enqueueQueuedConversion()
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        awaitState(viewModel.state, "Converting") { it is ConversionState.Converting }

        viewModel.cancel()

        assertEquals(
            "Cancel must reach WorkManager, not just the screen",
            WorkInfo.State.CANCELLED,
            stateOf(id),
        )
        awaitState(viewModel.state, "Idle") { it is ConversionState.Idle }
    }

    @Test
    fun `cancelling a queued join cancels that job`() {
        val id = enqueueQueuedJoin()
        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        awaitState(viewModel.state, "Joining") { it is JoinState.Joining }

        viewModel.cancel()

        assertEquals(
            "Cancel must reach WorkManager, not just the screen",
            WorkInfo.State.CANCELLED,
            stateOf(id),
        )
        awaitState(viewModel.state, "Idle") { it is JoinState.Idle }
    }

    /**
     * The negative that bounds both: cancelling must cancel the job the screen is showing, and only
     * that one.
     *
     * Without this, `cancel()` could cancel everything in the queue — `cancelAllWork()` in place of
     * `cancelWorkById(activeWorkId)` — and both tests above would still pass.
     */
    @Test
    fun `cancelling one conversion leaves another queued job alone`() {
        val bystander = enqueueQueuedConversion(displayName = "beach.mp4")
        val id = enqueueQueuedConversion(displayName = "holiday.mp4")
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        val converting = awaitState(viewModel.state, "Converting") { it is ConversionState.Converting }
        val onScreen = (converting as ConversionState.Converting).input.displayName

        viewModel.cancel()

        // Which of the two the ViewModel reattached to is the query's business, not this test's --
        // the comparator leaves queued jobs tied deliberately, per Reattachment's ordering notes.
        // So assert the shape rather than the identity: exactly one is cancelled, and the other is
        // untouched.
        val cancelled = listOf(id, bystander).filter { stateOf(it) == WorkInfo.State.CANCELLED }
        assertEquals(
            "exactly one job may be cancelled, with $onScreen on screen",
            1,
            cancelled.size,
        )
    }

    private fun stateOf(id: UUID): WorkInfo.State =
        requireNotNull(workManager.getWorkInfoById(id).get()) { "no WorkInfo for $id" }.state

    /**
     * A conversion sitting in the queue, which is where every job starts.
     *
     * Built by hand rather than through `ConversionWorker.request` for the reason in the class
     * KDoc; the display-name tag is included because `reattach()` reads it for the file card, and a
     * job without one would exercise the `UNKNOWN_INPUT_NAME` fallback instead of this test's
     * subject.
     */
    private fun enqueueQueuedConversion(displayName: String = "holiday.mp4"): UUID {
        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .addTag(JobTags.displayName(displayName))
            .setInitialDelay(QUEUE_HOLD_HOURS, TimeUnit.HOURS)
            .build()
        workManager.enqueue(request).result.get()
        return request.id
    }

    private fun enqueueQueuedJoin(inputCount: Int = 2): UUID {
        val request = OneTimeWorkRequestBuilder<ConcatWorker>()
            .addTag(JobTags.inputCount(inputCount))
            .setInitialDelay(QUEUE_HOLD_HOURS, TimeUnit.HOURS)
            .build()
        workManager.enqueue(request).result.get()
        return request.id
    }

    private companion object {
        /** Long enough that `TestScheduler` never releases the job during a test run. */
        const val QUEUE_HOLD_HOURS = 1L
    }
}
