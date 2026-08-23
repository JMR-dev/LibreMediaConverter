package org.libremediaconverter.work

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.convert.SoftwareTranscoder
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * That two jobs cannot write the same staged file.
 *
 * Observed rather than imagined: two independent conversions on a Pixel each computed
 * `cache/conversions/input_converted.mp4`, the second overwrote the first, and a tag query in a
 * fresh process then returned two SUCCEEDED `WorkInfo`s naming that one file — which is what
 * makes reattachment ambiguous about which job produced what is on disk.
 *
 * These drive the real worker rather than the naming function, because the naming function was
 * never the part that was wrong. What was wrong is which name the worker asked for.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class PerJobStagingTest {

    private lateinit var app: Application
    private lateinit var publisher: OutputPublisher
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = AlwaysRoomPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        ConversionDependencies.software = { WritingTranscoder }
        installTestWorkManager(app, Data.EMPTY)

        stagingDir = publisher.createStagingFile("anything").parentFile!!
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `two conversions of the same file stage under names of their own`() {
        runBlocking { conversionWorker(JOB_A).doWork() }
        runBlocking { conversionWorker(JOB_B).doWork() }

        // Two jobs, two files. One file here means the second job overwrote the first's output
        // while both went on reporting that path as their result.
        assertEquals(
            "each job must have staged its own file, found ${stagedNames()}",
            2,
            stagedNames().size,
        )
    }

    @Test
    fun `a second attempt at one job reuses the first attempt's staging path`() {
        runBlocking { conversionWorker(JOB_A, runAttemptCount = 0).doWork() }
        val first = stagedNames()

        runBlocking { conversionWorker(JOB_A, runAttemptCount = 1).doWork() }

        // A per-attempt name would leak one file per retry, and would stop the catch on the way
        // out of a failed attempt from collecting the partial the previous one left.
        assertEquals("a retry must not stage under a new name", first, stagedNames())
    }

    @Test
    fun `a display name that tries to climb out of staging cannot`() {
        // Display names come from a document provider and are not this app's to trust: one can
        // contain a separator, be empty, or be four kilobytes long. Deriving the staged path from
        // it put all of that on a filesystem path. Naming the job instead retires the question
        // rather than answering it with a sanitiser.
        runBlocking { conversionWorker(JOB_A, displayName = "../escape.mp4").doWork() }

        assertEquals("the output belongs in staging", 1, stagedNames().size)
        assertFalse(
            "nothing may be written outside the staging directory",
            File(app.cacheDir, "escape_converted.mp4").exists(),
        )
    }

    private fun stagedNames(): List<String> = stagingDir.listFiles().orEmpty().map { it.name }.sorted()

    private fun conversionWorker(
        id: UUID,
        runAttemptCount: Int = 0,
        displayName: String = DISPLAY_NAME,
    ): ConversionWorker = TestListenableWorkerBuilder<ConversionWorker>(
        context = app,
        inputData = workDataOf(
            ConversionWorker.KEY_INPUT_URI to INPUT.toString(),
            ConversionWorker.KEY_DISPLAY_NAME to displayName,
            ConversionWorker.KEY_SIZE_BYTES to INPUT_BYTES,
            ConversionWorker.KEY_CONTAINER to SPEC.container.name,
            ConversionWorker.KEY_VIDEO_CODEC to SPEC.videoCodec.name,
            ConversionWorker.KEY_AUDIO_CODEC to SPEC.audioCodec.name,
            ConversionWorker.KEY_ENGINE_PREFERENCE to EnginePreference.FORCE_SOFTWARE.name,
        ),
        runAttemptCount = runAttemptCount,
    ).setId(id).build()

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/input.mp4")
        const val DISPLAY_NAME = "input.mp4"
        const val INPUT_BYTES = 1024L
        val SPEC = OutputFormat.MP4_H265.spec
        val JOB_A: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000a")
        val JOB_B: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000b")
    }
}

/** An engine that only writes the file, which is the whole of what these tests look at. */
private object WritingTranscoder : SoftwareTranscoder {
    override suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
    ) {
        output.writeBytes(ByteArray(OUTPUT_BYTES))
    }

    private const val OUTPUT_BYTES = 512
}
