package org.libremediaconverter.work

import android.content.Context
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.convert.SoftwareTranscoder
import org.libremediaconverter.model.ConversionRequest
import java.io.File

/**
 * Scaffolding more than one worker test needs.
 *
 * Only that. The stubs a test uses to force *its own* failure stay in that test, next to the
 * assertion they serve.
 */

/**
 * A real [OutputPublisher] that never refuses on space.
 *
 * The space check reads the host's free disk, which has nothing to do with what any of these tests
 * are about and would make them pass or fail on how full the machine is. Where staging lives, and
 * the delete, stay the production implementation — the assertions are about the real filesystem.
 */
open class AlwaysRoomPublisher(context: Context) : OutputPublisher(context) {
    override fun hasSpaceFor(bytes: Long): Boolean = true
}

/**
 * An engine that writes the output file and nothing else.
 *
 * Enough for the tests that ask *where* a conversion put its result and *what it called it*; what
 * the bytes are is never the question there.
 */
object WritingTranscoder : SoftwareTranscoder {
    override suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit,
    ) {
        output.writeBytes(ByteArray(OUTPUT_BYTES))
    }

    private const val OUTPUT_BYTES = 512
}
