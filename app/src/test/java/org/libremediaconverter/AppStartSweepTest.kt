package org.libremediaconverter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.StagingSweep
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * That process start actually sweeps.
 *
 * [StagingSweepTest][org.libremediaconverter.convert.StagingSweepTest] pins the age rule and
 * `OutputPublisherStagingTest` pins the sweep against a real filesystem; neither says anything
 * about whether anything calls it, and deleting the one line that does left the whole suite green.
 * That line is the only reason this Application class exists, and it is the backstop for every leak
 * `discardStaged` cannot reach — a process reclaimed before a save, a worker that failed before its
 * output ever became a `Converted` state, a `reset()` whose delete was cancelled with the Activity.
 *
 * `onCreate()` is called again rather than a second Application being built: it is what the
 * framework calls at process start, the scope it launches on is already there, and the first test
 * below is what pins that the framework calls it on *this* class.
 */
@RunWith(RobolectricTestRunner::class)
class AppStartSweepTest {

    private lateinit var app: LibreMediaConverterApp
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        // The cast is an assertion in itself: Robolectric builds the Application named in the
        // merged manifest, so this fails if `android:name` ever stops pointing here -- in which
        // case the sweep below would be perfectly correct code that never runs.
        app = RuntimeEnvironment.getApplication() as LibreMediaConverterApp
        stagingDir = File(app.cacheDir, "conversions").apply { mkdirs() }
        stagingDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `the application the manifest starts is the one that sweeps`() {
        assertEquals(LibreMediaConverterApp::class.java, RuntimeEnvironment.getApplication().javaClass)
    }

    @Test
    fun `process start collects an abandoned staged file and leaves a live one alone`() {
        val abandoned = stagedFile("abandoned.mp4")
        val live = stagedFile("live.mp4")
        // Set explicitly. Relying on a file being written "long enough ago" is not something a test
        // can arrange, and the grace period is a day.
        assertTrue(
            abandoned.setLastModified(System.currentTimeMillis() - StagingSweep.GRACE_PERIOD_MS - ONE_MINUTE_MS),
        )

        // Both files are still here on the way in. The Application was already constructed once
        // before this test ran, so without this the sweep that call started could be the one that
        // collected the file, and the assertion below would be about the wrong process start.
        assertTrue(abandoned.exists() && live.exists())

        app.onCreate()

        awaitGone(abandoned)
        // The other half, and the one that says the sweep is a sweep rather than a
        // `clearStaging()`: the directory is shared by the convert tab, the join tab and
        // ConcatEngine's list file, so deleting everything could take a file from a running job.
        assertTrue("a file written moments ago belongs to a live job", live.exists())
    }

    /**
     * Waits for [file] to be deleted.
     *
     * The sweep runs on `Dispatchers.IO`, deliberately: it lists a directory and stats every entry
     * on the path that decides how long the launcher icon stays unresponsive. So there is nothing
     * to join, and the wait is a bounded poll — long enough for a directory listing, short enough
     * that a sweep which never happens fails rather than hangs.
     */
    private fun awaitGone(file: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (!file.exists()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail("process start left ${file.name} in staging; nothing swept it")
    }

    private fun stagedFile(name: String): File = File(stagingDir, name).apply { writeBytes(ByteArray(4096)) }

    private companion object {
        const val ONE_MINUTE_MS = 60L * 1000
        const val AWAIT_TIMEOUT_SECONDS = 10L
        const val POLL_INTERVAL_MS = 5L
    }
}
