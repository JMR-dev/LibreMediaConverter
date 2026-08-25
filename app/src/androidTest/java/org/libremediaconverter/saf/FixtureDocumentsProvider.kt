package org.libremediaconverter.saf

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/**
 * One file, offered to the system file picker, so that picking one can be tested at all.
 *
 * DocumentsUI does not browse the filesystem: it lists what [DocumentsProvider]s hand it. So a
 * test that drives the real picker has to supply the thing being picked, and it has to supply it
 * as a manifest-declared component — a `ContentProvider` is instantiated by the system, never
 * registered from test code. `app/src/androidTest/AndroidManifest.xml` is that declaration and
 * says why each of its attributes is load-bearing.
 *
 * ### Why not just write a file to Downloads
 *
 * That would work and it would test less. Two properties of a provider are what
 * [SafPickerRoundTripTest] actually needs:
 *
 * - **The root declares [Root.COLUMN_MIME_TYPES], and DocumentsUI filters the drawer by it.**
 *   That is what gives the MIME mutation a bite with a shape: ask for a type this root does not
 *   offer and the root itself is not in the picker, so the failure is "the fixture root is not
 *   there" rather than "one file among the hundreds in Downloads was not listed".
 * - **The contents are exactly this and nothing else.** A shared directory accumulates whatever
 *   earlier runs and other tests left in it, and a picker test that finds the wrong file passes.
 *
 * ### It runs in its own process, so it seeds itself
 *
 * The instrumentation's own code executes inside the *app's* process; this provider is a
 * component of the instrumentation *package*, so the system starts a separate process under
 * `org.libremediaconverter.test` for it. Nothing the test sets up in a field is visible here.
 * The bytes therefore come from this APK's own assets, on demand, and the test and the provider
 * agree only on the constants below — which are compile-time, so they cannot drift.
 *
 * The descriptor is opened on a real file rather than served through a pipe, deliberately.
 * `InputQuery.sizeOf` falls back to `ParcelFileDescriptor.statSize` when a provider omits
 * `OpenableColumns.SIZE`, and a pipe's `statSize` is `-1` — an unknown size, which is a
 * different case with its own screen. This fixture is meant to be an ordinary, fully described
 * file, so the one thing under test is the round trip.
 */
class FixtureDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    /**
     * The single root, named [ROOT_TITLE] so a UiAutomator text selector is unambiguous.
     *
     * [Root.COLUMN_MIME_TYPES] is the important column. Left null it would mean "this root
     * supports everything", the picker would list it whatever was asked for, and the MIME
     * mutation would have nothing to bite on.
     */
    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(copyOf(projection, DEFAULT_ROOT_PROJECTION)).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT_ID)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                .add(Root.COLUMN_TITLE, ROOT_TITLE)
                .add(Root.COLUMN_SUMMARY, "Instrumentation fixture")
                .add(Root.COLUMN_MIME_TYPES, FIXTURE_MIME_TYPE)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery)
        }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor =
        MatrixCursor(copyOf(projection, DEFAULT_DOCUMENT_PROJECTION)).apply {
            when (documentId) {
                ROOT_DOCUMENT_ID -> addDirectoryRow()
                FIXTURE_DOCUMENT_ID -> addFixtureRow()
                else -> throw java.io.FileNotFoundException("no such document: $documentId")
            }
        }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(copyOf(projection, DEFAULT_DOCUMENT_PROJECTION)).apply {
        if (parentDocumentId == ROOT_DOCUMENT_ID) addFixtureRow()
    }

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
        if (documentId != FIXTURE_DOCUMENT_ID) {
            throw java.io.FileNotFoundException("no such document: $documentId")
        }
        return ParcelFileDescriptor.open(fixtureFile(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun MatrixCursor.addDirectoryRow() {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            .add(Document.COLUMN_DISPLAY_NAME, ROOT_TITLE)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, null)
    }

    private fun MatrixCursor.addFixtureRow() {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, FIXTURE_DOCUMENT_ID)
            .add(Document.COLUMN_DISPLAY_NAME, FIXTURE_DISPLAY_NAME)
            .add(Document.COLUMN_MIME_TYPE, FIXTURE_MIME_TYPE)
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, fixtureFile().length())
            .add(Document.COLUMN_LAST_MODIFIED, fixtureFile().lastModified())
    }

    /**
     * The fixture on disk, unpacked from this APK's assets the first time anything asks.
     *
     * Idempotent rather than seeded once at [onCreate], because this process is started by
     * whoever queries the provider and can be killed between two queries of the same test.
     */
    private fun fixtureFile(): File {
        val context = requireNotNull(context) { "provider used before onCreate" }
        val file = File(context.filesDir, FIXTURE_DISPLAY_NAME)
        if (file.length() == 0L) {
            context.assets.open(FIXTURE_ASSET).use { source ->
                file.outputStream().use(source::copyTo)
            }
        }
        return file
    }

    /**
     * [projection] as a `MatrixCursor` will take it, or [fallback] when the caller asked for
     * everything.
     *
     * Rebuilt element by element rather than spread into `arrayOf`, so nothing depends on the
     * variance of the platform array type this arrives as.
     */
    private fun copyOf(projection: Array<out String>?, fallback: Array<String>): Array<String> =
        if (projection == null) fallback else Array(projection.size) { projection[it] }

    companion object {

        /** Matches `android:authorities` in `app/src/androidTest/AndroidManifest.xml`. */
        const val AUTHORITY: String = "org.libremediaconverter.test.fixtures"

        /**
         * What the picker's drawer calls this root.
         *
         * Deliberately not a word any other root uses. DocumentsUI's drawer also lists
         * "Downloads", "Images", "Videos" and the device name, and a UiAutomator selector that
         * could match two of them is not a selector.
         */
        const val ROOT_TITLE: String = "LMC R38 fixtures"

        /**
         * What the file card has to end up showing.
         *
         * The same string reaches the assertion two ways — as the picker row UiAutomator taps,
         * and as `OpenableColumns.DISPLAY_NAME` on the URI the app is handed — which is exactly
         * the round trip under test.
         */
        const val FIXTURE_DISPLAY_NAME: String = "lmc-r38-fixture.mp4"

        /**
         * The type the root advertises, and the one the MIME mutation has to stop matching.
         *
         * A real type rather than something invented, so the wildcard filter the screen passes
         * today is not the only one under which this test could pass.
         */
        const val FIXTURE_MIME_TYPE: String = "video/mp4"

        private const val ROOT_ID = "lmc-r38-root"
        private const val ROOT_DOCUMENT_ID = "root"
        private const val FIXTURE_DOCUMENT_ID = "root/$FIXTURE_DISPLAY_NAME"

        /** Already in this source set, and already a real H.264 MP4 the engines can open. */
        private const val FIXTURE_ASSET = "sample_h264.mp4"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
