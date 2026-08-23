package org.libremediaconverter.work

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * The rule for picking up a job the ViewModel did not start.
 *
 * Isolated from the ViewModel precisely so it can be tested: reaching this code for real means
 * the process being reclaimed while a job — or a result nobody saved — is still around, which
 * needs a device and an `am kill`. Extracting the choice means the rule is verified even though
 * the situation that calls for it cannot be reproduced on the JVM.
 */
class ReattachmentTest {

    @Test
    fun `nothing to reattach to when there is no work at all`() {
        assertNull(Reattachment.choose(emptyList()))
    }

    @Test
    fun `a cancelled job is never reattached to`() {
        // The user already said no. Bringing it back would undo that.
        val cancelled = job(state = WorkInfo.State.CANCELLED, outputPath = "/cache/out.mp4", outputExists = true)
        assertNull(Reattachment.choose(listOf(cancelled)))
    }

    @Test
    fun `a failed job is not reattached to`() {
        // Nothing marks a failure as seen, so it would reappear on every launch. Failures left
        // by an interrupted worker are ordinary: a restart's setForeground can be refused.
        assertNull(Reattachment.choose(listOf(job(state = WorkInfo.State.FAILED))))
    }

    @Test
    fun `a failed job is not reattached to even when it left a file behind`() {
        // The fixture that matters, and the one every other FAILED case here was missing: a job
        // killed mid-write leaves a partial in staging -- the 2 MB orphan the device pass found --
        // so the exclusion has to hold for a FAILED job that really does name a file on disk.
        // Ranking it like a result would offer the user a truncated file with a Save button.
        val partial = job(state = WorkInfo.State.FAILED, outputPath = STAGED, outputExists = true)
        assertNull(Reattachment.choose(listOf(partial)))
    }

    @Test
    fun `a finished result still on disk is offered`() {
        val result = job(state = WorkInfo.State.SUCCEEDED, outputPath = "/cache/out.mp4", outputExists = true)
        assertEquals(Reattachment.Certain(result), Reattachment.choose(listOf(result)))
    }

    @Test
    fun `a finished result whose staged file is gone is not offered`() {
        // Saved already, or the OS reclaimed the cache. A Save button here would fail on tap.
        val vanished = job(state = WorkInfo.State.SUCCEEDED, outputPath = "/cache/out.mp4", outputExists = false)
        assertNull(Reattachment.choose(listOf(vanished)))
    }

    @Test
    fun `a job that reported success without a path is not offered`() {
        val pathless = job(state = WorkInfo.State.SUCCEEDED, outputPath = null, outputExists = false)
        assertNull(Reattachment.choose(listOf(pathless)))
    }

    @Test
    fun `a running job is preferred to a finished result`() {
        // A running job holds a foreground notification. Someone opening the app while that
        // notification is in the shade is looking for that conversion.
        val result = job(state = WorkInfo.State.SUCCEEDED, outputPath = "/cache/out.mp4", outputExists = true)
        val running = job(state = WorkInfo.State.RUNNING)
        assertEquals(Reattachment.Certain(running), Reattachment.choose(listOf(result, running)))
    }

    @Test
    fun `a running job is preferred to a queued one`() {
        val queued = job(state = WorkInfo.State.ENQUEUED)
        val running = job(state = WorkInfo.State.RUNNING)
        assertEquals(Reattachment.Certain(running), Reattachment.choose(listOf(queued, running)))
    }

    @Test
    fun `a job waiting to retry is preferred to one that has never run`() {
        // It has already done part of the work — most likely it exhausted the foreground
        // budget mid-conversion — so it is the one closer to producing a file.
        val fresh = job(state = WorkInfo.State.ENQUEUED, runAttemptCount = 0)
        val retrying = job(state = WorkInfo.State.ENQUEUED, runAttemptCount = 1)
        assertEquals(Reattachment.Certain(retrying), Reattachment.choose(listOf(fresh, retrying)))
    }

    @Test
    fun `a queued job is preferred to a finished result`() {
        val result = job(state = WorkInfo.State.SUCCEEDED, outputPath = "/cache/out.mp4", outputExists = true)
        val queued = job(state = WorkInfo.State.ENQUEUED)
        assertEquals(Reattachment.Certain(queued), Reattachment.choose(listOf(result, queued)))
    }

    @Test
    fun `blocked work counts as queued rather than being ignored`() {
        val blocked = job(state = WorkInfo.State.BLOCKED)
        assertEquals(Reattachment.Certain(blocked), Reattachment.choose(listOf(blocked)))
    }

    @Test
    fun `the newer of two results is the one offered`() {
        // Losing this tie is not the same as waiting for the next launch: the query has no
        // ORDER BY, so an arbitrary winner would win every launch and the other result would
        // stay unreachable for as long as its file existed.
        val older = finishedResult(STAGED, tags = emptySet(), modifiedAt = 1_000L)
        val newer = finishedResult("/cache/beach_converted.mp4", tags = emptySet(), modifiedAt = 2_000L)

        assertEquals(Reattachment.Certain(newer), Reattachment.choose(listOf(older, newer)))
    }

