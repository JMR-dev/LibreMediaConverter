package org.libremediaconverter.convert

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The two sums the space check is made of, at the sizes where addition stops working.
 *
 * `SpaceCheckTest` pins which *question* each worker asks; this pins what the answer is once the
 * number is large. Both halves were live on main: `hasSpaceFor` added the headroom to the request
 * before comparing, and [InputQuery.total] folded a join's inputs with nothing stopping the sum
 * from wrapping. A wrapped total is not merely nonsense — it is negative, and every free-space
 * measurement beats a negative number, so the check that exists to refuse impossible jobs approved
 * the most impossible one it can be handed.
 *
 * Nothing here is about the *allocatable-versus-usable* question, which is a separate decision
 * still parked. This is the arithmetic on whichever number that decision ends up producing.
 */
@RunWith(RobolectricTestRunner::class)
class SpaceArithmeticTest {

    private lateinit var context: Context
    private lateinit var publisher: OutputPublisher

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        publisher = OutputPublisher(context)
    }

    @Test
    fun `a request no disk could hold is refused rather than wrapping into plenty of room`() {
        assertFalse("eight exabytes do not fit anywhere", publisher.hasSpaceFor(Long.MAX_VALUE))
        // Just inside the headroom of the maximum, which is the arithmetic's actual edge: this is
        // the range where `bytes + headroom` goes negative while `bytes` alone still looks huge.
        assertFalse(publisher.hasSpaceFor(Long.MAX_VALUE - ONE_HUNDRED_MIB))
    }

    // The clamp on a negative size is deliberately NOT asserted here. It only changes the answer
    // when free space is below the headroom, which this test cannot arrange -- the publisher reads
    // the host's real cache volume -- so any assertion available would pass against the unclamped
    // arithmetic too, and a test that cannot fail is worse than the gap it appears to close.

    @Test
    fun `an ordinary request is still allowed, so the refusals above are not vacuous`() {
        val free = File(context.cacheDir, "conversions").usableSpace
        assertTrue(
            "a one-byte conversion must fit; the volume under the cache reports $free bytes free",
            publisher.hasSpaceFor(1L),
        )
    }

    @Test
    fun `a join total too large to represent saturates instead of turning negative`() {
        val enormous = listOf(FOUR_EXABYTES, FOUR_EXABYTES, FOUR_EXABYTES)

        val total = InputQuery.total(enormous)

        assertEquals(Long.MAX_VALUE, total)
        // The whole point, in the shape the defect had: this total is handed straight to the space
        // check by ConcatWorker, and before the clamp it arrived negative and was approved.
        assertFalse("a join of three four-exabyte files does not fit", publisher.hasSpaceFor(total!!))
    }

    private companion object {
        const val ONE_HUNDRED_MIB = 100L * 1024 * 1024

        /** Big enough that three of them overflow, small enough to be a plausible `statSize`. */
        const val FOUR_EXABYTES = 4_000_000_000_000_000_000L
    }
}
