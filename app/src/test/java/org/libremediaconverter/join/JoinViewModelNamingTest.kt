package org.libremediaconverter.join

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.RecordingPublisher
import org.libremediaconverter.convert.awaitState
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.work.ConcatWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * That a join is named after the format it produced.
 *
 * `JoinState.Saved("joined.mp4")` was a literal, and so were the screen's `CreateDocument`
 * MIME type and the name it launched with. All three agree with reality only because the join
 * screen has no format picker and `ConcatWorker.request` defaults to MP4 — three copies of one
 * assumption, none of which would notice the day a picker arrives.
 *
 * MKV throughout below, because it is the format the old literals get wrong.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JoinViewModelNamingTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }

        staged = publisher.createStagingFile("staged-under-a-job-id.mkv").apply { writeBytes(ByteArray(4096)) }
        installTestWorkManager(
            app,
            workDataOf(
                ConcatWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConcatWorker.KEY_STRATEGY to ConcatStrategy.STREAM_COPY.name,
                ConcatWorker.KEY_SUGGESTED_NAME to "joined.mkv",
                ConcatWorker.KEY_MIME_TYPE to "video/x-matroska",
            ),
        )
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a finished join carries the name and type its own format produced`() {
        val joined = joinedViewModel().state.value as JoinState.Joined

        assertEquals("joined.mkv", joined.suggestedName)
        assertEquals("video/x-matroska", joined.mimeType)
    }

    @Test
    fun `saving reports that name rather than a hardcoded one`() {
        val viewModel = joinedViewModel()

        viewModel.save(DESTINATION)
        val saved = awaitState(viewModel.state, "Saved") { it is JoinState.Saved }

        assertEquals("joined.mkv", (saved as JoinState.Saved).displayName)
    }

    @Test
    fun `a join from before the worker reported its own name still gets one`() {
        // Work enqueued by an earlier version carries neither string. The fallback is the format
        // ConcatWorker.request has always defaulted to, which is what such a job really used.
        installTestWorkManager(
            app,
            workDataOf(
                ConcatWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConcatWorker.KEY_STRATEGY to ConcatStrategy.STREAM_COPY.name,
            ),
        )

        val joined = joinedViewModel().state.value as JoinState.Joined

        assertEquals("joined.mp4", joined.suggestedName)
        assertEquals("video/mp4", joined.mimeType)
    }

    /** A ViewModel driven all the way to [JoinState.Joined]. */
    private fun joinedViewModel(): JoinViewModel {
        val viewModel = JoinViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputsPicked(listOf(Uri.parse("content://test/a.mkv"), Uri.parse("content://test/b.mkv")))
        awaitState(viewModel.state, "Ready") { it is JoinState.Ready }
        viewModel.join()
        awaitState(viewModel.state, "Joined") { it is JoinState.Joined }
        return viewModel
    }

    private companion object {
        val DESTINATION: Uri = Uri.parse("content://test/destination.mkv")
    }
}
