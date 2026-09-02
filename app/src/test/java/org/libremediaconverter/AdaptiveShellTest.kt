package org.libremediaconverter

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Which navigation affordance the shell actually renders, and which screen it actually shows.
 *
 * Assertion gaps rather than coverage gaps, both of them, and that is why they lasted.
 * `AppRootRestorationTest` already drives `AppRoot` at `Compact` and `Expanded`, so JaCoCo is green
 * on `useRail` -- but it asserts only that the *selected tab* survives recreation, through a stub
 * `content` composable. Nothing anywhere queried for a rail or a bar, and nothing rendered the real
 * screens. Two consequences, both measured before this file existed:
 *
 * - **Transposing the `NavigationRail` and `NavigationBar` bodies passed the entire suite.**
 * - **Transposing `Content`'s two arms passed it too** -- a tablet showing the phone chrome, or the
 *   Convert tab opening the Join screen, and 546 tests with nothing to say about either.
 *
 * `AppRoot`'s own KDoc is why this matters more than it looks: from targetSdk 37 the app is resized
 * and rotated whether or not it is ready, so the width class is not a preference, it is whatever
 * the system hands over.
 *
 * ## Two things this needed that the rest of the suite does not
 *
 * **`createAndroidComposeRule`, not `createComposeRule`.** Rendering `AppRoot` with its *default*
 * content reaches `ConverterScreen`'s `viewModel = viewModel()`, which needs a
 * `ViewModelStoreOwner`; the plain rule supplies none. It works because both ViewModels are
 * `@JvmOverloads constructor(app: Application, …)`, so `AndroidViewModelFactory` can build them,
 * and because `app/build.gradle.kts` already puts `ui-test-manifest`'s `ComponentActivity` in the
 * merged manifest the unit tests build against -- which that file says in terms.
 *
 * **Tags on the two bars.** They are in `TestTags`, applied inside `main`, for the reason that
 * file's KDoc gives: a tag the test hands down proves only that the test set it.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class AdaptiveShellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        installTestWorkManager(app, Data.EMPTY)
        // The real screens are composed here, so their ViewModels are real too. Neither test is
        // about probing or publishing; left alone they would reach the FFprobe loader and this
        // machine's codec list, and decide things no assertion mentions.
        ConversionDependencies.probe = { _, _ -> InputProbe() }
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a phone gets the bottom bar and a tablet gets the rail`() {
        setShell(WindowWidthSizeClass.Compact)

        composeRule.onNodeWithTag(TestTags.Shell.NAVIGATION_BAR).assertExists()
        composeRule.onNodeWithTag(TestTags.Shell.NAVIGATION_RAIL).assertDoesNotExist()
    }

    @Test
    fun `an expanded window gets the rail`() {
        setShell(WindowWidthSizeClass.Expanded)

        composeRule.onNodeWithTag(TestTags.Shell.NAVIGATION_RAIL).assertExists()
        composeRule.onNodeWithTag(TestTags.Shell.NAVIGATION_BAR).assertDoesNotExist()
    }

    /**
     * The width class no test had ever passed.
     *
     * `useRail` is `!= Compact`, so Medium takes the rail with Expanded. Narrowing it to
     * `== Expanded` is a one-character change that breaks every tablet and unfolded foldable and
     * nothing else -- and until this test, nothing in either source set used `Medium` at all.
     */
    @Test
    fun `a medium window is a rail window, not a phone`() {
        setShell(WindowWidthSizeClass.Medium)

        composeRule.onNodeWithTag(TestTags.Shell.NAVIGATION_RAIL).assertExists()
        composeRule.onNodeWithTag(TestTags.Shell.NAVIGATION_BAR).assertDoesNotExist()
    }

    /**
     * The mapping every other test stubs out: which screen each destination actually opens.
     *
     * Matched on each screen's own "choose a file" affordance rather than on a title, because those
     * tags are applied by the screens themselves -- so this fails if the destinations are
     * transposed, and it fails for the right reason.
     */
    @Test
    fun `Convert opens the converter and Join opens the join screen`() {
        setShell(WindowWidthSizeClass.Compact)

        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).assertExists()
        composeRule.onNodeWithTag(TestTags.Join.CHOOSE_FILES).assertDoesNotExist()

        composeRule.onNodeWithText(Destination.JOIN.label).performClick()

        composeRule.onNodeWithTag(TestTags.Join.CHOOSE_FILES).assertExists()
        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).assertDoesNotExist()
    }

    /** [AppRoot] with its real content, which is the half nothing else composes. */
    private fun setShell(width: WindowWidthSizeClass) {
        composeRule.setContent { AppRoot(width) }
    }
}
