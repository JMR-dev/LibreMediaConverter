package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * An answer that arrives after the screen has moved on does nothing.
 *
 * Four refusal arms, cold before this file:
 *
 * ```
 * convert/ConversionViewModel.kt:513   currentInput() ?: return
 * convert/ConversionViewModel.kt:600   pendingSave() ?: return
 * join/JoinViewModel.kt:316            (as? Ready)?.inputs ?: return
 * join/JoinViewModel.kt:390            pendingSave() ?: return
 * ```
 *
 * They are not merely defensive. `ConverterScreen.kt:91` wires `convert()` to the
 * **POST_NOTIFICATIONS result**, and `:83` wires `save()` to the CreateDocument result — so both
 * are entered by a system callback rather than by a tap, and a result redelivered after process
 * death arrives at a brand-new ViewModel sitting on `Idle`.
 *
 * ## The production change that came with this
 *
 * `currentInput()` used to answer for `Converting`, `Waiting` and `Converted` as well as `Ready`.
 * Those arms were unreachable by tapping Convert but reachable through that permission callback,
 * and reaching one enqueued a **second** job over a live one — `activeWorkId` overwritten, the
 * first job still running with an orphaned notification and nothing holding its id.
 *
 * #202 decided to narrow rather than to test it as it stood, because a test written against the old
 * shape would have frozen the double-enqueue as intended behaviour. `JoinViewModel.join()` has been
 * `(_state.value as? JoinState.Ready)?.inputs ?: return` all along; the two screens are the same
 * shape and only one was over-general.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class StaleLauncherResultTest {

    private lateinit var app: Application
    private lateinit var workManager: WorkManager
    private lateinit var staged: java.io.File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        val publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        // A real staged file, because a SUCCEEDED job with no output path maps to Failed rather
        // than Converted -- and Converted is the state this file's second case has to reach.
        staged = publisher.createStagingFile("holiday.mp4").apply { writeBytes(ByteArray(4096)) }
        installTestWorkManager(
            app,
            workDataOf(
                ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConversionWorker.KEY_SUGGESTED_NAME to "holiday.mp4",
                ConversionWorker.KEY_MIME_TYPE to "video/mp4",
            ),
        )
        workManager = WorkManager.getInstance(app)
    }

    @After
    fun tearDown() = ConversionDependencies.reset()

    @Test
    fun `a permission answer arriving on an empty screen enqueues nothing`() {
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        awaitState(viewModel.state, "Idle") { it is ConversionState.Idle }

        viewModel.convert()

        assertEquals(ConversionState.Idle, viewModel.state.value)
        assertEquals("nothing may be enqueued for a file that is not there", 0, conversionJobs())
    }

    /**
     * The narrowing itself: a permission answer that arrives while a conversion is already running
     * must not start a second one.
     *
     * Reached by converting once — the synchronous test WorkManager finishes it inline, so the
     * screen is `Converted`, which is one of the three arms `currentInput()` used to answer for.
     * Calling `convert()` again from there is precisely what the permission callback can do.
     */
    @Test
    fun `a permission answer arriving after the job finished does not start a second one`() {
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(Uri.parse("content://test/holiday.mkv"))
        awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }
        viewModel.convert()
        val converted = awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }
        assertEquals("the fixture needs exactly one job to start with", 1, conversionJobs())

        viewModel.convert()

        assertEquals("a second job must not be enqueued over the first", 1, conversionJobs())
        assertEquals("and the screen must not move", converted, viewModel.state.value)
    }

    @Test
    fun `a save answer arriving on an empty screen does nothing`() {
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        awaitState(viewModel.state, "Idle") { it is ConversionState.Idle }

        viewModel.save(DESTINATION)

        assertEquals(ConversionState.Idle, viewModel.state.value)
    }

    @Test
    fun `a join answer arriving on an empty screen enqueues nothing`() {
        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        awaitState(viewModel.state, "Idle") { it is JoinState.Idle }

        viewModel.join()
        viewModel.save(DESTINATION)

        assertEquals(JoinState.Idle, viewModel.state.value)
        assertEquals(0, joinJobs())
    }

    private fun conversionJobs() = jobsTagged(ConversionWorker::class.java.name)

    private fun joinJobs() = jobsTagged(ConcatWorker::class.java.name)

    private fun jobsTagged(tag: String) = workManager.getWorkInfosByTag(tag).get().size

    private companion object {
        val DESTINATION: Uri = Uri.parse("content://test/destination.mp4")
    }
}
