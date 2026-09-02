package org.libremediaconverter.convert

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.AudioPlan
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ConversionRequest
import org.libremediaconverter.model.CopyPlanner
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.model.VideoPlan
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CancellationException

/**
 * A job that reached Media3 with a container Media3 cannot mux.
 *
 * [Media3Muxers]' own KDoc names the defect this guards: *"the router claimed five containers while
 * the engine silently wrote MP4 for all of them."* `factoryFor` answers null for fourteen of the
 * app's containers, and `buildTransformer` turns that null into a failed job rather than letting
 * `Transformer` fall back to its default muxer.
 *
 * The guard had never fired. `Media3Engine$buildTransformer$3` -- the `requireNotNull` message
 * lambda -- was four lines and four branches at 0%, which is to say the entire repair for a defect
 * the codebase went to the trouble of writing down was untested. Weakening it would restore that
 * bug silently, because the wrong output is a *playable file with the wrong container*, not a crash.
 *
 * Same harness and same two disciplines as [Media3EngineEmptyCompositionTest]: assert the plan
 * really is the one the test needs before driving the engine, and rule out
 * `CancellationException` so an unresumed continuation cannot read as a pass.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class Media3MuxerGuardTest {

    @Test
    fun `a container Media3 cannot mux fails the job rather than silently writing MP4`() {
        val context = RuntimeEnvironment.getApplication()
        val engine = Media3Engine(context)
        val request = ConversionRequest(
            spec = OutputSpec(Container.WEBM, VideoCodec.VP9, AudioCodec.OPUS),
            probe = InputProbe(videoCodec = "h264", audioCodec = "aac", container = Container.MP4),
        )

        // The premise, asserted rather than assumed -- three separate ways this test could pass
        // over a path it never entered.
        val plan = CopyPlanner.plan(request.spec, request.probe)
        assertEquals("the plan has to still be WebM by the time the engine sees it", Container.WEBM, plan.container)
        assertNull("...and Media3 really has no muxer for it", Media3Muxers.factoryFor(plan.container))
        // Not the empty-composition refusal, which fires earlier and is a different test's subject.
        assertNotEquals(VideoPlan.Drop, plan.video)
        assertNotEquals(AudioPlan.Drop, plan.audio)

        val failure = try {
            runCatching {
                runBlocking {
                    withTimeout(TIMEOUT_MS) {
                        engine.transcode(Uri.parse("file:///dev/null"), File(context.cacheDir, "guard.webm"), request) {
                        }
                    }
                }
            }.exceptionOrNull()
        } finally {
            engine.close()
        }

        assertFalse(
            "the continuation was never resumed -- the refusal escaped instead of failing the job: $failure",
            failure is CancellationException,
        )
        // Type *and* message, and the message half is the load-bearing one. Replacing the
        // requireNotNull with a fallback factory does not make the export succeed here: it lets it
        // run on and fail some other way, which a bare type assertion would happily accept.
        assertTrue("expected the muxer guard to refuse the job, got $failure", failure is IllegalArgumentException)
        assertTrue(
            "the refusal has to name the container it could not mux, got: ${failure?.message}",
            failure?.message.orEmpty().contains("cannot mux") &&
                failure?.message.orEmpty().contains(Container.WEBM.name),
        )
    }

    private companion object {
        /** Nothing is decoded or muxed on this path -- the guard refuses before any of that. */
        const val TIMEOUT_MS = 10_000L
    }
}
