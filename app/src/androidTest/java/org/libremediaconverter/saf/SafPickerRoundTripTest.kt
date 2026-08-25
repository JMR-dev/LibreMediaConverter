package org.libremediaconverter.saf

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.MainActivity
import org.libremediaconverter.ui.TestTags

/**
 * Choosing a file, through the real system picker, and still having it after a rotation.
 *
 * Two defects, and neither is reachable from anywhere else in this repo.
 *
 * **The picker is opened with a filter, and a filter can hide the user's file.** `ConverterScreen`
 * launches `ActivityResultContracts.OpenDocument` with a MIME array; DocumentsUI hides every root
 * and every document that array does not match. Narrow it and the app still compiles, still
 * renders, still passes every JVM test — and the user taps "Choose file" and is shown an empty
 * picker. Nothing in either source set drove SAF **as a picker** before this: the only SAF coverage
 * is the publish side, in `OutputPublisherPublishTest`, against hand-written `ContentProvider`
 * fakes. The launcher wiring, the filter, and the read grant that comes back had never been
 * executed by a test.
 *
 * **The picked file has to survive a rotation.** `MainActivity` declares no `configChanges`, so
 * every rotation destroys and recreates it, and `ConversionViewModel` holds the picked file in a
 * plain `MutableStateFlow` with no `SavedStateHandle` behind it. The only thing that carries it
 * across is the retained `ViewModelStore` the Activity gets from resolving the ViewModel through
 * `LocalViewModelStoreOwner`. Scope it to the composition instead and the file is gone.
 *
 * ### Why these two are one test class
 *
 * A rotation test alone has no bite of its own. `AppRootRestorationTest` already catches
 * `rememberSaveable` -> `remember` on the JVM, and a second test whose only mutation is one an
 * existing test catches is the vacuous test this whole decomposition exists to prevent. So the
 * rotation here runs **from a real picked input**, which is a state no JVM test can produce:
 * `AppRootRestorationTest` injects a stub `content` lambda specifically to avoid standing up
 * either ViewModel, and `StateRestorationTester` saves into an in-memory map rather than a
 * `Bundle`.
 *
 * ### The mutations, and what they printed
 *
 * Both were run, not asserted. Narrowing the wildcard array `ConverterScreen.kt` passes to
 * `pickInput.launch` — to `arrayOf("application/x-lmc-no-such-type")` — empties the picker of the
 * fixture root entirely, and [pickingAFileThroughTheSystemPickerFillsInTheFileCard] fails on the
 * assertion that names it.
 * Making the ViewModel composition-scoped leaves the picker test alone and fails
 * [thePickedInputSurvivesARealRotation], with `:app:testDebugUnitTest` still BUILD SUCCESSFUL —
 * which is the divergence this ticket was filed to establish, and which was doubted on it. It is
 * `viewModel()` -> `viewModel(viewModelStoreOwner = remember { <a plain ViewModelStoreOwner> })`,
 * **plus** `factory = ViewModelProvider.AndroidViewModelFactory()` and a `MutableCreationExtras`
 * carrying `APPLICATION_KEY`. The factory half is not decoration: an owner that is not a
 * `HasDefaultViewModelProviderFactory` contributes no creation extras, and the default factory
 * cannot construct an `AndroidViewModel` without them — so the owner swap alone crashes on
 * construction instead of demonstrating the scope. The PR body quotes both failures verbatim.
 *
 * ### It has to be an unlocked emulator
 *
 * The Pixel 10 Pro XL is secure-locked and cannot be unlocked from a shell, so the picker cannot be
 * driven there at all. That is why this gap survived as long as it did.
 * `tools/local-emulator/run-e2e.sh` runs API 33-36 on the development host.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SafPickerRoundTripTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Set by the one test that rotates, read by [restoreOrientation]. See its KDoc. */
    private var rotated = false

    /**
     * Leave the device the way it was found — and only if this test moved it.
     *
     * Two things are deliberate here, and both are about the *other* tests on the device rather
     * than about these two.
     *
     * The flag, because this runs after every test in the class, not only the one that rotated. An
     * unconditional restore issues a WindowManager rotation request after the picker test as well,
     * which has nothing to undo; JUnit does not promise method order, so that is an interaction
     * between two tests that no single-class run would ever show. Tracked as a flag rather than
     * read back off `isNaturalOrientation`, because a device whose *natural* orientation is
     * landscape would answer that question the wrong way round.
     *
     * And `unfreezeRotation`, because `setOrientationNatural` does not merely rotate: it freezes
     * the rotation there. A run that stopped after it would hand the next test a device that
     * cannot rotate at all.
     */
    @After
    fun restoreOrientation() {
        if (!rotated) return
        device.setOrientationNatural()
        device.unfreezeRotation()
        device.waitForIdle()
    }

    @Test
    fun pickingAFileThroughTheSystemPickerFillsInTheFileCard() {
        pickTheFixture()

        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NAME)
            .assertTextEquals(FixtureDocumentsProvider.FIXTURE_DISPLAY_NAME)

        // Not the same assertion twice. The name above comes from a metadata query, which a URI
        // with no read grant answers just as well; this line only appears once something has
        // opened the file and read its header. It is what says the picker handed back a URI the
        // app can actually USE -- delete grantUriPermissions from the fixture's manifest entry and
        // the name still arrives while this goes red.
        //
        // The whole "Container: MP4" and not "MP4": DetailRow renders the label and the value as
        // one semantics node.
        awaitNode(TestTags.Converter.detailRow(CONTAINER_LABEL))
        composeRule.onNodeWithTag(TestTags.Converter.detailRow(CONTAINER_LABEL))
            .assertTextEquals("$CONTAINER_LABEL: MP4")
    }

    @Test
    fun thePickedInputSurvivesARealRotation() {
        pickTheFixture()
        // The identity hash rather than the Activity itself, so nothing here keeps a destroyed
        // Activity reachable across the recreation it is being used to detect.
        val before = System.identityHashCode(composeRule.activity)

        device.setOrientationLandscape()
        rotated = true
        composeRule.waitForIdle()

        // Two guards before the assertion that matters, because both of the ways this test could
        // pass while proving nothing are silent ones.
        //
        // A device that ignored the rotation request would leave the app exactly as it was, and
        // "the file is still there" would then be a statement about a screen nothing happened to.
        assertNotEquals(
            "the device did not actually rotate, so nothing below is about a rotation",
            NATURAL_ROTATION,
            device.displayRotation,
        )
        // And a rotation that did NOT recreate the Activity -- a configChanges attribute added to
        // the manifest, an aspect-ratio or orientation lock -- would make this a recomposition
        // test. The retained ViewModelStore is only interesting because the Activity around it
        // really was destroyed and rebuilt.
        assertNotEquals(
            "the rotation did not recreate MainActivity, so the retained ViewModelStore was never used",
            before,
            System.identityHashCode(composeRule.activity),
        )

        awaitNode(TestTags.Converter.FILE_CARD_NAME)
        composeRule.onNodeWithTag(TestTags.Converter.FILE_CARD_NAME)
            .assertTextEquals(FixtureDocumentsProvider.FIXTURE_DISPLAY_NAME)
    }

    // --- driving the picker ---------------------------------------------------------------

    /**
     * Taps "Choose file", walks the system picker to the fixture, and returns once the app has it.
     *
     * Everything between the first tap and the last belongs to `com.google.android.documentsui`,
     * which is why UiAutomator is here at all: Compose's matchers stop at this process's
     * composition and Espresso's at its view hierarchy, and the picker is neither.
     */
    private fun pickTheFixture() {
        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).performClick()

        // THIS is the line the MIME filter mutation fails on. DocumentsUI matches the requested
        // types against Root.COLUMN_MIME_TYPES and drops the roots that cannot answer, so a filter
        // the fixture root does not satisfy takes the root out of the picker altogether -- along
        // with "Images", "Audio", "Videos" and "Documents", measured on API 34.
        val root = awaitPickerNode(By.text(FixtureDocumentsProvider.ROOT_TITLE)) {
            // Which screen the picker opens on is its own business: it lands on Recent, where the
            // roots are a strip at the bottom, but a device with a populated Recent may need the
            // drawer. Looking in the second place widens where the root is searched for; it does
            // not weaken what has to be found, which is still this root.
            device.findObject(By.desc(SHOW_ROOTS_DESCRIPTION))?.click()
        }
        root.click()

        awaitPickerNode(By.text(FixtureDocumentsProvider.FIXTURE_DISPLAY_NAME)).click()

        awaitNode(TestTags.Converter.FILE_CARD_NAME)
    }

    /**
     * The picker node [selector] names, or a failure that says which one was missing.
     *
     * [ifAbsent] runs once, after the first wait comes up empty, and then the wait is repeated. A
     * null return from `findObject` is deliberately not an error there: it is the "already on the
     * right screen" case.
     */
    private fun awaitPickerNode(selector: BySelector, ifAbsent: () -> Unit = {}) =
        device.wait(Until.findObject(selector), PICKER_TIMEOUT_MS)
            ?: run {
                ifAbsent()
                requireNotNull(device.wait(Until.findObject(selector), PICKER_TIMEOUT_MS)) {
                    "the system picker never showed $selector"
                }
            }

    /**
     * Blocks until [tag] is in the composition, so an assertion cannot race the picker's result.
     *
     * The described overload of `waitUntil`, not the bare one. A timeout is how both of this
     * class's mutations report themselves, and the bare overload's message is
     * `Condition still not satisfied after 30000 ms` — which names neither the node nor the test.
     * With the description it says which affordance never arrived, which is the whole finding.
     */
    private fun awaitNode(tag: String) {
        composeRule.waitUntil("a node tagged $tag exists", APP_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {

        /**
         * Generous on purpose. This waits on another app being started, and on FFprobe spawning a
         * native process over a `content://` URI; a timeout that merely usually passes is a flaky
         * gating leg on five API levels, which costs far more than the seconds it saves.
         */
        const val PICKER_TIMEOUT_MS = 30_000L
        const val APP_TIMEOUT_MS = 30_000L

        /** `Surface.ROTATION_0`, named rather than `0` so the comparison reads. */
        const val NATURAL_ROTATION = 0

        /** DocumentsUI's drawer button. It carries no text, only this description. */
        const val SHOW_ROOTS_DESCRIPTION = "Show roots"

        /** The detail row `MediaProbe` fills in for anything it could open and identify. */
        const val CONTAINER_LABEL = "Container"
    }
}