    @Test
    fun `a newer result still does not outrank live work`() {
        // Rank first, time second. A running job holds the notification the user is following.
        val running = job(state = WorkInfo.State.RUNNING)
        val newer = finishedResult(STAGED, tags = emptySet(), modifiedAt = Long.MAX_VALUE)

        assertEquals(Reattachment.Certain(running), Reattachment.choose(listOf(newer, running)))
    }

    @Test
    fun `two live jobs, which have written nothing to compare, resolve to the query's order`() {
        val first = job(state = WorkInfo.State.RUNNING)
        val second = job(state = WorkInfo.State.RUNNING)
        assertEquals(Reattachment.Certain(first), Reattachment.choose(listOf(first, second)))
    }

    @Test
    fun `a result is still found when everything else is unusable`() {
        val result = job(state = WorkInfo.State.SUCCEEDED, outputPath = "/cache/out.mp4", outputExists = true)
        val jobs = listOf(
            job(state = WorkInfo.State.CANCELLED),
            job(state = WorkInfo.State.FAILED),
            job(state = WorkInfo.State.SUCCEEDED, outputPath = "/cache/gone.mp4", outputExists = false),
            result,
        )
        assertEquals(Reattachment.Certain(result), Reattachment.choose(jobs))
    }

    // --- when two jobs claim the same staged file ---------------------------------------------

    @Test
    fun `two results naming the same file are still offered, but not attributed`() {
        // Straight off a device: two SUCCEEDED jobs whose output path was the same
        // input_converted.mp4, with one file on disk. The file is the user's either way; which
        // job wrote it is not knowable, so the caller is told not to describe it.
        val first = finishedResult(STAGED, tags = setOf(JobTags.displayName("holiday.mp4")))
        val second = finishedResult(STAGED, tags = setOf(JobTags.displayName("holiday.mkv")))

        assertEquals(Reattachment.Ambiguous(first), Reattachment.choose(listOf(first, second)))
    }

    @Test
    fun `aliases that describe the same input are attributed after all`() {
        // The ordinary way to end up with two: convert the same file twice. Nothing turns on
        // which of them wrote the file, so the label is safe.
        val tags = setOf(JobTags.displayName("holiday.mp4"), JobTags.sizeBytes(4_096))
        val first = finishedResult(STAGED, tags = tags)
        val second = finishedResult(STAGED, tags = tags)

        assertEquals(Reattachment.Certain(first), Reattachment.choose(listOf(first, second)))
    }

    @Test
    fun `results naming different files do not make each other ambiguous`() {
        val first = finishedResult(STAGED, tags = setOf(JobTags.displayName("holiday.mp4")))
        val second = finishedResult("/cache/beach_converted.mp4", tags = setOf(JobTags.displayName("beach.mp4")))

        assertEquals(Reattachment.Certain(first), Reattachment.choose(listOf(first, second)))
    }

    @Test
    fun `a job with no file yet is not aliased by every other job without one`() {
        // Guards the obvious mistake: live jobs all carry a null output path, and grouping on
        // that would make each of them ambiguous with all the others.
        val running = job(state = WorkInfo.State.RUNNING, tags = setOf(JobTags.displayName("holiday.mp4")))
        val queued = job(state = WorkInfo.State.ENQUEUED, tags = setOf(JobTags.displayName("beach.mp4")))

        assertEquals(Reattachment.Certain(running), Reattachment.choose(listOf(running, queued)))
    }

    @Test
    fun `an unusable alias does not make a result ambiguous`() {
        // A cancelled or failed job deletes its staged file on the way out, so it never wrote
        // what is on disk now and says nothing about who did.
        val result = finishedResult(STAGED, tags = setOf(JobTags.displayName("holiday.mp4")))
        val abandoned = job(
            state = WorkInfo.State.CANCELLED,
            outputPath = STAGED,
            outputExists = true,
            tags = setOf(JobTags.displayName("something else.mp4")),
        )

        assertEquals(Reattachment.Certain(result), Reattachment.choose(listOf(result, abandoned)))
    }

    private fun finishedResult(path: String, tags: Set<String>, modifiedAt: Long = 0L) = job(
        state = WorkInfo.State.SUCCEEDED,
        outputPath = path,
        outputExists = true,
        tags = tags,
        modifiedAt = modifiedAt,
    )

    private fun job(
        state: WorkInfo.State,
        runAttemptCount: Int = 0,
        outputPath: String? = null,
        outputExists: Boolean = false,
        tags: Set<String> = emptySet(),
        modifiedAt: Long = 0L,
    ) = JobSnapshot(
        id = UUID.randomUUID(),
        state = state,
        runAttemptCount = runAttemptCount,
        outputPath = outputPath,
        outputExists = outputExists,
        outputModifiedAt = modifiedAt,
        tags = tags,
    )

    private companion object {
        /** One staging path, because the interesting cases are the ones that share it. */
        const val STAGED = "/cache/holiday_converted.mp4"
    }
}
