package dev.jasonmross.mediaconverter.join

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jasonmross.mediaconverter.convert.MediaProbe
import dev.jasonmross.mediaconverter.ffmpeg.ConcatEngine
import dev.jasonmross.mediaconverter.model.ConcatStrategy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The join path, end to end on a device.
 *
 * The point of these tests is the strategy decision, not merely that a file appears.
 * FFmpeg's `concat` demuxer does not reliably reject mismatched inputs -- it can emit a
 * file whose later segments are garbled -- so "it produced output" is not evidence of
 * correctness. Each test therefore checks which strategy ran *and* that the result is
 * long enough to contain both inputs.
 */
@RunWith(AndroidJUnit4::class)
class ConcatEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = ConcatEngine(context)
    private val staged = mutableListOf<File>()

    private lateinit var clipA: File
    private lateinit var clipB: File
    private lateinit var clipMismatched: File

    @Before
    fun setUp() {
        clipA = copyAsset("clip_a.mp4")
        clipB = copyAsset("clip_b.mp4")
        clipMismatched = copyAsset("clip_c_mismatched.mp4")
    }

    @After
    fun tearDown() {
        (staged + listOf(clipA, clipB, clipMismatched)).forEach { it.delete() }
    }

    private fun copyAsset(name: String): File {
        val out = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name)
            .use { asset -> out.outputStream().use { asset.copyTo(it) } }
        return out
    }

    private fun output(name: String) =
        File(context.cacheDir, name).also { it.delete(); staged += it }

    private fun durationMs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .filter { it.containsKey(MediaFormat.KEY_DURATION) }
                .maxOfOrNull { it.getLong(MediaFormat.KEY_DURATION) / 1000 } ?: 0L
        } finally {
            extractor.release()
        }
    }

    // --- the fast path ------------------------------------------------------

    @Test
    fun matchingClipsAreJoinedByStreamCopy() = runBlocking {
        val out = output("joined_matching.mp4")
        val result = engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(clipB)), out)

        assertEquals(
            "identical inputs should not need re-encoding",
            ConcatStrategy.STREAM_COPY,
            result.strategy,
        )
        assertTrue("no output produced", out.exists() && out.length() > 0)
        // Both 2 s inputs must be present, not just the first.
        assertTrue(
            "joined duration ${durationMs(out)}ms is too short to hold both clips",
            durationMs(out) >= 3_500,
        )
    }

    // --- the correctness path ----------------------------------------------

    @Test
    fun mismatchedClipsAreReEncodedRatherThanStreamCopied() = runBlocking {
        val out = output("joined_mismatched.mp4")
        val result = engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(clipMismatched)), out)

        // This is the case a naive implementation gets wrong: the demuxer would accept
        // these and produce a corrupt second half.
        assertEquals(
            "differing resolution must force a re-encode",
            ConcatStrategy.REENCODE,
            result.strategy,
        )
        assertTrue("no output produced", out.exists() && out.length() > 0)
        assertTrue(
            "joined duration ${durationMs(out)}ms is too short to hold both clips",
            durationMs(out) >= 3_500,
        )
    }

    @Test
    fun reEncodedOutputIsPlayableAndCarriesBothTracks() = runBlocking {
        val out = output("joined_playable.mp4")
        engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(clipMismatched)), out)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.absolutePath)
            val mimes = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            assertTrue("no video track in $mimes", mimes.any { it.startsWith("video/") })
            assertTrue("no audio track in $mimes", mimes.any { it.startsWith("audio/") })
        } finally {
            extractor.release()
        }
    }

    // --- guards -------------------------------------------------------------

    @Test
    fun joiningRefusesFewerThanTwoInputs() {
        val out = output("joined_single.mp4")
        val failure = runCatching {
            runBlocking { engine.join(listOf(Uri.fromFile(clipA)), out) }
        }.exceptionOrNull()
        assertTrue(
            "expected an IllegalArgumentException, got $failure",
            failure is IllegalArgumentException,
        )
    }

    @Test
    fun theListFileIsCleanedUpAfterJoining() = runBlocking {
        val out = output("joined_cleanup.mp4")
        engine.join(listOf(Uri.fromFile(clipA), Uri.fromFile(clipB)), out)
        assertTrue(
            "the concat list file was left behind",
            !File(out.parentFile, "concat_list.txt").exists(),
        )
    }

    // --- the probe the planner depends on ----------------------------------

    @Test
    fun probeReadsThePropertiesTheStrategyDependsOn() {
        val a = MediaProbe.probeForConcat(context, Uri.fromFile(clipA))
        val mismatched = MediaProbe.probeForConcat(context, Uri.fromFile(clipMismatched))

        assertEquals("h264", a.videoCodec)
        assertEquals(320, a.width)
        assertEquals(240, a.height)

        assertEquals(640, mismatched.width)
        assertEquals(480, mismatched.height)
        assertTrue(
            "the probe must actually distinguish these clips, or the planner cannot",
            a.width != mismatched.width || a.height != mismatched.height,
        )
    }
}
