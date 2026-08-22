package org.libremediaconverter.convert

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.OutputFormat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end hardware transcode through [Media3Engine].
 *
 * This is the Phase 1 verification: it proves the MediaCodec pipeline actually runs on
 * a device, and — more importantly — that the engine can be driven from a thread with
 * no Looper of its own without tripping Transformer's single-thread requirement.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Media3EngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Media3Engine
    private lateinit var input: File
    private lateinit var output: File

    @Before
    fun setUp() {
        engine = Media3Engine(context)
        input = File(context.cacheDir, "sample_h264.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264.mp4")
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
        output = File(context.cacheDir, "out_hevc.mp4")
        output.delete()
    }

    @After
    fun tearDown() {
        engine.close()
        input.delete()
        output.delete()
    }

    @Test
    fun transcodesH264ToH265AndReportsProgress(): Unit = runBlocking {
        val seen = mutableListOf<Int>()

        engine.transcode(
            input = Uri.fromFile(input),
            output = output,
            format = OutputFormat.MP4_H265,
        ) { percent -> seen += percent }

        assertTrue("export produced no file", output.exists())
        assertTrue("export produced an empty file", output.length() > 0)
        // Assert against the muxed file, not just the reported result: this is what
        // actually proves the output is HEVC rather than a silent fallback to H.264.
        assertEquals(MimeTypes.VIDEO_H265, videoMimeTypeOf(output))
        // Assert against the muxed file rather than the engine's own report: a result
        // object can claim success for a file that will not play.
        assertTrue("output has no duration", durationMsOf(output) > 0)
        // Deliberately NOT asserting that progress fired. Polling is on a 250 ms tick,
        // and a 3 s 320x240 clip can finish inside one tick on fast hardware, which
        // would make the assertion fail intermittently for no real defect.
        seen.forEach { assertTrue("progress out of range: $it", it in 0..100) }
    }

    /**
     * Regression guard for the audio-extraction bug.
     *
     * [OutputFormat.M4A_AAC] declares `VideoCodec.NONE`, and the router sends it to Media3. But
     * the engine used to build a bare `EditedMediaItem` and take a video MIME type that defaulted
     * to HEVC, so "extract the audio" transcoded the *video* to H.265 and wrote it to a file named
     * `.m4a`. Nothing failed; the output was simply not what was asked for.
     *
     * This is the test the suite was missing — [Media3EngineTest] had no audio-only case at all,
     * which is why the defect survived.
     */
    @Test
    fun audioOnlyExportDropsTheVideoTrack(): Unit = runBlocking {
        val audio = File(context.cacheDir, "out_audio.m4a")
        audio.delete()
        try {
            engine.transcode(Uri.fromFile(input), audio, OutputFormat.M4A_AAC)

            assertTrue("export produced no file", audio.exists() && audio.length() > 0)
            val tracks = trackMimeTypesOf(audio)
            assertEquals("expected exactly one track, got $tracks", 1, tracks.size)
            assertEquals(MimeTypes.AUDIO_AAC, tracks.single())
            assertNull("an audio-only export must carry no video track", videoMimeTypeOf(audio))
            assertTrue("output has no duration", durationMsOf(audio) > 0)
        } finally {
            audio.delete()
        }
    }

    /**
     * WAV output, which reaches Media3 for the same reason M4A does.
     *
     * `Container.WAV` is in the router's Media3 set, but until the engine passed a muxer factory
     * the only muxer Transformer ever used was the MP4 one — so asking for WAV produced an MP4.
     * Asserting the RIFF header proves the container, not merely that a file appeared; this
     * follows what `FFmpegEngineTest` already does for its formats.
     */
    @Test
    fun wavExportWritesARiffHeader(): Unit = runBlocking {
        val wav = File(context.cacheDir, "out_audio.wav")
        wav.delete()
        try {
            engine.transcode(Uri.fromFile(input), wav, OutputFormat.WAV)

            assertTrue("export produced no file", wav.exists() && wav.length() > 0)
            assertEquals("RIFF", String(wav.readBytes().copyOfRange(0, 4), Charsets.US_ASCII))
        } finally {
            wav.delete()
        }
    }

    /**
     * Ogg/Opus output, the third container the router claims for Media3.
     *
     * Asserted by magic bytes rather than `MediaExtractor`: platform extractor support for raw
     * Ogg is inconsistent across the API levels in the CI matrix, and "OggS" is unambiguous.
     */
    @Test
    fun opusExportWritesAnOggHeader(): Unit = runBlocking {
        val ogg = File(context.cacheDir, "out_audio.opus")
        ogg.delete()
        try {
            engine.transcode(Uri.fromFile(input), ogg, OutputFormat.OPUS)

            assertTrue("export produced no file", ogg.exists() && ogg.length() > 0)
            assertEquals("OggS", String(ogg.readBytes().copyOfRange(0, 4), Charsets.US_ASCII))
        } finally {
            ogg.delete()
        }
    }

    /**
     * Regression guard for the Transformer threading trap.
     *
     * Transformer binds to the Looper of the thread that built it, falling back to the
     * main Looper when that thread has none — and then throws IllegalStateException
     * when start() is called from elsewhere. A WorkManager Worker runs on exactly such
     * a Looper-less thread, so this test drives the engine from one to prove the
     * HandlerThread indirection holds before any of that lands in Phase 2.
     */
    @Test
    fun runsFromAThreadWithNoLooper() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val task = pool.submit<Throwable?> {
                check(android.os.Looper.myLooper() == null) {
                    "precondition failed: this thread should have no Looper"
                }
                runCatching {
                    runBlocking { engine.transcode(Uri.fromFile(input), output) }
                }.exceptionOrNull()
            }
            val failure = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(
                "transcode from a Looper-less thread failed: $failure",
                failure == null,
            )
            assertTrue(output.exists() && output.length() > 0)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Transformer.start() failing synchronously.
     *
     * Previously written off as Media3-internal and unreachable. It is not: an output
     * path whose parent directory does not exist makes start() fail, and the engine has
     * to surface that as a rejected suspension rather than hanging forever waiting for
     * a listener callback that will never come. A hang here would be far worse than an
     * exception, because the worker would sit holding a foreground service.
     */
    @Test
    fun anUnwritableOutputPathFailsInsteadOfHanging() {
        val impossible = File("/does/not/exist/nested/out.mp4")
        val failure = runCatching {
            runBlocking {
                withTimeout(30_000) {
                    engine.transcode(Uri.fromFile(input), impossible, OutputFormat.MP4_H265) {}
                }
            }
        }.exceptionOrNull()

        assertTrue(
            "an unwritable output must raise, not hang or silently pass; got $failure",
            failure != null && failure !is kotlinx.coroutines.TimeoutCancellationException,
        )
    }

    private fun durationMsOf(file: File): Long {
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

    private fun trackMimeTypesOf(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally {
            extractor.release()
        }
    }

    private fun videoMimeTypeOf(file: File): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) return mime
            }
            return null
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
    }
}
