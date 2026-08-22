package org.libremediaconverter.convert

/**
 * Decides which staging entries are old enough to collect.
 *
 * A pure function rather than a loop inside [OutputPublisher], for the reason
 * [org.libremediaconverter.work.FailureOutcome] documents: the decision is worth verifying
 * and the situation that provokes it is not reproducible. Here the untestable part is the
 * clock — an orphan is only interesting a day after it was written, and a filesystem's
 * mtime granularity is not something a test should be measuring. Timestamps therefore
 * arrive as values.
 *
 * The rule replaces an unconditional `clearStaging()` that deleted the directory's whole
 * contents. That was hazardous: `<cacheDir>/conversions/` is shared by the convert tab, the
 * join tab and [org.libremediaconverter.ffmpeg.ConcatEngine]'s `concat_list.txt`, and any
 * two of them can be live at once, so a blanket delete could take a file out from under a
 * running job. Age is the narrowing.
 */
object StagingSweep {

    /** One directory entry, reduced to what the decision actually needs. */
    data class Entry(val name: String, val lastModifiedMs: Long)

    /**
     * How stale a staging file has to be before it is assumed abandoned.
     *
     * Twenty-four hours, chosen against the longest a live file can plausibly go untouched
     * rather than against how quickly cache should be reclaimed. Output files are written
     * continuously, so a running job refreshes their mtime by itself. `concat_list.txt` is
     * the exception — written once and then only read for the rest of the join — so the
     * period has to exceed a whole join. WorkManager caps a single attempt at the
     * six-hour-per-day foreground-service budget and then stops the worker, and a retry
     * rewrites the list file, so no attempt can hold a file still for a day.
     */
    const val GRACE_PERIOD_MS: Long = 24L * 60 * 60 * 1000

    /** The names in [entries] that may be deleted, in the order they were given. */
    fun collectable(entries: List<Entry>, nowMs: Long, gracePeriodMs: Long = GRACE_PERIOD_MS): List<String> = entries
        .filter { isCollectable(it.lastModifiedMs, nowMs, gracePeriodMs) }
        .map { it.name }

    /**
     * True if a file last written at [lastModifiedMs] is collectable at [nowMs].
     *
     * Exposed separately so the caller can re-check a single entry immediately before
     * deleting it, closing the window between listing a directory and acting on the list.
     *
     * A negative age — a file dated in the future, because the clock moved backwards — is
     * deliberately not collectable. It carries no information about whether the file is in
     * use, and keeping a file costs cache while deleting one can cost the user an hour of
     * transcoding.
     */
    fun isCollectable(lastModifiedMs: Long, nowMs: Long, gracePeriodMs: Long = GRACE_PERIOD_MS): Boolean =
        nowMs - lastModifiedMs >= gracePeriodMs
}
