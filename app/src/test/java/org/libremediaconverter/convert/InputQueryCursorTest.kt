package org.libremediaconverter.convert

import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * What [InputQuery] makes of a metadata row.
 *
 * ## Why this is a separate file from `UnknownInputSizeTest`
 *
 * That test drives the case where **no provider is registered** — the query returns null and
 * `measure()` answers instead — and it drives it thoroughly. What it never does is hand `InputQuery`
 * a row. Before this file, nothing did: `firstRow`'s body, `displayNameOrNull` and `sizeOrNull` had
 * never executed in the JVM suite, so every branch inside them was untested.
 *
 * ## What is actually being pinned
 *
 * Not "does it read a cursor" — that would pass against almost any implementation. The rule is that
 * **a size nobody could determine must not arrive as a number**, and there are four separate ways a
 * provider fails to determine one: a null cell, a missing column, a negative value, and no row at
 * all. `InputQuery`'s KDoc states the stake:
 *
 * > a worker's input `Data` carries the size the *picker* found … `hasSpaceFor(0)` is only "is there
 * > 128 MB free".
 *
 * So each of those four must produce `null`, and `null` specifically — not `0`, not `-1`. A test
 * that asserted only "not the file's length" would pass on `0`, which is the exact conflation the
 * class exists to end.
 *
 * ## Why every fall-through lands on null here
 *
 * [FakeSafProvider] does not implement `openFile`, so `measure()` cannot answer for these URIs
 * either. That is deliberate: it isolates the cursor half. The other direction — the cursor says
 * nothing and `measure()` succeeds — is `UnknownInputSizeTest`'s
 * `a picked file no provider describes is measured rather than reported as empty`, and is not
 * repeated here.
 *
 * ## What the mutations say, including the one that does not bite
 *
 * Measured against `MatrixCursor`, which is what these tests drive:
 *
 * | call on a null cell | result |
 * |---|---|
 * | `getString` | returns `null` |
 * | `getLong` | returns **`0`** |
 *
 * That second row is why `sizeOrNull`'s `!isNull(it)` guard is load-bearing and why these tests
 * bite: remove it and a null size arrives as `0`, a real number indistinguishable from an empty
 * file, which is the precise conflation this class exists to end. Removing it reddens
 * `a null size is unknown rather than zero`. Removing the trailing `takeIf { it >= 0 }` reddens
 * `a negative size is unknown rather than reported`.
 *
 * **Named exemption: `displayNameOrNull`'s `!isNull(it)` guard is not pinned by anything here, and
 * cannot be.** `getString` returns null for a null cell, so the fallback applies with or without
 * the guard — removing it leaves every test in this file green. The guard is not redundant in
 * production: `Cursor.getString`'s contract states that whether it throws on a null column is
 * *implementation-defined*, and a real `ContentProvider` is free to throw where `MatrixCursor`
 * returns null. It should stay. It simply cannot be falsified with this cursor, and saying so is
 * better than implying `a null display name falls back without disturbing the size` covers it —
 * that test pins the behaviour, not the guard.
 */
@RunWith(RobolectricTestRunner::class)
class InputQueryCursorTest {

