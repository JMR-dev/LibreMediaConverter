package org.libremediaconverter.work

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.HardwareTranscoder
import org.libremediaconverter.convert.SoftwareTranscoder
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.Container
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
 * What happens when the hardware engine does not finish the job.
 *
 * `runMedia3OrFallBack` was eleven lines at 0% on the JVM and `isCancellation` had never been
 * called by any unit test at all. Its own KDoc calls the fallback the protection against vendor
 * hardware encoders that "cannot be tested for correctness", so it is the branch most likely to
 * matter on a device nobody here owns — and it was reachable the whole time through
 * `ConversionDependencies.hardware`, which no unit test had ever used.
 *
 * The sharp one is cancellation. `runMedia3OrFallBack` catches `Throwable`, so without the
 * `isCancellation` re-throw a user cancelling a hardware transcode would have the app quietly
 * start a *second* conversion in software — the one thing cancelling is supposed to prevent.
 *
 * `ForcedFailureTest` covers the failure half on a device. It does not cover the cancellation half,
 * and this host cannot run it either way.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class HardwareFallbackTest {

    private lateinit var app: Application
    private lateinit var hardware: RecordingHardwareTranscoder
    private lateinit var software: RecordingSoftwareTranscoder

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        hardware = RecordingHardwareTranscoder()
        software = RecordingSoftwareTranscoder()
        ConversionDependencies.publisher = { AlwaysRoomPublisher(app) }
        ConversionDependencies.hardware = { hardware }
        ConversionDependencies.software = { software }
        // A probe with real codecs, not the default: `InputProbe()` reports UNPARSEABLE, which
        // PERMISSIVE.canDecode refuses, and the router would send every job here straight to
        // FFmpeg without any of these tests mentioning why.
        ConversionDependencies.probe = { _, _ -> H264_SOURCE }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a hardware failure runs the job again in software, on a clean staging file`() {
        hardware.failWith = { error("the vendor encoder produced nothing usable") }

        val result = runBlocking { worker().doWork() }

        assertTrue("the job should still succeed, got $result", result is ListenableWorker.Result.Success)
        assertEquals("the hardware engine gets exactly one attempt", 1, hardware.attempts)
        assertEquals("and the job then goes to software", 1, software.attempts)
        // The `staged.delete()` between the two, asserted where it is observable: FFmpeg must not
        // find a half-written hardware output sitting at the path it is about to write.
        assertFalse(
            "the partial hardware output must be gone before FFmpeg starts",
            software.outputExistedOnEntry,
        )
        assertEquals("the hardware engine is closed either way", 1, hardware.closes)
    }

    @Test
    fun `a cancelled hardware transcode is not quietly retried in software`() {
        hardware.failWith = { throw CancellationException("the user pressed Cancel") }

        assertThrows(CancellationException::class.java) { runBlocking { worker().doWork() } }

        assertEquals("the hardware engine ran", 1, hardware.attempts)
        assertEquals(
            "cancelling must not start a second conversion -- that is the whole point of cancelling",
            0,
            software.attempts,
        )
        assertEquals("and the engine is still closed on the way out", 1, hardware.closes)
    }

    @Test
    fun `a hardware transcode that works never reaches the software engine`() {
        val result = runBlocking { worker().doWork() }

        assertTrue("got $result", result is ListenableWorker.Result.Success)
        assertEquals(1, hardware.attempts)
        assertEquals("the fallback is a fallback, not a second pass", 0, software.attempts)
        assertEquals(1, hardware.closes)
    }

    /**
     * #169: the display-name fallback, which reaches further than the notification title.
     *
     * `inputData.getString(KEY_DISPLAY_NAME) ?: "input"` had never taken its right-hand side. The
     * value is not only the foreground notification's title: it feeds `outputNameFor`, so it is
     * also the filename offered in the user's save dialog. A job enqueued by an older build, or
     * built by hand, carries no such key.
     */
    @Test
    fun `a job that names no input file still suggests an output name`() {
        val result = runBlocking { worker(displayName = null).doWork() }

        assertTrue("got $result", result is ListenableWorker.Result.Success)
        val suggested = (result as ListenableWorker.Result.Success)
            .outputData.getString(ConversionWorker.KEY_SUGGESTED_NAME)
        assertTrue(
            "expected a name built from the fallback, got $suggested",
            suggested.orEmpty().startsWith("input"),
        )
    }

    private fun worker(displayName: String? = DISPLAY_NAME): ConversionWorker {
        val spec = OutputFormat.MP4_H265.spec
        val entries = buildMap<String, Any> {
            put(ConversionWorker.KEY_INPUT_URI, INPUT.toString())
            displayName?.let { put(ConversionWorker.KEY_DISPLAY_NAME, it) }
            put(ConversionWorker.KEY_SIZE_BYTES, INPUT_BYTES)
            put(ConversionWorker.KEY_CONTAINER, spec.container.name)
            put(ConversionWorker.KEY_VIDEO_CODEC, spec.videoCodec.name)
            put(ConversionWorker.KEY_AUDIO_CODEC, spec.audioCodec.name)
            // AUTO rather than FORCE_SOFTWARE, which is what every other worker test uses and is
            // exactly why this path had no coverage: forcing software never enters the function.
            put(ConversionWorker.KEY_ENGINE_PREFERENCE, EnginePreference.AUTO.name)
        }
        return TestListenableWorkerBuilder<ConversionWorker>(
            context = app,
            inputData = Data.Builder().putAll(entries).build(),
            runAttemptCount = 0,
        ).setId(JOB_ID).build()
    }

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/holiday.mp4")
        const val DISPLAY_NAME = "holiday.mp4"
        const val INPUT_BYTES = 1024L
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000009")
        val H264_SOURCE = InputProbe(
            videoCodec = "h264",
            audioCodec = "aac",
            container = Container.MP4,
            durationMs = 1_000,
        )
    }
}

/**
 * A hardware engine that writes something before it fails, and remembers being closed.
 *
 * Writing first is the point, exactly as it is for `PartialThenFailingTranscoder`: an engine that
 * only threw would let a missing `staged.delete()` pass unnoticed.
 */
@UnstableApi
private class RecordingHardwareTranscoder : HardwareTranscoder {

    var attempts = 0
    var closes = 0
    var failWith: (() -> Unit)? = null

    override suspend fun transcode(input: Uri, output: File, request: ConversionRequest, onProgress: (Int) -> Unit) {
        attempts++
        output.writeBytes(ByteArray(PARTIAL_BYTES))
        failWith?.invoke()
    }

    override fun close() {
        closes++
    }

    private companion object {
        const val PARTIAL_BYTES = 2048
    }
}

/** The software engine, recording whether the hardware attempt's leftovers were cleared first. */
private class RecordingSoftwareTranscoder : SoftwareTranscoder {

    var attempts = 0
    var outputExistedOnEntry = false

    override suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
    ) {
        attempts++
        outputExistedOnEntry = output.exists()
        output.writeBytes(ByteArray(OUTPUT_BYTES))
    }

    private companion object {
        const val OUTPUT_BYTES = 512
    }
}
