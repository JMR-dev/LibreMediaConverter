package org.libremediaconverter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream-copy decision for a single file.
 *
 * Deliberately mirrors [ConcatPlannerTest]'s shape, because it enforces the same rule for the same
 * reason: an unproven match must never become a stream copy. A needless re-encode costs time; a
 * wrong copy costs the user a file that will not play, and they may not notice until the source is
 * gone.
 */
class CopyPlannerTest {

    private val mp4H264 = InputProbe(
        videoCodec = "h264",
        audioCodec = "aac",
        container = Container.MP4,
    )

    // --- explicit copy ------------------------------------------------------

    @Test
    fun `asking to copy a known codec the container accepts copies it`() {
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY),
            mp4H264,
        )
        assertEquals(VideoPlan.Copy, plan.video)
        assertEquals(AudioPlan.Copy, plan.audio)
        assertTrue("this is a pure container change", plan.isPureRemux)
    }

    @Test
    fun `an unparseable source is never copied`() {
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            InputProbe(videoCodec = InputProbe.UNPARSEABLE, audioCodec = null),
        )
        assertTrue("must not copy an unidentified stream", plan.video !is VideoPlan.Copy)
        assertTrue(plan.audio !is AudioPlan.Copy)
    }

    /** Two unknowns are not evidence of agreement — the rule ConcatPlanner already states. */
    @Test
    fun `an unrecognised codec name falls back to re-encoding`() {
        val exotic = InputProbe(videoCodec = "cinepak", audioCodec = "qdm2", container = Container.MOV)
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
            exotic,
        )
        assertTrue(plan.video is VideoPlan.Encode)
        assertTrue(plan.audio is AudioPlan.Encode)
    }

    @Test
    fun `copying into a container that cannot hold the codec re-encodes instead`() {
        // H.264 cannot go in WebM. Asking to copy anyway must not produce a broken file.
        val plan = CopyPlanner.plan(
            OutputSpec(Container.WEBM, VideoCodec.COPY, AudioCodec.NONE),
            mp4H264,
        )
        assertEquals(VideoPlan.Encode(VideoCodec.VP9), plan.video)
    }

    // --- the auto-upgrade rule ----------------------------------------------

    @Test
    fun `a matching codec with a changing container is upgraded to a copy`() {
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MKV, VideoCodec.H264, AudioCodec.AAC),
            mp4H264,
        )
        assertEquals("MP4 h264 -> MKV h264 is a remux", VideoPlan.Copy, plan.video)
        assertEquals(AudioPlan.Copy, plan.audio)
    }

    /**
     * The case that stops auto-upgrade from breaking compression.
     *
     * Same container, same codec means the only reason to run the job is to re-encode it — almost
     * always to make it smaller. Copying would hand back a byte-identical file and call it done.
     */
    @Test
    fun `a matching codec in the same container still re-encodes`() {
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MP4, VideoCodec.H264, AudioCodec.AAC),
            mp4H264,
        )
        assertEquals(VideoPlan.Encode(VideoCodec.H264), plan.video)
        assertEquals(AudioPlan.Encode(AudioCodec.AAC), plan.audio)
    }

    @Test
    fun `an unknown source container never triggers auto-upgrade`() {
        // Without a source container there is no way to know the container is changing, so the
        // conservative answer is to encode.
        val noContainer = InputProbe(videoCodec = "h264", audioCodec = "aac", container = null)
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MKV, VideoCodec.H264, AudioCodec.AAC),
            noContainer,
        )
        assertEquals(VideoPlan.Encode(VideoCodec.H264), plan.video)
    }

    @Test
    fun `a different codec is always re-encoded`() {
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MKV, VideoCodec.H265, AudioCodec.OPUS),
            mp4H264,
        )
        assertEquals(VideoPlan.Encode(VideoCodec.H265), plan.video)
        assertEquals(AudioPlan.Encode(AudioCodec.OPUS), plan.audio)
    }

    // --- track removal ------------------------------------------------------

    @Test
    fun `audio-only output drops the video track`() {
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.AAC),
            mp4H264,
        )
        assertEquals(VideoPlan.Drop, plan.video)
        assertTrue(plan.audio is AudioPlan.Encode)
        assertTrue("dropping plus encoding is not a remux", !plan.isPureRemux)
    }

    @Test
    fun `a source with no video track drops video even when video was requested`() {
        val audioOnly = InputProbe(
            videoCodec = null,
            audioCodec = "aac",
            hasVideo = false,
            container = Container.MP4,
            kind = InputKind.AUDIO_ONLY,
        )
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MKV, VideoCodec.H264, AudioCodec.COPY),
            audioOnly,
        )
        assertEquals(VideoPlan.Drop, plan.video)
        assertEquals(AudioPlan.Copy, plan.audio)
        assertTrue("copying the only track is still a remux", plan.isPureRemux)
    }

    /**
     * The one plan `Media3Engine` cannot be handed.
     *
     * `EditedMediaItem.Builder` refuses a composition with both tracks removed —
     * checkState("Audio and video cannot both be removed") — and this is how an ordinary-looking
     * spec reaches it: a video codec named for a file that has no video, with the audio switched
     * off. Neither half is unusual on its own, which is why validation could read the spec, see a
     * video codec, and call it fine.
     */
    @Test
    fun `an audio-only source with the audio dropped removes both tracks`() {
        val audioOnly = InputProbe(
            videoCodec = null,
            audioCodec = "mp3",
            hasVideo = false,
            container = Container.MP3,
            kind = InputKind.AUDIO_ONLY,
        )
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MP4, VideoCodec.H265, AudioCodec.NONE),
            audioOnly,
        )
        assertEquals(VideoPlan.Drop, plan.video)
        assertEquals(AudioPlan.Drop, plan.audio)
        assertTrue("an empty plan is not a remux", !plan.isPureRemux)
    }

    @Test
    fun `copying one track and encoding the other is not a pure remux`() {
        val plan = CopyPlanner.plan(
            OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.OPUS),
            mp4H264,
        )
        assertEquals(VideoPlan.Copy, plan.video)
        assertEquals(AudioPlan.Encode(AudioCodec.OPUS), plan.audio)
        assertTrue(!plan.isPureRemux)
    }
}
