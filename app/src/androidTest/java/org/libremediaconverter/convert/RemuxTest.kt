package org.libremediaconverter.convert

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.Engine
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.work.ConversionWorker
import java.io.File

/**
 * Remuxing end to end, on a device.
 *
 * ## Why these assert the engine, not just the file
 *
 * A remux routed to FFmpeg produces a perfectly correct file — `-c copy` moves the same samples
 * into the same container. So an output-only assertion passes whether the hardware transmux path
 * ran or never executed at all, and `COPY` belongs to none of the router's capability sets, which
 * makes "silently always FFmpeg" the most likely way for this feature to regress.
 *
 * Timing is not a usable proxy either: it is flaky on emulators and cannot distinguish an FFmpeg
 * stream copy from a Media3 transmux, since both are fast. `ConversionWorker` already reports
 * `KEY_ENGINE_USED`, so these assert that.
 *
 * ## Fixtures
 *
 * Every committed asset was ISO MP4 before this feature, so a remux had no second container to
 * move between. The new ones are generated rather than sourced, and committed rather than built at
 * test time, following the convention `HardwareFallbackTest` documents — a regression test that
 * silently skips is worse than no test. Recipes, run against FFmpeg 8.1.2:
 *
 * ```
 * # sample_h264.mkv — the existing fixture's streams in Matroska. A pure remux, so the two files
 * # hold byte-identical samples and differ only in the container, which is exactly the axis under
 * # test. No encoder is involved, so the recipe reproduces anywhere.
 * ffmpeg -i sample_h264.mp4 -c copy -f matroska sample_h264.mkv
 *
 * # sample_aac.m4a — the same fixture's audio track alone, for AUDIO_ONLY probing and MKA output.
 * ffmpeg -i sample_h264.mp4 -vn -c:a copy -f mp4 sample_aac.m4a
 *
 * # sample_vp9.webm — the one fixture that must be encoded, since no committed source is VP9.
 * ffmpeg -i sample_h264.mp4 -c:v libvpx-vp9 -crf 40 -b:v 0 -deadline realtime -cpu-used 8 \
 *        -c:a libopus -b:a 64k -f webm sample_vp9.webm
 *
 * # sample_still.png — a single frame, to reach the probe's IMAGE branch.
 * ffmpeg -i sample_h264.mp4 -frames:v 1 -f image2 sample_still.png
 * ```
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RemuxTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val staged = mutableListOf<File>()

    @Before
    fun setUp() {
        WorkManager.getInstance(context).cancelAllWork()
    }

    @After
    fun tearDown() {
        staged.forEach { it.delete() }
        WorkManager.getInstance(context).cancelAllWork()
    }

    private fun asset(name: String): File = File(context.cacheDir, name).also { file ->
        file.delete()
        staged += file
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(name)
            .use { asset -> file.outputStream().use { asset.copyTo(it) } }
    }

    // --- probing ------------------------------------------------------------

    @Test
    fun probeIdentifiesTheSourceContainerOfEachFixture() {
        assertEquals(Container.MP4, MediaProbe.probe(context, Uri.fromFile(asset("sample_h264.mp4"))).container)
        assertEquals(Container.MKV, MediaProbe.probe(context, Uri.fromFile(asset("sample_h264.mkv"))).container)
        assertEquals(Container.WEBM, MediaProbe.probe(context, Uri.fromFile(asset("sample_vp9.webm"))).container)
    }

    /**
     * The distinction the old probe could not make.
     *
     * It reported `hasVideo = true, videoCodec = UNPARSEABLE` for anything the extractor refused,
     * so an audio file, a picture and a corrupt file were indistinguishable — and the source-info
     * card cannot describe any of them honestly until they are separate.
     */
    @Test
    fun probeDistinguishesAudioFromImagesFromRubbish() {
        val audio = MediaProbe.probe(context, Uri.fromFile(asset("sample_aac.m4a")))
        assertEquals(InputKind.AUDIO_ONLY, audio.kind)
        assertEquals(false, audio.hasVideo)
        assertNotNull("audio codec should still be identified", audio.audioCodec)

        val image = MediaProbe.probe(context, Uri.fromFile(asset("sample_still.png")))
        assertEquals(InputKind.IMAGE, image.kind)

        val rubbish = File(context.cacheDir, "not_media.bin").also {
            staged += it
            it.writeBytes(ByteArray(4096) { i -> (i % 251).toByte() })
        }
        val unreadable = MediaProbe.probe(context, Uri.fromFile(rubbish))
        assertEquals(InputKind.UNPARSEABLE, unreadable.kind)
    }

    // --- the hardware remux path --------------------------------------------

    /**
     * MKV in, MP4 out, nothing re-encoded — and it must stay on Media3.
     *
     * Media3 cannot *write* Matroska but reads it perfectly well, which is what makes this
     * direction a hardware remux. If this reports FFMPEG, the transmux path is dead.
     */
    @Test
    fun mkvToMp4RemuxesOnHardware() {
        val input = asset("sample_h264.mkv")
        val result = runConversion(
            input,
            OutputSpec(Container.MP4, VideoCodec.COPY, AudioCodec.COPY),
        )

        assertEquals(Engine.MEDIA3.name, result.engineUsed)
        assertEquals("h264 must survive a copy untouched", "video/avc", videoMimeOf(result.output))
        assertTrue("output has no duration", durationMsOf(result.output) > 0)
    }

    /** The reverse direction: Media3 has no Matroska muxer, so this is FFmpeg's. */
    @Test
    fun mp4ToMkvRemuxesOnFFmpeg() {
        val input = asset("sample_h264.mp4")
        val result = runConversion(
            input,
            OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY),
        )

        assertEquals(Engine.FFMPEG.name, result.engineUsed)
        assertEquals(
            "expected an EBML header",
            listOf(0x1A, 0x45, 0xDF, 0xA3),
            result.output.readBytes().take(4).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun webmToMkvKeepsVp9WithoutReencoding() {
        val input = asset("sample_vp9.webm")
        val result = runConversion(
            input,
            OutputSpec(Container.MKV, VideoCodec.COPY, AudioCodec.COPY),
        )

        assertTrue(result.output.length() > 0)
        assertEquals("vp9 must survive a copy", "video/x-vnd.on2.vp9", videoMimeOf(result.output))
    }

    @Test
    fun audioOnlySourceRemuxesIntoMka() {
        val input = asset("sample_aac.m4a")
        val spec = OutputSpec(Container.MKV, VideoCodec.NONE, AudioCodec.COPY)
        val result = runConversion(input, spec)

        assertEquals("mka", spec.extension)
        assertTrue(result.output.length() > 0)
        val tracks = trackMimesOf(result.output)
        assertTrue("expected audio only, got $tracks", tracks.none { it.startsWith("video/") })
    }

    @Test
    fun mp4ToMpegTsAndAviProduceTheirOwnContainers() {
        val input = asset("sample_h264.mp4")

        val ts = runConversion(input, OutputSpec(Container.MPEG_TS, VideoCodec.COPY, AudioCodec.COPY))
        assertEquals(Engine.FFMPEG.name, ts.engineUsed)
        // Every MPEG-TS packet starts with the 0x47 sync byte.
        assertEquals(0x47, ts.output.readBytes().first().toInt() and 0xFF)

        val avi = runConversion(input, OutputSpec(Container.AVI, VideoCodec.COPY, AudioCodec.COPY))
        assertEquals("RIFF", String(avi.output.readBytes().copyOfRange(0, 4), Charsets.US_ASCII))
    }

    // --- plumbing -----------------------------------------------------------

    private class Result(val output: File, val engineUsed: String)

    /**
     * Runs one conversion through the real worker.
     *
     * Deliberately through `ConversionWorker` rather than an engine directly: the routing decision
     * is the thing under test, and only the worker makes it.
     */
    private fun runConversion(input: File, spec: OutputSpec): Result = runBlocking {
        val manager = WorkManager.getInstance(context)
        val request = ConversionWorker.request(
            inputUri = Uri.fromFile(input),
            displayName = input.name,
            sizeBytes = input.length(),
            spec = spec,
        )
        manager.enqueue(request).result.get()

        val terminal = withTimeout(TIMEOUT_MS) {
            manager.getWorkInfoByIdFlow(request.id).first { info ->
                info != null && info.state.isFinished
            }
        }

        assertEquals(
            "conversion did not succeed: " +
                terminal?.outputData?.getString(ConversionWorker.KEY_ERROR),
            WorkInfo.State.SUCCEEDED,
            terminal?.state,
        )
        val path = requireNotNull(terminal?.outputData?.getString(ConversionWorker.KEY_OUTPUT_PATH))
        val output = File(path).also { staged += it }
        assertTrue("output is empty", output.length() > 0)
        Result(
            output = output,
            engineUsed = terminal?.outputData
                ?.getString(ConversionWorker.KEY_ENGINE_USED).orEmpty(),
        )
    }

    private fun trackMimesOf(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally {
            extractor.release()
        }
    }

    private fun videoMimeOf(file: File): String? = trackMimesOf(file).firstOrNull { it.startsWith("video/") }

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

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
