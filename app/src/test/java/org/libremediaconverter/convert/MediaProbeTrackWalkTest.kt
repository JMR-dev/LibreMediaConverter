package org.libremediaconverter.convert

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rules `MediaProbe` applies to a set of track formats.
 *
 * ## Why this exists, and what it revises
 *
 * Issue #84 classified `probeWithExtractor` and `probeForConcat` as device-bound and explicitly not
 * a gap:
 *
 * > These are exercised by `RemuxTest`, `ConcatEngineTest` and `RealMediaBenchmark` in
 * > `androidTest` … **Do not read their 0% as untested.**
 *
 * That was right about the measurement boundary and right about FFprobe. It was not right that
 * these are only orchestration. The track walk is a **branch matrix**, and `androidTest` reaches it
 * only through whatever the committed fixtures happen to contain — so none of the rules below is
 * *chosen* by any test there. A fixture with two video tracks, a track that omits its duration, or
 * an audio-before-video ordering is not something a device test would produce on purpose.
 *
 * The seam is the answer #133 preferred over driving `ShadowMediaExtractor`: the walk is a pure
 * function over `List<MediaFormat>`, and what is left needing a device — `setDataSource`,
 * `getTrackFormat`, `release` — is the thin edge `androidTest` should be covering. This is the
 * `work/FailureOutcome.kt` pattern `CLAUDE.md` names.
 *
 * `MediaFormat` is a real one throughout, not a stub. `MediaProbeTrackFieldsTest` records why that
 * matters: it is a heterogeneous map whose getters throw rather than coerce, and a hand-rolled
 * double would not reproduce that.
 */
@RunWith(RobolectricTestRunner::class)
class MediaProbeTrackWalkTest {

    // --- extractedFrom: the conversion flow's read ---------------------------

    @Test
    fun `the first video track wins when a file carries two`() {
        // `video == null` is the entire guard. A file with two video tracks must report the first,
        // because that is the one an engine will transcode -- and the width and height must come
        // from the same track, not be mixed across them.
        val extracted = MediaProbe.extractedFrom(
            listOf(
                video(MediaFormat.MIMETYPE_VIDEO_AVC, width = 1920, height = 1080),
                video(MediaFormat.MIMETYPE_VIDEO_HEVC, width = 640, height = 480),
            ),
        )

        assertEquals("h264", extracted.videoCodec)
        assertEquals(1920, extracted.width)
        assertEquals(1080, extracted.height)
    }

    @Test
    fun `the first audio track wins when a file carries two`() {
        val extracted = MediaProbe.extractedFrom(
            listOf(
                audio(MediaFormat.MIMETYPE_AUDIO_AAC),
                audio(MediaFormat.MIMETYPE_AUDIO_OPUS),
            ),
        )

        assertEquals("aac", extracted.audioCodec)
    }

    @Test
    fun `duration is the longest track, not the first or the last`() {
        // A file whose audio outlasts its video is ordinary. Taking the video's length would cut
        // the progress bar short; taking the last track's would be right only by accident of order.
        val extracted = MediaProbe.extractedFrom(
            listOf(
                video(MediaFormat.MIMETYPE_VIDEO_AVC, durationUs = 10_000_000),
                audio(MediaFormat.MIMETYPE_AUDIO_AAC, durationUs = 12_500_000),
                audio(MediaFormat.MIMETYPE_AUDIO_OPUS, durationUs = 1_000_000),
            ),
        )

        assertEquals(12_500L, extracted.durationMs)
    }

    @Test
    fun `a track that does not declare its duration contributes nothing to it`() {
        // MediaExtractor omits KEY_DURATION for plenty of real tracks -- MediaProbeTrackFieldsTest
        // records the same for KEY_FRAME_RATE. Reading a key that is absent is what containsKey
        // stands between us and.
        val extracted = MediaProbe.extractedFrom(
            listOf(
                video(MediaFormat.MIMETYPE_VIDEO_AVC),
                audio(MediaFormat.MIMETYPE_AUDIO_AAC, durationUs = 7_000_000),
            ),
        )

        assertEquals(7_000L, extracted.durationMs)
    }

