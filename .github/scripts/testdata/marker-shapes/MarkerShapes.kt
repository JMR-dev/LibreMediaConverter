// NOT A TEST, AND NEVER COMPILED. This is fixture data for e2e-report-shape-test.sh, which
// copies it into a throwaway repo root and runs the real report script against that. It lives
// under .github/ deliberately: Gradle only compiles app/src/**, ktlint and detekt are applied to
// :app only, and the report's own count reads app/src/androidTest -- so nothing here can reach
// the build, the linters, or the number the advisory job compares against. Verified by running
// the report against the real repo root with this file committed: still 3.
//
// It carries every shape the counter has to tell apart, in one file, because the bug in #120 was
// exactly that two of them look alike to a substring match. Three count and three must not:
//
//   COUNTS       the annotation on its own line
//   COUNTS       the annotation sharing a line with @Test -- legal Kotlin, and the case the
//                obvious "own line only" repair silently drops
//   COUNTS       the annotation indented inside a nested class
//   must NOT     a KDoc mentioning it -- this is #120 itself, copied from Media3EngineTest
//   must NOT     a commented-out annotation
//   must NOT     the import
//
// Three count. That is what the synthetic baseline in the test is set to, so the fixture and the
// baseline agree exactly the way the real tree and FAILS_ON_EMULATOR_API37_BASELINE do.
//
// The `@Test` here is spelled the way a real test spells it so the fixture reads like source
// rather than like a regex exercise. Nothing runs it.

package org.libremediaconverter.fixture

import org.junit.Test
import org.libremediaconverter.FailsOnEmulatorApi37

class MarkerShapes {
    @FailsOnEmulatorApi37
    @Test
    fun ownLine() = Unit

    @FailsOnEmulatorApi37 @Test
    fun sameLineAsTest() = Unit

    /**
     * Deliberately not `@FailsOnEmulatorApi37`: nothing here decodes or encodes, so no emulator
     * codec is involved and the API 37 image has no quarrel with it.
     */
    @Test
    fun mentionedInKdoc() = Unit

    // @FailsOnEmulatorApi37 -- taken off on 2026-01-01, kept as a note rather than deleted
    @Test
    fun commentedOut() = Unit

    class Nested {
        @FailsOnEmulatorApi37
        @Test
        fun indentedDeeper() = Unit
    }
}
