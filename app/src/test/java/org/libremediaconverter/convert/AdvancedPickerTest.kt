package org.libremediaconverter.convert

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ContainerCapabilities
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.Validation
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.ui.TestTags
import org.robolectric.RobolectricTestRunner

/**
 * The gate over the Advanced chips, and the error card that deliberately sits outside it.
 *
 * Two defects, and they pull in opposite directions.
 *
 * The first is the chips escaping the gate, or never being reachable through it. `AdvancedPicker`
 * is the one leaf on this screen that is not stateless -- `expanded` is its own `rememberSaveable`
 * -- and Container, Video and Audio live inside `AnimatedVisibility(visible = expanded)`. Nothing
 * else on the screen hides anything, so a refactor that flattened the panel, or wired the toggle to
 * a state nobody reads, would render an app that looks reasonable in a screenshot and is wrong.
 *
 * The second is the opposite mistake, and it is the one this file exists for: **moving the
 * `ValidationError` call inside the `AnimatedVisibility`**. It is invoked after that block, so an
 * invalid spec explains itself and offers one-tap fixes *while the section is collapsed*. That is
 * the only route out of an invalid spec for a user who never opened Advanced -- and since the only
 * way to reach an invalid spec is through Advanced, hiding the way out behind the same toggle looks
 * locally sensible and is a trap. Tidying the two `if` blocks into one is a plausible edit, it
 * compiles, and until this file existed nothing went red. Every assertion about the error card here
 * therefore runs with the toggle untouched, and asserts the panel is absent in the same test, so a
 * future `expanded = true` default cannot quietly satisfy it either.
 *
 * The invalid specs come from [ContainerCapabilities.validate] rather than from a hand-built
 * [Validation.Invalid], so the messages and the suggestions are the real pairing. A hand-built one
 * would keep passing after `validate` stopped producing anything like it.
 *
 * Node location is by the three separate chip-row tags, never by text. `"Copy"` and `"None"` are
 * each both a [VideoCodec] and an [AudioCodec], and `"MP3"` and `"FLAC"` are each both a
 * [Container] and an [AudioCodec], so a text matcher over the open panel is ambiguous for four
 * chips -- which is what the separate tags are for.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class AdvancedPickerTest {

    // The rule is the **v2** one (`androidx.compose.ui.test.junit4.v2`) while
    // [StateRestorationTester], which takes it below, is not. The mismatched imports are
    // deliberate: the v2 package has no tester of its own and the two do interoperate.
    @get:Rule
    val composeRule = createComposeRule()

    private val restoration = StateRestorationTester(composeRule)

    private val containers = mutableListOf<Container>()
    private val videoCodecs = mutableListOf<VideoCodec>()
    private val audioCodecs = mutableListOf<AudioCodec>()
    private val applied = mutableListOf<OutputSpec>()

    // --- the expand gate ----------------------------------------------------

    @Test
    fun `the three chip rows appear only while the panel is expanded`() {
        setPicker()

        assertPanelHidden()

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_PANEL).assertExists()
        ROW_TAGS.forEach { composeRule.onNodeWithTag(it).assertExists() }

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()

        // The exit transition outlives the click, so absence has to be waited for rather than
        // asserted straight away -- unlike the initial collapsed state, which has no animation
        // in flight.
        composeRule.waitUntil { nodeCount(TestTags.Converter.ADVANCED_PANEL) == 0 }
        assertPanelHidden()
    }

    /** The toggle is the only affordance the collapsed picker offers, so it has to say so. */
    @Test
    fun `the toggle names the direction it will move in`() {
        setPicker()

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).assertTextEquals("Advanced")

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE)
            .assertTextEquals("Hide advanced")
    }

    /**
     * The four colliding labels, one per row.
     *
     * `"Copy"` is a video codec *and* an audio codec; `"MP3"` is a container *and* an audio codec.
     * Clicking each through its own row is what proves the rows are wired to different callbacks
     * -- a picker that handed every chip to `onAudioCodec` would look identical on screen.
     */
    @Test
    fun `each chip row reports to its own callback, including the labels that collide`() {
        setPicker()
        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()

        chipIn(TestTags.Converter.ADVANCED_VIDEO_CHIPS, "Copy").performClick()

        assertEquals(listOf(VideoCodec.COPY), videoCodecs)
        assertEquals(emptyList<AudioCodec>(), audioCodecs)

        chipIn(TestTags.Converter.ADVANCED_AUDIO_CHIPS, "Copy").performClick()

        assertEquals(listOf(AudioCodec.COPY), audioCodecs)

        chipIn(TestTags.Converter.ADVANCED_CONTAINER_CHIPS, "MP3").performClick()

        assertEquals(listOf(Container.MP3), containers)
        // Still only the one audio click. `MP3` is an AudioCodec label too, and the container row
        // must not be reporting through that callback.
        assertEquals(listOf(AudioCodec.COPY), audioCodecs)
    }

    // --- the error card, which is outside the gate --------------------------

    /**
     * The headline case. Dropping both tracks is reachable from the collapsed screen -- the
     * `None`/`None` pair is set inside Advanced, but the user can close it again -- and the
     * explanation has to still be there.
     */
    @Test
    fun `an empty output explains itself while the section is collapsed`() {
        val spec = OutputSpec(Container.MP4, VideoCodec.NONE, AudioCodec.NONE)
        val invalid = invalidFor(spec)

        assertEquals("This would produce an empty file — keep at least one track.", invalid.message)

        setPicker(spec, invalid)

        assertPanelHidden()
        composeRule.onNodeWithTag(TestTags.Converter.VALIDATION_ERROR).assertExists()
        composeRule.onNodeWithText(invalid.message).assertIsDisplayed()
    }

    @Test
    fun `a codec the container cannot hold explains itself while the section is collapsed`() {
        val spec = OutputSpec(Container.WEBM, VideoCodec.H264, AudioCodec.OPUS)
        val invalid = invalidFor(spec)

        assertEquals("WebM cannot hold H.264 video.", invalid.message)

        setPicker(spec, invalid)

        assertPanelHidden()
        composeRule.onNodeWithText(invalid.message).assertIsDisplayed()
    }

    /**
     * Clicking a suggestion, with the toggle never touched.
     *
     * The second suggestion rather than the first, and its count pinned first: with one suggestion
     * a picker that handed every chip `suggestions[0]` would pass, and `onNodeWithTag` on a
     * suggestion index that no longer exists reports an unhelpful matcher failure rather than
     * saying the list shrank.
     */
    @Test
    fun `a suggestion chip applies its own spec without the section ever being opened`() {
        val spec = OutputSpec(Container.WEBM, VideoCodec.H264, AudioCodec.OPUS)
        val invalid = invalidFor(spec)

        assertEquals(2, invalid.suggestions.size)
        val second = invalid.suggestions[1]

        setPicker(spec, invalid)

        assertPanelHidden()
        composeRule.onNodeWithTag(TestTags.Converter.suggestion(1)).assertTextEquals(describe(second))
        composeRule.onNodeWithTag(TestTags.Converter.suggestion(1)).performClick()

        assertEquals(listOf(second), applied)
        // What the chips offer is what `validate` said would work, not a repair of the test's own.
        assertTrue(
            "suggestion $second should itself validate",
            ContainerCapabilities.validate(second, PROBE).isValid,
        )
    }

    /** A valid spec has nothing to say, collapsed or not. */
    @Test
    fun `a valid spec renders no error card`() {
        setPicker()

        composeRule.onNodeWithTag(TestTags.Converter.VALIDATION_ERROR).assertDoesNotExist()

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()

        composeRule.onNodeWithTag(TestTags.Converter.VALIDATION_ERROR).assertDoesNotExist()
    }

    // --- recreation ---------------------------------------------------------

    /**
     * `expanded` is the only `rememberSaveable` on either screen's leaves.
     *
     * `MainActivity` declares no `configChanges`, so a rotation destroys and rebuilds the whole
     * composition. A panel the user opened, set three chips in, and left open must not close
     * itself on the way back. `remember` would.
     *
     * What this cannot see is the saved *representation* -- `StateRestorationTester` saves into an
     * in-memory map rather than a `Bundle`. `AdvancedPanelSavedStateTest` covers that half.
     */
    @Test
    fun `an open panel is still open after recreation`() {
        restoration.setContent {
            AdvancedPicker(
                spec = VALID_SPEC,
                validation = Validation.Valid,
                onContainer = {},
                onVideoCodec = {},
                onAudioCodec = {},
                onSuggestion = {},
            )
        }

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE).performClick()
        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_PANEL).assertExists()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_PANEL).assertExists()
        ROW_TAGS.forEach { composeRule.onNodeWithTag(it).assertExists() }
        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_TOGGLE)
            .assertTextEquals("Hide advanced")
    }

    /** The default has to survive too, or the panel would spring open on every rotation. */
    @Test
    fun `a collapsed panel is still collapsed after recreation`() {
        restoration.setContent {
            AdvancedPicker(
                spec = VALID_SPEC,
                validation = Validation.Valid,
                onContainer = {},
                onVideoCodec = {},
                onAudioCodec = {},
                onSuggestion = {},
            )
        }

        assertPanelHidden()

        restoration.emulateSavedInstanceStateRestore()

        assertPanelHidden()
    }

    // --- helpers ------------------------------------------------------------

    private fun setPicker(spec: OutputSpec = VALID_SPEC, validation: Validation = Validation.Valid) {
        composeRule.setContent {
            AdvancedPicker(
                spec = spec,
                validation = validation,
                onContainer = { containers += it },
                onVideoCodec = { videoCodecs += it },
                onAudioCodec = { audioCodecs += it },
                onSuggestion = { applied += it },
            )
        }
    }

    /** The whole panel, by every tag it owns, so a partial escape counts as a failure. */
    private fun assertPanelHidden() {
        composeRule.onNodeWithTag(TestTags.Converter.ADVANCED_PANEL).assertDoesNotExist()
        ROW_TAGS.forEach { composeRule.onNodeWithTag(it).assertDoesNotExist() }
    }

    private fun nodeCount(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun chipIn(rowTag: String, label: String) =
        composeRule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(rowTag)))

    private fun invalidFor(spec: OutputSpec): Validation.Invalid {
        val validation = ContainerCapabilities.validate(spec, PROBE)
        return validation as? Validation.Invalid
            ?: throw AssertionError("$spec was expected to be invalid, but validate said $validation")
    }

    private companion object {
        val ROW_TAGS = listOf(
            TestTags.Converter.ADVANCED_CONTAINER_CHIPS,
            TestTags.Converter.ADVANCED_VIDEO_CHIPS,
            TestTags.Converter.ADVANCED_AUDIO_CHIPS,
        )

        val VALID_SPEC = OutputSpec(Container.MP4, VideoCodec.H264, AudioCodec.AAC)

        /** An ordinary H.264/AAC MP4, so the suggestions have a real source to repair towards. */
        val PROBE = InputProbe(
            videoCodec = "h264",
            audioCodec = "aac",
            durationMs = 90_000,
            container = Container.MP4,
        )
    }
}
