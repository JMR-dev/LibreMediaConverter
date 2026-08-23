package org.libremediaconverter.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orphan-collection rule.
 *
 * Timestamps are passed in as values rather than read off real files on purpose. The
 * interesting part of this decision is clock arithmetic — the boundary, and a clock that
 * has moved backwards — and a test that created real files would be measuring the
 * filesystem's mtime granularity instead of the rule.
 */
class StagingSweepTest {

    private val now = 1_700_000_000_000L
    private val grace = StagingSweep.GRACE_PERIOD_MS

    @Test
    fun `an orphan older than the grace period is collectable`() {
        // Left behind by a process that died, or by a "Start over" whose delete never ran.
        val entries = listOf(StagingSweep.Entry("orphan.mp4", now - grace - 1))
        assertEquals(listOf("orphan.mp4"), StagingSweep.collectable(entries, now))
    }

    @Test
    fun `a file written moments ago is left alone`() {
        // The in-flight guard. A live job's output has its mtime refreshed by every write,
        // so a running conversion always looks young; deleting it would destroy the job.
        val entries = listOf(StagingSweep.Entry("in_progress.mp4", now - 1_000))
        assertEquals(emptyList<String>(), StagingSweep.collectable(entries, now))
    }

    @Test
    fun `the grace boundary itself collects`() {
        // Pins the comparison: age >= grace collects, age one millisecond short does not.
        assertTrue(StagingSweep.isCollectable(lastModifiedMs = now - grace, nowMs = now))
        assertFalse(StagingSweep.isCollectable(lastModifiedMs = now - grace + 1, nowMs = now))
    }

    @Test
    fun `a file dated in the future is left alone`() {
        // The clock moved backwards — an RTC correction, or the user setting the date. The
        // age is negative, which says nothing about whether the file is still in use, so
        // the safe answer is to keep it and let a later sweep decide.
        val entries = listOf(StagingSweep.Entry("tomorrow.mp4", now + grace))
        assertEquals(emptyList<String>(), StagingSweep.collectable(entries, now))
    }

    @Test
    fun `an empty directory yields nothing`() {
        assertEquals(emptyList<String>(), StagingSweep.collectable(emptyList(), now))
    }

    @Test
    fun `a mixed directory names only the orphans`() {
        // The whole point of narrowing the old clearStaging(): a sweep that runs while a
        // join is live must not take the list file out from under it.
        val entries = listOf(
            StagingSweep.Entry("orphan.mp4", now - grace - 1),
            StagingSweep.Entry("concat_list.txt", now - 5_000),
            StagingSweep.Entry("joined.mp4", now - 5_000),
            StagingSweep.Entry("older_orphan.webm", now - grace * 7),
        )
        assertEquals(listOf("orphan.mp4", "older_orphan.webm"), StagingSweep.collectable(entries, now))
    }

    @Test
    fun `a shorter grace period can be asked for explicitly`() {
        // The caller owns the period; the constant is only a default.
        val entries = listOf(StagingSweep.Entry("recent.mp4", now - 60_000))
        assertEquals(emptyList<String>(), StagingSweep.collectable(entries, now))
        assertEquals(listOf("recent.mp4"), StagingSweep.collectable(entries, now, gracePeriodMs = 30_000))
    }
}
