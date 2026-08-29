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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.convert.SoftwareTranscoder
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.ContainerCapabilities
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.Validation
import org.libremediaconverter.model.VideoCodec
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * Jobs the worker refuses before it converts anything, and what it says about them.
 *
 * Two exits, both cold before this file, and both reachable for the same underlying reason: **a job
 * does not have to come from the picker.** WorkManager keeps queued and finished work for about a
 * week, so a downgrade or a rollback hands this build a job enqueued by another one — the premise
 * `WorkerEnumFallbackTest` and `JobTags` are both written on — and `ConversionWorker.request(...)`
 * is callable directly.
 *
 * What makes these worth their own file rather than another case in an existing one is that both
 * are about the *message*. A refusal that fails with empty output `Data` renders the UI's generic
 * "Conversion failed." with nothing else to say, which is the defect shape `DeniedForegroundStartTest`
 * records from the device pass. Asserting the verdict alone would pass against exactly that.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class RefusedJobTest {

    private lateinit var app: Application
    private lateinit var publisher: OutputPublisher
    private lateinit var engine: RefusingTranscoder

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = AlwaysRoomPublisher(app)
        engine = RefusingTranscoder()
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.software = { engine }
        // Neither test is about probing or about this machine's codecs; both would otherwise decide
        // the outcome for reasons no assertion mentions. See WorkerCancellationTest's setUp.
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a job with no input URI fails with a message rather than a bare failure`() {
        val result = runBlocking { workerWithout(ConversionWorker.KEY_INPUT_URI).doWork() }

        // `Failure.equals` compares output data, so this pins the message and the verdict together.
        assertEquals(
            ListenableWorker.Result.failure(workDataOf(ConversionWorker.KEY_ERROR to "No input file.")),
            result,
        )
    }

    @Test
    fun `a job with no input URI stages nothing`() {
        // The URI read is the first thing doWork does -- above the space check, above the staging
        // name, above the try. A refusal there must not have reserved anything.
        runBlocking { workerWithout(ConversionWorker.KEY_INPUT_URI).doWork() }

        assertEquals("a job refused for having no input must not stage a file", emptyList<String>(), stagedNames())
    }

    @Test
    fun `a spec the picker would never have allowed is refused with the reason`() {
        // WAV carries PCM and nothing else. The picker cannot produce this combination today, which
        // is exactly why the worker checks: the job can arrive from a queue written before the
        // settings changed, or from a direct request(...) call.
        val expected = ContainerCapabilities.validate(REFUSED_SPEC, InputProbe()) as? Validation.Invalid
            ?: throw AssertionError("the fixture spec is supposed to be invalid; ContainerCapabilities disagrees")

        val result = runBlocking { worker(REFUSED_SPEC).doWork() }

        assertEquals(
            ListenableWorker.Result.failure(workDataOf(ConversionWorker.KEY_ERROR to expected.message)),
            result,
        )
    }

    @Test
    fun `a refused spec never reaches an engine`() {
        // The half that says it failed *before* converting rather than during. Without this, a
        // worker that ran the job and then reported the validation message would pass the test
        // above -- and would have spent the user's battery on a file it was going to refuse.
        runBlocking { worker(REFUSED_SPEC).doWork() }

        assertTrue("a refused spec must be refused before any engine runs", engine.invocations.isEmpty())
    }

    @Test
    fun `a valid spec is not refused`() {
        // The control. Every assertion above is about a refusal, so without this they would all
        // still pass against a worker that refused everything.
        val result = runBlocking { worker(OutputFormat.MP4_H265.spec).doWork() }

        assertEquals(ListenableWorker.Result.success(), stripOutput(result))
        assertEquals(listOf(OutputFormat.MP4_H265.spec), engine.invocations)
    }

    // --- the same refusal, on the join side ----------------------------------

    @Test
    fun `a join of a single file is refused with a message rather than joined`() {
        // The arm beside it -- a job with no URI array at all -- is covered on the device by
        // `UnopenableUriTest.aJoinWithNoInputArrayFailsWithAMessage`. This one was covered by
        // nothing in either source set, which a coverage report cannot say because it cannot see
        // androidTest: the two arms are adjacent lines and only one of them had a test.
        //
        // Reachable for the reason this file's header gives, plus one of its own: `request(...)`
        // takes a `List<Uri>` and checks nothing about its length, so a single-item join is a
        // well-formed call, not a corrupted queue entry.
        val result = runBlocking { joinWorker(INPUT).doWork() }

        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(ConcatWorker.KEY_ERROR to ConcatWorker.TOO_FEW_INPUTS_MESSAGE),
            ),
            result,
        )
    }

    @Test
    fun `a join of two files is not refused for its count`() {
        // The control, and the half that makes the test above bite on the boundary rather than on
        // the message: without it, `uris.size < 3` passes everything here.
        //
        // It refuses the space instead of letting the job run, because the next thing past the
        // count guard is `ConcatEngine`, which is native -- `NamingPublisher`'s KDoc records that
        // no JVM test gets past it. A refusal with the *space* message is proof that execution
        // reached line 57, which is proof it got past line 42, and it costs no engine to say so.
        val noRoom = NamingPublisher(app).apply { refuseSpace = true }
        ConversionDependencies.publisher = { noRoom }

        val result = runBlocking { joinWorker(INPUT, SECOND_INPUT).doWork() }

        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(ConcatWorker.KEY_ERROR to "Not enough free space to join these files."),
            ),
            result,
        )
    }

    /** [ListenableWorker.Result.Success] compares its output data, which these tests do not pin. */
    private fun stripOutput(result: ListenableWorker.Result): ListenableWorker.Result =
        if (result is ListenableWorker.Result.Success) ListenableWorker.Result.success() else result

    private fun worker(spec: OutputSpec): ConversionWorker = build(
        workDataOf(
            ConversionWorker.KEY_INPUT_URI to INPUT.toString(),
            ConversionWorker.KEY_DISPLAY_NAME to DISPLAY_NAME,
            ConversionWorker.KEY_SIZE_BYTES to INPUT_BYTES,
            ConversionWorker.KEY_CONTAINER to spec.container.name,
            ConversionWorker.KEY_VIDEO_CODEC to spec.videoCodec.name,
            ConversionWorker.KEY_AUDIO_CODEC to spec.audioCodec.name,
            ConversionWorker.KEY_ENGINE_PREFERENCE to EnginePreference.FORCE_SOFTWARE.name,
        ),
    )

    /**
     * The ordinary input `Data`, less one key.
     *
     * Built by removal rather than by spelling out a shorter map, so the test cannot drift into
     * omitting something else as well and passing for a reason it does not name.
     */
    private fun workerWithout(key: String): ConversionWorker {
        val full = OutputFormat.MP4_H265.spec
        val entries = mapOf(
            ConversionWorker.KEY_INPUT_URI to INPUT.toString(),
            ConversionWorker.KEY_DISPLAY_NAME to DISPLAY_NAME,
            ConversionWorker.KEY_SIZE_BYTES to INPUT_BYTES,
            ConversionWorker.KEY_CONTAINER to full.container.name,
            ConversionWorker.KEY_VIDEO_CODEC to full.videoCodec.name,
            ConversionWorker.KEY_AUDIO_CODEC to full.audioCodec.name,
            ConversionWorker.KEY_ENGINE_PREFERENCE to EnginePreference.FORCE_SOFTWARE.name,
        ) - key
        return build(Data.Builder().putAll(entries).build())
    }

    private fun build(data: Data): ConversionWorker =
        TestListenableWorkerBuilder<ConversionWorker>(context = app, inputData = data, runAttemptCount = 0)
            .setId(JOB_ID)
            .build()

    /**
     * A join job carrying [inputs], a declared total, and a format.
     *
     * The total is declared so `hasRoomFor` takes its `hasSpaceFor` branch: the other branch is
     * `hasSpaceForUnknownSize`, which `NamingPublisher` does not override and which would measure
     * this machine's real disk.
     */
    private fun joinWorker(vararg inputs: Uri): ConcatWorker = TestListenableWorkerBuilder<ConcatWorker>(
        context = app,
        inputData = workDataOf(
            ConcatWorker.KEY_INPUT_URIS to inputs.map(Uri::toString).toTypedArray(),
            ConcatWorker.KEY_TOTAL_BYTES to INPUT_BYTES * inputs.size,
            ConcatWorker.KEY_FORMAT to OutputFormat.MP4_H264.name,
        ),
        runAttemptCount = 0,
    ).setId(JOB_ID).build()

    private fun stagedNames(): List<String> =
        publisher.createStagingFile("anything").parentFile?.listFiles().orEmpty().map { it.name }.sorted()

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/holiday.mp4")
        const val DISPLAY_NAME = "holiday.mp4"
        const val INPUT_BYTES = 1024L

        /** A join needs two, and "two" is the boundary the count guard is about. */
        val SECOND_INPUT: Uri = Uri.parse("file:///tmp/holiday-2.mp4")

        /** WAV carries PCM and nothing else, so AAC in WAV has nowhere to go. */
        val REFUSED_SPEC = OutputSpec(
            org.libremediaconverter.model.Container.WAV,
            VideoCodec.NONE,
            AudioCodec.AAC,
        )
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000005")
    }
}

/** An engine that records what it was asked for and writes an output, so a success is a success. */
private class RefusingTranscoder : SoftwareTranscoder {

    /** Every spec that actually reached an engine. Empty is the assertion for a refused job. */
    val invocations = mutableListOf<OutputSpec>()

    override suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
    ) {
        invocations += request.spec
        output.writeBytes(ByteArray(OUTPUT_BYTES))
    }

    private companion object {
        const val OUTPUT_BYTES = 512
    }
}
