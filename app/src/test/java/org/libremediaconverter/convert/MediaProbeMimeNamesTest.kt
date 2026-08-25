package org.libremediaconverter.convert

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The MIME -> short codec name table, which nothing downstream would notice going wrong.
 *
 * `MediaExtractor` answers in platform MIME spellings; the router, the copy planner and the
 * source-info card all speak FFmpeg's short names. [MediaProbe.shortName] is the one place those
 * two vocabularies meet, and most of its arms are translations rather than trimming — `video/avc`
 * is `h264`, `audio/mp4a-latm` is `aac`, `video/x-vnd.on2.vp9` is `vp9`.
 *
 * So a dropped or mistyped arm does not throw. It falls through to `substringAfter('/')` and
 * reports a different, entirely plausible-looking string. `CodecNames` carries alias lists that
 * happen to rescue some of those (`avc`, `av01`, `raw`) and not others (`mp4a-latm`,
 * `x-vnd.on2.vp9`), which is exactly why leaning on the rescue is not a plan: an unrecognised
 * codec is how a stream-copyable file quietly becomes a re-encode, and how the card ends up naming
 * a codec no user has heard of. This table is the only place those arms are pinned.
 *
 * A plain JVM test rather than Robolectric: `MediaFormat.MIMETYPE_*` are Java compile-time String
 * constants, so this test and `MediaProbe` alike carry the literals in their own bytecode and the
 * framework class is never loaded.
 *
 * Every case names its MIME in the failure message, because the MIME is the thing that has to be
 * looked up when one of these goes red.
 */
class MediaProbeMimeNamesTest {

    @Test
    fun `an AVC track is reported as h264, which is what everything downstream calls it`() {
        assertShortName("h264", MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    /** On2's vendor MIME looks nothing like the codec name FFmpeg and the router use. */
    @Test
    fun `the VP8 and VP9 vendor MIMEs are reported without their vendor prefix`() {
        assertShortName("vp8", MediaFormat.MIMETYPE_VIDEO_VP8)
        assertShortName("vp9", MediaFormat.MIMETYPE_VIDEO_VP9)
    }

    @Test
    fun `AV1 and MPEG-4 are reported by codec name rather than by MIME spelling`() {
        assertShortName("av1", MediaFormat.MIMETYPE_VIDEO_AV1)
        assertShortName("mpeg4", MediaFormat.MIMETYPE_VIDEO_MPEG4)
    }

    @Test
    fun `an AAC track is reported as aac, not as the mp4a-latm its MIME says`() {
        assertShortName("aac", MediaFormat.MIMETYPE_AUDIO_AAC)
    }

    @Test
    fun `uncompressed audio is reported as pcm, which is not what its MIME says either`() {
        assertShortName("pcm", MediaFormat.MIMETYPE_AUDIO_RAW)
    }

    /**
     * Four arms produce exactly what the fallback would produce anyway.
     *
     * `video/hevc` -> `hevc`, `audio/opus` -> `opus`, `audio/flac` -> `flac`,
     * `audio/vorbis` -> `vorbis`: for these the `when` arm and `substringAfter('/')` agree, so
     * deleting the arm changes no observable behaviour and no test can catch it. That is a
     * property of the code rather than a gap here, and it is reported as such rather than dressed
     * up as coverage. The assertions still earn their place — they pin the promise the router is
     * given (`hevc`, whatever the MIME happens to spell) against a later edit that changes the
     * mapping rather than deleting it.
     */
    @Test
    fun `the arms whose MIME subtype already is the short name still map to it`() {
        assertShortName("hevc", MediaFormat.MIMETYPE_VIDEO_HEVC)
        assertShortName("opus", MediaFormat.MIMETYPE_AUDIO_OPUS)
        assertShortName("flac", MediaFormat.MIMETYPE_AUDIO_FLAC)
        assertShortName("vorbis", MediaFormat.MIMETYPE_AUDIO_VORBIS)
    }

    /**
     * The fallback, which is what makes an unlisted codec describable at all.
     *
     * These are real `MediaFormat` MIMEs with no arm of their own. Dropping the subtype is the
     * right guess far more often than reporting the whole MIME would be — FFprobe calls the first
     * of these `ac3` too.
     */
    @Test
    fun `a MIME with no arm of its own falls back to its subtype`() {
        assertShortName("ac3", MediaFormat.MIMETYPE_AUDIO_AC3)
        assertShortName("mpeg2", MediaFormat.MIMETYPE_VIDEO_MPEG2)
        assertShortName("dolby-vision", MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)
    }

    /**
     * The surprising half of `substringAfter`'s contract, pinned deliberately.
     *
     * With no `/` in the string it returns the whole input rather than the empty string. Today's
     * callers gate on a `video/` or `audio/` prefix so they cannot reach this, but "report what
     * you were given" rather than "report nothing" is what would keep a malformed MIME visible on
     * the card instead of blank.
     */
    @Test
    fun `a MIME with no subtype separator is reported unchanged`() {
        assertShortName("weird", "weird")
        assertShortName("", "")
    }

    private fun assertShortName(expected: String, mime: String) =
        assertEquals("shortName(\"$mime\")", expected, MediaProbe.shortName(mime))
}
