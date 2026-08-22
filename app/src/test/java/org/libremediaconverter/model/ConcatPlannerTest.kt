package org.libremediaconverter.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stream-copy-vs-re-encode decision.
 *
 * This matters more than it looks: the `concat` demuxer does not reject mismatched
 * inputs loudly, it can emit a file whose later segments are garbled. So the default
 * on any doubt has to be re-encoding.
 */
class ConcatPlannerTest {

    private fun clip(
        video: String? = "h264",
        audio: String? = "aac",
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 30,
    ) = ConcatInput(video, audio, width, height, fps)

    @Test
    fun `identical clips can be stream copied`() {
        assertEquals(
            ConcatStrategy.STREAM_COPY,
            ConcatPlanner.plan(listOf(clip(), clip())),
        )
    }

    @Test
    fun `a single input needs no re-encode`() {
        assertEquals(ConcatStrategy.STREAM_COPY, ConcatPlanner.plan(listOf(clip())))
    }

    @Test
    fun `different video codecs force a re-encode`() {
        assertEquals(
            ConcatStrategy.REENCODE,
            ConcatPlanner.plan(listOf(clip(video = "h264"), clip(video = "hevc"))),
        )
    }

    @Test
    fun `different audio codecs force a re-encode`() {
        assertEquals(
            ConcatStrategy.REENCODE,
            ConcatPlanner.plan(listOf(clip(audio = "aac"), clip(audio = "opus"))),
        )
    }

    @Test
    fun `different resolutions force a re-encode`() {
        assertEquals(
            ConcatStrategy.REENCODE,
            ConcatPlanner.plan(listOf(clip(width = 1920, height = 1080), clip(width = 1280, height = 720))),
        )
    }

    @Test
    fun `different frame rates force a re-encode`() {
        assertEquals(
            ConcatStrategy.REENCODE,
            ConcatPlanner.plan(listOf(clip(fps = 30), clip(fps = 60))),
        )
    }

    @Test
    fun `an unknown codec is not treated as a match`() {
        // Two nulls are not evidence of agreement. Assuming they match is exactly how
        // a silent corrupt concat happens.
        assertEquals(
            ConcatStrategy.REENCODE,
            ConcatPlanner.plan(listOf(clip(video = null), clip(video = null))),
        )
    }

    @Test
    fun `a mismatch anywhere in a longer list is caught`() {
        val clips = listOf(clip(), clip(), clip(), clip(width = 640, height = 480), clip())
        assertEquals(ConcatStrategy.REENCODE, ConcatPlanner.plan(clips))
    }
}
