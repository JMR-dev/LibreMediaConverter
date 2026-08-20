package dev.jasonmross.mediaconverter.fallback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.jasonmross.mediaconverter.convert.OutputPublisher
import dev.jasonmross.mediaconverter.ffmpeg.ConcatEngine
import dev.jasonmross.mediaconverter.work.ConcatWorker
import dev.jasonmross.mediaconverter.work.ConversionWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The branches that handle a URI the app cannot open.
 *
 * These were previously written off as needing a SAF grant to be revoked mid-job. That
 * was wrong: a URI that was *never* valid reaches exactly the same null branch as one
 * whose permission was withdrawn, and pointing at a provider that does not exist is
 * trivial to arrange.
 *
 * The assertions deliberately check the *outcome* rather than a specific exception
 * type. Whether the resolver returns null or throws is an implementation detail of the
 * provider; what matters is that the job fails cleanly and tells the user something,
 * instead of crashing the worker.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class UnopenableUriTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)
    private lateinit var staged: File

    /** A syntactically valid content URI whose authority does not exist. */
    private val bogus: Uri = Uri.parse("content://dev.jasonmross.nonexistent.provider/media/1")

    @Before
    fun setUp() {
        staged = File(context.cacheDir, "unopenable_src.bin").apply { writeBytes(ByteArray(2048)) }
    }

    @After
    fun tearDown() {
        FakeFailures.reset()
        staged.delete()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    private suspend fun runToCompletion(request: androidx.work.OneTimeWorkRequest): WorkInfo? {
        workManager.enqueue(request).result.get()
        return withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }
    }

    // --- 1. conversion input that cannot be opened ---------------------------

    @Test
    fun aConversionInputThatCannotBeOpenedFailsCleanly(): Unit = runBlocking {
        val request = ConversionWorker.request(
            inputUri = bogus,
            displayName = "gone.mp4",
            sizeBytes = 1024,
        )
        val terminal = runToCompletion(request)

        assertEquals(WorkInfo.State.FAILED, terminal?.state)
        assertTrue(
            "the failure should carry a message for the user",
            !terminal?.outputData?.getString(ConversionWorker.KEY_ERROR).isNullOrBlank(),
        )
    }

    // --- 2. destination that cannot be written -------------------------------

    @Test
    fun publishingToAnUnwritableDestinationThrowsRatherThanSilentlySucceeding() {
        val publisher = OutputPublisher(context)
        val failure = runCatching { publisher.publish(staged, bogus) }.exceptionOrNull()
        assertTrue(
            "publishing to a dead provider must not appear to succeed, got $failure",
            failure != null,
        )
    }

    // --- 3. concat input that cannot be opened -------------------------------

    @Test
    fun aJoinInputThatCannotBeOpenedFailsCleanly(): Unit = runBlocking {
        val out = File(context.cacheDir, "joined_unopenable.mp4").also { it.delete() }
        val failure = runCatching {
            ConcatEngine(context).join(listOf(bogus, bogus), out)
        }.exceptionOrNull()
        assertTrue("joining unopenable inputs must fail, got $failure", failure != null)
        out.delete()
    }

    // --- 4. the concat worker's missing-input-array branch -------------------

    @Test
    fun aJoinWithNoInputArrayFailsWithAMessage(): Unit = runBlocking {
        // ConcatWorker.request() always sets the array, so this branch is unreachable
        // through it -- but a worker is just input Data, and WorkManager will happily
        // run one built without it. That is also what a corrupted or version-skewed
        // queue entry would look like after an app update.
        val request = OneTimeWorkRequestBuilder<ConcatWorker>()
            .setInputData(Data.Builder().putLong(ConcatWorker.KEY_TOTAL_BYTES, 1).build())
            .build()

        val terminal = runToCompletion(request)

        assertEquals(WorkInfo.State.FAILED, terminal?.state)
        assertEquals(
            "No input files.",
            terminal?.outputData?.getString(ConcatWorker.KEY_ERROR),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 300_000L
    }
}
