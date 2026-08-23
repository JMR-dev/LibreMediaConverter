package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.hasKeyWithValueOfType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.join.JoinState
import org.libremediaconverter.join.JoinViewModel
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.work.ConcatWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * That a size nobody reported is not the same thing as a size of zero.
 *
 * `queryFile` started at `var size = 0L` and only moved off it when a provider answered the
 * `OpenableColumns.SIZE` column, so "the file is empty" and "nobody told me" arrived at the space
 * check as the same number — and `hasSpaceFor(0)` is only "is there 128 MB free".
 *
 * The gap is not hypothetical. On a Pixel 10 Pro XL, `contentResolver.query` on a `file://` URI
 * returns null outright, so the cursor block never runs and the default survives:
 * `queryFile gave displayName='input' sizeBytes=0`. Robolectric reproduces that exactly, which is
 * what makes the first test below a JVM test rather than a device one.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class UnknownInputSizeTest {

    private lateinit var app: Application
    private lateinit var workers: SucceedingWorkerFactory

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // FFprobe's loader throws a bare java.lang.Error on the JVM, and nothing here is about
        // what the probe found.
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        // Both ViewModels reach WorkManager.getInstance() while constructing.
        workers = installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a picked file no provider describes is measured rather than reported as empty`() {
        val input = fileOfSize(INPUT_BYTES, "holiday.mp4")

        val ready = pickedInto(Uri.fromFile(input))

        // The resolver answers nothing at all for a file:// URI -- the exact device case -- so the
        // only way to this number is opening the file and asking the descriptor.
        assertEquals(INPUT_BYTES.toLong(), ready.input.sizeBytes)
    }

    @Test
    fun `a picked file nothing can measure has no size rather than a size of zero`() {
        // No provider is registered for this authority, so the metadata query returns null and
        // openFileDescriptor throws FileNotFoundException. Nothing can say how big it is, and
        // saying "zero" would be a claim rather than an answer.
        val ready = pickedInto(Uri.parse("content://test/holiday.mp4"))

        assertNull(ready.input.sizeBytes)
    }

    @Test
    fun `the same measurement is what the join picker gets`() {
        // Not a copy of the convert test for its own sake: `queryFile` existed twice, once in each
        // ViewModel, byte for byte. One of the two being fixed is the shape this would come back in.
        val first = fileOfSize(FIRST_JOIN_BYTES, "one.mp4")
        val second = fileOfSize(SECOND_JOIN_BYTES, "two.mp4")
        val viewModel = JoinViewModel(app)

        viewModel.onInputsPicked(listOf(Uri.fromFile(first), Uri.fromFile(second)))
        val ready = awaitState(viewModel.state, "Ready") { it is JoinState.Ready } as JoinState.Ready

        assertEquals(
            listOf(FIRST_JOIN_BYTES.toLong(), SECOND_JOIN_BYTES.toLong()),
            ready.inputs.map { it.sizeBytes },
        )
    }

    @Test
    fun `a join enqueues the total it worked out, and no total at all when it could not`() {
        // The wiring, which the two tests around it do not reach: `InputQuery.total` being right
        // says nothing about `join()` calling it, and `ConcatWorker`'s unknown branch is reached
        // by work built in that test rather than by this ViewModel. Restoring
        // `inputs.sumOf { it.sizeBytes ?: 0L }` leaves both of those green.
        //
        // Read off the request on its way to a worker, because that is the only place it is
        // legible: `WorkInfo` hands back a job's tags and its output, never the input `Data`.
        val first = fileOfSize(FIRST_JOIN_BYTES, "one.mp4")
        val second = fileOfSize(SECOND_JOIN_BYTES, "two.mp4")

        joined(Uri.fromFile(first), Uri.fromFile(second))
        val known = workers.enqueued.single()
        assertEquals(
            (FIRST_JOIN_BYTES + SECOND_JOIN_BYTES).toLong(),
            known.getLong(ConcatWorker.KEY_TOTAL_BYTES, MISSING),
        )

        // And with one input nothing can size, the key is absent rather than carrying a short
        // total -- a `Data` has no null, so absence is the only way to say "unknown" in one.
        workers = installTestWorkManager(app, Data.EMPTY)
        joined(Uri.fromFile(first), Uri.parse("content://test/two.mp4"))
        val unknown = workers.enqueued.single()
        assertFalse(
            "a total that could not be worked out must not be enqueued as a number",
            unknown.hasKeyWithValueOfType<Long>(ConcatWorker.KEY_TOTAL_BYTES),
        )
    }

    @Test
    fun `a total is only as good as its least-known part`() {
        // The rule the join side needed that the convert side did not. `sumOf` over a list with an
        // unknown in it produces a number, and a number that is short by one whole file is worse
        // than no number: the space check cannot tell it from a real total, so it would reserve
        // for half the job and pass.
        assertEquals(3_333L, InputQuery.total(listOf(1_111L, 2_222L)))
        assertNull(InputQuery.total(listOf(1_111L, null)))
        assertNull(InputQuery.total(listOf(null, 2_222L)))
        // A join of nothing has a known total of nothing. Both workers refuse fewer than two
        // inputs long before this, so it is a statement about the fold rather than a real case.
        assertEquals(0L, InputQuery.total(emptyList()))
    }

    /** Drives a real [JoinViewModel] from a pick to an enqueued join. */
    private fun joined(vararg uris: Uri) {
        val viewModel = JoinViewModel(app)
        viewModel.onInputsPicked(uris.toList())
        awaitState(viewModel.state, "Ready") { it is JoinState.Ready }
        viewModel.join()
        awaitState(viewModel.state, "past Joining") { it !is JoinState.Ready }
    }

    private fun pickedInto(uri: Uri): ConversionState.Ready {
        val viewModel = ConversionViewModel(app)
        viewModel.onInputPicked(uri)
        return awaitState(viewModel.state, "Ready") { it is ConversionState.Ready } as ConversionState.Ready
    }

    private fun fileOfSize(bytes: Int, name: String): File =
        File(app.cacheDir, name).apply { writeBytes(ByteArray(bytes)) }

    private companion object {
        const val INPUT_BYTES = 4_321
        const val FIRST_JOIN_BYTES = 1_111
        const val SECOND_JOIN_BYTES = 2_222

        /** A `getLong` default no real total could be mistaken for. */
        const val MISSING = -1L
    }
}
