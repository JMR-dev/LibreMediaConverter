package org.libremediaconverter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The theme has to resolve the scheme its arguments name, and `ThemeKt` had no test at all --
 * 23 lines, none of them covered, which is how #68 was found.
 *
 * Only two of the four branches in [LibreMediaConverterTheme]'s `when` are reachable from the
 * app. `MainActivity` is the single call site and passes no arguments, so `dynamicColor` is
 * always `true` and the live choice is between the dynamic dark and dynamic light schemes.
 * Those two are what ships, and asserting on them survives whichever way #68 is decided.
 *
 * **The other two branches have no caller.** `dynamicColor = false` is passed below by this
 * test and by nothing else in `app/src`, so the coverage it produces is not evidence that a
 * switch exists -- misreading it that way is the whole reason #68 was filed. #68 is the open
 * decision about whether one ever will exist.
 *
 * What the assertions distinguish the branches on was measured under Robolectric `sdk=36`
 * rather than assumed. The dynamic palette resolves to the platform's own default there --
 * dark background `#121318` against light `#FAF8FF`, dark primary `#B0C6FF` -- and that is a
 * different hue from the brand palette's [Purple80] / [Purple40]. A dynamic scheme reads the
 * device, so those exact values belong to the Robolectric stub and to no particular phone,
 * which is why the live-branch tests compare the two resolved schemes against each other
 * instead of hard-coding either one.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeColorSchemeTest {

    // The **v2** rule (`androidx.compose.ui.test.junit4.v2`), as everywhere else in this
    // source set.
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Every scheme the `when` can produce, read out of [MaterialTheme] inside the content
     * lambda -- the only place that shows what the theme actually chose, rather than what the
     * caller hoped for.
     *
     * All four are resolved in one composition because `setContent` may be called once per
     * test, and a comparison needs at least two of them.
     */
    private fun resolveAll(): Schemes {
        lateinit var dynamicDark: ColorScheme
        lateinit var dynamicLight: ColorScheme
        lateinit var brandDark: ColorScheme
        lateinit var brandLight: ColorScheme
        composeRule.setContent {
            LibreMediaConverterTheme(darkTheme = true) { dynamicDark = MaterialTheme.colorScheme }
            LibreMediaConverterTheme(darkTheme = false) { dynamicLight = MaterialTheme.colorScheme }
            LibreMediaConverterTheme(darkTheme = true, dynamicColor = false) {
                brandDark = MaterialTheme.colorScheme
            }
            LibreMediaConverterTheme(darkTheme = false, dynamicColor = false) {
                brandLight = MaterialTheme.colorScheme
            }
        }
        composeRule.waitForIdle()
        return Schemes(dynamicDark, dynamicLight, brandDark, brandLight)
    }

    private class Schemes(
        val dynamicDark: ColorScheme,
        val dynamicLight: ColorScheme,
        val brandDark: ColorScheme,
        val brandLight: ColorScheme,
    )

    /**
     * The live branches, and the one assertion that catches them being swapped: both dynamic
     * schemes come from the same device palette, so they are similar enough that identity or a
     * bare inequality would prove nothing. Background luminance is not similar -- it is the
     * thing dark mode is for.
     */
    @Test
    fun `dark mode resolves a darker scheme than light mode`() {
        val schemes = resolveAll()

        val dark = schemes.dynamicDark.background.luminance()
        val light = schemes.dynamicLight.background.luminance()
        assertTrue(
            "darkTheme = true should resolve the dynamic dark scheme, whose background " +
                "luminance ($dark) is below the light scheme's ($light)",
            dark < light,
        )
    }

    /**
     * Which of the two dark branches ran, and which of the two light ones: the scheme a live
     * call resolves is the dynamic one, not the brand palette sitting next to it.
     */
    @Test
    fun `the live branches take the dynamic palette rather than the brand one`() {
        val schemes = resolveAll()

        assertNotEquals(
            "the default dynamicColor = true should not resolve the brand dark palette",
            Purple80,
            schemes.dynamicDark.primary,
        )
        assertNotEquals(
            "the default dynamicColor = true should not resolve the brand light palette",
            Purple40,
            schemes.dynamicLight.primary,
        )
    }

    /**
     * The two dead branches. Nothing in `app/src` passes `dynamicColor = false`; this test
     * does it directly, because the parameter is public, and that is the only way either
     * branch runs. Covered so a later decision on #68 starts from a tested `when` -- not
     * because the brand palette is reachable in the app.
     */
    @Test
    fun `the brand palette branches run only when dynamicColor is passed explicitly`() {
        val schemes = resolveAll()

        assertEquals(Purple80, schemes.brandDark.primary)
        assertEquals(Purple40, schemes.brandLight.primary)
    }
}
