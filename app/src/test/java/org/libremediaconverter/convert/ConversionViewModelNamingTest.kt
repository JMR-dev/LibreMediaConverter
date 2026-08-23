package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * That the save dialog offers the job's name, not the picker's.
 *
 * `save()` and `suggestedOutputName()` both built the name out of `_settings.value.spec` — the
 * settings as they stand *now*, which is not necessarily the spec the job ran with. Today the
 * pickers are only drawn in the `Ready` state, so they cannot move between enqueue and save, and
 * the name comes out right by accident. Two things make the accident stop: a job picked up by
 * `reattach()`, whose spec was never in this ViewModel's settings at all, and any future in which
 * the pickers stay live while a conversion runs.
 *
 * `suggestedOutputName()` is gone rather than fixed: the answer belongs on the state, which the
 * screen already collects, and an accessor that recomputed it would only be a second place for it
 * to be wrong.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class ConversionViewModelNamingTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingPublisher
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingPublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }

        staged = publisher.createStagingFile("staged-under-a-job-id.mp3").apply { writeBytes(ByteArray(4096)) }
        // What the worker reports for an MP3 job. The staged name is opaque and says nothing about
        // either; these two strings are the only place the job's own output describes itself.
        installTestWorkManager(
            app,
            workDataOf(
                ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConversionWorker.KEY_SUGGESTED_NAME to "holiday_converted.mp3",
                ConversionWorker.KEY_MIME_TYPE to "audio/mpeg",
            ),
        )
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a finished conversion carries the name and type its own job produced`() {
        val converted = convertedViewModel().state.value as ConversionState.Converted

        // Not a name this ViewModel could have worked out. Its settings say MP4 + H.265, and the
        // staged file is called after the job id, so both of these can only have come from the
        // job's own output Data.
        assertEquals("holiday_converted.mp3", converted.suggestedName)
        // Wrong on its own, and worse in company: some providers rewrite a document's extension
        // to match its MIME type, so an MP3 offered as video/webm can arrive with the wrong one.
        assertEquals("audio/mpeg", converted.mimeType)
    }

    @Test
    fun `and the saved name is the job's, not the picker's as it stands now`() {
        val viewModel = convertedViewModel()

        // The picker moves after the job has finished. Today the pickers are drawn only in the
        // Ready state so this cannot happen through the UI -- but a reattached job is this exact
        // situation arrived at differently, its spec having never been in these settings at all.
        viewModel.setPreset(OutputFormat.WEBM_VP9)
        viewModel.save(DESTINATION)
        val saved = awaitState(viewModel.state, "Saved") { it is ConversionState.Saved }

        assertEquals("holiday_converted.mp3", (saved as ConversionState.Saved).displayName)
    }

    @Test
    fun `a result from before the worker reported its own name still gets one`() {
        // Work enqueued by an earlier version carries neither string, and WorkManager keeps
        // finished work for about a week -- so this is the ordinary case for a few days after the
        // change ships, not a corner. The old derivation is kept for exactly that: it is a guess,
        // but it is the same guess the app made before, and there is nothing better to hand.
        installTestWorkManager(app, workDataOf(ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath))
        val viewModel = convertedViewModel()

        // "input" rather than "holiday.mp4" because no provider answers the metadata query here,
        // so the ViewModel falls back to its own placeholder -- which is beside the point. What
        // matters is the extension: `.mp4` is the default preset's, arrived at by the old
        // derivation, and it is the only answer available for a job that reported nothing.
        val converted = viewModel.state.value as ConversionState.Converted
        assertEquals("input_converted.mp4", converted.suggestedName)
    }

    /** A ViewModel driven all the way to [ConversionState.Converted]. */
    private fun convertedViewModel(): ConversionViewModel {
        val viewModel = ConversionViewModel(app, Dispatchers.Unconfined)
        viewModel.onInputPicked(Uri.parse("content://test/holiday.mp4"))
        awaitState(viewModel.state, "Ready") { it is ConversionState.Ready }
        viewModel.convert()
        awaitState(viewModel.state, "Converted") { it is ConversionState.Converted }
        return viewModel
    }

    private companion object {
        val DESTINATION: Uri = Uri.parse("content://test/destination.mp3")
    }
}
