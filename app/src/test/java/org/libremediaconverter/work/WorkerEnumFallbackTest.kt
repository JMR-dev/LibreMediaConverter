package org.libremediaconverter.work

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.SoftwareTranscoder
import org.libremediaconverter.convert.StagingNames
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.QualityTier
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * Input `Data` naming something this build does not define.
 *
 * Not a malformed-input hypothetical: WorkManager keeps queued and finished work for about a week,
 * so a downgrade — or any rollback with work still in the queue — hands this build a job enqueued
 * by another one. That is the same previous-version case [JobTags] is written for.
 *
 * What made it worth a test is *where* the reads are. All three sit above the workers' `try`, so an
 * unknown name threw `IllegalArgumentException` out of `doWork()` entirely: WorkManager logged
 * FAILURE with `reschedule = false`, the output `Data` reached the UI with zero entries so the
 * screen said "Conversion failed." with nothing else, and the staged file was never deleted. That
 * is the signature `setForeground` was moved inside the `try` to end, reached through a different
 * door.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class WorkerEnumFallbackTest {

    private lateinit var app: Application
    private lateinit var publisher: NamingPublisher

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = NamingPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        // The progress notification builds its cancel action from WorkManager.getInstance().
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a quality tier this build does not define falls back to the default`() {
        val transcoder = RequestRecordingTranscoder()
        ConversionDependencies.software = { transcoder }

        val result = runBlocking { conversionWorker(quality = "ULTRA_FIDELITY").doWork() }

        // A Result at all is half the assertion -- the read is above the try, so the defect was an
        // exception rather than a wrong answer. The other half is which tier ran: falling back to
        // something arbitrary would silently convert at a quality nobody asked for.
        assertEquals(ListenableWorker.Result.success(), stripOutput(result))
        assertEquals(listOf(QualityTier.FAST), transcoder.qualities)
    }

    @Test
    fun `an engine preference this build does not define does not end the job`() {
        // Refused on space, which is the first thing below the three reads: it proves the reads
        // were reached and returned, without dragging in a routing decision this test is not about.
        publisher.refuseSpace = true

        val result = runBlocking { conversionWorker(preference = "FORCE_QUANTUM").doWork() }

        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(ConversionWorker.KEY_ERROR to "Not enough free space to convert."),
            ),
            result,
        )
    }

    @Test
    fun `an output format this build does not define falls back to the default`() {
        runBlocking { concatWorker(format = "AVI_MPEG4").doWork() }

        // The join itself fails -- ConcatEngine is native and there is no seam for it here -- so
        // what is asserted is the name it staged under, which is where the format actually lands.
        // A format nobody could resolve must produce the default's extension, not no extension and
        // not a throw on the way past.
        assertEquals(
            listOf(StagingNames.forJob(CONCAT_ID, ConcatWorker.DEFAULT_FORMAT.extension)),
            publisher.requestedNames,
        )
    }

    @Test
    fun `a container this build does not define falls back to the default spec`() {
        assertFallsBackToDefault(container = "HOLOTAPE")
    }

    @Test
    fun `a video codec this build does not define falls back to the default spec`() {
        assertFallsBackToDefault(video = "H267")
    }

    @Test
    fun `an audio codec this build does not define falls back to the default spec`() {
        assertFallsBackToDefault(audio = "SUPER_AAC")
    }

    /**
     * Drives a job whose spec is [NOT_THE_FALLBACK] on every axis but the one named, and asserts the
     * whole spec came back as [DEFAULT_SPEC].
     *
     * **The baseline is the point.** `readSpec` returns the *entire* fallback spec the moment any
     * one axis fails to resolve, so a test starting from `MP4_H265` -- which is itself the fallback
     * -- could not tell a worker that read the spec correctly from one that gave up on it. Starting
     * from MKV/H.264 makes the difference visible on two axes at once.
     *
     * Asserting the spec that *ran*, rather than only that a `Result` came back, is the other half:
     * the defect these three are written for threw out of `doWork` entirely, so "a Result at all"
     * would pass against a fallback to something arbitrary.
     */
    private fun assertFallsBackToDefault(
        container: String = NOT_THE_FALLBACK.container.name,
        video: String = NOT_THE_FALLBACK.videoCodec.name,
        audio: String = NOT_THE_FALLBACK.audioCodec.name,
    ) {
        val transcoder = RequestRecordingTranscoder()
        ConversionDependencies.software = { transcoder }

        val result = runBlocking {
            conversionWorker(container = container, video = video, audio = audio).doWork()
        }

        assertEquals(ListenableWorker.Result.success(), stripOutput(result))
        assertEquals(listOf(DEFAULT_SPEC), transcoder.specs)
    }

    /** [ListenableWorker.Result.Success] compares its output data, which these tests do not pin. */
    private fun stripOutput(result: ListenableWorker.Result): ListenableWorker.Result =
        if (result is ListenableWorker.Result.Success) ListenableWorker.Result.success() else result

    private fun conversionWorker(
        quality: String = QualityTier.FAST.name,
        preference: String = EnginePreference.FORCE_SOFTWARE.name,
        container: String = SPEC.container.name,
        video: String = SPEC.videoCodec.name,
        audio: String = SPEC.audioCodec.name,
    ): ConversionWorker = TestListenableWorkerBuilder<ConversionWorker>(
        context = app,
        inputData = workDataOf(
            ConversionWorker.KEY_INPUT_URI to INPUT.toString(),
            ConversionWorker.KEY_DISPLAY_NAME to DISPLAY_NAME,
            ConversionWorker.KEY_SIZE_BYTES to INPUT_BYTES,
            ConversionWorker.KEY_CONTAINER to container,
            ConversionWorker.KEY_VIDEO_CODEC to video,
            ConversionWorker.KEY_AUDIO_CODEC to audio,
            ConversionWorker.KEY_QUALITY to quality,
            ConversionWorker.KEY_ENGINE_PREFERENCE to preference,
        ),
        runAttemptCount = 0,
    ).setId(CONVERSION_ID).build()

    private fun concatWorker(format: String): ConcatWorker = TestListenableWorkerBuilder<ConcatWorker>(
        context = app,
        inputData = workDataOf(
            ConcatWorker.KEY_INPUT_URIS to arrayOf(INPUT.toString(), "file:///tmp/second.mp4"),
            ConcatWorker.KEY_TOTAL_BYTES to INPUT_BYTES,
            ConcatWorker.KEY_FORMAT to format,
        ),
        runAttemptCount = 0,
    ).setId(CONCAT_ID).build()

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/holiday.mp4")
        const val DISPLAY_NAME = "holiday.mp4"
        const val INPUT_BYTES = 1024L
        val SPEC = OutputFormat.MP4_H265.spec

        /** What `readSpec` returns when any axis fails to resolve. */
        val DEFAULT_SPEC = OutputFormat.MP4_H265.spec

        /** A spec that differs from [DEFAULT_SPEC] on container *and* video codec. See the helper. */
        val NOT_THE_FALLBACK = OutputFormat.MKV_H264.spec
        val CONVERSION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000021")
        val CONCAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000022")
    }
}

/** An engine that writes the output and remembers what it was asked to produce. */
private class RequestRecordingTranscoder : SoftwareTranscoder {

    val qualities = mutableListOf<QualityTier>()

    /** The spec each run was asked for. Which one ran is what the three readSpec tests assert. */
    val specs = mutableListOf<OutputSpec>()

    override suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
    ) {
        qualities += request.quality
        specs += request.spec
        output.writeBytes(ByteArray(OUTPUT_BYTES))
    }

    private companion object {
        const val OUTPUT_BYTES = 512
    }
}
