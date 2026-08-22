package org.libremediaconverter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bridge between what probing reports and what the app's enums mean.
 *
 * Three vocabularies meet: `MediaExtractor` MIME types, FFprobe `codec_name` strings, and the
 * enums. Stream copy depends on the round trip, so a missing alias here shows up as "we could not
 * identify the source codec" and silently costs the user a re-encode.
 */
class CodecNamesTest {

    @Test
    fun `the aliases both probes emit resolve to the same codec`() {
        listOf("h264", "avc", "avc1", "AVC1").forEach {
            assertEquals("$it should be H.264", VideoCodec.H264, CodecNames.videoFromName(it))
        }
        listOf("hevc", "h265", "hvc1", "hev1").forEach {
            assertEquals("$it should be H.265", VideoCodec.H265, CodecNames.videoFromName(it))
        }
        assertEquals(VideoCodec.AV1, CodecNames.videoFromName("av01"))
        assertEquals(AudioCodec.AAC, CodecNames.audioFromName("mp4a"))
        assertEquals(AudioCodec.PCM, CodecNames.audioFromName("pcm_s16le"))
    }

    /** The sentinel must never look like a codec — that is the whole point of it. */
    @Test
    fun `the unparseable sentinel resolves to nothing`() {
        assertNull(CodecNames.videoFromName(InputProbe.UNPARSEABLE))
    }

    @Test
    fun `an unknown name resolves to nothing rather than a default`() {
        assertNull(CodecNames.videoFromName("cinepak"))
        assertNull(CodecNames.audioFromName("qdm2"))
        assertNull(CodecNames.videoFromName(null))
    }

    /** The source-info card shows these, so they must never be blank or a raw sentinel. */
    @Test
    fun `descriptions stay readable for unknown and missing codecs`() {
        assertEquals("H.264", CodecNames.describeVideo("h264"))
        assertEquals("Unknown", CodecNames.describeVideo(null))
        assertEquals("Unrecognised", CodecNames.describeVideo(InputProbe.UNPARSEABLE))
        // An unrecognised but real codec name is more useful shown than hidden.
        assertEquals("cinepak", CodecNames.describeVideo("cinepak"))
    }
}
