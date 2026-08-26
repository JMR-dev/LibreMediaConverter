package org.libremediaconverter.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That a hung unit-test run still ends by itself, and still says why.
 *
 * `:app:testDebugUnitTest` had no timeout of any kind until #125 was filed. That ticket is a real
 * Java-level deadlock between Room's `TransactionExecutor` and WorkManager's `SerialExecutorImpl`,
 * reached through the WorkInfo flow the ViewModel collects, and one local run sat in it for 47
 * minutes. Nothing inside the suite could break it: the deadlock is monitor contention, which is
 * not interruptible, so it runs until something outside the JVM gives up.
 *
 * Two numbers in `app/build.gradle.kts` are what bound it now, and neither compiles, so nothing
 * else would notice their removal:
 *
 *  - `timeout.set(...)` on every `Test` task, which stops the forked test JVM.
 *  - the watchdog's `dumpAfterNanos`, which jstacks that JVM *before* the timeout kills it.
 *
 * The second is the one worth guarding hardest, and the one that most looks like stray config.
 * Gradle's timeout kills without a thread dump, and the jstack -- with its "Found one Java-level
 * deadlock" section naming both monitors -- is the only reason #125 could be described at all.
 * The ordering between the two numbers is what makes it work: dump first, kill second. Reverse
 * them, or delete the watchdog, and the suite still stops hanging but every hang from then on
 * reports as a bare "Timeout has been exceeded" with nothing to read. Measured against a probe
 * that hung one test: no test XML was written for the class that hung, so the hanging test itself
 * gets no attribution from the report at all.
 *
 * The range on the timeout is not decoration either, and it is the half a future edit is most
 * likely to get wrong. Below it, a healthy-but-slow runner trips the bound and a real signal
 * becomes noise people learn to re-run through; above it, CI's 30-minute job cap fires first and
 * the bound never gets to say anything.
 *
 * `ReleasePermissionTest` is the precedent and its caveat applies here too. This asserts the two
 * numbers are present, sanely sized and correctly ordered. It cannot assert that the timeout
 * fires -- that needs a hang, which is what the whole change exists to prevent. Refs #125.
 */
class HangBoundTest {

    @Test
    fun `every Test task is bounded, and bounded between the slow runner and the job cap`() {
        assertTrue(
            "app/build.gradle.kts sets its Test task timeout to ${timeoutMinutes}m, which is " +
                "outside $SANE_MINUTES. Under that range a slow CI runner trips a bound meant for " +
                "deadlocks -- the slowest observed passing run of the whole invocation was 90s. " +
                "Over it, the Unit tests job's own 30-minute cap kills the job first and the " +
                "timeout never reports. `null` means the line is gone or the block was rewritten, " +
                "and without it #125's deadlock has nothing to stop it: monitor contention breaks " +
                "no interrupt, so it runs until CI gives up and reports a timeout with no cause.",
            timeoutMinutes in SANE_MINUTES,
        )
    }

    @Test
    fun `the thread dump is taken before the timeout kills the JVM it would dump`() {
        assertTrue(
            "app/build.gradle.kts takes its hang thread dump after ${dumpAfterMinutes}m but times " +
                "the task out at ${timeoutMinutes}m, so the JVM is already dead when jstack runs " +
                "and every future hang reports as a bare `Timeout has been exceeded`. The dump " +
                "has to come first -- it is the only attribution a hanging test gets, since the " +
                "test XML never names it.",
            (dumpAfterMinutes ?: 0) < (timeoutMinutes ?: 0),
        )
    }

    /** Minutes given to a whole `Test` task before Gradle stops the forked JVM. */
    private val timeoutMinutes: Int?
        get() = minutesIn("""timeout\.set\(Duration\.ofMinutes\((\d+)\)\)""")

    /** Minutes the watchdog waits before jstacking the forked JVM. */
    private val dumpAfterMinutes: Int?
        get() = minutesIn("""val dumpAfterNanos = Duration\.ofMinutes\((\d+)\)""")

    /**
     * Read out of the build script rather than from a model: the numbers live in a Kotlin DSL block
     * that no unit test can instantiate, and a scan that reports `null` when the shape changes is a
     * better trade than not checking them at all.
     */
    private fun minutesIn(pattern: String): Int? =
        Regex(pattern).find(buildScript.readText())?.groupValues?.get(1)?.toInt()

    /**
     * Found by walking up rather than by a fixed relative path: Gradle's working directory for the
     * unit tests is the module, but that is a default rather than a promise.
     */
    private val buildScript: File
        get() = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "app/build.gradle.kts") }
            .firstOrNull { it.isFile }
            ?: error("could not find app/build.gradle.kts above ${File(".").absolutePath}")

    private companion object {
        /** Above the slowest observed passing run, below the Unit tests job's `timeout-minutes`. */
        val SANE_MINUTES = 3..29
    }
}
