package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File

/**
 * Where the two staging fixes meet: a result nobody started is still a result somebody owns.
 *
 * Reattachment hands the user back a job this ViewModel did not start, which means the
 * ViewModel inherits the staged file along with it — and "Start over" on that file has to
 * delete it exactly as it would for a conversion run in this process. Neither fix implies
 * the other: cleanup only reaches a file whose handle was recorded, and reattachment only
 * records one because it goes through the same `observe()` the normal success path does.
 * That is a structural claim about one function, which is precisely the kind that a merge
 * resolving the two changes into different places would quietly break. Hence a test rather
 * than a comment.
 *
 * These run a real `WorkManager` and a real `OutputPublisher` against a real `cacheDir`, so
 * what is asserted at the end is the filesystem.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ReattachedCleanupTest {

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
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    /**
     * The defect end to end, on the JVM: work that finished with no ViewModel left to see it,
     * and a ViewModel created afterwards that finds it anyway.
     */
    @Test
    fun `a conversion that finished before this ViewModel existed is picked up`() {
        finishAConversionWithNobodyWatching()

        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        val converted = awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }

        converted as ConversionState.Converted
        assertEquals(staged.absolutePath, converted.staged.absolutePath)
        // The name came back through the job's tags — the only channel WorkManager returns,
        // since WorkInfo never carries the Data a request was enqueued with.
        assertEquals("holiday.mp4", converted.input.displayName)
        assertEquals(4_096L, converted.input.sizeBytes)
    }

    @Test
    fun `start over on a reattached conversion deletes the staged file`() {
        finishAConversionWithNobodyWatching()
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }
        assertTrue("the reattached result should still be on disk", staged.exists())

        viewModel.reset()

        assertEquals(ConversionState.Idle, viewModel.state.value)
        assertEquals(
            "a reattached result is still this ViewModel's to discard",
            listOf(staged),
            publisher.discarded,
        )
        assertFalse("Start over must not leave a full-size copy in cache", staged.exists())
    }

    @Test
    fun `start over on a reattached join deletes the staged file`() {
        workManager.enqueue(
            ConcatWorker.request(
                inputs = listOf(Uri.parse("content://test/one.mp4"), Uri.parse("content://test/two.mp4")),
                totalBytes = 8_192L,
            ),
        ).result.get()

        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        awaitState(viewModel.state, "Joined") { it is JoinState.Joined }

        viewModel.reset()

        assertEquals(listOf(staged), publisher.discarded)
        assertFalse(staged.exists())
    }

    /**
     * A job from a process that is gone: enqueued and finished before any ViewModel exists, so
     * nothing observed it and nothing recorded its output.
     */
    private fun finishAConversionWithNobodyWatching() {
        workManager.enqueue(
            ConversionWorker.request(
                inputUri = Uri.parse("content://test/holiday.mp4"),
                displayName = "holiday.mp4",
                sizeBytes = 4_096L,
            ),
        ).result.get()
    }
}
