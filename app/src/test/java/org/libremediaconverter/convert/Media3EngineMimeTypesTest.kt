package org.libremediaconverter.convert

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.AudioPlan
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ConversionPlan
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.ConversionRouter
import org.libremediaconverter.model.CopyPlanner
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.Engine
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.model.VideoPlan

/**
 * Guards [Media3Engine]'s two enum-to-MIME tables and the claims written above them.
 *
 * The defect: neither table was exercised at all, so nothing stood between a wrong entry and the
 * user's file. Point `H265` at `VIDEO_H264` and every hardware HEVC export writes H.264 into a
 * file the user asked to be H.265 — Transformer does exactly as told, the export succeeds, and
 * the only symptom is a codec nobody chose.
 *
 * Worse, one arm carried an assertion instead of a value:
 *
 * ```
 * // Never reached: only an Encode plan consults this, and COPY/NONE are not Encode.
 * ```
 *
 * That is a claim about *callers* parked in a branch of a callee. It happens to be true, and
 * nothing whatsoever checked it, so it would have gone on reading as true after it stopped being.
 *
 * Three kinds of test, because arm-by-arm equality alone would only pin today's answers:
 *
 * 1. Every arm of both tables, nulls included.
 * 2. The "never reached" claim, proved over every plan [CopyPlanner] can produce.
 * 3. The tables against [ConversionRouter]'s actual decisions rather than against its codec sets —
 *    the comments claim behaviour ("the router routes them to FFmpeg"), and a set can be right
 *    while the rule that reads it is wrong.
 *
 * A JVM test rather than an instrumented one: both tables take an enum and return a constant.
 */
@UnstableApi
class Media3EngineMimeTypesTest {

    @Test
    fun `every video codec maps to the MIME type Transformer will be given`() {
        assertEquals(
            "EXPECTED_VIDEO_MIME must name every VideoCodec, so a new one cannot arrive untested",
            VideoCodec.entries.toSet(),
            EXPECTED_VIDEO_MIME.keys,
        )
        VideoCodec.entries.forEach { codec ->
            assertEquals(
                "videoMimeTypeFor(${codec.label})",
                EXPECTED_VIDEO_MIME.getValue(codec),
                Media3Engine.videoMimeTypeFor(codec),
            )
        }
    }

    @Test
    fun `every audio codec maps to the MIME type Transformer will be given`() {
        assertEquals(
            "EXPECTED_AUDIO_MIME must name every AudioCodec, so a new one cannot arrive untested",
            AudioCodec.entries.toSet(),
            EXPECTED_AUDIO_MIME.keys,
        )
        AudioCodec.entries.forEach { codec ->
            assertEquals(
                "audioMimeTypeFor(${codec.label})",
                EXPECTED_AUDIO_MIME.getValue(codec),
                Media3Engine.audioMimeTypeFor(codec),
            )
        }
    }

    /**
     * The "never reached" claim, proved rather than repeated.
     *
     * [Media3Engine] asks these tables only for `plan.video as? VideoPlan.Encode`, and every plan
     * it sees comes from [CopyPlanner]. So the claim reduces to a property of the planner: over
     * every spec it can be handed, an `Encode` never carries `COPY` or `NONE`. That holds because
     * both codecs are answered before the `Encode` branch, and the fallback draws from
     * `ContainerCapabilities.encodableVideo`, which contains neither — but this asserts it instead
     * of trusting the reading.
     *
     * The counters are not decoration. `(plan.video as? VideoPlan.Encode)?.let { ... }` asserts
     * nothing at all for a `Drop` or `Copy` plan, so a sweep that stopped producing `Encode` plans
     * would stay green while checking nothing.
     */
    @Test
    fun `no plan CopyPlanner can produce carries COPY or NONE inside an Encode`() {
        var videoEncodes = 0
        var audioEncodes = 0
        everyPlan().forEach { (spec, probe, plan) ->
            (plan.video as? VideoPlan.Encode)?.let {
                videoEncodes++
                assertTrue(
                    "CopyPlanner produced VideoPlan.Encode(${it.codec}) for $spec against $probe",
                    it.codec != VideoCodec.COPY && it.codec != VideoCodec.NONE,
                )
            }
            (plan.audio as? AudioPlan.Encode)?.let {
                audioEncodes++
                assertTrue(
                    "CopyPlanner produced AudioPlan.Encode(${it.codec}) for $spec against $probe",
                    it.codec != AudioCodec.COPY && it.codec != AudioCodec.NONE,
                )
            }
        }
        assertTrue("the sweep produced no video Encode plan, so it asserted nothing", videoEncodes > 0)
        assertTrue("the sweep produced no audio Encode plan, so it asserted nothing", audioEncodes > 0)
    }

