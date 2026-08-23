package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

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
     */
    @Test
    fun `an OutOfMemoryError is not swallowed`() {
        ConversionDependencies.probe = { _, _ -> throw OutOfMemoryError("Failed to allocate 512 MB") }

        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(INPUT)

        // The observable difference, and the reason this is asserted on state rather than on a
        // caught throwable: the probe hop is on Dispatchers.IO, so an error that escapes lands
        // on that thread's handler rather than at this call. What must not happen is the card
        // filling in with an "unreadable" verdict the app would then act on.
        val settled = settle(viewModel)
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
        val ready = awaitState(viewModel.state, "Ready with a probe") {
            it is ConversionState.Ready && it.input.probe != null
        }
        assertNull("nothing here should reach a terminal failure", (ready as? ConversionState.Failed))
        return (ready as ConversionState.Ready).input.probe
    }

    /**
     * Pumps the looper the way [awaitState] does, but for a fixed span and without requiring
     * anything to happen — here "the pick never came back" is the expected outcome, so there
     * is no predicate to wait on.
     */
    private fun settle(viewModel: ConversionViewModel): ConversionState {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_MS)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
        return viewModel.state.value
    }

    private companion object {
        val INPUT: Uri = Uri.parse("content://test/holiday.mp4")
        const val SETTLE_MS = 500L
        const val POLL_MS = 5L
    }
}
