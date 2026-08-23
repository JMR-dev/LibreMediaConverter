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
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.convert.StagingNames
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.ConversionRouter
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * That a finished job reports the name and type it actually produced.
 *
 * Only the worker knows both. The spec travels to it as input `Data`, and `WorkInfo` hands input
 * `Data` back to nobody — so a ViewModel picking a result up after a restart has no route to the
 * spec at all, and one watching a job it started had only its own picker, which is free to move
 * while the job runs. Two derived strings in the output `Data` close both.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class WorkerOutputNamingTest {

    private lateinit var app: Application
    private lateinit var publisher: OutputPublisher

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = AlwaysRoomPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        ConversionDependencies.software = { WritingTranscoder }
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a conversion reports the name and type its own spec produced`() {
        // MP3, which is nothing like the default preset a fresh picker offers, so a suggestion
        // built from the picker rather than from here is visibly wrong instead of accidentally
        // right.
        val staged = publisher.createStagingFile(StagingNames.forJob(JOB_ID, SPEC.extension))
        val decision = ConversionRouter.route(
            ConversionRequest(SPEC, enginePreference = EnginePreference.FORCE_SOFTWARE),
            DeviceCodecs.PERMISSIVE,
        )

        val result = runBlocking { conversionWorker().doWork() }

        assertEquals(
            ListenableWorker.Result.success(
                workDataOf(
                    ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                    ConversionWorker.KEY_ENGINE_USED to decision.engine.name,
                    ConversionWorker.KEY_ROUTE_REASON to decision.reason.explanation,
                    ConversionWorker.KEY_SUGGESTED_NAME to "holiday_converted.mp3",
                    ConversionWorker.KEY_MIME_TYPE to "audio/mpeg",
                ),
            ),
            result,
        )
    }

    @Test
    fun `a join names itself after the format it was asked for`() {
        // The join screen has no format picker, so `joined.mp4` was right by accident. Ask for
        // anything else and every hardcoded MP4 becomes wrong at once.
        assertEquals("joined.mp4", ConcatWorker.outputNameFor(OutputFormat.MP4_H264))
        assertEquals("joined.mkv", ConcatWorker.outputNameFor(OutputFormat.MKV_H264))
        assertEquals("joined.webm", ConcatWorker.outputNameFor(OutputFormat.WEBM_VP9))
    }

    private fun conversionWorker() = TestListenableWorkerBuilder<ConversionWorker>(
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

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/holiday.mp4")
        const val DISPLAY_NAME = "holiday.mp4"
        const val INPUT_BYTES = 1024L
        val SPEC = OutputFormat.MP3.spec
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000d")
    }
}
