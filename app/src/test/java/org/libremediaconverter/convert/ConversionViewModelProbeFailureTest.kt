package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * That a probe which throws leaves a screen the user can act on, not a dead coroutine.
 *
 * [MediaProbeNativeLoadTest] covers the boundary itself. This covers the other half of the
 * same defect: `onInputPicked` runs inside `viewModelScope.launch`, so anything the probe
 * throws and does not handle leaves the launch with no result at all — the file card never
 * fills in, and on a device the default handler takes the process down.
 *
 * The seam is what makes that testable. Injecting a prober that throws reproduces the
 * condition exactly, without depending on which types FFmpegKit happens to throw today.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConversionViewModelProbeFailureTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        installTestWorkManager(app, workDataOf(ConversionWorker.KEY_OUTPUT_PATH to "/dev/null"))
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    /**
     * The exact observed failure: FFmpegKit's loader rethrows a bare [Error] whose cause is
     * the `UnsatisfiedLinkError` `System.loadLibrary` raised.
     */
    @Test
    fun `a native load failure during the probe reports an unreadable file`() {
        ConversionDependencies.probe = { _, _ ->
            throw Error(
                "FFmpegKit failed to start on brand: robolectric.",
                UnsatisfiedLinkError("dlopen failed: library \"libffmpegkit.so\" not found"),
            )
        }

        val probe = pickedProbe()

        assertNotNull("the pick must finish; a thrown Error used to abandon the launch", probe)
        assertEquals(InputKind.UNPARSEABLE, probe?.kind)
        assertEquals(InputProbe.UNPARSEABLE, probe?.videoCodec)
    }

    /** Every touch after the first throws this instead, so the guard has to cover it too. */
    @Test
    fun `a NoClassDefFoundError from a poisoned class reports an unreadable file`() {
        ConversionDependencies.probe = { _, _ ->
            throw NoClassDefFoundError("Could not initialize class com.arthenica.ffmpegkit.FFmpegKitConfig")
        }

        assertEquals(InputKind.UNPARSEABLE, pickedProbe()?.kind)
    }

    /**
     * The line the guard must not cross.
     *
     * `MediaProbe` spawns a native process, so an [OutOfMemoryError] raised in it is a real
     * one about this JVM, not a report about the file. Swallowing it would turn "the device
     * is out of memory" into "this video looks unreadable" and let the app carry on in a
     * state it cannot honour — which is the regression a blanket `catch (Throwable)` would
     * have introduced, and the reason this defect was left open rather than fixed carelessly.
     *
     * **The error itself is what is asserted here, and that is what the `pickDispatcher` seam
     * bought.** With the hop hard-coded to `Dispatchers.IO` this was impossible: the throw
     * happened on a pool thread some time after this method had returned, so all a test could do
     * was infer it from a card that never filled in — which is also what a probe returning null
     * would look like. Worse, the escaped error went into kotlinx-coroutines-test's process-wide
     * collector and was rethrown at whichever `runTest` started next, which is a *different*
     * Compose class between runs of identical code. Putting the pick on [Dispatchers.Unconfined]
     * runs it inline, inside a `runTest` whose scope owns the collector's callback: the error is
     * handed to this test and consumed, rather than stored for a stranger.
     *
     * Note where it surfaces — at the end of `runTest`, not inside `onInputPicked`. `launch`
     * gives an escaped error to the handler chain and never to its caller, so nothing can catch
     * it at the call itself. This is as close as the coroutine machinery allows, and unlike the
     * old assertion it is the real [OutOfMemoryError] instance.
     */
    @Test
    fun `an OutOfMemoryError is not swallowed`() {
        ConversionDependencies.probe = { _, _ -> throw OutOfMemoryError("Failed to allocate 512 MB") }
        // Unconfined for the pick, so the whole of onInputPicked runs inline on this thread and
        // has thrown before runTest can leave the scope that has to receive the error.
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined, Dispatchers.Unconfined)

        val escaped = assertThrows(OutOfMemoryError::class.java) { runTest { viewModel.onInputPicked(INPUT) } }

        assertEquals("Failed to allocate 512 MB", escaped.message)
        // The other half of the contract, unchanged: an OOM is about the process, so the card is
        // left as it was rather than filled in with a verdict the app would then act on.
        val settled = viewModel.state.value
        // `sizeBytes = null`, not `0L`: no provider is registered for this authority, so the
        // metadata query returns nothing and the descriptor cannot be opened either. That is the
        // unknown, and it stopped being spelled the same way as "empty" -- see [InputQuery].
        assertEquals(ConversionState.Ready(InputFile(INPUT, "input", sizeBytes = null)), settled)
        assertNull("an OOM must not be reported as a probe result", (settled as ConversionState.Ready).input.probe)
    }

    /** A working probe is untouched by any of this. */
    @Test
    fun `a probe that succeeds still fills the card in`() {
        ConversionDependencies.probe = { _, _ -> InputProbe(videoCodec = "h264", kind = InputKind.VIDEO) }

        assertEquals("h264", pickedProbe()?.videoCodec)
    }

    /**
     * Drives a real pick and returns the probe the card ended up with.
     *
     * Asserting on the probe rather than merely on `Ready` is deliberate: `onInputPicked`
     * sets `Ready` *before* it probes, so a test that only checked the state would have
     * passed against the unguarded code.
     */
    private fun pickedProbe(): InputProbe? {
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(INPUT)
        // The predicate is the guard, and it is the only one needed. It requires `Ready`, so a
        // pick that ended in `Failed` never satisfies it and `awaitState` fails on its timeout
        // naming what it was waiting for -- "Ready with a probe" -- which says more than a
        // separate assertion could. A `ready as? ConversionState.Failed` check used to sit here
        // and was dead: `Ready` and `Failed` are sibling subtypes of one sealed interface, so
        // the cast was always null and the assertNull could never fire. Measured, not assumed --
        // flipping it to assertNotNull failed all three callers of this helper.
        val ready = awaitState(viewModel.state, "Ready with a probe") {
            it is ConversionState.Ready && it.input.probe != null
        }
        return (ready as ConversionState.Ready).input.probe
    }

    private companion object {
        val INPUT: Uri = Uri.parse("content://test/holiday.mp4")
    }
}
