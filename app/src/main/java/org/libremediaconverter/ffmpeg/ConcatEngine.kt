package org.libremediaconverter.ffmpeg

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import org.libremediaconverter.convert.ConcatJoiner
import org.libremediaconverter.convert.MediaProbe
import org.libremediaconverter.convert.StagingNames
import org.libremediaconverter.model.ConcatPlanner
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.model.OutputFormat
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Joins several inputs into one file.
 *
 * Picks between a stream copy and a full re-encode by inspecting the inputs, because
 * the `concat` demuxer requires matching codec, resolution and timebase and does not
 * reliably fail when they differ — it can emit a file whose later segments are
 * garbled. See [ConcatPlanner].
 */
class ConcatEngine(private val context: Context) : ConcatJoiner {

    data class Result(val strategy: ConcatStrategy, val output: File)

    override suspend fun join(inputs: List<Uri>, output: File, format: OutputFormat): Result {
        require(inputs.size >= 2) { "Joining needs at least two files." }

        val paths = inputs.map { uri ->
            if (uri.scheme == "content") {
                FFmpegKitConfig.getSafParameterForRead(context, uri)
            } else {
                uri.path
            } ?: error("Could not open one of the input files.")
        }

        val strategy = ConcatPlanner.plan(inputs.map { MediaProbe.probeForConcat(context, it) })
        Log.i(TAG, "Joining ${inputs.size} files using $strategy")

        // The demuxer reads its input list from a file, which must live somewhere
        // FFmpeg can read; app cache is a real path, so it just works.
        //
        // Named after the output rather than by the constant "concat_list.txt" it used to use.
        // The constant meant any two joins running at once shared one list file, so one of them
        // read the other's inputs -- and it is why a blanket sweep of the staging directory was
        // never safe. See StagingNames.
        val listFile = File(output.parentFile, StagingNames.concatListFor(output.name)).apply {
            writeText(FFmpegConcatCommand.listFileContents(paths))
        }

        val args = FFmpegConcatCommand.build(strategy, paths, listFile, output, format)
        try {
            execute(args)
        } finally {
            listFile.delete()
        }
        return Result(strategy, output)
    }

    private suspend fun execute(args: List<String>) = suspendCancellableCoroutine { cont ->
        Log.i(TAG, "ffmpeg ${args.joinToString(" ")}")
        val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { completed ->
            val outcome = sessionOutcome(
                rc = completed.getReturnCode(),
                prefix = "Joining",
                failStackTrace = { completed.getFailStackTrace() },
                logTail = { completed.getAllLogsAsString(LOG_TAIL_LIMIT) },
            )
            when (outcome) {
                SessionOutcome.Success -> cont.resume(Unit)
                SessionOutcome.Cancelled -> cont.cancel()
                is SessionOutcome.Failed -> cont.resumeWithException(FFmpegEngine.FFmpegException(outcome.message))
            }
        }
        cont.invokeOnCancellation { FFmpegKit.cancel(session.getSessionId()) }
    }

    private companion object {
        const val TAG = "ConcatEngine"
        const val LOG_TAIL_LIMIT = 40
    }
}
