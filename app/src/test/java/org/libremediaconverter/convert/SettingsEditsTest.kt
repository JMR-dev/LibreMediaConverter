package org.libremediaconverter.convert

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.VideoCodec
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The seven one-line edits the settings sheet makes, and what each one leaves alone.
 *
 * ## Why these needed a file of their own
 *
 * `setPreset` was covered. The six beside it — `setContainer`, `setVideoCodec`, `setAudioCodec`,
 * `applySuggestion`, `setQuality`, `setEnginePreference` — and `cancel()` had **no coverage at
 * all**, which is the tell: they are reachable from the JVM suite by exactly the route `setPreset`
 * already takes, and nothing had asked.
 *
 * ## What is actually being asserted
 *
 * Not "the setter sets something". Each of these copies into a nested `OutputSpec`, so the failure
 * worth catching is **a setter that writes the right value into the wrong field, or that rebuilds
 * the spec and silently discards the other two**. So every test here asserts the field it changed
 * *and* that the rest of the spec survived — a `setContainer` implemented as
 * `it.copy(spec = OutputFormat.MP4_H265.spec.copy(container = container))` would pass a test that
 * only checked the container.
 *
 * `ConverterScreenContentTest` cannot cover this: it builds `ConverterActions` itself and never
 * touches the ViewModel. That the *screen* calls these is #156's, and neither implies the other.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class SettingsEditsTest {

    private lateinit var app: Application
    private lateinit var viewModel: ConversionViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        installTestWorkManager(app, Data.EMPTY)
        viewModel = ConversionViewModel(app)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `choosing a preset replaces the whole spec`() {
        viewModel.setPreset(OutputFormat.WEBM_VP9)

        assertEquals(OutputFormat.WEBM_VP9.spec, viewModel.settings.value.spec)
    }

    @Test
    fun `changing the container leaves both codecs alone`() {
        // Moved off the default spec first, and that is load-bearing rather than tidiness. The
        // default IS `OutputFormat.MP4_H265.spec`, so a `setContainer` that rebuilt the spec from
        // that preset instead of from the current one produced an identical answer and the
        // mutation went green. Editing the codecs away from the default first is what makes
        // "the other two survived" an assertion rather than a coincidence.
        viewModel.setPreset(OutputFormat.WEBM_VP9)
        val before = viewModel.settings.value.spec

        viewModel.setContainer(Container.MKV)

        val after = viewModel.settings.value.spec
        assertEquals(Container.MKV, after.container)
        assertEquals("the video codec is not the container's to change", before.videoCodec, after.videoCodec)
        assertEquals("the audio codec is not the container's to change", before.audioCodec, after.audioCodec)
    }

    @Test
    fun `changing the video codec leaves the container and the audio codec alone`() {
        // The transposition this guards against is real: setVideoCodec and setAudioCodec take
        // different enum types, but a copy(...) naming the wrong field compiles wherever the types
        // happen to line up, and the picker would silently set the other one.
        val before = viewModel.settings.value.spec

        viewModel.setVideoCodec(VideoCodec.VP9)

        val after = viewModel.settings.value.spec
        assertEquals(VideoCodec.VP9, after.videoCodec)
        assertEquals(before.container, after.container)
        assertEquals(before.audioCodec, after.audioCodec)
    }

    @Test
    fun `changing the audio codec leaves the container and the video codec alone`() {
        val before = viewModel.settings.value.spec

        viewModel.setAudioCodec(AudioCodec.OPUS)

        val after = viewModel.settings.value.spec
        assertEquals(AudioCodec.OPUS, after.audioCodec)
        assertEquals(before.container, after.container)
        assertEquals(before.videoCodec, after.videoCodec)
    }

    @Test
    fun `applying a suggestion replaces the spec without disturbing quality or engine`() {
        // A suggestion comes from ContainerCapabilities when the current spec is invalid, so it is
        // a whole spec by construction. What it must not do is reset the two settings beside it.
        viewModel.setQuality(QualityTier.BEST)
        viewModel.setEnginePreference(EnginePreference.FORCE_SOFTWARE)

        viewModel.applySuggestion(OutputFormat.MKV_H264.spec)

        val settings = viewModel.settings.value
        assertEquals(OutputFormat.MKV_H264.spec, settings.spec)
        assertEquals(QualityTier.BEST, settings.quality)
        assertEquals(EnginePreference.FORCE_SOFTWARE, settings.enginePreference)
    }

    @Test
    fun `changing the quality leaves the spec and the engine preference alone`() {
        // Both neighbours are moved off their defaults first. Asserting against AUTO -- which is
        // what `ConversionSettings` starts with -- let a `setQuality` that also reset the engine
        // preference to AUTO pass, because the reset and the survival looked identical.
        viewModel.setPreset(OutputFormat.WEBM_VP9)
        viewModel.setEnginePreference(EnginePreference.FORCE_SOFTWARE)
        val before = viewModel.settings.value.spec

        viewModel.setQuality(QualityTier.BEST)

        val settings = viewModel.settings.value
        assertEquals(QualityTier.BEST, settings.quality)
        assertEquals(before, settings.spec)
        assertEquals(
            "quality is not the engine preference's to change",
            EnginePreference.FORCE_SOFTWARE,
            settings.enginePreference,
        )
    }

    @Test
    fun `changing the engine preference leaves the spec and the quality alone`() {
        // Off the defaults for the same reason as the test above: QualityTier.FAST is the starting
        // value, so asserting it here would have been satisfied by a reset as readily as by a
        // survival.
        viewModel.setPreset(OutputFormat.WEBM_VP9)
        viewModel.setQuality(QualityTier.BEST)
        val before = viewModel.settings.value.spec

        viewModel.setEnginePreference(EnginePreference.FORCE_SOFTWARE)

        val settings = viewModel.settings.value
        assertEquals(EnginePreference.FORCE_SOFTWARE, settings.enginePreference)
        assertEquals(before, settings.spec)
        assertEquals(
            "the engine preference is not the quality's to change",
            QualityTier.BEST,
            settings.quality,
        )
    }

    @Test
    fun `editing past every preset leaves no matching preset`() {
        // `matchingPreset` is what the settings sheet reads to decide whether to show a preset as
        // selected or to say "Custom". Editing one field of a preset must drop it out of the list
        // rather than leaving the old one highlighted.
        viewModel.setPreset(OutputFormat.MP4_H265)
        assertEquals(OutputFormat.MP4_H265, viewModel.settings.value.matchingPreset)

        viewModel.setAudioCodec(AudioCodec.FLAC)

        assertNull(
            "an edited spec is no longer any preset, and the sheet says Custom",
            viewModel.settings.value.matchingPreset,
        )
        assertNotEquals(OutputFormat.MP4_H265.spec, viewModel.settings.value.spec)
    }

    @Test
    fun `cancelling with no active job does nothing rather than throwing`() {
        // `activeWorkId?.let(...)` -- the null side. A user can reach Cancel through a state that
        // has already finished, and taking the app down for it would be worse than doing nothing.
        viewModel.cancel()

        assertEquals(ConversionState.Idle, viewModel.state.value)
    }
}
