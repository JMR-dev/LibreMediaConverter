package org.libremediaconverter.join

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.ParkedPickDispatcher
import org.libremediaconverter.convert.RecordingPublisher
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.work.ConcatWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `PickOwnershipTest`'s case on the join side.
 *
 * `onInputsPicked` makes one write and it lands after a hop off the main thread, so it belongs to
 * whichever pick was in flight rather than to whichever set of files the user last chose. Two
 * selections in quick succession — likelier here than on the convert side, since a join picks
 * several files at a time and the metadata query is per file — put the loser's files on screen if
 * its query came back second.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JoinPickOwnershipTest {

    private lateinit var app: Application
    private lateinit var parkedPick: ParkedPickDispatcher
    private lateinit var viewModel: JoinViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ConversionDependencies.publisher = { RecordingPublisher(app) }
        installTestWorkManager(app, workDataOf(ConcatWorker.KEY_OUTPUT_PATH to "/dev/null"))

        parkedPick = ParkedPickDispatcher()
        viewModel = JoinViewModel(app, pickDispatcher = parkedPick)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    /**
     * Two selections, with the first one's metadata query the slow one.
     *
     * The order is chosen rather than raced: both queries are parked, and this runs the second
     * before the first.
     */
    @Test
    fun `the slower of two selections does not land on top of the faster one`() {
        viewModel.onInputsPicked(FIRST)
        viewModel.onInputsPicked(SECOND)

        val queries = parkedPick.takeParked()
        assertEquals("both selections should be in flight", 2, queries.size)
        // The second selection's query comes back first; the first one's is the straggler.
        queries[1].run()
        queries[0].run()

        val current = viewModel.state.value
        assertEquals(
            "a selection the user has already replaced took the screen: $current",
            SECOND,
            (current as JoinState.Ready).inputs.map { it.uri },
        )
    }

    private companion object {
        val FIRST = listOf(
            Uri.parse("content://test/first-a.mp4"),
            Uri.parse("content://test/first-b.mp4"),
        )
        val SECOND = listOf(
            Uri.parse("content://test/second-a.mp4"),
            Uri.parse("content://test/second-b.mp4"),
        )
    }
}
