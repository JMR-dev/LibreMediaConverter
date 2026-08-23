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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** What a destination volume says when it fills up mid-write. */
private const val NO_SPACE = "No space left on device"

private const val DOCUMENTS_AUTHORITY = "org.libremediaconverter.test.documents"
private const val PLAIN_AUTHORITY = "org.libremediaconverter.test.plain"

/**
 * A stand-in for the provider behind a SAF destination.
 *
 * It answers only what `publish()` asks of a destination -- how many bytes are already there,
 * and delete it -- backed by a real file so the assertions are about the filesystem rather
 * than about a mock's call log alone. The rest of the `ContentProvider` surface is stubbed.
 *
 * Writing is deliberately NOT routed through it. Robolectric's `ShadowContentResolver`
 * consults its registered-stream map before it reaches any provider, which is what lets a
 * test hand out a stream that writes some bytes and then fails -- the condition this whole
 * file exists for, and one a real provider cannot be asked to produce on demand.
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
        val file = backingFile(uri)
        if (!file.exists()) return null
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>(file.name, file.length()))
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

        fun backingFile(uri: Uri) = File(root, uri.lastPathSegment.orEmpty())

        fun reset(directory: File) {
            root = directory
            deleteRequests.clear()
            deleteFailure = null
        }
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
 * A sink that behaves like a volume filling up.
 *
 * Two failure shapes, because `publish()` has to survive both: a write that throws partway,
 * and a `close()` that throws while flushing -- the second arriving after `copyTo` has
 * already returned successfully.
 */
private class UnreliableOutputStream(
    private val sink: OutputStream,
    private val failAfterBytes: Int = Int.MAX_VALUE,
    private val failOnClose: Boolean = false,
) : OutputStream() {

    private var written = 0

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = failAfterBytes - written
        if (room <= 0) throw IOException(NO_SPACE)
        val accepted = minOf(room, len)
        sink.write(b, off, accepted)
        written += accepted
        if (accepted < len) throw IOException(NO_SPACE)
    }

    override fun flush() = sink.flush()

    override fun close() {
        sink.close()
        if (failOnClose) throw IOException(NO_SPACE)
    }
}

/**
 * `publish()` on the paths where something goes wrong.
 *
 * The defect: a copy that failed partway left the bytes it had managed at the name the user
 * picked, while the UI said "Could not save the file". [OutputPublisherStagingTest] covers
 * the staging side of the same class; this covers the destination side, and needs a provider
 * rather than a bare file because the destination is a `content://` URI and the fix turns on
 * what kind of URI it is.
 *
 * Every case here is a failure case except one, and that is the point -- these branches never
 * run in a healthy test run and are exactly the ones a user meets on a bad day.
 */
@RunWith(RobolectricTestRunner::class)
class OutputPublisherPublishTest {

    private lateinit var context: Context
    private lateinit var publisher: OutputPublisher
    private lateinit var staged: File

    private val payload = ByteArray(8192) { (it % 251).toByte() }

    private val documentUri: Uri = Uri.parse("content://$DOCUMENTS_AUTHORITY/document/holiday.mp4")
    private val plainUri: Uri = Uri.parse("content://$PLAIN_AUTHORITY/document/holiday_plain.mp4")
    private val deadUri: Uri = Uri.parse("content://org.libremediaconverter.nonexistent/document/gone.mp4")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FakeSafProvider.reset(File(context.cacheDir, "destinations").apply { mkdirs() })
        register(FakeSafProvider::class.java, DOCUMENTS_AUTHORITY, asDocumentsProvider = true)
        register(FakePlainProvider::class.java, PLAIN_AUTHORITY, asDocumentsProvider = false)

        // SAF's CreateDocument contract hands back a document that already exists and is
        // empty, so that is the state every destination starts in here.
        FakeSafProvider.backingFile(documentUri).writeBytes(ByteArray(0))
        FakeSafProvider.backingFile(plainUri).writeBytes(ByteArray(0))

