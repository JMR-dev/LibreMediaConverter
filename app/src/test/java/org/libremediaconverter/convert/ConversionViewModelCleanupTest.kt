package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * That `reset()` actually deletes — the wiring, not the tool.
 *
 * D2 was never that `OutputPublisher` could not delete a file. It was that "Start over"
 * dropped the reference without calling anything. So this drives the real ViewModel through
 * a real `WorkManager` to `Converted` and then asserts on the filesystem.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConversionViewModelCleanupTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }
        // MediaProbe spawns FFprobe, whose loader throws a bare java.lang.Error with no
        // native library present. Without this the pick dies before the test starts.
        ConversionDependencies.probe = { _, _ -> InputProbe() }

        staged = publisher.createStagingFile("holiday.mp4").apply { writeBytes(ByteArray(4096)) }
        installTestWorkManager(app, workDataOf(ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath))
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `start over on a finished conversion deletes the staged file`() {
        val viewModel = convertedViewModel()
        assertTrue("the conversion should have produced a staged file", staged.exists())

        viewModel.reset()

        assertEquals(ConversionState.Idle, viewModel.state.value)
        assertEquals("reset() should have discarded exactly the staged file", listOf(staged), publisher.discarded)
        assertFalse("Start over must not leave a full-size copy in cache", staged.exists())
    }

    @Test
    fun `reset after a successful save does not try to delete again`() {
        val viewModel = convertedViewModel()
        viewModel.save(DESTINATION)
        awaitState(viewModel.state, "Saved") { it is ConversionState.Saved }

        // save() deletes the staged file itself, through File.delete() rather than through
        // the publisher, so assert the disappearance as well as the absent second discard.
        assertFalse("a successful save should have removed the staged file", staged.exists())

        viewModel.reset()

        // Discarding again would be a delete aimed at a path this ViewModel no longer owns.
        assertEquals(emptyList<File>(), publisher.discarded)
        assertFalse(staged.exists())
    }

    @Test
    fun `a failed save keeps the staged file, and a later reset collects it`() {
        val viewModel = convertedViewModel()
        publisher.publishFailure = IllegalStateException("destination volume full")

        viewModel.save(DESTINATION)
        awaitState(viewModel.state, "Failed") { it is ConversionState.Failed }

        // The deliberate decision, pinned: the staged file may be the only copy of an hour
        // of transcoding, and the destination did not receive it.
        assertTrue("a failed save must not destroy the only copy", staged.exists())
        assertEquals(emptyList<File>(), publisher.discarded)

        // Failed carries no file reference at all, so this only works because the handle is
        // a ViewModel field rather than something read back out of the state machine.
        viewModel.reset()

        assertEquals(listOf(staged), publisher.discarded)
        assertFalse(staged.exists())
    }

    /** A ViewModel driven all the way to [ConversionState.Converted]. */
    private fun convertedViewModel(): ConversionViewModel {
        // Unconfined so reset()'s delete runs inline instead of on a real IO thread.
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(Uri.parse("content://test/holiday.mp4"))
        awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }
        viewModel.convert()
        awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }
        return viewModel
    }

    private companion object {
        val DESTINATION: Uri = Uri.parse("content://test/destination.mp4")
    }
}
