package org.libremediaconverter.convert

import com.arthenica.ffmpegkit.MediaInformation
import com.arthenica.ffmpegkit.StreamInformation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.Container
import org.robolectric.RobolectricTestRunner

/**
 * What FFprobe's answer means, read as a function of the answer alone.
 *
 * `readMediaInformation` was 114 missed instructions and 24 missed branches — the second-biggest
 * block on the wave-4 report — of which **exactly one line needed a device**:
 *
 * ```kotlin
 * FFprobeKit.getMediaInformation(path).getMediaInformation()
 * ```
 *
 * Everything after it reads an ordinary object. `javap` over the committed AAR's runtime jar:
 * `MediaInformation(JSONObject, List<StreamInformation>, List<Chapter>)` and
 * `StreamInformation(JSONObject)` are plain public constructors, and neither class's `<clinit>`
 * loads the native library — so the fixtures below are built without `libffmpegkit` present.
 *
 * ## The one that matters
 *
 * `containerFrom(formatName, video?.getCodec())`. FFprobe reports `matroska,webm` for **both** MKV
 * and WebM, because they share a demuxer, so the video codec is the only thing separating them.
 * `containerFrom` has thirty-three covered branches of its own and not one of them can notice the
 * argument being dropped — the mistake would be at the call, not in the callee, and every existing
 * `containerFrom` test would stay green while every VP9 WebM quietly became an MKV.
 *
 * Robolectric only for `org.json`, which is a stub in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class FFprobeMappingTest {

    @Test
    fun `the video codec decides between matroska and webm`() {
        assertEquals(
            Container.WEBM,
            MediaProbe.ffprobeInfoFrom(info("matroska,webm", stream("video", "vp9"))).container,
        )
        assertEquals(
            Container.MKV,
            MediaProbe.ffprobeInfoFrom(info("matroska,webm", stream("video", "h264"))).container,
        )
    }

    /**
     * The same format name with no video stream at all, which is what makes the case above about
     * the *argument* rather than about the format string.
     */
    @Test
    fun `a matroska container with no video track cannot be told from webm and is not guessed`() {
        val read = MediaProbe.ffprobeInfoFrom(info("matroska,webm", stream("audio", "opus")))

        assertEquals(Container.MKV, read.container)
        assertNull(read.videoCodec)
    }

    @Test
    fun `the first stream of each type wins`() {
        val read = MediaProbe.ffprobeInfoFrom(
            info(
                "mov,mp4,m4a,3gp,3g2,mj2",
                stream("video", "h264", width = 1920, height = 1080),
                stream("video", "hevc", width = 640, height = 480),
                stream("audio", "aac"),
                stream("audio", "mp3"),
            ),
        )

        assertEquals("h264", read.videoCodec)
        assertEquals("aac", read.audioCodec)
        assertEquals(1920, read.width)
        assertEquals(1080, read.height)
    }

    /**
     * Dimensions come from the stream the codec came from, not from whichever stream has some.
     *
     * The fixture is deliberately awkward: the chosen video stream carries **no** dimensions and a
     * later one does. That is a real shape — FFprobe omits `width`/`height` for a stream it could
     * not measure — and it is the only arrangement that separates the two readings.
     *
     * A first version of this file asserted the dimensions inside the case above, where the chosen
     * stream was also the first one carrying any. Replacing `video?.getWidth()` with
     * `streams.firstNotNullOfOrNull { it.getWidth() }` gave the same answer there and **the
     * mutation survived**. It reddens here.
     */
    @Test
    fun `a video stream with no dimensions reports none rather than borrowing another stream's`() {
        val read = MediaProbe.ffprobeInfoFrom(
            info(
                "mov,mp4,m4a,3gp,3g2,mj2",
                stream("video", "h264"),
                stream("video", "hevc", width = 640, height = 480),
            ),
        )

        assertEquals("h264", read.videoCodec)
        assertEquals(0, read.width)
        assertEquals(0, read.height)
    }

    /**
     * Stream order is the file's, not a promise. An audio-first container must read the same as a
     * video-first one.
     */
    @Test
    fun `an audio track listed first does not become the video track`() {
        val read = MediaProbe.ffprobeInfoFrom(
            info("mov,mp4,m4a,3gp,3g2,mj2", stream("audio", "aac"), stream("video", "h264")),
        )

        assertEquals("h264", read.videoCodec)
        assertEquals("aac", read.audioCodec)
    }

    @Test
    fun `a duration in seconds becomes milliseconds`() {
        assertEquals(12_345L, MediaProbe.ffprobeInfoFrom(info("mp4", duration = "12.345")).durationMs)
    }

    /**
     * Both ways a duration can be absent, and neither may throw.
     *
     * FFprobe reports `"N/A"` for a stream it could not measure, and omits the key entirely for
     * some containers. `toDoubleOrNull` is what keeps the second from being an exception on the
     * file-pick path, where there is no user-visible failure to report it as.
     */
    @Test
    fun `a duration that is not a number is no duration rather than a crash`() {
        assertEquals(0L, MediaProbe.ffprobeInfoFrom(info("mp4", duration = "N/A")).durationMs)
        assertEquals(0L, MediaProbe.ffprobeInfoFrom(info("mp4", duration = null)).durationMs)
    }

    @Test
    fun `a file with no streams reports nothing rather than defaults that look measured`() {
        val read = MediaProbe.ffprobeInfoFrom(info("mp4"))

        assertNull(read.videoCodec)
        assertNull(read.audioCodec)
        assertEquals(0, read.width)
        assertEquals(0, read.height)
    }

    @Test
    fun `an image format is reported as one`() {
        assertTrue(MediaProbe.ffprobeInfoFrom(info("png_pipe", stream("video", "png"))).isImage)
        assertFalse(MediaProbe.ffprobeInfoFrom(info("mp4", stream("video", "h264"))).isImage)
    }

    private fun stream(type: String, codec: String, width: Int? = null, height: Int? = null) = StreamInformation(
        JSONObject().apply {
            put(StreamInformation.KEY_TYPE, type)
            put(StreamInformation.KEY_CODEC, codec)
            width?.let { put(StreamInformation.KEY_WIDTH, it) }
            height?.let { put(StreamInformation.KEY_HEIGHT, it) }
        },
    )

    /**
     * The format properties are **nested** under `"format"`, which is how FFprobe reports them and
     * what `MediaInformation` reads: `getFormat()` resolves through `getStringFormatProperty`, not
     * off the top-level object. A first version of this helper put the keys at the top level and
     * every format-dependent case failed with a null container, which is worth recording here so
     * the next fixture does not have to rediscover it.
     *
     * Streams are the other half and are *not* nested — they come from the constructor argument.
     */
    private fun info(formatName: String, vararg streams: StreamInformation, duration: String? = "1.0") =
        MediaInformation(
            JSONObject().apply {
                put(
                    MediaInformation.KEY_FORMAT_PROPERTIES,
                    JSONObject().apply {
                        put(MediaInformation.KEY_FORMAT, formatName)
                        duration?.let { put(MediaInformation.KEY_DURATION, it) }
                    },
                )
            },
            streams.toList(),
            emptyList(),
        )
}
