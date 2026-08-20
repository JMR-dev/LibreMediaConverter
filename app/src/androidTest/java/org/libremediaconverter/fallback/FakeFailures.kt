package org.libremediaconverter.fallback

import android.content.Context
import android.net.Uri
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.HardwareTranscoder
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.convert.SoftwareTranscoder
import org.libremediaconverter.model.ConversionRequest
import java.io.File

/**
 * Test doubles that force the failure paths.
 *
 * These exist because error handling is otherwise the least-tested code in the app: by
 * definition it only runs when something goes wrong, which is exactly what a healthy
 * test run avoids. Without a way to inject failure, the branches a user meets on a bad
 * day are the ones that were never executed.
 */
object FakeFailures {

    class ExplodingHardware(private val message: String = "hardware exploded") : HardwareTranscoder {
        var called = false
        override suspend fun transcode(
            input: Uri,
            output: File,
            videoMimeType: String,
            onProgress: (Int) -> Unit,
        ) {
            called = true
            throw IllegalStateException(message)
        }

        override fun close() = Unit
    }

    class ExplodingSoftware(private val message: String = "software exploded") : SoftwareTranscoder {
        var called = false
        override suspend fun run(
            request: ConversionRequest,
            inputPath: String,
            output: File,
            durationMs: Long,
            onProgress: (Int) -> Unit,
        ) {
            called = true
            throw IllegalStateException(message)
        }
    }

    /** Records that it ran and writes a plausible output, without doing real work. */
    class RecordingSoftware : SoftwareTranscoder {
        var called = false
        override suspend fun run(
            request: ConversionRequest,
            inputPath: String,
            output: File,
            durationMs: Long,
            onProgress: (Int) -> Unit,
        ) {
            called = true
            output.parentFile?.mkdirs()
            output.writeBytes(ByteArray(1024))
            onProgress(100)
        }
    }

    class FullDisk(context: Context) : OutputPublisher(context) {
        override fun hasSpaceFor(bytes: Long): Boolean = false
    }

    /** Restores the real implementations. Always call this from @After. */
    fun reset() = ConversionDependencies.reset()
}
