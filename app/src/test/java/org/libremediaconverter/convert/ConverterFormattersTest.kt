package org.libremediaconverter.convert

import org.junit.Assert.assertEquals
import org.junit.Test
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.VideoCodec

/**
 * The four pure helpers behind the converter screen's prose, pinned at the points where they
 * change what they say.
 *
 * No Compose rule and no Robolectric: these are `String` in, `String` out, and running them under a
 * device sandbox would buy nothing while hiding the boundaries in a rendered tree.
 *
 * The defect each group bites on:
 *
 * - **[formatBytes] picks a unit by comparing against three thresholds.** Every one of them is a
 *   `>=`, and a `>` would move a file sitting exactly on a boundary into the unit below -- `1 GB`
 *   shown as `1000.0 MB`. Only a value *on* the threshold can tell the two apart, so each of the
 *   three is asserted at the boundary and one below it. The unit prefixes are decimal, matching
 *   what the file manager and the provider report, not powers of two.
 * - **[formatDuration] has no hours field.** An hour-long recording reads `60:00`, and that is the
 *   contract rather than an oversight -- the row is a length, not a clock. Pinned so that adding
 *   hours is a deliberate change with a red test in front of it instead of a silent reformat.
 * - **[describe] builds the suggestion-chip label out of up to three parts**, and the parts are
 *   conditional: [VideoCodec.NONE] and [AudioCodec.NONE] drop out entirely, so an image output
 *   with neither track has to render as the container alone rather than as a container followed
 *   by a dangling separator.
 * - **[EnginePreference] carries no `label` property**, unlike every other enum the screen
 *   renders; its three display strings live in a `when` in the screen file. Adding a constant is
 *   caught by the compiler because that `when` is exhaustive, but nothing stops two constants
 *   being given the same string, which is what the distinctness assertion is for.
 */
class ConverterFormattersTest {

    @Test
    fun `bytes below a kilobyte are counted exactly`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1 B", formatBytes(1))
        assertEquals("999 B", formatBytes(999))
    }

    @Test
    fun `each unit starts exactly on its threshold rather than one byte past it`() {
        assertEquals("1 kB", formatBytes(1_000))
        assertEquals("1.0 MB", formatBytes(1_000_000))
        assertEquals("1.0 GB", formatBytes(1_000_000_000))
    }

    /**
     * One byte below each threshold, which is the half a `>=` to `>` change leaves alone. Both
     * halves are needed: the boundary values alone would still pass if the comparison let
     * everything through.
     */
    @Test
    fun `a value just below a threshold stays in the smaller unit`() {
        assertEquals("999 B", formatBytes(999))
        assertEquals("1000 kB", formatBytes(999_999))
        assertEquals("1000.0 MB", formatBytes(999_999_999))
    }

    @Test
    fun `a real file size reads as one decimal place`() {
        assertEquals("12.3 MB", formatBytes(12_345_678))
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
    }

    @Test
    fun `a duration is minutes and zero-padded seconds`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:01", formatDuration(1_000))
        assertEquals("0:59", formatDuration(59_000))
        assertEquals("1:00", formatDuration(60_000))
        assertEquals("1:30", formatDuration(90_000))
    }

    /** Sub-second remainders are dropped rather than rounded up into the next second. */
    @Test
    fun `a partial second does not become a whole one`() {
        assertEquals("0:00", formatDuration(999))
        assertEquals("0:59", formatDuration(59_999))
    }

    /** No hours field, deliberately: an hour is `60:00` and two hours are `120:00`. */
    @Test
    fun `an hour and beyond keeps counting in minutes`() {
        assertEquals("60:00", formatDuration(3_600_000))
        assertEquals("61:01", formatDuration(3_661_000))
        assertEquals("120:00", formatDuration(7_200_000))
    }

    @Test
    fun `a spec with both tracks names the container and joins the two codecs`() {
        assertEquals(
            "MP4 · H.264 + AAC",
            describe(OutputSpec(Container.MP4, VideoCodec.H264, AudioCodec.AAC)),
        )
    }

    @Test
    fun `a track set to none is left out instead of being named none`() {
        assertEquals(
            "MP3 · MP3",
            describe(OutputSpec(Container.MP3, VideoCodec.NONE, AudioCodec.MP3)),
        )
        assertEquals(
            "MP4 · H.264",
            describe(OutputSpec(Container.MP4, VideoCodec.H264, AudioCodec.NONE)),
        )
    }

    /** An image output has neither track, so there is nothing for the separator to separate. */
    @Test
    fun `a spec with no tracks at all is the container alone, with no trailing separator`() {
        assertEquals("GIF", describe(OutputSpec(Container.GIF, VideoCodec.NONE, AudioCodec.NONE)))
        assertEquals(
            "PNG frames",
            describe(OutputSpec(Container.IMAGE_SEQUENCE, VideoCodec.NONE, AudioCodec.NONE)),
        )
    }

    /** `Copy` is a codec here, not the absence of one, so a remux describes both tracks. */
    @Test
    fun `a remux names copy on both tracks rather than dropping them`() {
        assertEquals(
            "Matroska · Copy + Copy",
            describe(OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY)),
        )
    }

    @Test
    fun `each engine preference has the wording the chips show`() {
        assertEquals("Automatic", EnginePreference.AUTO.label())
        assertEquals("Prefer hardware", EnginePreference.PREFER_HARDWARE.label())
        assertEquals("Force software", EnginePreference.FORCE_SOFTWARE.label())
    }

    /**
     * Two constants sharing a label would render as two identical chips, one of which the user
     * could not choose deliberately. The exhaustive `when` cannot catch that; this does.
     */
    @Test
    fun `no two engine preferences render the same chip`() {
        val labels = EnginePreference.entries.map { it.label() }

        assertEquals(EnginePreference.entries.size, labels.toSet().size)
        assertEquals(emptyList<String>(), labels.filter { it.isBlank() })
    }
}