    /**
     * The video table's other claim: VP8, VP9 and AV1 targets "never reach here".
     *
     * Asked of the router rather than of its private codec set, so the rule is what is under test.
     */
    @Test
    fun `the router sends exactly H264 and H265 video encodes to Media3`() {
        val onMedia3 = REAL_VIDEO_CODECS.filter { engineForVideoEncode(it) == Engine.MEDIA3 }
        assertEquals(listOf(VideoCodec.H264, VideoCodec.H265), onMedia3)
    }

    /**
     * The audio table's sibling claim, and where it turned out to be incomplete.
     *
     * The comment named MP3 and FLAC. One rule — `audioEncode !in MEDIA3_AUDIO` — diverts Vorbis
     * by exactly the same logic, so three of the six encodable codecs never reach the table, not
     * two. Asserted as the whole set rather than as two memberships, which is what makes the
     * omission visible.
     */
    @Test
    fun `the router keeps MP3 FLAC and Vorbis audio encodes off Media3`() {
        val onMedia3 = REAL_AUDIO_CODECS.filter { engineForAudioEncode(it) == Engine.MEDIA3 }
        assertEquals(listOf(AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.PCM), onMedia3)
    }

    /**
     * The binding that makes the two halves above one test rather than two coincidences.
     *
     * A codec the router starts sending to Media3 must have a MIME type here, or Transformer is
     * left to pick its own and the user gets a codec they did not choose.
     */
    @Test
    fun `every codec the router sends to Media3 has a MIME type`() {
        REAL_VIDEO_CODECS.filter { engineForVideoEncode(it) == Engine.MEDIA3 }.forEach { codec ->
            assertNotNull(
                "${codec.label} is routed to Media3 but videoMimeTypeFor returns null",
                Media3Engine.videoMimeTypeFor(codec),
            )
        }
        REAL_AUDIO_CODECS.filter { engineForAudioEncode(it) == Engine.MEDIA3 }.forEach { codec ->
            assertNotNull(
                "${codec.label} is routed to Media3 but audioMimeTypeFor returns null",
                Media3Engine.audioMimeTypeFor(codec),
            )
        }
    }

    /**
     * The reverse direction, which holds for video and not for audio.
     *
     * Every video codec the router withholds has a null entry, so that table is exactly the set of
     * codecs Media3 is asked to encode. Audio has one entry more than the router will ever use:
     * `VORBIS -> AUDIO_VORBIS` is correct and unreachable. Pinned deliberately — if a routing
     * change makes Vorbis live, this is the test that says the arm above stopped being dead.
     */
    @Test
    fun `Vorbis is the one MIME type the router never asks for`() {
        REAL_VIDEO_CODECS.filter { engineForVideoEncode(it) == Engine.FFMPEG }.forEach { codec ->
            assertEquals(
                "${codec.label} never reaches Media3, so it must not name a MIME type",
                null,
                Media3Engine.videoMimeTypeFor(codec),
            )
        }
        val namedButUnrouted = REAL_AUDIO_CODECS
            .filter { Media3Engine.audioMimeTypeFor(it) != null }
            .filter { engineForAudioEncode(it) == Engine.FFMPEG }
        assertEquals(listOf(AudioCodec.VORBIS), namedButUnrouted)
        assertEquals(MimeTypes.AUDIO_VORBIS, Media3Engine.audioMimeTypeFor(AudioCodec.VORBIS))
    }

    /**
     * Routes a video-only re-encode to [codec] and reports the engine chosen.
     *
     * `mpeg2video` is the load-bearing detail: [CopyPlanner] upgrades a request to a stream copy
     * when the source codec matches, and a `Copy` plan would answer a different question. A name
     * `CodecNames` cannot resolve forces an `Encode` for every codec, which the assertion pins so
     * that a planner change cannot quietly turn this sweep into a sweep of `Copy` plans.
     */
    private fun engineForVideoEncode(codec: VideoCodec): Engine {
        val request = ConversionRequest(
            spec = OutputSpec(Container.MP4, codec, AudioCodec.NONE),
            probe = InputProbe(videoCodec = "mpeg2video", container = Container.MKV),
        )
        assertEquals(
            "this request no longer plans a video Encode, so its engine says nothing about $codec",
            VideoPlan.Encode(codec),
            CopyPlanner.plan(request.spec, request.probe).video,
        )
        return ConversionRouter.route(request, DeviceCodecs.PERMISSIVE).engine
    }

