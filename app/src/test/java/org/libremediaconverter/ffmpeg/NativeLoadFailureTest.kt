package org.libremediaconverter.ffmpeg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the guard draws its line.
 *
 * The whole point of naming the predicate was that "catch what a failed native load throws"
 * and "do not swallow an OutOfMemoryError in a method that spawns a native process" are two
 * requirements a catch clause cannot express together. These are that pair, written down.
 */
class NativeLoadFailureTest {

    // --- Recognised: the installation is broken, not this JVM ------------------------------

    /**
     * FFmpegKit's own shape. `NativeLoader.loadLibrary` catches the `UnsatisfiedLinkError`
     * that `System.loadLibrary` raises and rethrows a bare `java.lang.Error` wrapping it, so
     * the type carries no information and the cause is what identifies it.
     */
    @Test
    fun `a bare Error wrapping an UnsatisfiedLinkError is a native load failure`() {
        val error = Error("FFmpegKit failed to start on brand: robolectric.", UnsatisfiedLinkError("dlopen failed"))

        assertTrue(isNativeLoadFailure(error))
    }

    /** Every touch of the class after the first one, which is the easier half to miss. */
    @Test
    fun `a NoClassDefFoundError is a native load failure`() {
        val error = NoClassDefFoundError("Could not initialize class com.arthenica.ffmpegkit.FFmpegKitConfig")

        assertTrue(isNativeLoadFailure(error))
    }

    /** What the first touch looked like when the JVM wrapped the failing initializer. */
    @Test
    fun `an ExceptionInInitializerError is a native load failure`() {
        assertTrue(isNativeLoadFailure(ExceptionInInitializerError("Exception java.lang.Error: FFmpegKit failed")))
    }

    /** If a later FFmpegKit stops wrapping, the raw error is recognised on its own. */
    @Test
    fun `a plain UnsatisfiedLinkError is a native load failure`() {
        assertTrue(isNativeLoadFailure(UnsatisfiedLinkError("dlopen failed: libffmpegkit.so not found")))
    }

    // --- Not recognised: this JVM is in trouble and must be allowed to say so ---------------

    /**
     * The regression the narrow guard exists to prevent. `catch (Throwable)` here would report
     * "out of memory" to the user as "this file looks unreadable".
     */
    @Test
    fun `an OutOfMemoryError is not a native load failure`() {
        assertFalse(isNativeLoadFailure(OutOfMemoryError("Failed to allocate a 512 MB allocation")))
    }

    @Test
    fun `a StackOverflowError is not a native load failure`() {
        assertFalse(isNativeLoadFailure(StackOverflowError()))
    }

    @Test
    fun `an AssertionError is not a native load failure`() {
        assertFalse(isNativeLoadFailure(AssertionError("a broken invariant is not a broken install")))
    }

    /**
     * A bare `Error` on its own says nothing. Only the `UnsatisfiedLinkError` underneath it
     * makes it FFmpegKit's, so matching the type alone would be a blanket catch wearing a
     * predicate's clothes.
     */
    @Test
    fun `a bare Error with no cause is not a native load failure`() {
        assertFalse(isNativeLoadFailure(Error("something else went wrong")))
    }

    /** An OOM does not become catchable by acquiring a cause. */
    @Test
    fun `an OutOfMemoryError caused by something else is still not a native load failure`() {
        val error = OutOfMemoryError("Java heap space")
        error.initCause(IllegalStateException("some unrelated cause"))

        assertFalse(isNativeLoadFailure(error))
    }
}
