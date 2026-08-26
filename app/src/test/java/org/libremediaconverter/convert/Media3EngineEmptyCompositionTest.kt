package org.libremediaconverter.convert

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.AudioPlan
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.CopyPlanner
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.model.VideoPlan
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CancellationException

/**
 * What happens when Media3 refuses the export before it starts.
 *
 * `EditedMediaItem.Builder` rejects a composition with both tracks removed —
 * checkState("Audio and video cannot both be removed") — and [Media3Engine] builds it on its own
 * HandlerThread. That build used to sit *between* two narrow `runCatching` blocks, one around
 * `buildTransformer` and one around `start`, so the exception escaped `handler.post`'s body: it
 * reached the thread's uncaught handler, which on Android takes the process down, and the
 * continuation was left unresumed either way.
 *
 * Robolectric runs the real [android.os.HandlerThread] and the real Media3 builders, so the whole
 * sequence happens here — the engine really posts, really builds, and really throws. What it cannot
 * reproduce is the *consequence* of an escaped throw: a JVM background thread dying is not process
 * death. So the assertion is on the half that is observable everywhere and is the half that
 * matters to the user — the suspension is resolved, with the reason, rather than left hanging.
 * `Media3EngineTest.aPlanThatRemovesBothTracksFailsInsteadOfKillingTheProcess` is the same case on
 * a device.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class Media3EngineEmptyCompositionTest {

    @Test
    fun `a plan that removes both tracks fails the job instead of escaping the handler thread`() {
        val context = RuntimeEnvironment.getApplication()
        val engine = Media3Engine(context)
        val request = ConversionRequest(
            spec = OutputSpec(Container.MP4, VideoCodec.H265, AudioCodec.NONE),
            probe = InputProbe(
                videoCodec = null,
                audioCodec = "mp3",
                hasVideo = false,
                container = Container.MP3,
                kind = InputKind.AUDIO_ONLY,
            ),
        )

        // Asserted rather than assumed: ConversionRequest's default probe says hasVideo = true, and
        // with it this same spec plans to (Encode, Drop), nothing throws, and the test would pass
        // over a code path it never entered.
        val plan = CopyPlanner.plan(request.spec, request.probe)
        assertEquals(VideoPlan.Drop, plan.video)
        assertEquals(AudioPlan.Drop, plan.audio)

        val failure = try {
            runCatching {
                runBlocking {
                    withTimeout(TIMEOUT_MS) {
                        engine.transcode(Uri.parse("file:///dev/null"), File(context.cacheDir, "empty.mp4"), request) {}
                    }
                }
            }.exceptionOrNull()
        } finally {
            engine.close()
        }

        // Both halves are load-bearing, and the second is not pedantry: withTimeout raises
        // TimeoutCancellationException, and `java.util.concurrent.CancellationException` *extends*
        // IllegalStateException — so testing only the first would call an unresumed continuation a
        // pass. This assertion was written that way, and the mutation is what found it.
        assertFalse(
            "the continuation was never resumed — the failure escaped instead of being reported: $failure",
            failure is CancellationException,
        )
        assertTrue(
            "the builder's refusal must surface as a failed job; got $failure",
            failure is IllegalStateException,
        )
    }

    private companion object {
        /**
         * Short on purpose. Nothing is decoded, encoded or muxed on this path — the builder refuses
         * the input outright — so anything approaching this is a hang, which is the failure mode
         * this test is looking for.
         */
        const val TIMEOUT_MS = 10_000L
    }
}
