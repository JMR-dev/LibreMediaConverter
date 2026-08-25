package org.libremediaconverter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY. This file exists only to prove #83's report fires, and is deleted immediately after.
 *
 * It is the mutation the ticket asks for: a fourth test carrying [FailsOnEmulatorApi37] that
 * fails. The advisory job should report `expected 4` and `failed 4` against a baseline of 3, say
 * so as a `::notice::`, and finish with its conclusion unchanged.
 */
@RunWith(AndroidJUnit4::class)
class MutationProbeApi37Test {
    @Test
    @FailsOnEmulatorApi37
    fun failsOnPurposeToProveTheAdvisoryReportFires() {
        fail("mutation probe for #83 -- this test exists to fail")
    }
}
