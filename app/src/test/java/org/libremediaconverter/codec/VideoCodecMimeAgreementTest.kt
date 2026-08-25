package org.libremediaconverter.codec

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.libremediaconverter.convert.Media3Engine
import org.libremediaconverter.model.VideoCodec

/**
 * Bites on #86: a fifth `VideoCodec -> MIME` table, and nothing checking it agrees with the fourth.
 *
 * [AndroidDeviceCodecs.mimeFor] and [Media3Engine.videoMimeTypeFor] take the same enum and return a
 * MIME string, from opposite ends of one export. The first asks the device *"have you an encoder
 * for this?"*; the second tells Transformer *"produce this."* If they name different MIME types for
 * the same codec, the app checks for one encoder and then requests another — the check passes, the
 * export succeeds, and the user's H.265 file contains H.264. Both were `private` until #85 and #87
 * widened them, so this assertion could not be written before; each table had per-arm tests that
 * pinned its own answers and could not see the other side.
 *
 * **They do not agree everywhere, and must not be forced to.** Three buckets, all pinned below:
 *
 * - **H.264 and H.265** — both tables name a MIME, and it has to be the same one. This is the
 *   bucket the defect lives in.
 * - **VP8, VP9 and AV1** — the device table names a real MIME, Transformer's returns null. That is
 *   correct, not drift: `Transformer.setVideoMimeType` will not accept them, so the router sends
 *   them to FFmpeg before Media3 is asked anything, while a device may still genuinely own a VP9
 *   encoder and `canEncode` has to give a truthful answer about it. Flattening `mimeFor` to null
 *   here to "make the tables agree" would make `canEncode(VP9)` answer true on hardware that has
 *   no VP9 encoder. The routing half of that claim is proved in
 *   `Media3EngineMimeTypesTest.the router sends exactly H264 and H265 video encodes to Media3`,
 *   which drives the real router; it is not repeated here.
 * - **COPY and NONE** — neither names a MIME, because neither is encoded at all.
 *
 * The fourth bucket is asserted empty: a codec Transformer names and the device check cannot ask
 * about would mean `canEncode` waving through a target the app then really does encode.
 *
 * **Audio has no partner, and that is a gap rather than a decision.** [Media3Engine.audioMimeTypeFor]
 * is the same shape one enum over — `AudioCodec -> MIME` — but [AndroidDeviceCodecs] enumerates
 * `video/` MIME types only, so there is no device-side audio table to cross-check it against. An
 * audio encoder this device lacks is therefore not caught up front the way a video one is; the job
 * reaches Media3 and falls back after failing. Named here so the asymmetry reads as unfinished
 * rather than intended.
 */
@UnstableApi
class VideoCodecMimeAgreementTest {

    /** Both tables name a MIME. The pair has to match; this is the whole point of the file. */
    private val bothNameAMime = setOf(VideoCodec.H264, VideoCodec.H265)

    /** Only the device table names one, because Transformer is never asked for these. */
    private val deviceOnly = setOf(VideoCodec.VP8, VideoCodec.VP9, VideoCodec.AV1)

    /** Neither names one: nothing is encoded, so there is no encoder to name. */
    private val neitherNamesOne = setOf(VideoCodec.COPY, VideoCodec.NONE)

