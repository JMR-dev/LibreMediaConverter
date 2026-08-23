package org.libremediaconverter.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libremediaconverter.model.OutputFormat
import java.io.File
import java.util.UUID

/**
 * The rule that gives every job a staging path of its own.
 *
 * A pure function for the same reason as [StagingSweep] and
 * [org.libremediaconverter.work.FailureOutcome]: what it prevents cannot be provoked here. The
 * collision needs two jobs alive at once, one of them resumed by WorkManager after a process
 * restart, which is a device and an `am kill`. What *is* checkable is the property that makes the
 * collision impossible, and that is what these pin.
 */
class StagingNamesTest {

    @Test
    fun `two jobs converting the same file stage under different names`() {
        // The collision, seen on a device: two independent jobs both computed
        // cache/conversions/input_converted.mp4, and a tag query in a fresh process returned two
        // SUCCEEDED WorkInfos naming that one file.
        assertNotEquals(
            StagingNames.forJob(JOB_A, MP4.extension),
            StagingNames.forJob(JOB_B, MP4.extension),
        )
    }

    @Test
    fun `the same job stages under the same name on every attempt`() {
        // Load-bearing, not incidental. A retry runs doWork() from the top, and the catch on the
        // way out deletes the staged file -- which only collects the previous attempt's partial if
        // the name is the same. WorkManager builds WorkerParameters from the WorkSpec id and only
        // increments runAttemptCount, so the id is what stays still across a retry.
        assertEquals(
            StagingNames.forJob(JOB_A, MP4.extension),
            StagingNames.forJob(JOB_A, MP4.extension),
        )
    }

    @Test
    fun `the extension is the output's, because that is what infers the muxer`() {
        // Not cosmetic. FFmpegConcatCommand names no output muxer, so FFmpeg infers it from the
        // path -- an opaque name without the right extension would silently produce the wrong
        // container.
        assertTrue(StagingNames.forJob(JOB_A, MP4.extension).endsWith(".mp4"))
        assertTrue(StagingNames.forJob(JOB_A, OutputFormat.MKV_H265.extension).endsWith(".mkv"))
        assertTrue(StagingNames.forJob(JOB_A, OutputFormat.M4A_AAC.extension).endsWith(".m4a"))
    }

    @Test
    fun `a staging name is a bare filename and nothing else`() {
        // The alternative to an opaque name was sanitising the provider-supplied display name,
        // which can contain a separator, be empty, or be four kilobytes long. This is what makes
        // that whole question moot.
        val name = StagingNames.forJob(JOB_A, MP4.extension)
        assertEquals("a staging name must not be a path", name, File(name).name)
        assertTrue("a staging name must not be empty", name.isNotEmpty())
    }

    @Test
    fun `each join gets a list file of its own`() {
        // ConcatEngine used a constant, so any two joins at once shared one concat_list.txt and
        // one of them read the other's input list.
        assertNotEquals(
            StagingNames.concatListFor(StagingNames.forJob(JOB_A, MP4.extension)),
            StagingNames.concatListFor(StagingNames.forJob(JOB_B, MP4.extension)),
        )
    }

    @Test
    fun `a list file is named after the output it belongs to`() {
        // So the pair is obvious in a directory listing, and so the sweep ages them together.
        val output = StagingNames.forJob(JOB_A, MP4.extension)
        val list = StagingNames.concatListFor(output)
        assertEquals("$JOB_A.concat_list.txt", list)
        assertTrue(list.startsWith(output.substringBeforeLast('.')))
    }

    private companion object {
        val MP4 = OutputFormat.MP4_H264
        val JOB_A: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000a")
        val JOB_B: UUID = UUID.fromString("00000000-0000-4000-8000-00000000000b")
    }
}
