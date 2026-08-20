package dev.jasonmross.mediaconverter.convert

import android.content.Context
import android.net.Uri
import dev.jasonmross.mediaconverter.ffmpeg.FFmpegEngine
import dev.jasonmross.mediaconverter.model.ConversionRequest
import dev.jasonmross.mediaconverter.model.DeviceCodecs
import dev.jasonmross.mediaconverter.codec.AndroidDeviceCodecs
import java.io.File

/** The hardware conversion path. Implemented by [Media3Engine]. */
interface HardwareTranscoder : AutoCloseable {
    suspend fun transcode(
        input: Uri,
        output: File,
        videoMimeType: String = androidx.media3.common.MimeTypes.VIDEO_H265,
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
object ConversionDependencies {

    @Volatile
    var hardware: (Context) -> HardwareTranscoder = { Media3Engine(it) }

    @Volatile
    var software: () -> SoftwareTranscoder = { FFmpegEngine() }

    @Volatile
    var publisher: (Context) -> OutputPublisher = { OutputPublisher(it) }

    @Volatile
    var deviceCodecs: () -> DeviceCodecs = { AndroidDeviceCodecs.get() }

    fun reset() {
        hardware = { Media3Engine(it) }
        software = { FFmpegEngine() }
        publisher = { OutputPublisher(it) }
        deviceCodecs = { AndroidDeviceCodecs.get() }
    }
}
