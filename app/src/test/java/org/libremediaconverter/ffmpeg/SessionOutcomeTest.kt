package org.libremediaconverter.ffmpeg

import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finished FFmpegKit session means, for both engines at once.
 *
 * `FFmpegEngine` and `ConcatEngine` each carried their own copy of this `when`, and the copies had
 * drifted: one preferred the fail stack trace and fell back to the log tail, the other only ever
 * read the log tail. Neither was tested, because both live inside a callback handed to `FFmpegKit`,
 * which does not run on the JVM — so nothing could see that the two disagreed.
 *
 * **JVM-safe, verified rather than assumed.** `javap` over the committed AAR's runtime jar shows
 * `ReturnCode(int)` as a plain public constructor with `SUCCESS`/`CANCEL` int constants and pure
 * static `isSuccess`/`isCancel`; its `<clinit>` is constant initialisation and loads no native
 * library.
 *
 * The unification is #203's decision, so the tests pin it as one: a join failure now carries the
 * stack trace a conversion failure always did, while the two prefixes stay distinct.
 */
class SessionOutcomeTest {

    @Test
    fun `a return code of zero is success`() {
        assertEquals(SessionOutcome.Success, outcome(ReturnCode(ReturnCode.SUCCESS)))
    }

    /**
     * Cancellation is a separate outcome from failure, and the distinction is the point: the engines
     * resume the continuation *cancelled* rather than exceptionally, so a user who pressed Cancel
     * does not get an error card.
     */
    @Test
    fun `a return code of 255 is a cancellation, not a failure`() {
        assertEquals(SessionOutcome.Cancelled, outcome(ReturnCode(ReturnCode.CANCEL)))
    }

    @Test
    fun `any other return code fails, and the sentence carries the number`() {
        val failed = outcome(ReturnCode(1), stackTrace = "boom") as SessionOutcome.Failed

        assertTrue("the code belongs in the message, got: ${failed.message}", failed.message.contains("(1)"))
    }

    /**
     * The half that was different between the two engines before #203, now the same in both.
     */
    @Test
    fun `the stack trace is preferred over the log tail`() {
        val failed = outcome(ReturnCode(1), stackTrace = "the real cause", logTail = "…noise…")
            as SessionOutcome.Failed

        assertTrue(failed.message.contains("the real cause"))
        assertTrue("the log tail must not be appended as well", !failed.message.contains("noise"))
    }

    @Test
    fun `a blank stack trace falls back to the log tail`() {
        val blank = outcome(ReturnCode(1), stackTrace = "   ", logTail = "the last few lines") as SessionOutcome.Failed
        val absent = outcome(ReturnCode(1), stackTrace = null, logTail = "the last few lines") as SessionOutcome.Failed

        assertTrue(blank.message.contains("the last few lines"))
        assertTrue("a null stack trace is a blank one", absent.message.contains("the last few lines"))
    }

    /**
     * Both sources empty still has to produce a sentence. A message ending in a dangling colon is
     * thin, but it is what the user gets when FFmpeg said nothing at all, and it must not be an
     * exception on the way to the screen.
     */
    @Test
    fun `a failure with nothing to say still names the code`() {
        val failed = outcome(ReturnCode(1), stackTrace = null, logTail = null) as SessionOutcome.Failed

        assertEquals("FFmpeg failed (1): ", failed.message)
    }

    /**
     * `getReturnCode()` is nullable and a session killed before it reported anything has none.
     * Neither success nor cancellation, so it fails — and the sentence says so rather than throwing.
     */
    @Test
    fun `a session with no return code at all fails`() {
        val failed = outcome(null, logTail = "whatever was logged") as SessionOutcome.Failed

        assertTrue("got: ${failed.message}", failed.message.startsWith("FFmpeg failed (null): "))
    }

    /**
     * Unifying the *strategy* must not unify the *sentence*: the two engines describe different
     * jobs, and a join that reports "FFmpeg failed" is a worse message than the one it replaced.
     */
    @Test
    fun `each engine keeps its own prefix`() {
        val join = sessionOutcome(ReturnCode(1), "Joining", { "cause" }, { null }) as SessionOutcome.Failed

        assertTrue(join.message.startsWith("Joining failed (1): "))
    }

    /**
     * Neither message source is read unless the outcome is a failure.
     *
     * They are calls onto a native session, and reading them on the happy path is work every
     * successful conversion would do for nothing — which the shape this replaced did not, since it
     * read them inside the `else` branch. That is why the parameters are lambdas, and this is what
     * would notice if they stopped being.
     */
    @Test
    fun `a session that succeeded reads neither the stack trace nor the log`() {
        var reads = 0
        fun counted(): String? {
            reads++
            return null
        }

        sessionOutcome(ReturnCode(ReturnCode.SUCCESS), "FFmpeg", ::counted, ::counted)
        sessionOutcome(ReturnCode(ReturnCode.CANCEL), "FFmpeg", ::counted, ::counted)

        assertEquals("neither source may be touched unless the session failed", 0, reads)
    }

    private fun outcome(rc: ReturnCode?, stackTrace: String? = null, logTail: String? = null) =
        sessionOutcome(rc, "FFmpeg", { stackTrace }, { logTail })
}
