package org.libremediaconverter.join

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
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.RecordingPublisher
import org.libremediaconverter.convert.awaitState
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.work.ConcatWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The join tab's half of the same defect.
 *
 * Carried separately rather than parameterised with the convert tab, because the two are
 * independent ViewModels that can each hold a staged file at the same time — the reason
 * `clearStaging()` could not simply be wired up.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JoinViewModelCleanupTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }

        staged = publisher.createStagingFile("joined.mp4").apply { writeBytes(ByteArray(4096)) }
        installTestWorkManager(app, workDataOf(ConcatWorker.KEY_OUTPUT_PATH to staged.absolutePath))
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `start over on a finished join deletes the staged file`() {
        val viewModel = joinedViewModel()
        assertTrue("the join should have produced a staged file", staged.exists())

        viewModel.reset()

        assertEquals(JoinState.Idle, viewModel.state.value)
        assertEquals(listOf(staged), publisher.discarded)
        assertFalse("Start over must not leave a full-size copy in cache", staged.exists())
    }

    @Test
    fun `reset after a successful save does not try to delete again`() {
        val viewModel = joinedViewModel()
        viewModel.save(DESTINATION)
        awaitState(viewModel.state, "Saved") { it is JoinState.Saved }

        // save() deletes the staged file itself, through File.delete() rather than through
        // the publisher, so assert the disappearance as well as the absent second discard.
        assertFalse("a successful save should have removed the staged file", staged.exists())

        viewModel.reset()

        assertEquals(emptyList<File>(), publisher.discarded)
        assertFalse(staged.exists())
    }

    @Test
    fun `a failed save keeps the staged file, and a later reset collects it`() {
        val viewModel = joinedViewModel()
        publisher.publishFailure = IllegalStateException("destination volume full")

        viewModel.save(DESTINATION)
        awaitState(viewModel.state, "Failed") { it is JoinState.Failed }

        assertTrue("a failed save must not destroy the only copy", staged.exists())
        assertEquals(emptyList<File>(), publisher.discarded)

        viewModel.reset()

        assertEquals(listOf(staged), publisher.discarded)
        assertFalse(staged.exists())
    }

    /** A ViewModel driven all the way to [JoinState.Joined]. */
    private fun joinedViewModel(): JoinViewModel {
        // Unconfined so reset()'s delete runs inline instead of on a real IO thread.
        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputsPicked(listOf(Uri.parse("content://test/a.mp4"), Uri.parse("content://test/b.mp4")))
        awaitState(viewModel.state, "Ready") { it is JoinState.Ready }
        viewModel.join()
        awaitState(viewModel.state, "Joined") { it is JoinState.Joined }
        return viewModel
    }

    private companion object {
        val DESTINATION: Uri = Uri.parse("content://test/destination.mp4")
    }
}
