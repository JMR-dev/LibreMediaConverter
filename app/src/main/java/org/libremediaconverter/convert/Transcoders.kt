package org.libremediaconverter.convert

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import org.libremediaconverter.codec.AndroidDeviceCodecs
import org.libremediaconverter.ffmpeg.FFmpegEngine
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import java.io.File

/** The hardware conversion path. Implemented by [Media3Engine]. */
interface HardwareTranscoder : AutoCloseable {
    /**
     * Takes the whole [ConversionRequest] rather than just a video MIME type.
     *
     * The narrower signature was the reason "extract audio to M4A" produced an HEVC video track:
     * the container, the audio codec and "this output has no video at all" had nowhere to travel,
     * so the engine defaulted all three. Passing the request also carries the input probe, which
     * is what `CopyPlanner` needs to decide whether a track can be transmuxed.
     */
    suspend fun transcode(
        input: Uri,
        output: File,
        request: ConversionRequest = ConversionRequest(OutputFormat.MP4_H265.spec),
        onProgress: (Int) -> Unit = {},
    )
}

/** The software conversion path. Implemented by [FFmpegEngine]. */
interface SoftwareTranscoder {
    suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit = {},
    )
}

/**
 * The seam that lets tests force failure paths.
 *
 * Workers are constructed by WorkManager, so they cannot take constructor arguments,
 * and the app deliberately carries no DI framework. This holds the few collaborators a
 * conversion needs, defaulting to the real implementations.
 *
 * Its reason for existing is coverage of the branches that only run when something goes
 * wrong. Those branches are, by definition, the ones that never execute in a healthy
 * test run — and they are also the ones a user meets on a bad day, so leaving them
 * unexercised means the error handling is the least-tested code in the app.
 *
 * Tests must call [reset] afterwards; `FakeFailures` in the androidTest source set
 * does that for them.
 *
 * Every failure branch in the conversion and join paths is now forced by a test. The
 * three that needed a seam are covered here; the rest are reachable directly, either
 * with a content URI pointing at a provider that does not exist, or by constructing a
 * worker's input Data by hand rather than through its request() helper.
 */
@UnstableApi
object ConversionDependencies {

    @Volatile
    var hardware: (Context) -> HardwareTranscoder = { Media3Engine(it) }

    @Volatile
    var software: () -> SoftwareTranscoder = { FFmpegEngine() }

    @Volatile
    var publisher: (Context) -> OutputPublisher = { OutputPublisher(it) }

    @Volatile
    var deviceCodecs: () -> DeviceCodecs = { AndroidDeviceCodecs.get() }

    /**
     * Reading an input's codecs, container and duration.
     *
     * Here for a reason the others are not, and the reason is worth recording rather than
     * just working around. [MediaProbe] spawns FFprobe, and when FFmpegKit's native library
     * cannot load its initialiser throws a bare `java.lang.Error` — which
     * `probeWithFFprobe`'s own `catch (e: Exception)` does not catch, and which
     * `ConversionViewModel.onInputPicked` does not catch either. Picking a file would then
     * fail with an uncaught error rather than the "could not read this file" the code was
     * written to give.
     *
     * **That is a latent production hazard, found here and deliberately not fixed here.**
     * It cannot fire on a device that ships the `.so` files, which is every real install,
     * so making `MediaProbe` catch `Throwable` would be a behaviour change to the pick path
     * on the strength of a condition no user meets — its own commit, with its own test.
     * What this seam does is narrower: it keeps the JVM out of that path, which is what
     * makes the ViewModel reachable from a unit test at all.
     *
     * Instrumented tests and the app itself get the real probe, exactly as before.
     */
    @Volatile
    var probe: (Context, Uri) -> InputProbe = { context, uri -> MediaProbe.probe(context, uri) }

    fun reset() {
        hardware = { Media3Engine(it) }
        software = { FFmpegEngine() }
        publisher = { OutputPublisher(it) }
        deviceCodecs = { AndroidDeviceCodecs.get() }
        probe = { context, uri -> MediaProbe.probe(context, uri) }
    }
}
