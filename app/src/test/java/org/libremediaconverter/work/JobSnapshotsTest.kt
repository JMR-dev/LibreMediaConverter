package org.libremediaconverter.work

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The edge that feeds the reattachment decision.
 *
 * [Reattachment.choose] is a pure function with twenty tests, and every input it reasons over is
 * computed here — by the one part of reattachment that has to touch WorkManager and the
 * filesystem. That asymmetry was the gap: the rule was pinned exhaustively while the values it
 * ran on were pinned nowhere, so a regression in this file left the whole suite green. Two
 * demonstrated ones: dropping the empty-file filter offered a zero-byte staged file as a savable
 * result, and hardcoding [JobSnapshot.outputModifiedAt] to zero starved the newest-file tie-break
 * of the only data it has.
 *
 * A real `WorkManager` and a real `cacheDir`, because both are what the code under test is for.
 * The worker never runs: [EchoingWorkerFactory] stands in for a job that finished in a process
 * that no longer exists, which is the only way a snapshot with an output path comes to exist at
 * all.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class JobSnapshotsTest {

    private lateinit var app: Application
    private lateinit var workManager: WorkManager
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.ASSERT)
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(EchoingWorkerFactory)
                .build(),
        )
        workManager = WorkManager.getInstance(app)
        stagingDir = File(app.cacheDir, "conversions").apply { mkdirs() }
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `a staged file with nothing in it is not an output`() {
        // Zero bytes is what a job killed before its engine wrote anything leaves behind. Treating
        // it as a result would publish it: the user taps Save and gets a zero-byte "conversion"
        // rather than a message, which is worse than not being offered it.
        val empty = stagedFile("empty.mp4", bytes = 0)
        val real = stagedFile("real.mp4", bytes = 4096)
        // Never created at all -- the OS reclaimed the cache, or the file was saved and deleted.
        val reclaimed = File(stagingDir, "reclaimed.mp4")
        listOf(empty, real, reclaimed).forEach(::finishedWithOutput)

        val snapshots = snapshots()

        assertEquals(
            "only a file with bytes in it is a result",
            mapOf(
                empty.absolutePath to false,
                real.absolutePath to true,
                reclaimed.absolutePath to false,
            ),
            snapshots.associate { it.outputPath to it.outputExists },
        )
        // The path is still reported for all three. It is what the worker said; whether it still
        // names anything is the separate question above.
        assertEquals(
            setOf(empty.absolutePath, real.absolutePath, reclaimed.absolutePath),
            snapshots.mapNotNull { it.outputPath }.toSet(),
        )
        // And a file that is not an output has no time either: an mtime read off a zero-byte
        // leftover would feed the tie-break a moment nothing produced.
        assertEquals(0L, snapshotFor(snapshots, empty).outputModifiedAt)
    }

    @Test
    fun `each result carries the time its own file was last written`() {
        val older = stagedFile("older.mp4", bytes = 4096)
        val newer = stagedFile("newer.mp4", bytes = 4096)
        // Set explicitly rather than relying on the order the two were written: a filesystem is
        // free to give both the same mtime, and then the fixture would be testing nothing.
        assertTrue(older.setLastModified(OLDER_MS))
        assertTrue(newer.setLastModified(NEWER_MS))
        assertTrue(
            "the two fixtures must really carry different times, got ${older.lastModified()}",
            older.lastModified() < newer.lastModified(),
        )
        listOf(older, newer).forEach(::finishedWithOutput)

        val snapshots = snapshots()

        // Compared against what the filesystem stored rather than against what was requested,
        // because mtime granularity is the filesystem's business and not this test's claim.
        assertEquals(older.lastModified(), snapshotFor(snapshots, older).outputModifiedAt)
        assertEquals(newer.lastModified(), snapshotFor(snapshots, newer).outputModifiedAt)

        // Why the field exists, asserted through the rule that reads it: the tag query has no
        // ORDER BY, so without a real time here an arbitrary winner would win every launch while
        // the other result stayed unreachable for as long as its file existed.
        assertEquals(newer.absolutePath, Reattachment.choose(snapshots)?.job?.outputPath)
    }

    /**
     * A job in the tag query that never recorded an output path at all.
     *
     * Distinct from the three cases above, which all *have* a path and differ in what it names. A
     * job still running, or one that finished without writing its result key, carries no path at
     * all -- and `getWorkInfosByTagFlow` returns it alongside the finished ones, because the tag is
     * the worker class and every attempt ever enqueued carries it.
     *
     * The guard is the `?.` in `path?.let(::File)`. Without it the null goes straight into a `File`
     * constructor. What this pins is the consequence rather than the null check: such a job must
     * not be offered as a result, so `Reattachment.choose` has to walk past it to the job that
     * really produced a file. Choosing it would put a Converted screen in front of the user with a
     * Save button that has nothing to save.
     */
    @Test
    fun `a job that recorded no output path is not offered as a result`() {
        val real = stagedFile("real.mp4", bytes = 4096)
        finishedWithOutput(real)
        finishedWithNoOutput()

        val snapshots = snapshots()

        assertEquals("both jobs carry the tag, so both come back", 2, snapshots.size)
        val silent = snapshots.single { it.outputPath == null }
        assertFalse("no path means no output, not an empty one", silent.outputExists)
        assertEquals("and no time either, for the same reason", 0L, silent.outputModifiedAt)
        assertEquals(
            "the reattachment has to walk past it to the job that really produced a file",
            real.absolutePath,
            Reattachment.choose(snapshots)?.job?.outputPath,
        )
    }

    private fun snapshots(): List<JobSnapshot> = runBlocking {
        workManager.jobSnapshots(
            tag = ConversionWorker::class.java.name,
            outputPathKey = ConversionWorker.KEY_OUTPUT_PATH,
        )
    }

    private fun snapshotFor(snapshots: List<JobSnapshot>, output: File): JobSnapshot =
        snapshots.single { it.outputPath == output.absolutePath }

    private fun stagedFile(name: String, bytes: Int): File =
        File(stagingDir, name).apply { writeBytes(ByteArray(bytes)) }

    /**
     * A conversion that finished with [output] as its result and nobody watching.
     *
     * Built rather than taken from `ConversionWorker.request`, because what has to reach
     * `jobSnapshots` is the *output* `Data` of a finished job, and a request only carries input.
     */
    private fun finishedWithOutput(output: File) {
        workManager.enqueue(
            OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(workDataOf(ConversionWorker.KEY_OUTPUT_PATH to output.absolutePath))
                .build(),
        ).result.get()
    }

    /** A job that carries the tag and no result key -- still running, or finished without one. */
    private fun finishedWithNoOutput() {
        workManager.enqueue(OneTimeWorkRequestBuilder<ConversionWorker>().build()).result.get()
    }

    private companion object {
        /** Two fixed moments a day apart, so the ordering is stated rather than raced for. */
        const val OLDER_MS = 1_700_000_000_000L
        const val NEWER_MS = OLDER_MS + 24L * 60 * 60 * 1000
    }
}

/**
 * Stands in for whichever job finished before this process existed, reporting the output path it
 * was handed.
 *
 * The real [ConversionWorker] cannot run here — it drives Media3 and FFmpeg through native
 * libraries that do not exist on the JVM — and what `jobSnapshots` needs from it is only a
 * SUCCEEDED `WorkInfo` carrying an output path. Echoing the input means one factory can produce
 * several jobs with results of their own, which is what the ordering and aliasing cases need.
 */
private object EchoingWorkerFactory : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker = object : Worker(appContext, workerParameters) {
        override fun doWork(): Result {
            val path = inputData.getString(ConversionWorker.KEY_OUTPUT_PATH)
                ?: return Result.success()
            return Result.success(workDataOf(ConversionWorker.KEY_OUTPUT_PATH to path))
        }
    }
}