        publisher = OutputPublisher(context)
        staged = publisher.createStagingFile("holiday.mp4").apply { writeBytes(payload) }
    }

    @Test
    fun `a copy that fails partway leaves nothing at the destination`() {
        failMidCopy(documentUri, afterBytes = 512)

        val failure = assertThrows(IOException::class.java) { publisher.publish(staged, documentUri) }

        assertEquals(NO_SPACE, failure.message)
        assertEquals(listOf(documentUri), FakeSafProvider.deleteRequests)
        assertFalse(
            "a truncated file must not be left at the name the user picked",
            FakeSafProvider.backingFile(documentUri).exists(),
        )
    }

    @Test
    fun `a close that fails while flushing counts as a failed copy`() {
        // copyTo() has already returned by the time this throws. Guarding only the copy and
        // not the close would leave the file behind on exactly the disk-full case.
        shadowOf(context.contentResolver).registerOutputStreamSupplier(documentUri) {
            UnreliableOutputStream(FakeSafProvider.backingFile(documentUri).outputStream(), failOnClose = true)
        }

        val failure = assertThrows(IOException::class.java) { publisher.publish(staged, documentUri) }

        assertEquals(NO_SPACE, failure.message)
        assertFalse(
            "bytes that were never flushed are not a saved file",
            FakeSafProvider.backingFile(documentUri).exists(),
        )
    }

    @Test
    fun `a destination that already held bytes is not deleted`() {
        // Not the CreateDocument case: a provider that handed back an existing document for
        // a name the user re-picked. Truncating it is bad; removing it outright is worse, and
        // publish() cannot tell from a Uri that the app created it.
        val existing = FakeSafProvider.backingFile(documentUri).apply { writeBytes(ByteArray(4096)) }
        failMidCopy(documentUri, afterBytes = 512)

        assertThrows(IOException::class.java) { publisher.publish(staged, documentUri) }

        assertTrue("a document this app did not create must survive", existing.exists())
        assertEquals(emptyList<Uri>(), FakeSafProvider.deleteRequests)
    }

    @Test
    fun `a destination that is not a document is left alone`() {
        failMidCopy(plainUri, afterBytes = 512)

        assertThrows(IOException::class.java) { publisher.publish(staged, plainUri) }

        assertEquals(
            "deleteDocument has no business on a URI that is not a document",
            emptyList<Uri>(),
            FakeSafProvider.deleteRequests,
        )
        assertTrue(FakeSafProvider.backingFile(plainUri).exists())
    }

    @Test
    fun `a cleanup that fails does not replace the failure the user needs to see`() {
        FakeSafProvider.deleteFailure = SecurityException("provider refused the delete")
        failMidCopy(documentUri, afterBytes = 512)

        val failure = assertThrows(IOException::class.java) { publisher.publish(staged, documentUri) }

        assertEquals("the disk-full failure is what save() reports", NO_SPACE, failure.message)
        assertEquals(
            "the cleanup failure is attached rather than lost",
            listOf("provider refused the delete"),
            failure.suppressedExceptions.map { it.message },
        )
    }

    @Test
    fun `a destination the provider will not open does not stay behind as an empty file`() {
        // No stream supplier is registered for this URI and the fake provider does not implement
        // openFile, which is a provider that has gone away between the picker and the write.
        //
        // The document exists all the same: SAF's CreateDocument contract created it before
        // publish() was ever called, so "nothing has been written yet" was never the same claim as
        // "there is nothing of ours here". Leaving it means a zero-byte file at the name the user
        // chose, while the screen says the save failed.
        val destination = FakeSafProvider.backingFile(documentUri)
        assertEquals("the fixture starts as the empty document SAF hands back", 0L, destination.length())

        val failure = runCatching { publisher.publish(staged, documentUri) }.exceptionOrNull()

        assertTrue("a destination that will not open must not appear to succeed, got $failure", failure != null)
        assertEquals(listOf(documentUri), FakeSafProvider.deleteRequests)
        assertFalse(
            "a zero-byte file must not be left at the name the user picked",
            destination.exists(),
        )
    }

    @Test
    fun `a destination that cannot be opened at all fails without any cleanup`() {
        // The JVM twin of UnopenableUriTest's unwritable-destination case. The open sits inside
        // the guarded region now, so what keeps this one untouched is the guard rather than the
        // placement: nothing answers for that authority, so no size can be read, and "I could not
        // tell" must never authorise a delete.
        val failure = runCatching { publisher.publish(staged, deadUri) }.exceptionOrNull()

        assertTrue("publishing to a dead provider must not appear to succeed, got $failure", failure != null)
        assertEquals(emptyList<Uri>(), FakeSafProvider.deleteRequests)
    }

    @Test
    fun `a copy that succeeds delivers every byte and deletes nothing`() {
        shadowOf(context.contentResolver).registerOutputStreamSupplier(documentUri) {
            FakeSafProvider.backingFile(documentUri).outputStream()
        }

        publisher.publish(staged, documentUri)

        assertArrayEquals(payload, FakeSafProvider.backingFile(documentUri).readBytes())
        assertEquals(emptyList<Uri>(), FakeSafProvider.deleteRequests)
    }

    /**
     * Arms the destination to accept [afterBytes] and then fail.
     *
     * A supplier rather than a ready-made stream: opening the backing file truncates it, and
     * doing that here would erase the very content the "already held bytes" case is about
     * before `publish()` ever got to read its size.
     */
    private fun failMidCopy(destination: Uri, afterBytes: Int) {
        shadowOf(context.contentResolver).registerOutputStreamSupplier(destination) {
            UnreliableOutputStream(
                FakeSafProvider.backingFile(destination).outputStream(),
                failAfterBytes = afterBytes,
            )
        }
    }

    private fun register(provider: Class<out FakeSafProvider>, authority: String, asDocumentsProvider: Boolean) {
        val info = ProviderInfo().apply {
            this.authority = authority
            packageName = context.packageName
            name = provider.name
            exported = true
            grantUriPermissions = true
        }
        Robolectric.buildContentProvider(provider).create(info)

        // isDocumentUri() does not look at the URI alone: it asks the package manager whether
        // anything answers ACTION_DOCUMENTS_PROVIDER for that authority. Registering the
        // provider with the resolver is not enough, which is the whole reason the negative
        // case above can exist.
        val packageManager = shadowOf(context.packageManager)
        packageManager.addOrUpdateProvider(info)
        if (asDocumentsProvider) {
            packageManager.addIntentFilterForProvider(
                ComponentName(context.packageName, provider.name),
                IntentFilter(DocumentsContract.PROVIDER_INTERFACE),
            )
        }
    }
}
