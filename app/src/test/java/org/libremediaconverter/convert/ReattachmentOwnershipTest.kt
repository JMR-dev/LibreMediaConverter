package org.libremediaconverter.convert

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
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Issue #49, on the JVM and without the race.
 *
 * `ReattachOnLaunchTest.doesNotOverwriteAPickTheUserHasAlreadyMade` has been catching this on
 * devices since 2026-08-24 — four times in 400 gating leg-attempts, on API 33, 35 and 36 — and
 * every occurrence passed on re-run, which is why it was read as flaky infrastructure for two
 * days. It is not. The assertion it fails on is `expected null, but was:<Converted>`: a finished
 * job from an earlier session taking a screen the user had already picked a file on.
 *
 * The defect is a check-then-act whose act is deferred into another coroutine. `reattach()` reads
 * `_state.value` and then calls `observe()`, which *launches* a collector that has to suspend on
 * `getWorkInfoByIdFlow(...).collect` before it can write anything. So the check happens at one
 * moment and the write lands at another:
 *
 * 1. `init` starts the tag query and suspends in it.
 * 2. The user picks a file; `onInputPicked` suspends in its metadata query.
 * 3. The query comes back. `_state.value` is still `Idle` — step 2 has not written yet — so the
 *    guard passes and an observation of the old job is launched.
 * 4. The pick lands. `Ready(picked)`. The user owns the screen.
 * 5. The observation's first `WorkInfo` arrives and writes `Converted(yesterday)` over it.
 *
 * The comment above that guard claimed "no suspension point between this check and the assignment
 * below, so nothing can interleave". There is no assignment below, and the check and the write
 * are in different coroutines.
 *
 * [ReattachGuardsTest] covers the case where the pick has already *landed*, which the plain guard
 * does catch. This covers the one where it is still in flight, which it does not.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ReattachmentOwnershipTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var workManager: WorkManager
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }

        staged = publisher.createStagingFile("holiday_converted.mp4").apply { writeBytes(ByteArray(4096)) }
        installTestWorkManager(app, workDataOf(ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath))
        workManager = WorkManager.getInstance(app)
        // The situation reattachment exists for: a conversion that finished in a process that is
        // gone, with its output still in the cache and nothing in the UI holding its id.
        workManager.enqueue(
            ConversionWorker.request(
                inputUri = Uri.parse("content://test/holiday.mp4"),
                displayName = "holiday.mp4",
                sizeBytes = 4_096L,
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
     * The pick is parked on a dispatcher this test owns, so it stays in flight — issued, not yet
     * written — for as long as the assertions need it to be. Everything else is production: a
     * real `WorkManager` holding a real finished job, the real `reattach`, the real `observe`.
     *
     * Determinism comes from where Robolectric leaves the main looper. `reattach`'s tag query hops
     * to a real [kotlinx.coroutines.Dispatchers.IO] thread, so its continuation can only come back
     * as a message posted to the main looper — and that looper is paused, so it cannot run until
     * something pumps it. `onInputPicked` is an ordinary synchronous call from this thread. The
     * pick is therefore *always* issued before the guard runs; none of it is left to timing, which
     * is the whole point of writing this here rather than relying on the 1-in-130 device sighting.
     */
    @Test
    fun `a conversion found while the user was picking never reaches the screen`() {
        val picked = Uri.fromFile(File(app.cacheDir, "beach.mp4").apply { writeBytes(ByteArray(2048)) })
        val parkedPick = ParkedPickDispatcher()
        val viewModel = ConversionViewModel(app, pickDispatcher = parkedPick)
        viewModel.onInputPicked(picked)

        assertEquals(
            "the pick must still be in flight, or this proves something about a different situation",
            1,
            parkedPick.parkedCount,
        )

        // The control, and the reason this test does not rest on a settle window being long
        // enough. A second ViewModel with nothing to supersede it reattaches to the same job
        // through the same code; when it has arrived, the whole query-guard-observe-write path has
        // demonstrably run to completion. `viewModel` started its own reattachment first, so it
        // has had at least as long. Waiting on this rather than on a sleep is what makes the
        // assertion below "it did not happen" rather than "it had not happened yet".
        reattachmentHasRunToCompletion()

        val current = viewModel.state.value
        assertTrue("reattachment took the screen from the user: $current", current is ConversionState.Idle)

        // And the pick, when it lands, is what stays there.
        parkedPick.runAll()
        val ready = awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }
        assertEquals(picked, (ready as ConversionState.Ready).input.uri)
        reattachmentHasRunToCompletion()
        assertEquals("the user's pick must survive a late reattachment", ready, viewModel.state.value)
    }

    /**
     * The other half of the contract: a reattachment nobody has superseded still takes the screen.
     *
     * Without this, dropping every reattachment on the floor would pass the test above. Same job,
     * same WorkManager, same production path — only the pick is missing.
     */
    @Test
    fun `a conversion nobody has superseded still reaches the screen`() {
        val converted = awaitState(ConversionViewModel(app).state, "Converted") {
            it is ConversionState.Converted
        }

        assertEquals(staged.absolutePath, (converted as ConversionState.Converted).staged.absolutePath)
        assertEquals("holiday.mp4", converted.input.displayName)
    }

    /**
     * Drives a throwaway ViewModel through a whole reattachment, and returns once it has landed.
     *
     * [awaitState] pumps the main looper, which is what runs every reattachment continuation
     * waiting on it — this one's, and the one belonging to the ViewModel under test, which was
     * posted earlier and therefore runs first.
     */
    private fun reattachmentHasRunToCompletion() {
        awaitState(ConversionViewModel(app).state, "Converted") { it is ConversionState.Converted }
    }
}
