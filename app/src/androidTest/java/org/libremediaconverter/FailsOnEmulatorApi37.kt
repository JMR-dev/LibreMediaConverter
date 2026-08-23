package org.libremediaconverter

/**
 * Marks an instrumented test that does not pass on the `android-37.x` **emulator** system images.
 *
 * This is a marker, not a skip. Nothing reads it except CI, and CI reads it twice — once with
 * `notAnnotation` to build the gating API 37 leg, and once with `annotation` to build the advisory
 * one — so a test carrying it runs in exactly one of the two and can never fall through both.
 * That is the whole reason there is one annotation rather than a pair of test lists: two lists
 * drift, and the drift is silent in both directions (a test that runs nowhere reads as green).
 *
 * It says only what has been measured: **on the emulator, at API 37.** The same tests pass on a
 * physical Pixel 10 Pro XL at API 37 and at API 33–36 on the same runner under the same renderer,
 * so this must never be read as "this test is allowed to fail at API 37" — only as "the API 37
 * emulator image cannot currently answer this one". `docs/api-37-emulator-crash.md` has the
 * measurements and the one bullet in them that is still inference.
 *
 * Removing it is the goal, and the trigger is written down: a new API 37.x system image, or an
 * ATD image for 37. Delete the annotation from the tests, and the advisory job goes empty and
 * the gating one grows by two.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class FailsOnEmulatorApi37
