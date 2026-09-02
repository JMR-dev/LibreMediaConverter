package org.libremediaconverter.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.VideoCodec
import org.robolectric.RobolectricTestRunner

/**
 * The rules `AndroidDeviceCodecs.probe()` applies to the platform's codec list.
 *
 * ## Why this is not a third run of the #86/#133 spike
 *
 * #86 closed `probe()` as device-bound. #133 re-opened the question with
 * `ShadowMediaCodecList` in hand and closed it again, for a reason that was right about what it
 * was answering: `MediaCodecInfoBuilder` "has no `setIsAlias` and no `setCanonicalName`, so the
 * alias skip and the canonical-name dedup — the two things the class's KDoc calls out as easy to
 * get wrong — are not reachable through it."
 *
 * **That objection is about the shadow.** It does not apply to a function that takes its own entry
 * type, which is what `capabilitiesFrom` now does. The half #133 named as unreachable is the half
 * this file spends most of its cases on.
 *
 * ## What made the seam worth cutting, which is not coverage
 *
 * The `runCatching` fallback logged *"assuming permissive"* and returned empty sets — and empty
 * sets are **restrictive**: `"video/avc" in emptySet()` is `false`, so `canEncode` and `canDecode`
 * both answer no and every job routes to FFmpeg. The code was right and the message described the
 * opposite of it. That is pinned below, so whichever reading a future change takes, it has to say
 * so out loud.
 *
 * Robolectric only because `capabilitiesFrom` logs what it found; the rules themselves are pure.
 */
@RunWith(RobolectricTestRunner::class)
class CodecEnumerationTest {

    /**
     * The alias skip, in the one arrangement where it is observable — and finding that arrangement
     * is the whole of this test.
     *
     * A first attempt listed the alias *after* the codec it aliases and passed with the skip
     * deleted, because `canonicalName` is shared and the dedup below catches the second entry
     * either way. The two rules overlap, so a fixture that does not separate them tests neither.
     *
     * What separates them is **order**. `MediaCodecInfo.getCanonicalName()` on an alias returns the
     * underlying codec's name, so an alias arriving first claims that name in `seen` and has its
     * own `supportedTypes` credited — and then the real codec is dropped by the dedup. Without the
     * alias skip the device is described by whichever entry the platform happened to list first.
     *
     * That also says what the rule is worth. With a `Set` accumulator, an alias declaring the same
     * types as its codec changes nothing whichever order they arrive in; the skip earns its place
     * only when the two disagree, which is exactly when believing the wrong one matters.
     */
    @Test
    fun `an alias listed before the codec it aliases does not describe the device`() {
        val codecs = capabilities(
            entry("c2.qti.avc.encoder", encoder = true, types = listOf(HEVC), alias = true),
            entry("c2.qti.avc.encoder", encoder = true, types = listOf(AVC)),
        )

        assertTrue("the real codec's types are the device's", codecs.canEncode(VideoCodec.H264))
        assertFalse(
            "an alias must not be credited with types the codec it aliases never claimed",
            codecs.canEncode(VideoCodec.H265),
        )
    }

    @Test
    fun `two entries sharing a canonical name are read once`() {
        val codecs = capabilities(
            entry("c2.qti.avc.encoder", encoder = true, types = listOf(AVC)),
            entry("c2.qti.avc.encoder", encoder = true, types = listOf(HEVC)),
        )

        assertEquals(setOf(AVC), codecs.hardwareEncoders())
    }

    /**
     * Both halves of the hardware predicate, one arm at a time.
     *
     * A vendor may declare a codec hardware-accelerated *and* software-only; the class KDoc is
     * explicit that the first flag "cannot be tested for correctness", so the second is what stops
     * a mislabelled software encoder being treated as the fast path.
     */
    @Test
    fun `an encoder counts as hardware only when it is accelerated and not software-only`() {
        assertEquals(
            setOf(AVC),
            capabilities(entry("hw", encoder = true, accelerated = true, types = listOf(AVC))).hardwareEncoders(),
        )
        assertEquals(
            emptySet<String>(),
            capabilities(entry("sw", encoder = true, accelerated = false, types = listOf(AVC))).hardwareEncoders(),
        )
        assertEquals(
            "a codec claiming both must not be trusted as hardware",
            emptySet<String>(),
            capabilities(
                entry("both", encoder = true, accelerated = true, softwareOnly = true, types = listOf(AVC)),
            ).hardwareEncoders(),
        )
    }

