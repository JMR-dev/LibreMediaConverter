package org.libremediaconverter.convert

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Content providers more than one test needs, and the registration dance they all repeat.
 *
 * Only that. A stub that serves one test stays in that test, next to the assertion it exists for —
 * the rule `work/WorkerStubs.kt` states, and the reason `UnreliableOutputStream` is still private to
 * `OutputPublisherPublishTest`.
 *
 * These started life inside `OutputPublisherPublishTest`, which is the only thing that needed a
 * provider at all. They moved here when `InputQuery`'s cursor reads turned out to need the same
 * provider answering *badly* — see [RowShape].
 */

internal const val DOCUMENTS_AUTHORITY = "org.libremediaconverter.test.documents"
internal const val PLAIN_AUTHORITY = "org.libremediaconverter.test.plain"

/**
 * How [FakeSafProvider] answers a metadata query.
 *
 * A provider is another app. It can be uninstalled, revoke its grant, crash, or simply answer
 * something the caller did not expect — and "answered something unexpected" is not one case but
 * several, which is why this is an enum rather than a boolean.
 *
 * The distinction that matters most to callers is **null versus missing versus zero versus
 * negative**. `InputQuery` exists to stop the last three being conflated: `hasSpaceFor(0)` is only
 * "is there 128 MB free", so a size nobody could determine must not arrive as `0`, and
 * `OutputPublisher.destinationIsKnownEmpty` must answer `false` — never "empty, go ahead and
 * delete" — for every one of them.
 *
 * Column-level granularity is deliberate. `OutputPublisher` reads only `SIZE`; `InputQuery` reads
 * both, and reaches a different answer depending on which one is bad.
 */
internal enum class RowShape {
    /** What a healthy provider answers: the file's real name and real length. */
    NORMAL,

    /** A row is present and its `DISPLAY_NAME` cell is null. */
    NULL_DISPLAY_NAME,

    /** A row is present and its `SIZE` cell is null. */
    NULL_SIZE,

    /** The cursor carries no `DISPLAY_NAME` column at all — `getColumnIndex` gives `-1`. */
    NO_DISPLAY_NAME_COLUMN,

    /** The cursor carries no `SIZE` column at all — `getColumnIndex` gives `-1`. */
    NO_SIZE_COLUMN,

    /**
     * A size of `-1`.
     *
     * Not a corrupt provider: it is what anything without a fixed length reports — a pipe, or a
     * provider streaming its answer — and it is a third way of saying "unknown", distinct from a
     * null cell and from a missing column.
     */
    NEGATIVE_SIZE,

    /**
     * A cursor with the right columns and no rows in it.
     *
     * Distinct from returning `null`, which is what a provider that does not recognise the URI
     * does. Both mean "no answer", and code that treats one as an answer and the other as an
     * absence is wrong about one of them.
     */
    NO_ROWS,

    /**
     * The query itself throws.
     *
     * A resolver call is a call into another app, and that app can have been uninstalled, revoked
     * its grant, or simply crashed. `InputQuery.firstRow`'s KDoc is explicit that "a file picker is
     * not a place to bring the process down from", so this is the shape that proves the guard is
     * one.
     */
    QUERY_THROWS,
}

/**
 * A stand-in for the provider behind a SAF destination.
 *
 * It answers only what its callers ask of a document -- how many bytes are already there, what it
 * is called, and delete it -- backed by a real file so the assertions are about the filesystem
 * rather than about a mock's call log alone. The rest of the `ContentProvider` surface is stubbed.
 *
 * Writing is deliberately NOT routed through it. Robolectric's `ShadowContentResolver`
 * consults its registered-stream map before it reaches any provider, which is what lets a
 * test hand out a stream that writes some bytes and then fails -- a condition a real provider
 * cannot be asked to produce on demand.
 */
