package org.libremediaconverter.work

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
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
import org.libremediaconverter.model.OutputFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * That a refused foreground-service start does not end the job.
 *
 * The wiring half of [FailureOutcomeTest], and the half the defect actually lived in.
 * `setForeground()` used to sit *above* the `try` in both workers, so the exception the system
 * throws when it refuses a background foreground-service start escaped `doWork()` altogether:
 * WorkManager logged `Worker result FAILURE` and `reschedule = false`, the output `Data` reached
 * the UI with zero entries, and the partial file the killed attempt had left in staging was never
 * deleted. Confirmed on a Pixel 10 Pro XL — 119 seconds after a `kill -9`, WorkManager recovered
 * the job unprompted and the system denied it.
 *
 * None of that is reproducible here, so what is reproduced is the single cause of it: the throw.
 * A [ForegroundUpdater] whose future completes exceptionally makes `setForeground()` throw exactly
 * what the platform throws — `WorkForegroundUpdater` deliberately propagates it rather than
 * swallowing it, and `ListenableFuture.await()` unwraps the `ExecutionException`, so the worker
 * meets it bare.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class DeniedForegroundStartTest {

    private lateinit var app: Application
    private lateinit var publisher: OutputPublisher

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = AlwaysRoomPublisher(app)
        ConversionDependencies.publisher = { publisher }
        // The progress notification builds its cancel action from WorkManager.getInstance(), which
        // throws when nothing has initialised it. Without this the worker would fail for that
        // reason rather than the one under test, and the assertions would still pass.
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a conversion whose foreground start is denied retries instead of failing terminally`() {
        val result = runBlocking { conversionWorker().doWork() }

        // Retry, not failure: the denial is about when the job ran, not about the job. Terminal
        // failure is what the device showed, and it is what "the queue survives process death"
        // cannot survive.
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `a join whose foreground start is denied retries instead of failing terminally`() {
        val result = runBlocking { concatWorker().doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `a denied foreground start collects the partial file the killed attempt left behind`() {
        // Exactly the 2 MB orphan the device pass found. A process killed mid-transcode leaves a
        // partial in staging, and the attempt WorkManager schedules to recover it stages under the
        // same name -- the job id does not move across a retry -- so reaching staged.delete() is
        // what collects it.
        stagedFile().writeBytes(ByteArray(PARTIAL_BYTES))

        runBlocking { conversionWorker().doWork() }

        // Asserted against the whole directory rather than one path. A path this test computes
        // itself can stop matching the one the worker computes, and then the assertion passes by
        // asking whether a file nobody wrote is absent.
        assertEquals(
            "a denied restart must not orphan the previous attempt's partial",
            emptyList<String>(),
            stagedNames(),
        )
    }

    @Test
    fun `a start denied past the attempt bound fails with a message the user can act on`() {
        val worker = conversionWorker(runAttemptCount = FailureOutcome.MAX_FOREGROUND_START_ATTEMPTS)

        val result = runBlocking { worker.doWork() }

        // Not merely "a failure". The defect's other half was output `Data` with zero entries, so
        // the UI rendered its generic fallback with nothing to say. `Failure.equals` compares
        // output data, which pins the message as well as the verdict.
        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(ConversionWorker.KEY_ERROR to FailureOutcome.FOREGROUND_DENIED_MESSAGE),
            ),
            result,
        )
    }

    private fun conversionWorker(runAttemptCount: Int = 0): ConversionWorker =
        TestListenableWorkerBuilder<ConversionWorker>(
            context = app,
            inputData = workDataOf(
                ConversionWorker.KEY_INPUT_URI to INPUT.toString(),
                ConversionWorker.KEY_DISPLAY_NAME to DISPLAY_NAME,
                ConversionWorker.KEY_SIZE_BYTES to INPUT_BYTES,
                ConversionWorker.KEY_CONTAINER to SPEC.container.name,
                ConversionWorker.KEY_VIDEO_CODEC to SPEC.videoCodec.name,
                ConversionWorker.KEY_AUDIO_CODEC to SPEC.audioCodec.name,
            ),
            runAttemptCount = runAttemptCount,
        ).setId(CONVERSION_ID)
            .setForegroundUpdater(DenyingForegroundUpdater)
            .build()

    private fun concatWorker(): ConcatWorker = TestListenableWorkerBuilder<ConcatWorker>(
        context = app,
        inputData = workDataOf(
            ConcatWorker.KEY_INPUT_URIS to arrayOf(INPUT.toString(), "content://test/second.mp4"),
            ConcatWorker.KEY_TOTAL_BYTES to INPUT_BYTES,
            ConcatWorker.KEY_FORMAT to OutputFormat.MP4_H264.name,
        ),
        runAttemptCount = 0,
    ).setId(CONCAT_ID)
        .setForegroundUpdater(DenyingForegroundUpdater)
        .build()

    /** The staging path the worker will compute, asked for rather than spelled out here. */
    private fun stagedFile(): File = publisher.createStagingFile(StagingNames.forJob(CONVERSION_ID, SPEC.extension))

    private fun stagedNames(): List<String> = stagedFile().parentFile?.listFiles().orEmpty().map { it.name }.sorted()

    private companion object {
        val INPUT: Uri = Uri.parse("content://test/holiday.mp4")
        const val DISPLAY_NAME = "holiday.mp4"
        const val INPUT_BYTES = 1024L
        const val PARTIAL_BYTES = 2048
        val SPEC = OutputFormat.MP4_H265.spec
        val CONVERSION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val CONCAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")
    }
}

/** Stands in for the system refusing a background foreground-service start. */
private object DenyingForegroundUpdater : ForegroundUpdater {
    override fun setForegroundAsync(
        context: Context,
        id: UUID,
        foregroundInfo: ForegroundInfo,
    ): ListenableFuture<Void> = FailedFuture(
        ForegroundServiceStartNotAllowedException(
            "startForegroundService() not allowed: service " +
                "org.libremediaconverter/androidx.work.impl.foreground.SystemForegroundService",
        ),
    )
}

/**
 * An already-failed future, written out rather than pulled from a futures library.
 *
 * `await()` takes the `isDone` fast path and unwraps the `ExecutionException`, which is what puts
 * the platform's own exception in front of the worker's catch rather than a wrapper.
 */
private class FailedFuture(private val failure: Throwable) : ListenableFuture<Void> {
    override fun addListener(listener: Runnable, executor: Executor): Unit = executor.execute(listener)
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): Void = throw ExecutionException(failure)
    override fun get(timeout: Long, unit: TimeUnit): Void = throw ExecutionException(failure)
}
