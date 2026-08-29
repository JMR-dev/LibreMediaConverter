package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.workDataOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.join.JoinState
import org.libremediaconverter.join.JoinViewModel
import org.libremediaconverter.join.joinActions
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.work.ConcatWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * That each affordance is wired to the ViewModel method it is named after.
 *
 * ## What this covers that no other test can
 *
 * `ConverterScreenContentTest`, `ConverterStateAffordancesTest` and `JoinScreenContentTest` all
 * drive the **stateless** content composables, which build their own `ConverterActions`. So the
 * wiring — the list of `viewModel::` references the stateful outer hands down — was seen by nothing
 * in the suite.
 *
 * ## The hazard is narrower than "seventeen bindings", and this says so
 *
 * #156 was filed claiming a transposition of any two bindings would survive the suite. That is not
 * true, and it was worth checking rather than testing on the assumption:
 *
 * | swap | result |
 * |---|---|
 * | `onVideoCodec` ↔ `onAudioCodec` | **rejected by the compiler** |
 * | `onCancel` ↔ `onReset` | **compiles** |
 *
 * Every typed binding — container, both codecs, preset, suggestion, quality, engine preference —
 * takes a distinct parameter type, so the compiler is already the test. Writing assertions for
 * those would be theatre.
 *
 * **The `() -> Unit` bindings are the real gap**, because they are interchangeable to the compiler:
 * two on the converter screen (`onCancel`, `onReset`) and three on the join screen (`onJoin`,
 * `onCancel`, `onReset`). A Cancel that discards the finished file, or a Join that cancels, is a
 * one-character mistake that ships.
 *
 * ## How they are told apart
 *
 * By effect, not by a recording double. `reset()` sets the state to `Idle`; `cancel()` with no
 * active job leaves it alone (`ConversionViewModel.cancel` is `activeWorkId?.let(...)`, and
 * `SettingsEditsTest` pins that). Driving each from a non-`Idle` state is therefore enough to say
 * which one ran.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ScreenWiringTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ConversionDependencies.publisher = { RecordingPublisher(app) }
        ConversionDependencies.probe = { _, _ -> org.libremediaconverter.model.InputProbe() }
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    // --- the converter screen ----------------------------------------------

    @Test
    fun `Start over resets, and Cancel does not`() {
        // The transposition that compiles. If onReset were bound to cancel, this stays on Ready.
        installTestWorkManager(app, Data.EMPTY)
        val pick = ParkedPickDispatcher()
        val viewModel = ConversionViewModel(app, pickDispatcher = pick)
        val actions = converterActions(viewModel, onPickInput = {}, onConvert = {}, onSave = {})
        viewModel.onInputPicked(INPUT_URI)
        pick.runAll()
        assertNotEquals(
            "the fixture needs a non-Idle state or neither action is observable",
            ConversionState.Idle,
            viewModel.state.value,
        )

        actions.onReset()

        assertEquals(ConversionState.Idle, viewModel.state.value)
    }

    @Test
    fun `Cancel leaves the picked file on screen`() {
        // The other half. Without it, a wiring with BOTH actions bound to reset passes the test
        // above -- and that is exactly what a copy-paste of the wrong line produces.
        installTestWorkManager(app, Data.EMPTY)
        val pick = ParkedPickDispatcher()
        val viewModel = ConversionViewModel(app, pickDispatcher = pick)
        val actions = converterActions(viewModel, onPickInput = {}, onConvert = {}, onSave = {})
        viewModel.onInputPicked(INPUT_URI)
        pick.runAll()
        val before = viewModel.state.value

        actions.onCancel()

        assertEquals(
            "Cancel must not throw away the pick the way Start over does",
            before,
            viewModel.state.value,
        )
    }

    @Test
    fun `each settings affordance reaches the setting it is named after`() {
        // The typed bindings. The compiler already rejects a transposition among these, so this is
        // not that assertion -- it is the cheaper one that each is bound to *something*, and that a
        // binding dropped to `{}` during an edit would be caught.
        installTestWorkManager(app, Data.EMPTY)
        val pick = ParkedPickDispatcher()
        val viewModel = ConversionViewModel(app, pickDispatcher = pick)
        val actions = converterActions(viewModel, onPickInput = {}, onConvert = {}, onSave = {})

        actions.onPreset(OutputFormat.WEBM_VP9)
        assertEquals(OutputFormat.WEBM_VP9.spec, viewModel.settings.value.spec)

        actions.onContainer(Container.MKV)
        assertEquals(Container.MKV, viewModel.settings.value.spec.container)

        actions.onVideoCodec(VideoCodec.H264)
        assertEquals(VideoCodec.H264, viewModel.settings.value.spec.videoCodec)

        actions.onAudioCodec(AudioCodec.FLAC)
        assertEquals(AudioCodec.FLAC, viewModel.settings.value.spec.audioCodec)

        actions.onQuality(QualityTier.BEST)
        assertEquals(QualityTier.BEST, viewModel.settings.value.quality)

        actions.onEnginePreference(EnginePreference.FORCE_SOFTWARE)
        assertEquals(EnginePreference.FORCE_SOFTWARE, viewModel.settings.value.enginePreference)

        actions.onSuggestion(OutputFormat.MP4_H264.spec)
        assertEquals(OutputFormat.MP4_H264.spec, viewModel.settings.value.spec)
    }

    @Test
    fun `the launcher-backed actions are the ones the screen supplies`() {
        // Not wired to the ViewModel at all, deliberately -- they need an ActivityResultLauncher.
        // Asserted so that a later edit routing one of them at the ViewModel is noticed.
        installTestWorkManager(app, Data.EMPTY)
        val pick = ParkedPickDispatcher()
        val viewModel = ConversionViewModel(app, pickDispatcher = pick)
        val called = mutableListOf<String>()
        val actions = converterActions(
            viewModel,
            onPickInput = { called += "pick" },
            onConvert = { called += "convert" },
            onSave = { called += "save:$it" },
        )

        actions.onPickInput()
        actions.onConvert()
        actions.onSave("holiday.mp4")

        assertEquals(listOf("pick", "convert", "save:holiday.mp4"), called)
    }

    // --- the join screen, where three are interchangeable -------------------

    @Test
    fun `Start over resets the join, and Cancel does not`() {
        installTestWorkManager(app, workDataOf(ConcatWorker.KEY_OUTPUT_PATH to "/dev/null"))
        val pick = ParkedPickDispatcher()
        val viewModel = JoinViewModel(app, pickDispatcher = pick)
        val actions = joinActions(viewModel, onPickInputs = {}, onSave = {})
        viewModel.onInputsPicked(TWO_INPUTS)
        pick.runAll()
        assertTrue(
            "the fixture needs a non-Idle state: ${viewModel.state.value}",
            viewModel.state.value !is JoinState.Idle,
        )

        actions.onReset()

        assertEquals(JoinState.Idle, viewModel.state.value)
    }

    @Test
    fun `Cancel leaves the picked files on screen`() {
        installTestWorkManager(app, workDataOf(ConcatWorker.KEY_OUTPUT_PATH to "/dev/null"))
        val pick = ParkedPickDispatcher()
        val viewModel = JoinViewModel(app, pickDispatcher = pick)
        val actions = joinActions(viewModel, onPickInputs = {}, onSave = {})
        viewModel.onInputsPicked(TWO_INPUTS)
        pick.runAll()
        val before = viewModel.state.value

        actions.onCancel()

        assertEquals(before, viewModel.state.value)
    }

    @Test
    fun `Join starts the job rather than cancelling or resetting it`() {
        // The third of the join screen's interchangeable trio, and the one whose transposition is
        // worst: a Join button bound to cancel does nothing at all, which reads as a dead button.
        installTestWorkManager(app, workDataOf(ConcatWorker.KEY_OUTPUT_PATH to "/dev/null"))
        val pick = ParkedPickDispatcher()
        val viewModel = JoinViewModel(app, pickDispatcher = pick)
        val actions = joinActions(viewModel, onPickInputs = {}, onSave = {})
        viewModel.onInputsPicked(TWO_INPUTS)
        pick.runAll()

        actions.onJoin()

        assertTrue(
            "Join must leave Ready for a running state, not sit still and not go Idle: " +
                "${viewModel.state.value}",
            viewModel.state.value is JoinState.Joining || viewModel.state.value is JoinState.Joined,
        )
    }

    private companion object {
        val INPUT_URI: Uri = Uri.parse("content://test/holiday.mov")
        val TWO_INPUTS = listOf(
            Uri.parse("content://test/a.mp4"),
            Uri.parse("content://test/b.mp4"),
        )
    }
}
