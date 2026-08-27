package org.libremediaconverter.convert

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** What a destination volume says when it fills up mid-write. */
private const val NO_SPACE = "No space left on device"

/** How far a failing copy gets before the volume "fills up". Any value below the payload does. */
private const val PARTIAL_BYTES = 512

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

    /** How far a failing copy gets before the volume "fills up". Any value below the payload does. */

    private val documentUri: Uri = Uri.parse("content://$DOCUMENTS_AUTHORITY/document/holiday.mp4")
    private val plainUri: Uri = Uri.parse("content://$PLAIN_AUTHORITY/document/holiday_plain.mp4")
    private val deadUri: Uri = Uri.parse("content://org.libremediaconverter.nonexistent/document/gone.mp4")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FakeSafProvider.reset(File(context.cacheDir, "destinations").apply { mkdirs() })
        registerProvider(context, FakeSafProvider::class.java, DOCUMENTS_AUTHORITY, asDocumentsProvider = true)
        registerProvider(context, FakePlainProvider::class.java, PLAIN_AUTHORITY, asDocumentsProvider = false)

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
    fun `a destination whose size cannot be determined is never deleted`() {
        // The three short-circuits in destinationIsKnownEmpty, and the reason its KDoc gives for
        // each of them answering false:
        //
        //   "this decides whether a delete is allowed and 'I could not tell' must never authorise
        //    one."
        //
        // The contrast is `a copy that fails partway leaves nothing at the destination` above: a
        // provider that *does* say zero gets the delete. These say nothing, so they must not.
        // Getting this backwards costs the user a file they already had, on a save that failed.
        //
        // Named exemption: of the three conjuncts, `size >= 0` cannot be falsified behaviourally.
        // Measured -- getColumnIndex returns -1 for an absent column, and isNull(-1) throws
        // CursorIndexOutOfBoundsException, which the surrounding runCatching already turns into
        // `?: false`. So relaxing it to `size >= -1` leaves this test green: same answer, reached
        // by the exception path instead. The guard should stay -- control flow through an exception
        // is worse than a comparison, and another Cursor implementation need not throw -- but no
        // assertion here pins it, and saying so beats implying the missing-column case covers it.
        // `!row.isNull(size)` and `row.moveToFirst()` do both bite.
        listOf(
            RowShape.NO_SIZE_COLUMN to "a cursor with no SIZE column",
            RowShape.NULL_SIZE to "a cursor whose SIZE cell is null",
            RowShape.NO_ROWS to "a cursor holding no rows",
        ).forEach { (shape, description) ->
            FakeSafProvider.deleteRequests.clear()
            FakeSafProvider.backingFile(documentUri).writeBytes(ByteArray(0))
            FakeSafProvider.rowShape = shape
            failMidCopy(documentUri, afterBytes = PARTIAL_BYTES)

            assertThrows(IOException::class.java) { publisher.publish(staged, documentUri) }

            assertEquals(
                "$description must not authorise a delete",
                emptyList<Uri>(),
                FakeSafProvider.deleteRequests,
            )
            assertTrue(
                "$description must leave the destination where it was",
                FakeSafProvider.backingFile(documentUri).exists(),
            )
        }
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
}
