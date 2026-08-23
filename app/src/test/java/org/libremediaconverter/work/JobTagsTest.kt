package org.libremediaconverter.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What has to survive a restart, and what a malformed tag does.
 *
 * The values come from a picker and go into WorkManager's database, so the encoder and the
 * decoder are the two halves of one round trip and are tested as one. The lenient reads
 * matter as much as the round trip: work enqueued by an older version of the app carries none
 * of these tags, and it is exactly the work most likely to still be queued the first time
 * this code runs.
 */
class JobTagsTest {

    @Test
    fun `a display name survives the round trip`() {
        val tags = setOf("org.libremediaconverter.work.ConversionWorker", JobTags.displayName("holiday.mp4"))
        assertEquals("holiday.mp4", JobTags.displayNameOf(tags))
    }

    @Test
    fun `a display name that looks like another tag is still read back whole`() {
        // Tags are matched by prefix over the whole string, so a file named after one of the
        // other prefixes cannot be mistaken for it.
        val name = "lmc.size-bytes:9"
        val tags = setOf(JobTags.displayName(name), JobTags.sizeBytes(4096))
        assertEquals(name, JobTags.displayNameOf(tags))
        assertEquals(4096L, JobTags.sizeBytesOf(tags))
    }

    @Test
    fun `a display name with spaces, colons and unicode is carried verbatim`() {
        val name = "холидей: clip 2 — final.mkv"
        assertEquals(name, JobTags.displayNameOf(setOf(JobTags.displayName(name))))
    }

    @Test
    fun `a size survives the round trip`() {
        assertEquals(9_000_000_000L, JobTags.sizeBytesOf(setOf(JobTags.sizeBytes(9_000_000_000L))))
    }

    @Test
    fun `an input count survives the round trip`() {
        assertEquals(7, JobTags.inputCountOf(setOf(JobTags.inputCount(7))))
    }

    @Test
    fun `a job with no tags of ours reads back as nothing known`() {
        // Work enqueued before this app version. The reattachment falls back rather than
        // skipping the job, because the job is still the user's file.
        val tags = setOf("org.libremediaconverter.work.ConversionWorker")
        assertNull(JobTags.displayNameOf(tags))
        assertNull(JobTags.sizeBytesOf(tags))
        assertNull(JobTags.inputCountOf(tags))
    }

    @Test
    fun `a size that is not a number reads as unknown rather than throwing`() {
        assertNull(JobTags.sizeBytesOf(setOf("lmc.size-bytes:huge")))
        assertNull(JobTags.inputCountOf(setOf("lmc.input-count:")))
    }

    @Test
    fun `the three tags do not read each other`() {
        val tags = setOf(JobTags.displayName("clip.mp4"), JobTags.sizeBytes(12), JobTags.inputCount(3))
        assertEquals("clip.mp4", JobTags.displayNameOf(tags))
        assertEquals(12L, JobTags.sizeBytesOf(tags))
        assertEquals(3, JobTags.inputCountOf(tags))
    }
}
