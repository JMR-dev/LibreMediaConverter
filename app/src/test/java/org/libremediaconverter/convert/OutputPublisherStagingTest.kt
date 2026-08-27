package org.libremediaconverter.convert

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * What a pure function cannot say: the file is really gone.
 *
 * [StagingSweepTest] pins the rule; this pins the effect. Robolectric gives each test a
 * real, empty `cacheDir` on a temp path, so this drives the actual [OutputPublisher] over
 * the actual filesystem — the same calls `reset()` makes, without needing a ViewModel (both
 * of those construct a `WorkManager`, which is not initialised on the JVM classpath).
 *
 * The instrumented suite could also catch the "Start over leaks a full-size copy" defect --
 * it runs on this host for API 33-36 (`tools/local-emulator/run-e2e.sh`) and on CI for
 * 33-37. Here rather than there because a real `cacheDir` is all the defect needs, and
 * finding it costs an emulator boot there and a few seconds here.
 */
@RunWith(RobolectricTestRunner::class)
class OutputPublisherStagingTest {

    private lateinit var cacheDir: File
    private lateinit var publisher: OutputPublisher

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cacheDir = context.cacheDir
        publisher = OutputPublisher(context)
    }

    @Test
    fun `discarding a staged output actually removes it`() {
        // This is the leak in D2: convert, decline to save, tap "Start over".
        val staged = publisher.createStagingFile("holiday.mp4").apply { writeBytes(ByteArray(4096)) }
        assertTrue("the staged file should exist to begin with", staged.exists())

        assertTrue("discard should report that it deleted the file", publisher.discardStaged(staged))
        assertFalse("the staged file should be gone after the reset path runs", staged.exists())
    }

    @Test
    fun `discarding a file that is already gone is not an error`() {
        // reset() after a successful save, or two resets in a row. Neither should throw.
        val staged = publisher.createStagingFile("already_published.mp4")
        assertFalse(publisher.discardStaged(staged))
    }

    @Test
    fun `a file outside the staging directory is refused`() {
        // The handle can originate in WorkInfo.outputData, which is a string the ViewModel
        // turns straight into a File. Nothing else checks where it points.
        val outsider = File(cacheDir, "someone_elses.bin").apply { writeBytes(ByteArray(16)) }

        assertFalse(publisher.discardStaged(outsider))
        assertTrue("a file outside staging must survive", outsider.exists())
    }

    @Test
    fun `a path that climbs out of the staging directory is refused`() {
        // The naive parent check -- comparing path strings -- passes this one.
        val outsider = File(cacheDir, "climbed_to.bin").apply { writeBytes(ByteArray(16)) }
        val escaping = File(cacheDir, "conversions/../climbed_to.bin")

        assertFalse(publisher.discardStaged(escaping))
        assertTrue("a traversal must not delete outside staging", outsider.exists())
    }

    @Test
    fun `the sweep collects an orphan and leaves a live job alone`() {
        // Named the way the app names them, so the sweep is exercised against real shapes.
        val liveJob = StagingNames.forJob(UUID.randomUUID(), "mp4")
        val orphan = publisher.createStagingFile(
            StagingNames.forJob(UUID.randomUUID(), "mp4"),
        ).apply { writeBytes(ByteArray(4096)) }
        val liveOutput = publisher.createStagingFile(liveJob).apply { writeBytes(ByteArray(4096)) }
        val liveList = publisher.createStagingFile(
            StagingNames.concatListFor(liveJob),
        ).apply { writeText("file 'a.mp4'\n") }
        assertTrue(orphan.setLastModified(System.currentTimeMillis() - StagingSweep.GRACE_PERIOD_MS - 60_000))

        publisher.sweepStaging()

        assertFalse("an abandoned output should be collected", orphan.exists())
        assertTrue("a live job's output must survive", liveOutput.exists())
        assertTrue("a live join's list file must survive", liveList.exists())
    }

    @Test
    fun `the sweep tolerates a staging directory that does not exist yet`() {
        File(cacheDir, "conversions").deleteRecursively()

        publisher.sweepStaging()
    }

    @Test
    fun `the sweep tolerates a staging path that is not a directory`() {
        // The other half of `listFiles() ?: return`, and not the same as the case above: a missing
        // directory is created by `stagingDir`'s own mkdirs() and lists as empty. Only a path that
        // cannot be a directory makes listFiles() answer null, and a sweep that dereferenced that
        // would take the app down on a launch rather than on a conversion -- AppStartSweepTest is
        // where this runs from.
        File(cacheDir, "conversions").deleteRecursively()
        File(cacheDir, "conversions").writeBytes(ByteArray(8))

        publisher.sweepStaging()
    }

    @Test
    fun `discarding a file with no parent at all is refused`() {
        // A relative name has no parent directory, so `staged.parentFile` is null. The handle
        // reaches the ViewModel as a path string out of WorkInfo.outputData and is turned straight
        // into a File, so this is not a shape the caller can rule out -- and the guard has to
        // answer false rather than dereference it.
        val parentless = File("holiday.mp4")
        assertNull("the fixture is supposed to have no parent", parentless.parentFile)

        assertFalse("a file with no parent is not in staging", publisher.discardStaged(parentless))
    }
}
