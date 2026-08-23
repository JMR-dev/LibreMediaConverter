package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.join.JoinState
import org.libremediaconverter.join.JoinViewModel
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.work.ConcatWorker
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * What the user is told when the file went away between being offered and being saved.
 *
 * Not a corner: staging is `cacheDir`, which is what the OS empties when it wants space, and the
 * sweep collects anything a day old. Reattachment is where the two are furthest apart — the check
 * that decided the file existed ran inside a tag query on launch, and the Save button may not be
 * tapped for hours.
 *
 * The real [OutputPublisher] rather than the recording stub, because the defect is what the *real*
 * publish does with a staged file that is not there: `staged.inputStream()` throws, and `save()`
 * put `e.message` on screen — a `/data/user/0/…/4b4882….mp4: open failed: ENOENT` path the user has
 * never seen and can do nothing with.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MissingStagedFileTest {

    private lateinit var app: Application
    private lateinit var publisher: OutputPublisher
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = OutputPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }

        staged = publisher.createStagingFile("holiday_converted.mp4").apply { writeBytes(ByteArray(4096)) }
        installTestWorkManager(
            app,
            workDataOf(
                ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConcatWorker.KEY_OUTPUT_PATH to staged.absolutePath,
            ),
        )
        // A destination that really opens, so the save gets far enough to reach the staged file.
        // Without this the failure would be about the destination and the test would pass while
        // saying nothing.
        shadowOf(app.contentResolver).registerOutputStreamSupplier(DESTINATION) { ByteArrayOutputStream() }
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `saving a conversion whose staged file has gone says so in a sentence`() {
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(Uri.parse("content://test/holiday.mp4"))
        awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }
        viewModel.convert()
        awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }

        assertTrue("the fixture must start with a real staged file", staged.delete())

        viewModel.save(DESTINATION)

        val failed = awaitState(viewModel.state, "Failed") { it is ConversionState.Failed }
        assertEquals(STAGED_FILE_GONE_MESSAGE, (failed as ConversionState.Failed).message)
    }

    @Test
    fun `saving a join whose staged file has gone says so in a sentence`() {
        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputsPicked(listOf(Uri.parse("content://test/a.mp4"), Uri.parse("content://test/b.mp4")))
        awaitState(viewModel.state, "Ready") { it is JoinState.Ready }
        viewModel.join()
        awaitState(viewModel.state, "Joined") { it is JoinState.Joined }

        assertTrue("the fixture must start with a real staged file", staged.delete())

        viewModel.save(DESTINATION)

        val failed = awaitState(viewModel.state, "Failed") { it is JoinState.Failed }
        assertEquals(STAGED_FILE_GONE_MESSAGE, (failed as JoinState.Failed).message)
    }

    private companion object {
        val DESTINATION: Uri = Uri.parse("content://test/destination.mp4")
    }
}
