package org.libremediaconverter.convert

import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.InputKind
import org.libremediaconverter.model.InputProbe
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * That a failed native load is reported, not thrown.
 *
 * The JVM is the only place this is reachable: there are no `.so` files here by
 * construction, which is exactly the shape a corrupted install or an ABI mismatch has on a
 * device. So the condition that cannot be provoked on working hardware is free here, and
 * these tests are the only ones that can exercise it at all.
 */
@RunWith(RobolectricTestRunner::class)
class MediaProbeNativeLoadTest {

    /**
     * What the boundary actually throws, pinned against the library rather than assumed.
     *
     * This is the test that justifies the shape of the guard, and it contradicts the obvious
     * guess. `NativeLoader.loadLibrary` catches `UnsatisfiedLinkError` from
     * `System.loadLibrary` and rethrows `java.lang.Error(message, cause)` — so
     * `UnsatisfiedLinkError` never escapes, and because a bare `Error` *is* an `Error`, JLS
     * 12.4.2 propagates it out of the static initialiser unwrapped rather than boxing it in
     * `ExceptionInInitializerError`. Catching either of those two named types would catch
     * nothing at all.
     *
     * The second touch of the class is a different type again — `NoClassDefFoundError`, the
     * JVM's own "this class already failed to initialise" — so a guard written for one shape
     * lets the other through. Both are asserted, in whichever order this classloader reaches
     * them.
     */
    @Test
    fun `loading FFmpegKit without its native library throws an Error, not an Exception`() {
        val thrown: Throwable? = runCatching { FFmpegKitConfig.getLogLevel() }.exceptionOrNull()

        // The whole defect in one assertion: `catch (e: Exception)` could never have seen this.
        assertTrue(
            "expected the native load to fail with something no catch (e: Exception) can see, got $thrown",
            thrown !is Exception,
        )
        assertTrue("expected an Error, got $thrown", thrown is Error)
        val error = thrown as Error
        // Either the first touch (bare Error wrapping UnsatisfiedLinkError) or a later one
        // (NoClassDefFoundError). Both are native-load failures; neither is a VirtualMachineError.
        assertTrue(
            "expected a bare Error caused by UnsatisfiedLinkError or a NoClassDefFoundError, got $error",
            error is NoClassDefFoundError || error.cause is UnsatisfiedLinkError,
        )
    }

    /**
     * The defect itself: picking a file must not die because FFprobe could not start.
     *
     * `probeWithFFprobe` guarded its call with `catch (e: Exception)`, which an `Error` walks
     * straight through. With neither probe able to read the file, the designed answer is the
     * unparseable probe — "nothing could read it, route it to FFmpeg" — not a throw.
     */
    @Test
    fun `probe reports an unreadable input instead of throwing when FFprobe cannot start`() {
        val probe = MediaProbe.probe(RuntimeEnvironment.getApplication(), CONTENT_URI)

        assertEquals(InputKind.UNPARSEABLE, probe.kind)
        assertEquals(InputProbe.UNPARSEABLE, probe.videoCodec)
    }

    /**
     * The second call takes the other branch — `NoClassDefFoundError` rather than the bare
     * `Error` — so a guard that covered only the first shape would still crash every pick
     * after the first one.
     */
    @Test
    fun `a second probe is guarded too, though the JVM throws a different Error by then`() {
        val first = MediaProbe.probe(RuntimeEnvironment.getApplication(), CONTENT_URI)
        val second = MediaProbe.probe(RuntimeEnvironment.getApplication(), CONTENT_URI)

        assertEquals(InputKind.UNPARSEABLE, first.kind)
        assertEquals(InputKind.UNPARSEABLE, second.kind)
    }

    private companion object {
        /** `content://` so the probe takes the SAF branch, which is what a real pick does. */
        val CONTENT_URI: Uri = Uri.parse("content://test/holiday.mp4")
    }
}