    private lateinit var context: Context
    private lateinit var uri: Uri

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FakeSafProvider.reset(File(context.cacheDir, "picked").apply { mkdirs() })
        registerProvider(context, FakeSafProvider::class.java, DOCUMENTS_AUTHORITY, asDocumentsProvider = true)
        uri = Uri.parse("content://$DOCUMENTS_AUTHORITY/document/holiday.mp4")
        FakeSafProvider.backingFile(uri).writeBytes(ByteArray(PAYLOAD_BYTES))
    }

    @Test
    fun `a provider that answers properly supplies both the name and the size`() {
        val described = InputQuery.describe(context, uri)

        assertEquals("holiday.mp4", described.displayName)
        assertEquals(PAYLOAD_BYTES.toLong(), described.sizeBytes)
    }

    @Test
    fun `a null display name falls back without disturbing the size`() {
        FakeSafProvider.rowShape = RowShape.NULL_DISPLAY_NAME

        val described = InputQuery.describe(context, uri)

        assertEquals(InputQuery.FALLBACK_DISPLAY_NAME, described.displayName)
        // The two columns are read independently. A provider that cannot name the file can still
        // size it, and losing the size here would be a bug the name assertion alone would miss.
        assertEquals(PAYLOAD_BYTES.toLong(), described.sizeBytes)
    }

    @Test
    fun `a cursor with no display name column falls back rather than throwing`() {
        // getColumnIndex returns -1 rather than throwing, so the `it >= 0` guard is the only thing
        // between this and an IllegalArgumentException out of getString.
        FakeSafProvider.rowShape = RowShape.NO_DISPLAY_NAME_COLUMN

        val described = InputQuery.describe(context, uri)

        assertEquals(InputQuery.FALLBACK_DISPLAY_NAME, described.displayName)
        assertEquals(PAYLOAD_BYTES.toLong(), described.sizeBytes)
    }

    @Test
    fun `a null size is unknown rather than zero`() {
        FakeSafProvider.rowShape = RowShape.NULL_SIZE

        assertNull(unknownSizeMessage("a null cell"), InputQuery.sizeOf(context, uri))
    }

    @Test
    fun `a cursor with no size column is unknown rather than zero`() {
        FakeSafProvider.rowShape = RowShape.NO_SIZE_COLUMN

        assertNull(unknownSizeMessage("a missing column"), InputQuery.sizeOf(context, uri))
    }

    @Test
    fun `a negative size is unknown rather than reported`() {
        // What anything without a fixed length reports -- a pipe, or a provider streaming its
        // answer. Passing -1 through would be worse than passing 0: hasSpaceFor compares it
        // against free space, so it would read as "needs less than nothing".
        FakeSafProvider.rowShape = RowShape.NEGATIVE_SIZE

        assertNull(unknownSizeMessage("a negative size"), InputQuery.sizeOf(context, uri))
    }

    @Test
    fun `a cursor with no rows is unknown rather than zero`() {
        // Distinct from the provider returning null, which UnknownInputSizeTest covers. A cursor
        // that exists and holds nothing still has to reach the same answer.
        FakeSafProvider.rowShape = RowShape.NO_ROWS

        val described = InputQuery.describe(context, uri)

        assertEquals(InputQuery.FALLBACK_DISPLAY_NAME, described.displayName)
        assertNull(unknownSizeMessage("an empty cursor"), described.sizeBytes)
    }

    @Test
    fun `a provider that throws is survived rather than propagated`() {
        // The guard firstRow's KDoc exists for: "a resolver call is a call into another app ... and
        // a file picker is not a place to bring the process down from". Without the runCatching,
        // this SecurityException reaches the caller and takes the pick with it.
        FakeSafProvider.rowShape = RowShape.QUERY_THROWS

        val described = InputQuery.describe(context, uri)

        assertEquals(InputQuery.FALLBACK_DISPLAY_NAME, described.displayName)
        assertNull(unknownSizeMessage("a provider that threw"), described.sizeBytes)
    }

    @Test
    fun `a join total is unknown when any one input could not be sized`() {
        // The consequence the four cases above exist for, asserted once at the place it lands.
        // Summing the inputs that did answer would produce a lower bound indistinguishable from a
        // real total, which is what the space check cannot tell apart.
        FakeSafProvider.rowShape = RowShape.NULL_SIZE
        val unsizable = InputQuery.sizeOf(context, uri)
        FakeSafProvider.rowShape = RowShape.NORMAL
        val sizable = InputQuery.sizeOf(context, uri)

        assertEquals(PAYLOAD_BYTES.toLong(), sizable)
        assertNull(unsizable)
        assertNull("one unknown input makes the whole total unknown", InputQuery.total(listOf(sizable, unsizable)))
    }

    private fun unknownSizeMessage(cause: String) =
        "$cause means nobody could size the file; that must be null, not 0 -- hasSpaceFor(0) is only a headroom check"

    private companion object {
        const val PAYLOAD_BYTES = 4096
    }
}
