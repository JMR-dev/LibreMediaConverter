package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.join.JoinState
import org.libremediaconverter.join.JoinViewModel
import org.libremediaconverter.join.pendingSave
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.work.ConcatWorker
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * A failed save has to leave the file *offerable*, not merely undeleted.
 *
 * The defect is #30, and both halves of it were already written down in `main`. `save()`'s
 * `onFailure` kept the staged file on purpose -- "deleting here would destroy the work to tidy up
 * a cache directory" -- and then handed the screen a `Failed` carrying a message and nothing else,
 * so the single control that branch rendered was "Start over", wired to `reset()`, which deletes
 * exactly that file. The intent and the affordance disagreed, and the affordance won.
 *
 * `ConversionViewModelCleanupTest` already pins the *keeping*: after a failed save the file is
 * still on disk and nothing has been discarded. It stays green with the state carrying nothing,
 * because it reads the filesystem rather than the state. This file asserts the other half -- that
 * the handle reaches the state a screen can read -- and the negative that bounds it: a failure
 * with nothing staged behind it must not sprout a save button.
 *
 * Both ViewModels in one class, following `MissingStagedFileTest`. They are separate state
 * machines that can each hold a staged file at once, but this defect and its fix are the same
 * shape in both, and splitting them would put the two halves of one invariant in two files.
 *
 * ### Not asserted here, so each is a decision rather than an omission
 *
 * - **That the destination received the bytes.** [RecordingPublisher.publish] is a stub, which is
 *   the only way to make a save fail deterministically -- and making it fail is what every case
 *   here needs. `OutputPublisherPublishTest` owns what a real publish writes.
 * - **The screen's two buttons.** `ConverterStateAffordancesTest` and `JoinStateAffordancesTest`
 *   own what each state renders; this file owns what each state carries.
 * - **`ConverterScreen`'s `destinationMime` line itself.** It lives in the entry point, above the
 *   `ScreenContent` seam, and reaching it needs a real ViewModel inside a composition. What it
 *   reads -- `pendingSave()?.mimeType` -- is asserted directly instead, which is why that
 *   derivation was moved out of the entry point in the first place.
 * - **Picking a new input while a `Failed` carries a file.** `onInputPicked` overwrites the state
 *   without discarding, from `Converted` exactly as much as from a carrying `Failed`, and neither
 *   branch renders a picker. It is a pre-existing path this change neither opens nor widens: the
 *   carried handle is a view of `pendingStaged`, never a second owner of the file.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class FailedSaveRetryTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }
        // MediaProbe spawns FFprobe, whose loader throws with no native library present.
        ConversionDependencies.probe = { _, _ -> InputProbe() }

        staged = publisher.createStagingFile("holiday.mp4").apply { writeBytes(ByteArray(4096)) }
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    // ------------------------------------------------------------------ Convert

    /**
     * The bite named in #30's fix. Emitting a plain `Failed` from `save()`'s `onFailure` -- which
     * is what `main` did -- reddens this case on the null handle, and nothing else in the suite.
     */
    @Test
    fun `a failed save leaves the staged file offerable, not merely undeleted`() {
        val viewModel = failedSaveViewModel()

        val failed = viewModel.state.value as ConversionState.Failed
        val retry = failed.retry
        assertNotNull("a failed save must leave the staged file offerable, not just on disk", retry)
        assertEquals("the retry must name the file the conversion actually produced", staged, retry?.staged)
        // Both from the job's own output Data rather than from the pickers, so a retry opens the
        // same dialog the first attempt did.
        assertEquals(SUGGESTED_NAME, retry?.suggestedName)
        assertEquals(JOB_MIME_TYPE, retry?.mimeType)
        assertTrue("a failed save must not destroy the only copy", staged.exists())
    }

    /**
     * The whole point of carrying the handle: the second attempt is a real save, not a new job.
     *
     * `publishFailure` is cleared between the two calls, so one `RecordingPublisher` plays both a
     * full destination and an empty one -- which is exactly the user's situation.
     */
    @Test
    fun `retrying a failed save publishes the file and leaves nothing staged`() {
        val viewModel = failedSaveViewModel()

        publisher.publishFailure = null
        viewModel.save(DESTINATION)

        val saved = awaitState(viewModel.state, "Saved") { it is ConversionState.Saved }
        assertEquals(SUGGESTED_NAME, (saved as ConversionState.Saved).displayName)
        assertFalse("a successful retry should have removed the staged file", staged.exists())
        // Nothing to collect afterwards: the retry published it, so reset() has no work left.
        viewModel.reset()
        assertEquals(emptyList<File>(), publisher.discarded)
    }

    /**
     * The second failure must not eat the file the first one kept.
     *
     * A `Failed` built fresh from `e.message` alone would drop the handle here while every other
     * assertion in this file stayed green -- the file is still on disk, and the first failure
     * already proved the state can carry it.
     */
    @Test
    fun `a retry that fails again still carries the file rather than dropping it`() {
        val viewModel = failedSaveViewModel()

        publisher.publishFailure = IllegalStateException("destination volume still full")
        viewModel.save(DESTINATION)

        // Waited for by the *second* message rather than by `is Failed`: the state was already
        // Failed when the retry started, so the type alone would be satisfied before it ran.
        val failed = awaitState(viewModel.state, "the second failure") {
            it is ConversionState.Failed && it.message == "destination volume still full"
        } as ConversionState.Failed
        assertEquals("the second failure must offer the same file the first one did", staged, failed.retry?.staged)
        assertTrue(staged.exists())
        assertEquals(emptyList<File>(), publisher.discarded)
    }

    /**
     * "Start over" still deletes, and that is the decision `reset()`'s KDoc records: acceptable
     * only because "Try saving again" is on screen beside it. Exactly once, through the publisher.
     */
    @Test
    fun `start over from a failed save discards the carried file exactly once`() {
        val viewModel = failedSaveViewModel()

        viewModel.reset()

        assertEquals(ConversionState.Idle, viewModel.state.value)
        assertEquals(listOf(staged), publisher.discarded)
        assertFalse(staged.exists())
    }

    /**
     * A retry meets the same existence check the first attempt did, so a file collected by the
     * sweep or by the OS in between is reported as a sentence rather than as a raw ENOENT path.
     * And the state that reports it carries nothing: there is no file left to offer.
     */
    @Test
    fun `a retry whose staged file has gone says so and offers nothing further`() {
        val viewModel = failedSaveViewModel()
        assertTrue("the fixture must start with a real staged file", staged.delete())

        publisher.publishFailure = null
        viewModel.save(DESTINATION)

        val failed = viewModel.state.value as ConversionState.Failed
        assertEquals(STAGED_FILE_GONE_MESSAGE, failed.message)
        assertNull("a file that has gone cannot be offered again", failed.retry)
    }

    /**
     * The negative that bounds the whole change, and the reason `retry` is nullable.
     *
     * A transcode that died staged nothing, so there is no file to hand back -- and a `Failed`
     * that carried one anyway would put a save button on a screen with nothing to save. Driven
     * through a worker that really fails rather than by constructing the state, because the line
     * under test is the `WorkInfo.State.FAILED` arm of `observe`.
     */
    @Test
    fun `a transcode failure carries nothing to save`() {
        installFailingTestWorkManager(app, workDataOf(ConversionWorker.KEY_ERROR to "The encoder gave up."))
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(Uri.parse("content://test/holiday.mp4"))
        awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }

        viewModel.convert()

        val failed = awaitState(viewModel.state, "Failed") { it is ConversionState.Failed } as ConversionState.Failed
        assertEquals("The encoder gave up.", failed.message)
        assertNull("a transcode failure has nothing staged, so it must offer no save", failed.retry)
        assertNull("and nothing for the save dialog to open with either", failed.pendingSave())
    }

    /**
     * What the save dialog reopens with, which is the entry point's only reader of this state.
     *
     * The pickers are moved *after* the job finishes, which is what makes this bite: a retry that
     * asked the current settings would offer `audio/mpeg` for a file the job wrote as MP4. The
     * same gap is permanent for a reattached job, whose spec was never in these settings at all.
     */
    @Test
    fun `a retry offers the type the job chose, not the one the pickers now show`() {
        val viewModel = failedSaveViewModel()

        viewModel.setPreset(OutputFormat.MP3)

        assertEquals(
            "the fixture needs the pickers to disagree with the job",
            "audio/mpeg",
            viewModel.settings.value.spec.mimeType,
        )
        assertEquals(JOB_MIME_TYPE, viewModel.state.value.pendingSave()?.mimeType)
    }

    // --------------------------------------------------------------------- Join

    @Test
    fun `a failed join save leaves the staged file offerable, not merely undeleted`() {
        val viewModel = failedJoinSaveViewModel()

        val failed = viewModel.state.value as JoinState.Failed
        val retry = failed.retry
        assertNotNull("a failed save must leave the staged file offerable, not just on disk", retry)
        assertEquals(staged, retry?.staged)
        assertEquals(SUGGESTED_NAME, retry?.suggestedName)
        assertEquals(JOB_MIME_TYPE, retry?.mimeType)
        assertTrue(staged.exists())
    }

    @Test
    fun `retrying a failed join save publishes the file and leaves nothing staged`() {
        val viewModel = failedJoinSaveViewModel()

        publisher.publishFailure = null
        viewModel.save(DESTINATION)

        val saved = awaitState(viewModel.state, "Saved") { it is JoinState.Saved }
        assertEquals(SUGGESTED_NAME, (saved as JoinState.Saved).displayName)
        assertFalse(staged.exists())
        viewModel.reset()
        assertEquals(emptyList<File>(), publisher.discarded)
    }

    @Test
    fun `a join retry that fails again still carries the file rather than dropping it`() {
        val viewModel = failedJoinSaveViewModel()

        publisher.publishFailure = IllegalStateException("destination volume still full")
        viewModel.save(DESTINATION)

        // By the second message, not by `is Failed` -- see the converter case above.
        val failed = awaitState(viewModel.state, "the second failure") {
            it is JoinState.Failed && it.message == "destination volume still full"
        } as JoinState.Failed
        assertEquals(staged, failed.retry?.staged)
        assertTrue(staged.exists())
        assertEquals(emptyList<File>(), publisher.discarded)
    }

    @Test
    fun `start over from a failed join save discards the carried file exactly once`() {
        val viewModel = failedJoinSaveViewModel()

        viewModel.reset()

        assertEquals(JoinState.Idle, viewModel.state.value)
        assertEquals(listOf(staged), publisher.discarded)
        assertFalse(staged.exists())
    }

    @Test
    fun `a join failure carries nothing to save`() {
        installFailingTestWorkManager(app, workDataOf(ConcatWorker.KEY_ERROR to "The files could not be joined."))
        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputsPicked(listOf(Uri.parse("content://test/a.mp4"), Uri.parse("content://test/b.mp4")))
        awaitState(viewModel.state, "Ready") { it is JoinState.Ready }

        viewModel.join()

        val failed = awaitState(viewModel.state, "Failed") { it is JoinState.Failed } as JoinState.Failed
        assertEquals("The files could not be joined.", failed.message)
        assertNull("a join failure has nothing staged, so it must offer no save", failed.retry)
        assertNull(failed.pendingSave())
    }

    // ------------------------------------------------------------------ Harness

    /**
     * A ViewModel driven to `Converted` and then through a save that threw.
     *
     * The WorkManager is installed here rather than in `@Before`, because two cases in this class
     * need one whose workers fail instead.
     */
    private fun failedSaveViewModel(): ConversionViewModel {
        installTestWorkManager(app, conversionOutput())
        // Unconfined so reset()'s delete runs inline instead of on a real IO thread.
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(Uri.parse("content://test/holiday.mkv"))
        awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }
        viewModel.convert()
        awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }

        publisher.publishFailure = IllegalStateException("destination volume full")
        viewModel.save(DESTINATION)
        awaitState(viewModel.state, "Failed") { it is ConversionState.Failed }
        return viewModel
    }

    /** The join tab's equivalent, driven to `Joined` and then through a save that threw. */
    private fun failedJoinSaveViewModel(): JoinViewModel {
        installTestWorkManager(app, joinOutput())
        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputsPicked(listOf(Uri.parse("content://test/a.mp4"), Uri.parse("content://test/b.mp4")))
        awaitState(viewModel.state, "Ready") { it is JoinState.Ready }
        viewModel.join()
        awaitState(viewModel.state, "Joined") { it is JoinState.Joined }

        publisher.publishFailure = IllegalStateException("destination volume full")
        viewModel.save(DESTINATION)
        awaitState(viewModel.state, "Failed") { it is JoinState.Failed }
        return viewModel
    }

    /**
     * The output `Data` a finished conversion reports.
     *
     * The name and type are set rather than left out, so the assertions above are about what the
     * *job* chose. Both ViewModels fall back to a derivation when they are missing, and a fixture
     * that omitted them would be asserting the fallback while looking like it asserted the job.
     *
     * Spelled out per worker rather than shared with [joinOutput], even though the two constants
     * hold the same strings today. A test that leaned on that would be asserting a coincidence.
     */
    private fun conversionOutput() = workDataOf(
        ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath,
        ConversionWorker.KEY_SUGGESTED_NAME to SUGGESTED_NAME,
        ConversionWorker.KEY_MIME_TYPE to JOB_MIME_TYPE,
    )

    /** The output `Data` a finished join reports. See [conversionOutput]. */
    private fun joinOutput() = workDataOf(
        ConcatWorker.KEY_OUTPUT_PATH to staged.absolutePath,
        ConcatWorker.KEY_SUGGESTED_NAME to SUGGESTED_NAME,
        ConcatWorker.KEY_MIME_TYPE to JOB_MIME_TYPE,
    )

    private companion object {
        val DESTINATION: Uri = Uri.parse("content://test/destination.mp4")

        const val SUGGESTED_NAME = "holiday.mp4"

        /** What the job wrote. [OutputFormat.MP3]'s `audio/mpeg` is what the pickers move to. */
        const val JOB_MIME_TYPE = "video/mp4"
    }
}
