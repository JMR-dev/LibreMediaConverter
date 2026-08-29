package org.libremediaconverter.join

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.RecordingPublisher
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.work.ConcatWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The two layers that refuse a short join, refusing it with one sentence.
 *
 * ## Why this is not "assert a constant equals itself"
 *
 * `ConcatWorker` and `JoinViewModel` both reject a join of fewer than two files, and before #158
 * each carried **its own copy of the literal**. Only the worker's was pinned — by `RefusedJobTest`,
 * added in #139 — so the wording on the screen could drift away from the wording in the job with no
 * test saying anything, for one message the user sees from one condition.
 *
 * Sharing a constant makes them agree by construction. What it does *not* do is prove that both
 * layers still reach it: a refactor that stops `JoinViewModel` refusing at all, or that gives it a
 * different message, passes any test that only reads `TOO_FEW_INPUTS_MESSAGE`. So each layer is
 * driven for real here — the ViewModel through `onInputsPicked`, the worker through `doWork` — and
 * the assertion is that the two answers are **the same string**, taken from two running layers
 * rather than from one declaration.
 *
 * That is the shape `CLAUDE.md` asks for: revert the sharing and this goes red, because the two
 * sites drift the moment they are allowed to.
 *
 * ## Scope
 *
 * The arity guard's own behaviour on the ViewModel side — that it refuses one file, that it accepts
 * two, that it claims ownership first — is #155's, and this deliberately does not duplicate it.
 * This file is about the *agreement between layers*, which is what #158 changed.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class SharedFailureMessagesTest {

    private lateinit var app: Application
    private lateinit var viewModel: JoinViewModel

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ConversionDependencies.publisher = { RecordingPublisher(app) }
        installTestWorkManager(app, workDataOf(ConcatWorker.KEY_OUTPUT_PATH to "/dev/null"))
        viewModel = JoinViewModel(app)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `both layers refuse a one-file join with the same sentence`() {
        // The ViewModel, refusing before anything is enqueued.
        viewModel.onInputsPicked(listOf(ONE_FILE))
        val fromScreen = (viewModel.state.value as JoinState.Failed).message

        // The worker, refusing a job that reached the queue anyway -- which it can, because
        // ConcatWorker.request(...) takes a List<Uri> and checks nothing about its length.
        val result = runBlocking { worker(ONE_FILE).doWork() }
        val fromJob = (result as ListenableWorker.Result.Failure)
            .outputData.getString(ConcatWorker.KEY_ERROR)

        assertEquals(
            "the screen and the job must say the same thing about the same refusal",
            fromScreen,
            fromJob,
        )
        // And that the shared sentence is the one either layer would have written on its own,
        // rather than both having drifted together to something else.
        assertEquals(ConcatWorker.TOO_FEW_INPUTS_MESSAGE, fromScreen)
    }

    private fun worker(vararg inputs: Uri): ConcatWorker = TestListenableWorkerBuilder<ConcatWorker>(
        context = app,
        inputData = workDataOf(
            ConcatWorker.KEY_INPUT_URIS to inputs.map(Uri::toString).toTypedArray(),
            ConcatWorker.KEY_TOTAL_BYTES to 1024L,
        ),
        runAttemptCount = 0,
    ).build()

    private companion object {
        val ONE_FILE: Uri = Uri.parse("content://test/holiday.mp4")
    }
}
