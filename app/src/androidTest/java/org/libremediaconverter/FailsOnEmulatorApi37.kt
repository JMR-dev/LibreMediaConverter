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
 *
 * **How many tests carry it is committed below**, as [FAILS_ON_EMULATOR_API37_BASELINE], and the
 * advisory job checks the run against it. Adding or removing a marker means changing that number
 * in the same diff.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class FailsOnEmulatorApi37

/**
 * How many tests carry [FailsOnEmulatorApi37] — the advisory API 37 job's committed baseline.
 *
 * **No Kotlin reads this, and it is not stray config.** `.github/scripts/e2e-report-shape.sh`
 * parses it out of this file by name, and the advisory job compares the run it just did against
 * it: this many tests should start, and all of them should fail. Deleting it makes that
 * comparison silently stop happening — the report keeps printing, with nothing to compare to.
 *
 * **One number, both checks, and that is what the marker means.** A test carrying it cannot pass
 * on this image, so the count is simultaneously how many the advisory leg runs and how many fail.
 * A *smaller* failure count is the interesting direction: it means one of them now passes, which
 * is the trigger the KDoc above names for deleting the annotation.
 *
 * So: adding or removing a [FailsOnEmulatorApi37] means changing this number, in this file, in
 * the same diff. The report says so on the run itself if you forget — it prints the tree's own
 * `grep` count beside this one.
 *
 * Why a baseline at all (#83): that job is `continue-on-error` and red on every PR by design, so
 * a red X cannot distinguish the known failures from the known failures plus a new one. Counting
 * failures alone does not fix it either — the run is usually truncated by an
 * `INSTRUMENTATION_ABORTED`, so the count is a number taken from a partial run. The report
 * records the truncation next to the counts for that reason.
 */
const val FAILS_ON_EMULATOR_API37_BASELINE = 3
