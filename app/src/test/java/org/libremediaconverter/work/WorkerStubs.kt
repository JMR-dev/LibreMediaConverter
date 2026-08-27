package org.libremediaconverter.work

import android.content.Context
import com.google.common.util.concurrent.ListenableFuture
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.convert.SoftwareTranscoder
import org.libremediaconverter.model.ConversionRequest
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

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
 * An [AlwaysRoomPublisher] that records the staging names it is asked for.
 *
 * For a conversion the staged file survives the job and a directory listing says everything. For a
 * join it does not: `ConcatEngine` is native, so no test here gets past it, and the catch on the way
 * out deletes what was staged. The name the worker *asked* for is then the only place its job id
 * and its output format are legible at all — the same reason `SpaceCheckTest` records the question
 * rather than the verdict.
 */
open class NamingPublisher(context: Context) : AlwaysRoomPublisher(context) {

    /** Every name passed to [createStagingFile], in order. */
    val requestedNames = mutableListOf<String>()

    /** Set to refuse every space check, the way `FakeFailures.FullDisk` does. */
    var refuseSpace = false

    override fun hasSpaceFor(bytes: Long): Boolean = !refuseSpace

    override fun createStagingFile(name: String): File {
        requestedNames += name
        return super.createStagingFile(name)
    }
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

/**
 * An already-failed future, written out rather than pulled from a futures library.
 *
 * `await()` takes the `isDone` fast path and unwraps the `ExecutionException`, which is what puts
 * the original exception in front of the worker's `catch` rather than a wrapper. That is the whole
 * mechanism behind driving a `ForegroundUpdater` to fail: `WorkForegroundUpdater` propagates
 * whatever the future failed with rather than swallowing it, so `setForeground()` throws exactly
 * what is handed here.
 *
 * Shared because two tests inject two different failures through it -- a denied foreground start
 * and a cancellation -- and Kotlin will not take two file-private top-level classes of one name in
 * one package.
 */
internal class FailedFuture(private val failure: Throwable) : ListenableFuture<Void> {
    override fun addListener(listener: Runnable, executor: Executor): Unit = executor.execute(listener)
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
    override fun isCancelled(): Boolean = false
    override fun isDone(): Boolean = true
    override fun get(): Void = throw ExecutionException(failure)
    override fun get(timeout: Long, unit: TimeUnit): Void = throw ExecutionException(failure)
}
