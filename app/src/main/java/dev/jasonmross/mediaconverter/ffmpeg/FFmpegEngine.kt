package dev.jasonmross.mediaconverter.ffmpeg

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import dev.jasonmross.mediaconverter.model.ConversionRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Software conversion via the bundled FFmpeg.
 *
 * Handles everything Media3 structurally cannot: Matroska, MP3, GIF, frame sequences,
 * inputs with no platform decoder, and the CRF quality tier.
 *
 * Like [dev.jasonmross.mediaconverter.convert.Media3Engine], input arrives as a real
 * filesystem path and output is written to app-private cache. FFmpeg is perfectly able
 * to write through a SAF descriptor via its ffkitsaf protocol, but MP4 faststart has
 * to seek backwards to rewrite the moov atom, which a SAF descriptor does not reliably
 * support — so staging is the safe default for every format rather than a special case.
 */
class FFmpegEngine {

    init {
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)
    }

    /**
     * Runs a conversion, reporting progress 0..100.
     *
     * Progress is derived from the statistics callback's timestamp against the known
     * input duration. FFmpeg has no native notion of percentage complete, so a
     * [durationMs] of zero means progress simply cannot be reported — the caller gets
     * an indeterminate job rather than a fabricated number.
     */
    suspend fun run(
        request: ConversionRequest,
        inputPath: String,
        output: File,
        durationMs: Long,
        onProgress: (Int) -> Unit = {},
    ): Unit = suspendCancellableCoroutine { cont ->
        val args = FFmpegCommandBuilder.build(request, inputPath, output.absolutePath)
        Log.i(TAG, "ffmpeg ${args.joinToString(" ")}")

        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { completed ->
                val rc = completed.getReturnCode()
                when {
                    ReturnCode.isSuccess(rc) -> cont.resume(Unit)
                    ReturnCode.isCancel(rc) ->
                        cont.cancel()
                    else -> cont.resumeWithException(
                        FFmpegException(
                            "FFmpeg failed (${rc?.value}): " +
                                completed.getFailStackTrace().orEmpty().ifBlank {
                                    completed.getAllLogsAsString(LOG_TAIL_LIMIT).orEmpty()
                                }
                        )
                    )
                }
            },
            { log -> Log.d(TAG, log.message.trimEnd()) },
            { stats ->
                if (durationMs > 0) {
                    val percent = (stats.time / durationMs * 100).toInt().coerceIn(0, 100)
                    onProgress(percent)
                }
            },
        )

        cont.invokeOnCancellation {
            FFmpegKit.cancel(session.getSessionId())
            output.delete()
        }
    }

    class FFmpegException(message: String) : RuntimeException(message)

    private companion object {
        const val TAG = "FFmpegEngine"
        const val LOG_TAIL_LIMIT = 40
    }
}
