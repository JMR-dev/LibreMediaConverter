package org.libremediaconverter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The theme called the way the app calls it: with no arguments at all.
 *
 * [ThemeColorSchemeTest] resolves every branch of the `when` and always passes `darkTheme`
 * explicitly, so the `$default` bridge is never entered and **`isSystemInDarkTheme()` is never
 * called**. `MainActivity.kt:79` is its only default-argument caller and does not execute on the
 * JVM, which left the app's actual call shape the one nothing exercised —
 * `LibreMediaConverterTheme` reported `mi=21, mb=6, cb=12` at method level.
 *
 * ## Not #68
 *
 * #68 is about the two **unreachable** arms, `DarkColorScheme` and `LightColorScheme`, which cannot
 * run because `dynamicColor` is always `true` and nothing can flip it. That is an open product
 * decision. This is the reachable half — whether the default follows the system — and closing it
 * does not close that.
 *
 * ## Why the assertion compares schemes rather than reading a number
 *
 * A luminance threshold would be a guess about the device palette. What is asserted instead is that
 * the no-argument call resolves to **the same scheme** an explicit `darkTheme` of the matching
 * value does, and a different one from its opposite. That holds whatever palette the platform
 * hands back, and it is exactly the claim: the default reads the system rather than picking a side.
 *
 * Both schemes are resolved in one composition because `setContent` may be called once per test.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeFollowsSystemTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "+night")
    fun `with no arguments the theme follows a system in dark mode`() {
        val resolved = resolve()

        assertEquals("the default must resolve what darkTheme = true does", resolved.dark, resolved.bare)
        assertNotEquals(resolved.light, resolved.bare)
    }

    @Test
    @Config(qualifiers = "+notnight")
    fun `with no arguments the theme follows a system in light mode`() {
        val resolved = resolve()

        assertEquals("the default must resolve what darkTheme = false does", resolved.light, resolved.bare)
        assertNotEquals(resolved.dark, resolved.bare)
    }

    /**
     * The three colours are read together as one value, because any single one could coincide
     * between the two schemes on some palette while the schemes themselves differ. Background is
     * what dark mode is chiefly about; primary and surface are along to make a coincidence
     * implausible rather than merely unlikely.
     */
    private data class Fingerprint(val background: Long, val primary: Long, val surface: Long)

    private fun ColorScheme.fingerprint() =
        Fingerprint(background.value.toLong(), primary.value.toLong(), surface.value.toLong())

    private class Resolved(val bare: Fingerprint, val dark: Fingerprint, val light: Fingerprint)

    private fun resolve(): Resolved {
        lateinit var bare: Fingerprint
        lateinit var dark: Fingerprint
        lateinit var light: Fingerprint
        composeRule.setContent {
            // No arguments — the call MainActivity makes, and the one nothing exercised.
            LibreMediaConverterTheme { bare = MaterialTheme.colorScheme.fingerprint() }
            LibreMediaConverterTheme(darkTheme = true) { dark = MaterialTheme.colorScheme.fingerprint() }
            LibreMediaConverterTheme(darkTheme = false) { light = MaterialTheme.colorScheme.fingerprint() }
        }
        composeRule.waitForIdle()
        return Resolved(bare, dark, light)
    }
}
