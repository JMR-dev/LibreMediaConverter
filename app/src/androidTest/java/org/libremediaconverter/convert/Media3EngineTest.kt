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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
            videoMimeType = MimeTypes.VIDEO_H265,
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
                    engine.transcode(Uri.fromFile(input), impossible, MimeTypes.VIDEO_H265) {}
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
