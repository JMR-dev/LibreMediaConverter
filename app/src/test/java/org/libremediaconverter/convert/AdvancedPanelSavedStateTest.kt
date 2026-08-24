package org.libremediaconverter.convert

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.createDrainedComposeRule
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.Validation
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner

/**
 * What `AdvancedPickerTest`'s restoration test cannot see.
 *
 * `StateRestorationTester` saves into an **in-memory map**, never a `Bundle`. That is enough to
 * discriminate `rememberSaveable` from `remember`, and it is where it stops: the map holds object
 * references, so a value the platform could never parcel goes in and comes back out looking green.
 * `AppRootRestorationTest` has the same blind spot and `DestinationSaverTest` is the split it
 * prompted; this is that split for `expanded`, the only `rememberSaveable` on either screen's
 * leaves.
 *
 * ### The saved representation is not the Boolean
 *
 * `var expanded by rememberSaveable { mutableStateOf(false) }` passes no `stateSaver`, so
 * `autoSaver` saves **the `MutableState` itself**, not the `false` inside it. That works only
 * because `mutableStateOf` on Android returns a `Parcelable` implementation -- the same call on a
 * plain JVM returns one that is not. So what stands between an open panel and a rotation that
 * closes it is a platform-specific detail of a factory function nothing here names directly, and
 * an in-memory map cannot tell the two apart.
 *
 * Pinning it is the move `DestinationSaverTest` makes about names versus ordinals. Passing an
 * explicit `stateSaver` would save a bare `Boolean` instead and is a perfectly reasonable edit --
 * it is just not the one in the tree, and it should be made on purpose rather than discovered
 * after a rotation.
 *
 * ### Shared bite, stated rather than implied
 *
 * `rememberSaveable` -> `remember` empties the registry, so it reddens this file *and* the
 * restoration test in `AdvancedPickerTest`. Both failures belong in any report of that mutation.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class AdvancedPanelSavedStateTest {

    // Not `createComposeRule()` directly: see [drainEscapedCoroutineErrors].
    @get:Rule
    val composeRule = createDrainedComposeRule()

    /**
     * `canBeSaved = { true }` deliberately.
     *
     * A predicate mirroring what a `Bundle` accepts would be a hand-written copy of the thing
     * under test, and a `false` from it *drops* the entry silently -- so the test would fail by
     * finding nothing saved, which is also how a `remember` regression fails. Two causes, one
     * symptom, is not a test. The type is checked on the way out instead.
     */
    private val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })

    @Test
    fun `the panel registers its open state with the registry, and nothing else`() {
        setPicker()

        // Collapsed is a saved value, not an absent one: `rememberSaveable` registers its provider
        // on first composition, whatever the state happens to be. Exactly one, because `expanded`
        // is the only saveable in the subtree -- a second would mean something else began saving.
        assertEquals(1, savedValues().size)

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()

        val saved = theOneSavedValue()
        assertTrue("saved as ${saved?.javaClass?.name}", saved is MutableState<*>)
        assertEquals(true, (saved as MutableState<*>).value)
    }

    @Test
    fun `the open panel survives a real Parcel, not just an in-memory map`() {
        setPicker()
        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()

        val saved = theOneSavedValue()

        // The claim the restoration test cannot make. A `MutableState` that was not `Parcelable`
        // would satisfy `StateRestorationTester` and then be dropped by the platform.
        assertTrue("saved as ${saved?.javaClass?.name}", saved is Parcelable)

        val restored = throughARealBundle(saved as Parcelable)

        assertTrue("restored as ${restored.javaClass.name}", restored is MutableState<*>)
        assertEquals(true, (restored as MutableState<*>).value)
    }

    private fun setPicker() {
        composeRule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                AdvancedPicker(
                    spec = OutputSpec(Container.MP4, VideoCodec.H264, AudioCodec.AAC),
                    validation = Validation.Valid,
                    onContainer = {},
                    onVideoCodec = {},
                    onAudioCodec = {},
                    onSuggestion = {},
                )
            }
        }
    }

    /** Every value the picker hands the host to persist, keys dropped -- they are positional. */
    private fun savedValues(): List<Any?> = composeRule.runOnIdle { registry.performSave().values.flatten() }

    /**
     * The single saved value, asserted rather than assumed.
     *
     * `single()` on an empty list throws `NoSuchElementException: List is empty`, which names
     * neither the panel nor the registry -- and an empty registry is exactly how the
     * `rememberSaveable` -> `remember` regression shows up here.
     */
    private fun theOneSavedValue(): Any? {
        val values = savedValues()
        assertEquals("the panel should register exactly one saved value", 1, values.size)
        return values.first()
    }

    /** A write and a read through a real `Parcel`, which is what the tester's map stands in for. */
    private fun throughARealBundle(value: Parcelable): Parcelable {
        val bundle = Bundle().apply { putParcelable(KEY, value) }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            val restored = requireNotNull(parcel.readBundle(javaClass.classLoader)) {
                "the Bundle did not survive the Parcel"
            }
            requireNotNull(restored.getParcelable(KEY, Parcelable::class.java)) {
                "the saved state did not survive the Parcel"
            }
        } finally {
            parcel.recycle()
        }
    }

    private companion object {
        const val KEY = "expanded"
    }
}