    /**
     * Decoders are collected regardless of the hardware flags, and that asymmetry is the design.
     *
     * `canDecode` asks whether the platform can read the input at all — a software decoder answers
     * that as well as a hardware one. `canEncode` asks whether the *fast path* exists, which is a
     * different question and why only encoders are filtered.
     */
    @Test
    fun `a software decoder still counts as something the platform can read`() {
        val codecs = capabilities(
            entry(
                "c2.android.avc.decoder",
                encoder = false,
                accelerated = false,
                softwareOnly = true,
                types = listOf(AVC),
            ),
        )

        assertTrue(codecs.canDecode("h264"))
    }

    @Test
    fun `audio types are ignored on both sides`() {
        val codecs = capabilities(
            entry("aac.encoder", encoder = true, accelerated = true, types = listOf("audio/mp4a-latm")),
            entry("aac.decoder", encoder = false, types = listOf("audio/mp4a-latm")),
        )

        assertEquals(emptySet<String>(), codecs.hardwareEncoders())
        // Not "the platform cannot decode AAC" -- `canDecode` is asked about *video* codec names,
        // and an unknown name is answered permissively. The point is that nothing audio reached
        // either set.
        assertTrue("an unknown name stays permissive", codecs.canDecode("something-nobody-named"))
    }

    /**
     * The failure fallback, pinned as the restrictive answer it actually is.
     *
     * #194 decided this rather than assuming it: the code stays, the message changes. If a later
     * change wants the permissive reading its old log line described, this test is what makes that
     * a decision instead of a drift.
     */
    @Test
    fun `an enumeration that fails sends every job to FFmpeg`() {
        val codecs = AndroidDeviceCodecs.capabilitiesFrom { error("MediaCodecList exploded") }

        assertFalse("a failed enumeration must not claim a hardware encoder", codecs.canEncode(VideoCodec.H264))
        assertFalse(codecs.canDecode("h264"))
        assertEquals(emptySet<String>(), codecs.hardwareEncoders())
    }

    /**
     * A list that throws partway keeps what it already read.
     *
     * This predates the seam — `runCatching` has always wrapped the iteration rather than a list
     * built before it — and it is asserted here because the seam is where it could quietly have
     * been lost. Taking a `List` instead of a `Sequence` would move the throw outside the loop and
     * turn this partial answer into an empty one, with no test to notice.
     */
    @Test
    fun `codecs read before a failing entry are kept`() {
        val codecs = AndroidDeviceCodecs.capabilitiesFrom {
            sequence {
                yield(entry("good", encoder = true, accelerated = true, types = listOf(AVC)))
                error("the sixth codec's properties threw")
            }
        }

        assertEquals(setOf(AVC), codecs.hardwareEncoders())
    }

    private fun capabilities(vararg entries: AndroidDeviceCodecs.Companion.CodecEntry) =
        AndroidDeviceCodecs.capabilitiesFrom { entries.asSequence() }

    private fun entry(
        canonicalName: String,
        encoder: Boolean,
        accelerated: Boolean = true,
        softwareOnly: Boolean = false,
        alias: Boolean = false,
        types: List<String>,
    ) = AndroidDeviceCodecs.Companion.CodecEntry(
        canonicalName = canonicalName,
        isAlias = alias,
        isEncoder = encoder,
        isHardwareAccelerated = accelerated,
        isSoftwareOnly = softwareOnly,
        supportedTypes = types,
    )

    private companion object {
        const val AVC = "video/avc"
        const val HEVC = "video/hevc"
    }
}
