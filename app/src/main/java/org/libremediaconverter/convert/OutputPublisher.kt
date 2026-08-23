package org.libremediaconverter.convert

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
     * True if staging can take a further [bytes], with [SPACE_HEADROOM_BYTES] left over.
     *
     * **The doc this replaces claimed peak usage was "roughly input + output at once" while the
     * arithmetic reserved `input + 128 MB`.** The arithmetic is what stays, and this says why
     * rather than the two continuing to disagree.
     *
     * [bytes] is the *input's* size standing in for the output's, because before an engine has
     * run there is no other number. It is generous for the ordinary conversion, which is asked
     * for precisely because it shrinks its input, and short for the ones that do not — a re-encode
     * to a bulkier codec, or a stream copy into a container with more overhead.
     *
     * The 128 MB absorbs that error, and one more besides: [publish] copies the staged file to
     * the user's destination, so while that runs the bytes exist twice on any destination sharing
     * this volume. Reserving `input + output` outright would have refused jobs that fit, on a
     * device where the destination is usually removable or remote.
     *
     * So this is a pre-flight check that stops a job which obviously cannot fit from spending
     * minutes discovering it — not a guarantee. A conversion that runs out of space anyway fails
     * through its engine, with a message of its own.
     *
     * Open so a test can force a full disk; see `FakeFailures` in the instrumented source set.
     */
    open fun hasSpaceFor(bytes: Long): Boolean = stagingDir.usableSpace > bytes + SPACE_HEADROOM_BYTES

    /**
     * The same check for a job whose input size nobody could determine — see [InputQuery].
     *
     * **This deliberately produces the same number the defect produced by accident**, which is
     * worth stating plainly: with no size to reserve for, all that is left to check is the
     * headroom. What has changed is that it is now the answer to a question that was asked. The
     * old code could not tell an unmeasurable file from an empty one, so it silently made this
     * the answer for *both*; now [hasSpaceFor] means "there is room for this many bytes" and
     * nothing else claims it.
     *
     * Refusing instead was considered and rejected. It would turn "no provider answered the
     * `SIZE` column" into "this file cannot be converted" — a worse defect than the one being
     * fixed, and one the user could do nothing about.
     *
     * The default answers *through* [hasSpaceFor], which is what keeps a publisher that refuses
     * on space — `FakeFailures.FullDisk`, which overrides `hasSpaceFor` and nothing else —
     * refusing this too. `SpaceCheckTest` pins that delegation, because an override here that
     * stopped delegating would quietly stop honouring a full disk.
     */
    open fun hasSpaceForUnknownSize(): Boolean = hasSpaceFor(0L)

    /**
     * Copies a finished staging file into a user-chosen SAF destination.
     *
     * A copy that fails partway -- the destination volume filling up is the obvious one, a
     * provider giving out mid-write the other -- used to leave the bytes it had managed at
     * the name the user picked, while the UI said "Could not save the file". The user was
     * then holding a truncated file they had been told was never written, and nothing in the
     * app would ever tidy it up: staging cleanup only reaches [stagingDir], never the
     * destination.
     *
     * So a failed copy deletes the document. Three things bound that, because deleting a
     * file the user already had would be a far worse defect than the one being fixed:
     *
     *  - **Only a document URI.** `DocumentsContract.deleteDocument` is the only delete this
     *    has any right to attempt, and it is defined on document URIs. Anything else -- a
     *    `file://` path, a MediaStore item, a content URI from a provider that is not a
     *    documents provider -- is left exactly as it is.
     *  - **Only a destination that was empty when we started.** The size is read before the
     *    stream is opened, and the delete only runs if the answer was positively zero. Every
     *    destination reaching here comes from the SAF `CreateDocument` contract, so in
     *    practice it is a document this app just created; but `publish` cannot verify that
     *    from a `Uri`, and a provider that hands back an existing document for a name the
     *    user re-picked would otherwise have its file deleted rather than merely truncated.
     *    A provider that reports no size at all falls into the same "not known to be empty"
     *    bucket, so the fix is conservative rather than universal: it will not clean up
     *    behind such a provider, and it will not delete anything of theirs either.
     *  - **The original failure is what the caller sees.** Cleanup runs inside its own
     *    `runCatching`; if it throws, that goes on the original exception as a suppressed
     *    one. `save()` reports `e.message`, and "could not delete the half-written file" is
     *    not the thing to tell someone whose disk just filled up.
     *
     * The whole `use` is guarded, not just the copy: a `close()` that throws while flushing
     * IS the disk-full case, and it arrives after `copyTo` has returned. The cost is that a
     * file whose every byte reached the provider before a failing flush is deleted too --
     * which is the right way round, since a flush that failed means the bytes are not
     * durably there to begin with.
     *
     * A failure from `openOutputStream` itself is deliberately outside the guard. Nothing
     * has been written at that point, so there is nothing of ours to remove.
     */
    open fun publish(staged: File, destination: Uri) {
        val destinationWasEmpty = destinationIsKnownEmpty(destination)
        val out = context.contentResolver.openOutputStream(destination)
            ?: error("Could not open destination for writing: $destination")
        try {
            out.use { sink -> staged.inputStream().use { source -> source.copyTo(sink) } }
        } catch (failure: Throwable) {
            if (destinationWasEmpty) deletePartialOutput(destination, failure)
            throw failure
        }
    }

    /**
     * True only when the destination is *positively known* to hold no bytes yet.
     *
     * Every other answer -- a provider that does not report `_size`, a query that returns no
     * row, a resolver call that throws -- is false, because this decides whether a delete is
     * allowed and "I could not tell" must never authorise one.
     *
     * The column is looked up by name rather than taken as index 0: a projection is a
     * request, not a guarantee, and a provider is free to return its own column set.
     */
    private fun destinationIsKnownEmpty(destination: Uri): Boolean = runCatching {
        context.contentResolver
            .query(destination, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { row ->
                val size = row.getColumnIndex(OpenableColumns.SIZE)
                size >= 0 && row.moveToFirst() && !row.isNull(size) && row.getLong(size) == 0L
            }
    }.getOrNull() ?: false

    /**
     * Removes the half-written document, never at the expense of [cause].
     *
     * `deleteDocument` reports its own failure two different ways -- `false`, or a thrown
     * `FileNotFoundException` -- and neither is worth failing the save over, because the
     * save has already failed. Whatever it does, [cause] is what propagates; a thrown
     * cleanup failure is attached to it so it is not simply lost.
     */
    private fun deletePartialOutput(destination: Uri, cause: Throwable) {
        runCatching {
            if (DocumentsContract.isDocumentUri(context, destination)) {
                DocumentsContract.deleteDocument(context.contentResolver, destination)
            }
        }.onFailure(cause::addSuppressed)
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
