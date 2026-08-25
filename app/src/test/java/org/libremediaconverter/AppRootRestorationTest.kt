package org.libremediaconverter

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The selected tab has to survive activity recreation, not just recomposition.
 *
 * `remember` covers recomposition only, and `MainActivity` declares no `configChanges`, so
 * every rotation and every resize destroys and recreates the Activity. That is the exact
 * case [AppRoot]'s own KDoc says the shell exists for: from targetSdk 37 the app is resized
 * and rotated whether or not it is ready.
 *
 * [StateRestorationTester] is the tool for it -- `emulateSavedInstanceStateRestore()`
 * disposes the composition and rebuilds it, so anything held only by `remember` is gone and
 * only saved state comes back. It is Compose's own stand-in for the recreation rather than
 * the real thing: it saves into an in-memory map instead of parcelling through a `Bundle`,
 * so it proves `rememberSaveable` is being used -- not that a particular saved
 * representation survives a `Bundle` round trip. A JVM round-trip test on the
 * saver covers the representation.
 *
 * Robolectric rather than the instrumented suite, deliberately -- but not because the
 * instrumented suite is unavailable. It runs on this host for API 33-36
 * (`tools/local-emulator/run-e2e.sh`), and CI runs 33-37. The reason is cost: this test
 * needs a composition and a saved-state round trip, nothing a device supplies, and it runs
 * in the same `./gradlew` invocation as every other JVM test instead of booting an
 * emulator. A loop measured in seconds is a loop people stay inside.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class AppRootRestorationTest {

    // Not `createComposeRule()` directly: see [drainEscapedCoroutineErrors]. Every Compose test
    // class in this source set starts there, whether or not it is the one that happens to be
    // running when another test's escaped coroutine error is delivered.
    @get:Rule
    val composeRule = createDrainedComposeRule()

    private val restoration = StateRestorationTester(composeRule)

    /**
     * The stub screen is matched by a test tag rather than by text: the label on the bar
     * ("Join") and the enum constant ("JOIN") differ only in case, and a matcher that could
     * pick up either is not an assertion.
     */
    private fun tagFor(destination: Destination) = "content:${destination.name}"

    private fun assertShowing(destination: Destination) {
        composeRule.onNodeWithTag(tagFor(destination)).assertExists()
        composeRule.onNodeWithText(destination.label).assertIsSelected()
    }

    private fun setShell(width: () -> WindowWidthSizeClass) {
        restoration.setContent {
            AppRoot(width()) { destination, modifier ->
                Box(modifier.testTag(tagFor(destination)))
            }
        }
    }

    @Test
    fun `the selected tab survives recreation on a phone`() {
        setShell { WindowWidthSizeClass.Compact }
        assertShowing(Destination.CONVERT)

        composeRule.onNodeWithText(Destination.JOIN.label).performClick()
        assertShowing(Destination.JOIN)

        restoration.emulateSavedInstanceStateRestore()

        assertShowing(Destination.JOIN)
    }

    @Test
    fun `the selected tab survives recreation on the rail layout`() {
        setShell { WindowWidthSizeClass.Expanded }

        composeRule.onNodeWithText(Destination.JOIN.label).performClick()
        assertShowing(Destination.JOIN)

        restoration.emulateSavedInstanceStateRestore()

        assertShowing(Destination.JOIN)
    }

    /**
     * The real rotation: the width class changes across the recreation, so the shell comes
     * back as a rail where it went out as a bottom bar. The tab still has to be the one the
     * user chose.
     */
    @Test
    fun `the selected tab survives a rotation that also changes the width class`() {
        var width by mutableStateOf(WindowWidthSizeClass.Compact)
        setShell { width }

        composeRule.onNodeWithText(Destination.JOIN.label).performClick()
        assertShowing(Destination.JOIN)

        width = WindowWidthSizeClass.Expanded
        restoration.emulateSavedInstanceStateRestore()

        assertShowing(Destination.JOIN)
    }
}
