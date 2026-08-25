package org.libremediaconverter.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libremediaconverter.model.CodecNames
import org.libremediaconverter.model.VideoCodec

/**
 * Bites on #87: two tables read one codec vocabulary and had stopped agreeing.
 *
 * `CodecNames.VIDEO_ALIASES` answers "which enum is this FFprobe name", for the source-info card
 * and for routing. `AndroidDeviceCodecs.NAME_TO_MIME` answers "which MIME do I ask this device
 * about", for the capability check. On `ad28293` five names lived in one and not the other: `x264`,
 * `hev1`, `x265` and `vp09` were identified for display and then fell through the device check as
 * unknown, so the app attempted a hardware path it had enough information to skip; `mpeg4` ran the
 * other way and rendered as a raw name on the card.
 *
 * Per-table arm tests would have passed on both tables and encoded the disagreement, which is why
 * these walk the key sets instead. A name added to — or removed from — one side alone fails here.
 */
class CodecVocabularyTest {

    private val aliases = CodecNames.VIDEO_ALIASES
    private val mimes = AndroidDeviceCodecs.NAME_TO_MIME
    private val decodeOnly = AndroidDeviceCodecs.DECODE_ONLY_NAMES

    @Test
    fun `no video codec name resolves for display without also resolving for the device check`() {
        assertEquals(
            "resolve in CodecNames but return null from mimeForCodecName, so the device check runs blind",
            emptySet<String>(),
            aliases.keys - mimes.keys,
        )
    }

    @Test
    fun `no video codec name resolves for the device check without being a name the app can label`() {
        assertEquals(
            "resolve in AndroidDeviceCodecs but not in CodecNames, and are not listed as decode-only",
            emptySet<String>(),
            mimes.keys - aliases.keys - decodeOnly,
        )
    }

    /**
     * Membership is not enough: `"x265" to MIMETYPE_VIDEO_AVC` would satisfy both key sets and
     * still ask the device about the wrong codec.
     */
    @Test
    fun `the two tables agree on what each name means, not merely that they know it`() {
        aliases.forEach { (name, codec) ->
            val expected = AndroidDeviceCodecs.mimeFor(codec)
            assertNotNull("$name maps to $codec, which has no MIME to ask about", expected)
            assertEquals("$name is $codec in CodecNames", expected, mimes[name])
        }
    }

    /**
     * The exception list is the escape hatch: any future divergence could be waved through by
     * adding the name to it. Guard both directions so it cannot be.
     */
    @Test
    fun `the decode-only names are genuinely decode-only`() {
        decodeOnly.forEach { name ->
            assertNotNull("$name is listed as decode-only but the device check cannot resolve it", mimes[name])
            assertNull(
                "$name is listed as decode-only, but CodecNames does resolve it — that is a divergence " +
                    "being waved through rather than a documented exception",
                CodecNames.videoFromName(name),
            )
        }
    }

    /**
     * The five names #87 measured, pinned by name so the specific regression cannot come back
     * quietly even if someone rewrites the tables above.
     */
    @Test
    fun `the names that used to resolve on one side only resolve on both`() {
        mapOf(
            "x264" to VideoCodec.H264,
            "hev1" to VideoCodec.H265,
            "x265" to VideoCodec.H265,
            "vp09" to VideoCodec.VP9,
        ).forEach { (name, codec) ->
            assertEquals("$name is a name FFmpeg emits", codec, CodecNames.videoFromName(name))
            assertEquals(
                "$name has to reach the device check too, or the app identifies it and then asks blind",
                AndroidDeviceCodecs.mimeFor(codec),
                AndroidDeviceCodecs.mimeForCodecName(name),
            )
        }
        // The one that runs the other way: decodable input with no enum to name it.
        assertNull("mpeg4 is not an output the app can target", CodecNames.videoFromName("mpeg4"))
        assertNotNull("mpeg4 is still decodable input", AndroidDeviceCodecs.mimeForCodecName("mpeg4"))
    }

    /**
     * Without this the agreement test above could pass on two nulls.
     *
     * `MediaFormat.MIMETYPE_VIDEO_AVC` is a Java compile-time constant, so it is inlined and the
     * unit-test classpath's stubbed `android.jar` never has to supply it. If that ever stops being
     * true, every MIME comparison here would be `null == null` and green — the vacuous-mutation
     * failure this repo has counted before. Assert one literal so the stub fails loudly instead.
     */
    @Test
    fun `the MIME constants are real strings rather than stubs`() {
        assertEquals("video/avc", AndroidDeviceCodecs.mimeForCodecName("h264"))
        assertEquals("video/hevc", AndroidDeviceCodecs.mimeForCodecName("hevc"))
        assertEquals("video/avc", AndroidDeviceCodecs.mimeFor(VideoCodec.H264))
    }

    @Test
    fun `codec names are matched case-insensitively on both sides`() {
        assertEquals(VideoCodec.H265, CodecNames.videoFromName("HEV1"))
        assertEquals("video/hevc", AndroidDeviceCodecs.mimeForCodecName("HEV1"))
    }

    @Test
    fun `a name neither table knows still resolves to nothing`() {
        assertNull(CodecNames.videoFromName("cinepak"))
        assertNull(AndroidDeviceCodecs.mimeForCodecName("cinepak"))
    }

    /**
     * The behaviour #87 actually changes, at the seam that uses it.
     *
     * `canDecode` treats an unresolved name as "assume the platform copes". Before the alias
     * landed, a device with no HEVC decoder answered true for `x265` and Media3 was handed a job it
     * could not do; now the router sends it to FFmpeg without spending the attempt.
     */
    @Test
    fun `a device without the decoder now says so for the aliases it used to wave through`() {
        val hevcOnly = AndroidDeviceCodecs.forTesting(encoders = emptySet(), decoders = setOf("video/hevc"))
        assertTrue("x265 is HEVC by another name", hevcOnly.canDecode("x265"))
        assertFalse("this device has no AVC decoder, and x264 is AVC", hevcOnly.canDecode("x264"))
        assertTrue("a name nobody knows keeps the permissive answer", hevcOnly.canDecode("cinepak"))
    }

    /**
     * The other half of the null policy, at the seam it exists for — #86.
     *
     * `mimeFor`'s `COPY, NONE -> null` arm carries its consequence in a comment: "Returning null
     * makes canEncode answer true, which is the right answer: a copied or absent track places no
     * demand on the hardware." That is a product decision, and until this test nothing held it. A
     * MIME appearing in that arm would make a device with no matching encoder refuse a stream copy
     * — a job that never encodes anything — and the router would send it to FFmpeg to re-mux what
     * Media3 could have re-muxed.
     *
     * The `H264` line is what makes the other two mean something: without it, a `canEncode` that
     * simply returned `true` would satisfy this test. `NONE` is asserted separately from `COPY`
     * because they are one arm today and two answers, and splitting the arm must not silently
     * halve the coverage.
     */
    @Test
    fun `a device with no video encoder at all still permits a copied or absent track`() {
        val noEncoders = AndroidDeviceCodecs.forTesting(encoders = emptySet(), decoders = setOf("video/avc"))
        assertTrue(
            "a copied track is re-muxed, not encoded, so no encoder is required",
            noEncoders.canEncode(VideoCodec.COPY),
        )
        assertTrue("an absent track places no demand on the hardware", noEncoders.canEncode(VideoCodec.NONE))
        assertFalse(
            "this device has no AVC encoder, so an H.264 target has to be refused — without this, " +
                "a canEncode that always answered true would satisfy the two assertions above",
            noEncoders.canEncode(VideoCodec.H264),
        )
    }
}
