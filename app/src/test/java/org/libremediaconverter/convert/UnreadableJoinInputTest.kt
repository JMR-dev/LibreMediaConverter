package org.libremediaconverter.convert

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.ConcatPlanner
import org.libremediaconverter.model.ConcatStrategy
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A clip in a join that nothing could read, from the probe all the way to the strategy.
 *
 * Both halves of this are covered already, and separately: `MediaProbeTrackWalkTest` pins what
 * `concatInputFrom` makes of a track list, and `ConcatPlannerTest`'s
 * `an unknown codec is not treated as a match` pins what the planner does with a hand-built
 * `ConcatInput(video = null)`. **Nothing spanned the two**, and the span is the load-bearing part:
 * the planner's safety rests on the probe really producing that shape, and the hand-built fixture
 * would go on passing if it stopped.
 *
 * Measured rather than asserted: mutating `concatInputFrom`'s initial `video` to a non-null
 * placeholder leaves `ConcatPlannerTest` green and turns this red.
 *
 * ## The asymmetry this protects
 *
 * `ConcatPlanner` guards its video check against a null codec (`ConcatStrategy.kt:51`) and its
 * audio check not at all (`:54`). **That is correct, not an oversight.** `MediaProbe.shortName`
 * returns a non-null `String`, so in `concatInputFrom` a null `audioCodec` means the track is
 * *absent* — and two clips with no audio genuinely do match. A null `videoCodec` carries both
 * meanings, absent or unreadable, which is why only that one is guarded.
 *
 * So the audio check is safe *because* the video guard fires first on a clip nothing could read.
 * Nothing wrote that coupling down and nothing held it.
 *
 * ## What this deliberately does not cover
 *
 * `probeForConcat`'s `catch` arm (`MediaProbe.kt:300-302`). It is **not reachable on the JVM**:
 * Robolectric's `MediaExtractor` never throws from `setDataSource`, measured across an
 * unregistered `content://` authority, a missing `file://`, a file of garbage bytes and an `http://`
 * URL — all four returned normally with `trackCount = 0`. So the failure arrives here as an empty
 * track list rather than as an exception, which reaches the same `ConcatInput(null, null, 0, 0, 0)`
 * by the other road. The catch stays device-only, and this file does not pretend otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class UnreadableJoinInputTest {

    @Test
    fun `a clip nothing could read probes as unknown, and an unknown clip is re-encoded`() {
        val unreadable = MediaProbe.probeForConcat(RuntimeEnvironment.getApplication(), UNREADABLE)

        assertNull("an unreadable clip proves nothing about its video codec", unreadable.videoCodec)
        assertNull("nor about its audio codec", unreadable.audioCodec)
        assertEquals("nor about its dimensions", 0, unreadable.width)
        assertEquals(0, unreadable.height)
        assertEquals(0, unreadable.frameRate)

        assertEquals(
            "a clip nothing could read is not evidence of a match with anything",
            ConcatStrategy.REENCODE,
            ConcatPlanner.plan(listOf(unreadable, unreadable)),
        )
    }

    private companion object {
        /** `content://` so the probe takes the SAF branch a real pick takes. Nothing answers it. */
        val UNREADABLE: Uri = Uri.parse("content://test/vanished.mp4")
    }
}
