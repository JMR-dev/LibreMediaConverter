package org.libremediaconverter.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the release job still holds the one permission it needs to publish.
 *
 * `build.yml`'s `release` job declares `contents: write`, and nothing was checking it. Deleting
 * those two lines leaves actionlint clean and CodeQL silent — a *narrower* permission is not an
 * alert — and the job is `if: startsWith(github.ref, 'refs/tags/v')`, so no pull request and no
 * merge to `main` can exercise it. Measured: with the declaration removed, every gating check
 * still passes. The first thing that would notice is a release failing to publish, at the moment
 * someone is trying to cut one.
 *
 * The deletion also looks like tidying. A top-level `permissions: contents: read` now sits
 * directly above it, so a reader could reasonably take the job-level block for a duplicate. It is
 * an override, not a duplicate, and a comment saying so is not a check.
 *
 * `BackupExclusionsTest` is the precedent: a file that is configuration rather than code, load
 * bearing, and unguarded because nothing compiles it.
 *
 * **What this pins, and what it does not.** It asserts the declaration exists in the `release`
 * job's block. It cannot assert that a release actually publishes — that needs a tag push, which
 * is the thing no PR can do. So this is a tripwire against silent removal, not proof the release
 * path works.
 */
class ReleasePermissionTest {

    @Test
    fun `the release job declares the write permission it needs to publish`() {
        val release = jobBlock("release")
        assertTrue(
            "build.yml's `release` job no longer declares `contents: write`. It is the only " +
                "permission that lets the job create a release, the top-level block above it is " +
                "`contents: read`, and nothing else in CI would catch this until a tag failed to " +
                "publish. If the release moved elsewhere, delete this test deliberately.",
            release.any { it.trimStart().startsWith("contents: write") },
        )
    }

    /**
     * The lines of one top-level job, from its `  <name>:` header to the next job at that indent.
     *
     * Line-based rather than parsed: the module has no YAML dependency, and adding one to read two
     * lines would be a worse trade than a scan that fails loudly when the shape changes.
     */
    private fun jobBlock(name: String): List<String> {
        val lines = workflow.readLines()
        val start = lines.indexOfFirst { it == "  $name:" }
        check(start >= 0) { "no `  $name:` job in ${workflow.path} — has the file been restructured?" }
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.matches(Regex("^ {2}[A-Za-z0-9_-]+:.*")) }
        return if (end < 0) rest else rest.take(end)
    }

    /**
     * Found by walking up rather than by a fixed relative path: Gradle's working directory for the
     * unit tests is the module, but that is a default rather than a promise.
     */
    private val workflow: File
        get() = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, ".github/workflows/build.yml") }
            .firstOrNull { it.isFile }
            ?: error("could not find .github/workflows/build.yml above ${File(".").absolutePath}")
}
