package dev.jasonmross.mediaconverter.ffmpeg

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jasonmross.mediaconverter.model.ConversionRequest
import dev.jasonmross.mediaconverter.model.OutputFormat
import dev.jasonmross.mediaconverter.model.QualityTier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the bundled FFmpeg on-device.
 *
 * These are the formats the app exists for that Media3 structurally cannot produce, so
 * they are also the ones with no other coverage. Each test asserts against the produced
 * file rather than the exit code, because FFmpeg will happily report success after
 * writing something unplayable if the arguments are wrong.
 */
@RunWith(AndroidJUnit4::class)
class FFmpegEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = FFmpegEngine()
    private lateinit var input: File
    private val outputs = mutableListOf<File>()

    @Before
    fun setUp() {
        input = File(context.cacheDir, "ffmpeg_sample.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264.mp4")
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
    }

    @After
    fun tearDown() {
        input.delete()
        outputs.forEach { it.delete() }
    }

    private fun outputFor(name: String) =
        File(context.cacheDir, name).also { it.delete(); outputs += it }

    private fun convert(format: OutputFormat, quality: QualityTier = QualityTier.BEST): File {
        val out = outputFor("out_${format.name.lowercase()}.${format.extension}")
        runBlocking {
            engine.run(
                request = ConversionRequest(format = format, quality = quality),
                inputPath = input.absolutePath,
                output = out,
                durationMs = 3_000,
            )
        }
        return out
    }

    private fun trackMimes(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
        } finally {
            extractor.release()
        }
    }

    // --- the formats that justify bundling FFmpeg at all -------------------

    @Test
    fun encodesMp3WhichAndroidCannotDoAtAnyApiLevel() {
        val out = convert(OutputFormat.MP3)
        assertTrue("no MP3 produced", out.exists() && out.length() > 0)
        assertTrue(
            "expected an mpeg audio track, got ${trackMimes(out)}",
            trackMimes(out).any { it.contains("mp") && it.startsWith("audio/") },
        )
    }

    @Test
    fun encodesGifWithAGeneratedPalette() {
        val out = convert(OutputFormat.GIF)
        assertTrue("no GIF produced", out.exists() && out.length() > 0)
        // GIF87a / GIF89a magic. Proves a real GIF rather than a mislabelled file.
        val magic = out.inputStream().use { String(it.readNBytes(6)) }
        assertTrue("not a GIF: $magic", magic.startsWith("GIF"))
    }

    @Test
    fun encodesMatroskaWhichMedia3CannotMux() {
        val out = convert(OutputFormat.MKV_H264)
        assertTrue("no MKV produced", out.exists() && out.length() > 0)
        // EBML magic, the Matroska container header.
        val magic = out.inputStream().use { it.readNBytes(4) }
        assertEquals(0x1A.toByte(), magic[0])
        assertEquals(0x45.toByte(), magic[1])
        assertEquals(0xDF.toByte(), magic[2])
        assertEquals(0xA3.toByte(), magic[3])
    }

    @Test
    fun encodesFlacLosslessAudio() {
        val out = convert(OutputFormat.FLAC)
        assertTrue("no FLAC produced", out.exists() && out.length() > 0)
    }

    @Test
    fun encodesWav() {
        val out = convert(OutputFormat.WAV)
        assertTrue("no WAV produced", out.exists() && out.length() > 0)
        val magic = out.inputStream().use { String(it.readNBytes(4)) }
        assertEquals("RIFF", magic)
    }

    @Test
    fun encodesOpus() {
        val out = convert(OutputFormat.OPUS)
        assertTrue("no Opus produced", out.exists() && out.length() > 0)
    }

    // --- the quality tier the GPL licence was taken for --------------------

    @Test
    fun bestQualityProducesAPlayableH264File() {
        val out = convert(OutputFormat.MP4_H264, QualityTier.BEST)
        assertTrue("no MP4 produced", out.exists() && out.length() > 0)
        assertTrue(
            "expected an AVC track, got ${trackMimes(out)}",
            trackMimes(out).any { it == MediaFormat.MIMETYPE_VIDEO_AVC },
        )
    }

    @Test
    fun bestQualityProducesAPlayableH265File() {
        val out = convert(OutputFormat.MP4_H265, QualityTier.BEST)
        assertTrue("no MP4 produced", out.exists() && out.length() > 0)
        assertTrue(
            "expected an HEVC track, got ${trackMimes(out)}",
            trackMimes(out).any { it == MediaFormat.MIMETYPE_VIDEO_HEVC },
        )
    }

    @Test
    fun failureSurfacesAsAnExceptionRatherThanASilentEmptyFile() {
        val out = outputFor("nope.mp4")
        val failure = runCatching {
            runBlocking {
                engine.run(
                    request = ConversionRequest(format = OutputFormat.MP4_H264),
                    inputPath = "/does/not/exist.mp4",
                    output = out,
                    durationMs = 1_000,
                )
            }
        }.exceptionOrNull()
        assertTrue("expected an FFmpegException, got $failure", failure is FFmpegEngine.FFmpegException)
    }
}
