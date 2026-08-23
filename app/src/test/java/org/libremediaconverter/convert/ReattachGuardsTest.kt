package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The two decisions `reattach()` makes that [org.libremediaconverter.work.Reattachment] cannot.
 *
 * `Reattachment.choose` answers "which job", and twenty tests pin it. What it does not decide is
 * whether the answer may still be used by the time it arrives, or how much of it the card is
 * allowed to believe — and both of those live in the ViewModel, where nothing was asserting them.
 * Deleting either guard left the whole suite green.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ReattachGuardsTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var workManager: WorkManager
    private lateinit var staged: File
    private lateinit var queries: HoldableTaskExecutor

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }

        staged = publisher.createStagingFile("holiday_converted.mp4").apply { writeBytes(ByteArray(4096)) }
        queries = HoldableTaskExecutor()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ASSERT)
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(queries)
                .setWorkerFactory(
                    SucceedingWorkerFactory(workDataOf(ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath)),
                )
                .build(),
        )
        workManager = WorkManager.getInstance(app)
    }

    @After
    fun tearDown() {
        queries.release()
        ConversionDependencies.reset()
    }

    /**
     * The race the guard exists for: the tag query suspends, and while it is away the user picks a
     * file of their own. Reattaching over that would throw away what they just did — and, worse,
     * point the Save button at yesterday's file while the card named today's.
     *
     * Made deterministic by holding WorkManager's task executor rather than by hoping the pick wins:
     * the query cannot complete until this test lets it, so the pick has landed before the guard is
     * ever reached.
     */
    @Test
    fun `a file picked while the query was in flight is not reattached over`() {
        finishAConversionWithNobodyWatching()
        val picked = File(app.cacheDir, "beach.mp4").apply { writeBytes(ByteArray(2048)) }

        queries.hold()
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(Uri.fromFile(picked))
        val ready = awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }
        // The URI, because that is what tells the two inputs apart: a reattached job's is
        // Uri.EMPTY -- WorkManager never hands back the Data a request was enqueued with -- while a
        // picked file's is the one the picker returned.
        assertEquals(Uri.fromFile(picked), (ready as ConversionState.Ready).input.uri)

        queries.release()
        settle()

        // The query really did run and really did reach the guard -- without this the assertion
        // below would pass just as well against a reattachment that never arrived.
        assertTrue("the reattach query should have been held, then run", queries.heldTasks > 0)
        val current = viewModel.state.value
        assertTrue("the user's pick must survive a late reattachment, got $current", current is ConversionState.Ready)
        assertEquals(Uri.fromFile(picked), (current as ConversionState.Ready).input.uri)
        assertEquals(2_048L, current.input.sizeBytes)
    }

    /**
     * Two finished jobs naming one staged file, which is exactly what the device produced before
     * staging was keyed on the job id.
     *
     * The file is the user's either way, so it is still offered. Which job wrote it is not
     * knowable, so the card must not borrow either job's input name: a card labelled with the other
     * conversion's file is a confident lie, where a neutral label is merely thin.
     */
    @Test
    fun `a result two jobs both claim is offered without being attributed to either`() {
        finishAConversionWithNobodyWatching(displayName = "holiday.mp4")
        finishAConversionWithNobodyWatching(displayName = "beach.mp4")

        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        val converted = awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }

        converted as ConversionState.Converted
        assertEquals(
            "the bytes on disk are what the user gets back",
            staged.absolutePath,
            converted.staged.absolutePath,
        )
        assertEquals(
            "neither job's name may be claimed for the other's file",
            "Media file",
            converted.input.displayName,
        )
        // The size travels in the same tags as the name, so it goes the same way rather than being
        // reported as one job's number against the other job's file.
        assertEquals(null, converted.input.sizeBytes)
    }

    /** Pumps the main looper for long enough that anything already dispatched has run. */
    private fun settle() {
        repeat(SETTLE_PUMPS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(SETTLE_INTERVAL_MS)
        }
    }

    private fun finishAConversionWithNobodyWatching(displayName: String = "holiday.mp4") {
        workManager.enqueue(
            ConversionWorker.request(
                inputUri = Uri.parse("content://test/$displayName"),
                displayName = displayName,
                sizeBytes = 4_096L,
            ),
        ).result.get()
    }

    private companion object {
        const val SETTLE_PUMPS = 60
        const val SETTLE_INTERVAL_MS = 5L
    }
}

/**
 * WorkManager's task executor, with a brake the test can apply.
 *
 * The reattachment query is a suspending call the ViewModel makes in `init`, so a test that wants
 * to act "while it is in flight" has to be able to stop it finishing. Holding the executor it runs
 * on is the only seam for that: `jobSnapshots` takes no dispatcher, and racing it would make the
 * assertion depend on which of two IO hops happened to return first.
 *
 * Never applied on the main thread. The test releases the brake from there, so a wait taken on that
 * thread would deadlock the loop that was going to end it. The wait is bounded for the same class of
 * reason: a wiring mistake should turn the test red, not hang the build.
 */
private class HoldableTaskExecutor : Executor {

    private val released = CountDownLatch(1)

    @Volatile
    private var holding = false

    /** How many tasks were actually held. Zero means the brake never gripped anything. */
    @Volatile
    var heldTasks = 0
        private set

    fun hold() {
        holding = true
    }

    fun release() {
        holding = false
        released.countDown()
    }

    override fun execute(command: Runnable) {
        if (holding && Looper.myLooper() != Looper.getMainLooper()) {
            heldTasks++
            check(released.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "a held WorkManager task was never released"
            }
        }
        command.run()
    }

    private companion object {
        const val HOLD_TIMEOUT_SECONDS = 10L
    }
}