    /** The audio counterpart. `ac3` is unresolvable for the same reason `mpeg2video` is. */
    private fun engineForAudioEncode(codec: AudioCodec): Engine {
        val request = ConversionRequest(
            spec = OutputSpec(Container.MP4, VideoCodec.NONE, codec),
            probe = InputProbe(audioCodec = "ac3", hasVideo = false, container = Container.MKV),
        )
        assertEquals(
            "this request no longer plans an audio Encode, so its engine says nothing about $codec",
            AudioPlan.Encode(codec),
            CopyPlanner.plan(request.spec, request.probe).audio,
        )
        return ConversionRouter.route(request, DeviceCodecs.PERMISSIVE).engine
    }

    private fun everyPlan(): List<Triple<OutputSpec, InputProbe, ConversionPlan>> =
        ALL_SPECS.flatMap { spec -> PROBES.map { Triple(spec, it, CopyPlanner.plan(spec, it)) } }

    private companion object {

        /** Every arm of `videoMimeTypeFor`, including the ones the tests above prove unreachable. */
        val EXPECTED_VIDEO_MIME: Map<VideoCodec, String?> = mapOf(
            VideoCodec.H264 to MimeTypes.VIDEO_H264,
            VideoCodec.H265 to MimeTypes.VIDEO_H265,
            VideoCodec.VP8 to null,
            VideoCodec.VP9 to null,
            VideoCodec.AV1 to null,
            // Unreachable, and asserted anyway: the proof lives in another test, and a reader
            // deleting these would leave the arms themselves unexercised.
            VideoCodec.COPY to null,
            VideoCodec.NONE to null,
        )

        val EXPECTED_AUDIO_MIME: Map<AudioCodec, String?> = mapOf(
            AudioCodec.AAC to MimeTypes.AUDIO_AAC,
            AudioCodec.OPUS to MimeTypes.AUDIO_OPUS,
            AudioCodec.VORBIS to MimeTypes.AUDIO_VORBIS,
            AudioCodec.PCM to MimeTypes.AUDIO_RAW,
            AudioCodec.MP3 to null,
            AudioCodec.FLAC to null,
            AudioCodec.COPY to null,
            AudioCodec.NONE to null,
        )

        /** Codecs a user can actually ask to be produced: `COPY` and `NONE` are instructions. */
        val REAL_VIDEO_CODECS = VideoCodec.entries - VideoCodec.COPY - VideoCodec.NONE
        val REAL_AUDIO_CODECS = AudioCodec.entries - AudioCodec.COPY - AudioCodec.NONE

        /** Every output a spec can name — 15 containers by 7 video codecs by 8 audio codecs. */
        val ALL_SPECS: List<OutputSpec> = Container.entries.flatMap { container ->
            VideoCodec.entries.flatMap { video ->
                AudioCodec.entries.map { audio -> OutputSpec(container, video, audio) }
            }
        }

        /** Inputs chosen to reach each of [CopyPlanner]'s branches. */
        val PROBES = listOf(
            // Nothing known about the source at all.
            InputProbe(),
            // Identified, and the container changes: the copy upgrade applies.
            InputProbe(videoCodec = "h264", audioCodec = "aac", container = Container.MKV),
            // Identified, container unchanged: the copy upgrade deliberately does not apply.
            InputProbe(videoCodec = "h264", audioCodec = "aac", container = Container.MP4),
            // Copyable but not encodable by either engine — the fallback's reason for existing.
            InputProbe(videoCodec = "av1", audioCodec = "flac", container = Container.MKV),
            // Real codecs this app cannot name, so a copy is never proven safe.
            InputProbe(videoCodec = "mpeg2video", audioCodec = "ac3", container = Container.AVI),
            // The platform extractor could not open it.
            InputProbe(videoCodec = InputProbe.UNPARSEABLE),
            // Audio only.
            InputProbe(videoCodec = null, audioCodec = "opus", hasVideo = false, container = Container.OGG),
        )
    }
}
