package dev.jasonmross.mediaconverter.bench

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.jasonmross.mediaconverter.model.Engine
import dev.jasonmross.mediaconverter.model.OutputFormat
import dev.jasonmross.mediaconverter.model.QualityTier
import dev.jasonmross.mediaconverter.work.ConversionWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The dynamic fallback, exercised by a real file that genuinely breaks the fast path.
 *
 * The sample is H.264 **High 4:4:4 Predictive** (`avc1.F4001F`). Hardware AVC decoders
 * implement High 4:2:0, and Android's software `c2.google.avc.decoder` does not cover
 * 4:4:4 either, so Media3 cannot decode it on any device — it fails with a codec
 * exception partway through the export.
 *
 * The static routing rules cannot predict this: the container is MP4, the codec is
 * "h264", and the device reports both AVC decode and encode. Everything looks fine
 * until the codec is actually configured. This is the case the runtime fallback exists
 * for, and until a file like this was tried on hardware it had never actually fired.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HardwareFallbackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)

    @Test
    fun aFileMedia3CannotDecodeStillConvertsViaFfmpeg(): Unit = runBlocking {
        val input = File(context.filesDir, SAMPLE).takeIf { it.exists() && it.length() > 0 }
        assumeTrue("$SAMPLE not present; skipping", input != null)

        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input!!),
            displayName = SAMPLE,
            sizeBytes = input.length(),
            format = OutputFormat.MP4_H265,
            // Fast deliberately: this is the tier that would be routed to Media3, so it
            // is the tier where the fallback has to save the conversion.
            quality = QualityTier.FAST,
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        val error = terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)
        org.junit.Assert.assertEquals(
            "a file Media3 cannot decode must still convert, but failed with: $error",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )

        val out = File(terminal!!.outputData.getString(ConversionWorker.KEY_OUTPUT_PATH)!!)
        org.junit.Assert.assertTrue("no output produced", out.exists() && out.length() > 0)

        // The routing decision still reads MEDIA3 -- that is the *static* decision, which
        // was reasonable on the information available. The recovery happened underneath
        // it at runtime, which is the point.
        android.util.Log.i(
            TAG,
            "BENCH fallback: routed=${terminal.outputData.getString(ConversionWorker.KEY_ENGINE_USED)} " +
                "output=${out.length() / 1_000_000}MB",
        )
        out.delete()
    }

    private companion object {
        const val TAG = "HardwareFallbackTest"
        const val SAMPLE = "bench_h264_720p.mp4"
        const val TIMEOUT_MS = 600_000L
    }
}
