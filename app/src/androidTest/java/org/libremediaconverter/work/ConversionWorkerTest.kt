package org.libremediaconverter.work

import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.libremediaconverter.codec.AndroidDeviceCodecs
import org.libremediaconverter.model.Engine
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.model.QualityTier
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
 * Exercises the real WorkManager path, not a test double.
 *
 * The point is to cover what a fake worker runner would skip: `setForeground` with a
 * foreground service type, on a device whose API level actually enforces the rules.
 * Declaring the wrong type — or forgetting to declare it on WorkManager's
 * SystemForegroundService in the manifest — fails here rather than in production.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ConversionWorkerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager = WorkManager.getInstance(context)
    private lateinit var input: File

    @Before
    fun setUp() {
        input = File(context.cacheDir, "worker_sample.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264.mp4")
            .use { asset -> input.outputStream().use { asset.copyTo(it) } }
    }

    @After
    fun tearDown() {
        input.delete()
        File(context.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    /**
     * Deliberately derives the expectation from the running API rather than pinning a
     * value, so the same test is meaningful on an API 33, 34 or 35+ device. The three
     * regimes are the whole reason ConversionForegroundType exists.
     */
    @Test
    fun foregroundTypeMatchesTheRunningApiLevel() {
        val expected = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        assertEquals(expected, ConversionForegroundType.current())
    }

    @Test
    fun runsAConversionThroughWorkManager(): Unit = runBlocking {
        // H.264 rather than the H.265 default: on a device with no hardware encoder
        // this runs in software, and AVC is markedly quicker than HEVC there. The point
        // of this test is the WorkManager round trip, not the codec.
        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = "worker_sample.mp4",
            sizeBytes = input.length(),
            spec = OutputFormat.MP4_H264.spec,
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { info ->
                info != null && info.state.isFinished
            }
        }

        assertEquals(
            "worker did not succeed: ${terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)}",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )

        val path = terminal?.outputData?.getString(ConversionWorker.KEY_OUTPUT_PATH)
        assertTrue("no output path in result", path != null)
        val output = File(path!!)
        assertTrue("output file missing", output.exists())
        assertTrue("output file empty", output.length() > 0)
    }

    /**
     * The whole point of the router, end to end.
     *
     * MP3 cannot be produced by Media3 on any Android version, so this asserts both
     * that the job completes and that it was actually carried out by FFmpeg. Checking
     * only that a file appeared would pass even if the routing were broken.
     */
    @Test
    fun routesAnMp3JobToFfmpegAndProducesAFile(): Unit = runBlocking {
        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = "worker_sample.mp4",
            sizeBytes = input.length(),
            spec = OutputFormat.MP3.spec,
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        assertEquals(
            "mp3 job did not succeed: ${terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)}",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )
        assertEquals(
            Engine.FFMPEG.name,
            terminal?.outputData?.getString(ConversionWorker.KEY_ENGINE_USED),
        )
        val output = File(terminal!!.outputData.getString(ConversionWorker.KEY_OUTPUT_PATH)!!)
        assertTrue("no mp3 written", output.exists() && output.length() > 0)
        assertTrue("wrong extension: ${output.name}", output.name.endsWith(".mp3"))
    }

    /**
     * A Fast MP4 job takes the hardware path *when the device has one*.
     *
     * The expectation is derived from the device rather than assumed. Emulators
     * typically expose only `c2.android.*` software codecs, which the capability probe
     * correctly rejects as non-hardware, so the same job legitimately routes to FFmpeg
     * there. Asserting MEDIA3 unconditionally tests the test machine, not the router.
     */
    @Test
    fun routesAFastMp4JobByDeviceCapability(): Unit = runBlocking {
        val hasHardwareHevc = AndroidDeviceCodecs.get().canEncode(VideoCodec.H265)

        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = "worker_sample.mp4",
            sizeBytes = input.length(),
            spec = OutputFormat.MP4_H265.spec,
            quality = QualityTier.FAST,
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        assertEquals(
            "job did not succeed: ${terminal?.outputData?.getString(ConversionWorker.KEY_ERROR)}",
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )
        assertEquals(
            if (hasHardwareHevc) "hardware HEVC present, expected the Media3 path"
            else "no hardware HEVC encoder, expected the FFmpeg path",
            if (hasHardwareHevc) Engine.MEDIA3.name else Engine.FFMPEG.name,
            terminal?.outputData?.getString(ConversionWorker.KEY_ENGINE_USED),
        )
    }

    /** The quality tier is the reason the shipped binary is GPL, so verify it routes. */
    @Test
    fun routesABestQualityJobToFfmpeg(): Unit = runBlocking {
        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = "worker_sample.mp4",
            sizeBytes = input.length(),
            spec = OutputFormat.MP4_H264.spec,
            quality = QualityTier.BEST,
        )
        workManager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
        }

        assertEquals(WorkInfo.State.SUCCEEDED, terminal?.state)
        assertEquals(
            Engine.FFMPEG.name,
            terminal?.outputData?.getString(ConversionWorker.KEY_ENGINE_USED),
        )
    }

    @Test
    fun outputNameTakesTheExtensionOfTheChosenFormat() {
        assertEquals(
            "clip_converted.mp3",
            ConversionWorker.outputNameFor("clip.mp4", OutputFormat.MP3.spec),
        )
        assertEquals(
            "clip_converted.gif",
            ConversionWorker.outputNameFor("clip.mov", OutputFormat.GIF.spec),
        )
        assertEquals(
            "clip_converted.mkv",
            ConversionWorker.outputNameFor("clip", OutputFormat.MKV_H264.spec),
        )
    }

    /**
     * The extension follows the container and whether a video track survives.
     *
     * It used to be a literal on each preset, so the only names reachable were the ones somebody
     * had enumerated. Matroska without video is `.mka` and MP4 without video is `.m4a` — neither
     * had a preset before, and both are now one tap away in the Advanced picker.
     */
    @Test
    fun outputNameDistinguishesAudioOnlyVariantsOfAContainer() {
        assertEquals(
            "clip_converted.mka",
            ConversionWorker.outputNameFor(
                "clip.mkv",
                OutputSpec(Container.MKV, VideoCodec.NONE, AudioCodec.FLAC),
            ),
        )
        assertEquals(
            "clip_converted.m4a",
            ConversionWorker.outputNameFor(
                "clip.mp4",
                OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.AAC),
            ),
        )
        assertEquals(
            "clip_converted.mkv",
            ConversionWorker.outputNameFor(
                "clip.mp4",
                OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY),
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
