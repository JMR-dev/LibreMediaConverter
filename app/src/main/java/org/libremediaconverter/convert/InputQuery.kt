package org.libremediaconverter.convert

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/**
 * What the app can find out about a picked file before an engine opens it.
 *
 * One place rather than two: `queryFile` existed in `ConversionViewModel` and `JoinViewModel`
 * byte for byte, so a fix to either was a fix to half the app.
 *
 * **An unknown size is null here, never zero.** That distinction is the whole point of this file.
 * The old code started at `var size = 0L` and only moved off it when a provider answered the
 * `OpenableColumns.SIZE` column, so "this file is empty" and "nobody told me how big it is"
 * reached `OutputPublisher.hasSpaceFor` as the same number — and `hasSpaceFor(0)` is only "is
 * there 128 MB free". Confirmed live on a Pixel 10 Pro XL, where `contentResolver.query` on a
 * `file://` URI returns null outright and the default survived untouched:
 * `queryFile gave displayName='input' sizeBytes=0`.
 *
 * So the size is asked for twice, in order:
 *
 *  1. **What the provider says.** `OpenableColumns.SIZE`, which documents providers *may* omit.
 *  2. **What the file itself says.** `openFileDescriptor(uri, "r")` and `statSize`, which needs
 *     no cooperation from a provider beyond being openable — and the app is going to have to
 *     open the input anyway, so it is not asking for anything a conversion would not need. This
 *     is what answers the `file://` case above.
 *
 * Only when both decline is the answer null, and the callers each say what they do about that.
 */
object InputQuery {

    /**
     * Shown when no provider names the file.
     *
     * Kept exactly as it was — it is what reaches the save dialog as `input_converted.mp4` for a
     * job whose input nothing described, and changing it here would rename files for reasons
     * unrelated to this fix.
     */
    const val FALLBACK_DISPLAY_NAME = "input"

    /** Everything the picker knows about [uri] the moment it is chosen. */
    fun describe(context: Context, uri: Uri): InputFile = InputFile(
        uri = uri,
        displayName = firstRow(context, uri) { it.displayNameOrNull() } ?: FALLBACK_DISPLAY_NAME,
        sizeBytes = sizeOf(context, uri),
    )

    /**
     * How many bytes [uri] holds, or null when nothing can say.
     *
     * Public because the workers need it too, and for a reason worth stating: a worker's input
     * `Data` carries the size the *picker* found, which is missing for work enqueued before this
     * existed and for a request built by hand. The worker holds the URI, so when the number is
     * absent it can ask the file rather than assume.
     */
    fun sizeOf(context: Context, uri: Uri): Long? = firstRow(context, uri) { it.sizeOrNull() } ?: measure(context, uri)

    /**
     * The sum of [sizes], or null if even one of them is unknown.
     *
     * A join's total is only as good as its worst-known part. Adding up the ones that answered
     * would produce a lower bound that reads exactly like a real total, and the space check has
     * no way to tell the two apart — which is the same conflation this whole file exists to end.
     *
     * The sum saturates rather than wrapping. Sizes reach here non-negative — both of the ways one
     * is found reject a negative answer — but nothing bounds their *sum*, and a total that wrapped
     * negative would not be harmless nonsense: `OutputPublisher.hasSpaceFor` compares it against
     * free space, so the largest join representable would come back as the one with the most room.
     */
    fun total(sizes: List<Long?>): Long? = sizes.fold(0L as Long?) { running, size ->
        when {
            running == null || size == null -> null
            size > Long.MAX_VALUE - running -> Long.MAX_VALUE
            else -> running + size
        }
    }

    /**
     * Reads [read] out of the first row of a metadata query, or null if there is no row.
     *
     * Guarded because a resolver call is a call into another app: a provider that has been
     * uninstalled, revoked its grant, or simply crashes takes the query with it, and a file
     * picker is not a place to bring the process down from.
     */
    private fun <T> firstRow(context: Context, uri: Uri, read: (Cursor) -> T): T? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) read(cursor) else null
        }
    }.onFailure { Log.w(TAG, "Could not read metadata for $uri", it) }.getOrNull()

    /**
     * The size according to the file descriptor, or null if it cannot be opened.
     *
     * `statSize` is `-1` for anything without a fixed length — a pipe, or a provider streaming its
     * answer — which is a different way of saying "unknown" and is treated as one.
     */
    private fun measure(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()?.takeIf { it >= 0 }

    private fun Cursor.displayNameOrNull(): String? =
        getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.sizeOrNull(): Long? =
        getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)?.takeIf { it >= 0 }

    private const val TAG = "InputQuery"
}
