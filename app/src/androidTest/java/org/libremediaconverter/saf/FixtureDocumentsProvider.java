package org.libremediaconverter.saf;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One file, offered to the system file picker, so that picking one can be tested at all.
 *
 * <p>DocumentsUI does not browse a filesystem: it lists what {@link DocumentsProvider}s hand it.
 * So a test that drives the real picker has to supply the thing being picked, and it has to
 * supply it as a manifest-declared component, because a {@code ContentProvider} is instantiated
 * by the system and cannot be registered from test code. {@code
 * app/src/androidTest/AndroidManifest.xml} is that declaration and says why each of its
 * attributes is load-bearing.
 *
 * <h2>The only Java file in this module, and it has to be</h2>
 *
 * <p>Everything else here is Kotlin. This cannot be: <b>the Kotlin standard library is not on
 * this class's classpath at runtime.</b>
 *
 * <p>Instrumentation code normally never notices. The test APK's dex is loaded into the app's
 * process, where the app APK supplies {@code kotlin.jvm.internal.Intrinsics} — so the test APK is
 * built without it, deliberately, since packaging a second copy is what {@code
 * checkDebugAndroidTestDuplicateClasses} exists to prevent. A provider is different. It is a
 * component of the instrumentation <i>package</i>, so when DocumentsUI queries it the system
 * starts a plain {@code org.libremediaconverter.test} process with only the test APK on its dex
 * path, and no app APK anywhere. The Kotlin version of this file crashed there on its first
 * query, before returning a single row:
 *
 * <pre>
 * FATAL EXCEPTION: binder:6369_2
 * Process: org.libremediaconverter.test
 * java.lang.NoClassDefFoundError: Failed resolution of: Lkotlin/jvm/internal/Intrinsics;
 *     at org.libremediaconverter.saf.FixtureDocumentsProvider.queryDocument
 * </pre>
 *
 * <p>The compiler emits that reference for the null checks on almost every function, so there is
 * no Kotlin dialect that avoids it. For the same reason nothing here imports {@code androidx.*}:
 * those classes are absent from this process for exactly the same reason. Framework and JDK only.
 *
 * <h2>Why a provider rather than a file in Downloads</h2>
 *
 * <p>That would have worked, and it would have tested less. Two properties are what {@code
 * SafPickerRoundTripTest} actually needs:
 *
 * <ul>
 *   <li><b>The root declares {@link Root#COLUMN_MIME_TYPES}, and DocumentsUI filters by it.</b>
 *       That is what gives the screen's MIME filter a mutation with a shape: ask for a type this
 *       root does not offer and the root itself is not in the picker, so the failure reads as
 *       "the fixture root is not there" rather than "one file among the hundreds in Downloads was
 *       not listed".
 *   <li><b>The contents are exactly this and nothing else.</b> A shared directory accumulates
 *       whatever earlier runs and other tests left in it, and a picker test that finds the wrong
 *       file passes.
 * </ul>
 *
 * <p>The descriptor is opened on a real file rather than served through a pipe, deliberately.
 * {@code InputQuery.sizeOf} falls back to {@code ParcelFileDescriptor.statSize} when a provider
 * omits {@code OpenableColumns.SIZE}, and a pipe's {@code statSize} is {@code -1} — an unknown
 * size, which is a different case with a screen of its own. This fixture is meant to be an
 * ordinary, fully described file, so that the one thing under test is the round trip.
 */
public final class FixtureDocumentsProvider extends DocumentsProvider {

    /**
     * What the picker calls this root.
     *
     * <p>Deliberately not a word any other root uses. The picker's own landing screen already
     * offers "Images", "Audio", "Videos" and "Documents", and a UiAutomator selector that could
     * match two things is not a selector.
     */
    public static final String ROOT_TITLE = "LMC R38 fixtures";

    /**
     * What the file card has to end up showing.
     *
     * <p>The same string reaches the assertion two ways — as the picker row UiAutomator taps, and
     * as {@code OpenableColumns.DISPLAY_NAME} on the URI the app is handed — which is exactly the
     * round trip under test.
     */
    public static final String FIXTURE_DISPLAY_NAME = "lmc-r38-fixture.mp4";

    /**
     * The type the root advertises, and the one the MIME mutation has to stop matching.
     *
     * <p>A real type rather than something invented, so the wildcard filter the screen passes
     * today is not the only filter under which this test could pass.
     */
    public static final String FIXTURE_MIME_TYPE = "video/mp4";

    private static final String ROOT_ID = "lmc-r38-root";
    private static final String ROOT_DOCUMENT_ID = "root";
    private static final String FIXTURE_DOCUMENT_ID = "root/" + FIXTURE_DISPLAY_NAME;

    /** Already in this source set, and already a real H.264 MP4 the engines can open. */
    private static final String FIXTURE_ASSET = "sample_h264.mp4";

    private static final String[] DEFAULT_ROOT_PROJECTION = {
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * The single root.
     *
     * <p>{@link Root#COLUMN_MIME_TYPES} is the important column. Left null it would mean "this
     * root supports everything", the picker would list it whatever was asked for, and the MIME
     * mutation would have nothing to bite on.
     */
    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        cursor.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            .add(Root.COLUMN_TITLE, ROOT_TITLE)
            .add(Root.COLUMN_SUMMARY, "Instrumentation fixture")
            .add(Root.COLUMN_MIME_TYPES, FIXTURE_MIME_TYPE)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_gallery);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        if (ROOT_DOCUMENT_ID.equals(documentId)) {
            addDirectoryRow(cursor);
        } else if (FIXTURE_DOCUMENT_ID.equals(documentId)) {
            addFixtureRow(cursor);
        } else {
            throw new FileNotFoundException("no such document: " + documentId);
        }
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        if (ROOT_DOCUMENT_ID.equals(parentDocumentId)) {
            addFixtureRow(cursor);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (!FIXTURE_DOCUMENT_ID.equals(documentId)) {
            throw new FileNotFoundException("no such document: " + documentId);
        }
        return ParcelFileDescriptor.open(fixtureFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void addDirectoryRow(MatrixCursor cursor) {
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            .add(Document.COLUMN_DISPLAY_NAME, ROOT_TITLE)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, null);
    }

    private void addFixtureRow(MatrixCursor cursor) throws FileNotFoundException {
        File file = fixtureFile();
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, FIXTURE_DOCUMENT_ID)
            .add(Document.COLUMN_DISPLAY_NAME, FIXTURE_DISPLAY_NAME)
            .add(Document.COLUMN_MIME_TYPE, FIXTURE_MIME_TYPE)
            .add(Document.COLUMN_FLAGS, 0)
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    /**
     * The fixture on disk, unpacked from this APK's own assets the first time anything asks.
     *
     * <p>On demand rather than seeded once in {@link #onCreate()}, because this process is started
     * by whoever queries the provider and can be killed between two queries of the same test.
     *
     * <p>A failure here is reported as {@link FileNotFoundException} rather than swallowed. A
     * provider that answers with a zero-byte file would put the test on the "Size unknown" screen
     * with nothing saying why.
     */
    private File fixtureFile() throws FileNotFoundException {
        File file = new File(getContext().getFilesDir(), FIXTURE_DISPLAY_NAME);
        if (file.length() > 0L) {
            return file;
        }
        try (InputStream source = getContext().getAssets().open(FIXTURE_ASSET);
                OutputStream sink = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("could not unpack " + FIXTURE_ASSET + ": " + e);
        }
        return file;
    }
}
