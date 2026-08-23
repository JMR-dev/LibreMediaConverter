package org.libremediaconverter.convert

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.StateFlow
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shared scaffolding for the two ViewModel cleanup tests.
 *
 * These tests exist because [StagingSweepTest] and [OutputPublisherStagingTest] both prove
 * the *tool* works while saying nothing about whether anything calls it — and the wiring is
 * where D2 actually lived. Deleting the `discardStaged` line from either `reset()` left all
 * of the earlier tests green.
 */

/**
 * A real [OutputPublisher] that records what it was asked to discard.
 *
 * It still really deletes, so the assertions are about the filesystem rather than about a
 * mock's memory. [publish] is stubbed because the SAF destination is not what these tests
 * are about, and because making it throw is the only way to reach the failed-save branch
 * deterministically.
 */
open class RecordingPublisher(context: Context) : OutputPublisher(context) {

    val discarded = mutableListOf<File>()

    /** When set, [publish] throws it — the failed-save path. */
    var publishFailure: Throwable? = null

    override fun publish(staged: File, destination: Uri) {
        publishFailure?.let { throw it }
    }

    override fun discardStaged(staged: File): Boolean {
        discarded += staged
        return super.discardStaged(staged)
    }
}

/**
 * Stands in for whichever worker is enqueued and succeeds immediately with [outputData].
 *
 * The real workers cannot run here: both drive FFmpeg or Media3 through native libraries
 * that do not exist on the JVM. What the ViewModel actually needs from them is one
 * `SUCCEEDED` `WorkInfo` carrying an output path, and that is exactly what this produces —
 * through a real `WorkManager`, so the ViewModel's own observer, its `SUCCEEDED` branch and
 * its cleanup handle are all the production ones.
 *
 * It also keeps every [Data] it was handed, which is the only way back to what a ViewModel
 * actually enqueued: `WorkInfo` returns a job's tags and its output and never the input `Data`
 * it was built with, so a test that wants to know what `convert()` or `join()` put in a request
 * has to catch it here, on its way to the worker.
 */
class SucceedingWorkerFactory(private val outputData: Data) : WorkerFactory() {

    /** The input `Data` of each request that has reached a worker, in order. */
    val enqueued = mutableListOf<Data>()

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker {
        enqueued += workerParameters.inputData
        return object : Worker(appContext, workerParameters) {
            override fun doWork(): Result = Result.success(outputData)
        }
    }
}

/**
 * Installs a synchronous test WorkManager whose workers succeed with [outputData].
 *
 * @return the factory, so a caller that cares can read back what was enqueued.
 */
fun installTestWorkManager(context: Context, outputData: Data): SucceedingWorkerFactory {
    val factory = SucceedingWorkerFactory(outputData)
    WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder()
            .setMinimumLoggingLevel(Log.ASSERT)
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build(),
    )
    return factory
}

/**
 * Waits for [predicate] to hold, pumping the main looper as it goes.
 *
 * Both ViewModels hop to a real `Dispatchers.IO` for file metadata and resume on the main
 * looper, which Robolectric leaves paused. So neither a bare read of `state.value` nor a
 * single `idle()` is enough, and the timeout is generous because it only has to be longer
 * than a few file stats — in practice this converges in milliseconds.
 */
fun <T> awaitState(state: StateFlow<T>, description: String, predicate: (T) -> Boolean): T {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        val current = state.value
        if (predicate(current)) return current
        Thread.sleep(POLL_INTERVAL_MS)
    }
    throw AssertionError("Timed out waiting for $description; state was ${state.value}")
}

private const val AWAIT_TIMEOUT_SECONDS = 10L
private const val POLL_INTERVAL_MS = 5L
