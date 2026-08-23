package org.libremediaconverter

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What [AppRootRestorationTest] cannot see.
 *
 * `StateRestorationTester` saves into an in-memory map, so it proves the shell uses
 * `rememberSaveable` and stops there -- it would be just as green if the saved value were an
 * ordinal, or if the enum were left to `autoSaver`. The saved *representation* is a separate
 * decision with separate consequences, and this is where it is pinned.
 */
class DestinationSaverTest {

    /** `canBeSaved` is the host registry's question; a String always can. */
    private val scope = SaverScope { true }

    private fun save(destination: Destination): Any? = with(DestinationSaver) { scope.save(destination) }

    @Test
    fun `a destination is saved as its constant name, not its position`() {
        // JOIN is ordinal 1. If this ever reads `1`, inserting a tab above it silently
        // redefines every value already saved.
        assertEquals("CONVERT", save(Destination.CONVERT))
        assertEquals("JOIN", save(Destination.JOIN))
    }

    @Test
    fun `every destination survives the round trip`() {
        Destination.entries.forEach { destination ->
            assertEquals(destination, DestinationSaver.restore(save(destination) as String))
        }
    }

    @Test
    fun `a name no longer in the enum restores to nothing`() {
        // A downgrade, or a renamed constant, leaves a name that no longer resolves.
        // Returning null is what makes rememberSaveable fall back to the default tab
        // instead of throwing on the way back from a rotation.
        assertNull(DestinationSaver.restore("SETTINGS"))
    }
}
