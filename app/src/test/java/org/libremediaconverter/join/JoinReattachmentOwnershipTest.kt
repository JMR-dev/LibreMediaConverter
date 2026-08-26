package org.libremediaconverter.join

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.ParkedPickDispatcher
import org.libremediaconverter.convert.RecordingPublisher
import org.libremediaconverter.convert.awaitState
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.work.ConcatWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Issue #49 on the join side, where nothing was watching for it.
 *
 * `reattach()` checks that the screen is still free, and then hands the answer to `observe()`,
 * which writes from a *different* coroutine that has to suspend on `collect` before it can write
 * anything at all. So the check happens at one moment and the write lands at another, with a
 * whole pick able to fit in between:
 *
 * 1. `init` starts the tag query and suspends in it.
 * 2. The user picks files; `onInputsPicked` suspends in its metadata query.
 * 3. The query comes back. The screen is still `Idle` — step 2 has not written yet — so the
 *    guard passes and an observation of the old job is launched.
 * 4. The pick lands. `Ready(picked)`. The user owns the screen.
 * 5. The observation's first `WorkInfo` arrives and writes `Joined(yesterday's file)` over it.
 *
 * The comment above that guard used to say "no suspension point between this check and the
 * assignment below, so nothing can interleave". There is no assignment below, and the two lines
 * are in different coroutines.
 *
 * The convert side has been failing this on CI for two days — four occurrences across three API
 * levels, each read as flaky infrastructure. `JoinViewModel` has the identical shape and no test
 * at all, which is why this one was written before the fix rather than after it.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JoinReattachmentOwnershipTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var workManager: WorkManager
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }

        staged = publisher.createStagingFile("joined-yesterday.mp4").apply { writeBytes(ByteArray(4096)) }
        installTestWorkManager(
            app,
            workDataOf(
                ConcatWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConcatWorker.KEY_STRATEGY to ConcatStrategy.STREAM_COPY.name,
            ),
        )
        workManager = WorkManager.getInstance(app)
        // The situation reattachment exists for: a join that finished in a process that is gone,
        // with its output still in the cache and nothing in the UI holding its id.
        workManager.enqueue(
            ConcatWorker.request(
                inputs = listOf(
                    Uri.parse("content://test/yesterday-a.mp4"),
                    Uri.parse("content://test/yesterday-b.mp4"),
                ),
                totalBytes = 8_192L,
            ),
        ).result.get()
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    /**
     * The race, made into a state the test can sit in rather than one it has to catch.
     *
     * The pick is parked on a dispatcher this test owns, so it is in flight — issued, not yet
     * written — for as long as the assertions need it to be. Everything else is real: a real
     * `WorkManager` holding a real finished job, the production `reattach`, the production
     * `observe`.
     *
     * Determinism comes from where Robolectric leaves the main looper. `reattach`'s tag query
     * hops to a real [kotlinx.coroutines.Dispatchers.IO] thread, so its continuation can only
     * come back as a message posted to the main looper — and that looper is paused, so it cannot
     * run until something pumps it. `onInputsPicked` is an ordinary synchronous call from this
     * thread. The pick is therefore always issued before the guard runs, with nothing left to
     * timing.
     */
    @Test
    fun `a join found while the user was picking never reaches the screen`() {
        val parkedPick = ParkedPickDispatcher()
        val viewModel = JoinViewModel(app, pickDispatcher = parkedPick)
        viewModel.onInputsPicked(PICKED)

        assertEquals(
            "the pick must still be in flight, or this proves something about a different situation",
            1,
            parkedPick.parkedCount,
        )

        // The control, and the reason this test does not rest on a settle window being long
        // enough. A second ViewModel with nothing to supersede it reattaches to the same job
        // through the same code; when it has arrived, the whole query-guard-observe-write path
        // has demonstrably run to completion. `viewModel` started its own reattachment first, so
        // it has had at least as long. Waiting on this rather than on a sleep is what makes the
        // assertion below "it did not happen" instead of "it had not happened yet".
        reattachmentHasRunToCompletion()

        val current = viewModel.state.value
        assertTrue("reattachment took the screen from the user: $current", current is JoinState.Idle)

        // And the pick, when it lands, is what stays there.
        parkedPick.runAll()
        val ready = awaitState(viewModel.state, "Ready") { it is JoinState.Ready }
        assertEquals(PICKED, (ready as JoinState.Ready).inputs.map { it.uri })
        reattachmentHasRunToCompletion()
        assertEquals("the user's pick must survive a late reattachment", ready, viewModel.state.value)
    }

    /**
     * The other half of the contract: a reattachment nobody has superseded still takes the screen.
     *
     * Without this, dropping every reattachment on the floor would pass the test above. It is the
     * same job, the same WorkManager and the same production path — only the pick is missing.
     */
    @Test
    fun `a join nobody has superseded still reaches the screen`() {
        val joined = awaitState(JoinViewModel(app).state, "Joined") { it is JoinState.Joined }

        assertEquals(staged.absolutePath, (joined as JoinState.Joined).staged.absolutePath)
    }

    /**
     * Drives a throwaway ViewModel through a whole reattachment, and returns once it has landed.
     *
     * [awaitState] pumps the main looper, which is what runs every reattachment continuation
     * waiting on it — this one's and the one belonging to the ViewModel under test, which was
     * posted earlier and therefore runs first.
     */
    private fun reattachmentHasRunToCompletion() {
        awaitState(JoinViewModel(app).state, "Joined") { it is JoinState.Joined }
    }

    private companion object {
        val PICKED = listOf(
            Uri.parse("content://test/clip-one.mp4"),
            Uri.parse("content://test/clip-two.mp4"),
        )
    }
}
