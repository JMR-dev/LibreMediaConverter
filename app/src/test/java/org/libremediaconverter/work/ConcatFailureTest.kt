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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConcatJoiner
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.StagingNames
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.ffmpeg.ConcatEngine
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.model.OutputFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * What a join does when the engine fails partway.
 *
 * Everything past `ConcatWorker`'s `setForeground` was untested on **every** source set, and the
 * repo had already measured the cost: `PerJobStagingTest`'s KDoc records that reverting
 * `ConcatWorker` to a constant staging name left all 257 tests green, because nothing in the JVM
 * suite can get past a `ConcatEngine` constructed in place. `RefusedJobTest` says the same from the
 * other side -- "the next thing past the count guard is `ConcatEngine`, which is native".
 * `ConcatEngineTest` on a device tests the engine directly, bypassing the worker, and
 * `ConcatWorkerTest` covers only the too-few-inputs guard and the happy path.
 *
 * `ConversionDependencies.concat` is the seam that closes it, added here to sit beside the
 * `.hardware` and `.software` that `ConversionWorker` has had all along -- the asymmetry between the
 * two workers was the whole reason one of them had a tested failure path and the other did not.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConcatFailureTest {

    private lateinit var app: Application
    private lateinit var publisher: AlwaysRoomPublisher

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = AlwaysRoomPublisher(app)
        ConversionDependencies.publisher = { publisher }
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a join whose engine fails reports the engine's own reason`() {
        ConversionDependencies.concat = { FailingJoiner { error(DEMUXER_MESSAGE) } }

        val result = runBlocking { joinWorker().doWork() }

        assertEquals(
            ListenableWorker.Result.failure(workDataOf(ConcatWorker.KEY_ERROR to DEMUXER_MESSAGE)),
            result,
        )
    }

    /**
     * A failure carrying no message at all, which Kotlin and Java both allow and FFmpegKit's
     * wrappers can produce.
     *
     * Without the fallback the user is shown an empty error, and `JoinViewModel` cannot tell that
     * from a job that reported nothing -- the two would be one blank screen with different causes.
     */
    @Test
    fun `a failure with no message of its own still says something`() {
        ConversionDependencies.concat = { FailingJoiner { throw RuntimeException() } }

        val result = runBlocking { joinWorker().doWork() }

        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(ConcatWorker.KEY_ERROR to ConcatWorker.GENERIC_FAILURE_MESSAGE),
            ),
            result,
        )
    }

    /**
     * The staged file is deleted on the way out.
     *
     * The joiner writes before it fails, exactly as `PartialThenFailingTranscoder` does on the
     * conversion side: a stub that only threw would let a missing `staged.delete()` pass. What it
     * costs to lose is a full-size partial per failed join, sitting in cache until the sweep is old
     * enough to be sure nobody is coming back for it.
     *
     * Asserted against the file the joiner was actually handed rather than by scanning the staging
     * directory for a name. The first draft did scan, for a `"join-"` prefix that
     * `StagingNames.forJob` does not produce -- it names files `<jobId>.<ext>` -- so the assertion
     * was trivially true and the mutation walked straight through it.
     */
    @Test
    fun `a failed join leaves nothing behind in staging`() {
        val joiner = FailingJoiner { error(DEMUXER_MESSAGE) }
        ConversionDependencies.concat = { joiner }

        runBlocking { joinWorker().doWork() }

        val staged = requireNotNull(joiner.lastOutput) { "the joiner never ran, so this proves nothing" }
        assertEquals(
            "the fixture has to write before it fails, or the delete is unobservable",
            PARTIAL_BYTES,
            joiner.bytesWritten,
        )
        assertFalse("a failed join must not leave its partial behind: $staged", staged.exists())
    }

    /**
     * The success path, and the staging name #159's fixture and `PerJobStagingTest` both care about.
     *
     * Worth its place rather than a happy-path formality: `PerJobStagingTest`'s KDoc records that
     * **reverting `ConcatWorker` to a constant staging name left all 257 tests green**, because
     * nothing could reach the line that names the file. This is the test that was missing when that
     * was written -- the join's output `Data` had never been read by anything on the JVM.
     *
     * The staged path is asserted to carry the job id, not a constant: two joins of the same format
     * sharing one name is the defect, and `ConcatEngine`'s list file collided harder still.
     */
    @Test
    fun `a join that works reports its own staged file, strategy and name`() {
        val joiner = SucceedingJoiner()
        ConversionDependencies.concat = { joiner }

        val result = runBlocking { joinWorker().doWork() }

        assertTrue("got $result", result is ListenableWorker.Result.Success)
        val data = (result as ListenableWorker.Result.Success).outputData
        assertEquals(
            "the staged file has to be this job's, not a name every join shares",
            File(publisherStagingDir(), StagingNames.forJob(JOB_ID, OutputFormat.MP4_H264.extension)).absolutePath,
            data.getString(ConcatWorker.KEY_OUTPUT_PATH),
        )
        assertEquals(ConcatStrategy.STREAM_COPY.name, data.getString(ConcatWorker.KEY_STRATEGY))
        assertEquals(OutputFormat.MP4_H264.mimeType, data.getString(ConcatWorker.KEY_MIME_TYPE))
        assertTrue(
            "the save dialog needs a name with the right extension, got ${data.getString(
                ConcatWorker.KEY_SUGGESTED_NAME,
            )}",
            data.getString(ConcatWorker.KEY_SUGGESTED_NAME).orEmpty().endsWith(".${OutputFormat.MP4_H264.extension}"),
        )
    }

    /**
     * The arm beside the count guard: no URI array at all.
     *
     * Covered today only by `UnopenableUriTest` on a device, although it runs before staging and
     * before any native code. It is the exact sibling of `RefusedJobTest`'s ConversionWorker twin,
     * and it belongs on the JVM with it -- a device test for a branch that needs no device is a
     * slower test that reports later.
     */
    @Test
    fun `a join with no input array at all is refused with a message`() {
        val result = runBlocking {
            TestListenableWorkerBuilder<ConcatWorker>(
                context = app,
                inputData = workDataOf(ConcatWorker.KEY_FORMAT to OutputFormat.MP4_H264.name),
                runAttemptCount = 0,
            ).setId(JOB_ID).build().doWork()
        }

        assertEquals(
            ListenableWorker.Result.failure(workDataOf(ConcatWorker.KEY_ERROR to ConcatWorker.NO_INPUTS_MESSAGE)),
            result,
        )
    }

    private fun publisherStagingDir(): File? = publisher.createStagingFile("probe").parentFile

    private fun joinWorker(): ConcatWorker = TestListenableWorkerBuilder<ConcatWorker>(
        context = app,
        inputData = workDataOf(
            ConcatWorker.KEY_INPUT_URIS to arrayOf(FIRST.toString(), SECOND.toString()),
            ConcatWorker.KEY_TOTAL_BYTES to TOTAL_BYTES,
            ConcatWorker.KEY_FORMAT to OutputFormat.MP4_H264.name,
        ),
        runAttemptCount = 0,
    ).setId(JOB_ID).build()

    private companion object {
        val FIRST: Uri = Uri.parse("file:///tmp/one.mp4")
        val SECOND: Uri = Uri.parse("file:///tmp/two.mp4")
        const val TOTAL_BYTES = 2048L
        const val DEMUXER_MESSAGE = "the demuxer rejected the input list"
        const val PARTIAL_BYTES = 2048
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000b")
    }
}

/** A joiner that writes something and then fails, so a missing `staged.delete()` cannot pass. */
private class FailingJoiner(private val failure: () -> Nothing) : ConcatJoiner {

    /** The handle the worker created, kept so a test can ask whether it survived the failure. */
    var lastOutput: File? = null
    var bytesWritten = 0

    override suspend fun join(inputs: List<Uri>, output: File, format: OutputFormat): ConcatEngine.Result {
        lastOutput = output
        output.writeBytes(ByteArray(PARTIAL_BYTES))
        bytesWritten = PARTIAL_BYTES
        failure()
    }

    private companion object {
        const val PARTIAL_BYTES = 2048
    }
}

/** The joiner that finishes, so the success path and the output `Data` can be read on the JVM. */
private class SucceedingJoiner : ConcatJoiner {
    override suspend fun join(inputs: List<Uri>, output: File, format: OutputFormat): ConcatEngine.Result {
        output.writeBytes(ByteArray(OUTPUT_BYTES))
        return ConcatEngine.Result(ConcatStrategy.STREAM_COPY, output)
    }

    private companion object {
        const val OUTPUT_BYTES = 4096
    }
}
