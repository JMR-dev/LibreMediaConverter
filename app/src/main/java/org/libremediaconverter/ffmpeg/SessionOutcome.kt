package org.libremediaconverter.ffmpeg

import com.arthenica.ffmpegkit.ReturnCode

/**
 * What a finished FFmpegKit session means, as a function of its return code.
 *
 * Both engines had their own copy of this `when`, twelve lines apart in two files, and the copies
 * had drifted: [FFmpegEngine] preferred the fail stack trace and fell back to the log tail, while
 * [ConcatEngine] only ever read the log tail. Neither was tested — both live inside a callback
 * handed to `FFmpegKit`, which does not run on the JVM — so the divergence was invisible.
 *
 * #203 decided to unify on the stack trace, so a join failure now carries the diagnostics a
 * conversion failure always did. The *prefix* stays per-engine: unifying the strategy must not
 * unify the sentence, since "FFmpeg failed" and "Joining failed" describe different jobs.
 */
internal sealed interface SessionOutcome {

    /** rc 0. The suspension resumes normally. */
    data object Success : SessionOutcome

    /** rc 255. The suspension is cancelled rather than failed — the user asked for this. */
    data object Cancelled : SessionOutcome

    /** Anything else, with the sentence the user is shown. */
    data class Failed(val message: String) : SessionOutcome
}

/**
 * Maps a return code onto the outcome, and builds the failure sentence when there is one.
 *
 * **The two message parts arrive as lambdas, deliberately.** `getAllLogsAsString` and
 * `getFailStackTrace` are calls onto a native session, and only the failure arm needs either. Taking
 * them by value would put both on the happy path of every successful conversion, which is a cost the
 * shape this replaced did not have — the old code read them inside the `else` branch. That is the
 * same reason [org.libremediaconverter.codec.AndroidDeviceCodecs.capabilitiesFrom] takes a
 * `Sequence`: a seam should not change what runs when.
 *
 * A null [rc] is a real input rather than a defensive one — `getReturnCode()` is nullable, and a
 * session killed before it reported anything has none. It is neither success nor cancellation, so
 * it fails, and the sentence says `null` where the number would be.
 */
internal fun sessionOutcome(
    rc: ReturnCode?,
    prefix: String,
    failStackTrace: () -> String?,
    logTail: () -> String?,
): SessionOutcome = when {
    ReturnCode.isSuccess(rc) -> SessionOutcome.Success
    ReturnCode.isCancel(rc) -> SessionOutcome.Cancelled
    else -> SessionOutcome.Failed(
        "$prefix failed (${rc?.value}): " + failStackTrace().orEmpty().ifBlank { logTail().orEmpty() },
    )
}