    @Test
    fun `declaring audio before video changes nothing`() {
        // Track order is a property of the container, not of the content. Both orderings have to
        // reach the same answer or the same file remuxed twice would probe differently.
        val videoFirst = MediaProbe.extractedFrom(
            listOf(
                video(MediaFormat.MIMETYPE_VIDEO_AVC, width = 1280, height = 720),
                audio(MediaFormat.MIMETYPE_AUDIO_AAC),
            ),
        )
        val audioFirst = MediaProbe.extractedFrom(
            listOf(
                audio(MediaFormat.MIMETYPE_AUDIO_AAC),
                video(MediaFormat.MIMETYPE_VIDEO_AVC, width = 1280, height = 720),
            ),
        )

        assertEquals(videoFirst.videoCodec, audioFirst.videoCodec)
        assertEquals(videoFirst.audioCodec, audioFirst.audioCodec)
        assertEquals(videoFirst.width, audioFirst.width)
        assertEquals(videoFirst.height, audioFirst.height)
    }

    @Test
    fun `a track that is neither audio nor video is ignored`() {
        // Subtitle and timed-metadata tracks are common in MKV and MP4. Neither prefix matches, so
        // neither slot is filled -- and, importantly, a subtitle track must not be mistaken for the
        // absence of an audio track by some later `else`.
        val extracted = MediaProbe.extractedFrom(
            listOf(
                MediaFormat().apply { setString(MediaFormat.KEY_MIME, "text/vtt") },
                video(MediaFormat.MIMETYPE_VIDEO_AVC),
            ),
        )

        assertEquals("h264", extracted.videoCodec)
        assertNull(extracted.audioCodec)
    }

    @Test
    fun `a file with no tracks reports nothing rather than zero-width video`() {
        val extracted = MediaProbe.extractedFrom(emptyList())

        assertNull(extracted.videoCodec)
        assertNull(extracted.audioCodec)
        assertEquals(0L, extracted.durationMs)
        assertEquals(0, extracted.width)
        assertEquals(0, extracted.height)
    }

    @Test
    fun `an audio-only file reports no video codec at all`() {
        // The distinction MediaProbe.classify turns into InputKind.AUDIO_ONLY, and the reason
        // `hasVideo` exists: an audio file and a corrupt file must not look alike.
        val extracted = MediaProbe.extractedFrom(listOf(audio(MediaFormat.MIMETYPE_AUDIO_AAC)))

        assertNull(extracted.videoCodec)
        assertEquals("aac", extracted.audioCodec)
        assertEquals(0, extracted.width)
    }

    // --- concatInputFrom: the join flow's read -------------------------------

    @Test
    fun `the join read takes frame rate from the first video track`() {
        val input = MediaProbe.concatInputFrom(
            listOf(
                video(MediaFormat.MIMETYPE_VIDEO_AVC, width = 1920, height = 1080, frameRate = 30),
                video(MediaFormat.MIMETYPE_VIDEO_HEVC, width = 640, height = 480, frameRate = 60),
                audio(MediaFormat.MIMETYPE_AUDIO_AAC),
            ),
        )

        assertEquals("h264", input.videoCodec)
        assertEquals("aac", input.audioCodec)
        assertEquals(1920, input.width)
        assertEquals(1080, input.height)
        assertEquals(30, input.frameRate)
    }

    @Test
    fun `a video track with no declared frame rate reports zero rather than guessing`() {
        // ConcatPlanner treats 0 as "cannot prove a match" and re-encodes. A guessed 30 would read
        // as agreement and produce a stream copy of clips that do not actually match -- the failure
        // its KDoc says the whole flow is arranged to avoid.
        val input = MediaProbe.concatInputFrom(listOf(video(MediaFormat.MIMETYPE_VIDEO_AVC)))

        assertEquals(0, input.frameRate)
    }

    @Test
    fun `a file with no tracks joins as entirely unknown`() {
        val input = MediaProbe.concatInputFrom(emptyList())

        assertNull(input.videoCodec)
        assertNull(input.audioCodec)
        assertEquals(0, input.width)
        assertEquals(0, input.height)
        assertEquals(0, input.frameRate)
    }

    private fun video(
        mime: String,
        width: Int = 1920,
        height: Int = 1080,
        durationUs: Long? = null,
        frameRate: Int? = null,
    ): MediaFormat = MediaFormat.createVideoFormat(mime, width, height).apply {
        durationUs?.let { setLong(MediaFormat.KEY_DURATION, it) }
        frameRate?.let { setInteger(MediaFormat.KEY_FRAME_RATE, it) }
    }

    private fun audio(mime: String, durationUs: Long? = null): MediaFormat =
        MediaFormat.createAudioFormat(mime, SAMPLE_RATE, CHANNELS).apply {
            durationUs?.let { setLong(MediaFormat.KEY_DURATION, it) }
        }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
    }
}
