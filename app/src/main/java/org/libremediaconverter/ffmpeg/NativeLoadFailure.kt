package org.libremediaconverter.ffmpeg

/**
 * Whether [error] is FFmpegKit failing to load its native library, rather than this JVM
 * being in trouble.
 *
 * ## Why a predicate rather than a catch clause
 *
 * `config/detekt/detekt.yml` turns `TooGenericExceptionCaught` off with a written argument:
 * the engine boundaries sit in front of native code whose failure types are undocumented,
 * "enumerating it would mean guessing, and a guess that is wrong crashes the app on a file
 * it could have simply reported as unreadable." That argument is about *exceptions*, and it
 * applies unchanged one level up — except that on the `Error` side the opposite mistake is
 * available too. `catch (Throwable)` at a boundary that spawns a native process would
 * swallow a genuine [OutOfMemoryError] and let the app carry on pretending it had merely
 * met an unreadable file.
 *
 * So this names the failure instead of the catch clause. Everything it does not recognise is
 * rethrown.
 *
 * ## What the boundary actually throws
 *
 * Read off the shipped AAR and confirmed by `MediaProbeNativeLoadTest`, because all three of
 * the obvious guesses are wrong:
 *
 * - `NativeLoader.loadLibrary` catches the `UnsatisfiedLinkError` that `System.loadLibrary`
 *   raises and rethrows `java.lang.Error(message, cause)` — a **bare** `Error`, which is
 *   neither an `Exception` nor a [LinkageError]. `catch (e: UnsatisfiedLinkError)` sees
 *   nothing. Its `cause` is the original `UnsatisfiedLinkError`, which is what identifies it
 *   here; matching on the message would be matching on a format string.
 * - That throw happens under `FFmpegKitConfig.<clinit>`, so what a caller sees also depends
 *   on how the runtime treats an initializer that fails: observed as
 *   `ExceptionInInitializerError` on the JVM under Robolectric, and recorded as the bare
 *   `Error` in `docs/defect-audit.md`. Both shapes are handled rather than either being
 *   assumed.
 * - Every touch **after** the first is a third type again — `NoClassDefFoundError: Could not
 *   initialize class …`, the JVM's own record that the class is poisoned. A guard written
 *   for the first shape alone would let the second pick onwards crash, which is the harder
 *   half to notice.
 *
 * All of the class-loading shapes are [LinkageError]s, and none of the errors that mean this
 * JVM is failing — [OutOfMemoryError], `StackOverflowError`, the rest of
 * `VirtualMachineError` — is one. That disjointness is what makes this narrow rather than a
 * blanket `catch (Throwable)`.
 *
 * ## When it can fire
 *
 * Not on a healthy install: the `.so` files ship in the APK. A corrupted install or an ABI
 * mismatch is the realistic device path, and the JVM unit tests are the other, where the
 * libraries are absent by construction.
 */
internal fun isNativeLoadFailure(error: Error): Boolean = when {
    // NoClassDefFoundError, ExceptionInInitializerError, UnsatisfiedLinkError: the JVM's
    // whole vocabulary for "the code could not be loaded".
    error is LinkageError -> true
    // FFmpegKit's own bare java.lang.Error, identified by what it wraps.
    error.cause is UnsatisfiedLinkError -> true
    else -> false
}
