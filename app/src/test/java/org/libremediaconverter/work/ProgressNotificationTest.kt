package org.libremediaconverter.work

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.testing.TestForegroundUpdater
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.SoftwareTranscoder
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

/**
 * That progress goes through WorkManager rather than around it.
 *
 * `publishProgress` called `NotificationManager.notify(1001, …)` directly, on the very id
 * WorkManager owns through `setForeground`, with a notification built `setOngoing(true)`. Two
 * owners of one id is a race, and on a Pixel 10 Pro XL it was lost on attempt 3 of 12 while
 * cancelling a `BEST`-tier job:
 *
 * ```
 * attempt 3: terminal state = CANCELLED
 * attempt 3: +300ms  active=0 id1001=false ongoing=null   <- WorkManager tore it down
 * attempt 3: +700ms  active=1 id1001=true  ongoing=true   <- a progress tick put it back
 * attempt 3: +5000ms active=1 id1001=true  ongoing=true
 * ```
 *
 * Still there ten minutes later with no app process at all. The record carried
 * `flags=ONGOING_EVENT|ONLY_ALERT_ONCE` and **no `FOREGROUND_SERVICE`**, which is what proves the
 * direct `notify` posted it rather than `setForeground` — WorkManager's own post carries that flag.
 * Whether the orphan could be swiped away was never established and is not what these tests are
 * about; the resurrection is, and it is reproduced.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ProgressNotificationTest {

    private lateinit var app: Application
    private lateinit var updater: RecordingForegroundUpdater

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        updater = RecordingForegroundUpdater()
        ConversionDependencies.publisher = { AlwaysRoomPublisher(app) }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        // The notification's cancel action is a WorkManager PendingIntent, so the worker would
        // fail for that reason rather than the one under test without this.
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `progress on a running worker updates WorkManager's own notification, not one of ours`() {
        runBlocking { workerReporting { onProgress -> onProgress(PERCENT) }.doWork() }

        // The positive half: the update really happened, on the id WorkManager is holding, and it
        // carries the percentage. Asserting only that nothing was posted directly would pass just
        // as well against a `publishProgress` that had been deleted.
        val progressUpdates = updater.infos.drop(1)
        assertEquals("one throttled progress update expected", 1, progressUpdates.size)
        assertEquals(updater.infos.first().notificationId, progressUpdates.single().notificationId)
        assertEquals(PERCENT, progressUpdates.single().notification.extras.getInt(Notification.EXTRA_PROGRESS))

        // And the negative half: nothing reached the notification manager under its own steam.
        // Asserted over the whole manager rather than one id, so a renamed constant cannot make
        // this pass by asking about a notification nobody posts.
        assertEquals("the worker must post no notification of its own", 0, postedNotifications())
    }

    @Test
    fun `a progress update that lands after the worker is stopped puts nothing back`() {
        // The device sequence, in one worker: WorkManager has torn the notification down, and a
        // tick that was already in flight arrives afterwards. `lastNotified` is still 0 here, so
        // this tick is one the throttle would have let through -- which is what makes the test
        // about `isStopped` rather than about timing.
        val worker = workerReporting { onProgress ->
            stopped(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
            onProgress(PERCENT)
        }

        runBlocking { worker.doWork() }

        assertEquals("a stopped worker must publish nothing", 1, updater.infos.size)
        assertEquals("and must resurrect nothing", 0, postedNotifications())
    }

    @Test
    fun `progress arriving several times a second is still throttled to one update`() {
        // The throttle is not decoration: FFmpeg's statistics callback and Media3's progress
        // polling both fire several times a second, and pushing every one of them janks the
        // system UI. Routing progress through `setForeground` does not make that cheaper.
        runBlocking {
            workerReporting { onProgress -> repeat(TICKS) { onProgress(it) } }.doWork()
        }

        assertTrue(
            "$TICKS ticks inside one throttle window must not be ${updater.infos.size - 1} updates",
            updater.infos.size - 1 == 1,
        )
    }

    /**
     * A worker routed to the software engine, whose engine is [report] and a written output.
     *
     * `FORCE_SOFTWARE` because it is the one preference that decides without consulting the input,
     * and a `file://` URI because a `content://` one would send the worker through FFmpegKit's SAF
     * bridge, which is native. [report] is handed the worker's own progress callback, and runs with
     * the worker as its receiver so a test can stop it mid-transcode.
     */
    private fun workerReporting(report: ConversionWorker.((Int) -> Unit) -> Unit): ConversionWorker {
        val worker = TestListenableWorkerBuilder<ConversionWorker>(
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
        ).setId(JOB_ID)
            .setForegroundUpdater(updater)
            .build()
        // Resolved when the worker reaches the engine, so assigning after `build()` is in time.
        ConversionDependencies.software = { ReportingTranscoder { onProgress -> worker.report(onProgress) } }
        return worker
    }

    /** Stops the worker the way WorkManager does, so `isStopped` becomes true. */
    private fun ConversionWorker.stopped(reason: Int) = stop(reason)

    private fun postedNotifications(): Int = shadowOf(app.getSystemService(NotificationManager::class.java)).size()

    private companion object {
        val INPUT: Uri = Uri.parse("file:///tmp/holiday.mp4")
        const val DISPLAY_NAME = "holiday.mp4"
        const val INPUT_BYTES = 1024L
        const val PERCENT = 42
        const val TICKS = 50
        val SPEC = OutputFormat.MP4_H265.spec
        val JOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000021")
    }
}

/**
 * Records every [ForegroundInfo] the worker publishes, and otherwise behaves as the test default.
 *
 * Delegating to [TestForegroundUpdater] rather than hand-rolling a `ListenableFuture<Void>`: the
 * worker awaits what this returns, so a future that never completes would hang the initial
 * `setForeground` rather than test anything.
 */
private class RecordingForegroundUpdater : TestForegroundUpdater() {
    val infos = mutableListOf<ForegroundInfo>()

    override fun setForegroundAsync(
        context: Context,
        id: UUID,
        foregroundInfo: ForegroundInfo,
    ): ListenableFuture<Void> {
        infos += foregroundInfo
        return super.setForegroundAsync(context, id, foregroundInfo)
    }
}

/** An engine that reports whatever [report] wants reported, then writes an output. */
private class ReportingTranscoder(private val report: ((Int) -> Unit) -> Unit) : SoftwareTranscoder {
    override suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
    ) {
        report(onProgress)
        output.writeBytes(ByteArray(OUTPUT_BYTES))
    }

    private companion object {
        const val OUTPUT_BYTES = 512
    }
}
