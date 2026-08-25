package org.libremediaconverter.convert

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Reading Int track properties out of a `MediaFormat`, which is a heterogeneous map.
 *
 * [MediaProbe.intOr] guards two different failures with one expression, and only one of them is
 * obvious. A key the format does not carry is the easy half. The other is a key it *does* carry
 * with a value of another type: `getInteger` casts rather than coerces, so a frame rate stored as
 * a Float answers with a `ClassCastException`. `probeForConcat` reads `KEY_FRAME_RATE`, which the
 * platform accepts either way, and its `catch` sits outside the track loop — so without the
 * `runCatching` one oddly-typed field would discard the codec and dimensions already read from
 * that file and the join would re-encode for no reason.
 *
 * Robolectric rather than a plain JVM test, unlike the two sibling `MediaProbe` helper tests: this
 * one needs a real `MediaFormat` instance, not just its compile-time String constants.
 */
@RunWith(RobolectricTestRunner::class)
class MediaProbeTrackFieldsTest {

    @Test
    fun `a property the format carries as an Int is read`() {
        val format = videoFormat()

        assertEquals(1920, with(MediaProbe) { format.intOr(MediaFormat.KEY_WIDTH) })
        assertEquals(1080, with(MediaProbe) { format.intOr(MediaFormat.KEY_HEIGHT) })
    }

    /**
     * A track that simply does not say. `MediaExtractor` omits `KEY_FRAME_RATE` for plenty of real
     * files, and 0 is what `ConcatPlanner` reads as "cannot prove a match".
     */
    @Test
    fun `a key the format does not carry gives the fallback`() {
        val format = videoFormat()

        assertEquals(0, with(MediaProbe) { format.intOr(MediaFormat.KEY_FRAME_RATE) })
        assertEquals(-1, with(MediaProbe) { format.intOr(MediaFormat.KEY_FRAME_RATE, -1) })
    }

    /**
     * The premise of the `runCatching`, pinned against the platform rather than assumed.
     *
     * If `getInteger` coerced a Float instead of throwing, the guard below would be testing
     * nothing at all — so the throw is asserted directly first.
     */
    @Test
    fun `getInteger refuses a Float rather than coercing it`() {
        val format = videoFormat()
        format.setFloat(MediaFormat.KEY_FRAME_RATE, NON_INTEGRAL_FRAME_RATE)

        val thrown = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.exceptionOrNull()

        assertTrue("expected getInteger to refuse a Float, got $thrown", thrown is ClassCastException)
    }

    /** And that refusal is answered with the fallback, not passed on to the caller. */
    @Test
    fun `a frame rate the format carries as a Float gives the fallback rather than throwing`() {
        val format = videoFormat()
        format.setFloat(MediaFormat.KEY_FRAME_RATE, NON_INTEGRAL_FRAME_RATE)

        assertEquals(0, with(MediaProbe) { format.intOr(MediaFormat.KEY_FRAME_RATE) })
        assertEquals(-1, with(MediaProbe) { format.intOr(MediaFormat.KEY_FRAME_RATE, -1) })
    }

    private fun videoFormat(): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)

    private companion object {
        /** NTSC's 30000/1001, the frame rate that cannot be stored as an Int in the first place. */
        const val NON_INTEGRAL_FRAME_RATE = 29.97f
    }
}
