package org.libremediaconverter.fallback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.work.ConversionWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The runtime fallback, driven by a file Media3 genuinely cannot decode.
 *
 * The fixture is H.264 **High 4:4:4 Predictive**. Hardware AVC decoders implement High
 * 4:2:0, and Android's software `c2.google.avc.decoder` does not cover 4:4:4 either, so
 * Media3 fails partway through the export on every device.
 *
 * The static routing rules cannot predict that: the container is MP4, the codec reports
 * as "h264", and the device advertises AVC decode and encode. Everything looks viable
 * until the codec is configured. This is the one case that proves the runtime fallback
 * works, so it deliberately uses a **committed fixture** rather than media staged by
 * hand — a regression test that silently skips is worse than no test, because the count
 * still reads as coverage.
 *
 * The fixture was produced with x264, which the host toolchain cannot do (Fedora's
 * ffmpeg ships openh264, which is Constrained Baseline only):
 *
 *   ffmpeg -f lavfi -i testsrc=duration=3:size=320x240:rate=15 \
 *          -f lavfi -i sine=frequency=440:duration=3 \
 *          -c:v libx264 -profile:v high444 -pix_fmt yuv444p -preset ultrafast \
 *          -c:a aac -shortest sample_h264_444.mp4
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HardwareFallbackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)
    private lateinit var input: File

    @Before
    fun setUp() {
        input = File(context.cacheDir, SAMPLE)
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(SAMPLE)
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
    }

    @After
    fun tearDown() {
        input.delete()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun aFileMedia3CannotDecodeStillConvertsViaFfmpeg(): Unit = runBlocking {
        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = SAMPLE,
            sizeBytes = input.length(),
            format = OutputFormat.MP4_H265,
            // Fast deliberately: this is the tier the router sends to Media3, so it is
            // the tier where the fallback has to rescue the conversion.
            quality = QualityTier.FAST,
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        val error = terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)
        assertEquals(
            "a file Media3 cannot decode must still convert, but failed with: $error",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )

        val out = File(terminal!!.outputData.getString(ConversionWorker.KEY_OUTPUT_PATH)!!)
        assertTrue("no output produced", out.exists() && out.length() > 0)
        out.delete()
    }

    private companion object {
        const val SAMPLE = "sample_h264_444.mp4"
        const val TIMEOUT_MS = 600_000L
    }
}
