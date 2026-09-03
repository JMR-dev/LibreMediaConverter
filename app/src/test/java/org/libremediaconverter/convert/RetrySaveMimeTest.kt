package org.libremediaconverter.convert

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.ui.TestTags
import org.libremediaconverter.work.ConversionWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The save dialog opens with the type the *job* produced, not the type the picker is showing now.
 *
 * `ConverterScreen.kt:80` — `state.pendingSave()?.mimeType ?: settings.spec.mimeType` — had never
 * taken its left-hand side. Its comment records what the line is for:
 *
 * > a retry offered after a failed save opens the dialog with the type its first attempt used —
 * > the cast answered null for a `Failed`, and the fallback below is the current picker, which a
 * > reattached job never set.
 *
 * So the untested half is the fix, and the tested half is the fallback it was added to stop being
 * used.
 *
 * ## This revises a named exemption, deliberately
 *
 * `FailedSaveRetryTest`'s KDoc lists this line under "Not asserted here, so each is a decision
 * rather than an omission":
 *
 * > It lives in the entry point, above the `ScreenContent` seam, and reaching it needs a real
 * > ViewModel inside a composition.
 *
 * That was true when written. `AdaptiveShellTest` (#173) then established exactly that capability,
 * and #200 added the two `ShadowActivity` mechanics that let a test read what a launcher launched.
 * The reason the exemption gave no longer holds, so the exemption is withdrawn rather than left to
 * be taken at face value — the same shape as #141 revising #84's boundary. That KDoc is corrected
 * in this change.
 *
 * ## Why the job is reattached rather than run
 *
 * The screen composes its own ViewModel through `viewModel()`, so nothing can be injected into it.
 * A job finished before the composition is the one route to a `Converted` state carrying output
 * `Data` this test chose — and it is also the case the line exists for, since a reattached job's
 * spec "was never in these settings at all".
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class RetrySaveMimeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var staged: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        staged = OutputPublisher(app).createStagingFile("holiday.mkv").apply { writeBytes(ByteArray(4096)) }
    }

    @After
    fun tearDown() = ConversionDependencies.reset()

    @Test
    fun `the save dialog offers the type the job produced, not the one the picker is showing`() {
        finishAJobProducing(JOB_MIME_TYPE)
        composeRule.setContent { ConverterScreen() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.SAVE_FILE).performScrollTo().performClick()
        composeRule.waitForIdle()

        val intent = requireNotNull(shadowOf(composeRule.activity).nextStartedActivityForResult) {
            "the save dialog was never launched"
        }.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(JOB_MIME_TYPE, intent.type)
        // The fixture is only meaningful while the two differ; without this the assertion above
        // would pass just as well against the fallback.
        assertNotEquals(
            "the picker's own type must differ, or this test proves nothing",
            JOB_MIME_TYPE,
            OutputFormat.MP4_H265.spec.mimeType,
        )
    }

    /**
     * A conversion that finished while nothing was watching, which is what `reattach()` picks up.
     *
     * `SucceedingWorkerFactory` reports this output `Data` for whatever is enqueued, so the job
     * lands `SUCCEEDED` carrying a staged path that exists — the two things `Reattachment.choose`
     * requires of a finished job.
     */
    private fun finishAJobProducing(mimeType: String) {
        installTestWorkManager(
            app,
            workDataOf(
                ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConversionWorker.KEY_SUGGESTED_NAME to "holiday.mkv",
                ConversionWorker.KEY_MIME_TYPE to mimeType,
            ),
        )
        WorkManager.getInstance(app).enqueue(
            ConversionWorker.request(
                inputUri = Uri.parse("content://test/holiday.mkv"),
                displayName = "holiday.mkv",
                sizeBytes = 4_096L,
            ),
        ).result.get()
    }

    private companion object {
        /** Matroska, against the MP4 the picker defaults to. */
        const val JOB_MIME_TYPE = "video/x-matroska"
    }
}
