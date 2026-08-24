package org.libremediaconverter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No two entries of [TestTags] may share a value.
 *
 * A duplicated value is the one mistake this table invites -- the constants are added in blocks of
 * near-identical lines, and a copy-paste that keeps the old string still compiles, still reads
 * correctly at the call site, and still passes every test in the file that placed it. It surfaces
 * later, in someone else's PR, as an affordance that "resolves to exactly one node" finding two,
 * with nothing in that diff to explain it.
 *
 * Read by reflection rather than from a hand-written list, because a hand-written list would be a
 * second copy of the table with the same copy-paste failure in it.
 */
class TagTableUniquenessTest {

    private fun tagsIn(vararg holders: Class<*>): List<String> = holders.flatMap { holder ->
        holder.declaredFields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
    }

    @Test
    fun `every tag constant has its own value`() {
        val tags = tagsIn(
            TestTags::class.java,
            TestTags.Converter::class.java,
            TestTags.Join::class.java,
        )

        // Without this the check would pass on an empty list, which is what a reflection call
        // that stopped finding the constants would hand it.
        assertTrue("reflection found only ${tags.size} tag constants, so it is not reading the table", tags.size > 20)
        assertEquals(emptyList<String>(), tags.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }
}
