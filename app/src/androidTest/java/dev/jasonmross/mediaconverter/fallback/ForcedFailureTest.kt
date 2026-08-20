package dev.jasonmross.mediaconverter.fallback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.jasonmross.mediaconverter.convert.ConversionDependencies
import dev.jasonmross.mediaconverter.model.Engine
import dev.jasonmross.mediaconverter.model.OutputFormat
import dev.jasonmross.mediaconverter.model.QualityTier
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
 * Forces each failure path that real media cannot reliably trigger.
 *
 * Paired with [HardwareFallbackTest], which drives the same fallback with a genuinely
 * undecodable file. This one covers the branches that would otherwise need a full disk,
 * a broken codec, or a six-hour foreground-service budget to reach.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ForcedFailureTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)
    private lateinit var input: File

    @Before
    fun setUp() {
        input = File(context.cacheDir, SAMPLE)
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(SAMPLE)
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
    }

    @After
    fun tearDown() {
        FakeFailures.reset()
        input.delete()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    private suspend fun runToCompletion(request: androidx.work.OneTimeWorkRequest): WorkInfo? {
        workManager.enqueue(request).result.get()
        return withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }
    }

    private fun convertRequest(format: OutputFormat = OutputFormat.MP4_H265) =
        ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = SAMPLE,
            sizeBytes = input.length(),
            format = format,
            quality = QualityTier.FAST,
        )

    // --- the dynamic fallback, forced rather than provoked -------------------

    @Test
    fun hardwareFailureFallsBackToSoftware(): Unit = runBlocking {
        val hardware = FakeFailures.ExplodingHardware()
        val software = FakeFailures.RecordingSoftware()
        ConversionDependencies.hardware = { hardware }
        ConversionDependencies.software = { software }

        val terminal = runToCompletion(convertRequest(OutputFormat.MP4_H264))

        assertEquals(WorkInfo.State.SUCCEEDED, terminal?.state)
        assertTrue("the hardware path should have been attempted", hardware.called)
        assertTrue("the software path should have rescued it", software.called)
    }

    @Test
    fun whenBothEnginesFailTheJobFailsWithTheReason(): Unit = runBlocking {
        ConversionDependencies.hardware = { FakeFailures.ExplodingHardware() }
        ConversionDependencies.software = { FakeFailures.ExplodingSoftware("no codec available") }

        val terminal = runToCompletion(convertRequest(OutputFormat.MP4_H264))

        assertEquals(WorkInfo.State.FAILED, terminal?.state)
        assertEquals(
            "the user should see why it failed",
            "no codec available",
            terminal?.outputData?.getString(ConversionWorker.KEY_ERROR),
        )
    }

    @Test
    fun aJobRoutedStraightToFfmpegDoesNotTouchTheHardwarePath(): Unit = runBlocking {
        val hardware = FakeFailures.ExplodingHardware()
        val software = FakeFailures.RecordingSoftware()
        ConversionDependencies.hardware = { hardware }
        ConversionDependencies.software = { software }

        // MP3 has no Android encoder at all, so the router must bypass Media3 entirely.
        val terminal = runToCompletion(convertRequest(OutputFormat.MP3))

        assertEquals(WorkInfo.State.SUCCEEDED, terminal?.state)
        assertEquals(
            Engine.FFMPEG.name,
            terminal?.outputData?.getString(ConversionWorker.KEY_ENGINE_USED),
        )
        assertTrue(!hardware.called, "the hardware path must not be attempted for MP3")
        assertTrue("software should have run", software.called)
    }

    // --- the free-space precheck -------------------------------------------

    @Test
    fun aFullDiskFailsBeforeAnyConversionStarts(): Unit = runBlocking {
        val hardware = FakeFailures.ExplodingHardware()
        ConversionDependencies.publisher = { FakeFailures.FullDisk(it) }
        ConversionDependencies.hardware = { hardware }

        val terminal = runToCompletion(convertRequest())

        assertEquals(WorkInfo.State.FAILED, terminal?.state)
        assertTrue(
            "the message should mention space, was: " +
                terminal?.outputData?.getString(ConversionWorker.KEY_ERROR),
            terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)
                .orEmpty().contains("space", ignoreCase = true),
        )
        assertTrue(
            "no conversion should be attempted when the disk is full",
            !hardware.called,
        )
    }

    @Test
    fun aFullDiskFailsAJoinBeforeItStarts(): Unit = runBlocking {
        ConversionDependencies.publisher = { FakeFailures.FullDisk(it) }

        val request = ConcatWorker.request(
            inputs = listOf(Uri.fromFile(input), Uri.fromFile(input)),
            totalBytes = input.length() * 2,
        )
        val terminal = runToCompletion(request)

        assertEquals(WorkInfo.State.FAILED, terminal?.state)
        assertTrue(
            terminal?.outputData?.getString(ConcatWorker.KEY_ERROR)
                .orEmpty().contains("space", ignoreCase = true),
        )
    }

    // --- malformed input ----------------------------------------------------

    @Test
    fun aMissingInputFailsRatherThanCrashing(): Unit = runBlocking {
        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(File(context.cacheDir, "does_not_exist.mp4")),
            displayName = "does_not_exist.mp4",
            sizeBytes = 1,
        )
        val terminal = runToCompletion(request)
        assertEquals(WorkInfo.State.FAILED, terminal?.state)
    }

    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)

    private fun assertTrue(condition: Boolean, message: String) =
        org.junit.Assert.assertTrue(message, condition)

    private companion object {
        const val SAMPLE = "sample_h264.mp4"
        const val TIMEOUT_MS = 300_000L
    }
}