    /**
     * Sorts every [VideoCodec] by what the two tables actually answer, then compares the sorting
     * with the buckets documented above.
     *
     * This is what makes the agreement test below non-vacuous, and it is deliberately an exact
     * comparison in all four directions. A codec added to the enum lands in some bucket and fails
     * here rather than arriving unclassified. A table that starts returning null for everything —
     * the shape a filtered loop would pass on — empties two buckets and fails here. And a
     * *convergence* fails too: giving `videoMimeTypeFor(VP9)` a real MIME moves VP9 out of
     * `deviceOnly`, which is the point. The divergence should be deliberate and visible, so
     * changing it should require saying so in this file.
     */
    @Test
    fun `each video codec is in the bucket the two tables actually put it in`() {
        assertEquals(
            "codecs both tables name a MIME for",
            bothNameAMime,
            VideoCodec.entries.filter { device(it) != null && transformer(it) != null }.toSet(),
        )
        assertEquals(
            "codecs only the device check names a MIME for, because Transformer will not encode them",
            deviceOnly,
            VideoCodec.entries.filter { device(it) != null && transformer(it) == null }.toSet(),
        )
        assertEquals(
            "codecs neither table names a MIME for, because nothing is encoded",
            neitherNamesOne,
            VideoCodec.entries.filter { device(it) == null && transformer(it) == null }.toSet(),
        )
        assertEquals(
            "codecs Transformer names a MIME for that the device check cannot ask about — canEncode " +
                "would answer true without looking, for a codec Media3 really is told to produce",
            emptySet<VideoCodec>(),
            VideoCodec.entries.filter { device(it) == null && transformer(it) != null }.toSet(),
        )
    }

    /**
     * The cross-check itself.
     *
     * Per-arm tests in either file cannot catch this: each pins its own table's answers, so a pair
     * changed in lockstep with its own expectations stays green on both sides while the two tables
     * describe different codecs.
     */
    @Test
    fun `where both tables name a MIME they name the same one`() {
        bothNameAMime.forEach { codec ->
            val asked = device(codec)
            val requested = transformer(codec)
            assertNotNull("AndroidDeviceCodecs has no MIME to ask the device about for ${codec.label}", asked)
            assertNotNull("Media3Engine has no MIME to give Transformer for ${codec.label}", requested)
            assertEquals(
                "${codec.label}: the device is asked about $asked and Transformer is then told to " +
                    "produce $requested, so the capability check answers about a codec that is not the output",
                asked,
                requested,
            )
        }
    }

    /**
     * The documented divergence, asserted rather than described.
     *
     * Both halves matter. The null side is Media3's refusal; the non-null side is the device
     * check's genuine question, and it is the half a reader "tidying up" the disagreement would
     * delete.
     */
    @Test
    fun `the codecs Transformer will not encode are still codecs this device may or may not have`() {
        deviceOnly.forEach { codec ->
            assertNotNull(
                "${codec.label} goes to FFmpeg, but canEncode still has to answer truthfully about " +
                    "this device's encoder — a null here makes it answer true without looking",
                device(codec),
            )
            assertNull(
                "Transformer rejects ${codec.label}, so naming a MIME for it would request an export " +
                    "Media3 cannot perform",
                transformer(codec),
            )
        }
    }

    /**
     * Guards every comparison above against passing as `null == null`.
     *
     * `MediaFormat`'s MIME types are Java compile-time constants and are inlined, so the unit-test
     * classpath's stubbed `android.jar` never supplies them; `MimeTypes`' come from a real
     * `media3-common` jar. If either stopped holding, the buckets would collapse and this fails
     * first, with the reason. Same guard, and the same reason, as
     * `CodecVocabularyTest.the MIME constants are real strings rather than stubs`.
     */
    @Test
    fun `both tables return real MIME strings rather than stubs`() {
        assertEquals("video/avc", AndroidDeviceCodecs.mimeFor(VideoCodec.H264))
        assertEquals("video/hevc", AndroidDeviceCodecs.mimeFor(VideoCodec.H265))
        assertEquals("video/x-vnd.on2.vp9", AndroidDeviceCodecs.mimeFor(VideoCodec.VP9))
        assertEquals("video/avc", Media3Engine.videoMimeTypeFor(VideoCodec.H264))
        assertEquals("video/hevc", Media3Engine.videoMimeTypeFor(VideoCodec.H265))
    }

    private fun device(codec: VideoCodec): String? = AndroidDeviceCodecs.mimeFor(codec)

    private fun transformer(codec: VideoCodec): String? = Media3Engine.videoMimeTypeFor(codec)
}
