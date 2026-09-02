package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.join.JoinState
import org.libremediaconverter.join.JoinViewModel
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.work.ConcatWorker
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * A failure that says nothing still has to say something.
 *
 * Three sites, all `ci == 0` before this file, and all the same rule:
 *
 * ```
 * work/ConversionWorker.kt:316     cause.message ?: GENERIC_FAILURE_MESSAGE
 * convert/ConversionViewModel.kt:631   e.message ?: SAVE_FAILED_MESSAGE
 * join/JoinViewModel.kt:416           e.message ?: SAVE_FAILED_MESSAGE
 * ```
 *
 * Every existing test throws *with* a message, so the right-hand side had never been evaluated
 * anywhere in the suite. A `Throwable` carrying none is not exotic — `RuntimeException()`,
 * `IOException()` and most platform exceptions raised without an argument all have a null message.
 *
 * ## Held in one class, against the ticket's suggestion
 *
 * #193 proposed putting each case beside the behaviour it neighbours. They are together instead,
 * because they are one rule at three layers and because the trap below has to be explained once
 * rather than three times. `FailedSaveRetryTest` sets the precedent for both ViewModels in one
 * file; this extends it by one worker.
 *
 * ## The trap, which is why the worker case asserts what it does
 *
 * `ConversionStateMappingTest`'s *"a failure with nothing said still says something"* looks like it
 * already covers the worker site. It does not: it drives the **read** side, `map(FAILED, Data.EMPTY)`,
 * and that side has a fallback of its own (`ConversionViewModel.kt:147-149`):
 *
 * ```kotlin
 * update.outputData.getString(ConversionWorker.KEY_ERROR)
 *     ?.takeIf { it.isNotBlank() }
 *     ?: ConversionWorker.GENERIC_FAILURE_MESSAGE
 * ```
 *
 * So mutating the worker's fallback to `.orEmpty()` writes `KEY_ERROR to ""`, and the ViewModel
 * turns that straight back into the same constant. **A test asserting on the resulting `Failed`
 * state stays green under the mutation**, which is most likely why the write-side fallback survived
 * three waves of test work. The worker case therefore reads `KEY_ERROR` off the worker's own
 * `Result`, before anything downstream can repair it.
 *
 * The two save cases have no such second line: both write `_state.value` directly, so the state is
 * the right thing to assert there.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class MessagelessFailureTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        staged = publisher.createStagingFile("holiday.mp4").apply { writeBytes(ByteArray(4096)) }
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    /**
     * The engine gives up without saying why, which is what a native crash looks like from here.
     *
     * Asserted on the worker's own output `Data` rather than on a screen — see the class KDoc.
     */
    @Test
    fun `a conversion that fails without a message still reports one`() {
        installTestWorkManager(app, Data.EMPTY)
        ConversionDependencies.software = { MessagelessTranscoder }

        val result = runBlocking { failingWorker().doWork() }

        assertTrue("the job must fail rather than retry, got $result", result is ListenableWorker.Result.Failure)
        assertEquals(
            "a failure with no message must still put something on screen",
            ConversionWorker.GENERIC_FAILURE_MESSAGE,
            (result as ListenableWorker.Result.Failure).outputData.getString(ConversionWorker.KEY_ERROR),
        )
    }

    @Test
    fun `a save that fails without a message still reports one`() {
        installTestWorkManager(app, conversionOutput())
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(Uri.parse("content://test/holiday.mkv"))
        awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }
        viewModel.convert()
        awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }

        publisher.publishFailure = RuntimeException()
        viewModel.save(DESTINATION)

        val failed = awaitState(viewModel.state, "Failed") { it is ConversionState.Failed } as ConversionState.Failed
        assertEquals(SAVE_FAILED_MESSAGE, failed.message)
        // The handle travels even on the wordless path. Without this, a fallback that also dropped
        // `pending` would pass -- and the file would be unreachable from the screen that just said
        // the save failed.
        assertNotNull("a wordless failure must still offer the file again", failed.retry)
    }

    @Test
    fun `a join save that fails without a message still reports one`() {
        installTestWorkManager(app, joinOutput())
        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputsPicked(listOf(Uri.parse("content://test/a.mp4"), Uri.parse("content://test/b.mp4")))
        awaitState(viewModel.state, "Ready") { it is JoinState.Ready }
        viewModel.join()
        awaitState(viewModel.state, "Joined") { it is JoinState.Joined }

        publisher.publishFailure = RuntimeException()
        viewModel.save(DESTINATION)

        val failed = awaitState(viewModel.state, "Failed") { it is JoinState.Failed } as JoinState.Failed
        assertEquals(SAVE_FAILED_MESSAGE, failed.message)
        assertNotNull("a wordless failure must still offer the file again", failed.retry)
    }

    /**
     * `FORCE_SOFTWARE` so the failure comes straight out of `runFFmpeg`.
     *
     * `AUTO` would enter `runMedia3OrFallBack`, whose catch runs the job a second time in software
     * — the same exception would arrive, but through a path this test is not about and which
     * `HardwareFallbackTest` already owns.
     */
    private fun failingWorker(): ConversionWorker {
        val spec = OutputFormat.MP4_H265.spec
        return TestListenableWorkerBuilder<ConversionWorker>(
            context = app,
            inputData = workDataOf(
                ConversionWorker.KEY_INPUT_URI to "file:///tmp/holiday.mp4",
                ConversionWorker.KEY_DISPLAY_NAME to "holiday.mp4",
                ConversionWorker.KEY_CONTAINER to spec.container.name,
                ConversionWorker.KEY_VIDEO_CODEC to spec.videoCodec.name,
                ConversionWorker.KEY_AUDIO_CODEC to spec.audioCodec.name,
                ConversionWorker.KEY_ENGINE_PREFERENCE to EnginePreference.FORCE_SOFTWARE.name,
            ),
            runAttemptCount = 0,
        ).setId(JOB_ID).build()
    }

    private fun conversionOutput() = workDataOf(
        ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath,
        ConversionWorker.KEY_SUGGESTED_NAME to SUGGESTED_NAME,
        ConversionWorker.KEY_MIME_TYPE to JOB_MIME_TYPE,
    )

    private fun joinOutput() = workDataOf(
        ConcatWorker.KEY_OUTPUT_PATH to staged.absolutePath,
        ConcatWorker.KEY_SUGGESTED_NAME to SUGGESTED_NAME,
        ConcatWorker.KEY_MIME_TYPE to JOB_MIME_TYPE,
    )

    private companion object {
        val DESTINATION: Uri = Uri.parse("content://test/destination.mp4")
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000019a")
        const val SUGGESTED_NAME = "holiday.mp4"
        const val JOB_MIME_TYPE = "video/mp4"
    }
}

/**
 * An engine that gives up without saying why.
 *
 * `RuntimeException()` rather than a subclass with a blank message: `Throwable.message` is *null*
 * here, which is the case the elvis exists for. A blank-but-present message takes the left-hand
 * side and is a different path — `ConversionStateMappingTest` covers that one, on the read side.
 */
@UnstableApi
private object MessagelessTranscoder : SoftwareTranscoder {
    override suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
    ): Unit = throw RuntimeException()
}
