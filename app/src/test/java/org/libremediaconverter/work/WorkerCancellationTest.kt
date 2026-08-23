package org.libremediaconverter.work

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * That a cancelled conversion stays cancelled, and still cleans up after itself.
 *
 * The worker's outer catch is `catch (e: Throwable)`, which caught `CancellationException` along
 * with everything else and answered it with a `Result`. That is a coroutine reporting completion
 * inside a scope that has already been cancelled — structured concurrency's one rule, broken
 * quietly. What made it invisible is that WorkManager marks the work `CANCELLED` itself and
 * ignores the returned `Result`, so nothing on screen ever disagreed.
 *
 * The delete on that path is not incidental and has to survive the fix: an attempt that was
 * cancelled leaves a partial file in staging, the worker starts from the top rather than resuming
 * it, and this is the only code holding its handle.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class WorkerCancellationTest {

    private lateinit var app: Application
    private lateinit var publisher: OutputPublisher

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = AlwaysRoomPublisher(app)
        ConversionDependencies.publisher = { publisher }
        // FFprobe's loader throws a bare java.lang.Error on the JVM, and the device-codec query
        // reads whatever MediaCodecList the runtime fabricates. Neither is what these tests are
        // about; both would decide the routing for reasons no assertion mentions.
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a cancelled conversion propagates instead of being turned into a Result`() {
        val worker = conversionWorker { throw CancellationException("stopped mid-transcode") }

        val thrown = runCatching { runBlocking { worker.doWork() } }.exceptionOrNull()

        assertTrue(
            "cancellation must leave doWork as cancellation, not as a Result; got $thrown",
            thrown is CancellationException,
        )
    }

    @Test
    fun `a cancelled conversion still deletes the partial it had already written`() {
        val worker = conversionWorker { throw CancellationException("stopped mid-transcode") }

        runCatching { runBlocking { worker.doWork() } }

        // The engine stub writes before it throws, so a file really existed. Asserted against the
        // whole directory rather than one path, so a name this test computes drifting from the
        // worker's cannot turn it into a question about a file nobody wrote.
        assertEquals("a cancelled attempt must not leave its partial behind", emptyList<String>(), stagedNames())
    }

    @Test
    fun `an ordinary engine failure is still answered with a Result`() {
        val worker = conversionWorker { error("the muxer was never started") }

        val result = runBlocking { worker.doWork() }

        // The other half of the rule: only cancellation propagates. Widening the rethrow to every
        // exception would take the user's error message away with it.
        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(ConversionWorker.KEY_ERROR to "the muxer was never started"),
            ),
            result,
        )
        assertEquals("a failed attempt must not leave its partial behind", emptyList<String>(), stagedNames())
    }

    /**
     * A worker routed to the software engine, which is [failure] and nothing else.
     *
     * `FORCE_SOFTWARE` rather than letting the router choose: it is the one preference that decides
     * without consulting the input at all, so the test says which engine it is replacing instead of
     * depending on a routing rule it is not about. The input is a `file://` URI for the same kind
     * of reason — a `content://` one would send the worker through FFmpegKit's SAF bridge, which is
     * native.
     */
    private fun conversionWorker(failure: () -> Nothing): ConversionWorker {
        ConversionDependencies.software = { PartialThenFailingTranscoder(failure) }
        return TestListenableWorkerBuilder<ConversionWorker>(
            context = app,
            inputData = workDataOf(
                ConversionWorker.KEY_INPUT_URI to INPUT.toString(),
                ConversionWorker.KEY_DISPLAY_NAME to DISPLAY_NAME,
                ConversionWorker.KEY_SIZE_BYTES to INPUT_BYTES,
                ConversionWorker.KEY_CONTAINER to SPEC.container.name,
                ConversionWorker.KEY_VIDEO_CODEC to SPEC.videoCodec.name,
                ConversionWorker.KEY_AUDIO_CODEC to SPEC.audioCodec.name,
                ConversionWorker.KEY_ENGINE_PREFERENCE to EnginePreference.FORCE_SOFTWARE.name,
            ),
            runAttemptCount = 0,
        ).setId(JOB_ID).build()
    }

    private fun stagedNames(): List<String> =
        publisher.createStagingFile("anything").parentFile?.listFiles().orEmpty().map { it.name }.sorted()

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/holiday.mp4")
        const val DISPLAY_NAME = "holiday.mp4"
        const val INPUT_BYTES = 1024L
        val SPEC = OutputFormat.MP4_H265.spec
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
    }
}

/**
 * An engine that writes something and then fails, which is what every real interruption looks like.
 *
 * Writing first is the point: a stub that only threw would let a missing `delete()` pass.
 */
private class PartialThenFailingTranscoder(private val failure: () -> Nothing) : SoftwareTranscoder {
    override suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
    ) {
        output.writeBytes(ByteArray(PARTIAL_BYTES))
        failure()
    }

    private companion object {
        const val PARTIAL_BYTES = 2048
    }
}
