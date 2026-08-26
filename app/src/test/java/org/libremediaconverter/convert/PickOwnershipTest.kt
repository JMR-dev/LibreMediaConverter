package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The half of issue #49 that is not about reattachment at all.
 *
 * `onInputPicked` makes two writes and both of them land after a hop off the main thread, so both
 * belong to whichever pick was in flight rather than to whichever pick the user last made. Nothing
 * was enforcing that. Two taps in quick succession — an easy thing to do while a `content://`
 * metadata query is slow — put the loser's file on screen if its query happened to come back
 * second, which is the same defect the ticket reported against reattachment with a different
 * coroutine on the losing side.
 *
 * Both cases below existed before the fix and neither was reported, because a pick that loses to
 * another pick still shows *a* file the user chose. That is what made this worth closing in the
 * same change: one rule covering every deferred write is checkable, where "the observer checks and
 * the pick does not" is a rule nobody can hold in their head.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PickOwnershipTest {

    private lateinit var app: Application
    private lateinit var parkedPick: ParkedPickDispatcher
    private lateinit var viewModel: ConversionViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ConversionDependencies.publisher = { RecordingPublisher(app) }
        ConversionDependencies.probe = { _, _ -> PROBE }
        installTestWorkManager(app, workDataOf(ConversionWorker.KEY_OUTPUT_PATH to "/dev/null"))

        parkedPick = ParkedPickDispatcher()
        viewModel = ConversionViewModel(app, pickDispatcher = parkedPick)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    /**
     * Two taps, and the first one's metadata query is the slow one.
     *
     * The order is chosen rather than raced: both queries are parked, and this runs the second
     * before the first. Without an ownership check the straggler writes last and the screen ends
     * up showing a file the user moved off two taps ago.
     */
    @Test
    fun `the slower of two picks does not land on top of the faster one`() {
        viewModel.onInputPicked(FIRST)
        viewModel.onInputPicked(SECOND)

        val queries = parkedPick.takeParked()
        assertEquals("both picks should be in flight", 2, queries.size)
        // The second pick's query comes back first; the first pick's is the straggler.
        queries[1].run()
        queries[0].run()

        val current = viewModel.state.value
        assertEquals(
            "a pick the user has already replaced took the screen: $current",
            SECOND,
            (current as ConversionState.Ready).input.uri,
        )
    }

    /**
     * The second of `onInputPicked`'s two writes, which lands a whole probe later.
     *
     * The probe hop is a native process spawn, so it is the longest gap in a pick and the easiest
     * one to pick again during. This used to be guarded by comparing URIs against the state, which
     * answers a narrower question than the one that matters — it cannot tell a second pick of the
     * same file from the first, and it reads a state that a later claim may not have written yet,
     * which is exactly this case: the newer pick has claimed the screen but its own query has not
     * come back, so the state still names the older file and the comparison waves it through.
     */
    @Test
    fun `a probe from a pick the user has moved off does not fill the card in`() {
        viewModel.onInputPicked(FIRST)
        parkedPick.takeParked().single().run()
        assertEquals(FIRST, (viewModel.state.value as ConversionState.Ready).input.uri)

        // The user picks again while the first pick is still probing.
        viewModel.onInputPicked(SECOND)
        val pending = parkedPick.takeParked()
        assertEquals("the first probe and the second query should both be waiting", 2, pending.size)
        pending[0].run()

        assertNull(
            "a probe belonging to a pick the user replaced must not reach the card",
            (viewModel.state.value as ConversionState.Ready).input.probe,
        )

        // And the pick that did win still fills its own card in, probe included.
        pending[1].run()
        parkedPick.runAll()
        val settled = viewModel.state.value as ConversionState.Ready
        assertEquals(SECOND, settled.input.uri)
        assertNotNull("the winning pick's own probe still has to land", settled.input.probe)
    }

    private companion object {
        val FIRST: Uri = Uri.fromFile(File("/tmp/first.mp4"))
        val SECOND: Uri = Uri.fromFile(File("/tmp/second.mp4"))
        val PROBE = InputProbe(videoCodec = "h264")
    }
}
