package dev.jasonmross.mediaconverter.work

import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
 * Exercises the real WorkManager path, not a test double.
 *
 * The point is to cover what a fake worker runner would skip: `setForeground` with a
 * foreground service type, on a device whose API level actually enforces the rules.
 * Declaring the wrong type — or forgetting to declare it on WorkManager's
 * SystemForegroundService in the manifest — fails here rather than in production.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ConversionWorkerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)
    private lateinit var input: File

    @Before
    fun setUp() {
        input = File(context.cacheDir, "worker_sample.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264.mp4")
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
    }

    @After
    fun tearDown() {
        input.delete()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun foregroundTypeMatchesTheRunningApiLevel() {
        val expected = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        assertEquals(expected, ConversionForegroundType.current())
    }

    @Test
    fun runsAConversionThroughWorkManager() = runBlocking {
        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = "worker_sample.mp4",
            sizeBytes = input.length(),
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { info ->
                info != null && info.state.isFinished
            }
        }

        assertEquals(
            "worker did not succeed: ${terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)}",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )

        val path = terminal?.outputData?.getString(ConversionWorker.KEY_OUTPUT_PATH)
        assertTrue("no output path in result", path != null)
        val output = File(path!!)
        assertTrue("output file missing", output.exists())
        assertTrue("output file empty", output.length() > 0)
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
