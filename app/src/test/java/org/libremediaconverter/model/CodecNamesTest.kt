package org.libremediaconverter.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bridge between what probing reports and what the app's enums mean.
 *
 * Three vocabularies meet: `MediaExtractor` MIME types, FFprobe `codec_name` strings, and the
 * enums. Stream copy depends on the round trip, so a missing alias here shows up as "we could not
 * identify the source codec" and silently costs the user a re-encode.
 *
 * Also bites on #74: `describeVideo` and `describeAudio` are one function apiece over one
 * vocabulary and had stopped matching. Only the video side special-cased
 * [InputProbe.UNPARSEABLE]; the audio side fell through to the raw name, and that sentinel opens
 * with a NUL, so the source-info card would have rendered a control character. The arms are shared
 * now, and the tests below assert both sides so the symmetric bug cannot reappear on the other one.
 *
 * The tables these read are cross-checked against the device capability check by
 * `CodecVocabularyTest` (#87). Deliberately not repeated here: this file is what each name means,
 * that one is whether the app's two copies of the vocabulary still agree.
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

    /** The audio row of the same card, which had none of the above. */
    @Test
    fun `audio descriptions degrade exactly the way video ones do`() {
        assertEquals("AAC", CodecNames.describeAudio("mp4a"))
        assertEquals("Unknown", CodecNames.describeAudio(null))
        assertEquals("Unrecognised", CodecNames.describeAudio(InputProbe.UNPARSEABLE))
        assertEquals("qdm2", CodecNames.describeAudio("qdm2"))
    }

    /**
     * #74's actual failure mode, stated as the thing the user would have seen.
     *
     * `InputProbe.UNPARSEABLE` is `"\u0000unparseable"`. Falling through to `?: name` does not
     * mislabel the track, it puts U+0000 into a `Text`.
     */
    @Test
    fun `no description can put a control character on the card`() {
        listOf(CodecNames.describeAudio(InputProbe.UNPARSEABLE), CodecNames.describeVideo(InputProbe.UNPARSEABLE))
            .forEach { assertFalse("$it leaks the sentinel", it.contains('\u0000')) }
    }

    /**
     * Every alias, pinned one at a time.
     *
     * The tables became maps so `CodecVocabularyTest` could enumerate them; this is what catches a
     * key mistyped or a value pointing at the wrong enum while that rewrite happened.
     */
    @Test
    fun `every name in the tables resolves to the codec it spells`() {
        CodecNames.VIDEO_ALIASES.forEach { (name, codec) ->
            assertEquals(name, codec, CodecNames.videoFromName(name))
        }
        CodecNames.AUDIO_ALIASES.forEach { (name, codec) ->
            assertEquals(name, codec, CodecNames.audioFromName(name))
        }
        assertEquals(VideoCodec.H264, CodecNames.videoFromName("x264"))
        assertEquals(VideoCodec.VP9, CodecNames.videoFromName("vp09"))
        assertEquals(AudioCodec.MP3, CodecNames.audioFromName("mpga"))
        assertEquals(AudioCodec.OPUS, CodecNames.audioFromName("opus"))
    }

    /** The audio lookup reads the sentinel the same way the video one does. */
    @Test
    fun `the unparseable sentinel resolves to nothing on the audio side too`() {
        assertNull(CodecNames.audioFromName(InputProbe.UNPARSEABLE))
        assertNull(CodecNames.audioFromName(null))
    }
}
