package dev.jasonmross.mediaconverter.join

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.jasonmross.mediaconverter.model.ConcatStrategy
import dev.jasonmross.mediaconverter.work.ConcatWorker
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
 * Joining as background work.
 *
 * Covers what the engine test cannot: the WorkManager round trip, the foreground
 * service, and the fact that the chosen strategy is reported back to the UI rather than
 * lost inside the worker.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ConcatWorkerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)
    private lateinit var clipA: File
    private lateinit var clipB: File

    @Before
    fun setUp() {
        clipA = copyAsset("clip_a.mp4")
        clipB = copyAsset("clip_b.mp4")
    }

    @After
    fun tearDown() {
        clipA.delete()
        clipB.delete()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    private fun copyAsset(name: String): File {
        val out = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name)
            .use { asset -> out.outputStream().use { asset.copyTo(it) } }
        return out
    }

    @Test
    fun joinsTwoFilesAndReportsTheStrategyUsed(): Unit = runBlocking {
        val request = ConcatWorker.request(
            inputs = listOf(Uri.fromFile(clipA), Uri.fromFile(clipB)),
            totalBytes = clipA.length() + clipB.length(),
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        assertEquals(
            "join did not succeed: ${terminal?.outputData?.getString(ConcatWorker.KEY_ERROR)}",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )
        // The strategy has to reach the UI: it is what tells the user whether their
        // files were re-encoded or copied losslessly.
        assertEquals(
            ConcatStrategy.STREAM_COPY.name,
            terminal?.outputData?.getString(ConcatWorker.KEY_STRATEGY),
        )
        val out = File(terminal!!.outputData.getString(ConcatWorker.KEY_OUTPUT_PATH)!!)
        assertTrue("no joined file written", out.exists() && out.length() > 0)
    }

    @Test
    fun aSingleInputFailsWithAnActionableMessage(): Unit = runBlocking {
        val request = ConcatWorker.request(
            inputs = listOf(Uri.fromFile(clipA)),
            totalBytes = clipA.length(),
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        assertEquals(WorkInfo.State.FAILED, terminal?.state)
        val message = terminal?.outputData?.getString(ConcatWorker.KEY_ERROR).orEmpty()
        assertTrue(
            "the message should tell the user what to do, was: '$message'",
            message.contains("two", ignoreCase = true),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
