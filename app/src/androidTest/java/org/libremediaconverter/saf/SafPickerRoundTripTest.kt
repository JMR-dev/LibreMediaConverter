package org.libremediaconverter.saf

import android.app.UiAutomation
import androidx.compose.ui.test.ComposeTimeoutException
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
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.FailsOnEmulatorApi37
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
 * ### #93: what actually failed was reading the screen, not the picker
 *
 * Ninety minutes after this class landed it started failing on gating legs at API 33, 34, 35 and
 * 37 — on diffs that were two KDoc comments, a MIME lookup table and a README paragraph (#93).
 * Every failure named the fixture root, so it read as a root-discovery race, and the ticket was
 * filed on that reading. It was not one, and it was not the `StaleObjectException` #80 had fixed
 * an hour earlier either.
 *
 * **DocumentsUI was fine.** On the API 34 leg of run 32806342548 its own
 * `ProvidersAccess: Matched roots` names
 * `content://org.libremediaconverter.test.fixtures/root/lmc-r38-root` five times inside the sixty
 * seconds the test spent failing, `ActivityTaskManager` logged the `PickActivity` as `Displayed`,
 * and the provider process started on cue.
 *
 * **This process could not read any window at all.** Two counts settle it. Across that whole leg
 * UiAutomator logged `Retrieving node with selector` 1095 times and `Node not found with selector`
 * 1095 times — not one selector ever matched, from the first query of the run. The green leg of
 * the same job asked 7 times and found 5. `UiDevice.getWindowRoots` builds its search set from
 * `UiAutomation.getWindows()` and, on API 21 and up, from nothing else; an empty list there makes
 * every selector unfindable and says nothing whatever about SAF. The corroborating detail is that
 * `By.desc("Show roots")` — the toolbar button, present on that screen whether the roots list is
 * stale or not — was also not found, 28 s after the picker was displayed.
 *
 * **A fresh picker is not the repair, and this was measured rather than assumed.** The same leg
 * opened a *second* `PickActivity` for the second test, in the same DocumentsUI process
 * (pid 3299), and read exactly as little from it. So whatever was broken outlived one window.
 * [requireAReadableScreen] is the part aimed at that: it asks whether this process can see the
 * app's own window *before* the picker is opened, and [rebuildUiAutomation] tears the connection
 * down and builds another if it cannot.
 *
 * **The check has since caught the real thing, in CI, and the connection rebuild did not repair
 * it.** Run 32811493607, API 35 and API 37 legs, both tests, 12 s each instead of 60:
 *
 * ```
 * java.lang.AssertionError: UiAutomator cannot see this app's own window, so it could not have
 *   seen the picker's either. This is not a SAF failure.
 *     at SafPickerRoundTripTest.requireAReadableScreen
 * ```
 *
 * That is the diagnosis this class could not previously give, and it moves the question off SAF
 * for good.
 *
 * ### What the window list said, and why nothing here can fix it
 *
 * [describeWindows] was added to that failure so the next occurrence would close the question
 * rather than reopen it. It did — on the API 34 leg of run 32812248131 and again, character for
 * character, on the API 33 leg of run 32812892103:
 *
 * ```
 * ... Waking the device, dismissing the keyguard and rebuilding the UiAutomation connection all
 * failed to make it readable. What it could see: com.android.systemui[type=3], android[type=3]
 * ```
 *
 * `type=3` is `AccessibilityWindowInfo.TYPE_SYSTEM`. The list is **not** empty — it holds the
 * system windows and **not one `TYPE_APPLICATION` window**, on a device where the framework had
 * already logged `Displayed org.libremediaconverter/.MainActivity`. So the application layer
 * never reaches accessibility on those boots, and every selector in this class, the picker's and
 * the app's alike, is unfindable for the whole instrumentation run.
 *
 * Three CI runs on this branch caught the fault, at API 33, 34, 35 and 37, and every one of them
 * printed that same list. It is not one level's quirk.
 *
 * ### And that list is what identified the occluder
 *
 * `android[type=3]` is `system_server`, and what it was holding is in the same logcat, minutes
 * before this class ever ran:
 *
 * ```
 * ANR in com.google.android.apps.nexuslauncher (com.google.android.apps.nexuslauncher/.NexusLauncherActivity)
 * Reason: Input dispatching timed out (Application does not have a focused window)
 * Window{4ed8414 u0 Application Not Responding: com.google.android.apps.nexuslauncher}
 * ```
 *
 * **The launcher ANRs on a loaded runner emulator, and the dialog it leaves behind never goes
 * away.** It is opaque and fullscreen, so `AccessibilityWindowManager` drops every application
 * window beneath it — which is how the app can be `Displayed` and unreadable at once, the
 * contradiction that made #93 look like a SAF bug for six PRs. It is present on both legs
 * examined, at API 33 and 34, at the failure timestamp.
 *
 * So [dismissASystemErrorDialog] is tried first, and it is the remedy with a mechanism behind it.
 * The other two are kept behind it and are **measured as not the cause**: [unlockTheDevice] (the
 * keyguard theory, from `KeyguardViewMediator` reporting an unprovisioned device — dismissing it
 * changed nothing) and [rebuildUiAutomation]. A second `PickActivity` is not a remedy for this
 * either, and that was measured too: the first failing leg opened one and read as little from it.
 *
 * **What is honest about the dialog remedy: it has been shown to do no harm, not to work.** It
 * was forced on with no dialog present and the suite stayed green, which is the way a blind
 * `click()` could have broken a healthy run. Dismissing a real ANR dialog has not been observed,
 * because the fault has never been reproduced locally — not on six warm runs, not on cold
 * full-suite runs at API 34 and 35 on freshly created AVDs under `swangle_indirect` at two cores,
 * not under host load. If it recurs, the message now names the dialog and the window list, so the
 * next step is a measurement rather than another theory.
 *
 * ### The whole pick is retried, which is a separate and smaller claim
 *
 * [pickTheFixture] also backs out and asks for another picker when the walk comes up short. That
 * is not the answer to the paragraph above; it is the answer to a picker whose *lists* were built
 * before their data arrived, which is a real thing DocumentsUI does and which
 * [tapPickerNode]'s re-find cannot reach either — it re-acquires a handle inside the one picker.
 *
 * One API 37 run failed a step deeper than the rest: the root appeared and
 * `[TEXT='\Qlmc-r38-fixture.mp4\E']` did not. **That shape has not been reproduced or
 * diagnosed.** It is covered here only because a fresh pick re-walks from Recent, and that is
 * worth writing down rather than letting the retry read as a fix for something nobody measured.
 *
 * ### The mutations, and what they printed
 *
 * Both were run, not asserted. Narrowing the wildcard array `ConverterScreen.kt` passes to
 * `pickInput.launch` — to `arrayOf("application/x-lmc-no-such-type")` — empties the picker of the
 * fixture root entirely, and both tests fail on the assertion that names it. **Re-run after the
 * #93 retry landed**, because a retry that tolerated an absent root would have made this mutation
 * vacuous, which is the one thing that must not happen here:
 *
 * ```
 * java.lang.AssertionError: the system picker never showed BySelector [TEXT='\QLMC R38 fixtures\E'],
 *   in 3 separate pickers (the last one left org.libremediaconverter in front)
 *     at org.libremediaconverter.saf.SafPickerRoundTripTest.pickTheFixture(SafPickerRoundTripTest.kt:268)
 * ```
 *
 * The root is absent from all three pickers, so all three report it, and the cost of saying so is
 * bounded: 126 s and 127 s for the two tests, against the 1200 s wrapper timeout in
 * `.github/scripts/e2e-run.sh`. The clause about what was left in front is not decoration either
 * — it is what says the retry really did get back to the app between attempts rather than tapping
 * behind a picker that never closed.
 *
 * **That mutation only shows the retry failing correctly.** Showing it *recovering* needs a
 * failure that goes away, so one was injected: a field making the first
 * [walkThePickerToTheFixture] of each test return a selector nothing matches. Both tests then
 * passed, with `ActivityTaskManager` logging four `OPEN_DOCUMENT` starts for the two of them —
 * two pickers each. That is the run which says the reopened pick completes: that
 * `pickInput.launch` is not refused from the re-resumed Activity, and that the second test's
 * reopen, which lands in the last-accessed stack rather than on Recent, still walks to the file.
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
 * `tools/local-emulator/run-e2e.sh` runs API 33-36 on the development host, and both tests pass
 * there: **59 / 0 / 0 / 2 at API 33 and again at API 36**, whole suite, 2026-08-24.
 *
 * ### Why only the rotation test carries [FailsOnEmulatorApi37]
 *
 * This class is the first thing in the suite that touches system UI, and the android-37.x images
 * are where that stops being free: surfaceflinger aborts inside the guest's Gralloc5 mapper, init
 * SIGKILLs zygote with it, and the framework restarts underneath the run. Disabling SystemUI --
 * the deviation the API 37 leg already makes -- removes the *idle* trigger, not this one.
 *
 * The marker is on one method and not on the class, because that is what was measured, one method
 * per fresh emulator, on `android-37.0` under `swangle_indirect`:
 *
 * ```
 * thePickedInputSurvivesARealRotation            INSTRUMENTATION_ABORTED: System has crashed.
 *                                               Expected 1 tests, received 0
 * pickingAFileThroughTheSystemPickerFillsInTheFileCard                              PASSED
 * ```
 *
 * A rotation rebuilds every surface on screen at once, which the mapper does not survive; merely
 * starting DocumentsUI does not.
 *
 * **The first version of this said the class, and it was wrong.** The picker test had failed at
 * API 37 too -- with a `StaleObjectException` that turned out to be this file's own bug rather
 * than the image's, and which CI then reproduced deterministically at API 33, 34 and 35. Fixing
 * it ([tapPickerNode]) and re-measuring is what separated the two. An annotation is a claim about
 * an image, and a broken test makes every image look broken; **re-measure after fixing a test
 * before deciding what the platform did.**
 *
 * The annotation says only that, and CI reads it twice, so the rotation test runs on the advisory
 * API 37 leg and not the gating one. **Do not read it as "a rotation is allowed to lose the
 * file".** That is what API 33 through 36 are for, and they answer it.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SafPickerRoundTripTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** The app under test, whose own window is what [requireAReadableScreen] asks for. */
    private val appPackage: String =
        InstrumentationRegistry.getInstrumentation().targetContext.packageName

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
    @FailsOnEmulatorApi37
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
     *
     * **What is retried here is the whole pick.** [tapPickerNode]'s re-find re-acquires a handle
     * to a node inside the picker that is already open, so it cannot reach a list that was built
     * before its data arrived. Backing out and tapping "Choose file" again gets a *second*
     * `PickActivity`, which rebuilds every list in it — and is what a user does when a picker
     * comes up wrong. It is **not** the answer to the unreadable-screen failure in the class
     * KDoc; [requireAReadableScreen], one line above, is the part aimed at that.
     *
     * The first attempt keeps the full [PICKER_TIMEOUT_MS]; the later ones use
     * [REOPENED_TIMEOUT_MS], because by then the picker's process, its provider and its root cache
     * are all warm and the only thing being waited on is one screen. That is what keeps the cost
     * of a genuinely absent root bounded — see the class KDoc.
     */
    private fun pickTheFixture() {
        var missing: BySelector? = null
        repeat(PICK_ATTEMPTS) { attempt ->
            requireAReadableScreen()
            openThePicker()
            missing = walkThePickerToTheFixture(
                if (attempt == 0) PICKER_TIMEOUT_MS else REOPENED_TIMEOUT_MS,
            )
            if (missing == null) {
                awaitNode(TestTags.Converter.FILE_CARD_NAME)
                return
            }
            dismissThePicker()
        }
        throw AssertionError(
            "the system picker never showed $missing, in $PICK_ATTEMPTS separate pickers " +
                "(the last one left ${device.currentPackageName} in front)",
        )
    }

    /**
     * Refuses to go near the picker until this process can read a window it already knows is there.
     *
     * **This is the check that would have answered #93 outright**, instead of leaving six PRs to
     * infer a SAF fault from a picker that was never the problem. It is here because of what the
     * failing logcat counts. Across the whole API 34 leg UiAutomator
     * asked for a node 1095 times and logged `Node not found` 1095 times — it never read anything,
     * from the first query of the run onwards. The green leg of the same job asked 7 times and
     * found 5. So the window list `UiDevice` searches, `UiAutomation.getWindows()`, was empty for
     * that entire instrumentation run; on API 21 and up that list is the *only* place
     * `getWindowRoots` looks, so an empty one makes every selector unfindable and says nothing
     * about the app, the picker or the fixture.
     *
     * The probe is deliberately the app's **own** window, asked while the app is in front and
     * before anything is tapped. It is the one window that must be readable for any of the rest to
     * mean anything, so a failure here is unambiguous — where "the picker never showed the root"
     * was not, and is what sent #93 looking at package installation and root caches.
     *
     * The repair is [rebuildUiAutomation]. It has been forced on and measured — a rebuilt
     * connection still reads windows, which is the way it could have been worse than nothing —
     * but it has **never been run against the real fault**, because the fault has never been
     * reproduced on demand. See the class KDoc. What is certain is that a fresh picker is *not*
     * the repair: the failing leg opened a second `PickActivity` for the second test, in the
     * same DocumentsUI process, and read exactly as little from it.
     */
    private fun requireAReadableScreen() {
        val app = By.pkg(appPackage)
        if (device.wait(Until.hasObject(app), READABLE_TIMEOUT_MS) == true) return
        dismissASystemErrorDialog()
        if (device.wait(Until.hasObject(app), READABLE_TIMEOUT_MS) == true) return
        unlockTheDevice()
        if (device.wait(Until.hasObject(app), READABLE_TIMEOUT_MS) == true) return
        rebuildUiAutomation()
        if (device.wait(Until.hasObject(app), READABLE_TIMEOUT_MS) != true) {
            throw AssertionError(
                "UiAutomator cannot see this app's own window, so it could not have seen the " +
                    "picker's either. This is not a SAF failure. Closing a system error dialog, " +
                    "waking the device, dismissing the keyguard and rebuilding the UiAutomation " +
                    "connection all failed to make it readable. What it could see: " +
                    describeWindows(),
            )
        }
    }

    /**
     * Closes a system "isn't responding" dialog, if that is what is on top of the app.
     *
     * **This is the occluder #93 turned out to have**, and it took the window list in the failure
     * message to find it. `AppNotRespondingDialog` belongs to `system_server`, so it is the
     * `android[type=3]` in `com.android.systemui[type=3], android[type=3]` — and it is opaque and
     * fullscreen, so `AccessibilityWindowManager` drops every application window beneath it. The
     * app is `Displayed` and unreadable at the same time, which is exactly the contradiction this
     * class spent #93 failing to explain. It is not even this app's dialog:
     *
     * ```
     * ANR in com.google.android.apps.nexuslauncher (com.google.android.apps.nexuslauncher/.NexusLauncherActivity)
     * Reason: Input dispatching timed out (Application does not have a focused window)
     * Window{4ed8414 u0 Application Not Responding: com.google.android.apps.nexuslauncher}
     * ```
     *
     * The launcher ANRs on a loaded runner emulator minutes before this class runs, and the dialog
     * it leaves behind never goes away on its own.
     *
     * Dismissed by resource id rather than by button text, because the text is localised and the
     * ids are not, and by id rather than by "the first button in the system window", because that
     * would click whatever system window happened to be there. `aerr_wait` first: it dismisses the
     * dialog and leaves the offending app alone, which is the polite answer when the app is not
     * ours. Back is not tried — `BaseErrorDialog` swallows key events.
     */
    private fun dismissASystemErrorDialog() {
        for (id in ERROR_DIALOG_BUTTONS) {
            val button = device.findObject(By.res(id)) ?: continue
            button.click()
            device.waitForIdle()
            return
        }
    }

    /**
     * Wakes the display and asks the keyguard to go away.
     *
     * The cheapest explanation for "this process cannot see the app's own window" is that
     * something is in front of it, and on a runner emulator that something is the lock screen:
     * these images come up unprovisioned, and `KeyguardViewMediator` says so in as many words --
     * `we need to show the keyguard since the device isn't provisioned yet`. An occluded window is
     * not in the accessibility window list, which is the same symptom as a broken connection and
     * has a far more ordinary cause.
     *
     * `wm dismiss-keyguard` rather than a swipe, because it is a request to the window manager
     * rather than a gesture that has to land somewhere this process cannot see. It is only
     * attempted on the failure path -- a device that was readable never reaches here -- so a run
     * where the keyguard was never up pays nothing and is not altered.
     */
    private fun unlockTheDevice() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.waitForIdle()
    }

    /** The accessibility window list, for a failure message that says what was actually there. */
    private fun describeWindows(): String {
        val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
        if (windows.isEmpty()) return "no windows at all (UiAutomation.getWindows() is empty)"
        return windows.joinToString(", ") { "${it.root?.packageName ?: "?"}[type=${it.type}]" }
    }

    /**
     * Tears down this run's `UiAutomation` connection and establishes a new one.
     *
     * `Instrumentation.getUiAutomation` hands back the existing connection unless the flags differ
     * from the ones it was created with, in which case it destroys it and builds another — so
     * asking for different flags and then for the original ones back is how a test reaches the
     * connection at all. `UiDevice` re-reads the flags from `Configurator` on every call rather
     * than caching an instance, so the next selector goes through the new connection.
     *
     * `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` is toggled rather than chosen: it is only being
     * used as a value that differs from whatever is configured, and it is put back.
     *
     * **Forced on and measured, because the obvious way for this to be worse than nothing is
     * silent.** `UiDevice` puts `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` on the service info during its
     * own initialisation, and `getWindows()` is empty without it — so a rebuilt connection that
     * did not get the flag back would cause exactly the emptiness this is meant to cure, on the
     * one path where it is the last hope. Run unconditionally on every attempt, on a cold API 34
     * emulator, both tests passed, and logcat shows the connection really being replaced rather
     * than handed back: `Init UiAutomation[id=2, flags=0]`, then `id=4, flags=1`, then
     * `id=6, flags=0`, with `Registering UiTestAutomationService` between each.
     */
    private fun rebuildUiAutomation() {
        val configurator = Configurator.getInstance()
        val flags = configurator.uiAutomationFlags
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        configurator.uiAutomationFlags = flags xor UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        instrumentation.getUiAutomation(configurator.uiAutomationFlags)
        configurator.uiAutomationFlags = flags
        instrumentation.getUiAutomation(flags)
    }

    /** Waits for the app to be showing its own screen again, then asks for a picker. */
    private fun openThePicker() {
        awaitNode(TestTags.Converter.CHOOSE_FILE)
        composeRule.onNodeWithTag(TestTags.Converter.CHOOSE_FILE).performClick()
    }

    /**
     * Null once the fixture URI is with the app, or the selector whose list never carried it.
     *
     * Three things have to be there, in order, and the `when` names them in that order so that a
     * failure says which one was missing rather than "the picker did not work".
     *
     * **The first branch is what tells an unreadable picker from an absent root.** In #93 neither
     * the root *nor the toolbar's "Show roots" button* could be found for sixty seconds, and a
     * stale roots list would have left the toolbar findable. Both arrived as one message. Asking
     * for the picker's package on its own separates them: `never showed BySelector [PKG=...]`
     * means the picker was not readable, and the root selector means the root was not offered.
     *
     * The second is the line the MIME filter mutation fails on: DocumentsUI matches the requested
     * types against `Root.COLUMN_MIME_TYPES` and drops the roots that cannot answer, so a filter
     * the fixture root does not satisfy takes the root out of the picker altogether — along with
     * "Images", "Audio", "Videos" and "Documents", measured on API 34.
     *
     * **The third takes no recovery action of its own, and that is deliberate rather than an
     * oversight.** [openTheRootsDrawer] exists because a root has a *second* place it can be
     * shown; a document in a directory listing has no second place, so there is nothing an
     * in-picker action could do. Its recovery is the outer loop: a fresh picker re-walks from
     * Recent into the root, which rebuilds the directory listing as well as the roots strip.
     */
    private fun walkThePickerToTheFixture(timeoutMs: Long): BySelector? {
        val picker = By.pkg(DOCUMENTS_UI_PACKAGE)
        val root = By.text(FixtureDocumentsProvider.ROOT_TITLE)
        val fixture = By.text(FixtureDocumentsProvider.FIXTURE_DISPLAY_NAME)
        return when {
            device.wait(Until.hasObject(picker), timeoutMs) != true -> picker
            !tapPickerNode(root, timeoutMs, ifAbsent = ::openTheRootsDrawer) -> root
            !tapPickerNode(fixture, timeoutMs) -> fixture
            else -> null
        }
    }

    /**
     * The picker's own drawer, opened only when the root was not on the screen it landed on.
     *
     * **In practice it never runs, and #80 was right to say so.** A hierarchy dump taken on a
     * cold API 34 emulator while this test was passing has the fixture root on the landing
     * screen — `text="LMC R38 fixtures"` at `android:id/title`, under a `BROWSE FILES IN OTHER
     * APPS` header — with the drawer shut (`Show roots` present, `Hide roots` absent). So the
     * roots strip is the normal path and the drawer is a widening, kept because a device with a
     * populated Recent may push the strip off screen. Looking in a second place widens where the
     * root is searched for; it does not weaken what has to be found, which is still this root.
     */
    private fun openTheRootsDrawer() {
        device.findObject(By.desc(SHOW_ROOTS_DESCRIPTION))?.click()
    }

    /**
     * Backs out of the picker until the app has the window focus again.
     *
     * **The focus is asked of the Activity, not of UiAutomator, and that is not a stylistic
     * choice.** The failure this retry exists for is a picker window UiAutomator cannot see, so a
     * probe that went through the same accessibility window list would cheerfully report "the
     * picker is gone" about the window that is still in front — and the reopened pick would then
     * tap "Choose file" behind it. `Activity.hasWindowFocus` comes from the framework instead, and
     * answers about the app rather than about the picker.
     *
     * It is also why this counts backs rather than pressing a fixed number of them. One back is
     * enough from Recent and two are needed from inside the root, but a third from Recent would
     * finish `MainActivity` and take the rest of the test with it.
     */
    private fun dismissThePicker() {
        repeat(BACK_PRESSES) {
            if (awaitAppFocus()) return
            // Before the back press, not instead of it: an app-error dialog swallows key events,
            // so a back aimed at the picker lands on the dialog and nothing moves. Measured --
            // API 34 of run 32813885120 exhausted all four presses with `android` in front, which
            // is that dialog, while the launcher it belonged to went on ANRing behind everything.
            dismissASystemErrorDialog()
            device.pressBack()
        }
        // The check after the last press, and not a spare one: `repeat` presses on its final
        // iteration too, so without this a dismissal that worked on the last press would still be
        // reported as a failure to close.
        if (!awaitAppFocus()) {
            throw AssertionError(
                "the system picker would not close: after $BACK_PRESSES back presses the app " +
                    "still does not have the window focus, and ${device.currentPackageName} is " +
                    "in front. What could be seen: " + describeWindows(),
            )
        }
    }

    /** True once [MainActivity] has the window focus, false if it does not take it in time. */
    private fun awaitAppFocus(): Boolean = try {
        composeRule.waitUntil("the app has the window focus back", FOCUS_TIMEOUT_MS) {
            composeRule.activity.hasWindowFocus()
        }
        true
    } catch (_: ComposeTimeoutException) {
        false
    }

    /**
     * Finds the picker node [selector] names and taps it, re-finding it if it goes stale.
     *
     * **The re-finding is not padding, and this is not a retry of the assertion.** A `UiObject2`
     * holds an `AccessibilityNodeInfo` captured when it was found, and DocumentsUI is still
     * settling when the node first appears — its list rebinds, the roots strip lays out, a window
     * animates. If the node is replaced in that gap, `click()` throws `StaleObjectException`
     * against the handle rather than missing the target. Measured on a cold API 34 emulator:
     *
     * ```
     * androidx.test.uiautomator.StaleObjectException
     *   at androidx.test.uiautomator.UiObject2.getAccessibilityNodeInfo(UiObject2.java:1042)
     *   at androidx.test.uiautomator.UiObject2.click(UiObject2.java:526)
     * ```
     *
     * So what is retried is *acquiring a handle to a node that has to be there anyway*. **A node
     * that is simply not in this picker is reported rather than retried here** — it comes back as
     * `false`, and [pickTheFixture] answers it with a whole new picker, which is the only thing
     * that rebuilds a list or a window. The MIME mutation's bite is untouched either way: a root
     * that is not in the picker is not found on any attempt or in any picker, and the failure is
     * still "the system picker never showed" rather than a stale one.
     */
    private fun tapPickerNode(selector: BySelector, timeoutMs: Long, ifAbsent: () -> Unit = {}): Boolean {
        var stale: StaleObjectException? = null
        repeat(TAP_ATTEMPTS) { attempt ->
            // ifAbsent only on the first attempt: it navigates, and re-navigating from a screen it
            // already reached would walk away from the node.
            val node = awaitPickerNode(selector, timeoutMs, if (attempt == 0) ifAbsent else ({}))
                ?: return false
            device.waitForIdle()
            try {
                node.click()
                return true
            } catch (e: StaleObjectException) {
                stale = e
            }
        }
        throw AssertionError("$selector kept going stale between finding it and tapping it", stale)
    }

    /**
     * The picker node [selector] names, or null if this picker never showed it.
     *
     * [ifAbsent] runs once, after the first wait comes up empty, and then the wait is repeated. A
     * null return from `findObject` is deliberately not an error there: it is the "already on the
     * right screen" case.
     */
    private fun awaitPickerNode(selector: BySelector, timeoutMs: Long, ifAbsent: () -> Unit) =
        device.wait(Until.findObject(selector), timeoutMs)
            ?: run {
                ifAbsent()
                device.wait(Until.findObject(selector), timeoutMs)
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

        /**
         * The same wait once a picker has already come and gone, and shorter for a reason.
         *
         * What [PICKER_TIMEOUT_MS] is generous about is a cold start: DocumentsUI's process, the
         * fixture's provider process, the root cache. By the second attempt all three are warm and
         * the only thing left to wait on is one screen being laid out — measured at 2.7 to 3.4 s
         * from the picker starting, on cold CI emulators at API 33, 34 and 35. Ten seconds is
         * three times the worst of those, and it is what keeps a genuinely absent root — the MIME
         * mutation — from costing three full-length attempts.
         */
        const val REOPENED_TIMEOUT_MS = 10_000L

        /**
         * How long the app is given to take the window focus back after a back press.
         *
         * Short, because this is asked once per back press and the first one is always asked while
         * the picker is still in front, where it is *expected* to time out.
         */
        const val FOCUS_TIMEOUT_MS = 3_000L

        /**
         * How long this process is given to be able to read the screen at all.
         *
         * Short, and it is not waiting on anything being drawn: the app is already in front
         * when this is asked. It is waiting only on the accessibility window list existing,
         * which either does within a poll or two or -- as in #93 -- not at all.
         */
        const val READABLE_TIMEOUT_MS = 5_000L

        /** `Surface.ROTATION_0`, named rather than `0` so the comparison reads. */
        const val NATURAL_ROTATION = 0

        /**
         * How many pickers the fixture may fail to appear in before that is the finding.
         *
         * Three. Each one is a fresh `PickActivity` -- a fresh window, a fresh accessibility
         * registration, a fresh roots query and a fresh directory load -- so this bounds the thing
         * #93 measured, which is a picker that came up unreadable *once*. A root that is genuinely
         * not offered is absent from all three, which is what keeps #64's MIME mutation red.
         */
        const val PICK_ATTEMPTS = 3

        /**
         * How many back presses may be spent getting out of a picker.
         *
         * One is enough from Recent, two from inside the fixture's own directory. Four leaves room
         * for a picker that has been navigated deeper than this test ever navigates it, and stops
         * well short of the count that would start finishing `MainActivity` instead.
         */
        const val BACK_PRESSES = 4

        /**
         * The package the system picker runs in.
         *
         * Named rather than resolved: `PackageManager.resolveActivity` is deprecated from API 33
         * and its replacement is a lint argument this test does not need to have. A wrong value
         * here cannot pass silently -- it is the first thing [walkThePickerToTheFixture] looks
         * for, so the failure would read `never showed BySelector [PKG='...']` on every device.
         * It is `com.google.android.documentsui` on every `google_apis` emulator image the CI
         * matrix uses and on the Pixel 10 Pro XL.
         */
        const val DOCUMENTS_UI_PACKAGE = "com.google.android.documentsui"

        /**
         * How many times a picker node may be re-found before its staleness is the finding.
         *
         * Three, not "until the timeout". Each attempt already waits up to [PICKER_TIMEOUT_MS] for
         * the node to exist, so this bounds only the settling window after it does; a node that is
         * still being replaced after three of those is telling you something about the device, and
         * a loop that hid it would be the flake rather than the fix.
         */
        const val TAP_ATTEMPTS = 3

        /**
         * The buttons on the framework's app-error dialogs, by resource id.
         *
         * `aerr_wait` is first because it dismisses the dialog without killing the app under it,
         * and the app under it is usually the launcher rather than anything this suite owns.
         * `button1` catches the plainer `BaseErrorDialog` shapes that have no `aerr_` ids.
         */
        val ERROR_DIALOG_BUTTONS = listOf(
            "android:id/aerr_wait",
            "android:id/aerr_close",
            "android:id/button1",
        )

        /** DocumentsUI's drawer button. It carries no text, only this description. */
        const val SHOW_ROOTS_DESCRIPTION = "Show roots"

        /** The detail row `MediaProbe` fills in for anything it could open and identify. */
        const val CONTAINER_LABEL = "Container"
    }
}
