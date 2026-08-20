package org.libremediaconverter.ffmpeg

import android.content.Context
import android.net.Uri
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import org.libremediaconverter.convert.MediaProbe
import org.libremediaconverter.model.ConcatPlanner
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.model.OutputFormat
import kotlinx.coroutines.suspendCancellableCoroutine
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
class ConcatEngine(private val context: Context) {

    data class Result(val strategy: ConcatStrategy, val output: File)

    suspend fun join(
        inputs: List<Uri>,
        output: File,
        format: OutputFormat = OutputFormat.MP4_H264,
    ): Result {
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
        val listFile = File(output.parentFile, "concat_list.txt").apply {
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
            val rc = completed.getReturnCode()
            when {
                ReturnCode.isSuccess(rc) -> cont.resume(Unit)
                ReturnCode.isCancel(rc) -> cont.cancel()
                else -> cont.resumeWithException(
                    FFmpegEngine.FFmpegException(
                        "Joining failed (${rc?.value}): " +
                            completed.getAllLogsAsString(LOG_TAIL_LIMIT).orEmpty()
                    )
                )
            }
        }
        cont.invokeOnCancellation { FFmpegKit.cancel(session.getSessionId()) }
    }

    private companion object {
        const val TAG = "ConcatEngine"
        const val LOG_TAIL_LIMIT = 40
    }
}
