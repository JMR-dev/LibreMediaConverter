package org.libremediaconverter.bench

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.codec.AndroidDeviceCodecs
import org.libremediaconverter.convert.Media3Engine
import org.libremediaconverter.convert.MediaProbe
import org.libremediaconverter.ffmpeg.FFmpegEngine
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.ConversionRouter
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.VideoCodec
import java.io.File

/**
 * Measures the two claims that motivate the whole architecture, against real media.
 *
 * **This is a benchmark, not part of the automated suite.** It needs real, long-form
 * media that is not committed to the repository, so it skips unless someone stages
 * files deliberately. Do not read a passing run of the test suite as evidence these
 * numbers still hold — correctness lives in the tests that ship their own fixtures.
 *
 * Not a correctness test — the assertions are deliberately loose. These exist to
 * produce numbers for two decisions that were otherwise taken on faith:
 *
 *  1. that the hardware path is worth having a second engine for at all, and
 *  2. that x264's CRF is worth the GPL licence the app carries for it.
 *
 * Skips itself when the sample files are absent, so it is harmless in CI. Populate with:
 *   adb push <file>.mp4 /sdcard/Android/data/org.libremediaconverter/files/
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RealMediaBenchmark {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Internal storage, not the external files dir.
     *
     * Files placed in the external dir by `adb push` or `adb shell cp` stay owned by
     * the shell user, and the app then gets EACCES trying to read them — which
     * presents as an unparseable input rather than a permission problem. Piping
     * through `run-as` writes as the app's own uid, so ownership is unambiguous.
     */
    private val samples: File get() = context.filesDir

    private fun sample(name: String): File? = File(samples, name).takeIf { it.exists() && it.length() > 0 }

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

    /** Records what this device can actually do, so the numbers below have context. */
    @Test
    fun reportDeviceEncoderCapabilities() {
        val codecs = AndroidDeviceCodecs.get()
        val summary = VideoCodec.entries
            .filter { it != VideoCodec.NONE }
            .joinToString(", ") { "${it.name}=${codecs.canEncode(it)}" }
        Log.i(TAG, "BENCH hardware-encoders: ${codecs.hardwareEncoders()}")
        Log.i(TAG, "BENCH can-encode: $summary")
    }

    @Test
    fun hardwareVersusSoftwareOnRealVideo(): Unit = runBlocking {
        val input = sample(H264_SAMPLE)
        assumeTrue("$H264_SAMPLE not present; skipping", input != null)
        input!!

        val sourceMs = durationMs(input)
        val uri = Uri.fromFile(input)
        Log.i(TAG, "BENCH source: ${input.name} ${input.length() / 1_000_000}MB ${sourceMs}ms")

        // --- hardware, via Media3 -------------------------------------------
        // Not every real file survives this path. This source is H.264 High 4:4:4
        // Predictive (avc1.F4001F), which neither the hardware decoder nor Android's
        // software c2.google.avc.decoder supports, so Media3 cannot read it at all.
        // Record that rather than failing: it is exactly why the FFmpeg fallback exists.
        val hwOut = File(context.cacheDir, "bench_hw.mp4").apply { delete() }
        val engine = Media3Engine(context)
        val hwMs = try {
            runCatching { timed { engine.transcode(uri, hwOut, ConversionRequest(OutputFormat.MP4_H265.spec)) } }
                .onFailure { Log.w(TAG, "BENCH hardware: UNSUPPORTED (${it.message})") }
                .getOrNull()
        } finally {
            engine.close()
        }

        // --- software, via FFmpeg + x264 CRF --------------------------------
        val swOut = File(context.cacheDir, "bench_sw.mp4").apply { delete() }
        val swMs = timed {
            FFmpegEngine().run(
                request = ConversionRequest(
                    spec = OutputFormat.MP4_H264.spec,
                    quality = QualityTier.BEST,
                ),
                inputPath = input.absolutePath,
                output = swOut,
                durationMs = sourceMs,
            )
        }

        if (hwMs != null) {
            Log.i(
                TAG,
                "BENCH hardware: ${hwMs}ms -> ${hwOut.length() / 1_000_000}MB " +
                    "(${"%.1f".format(sourceMs.toDouble() / hwMs)}x realtime)",
            )
        }
        Log.i(
            TAG,
            "BENCH software: ${swMs}ms -> ${swOut.length() / 1_000_000}MB " +
                "(${"%.2f".format(sourceMs.toDouble() / swMs)}x realtime)",
        )
        if (hwMs != null) {
            Log.i(TAG, "BENCH speedup: ${"%.1f".format(swMs.toDouble() / hwMs)}x")
        }

        hwOut.delete()
        swOut.delete()
    }

    /**
     * AV1 input is the sharpest routing case: Transformer cannot fall back to a
     * software decoder, so a device without hardware AV1 decode must go to FFmpeg.
     */
    @Test
    fun av1InputRoutesAccordingToDeviceDecodeSupport(): Unit = runBlocking {
        val input = sample(AV1_SAMPLE)
        assumeTrue("$AV1_SAMPLE not present; skipping", input != null)
        input!!

        val probe = MediaProbe.probe(context, Uri.fromFile(input))
        val decision = ConversionRouter.route(
            ConversionRequest(spec = OutputFormat.MP4_H265.spec, probe = probe),
            AndroidDeviceCodecs.get(),
        )
        Log.i(TAG, "BENCH av1 probe: codec=${probe.videoCodec} duration=${probe.durationMs}ms")
        Log.i(TAG, "BENCH av1 route: ${decision.engine} (${decision.reason})")

        val out = File(context.cacheDir, "bench_av1_out.mp4").apply { delete() }
        val engine = Media3Engine(context)
        val ms = try {
            timed { engine.transcode(Uri.fromFile(input), out, ConversionRequest(OutputFormat.MP4_H265.spec)) }
        } finally {
            engine.close()
        }
        Log.i(
            TAG,
            "BENCH av1 transcode: ${ms}ms -> ${out.length() / 1_000_000}MB " +
                "(${"%.1f".format(probe.durationMs.toDouble() / ms)}x realtime)",
        )
        out.delete()
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }

    private companion object {
        const val TAG = "RealMediaBenchmark"
        const val H264_SAMPLE = "bench_h264_720p.mp4"
        const val AV1_SAMPLE = "bench_av1_1080p.mp4"
    }
}
