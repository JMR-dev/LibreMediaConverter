package org.libremediaconverter.convert

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Staging and publication of conversion output.
 *
 * Conversions never write directly to the destination the user picked. FFmpeg and the
 * MP4 muxer both need to seek backwards to finalise a file — faststart rewrites the
 * moov atom at the end — and a SAF file descriptor is not reliably seekable. Writing
 * through one produces a truncated or unplayable file.
 *
 * So every job writes to app-private cache, which is a real POSIX path with no
 * permissions and no scoped-storage rules, and the finished file is copied out to the
 * user's chosen destination afterwards.
 *
 * The cost is one extra copy and transient double disk usage, which is why
 * [hasSpaceFor] exists.
 */
open class OutputPublisher(private val context: Context) {

    private val stagingDir: File
        get() = File(context.cacheDir, "conversions").apply { mkdirs() }

    open fun createStagingFile(name: String): File = File(stagingDir, name)

    /**
     * True if there is room for a further [bytes], including headroom.
     *
     * Staging means peak usage is roughly input + output at once, so a job that would
     * just barely fit is rejected rather than failing partway through.
     */
    open fun hasSpaceFor(bytes: Long): Boolean = stagingDir.usableSpace > bytes + SPACE_HEADROOM_BYTES

    /** Copies a finished staging file into a user-chosen SAF destination. */
    open fun publish(staged: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination)?.use { out ->
            staged.inputStream().use { it.copyTo(out) }
        } ?: error("Could not open destination for writing: $destination")
    }

    /**
     * Deletes one staged file, if it really is one of ours.
     *
     * This is what a ViewModel's `reset()` calls when the user taps "Start over" on a
     * finished-but-unsaved conversion, which is otherwise a full-size copy left in cache
     * for the OS to reclaim whenever it feels like it.
     *
     * The guard is not decoration. The handle reaches the ViewModel as a path string in
     * `WorkInfo.outputData` and is turned straight into a `File`, so this is the one place
     * that checks where it points before deleting. Comparing the *canonical* parent rather
     * than the path as written is what makes `conversions/../something` fail: the naive
     * string comparison accepts it.
     *
     * @return true if a file was deleted. False covers both "not in staging" and "already
     *   gone", which the caller has no reason to tell apart — a `reset()` after a
     *   successful save is an ordinary second call.
     */
    open fun discardStaged(staged: File): Boolean {
        val parent = staged.parentFile?.canonicalOrAbsolute() ?: return false
        if (parent != stagingDir.canonicalOrAbsolute()) return false
        return staged.delete()
    }

    /**
     * Deletes staged files old enough to have been abandoned.
     *
     * The backstop for everything `discardStaged` cannot reach: a process killed between
     * finishing a conversion and saving it, a worker that failed before its output ever
     * became a `Converted` state, or a `reset()` whose delete was cancelled with the
     * Activity. [StagingSweep] owns the rule and its reasoning.
     *
     * Deliberately not the `clearStaging()` this replaces. That deleted the directory's
     * whole contents, and the convert tab, the join tab and `ConcatEngine`'s list file all
     * share this directory — so a blanket delete could destroy a live job's file. Per-job
     * staging names ([StagingNames]) stop two jobs from *sharing* a file; they say nothing
     * about whether a file's job is still running, which is the question here.
     *
     * [nowMs] is a parameter so the clock is the caller's, not a hidden global.
     */
    open fun sweepStaging(nowMs: Long = System.currentTimeMillis()) {
        val dir = stagingDir
        val listing = dir.listFiles() ?: return
        val entries = listing.map { StagingSweep.Entry(it.name, it.lastModified()) }
        StagingSweep.collectable(entries, nowMs).forEach { name ->
            val file = File(dir, name)
            // Re-read the timestamp rather than trusting the snapshot above. Between the
            // listing and here, a worker resumed by WorkManager -- which runs in this same
            // process -- could have started writing this very file, and unlinking an inode a
            // running job still holds open would end with the job reporting success for a
            // path that no longer exists.
            if (StagingSweep.isCollectable(file.lastModified(), nowMs)) file.delete()
        }
    }

    private fun File.canonicalOrAbsolute(): File = runCatching { canonicalFile }.getOrDefault(absoluteFile)

    private companion object {
        const val SPACE_HEADROOM_BYTES = 128L * 1024 * 1024
    }
}