internal open class FakeSafProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (rowShape == RowShape.QUERY_THROWS) throw SecurityException("provider revoked the grant")
        val file = backingFile(uri)
        if (!file.exists()) return null
        return MatrixCursor(columnsFor(rowShape)).apply {
            if (rowShape != RowShape.NO_ROWS) addRow(cellsFor(rowShape, file))
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_DELETE_DOCUMENT) return null
        val target = extras?.getParcelable(EXTRA_URI, Uri::class.java) ?: return null
        deleteRequests += target
        deleteFailure?.let { throw it }
        backingFile(target).delete()
        return Bundle()
    }

    override fun getType(uri: Uri) = "video/mp4"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        // DocumentsContract.METHOD_DELETE_DOCUMENT and EXTRA_URI are hidden from the public
        // SDK, so they cannot be referenced. These are the wire names
        // DocumentsContract.deleteDocument() actually sends, which is what a provider sees.
        const val METHOD_DELETE_DOCUMENT = "android:deleteDocument"
        const val EXTRA_URI = "uri"

        /** Where the "documents" really live. Set per test to a Robolectric temp path. */
        lateinit var root: File

        /** Every delete this provider was asked for, in order. Empty is an assertion too. */
        val deleteRequests = mutableListOf<Uri>()

        /** Armed by the test that needs the cleanup itself to fail. */
        var deleteFailure: RuntimeException? = null

        /**
         * How the next query answers. [reset] puts it back to [RowShape.NORMAL], so a test that
         * does not care never has to think about it.
         */
        var rowShape: RowShape = RowShape.NORMAL

        fun backingFile(uri: Uri) = File(root, uri.lastPathSegment.orEmpty())

        fun reset(directory: File) {
            root = directory
            deleteRequests.clear()
            deleteFailure = null
            rowShape = RowShape.NORMAL
        }

        private fun columnsFor(shape: RowShape): Array<String> = when (shape) {
            RowShape.NO_DISPLAY_NAME_COLUMN -> arrayOf(OpenableColumns.SIZE)
            RowShape.NO_SIZE_COLUMN -> arrayOf(OpenableColumns.DISPLAY_NAME)
            else -> arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        }

        private fun cellsFor(shape: RowShape, file: File): Array<Any?> = when (shape) {
            RowShape.NO_DISPLAY_NAME_COLUMN -> arrayOf(file.length())
            RowShape.NO_SIZE_COLUMN -> arrayOf<Any?>(file.name)
            RowShape.NULL_DISPLAY_NAME -> arrayOf(null, file.length())
            RowShape.NULL_SIZE -> arrayOf(file.name, null)
            RowShape.NEGATIVE_SIZE -> arrayOf(file.name, UNKNOWN_LENGTH)
            else -> arrayOf(file.name, file.length())
        }

        /** What `statSize` reports for anything without a fixed length. See [RowShape.NEGATIVE_SIZE]. */
        private const val UNKNOWN_LENGTH = -1L
    }
}

/**
 * The same provider, registered WITHOUT the documents-provider intent filter.
 *
 * A separate class because the package manager keys providers by component name, so two
 * authorities need two components. It exists to prove the guard is a guard: a content URI
 * from something that is not a documents provider must not be handed to `deleteDocument`.
 */
internal class FakePlainProvider : FakeSafProvider()

/**
 * Stands [provider] up on [authority] so `contentResolver` and the package manager both know it.
 *
 * `isDocumentUri()` does not look at the URI alone: it asks the package manager whether anything
 * answers `ACTION_DOCUMENTS_PROVIDER` for that authority. Registering the provider with the
 * resolver is not enough, which is the whole reason [asDocumentsProvider] is a parameter rather
 * than always true — the negative case is a test.
 */
internal fun registerProvider(
    context: Context,
    provider: Class<out FakeSafProvider>,
    authority: String,
    asDocumentsProvider: Boolean,
) {
    val info = ProviderInfo().apply {
        this.authority = authority
        packageName = context.packageName
        name = provider.name
        exported = true
        grantUriPermissions = true
    }
    Robolectric.buildContentProvider(provider).create(info)

    val packageManager = shadowOf(context.packageManager)
    packageManager.addOrUpdateProvider(info)
    if (asDocumentsProvider) {
        packageManager.addIntentFilterForProvider(
            ComponentName(context.packageName, provider.name),
            IntentFilter(DocumentsContract.PROVIDER_INTERFACE),
        )
    }
}
