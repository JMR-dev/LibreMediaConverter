package org.libremediaconverter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matrix that replaced the closed format enum.
 *
 * `OutputFormat` used to be twelve hand-picked triples, and its KDoc defended that on the grounds
 * that a closed set was what made routing decidable. Opening it up moves that burden here, so this
 * is where decidability now has to be proven.
 */
class ContainerCapabilitiesTest {

    private val h264Source = InputProbe(
        videoCodec = "h264",
        audioCodec = "aac",
        container = Container.MP4,
    )

    // --- copy and encode are different questions ----------------------------

    /**
     * The case that makes the mode axis necessary.
     *
     * A single boolean would have to answer one way or the other, and either answer is wrong half
     * the time: refusing AV1 in MP4 blocks a legitimate remux, allowing it promises an encode
     * neither engine can deliver.
     */
    @Test
    fun `MP4 carries AV1 on copy but cannot encode it`() {
        assertTrue(ContainerCapabilities.accepts(Container.MP4, VideoCodec.AV1, CodecMode.COPY))
        assertFalse(ContainerCapabilities.accepts(Container.MP4, VideoCodec.AV1, CodecMode.ENCODE))
    }

    @Test
    fun `Matroska carries Vorbis on copy but nothing here encodes it`() {
        assertTrue(ContainerCapabilities.accepts(Container.MKV, AudioCodec.VORBIS, CodecMode.COPY))
        assertFalse(
            ContainerCapabilities.accepts(Container.MKV, AudioCodec.VORBIS, CodecMode.ENCODE),
        )
    }

    @Test
    fun `a codec the container cannot hold is refused in both modes`() {
        listOf(CodecMode.COPY, CodecMode.ENCODE).forEach { mode ->
            assertFalse(
                "WebM should never accept H.264 ($mode)",
                ContainerCapabilities.accepts(Container.WEBM, VideoCodec.H264, mode),
            )
            assertFalse(
                "WAV should never accept AAC ($mode)",
                ContainerCapabilities.accepts(Container.WAV, AudioCodec.AAC, mode),
            )
        }
    }

    @Test
    fun `H265 in AVI is refused — AVI predates it`() {
        assertFalse(ContainerCapabilities.accepts(Container.AVI, VideoCodec.H265, CodecMode.COPY))
        assertTrue(ContainerCapabilities.accepts(Container.AVI, VideoCodec.H264, CodecMode.COPY))
    }

    @Test
    fun `resolving COPY before asking the matrix is required`() {
        // The matrix cannot answer for COPY; the caller has to resolve it against the probe first.
        // Failing loudly is what stops a caller from silently getting "false" and refusing a
        // perfectly good remux.
        runCatching { ContainerCapabilities.accepts(Container.MP4, VideoCodec.COPY, CodecMode.COPY) }
            .onSuccess { throw AssertionError("expected COPY to be rejected by the matrix") }
    }

    // --- validation ---------------------------------------------------------

    @Test
    fun `every preset is a valid spec`() {
        OutputFormat.entries.forEach { preset ->
            val result = ContainerCapabilities.validate(preset.spec, h264Source)
            assertTrue("${preset.name} is not valid: $result", result.isValid)
        }
    }

    /** A suggestion that is itself invalid is worse than no suggestion. */
    @Test
    fun `every suggestion is itself valid`() {
        val broken = OutputSpec(Container.WEBM, VideoCodec.H264, AudioCodec.AAC)
        val result = ContainerCapabilities.validate(broken, h264Source)

        val invalid = result as? Validation.Invalid
            ?: throw AssertionError("expected H.264 in WebM to be rejected")
        assertTrue("no alternatives offered", invalid.suggestions.isNotEmpty())
        invalid.suggestions.forEach { suggestion ->
            assertTrue(
                "suggested $suggestion is itself invalid",
                ContainerCapabilities.validate(suggestion, h264Source).isValid,
            )
        }
    }

    @Test
    fun `an unidentifiable source codec cannot be copied`() {
        val unknown = InputProbe(videoCodec = InputProbe.UNPARSEABLE, audioCodec = null)
        val spec = OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.NONE)

        val result = ContainerCapabilities.validate(spec, unknown)
        assertFalse("copying an unknown codec must be refused", result.isValid)
    }

    @Test
    fun `copying a video track into an audio-only container is refused`() {
        val spec = OutputSpec(Container.MP3, VideoCodec.COPY, AudioCodec.MP3)
        val result = ContainerCapabilities.validate(spec, h264Source)

        val invalid = result as? Validation.Invalid
            ?: throw AssertionError("expected video in MP3 to be rejected")
        assertTrue(invalid.message.contains("audio only"))
    }

    @Test
    fun `dropping both tracks is refused rather than producing an empty file`() {
        val spec = OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.NONE)
        val result = ContainerCapabilities.validate(spec, h264Source)

        assertFalse(result.isValid)
        assertTrue((result as Validation.Invalid).suggestions.isNotEmpty())
    }

    @Test
    fun `copying is offered as the fix when the codec is right but unencodable`() {
        val av1Source = InputProbe(videoCodec = "av1", audioCodec = "aac", container = Container.MKV)
        val spec = OutputSpec(Container.MP4, VideoCodec.AV1, AudioCodec.AAC)

        val invalid = ContainerCapabilities.validate(spec, av1Source) as? Validation.Invalid
            ?: throw AssertionError("expected an AV1 encode to be rejected")
        assertTrue(
            "should offer to copy the AV1 track instead, got ${invalid.suggestions}",
            invalid.suggestions.any { it.videoCodec == VideoCodec.COPY },
        )
    }

    // --- the picker reads these ---------------------------------------------

    @Test
    fun `encodable lists never contain a codec the container cannot hold`() {
        Container.entries.forEach { container ->
            ContainerCapabilities.encodableVideo(container).forEach {
                assertTrue(
                    "$container claims to encode $it but cannot hold it",
                    ContainerCapabilities.accepts(container, it, CodecMode.COPY),
                )
            }
            ContainerCapabilities.encodableAudio(container).forEach {
                assertTrue(
                    "$container claims to encode $it but cannot hold it",
                    ContainerCapabilities.accepts(container, it, CodecMode.COPY),
                )
            }
        }
    }

    @Test
    fun `audio-only containers offer no video codecs`() {
        listOf(Container.MP3, Container.WAV, Container.FLAC, Container.OGG, Container.AAC_ADTS)
            .forEach { container ->
                assertFalse("$container claims to hold video", container.canHoldVideo)
                assertEquals(emptyList<VideoCodec>(), ContainerCapabilities.encodableVideo(container))
            }
    }
}
