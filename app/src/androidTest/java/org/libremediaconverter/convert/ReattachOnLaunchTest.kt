package org.libremediaconverter.convert

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.join.JoinState
import org.libremediaconverter.join.JoinViewModel
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.model.Engine
import org.libremediaconverter.work.ConcatWorker
import org.libremediaconverter.work.ConversionWorker
import org.libremediaconverter.work.JobTags
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Stands in for a worker whose job is already over.
 *
 * Reattachment is defined entirely by what WorkManager can hand back — the worker class name
 * as a tag, the tags the request carried, and the output `Data` — so a job with that shape is
 * all the ViewModel needs to see. Producing one by running a real transcode would take minutes
 * and would test the engines, which have their own suites. This echoes its input as its result,
 * which lets a test state any finished job in a line.
 */
class EchoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success(inputData)
}

/**
 * The defect: a conversion that outlives the process becomes unreachable.
 *
 * WorkManager's queue survives process death — that is why the app uses it — but the ViewModel
 * held its job id in a plain field, so the next launch started at Idle while the finished output
 * sat in `cacheDir` with no route to it from the UI. The realistic window is after the transcode
 * finishes and before the user taps Save: the foreground service is gone and the process is an
 * ordinary background one that may be reclaimed hours before the user comes back.
 *
 * A ViewModel constructed here *is* that next launch: it is a fresh instance with no memory of
 * the work, exactly as after `am kill`. The real [WorkManager] is used rather than
 * `WorkManagerTestInitHelper`, whose `setDelegate` replaces the singleton for the whole process
 * and would silently turn `ConversionWorkerTest` — which exists to exercise the real WorkManager
 * path, foreground service included — into a synchronous test double, depending on class order.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ReattachOnLaunchTest {

    private val app = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as Application
    private val workManager = WorkManager.getInstance(app)

    @Before
    fun clearTheQueue() = emptyQueueAndStaging()

    @After
    fun leaveNothingBehind() = emptyQueueAndStaging()

    /**
     * The claim the whole fix rests on, checked against the production request builder rather
     * than assumed: `WorkRequest.Builder` seeds every request's tags with its worker class name,
     * so the app's own work is findable with nothing persisted anywhere.
     */
    @Test
    fun aConversionRequestIsFindableByItsWorkerClassName() {
        val request = ConversionWorker.request(
            // A file that does not exist, so the job fails within seconds instead of transcoding.
            // What is under test is the request's tags, which are written when it is enqueued.
            inputUri = Uri.fromFile(File(app.cacheDir, "no_such_input.mp4")),
            displayName = "holiday.mp4",
            sizeBytes = 4_096L,
        )
        workManager.enqueue(request).result.get()
        workManager.cancelWorkById(request.id).result.get()
        val info = awaitFinished(request.id)

        assertTrue(
            "no worker class name in ${info.tags}",
            info.tags.contains(ConversionWorker::class.java.name),
        )
        assertEquals("holiday.mp4", JobTags.displayNameOf(info.tags))
        assertEquals(4_096L, JobTags.sizeBytesOf(info.tags))
    }

    @Test
    fun reattachesToAConversionThatFinishedWhileTheViewModelWasGone() {
        val staged = stage("holiday_converted.mp4")
        finishedJob(
            tags = listOf(
                ConversionWorker::class.java.name,
                JobTags.displayName("holiday.mp4"),
                JobTags.sizeBytes(4_096L),
            ),
            output = workDataOf(
                ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConversionWorker.KEY_ENGINE_USED to Engine.FFMPEG.name,
                ConversionWorker.KEY_ROUTE_REASON to "test route",
            ),
        )

        val converted = awaitConversion<ConversionState.Converted>()

        assertEquals(staged.absolutePath, converted.staged.absolutePath)
        assertEquals("holiday.mp4", converted.input.displayName)
        assertEquals(4_096L, converted.input.sizeBytes)
        assertEquals(Engine.FFMPEG.name, converted.engineUsed)
    }

    /**
     * The Save button has to be reachable *and* mean something. A staged file the OS reclaimed
     * out of the cache — or one a previous save already published and deleted — would otherwise
     * be offered and fail on tap.
     */
    @Test
    fun ignoresAFinishedConversionWhoseStagedFileIsGone() {
        val missing = File(File(app.cacheDir, "conversions"), "vanished_converted.mp4")
        missing.delete()
        finishedJob(
            tags = listOf(ConversionWorker::class.java.name, JobTags.displayName("vanished.mp4")),
            output = workDataOf(ConversionWorker.KEY_OUTPUT_PATH to missing.absolutePath),
        )

        assertStaysIdle(conversionViewModel())
    }

    /**
     * The shape a device actually produced: two SUCCEEDED jobs reporting the same output path,
     * with one file on disk, because the staging name is derived from the input's display name.
     * The file has to stay reachable — losing it is the defect — while the card must not claim
     * an input that may belong to the other job.
     */
    @Test
    fun offersAFileTwoJobsClaimWithoutAttributingItToEither() {
        val staged = stage("input_converted.mp4")
        val output = workDataOf(ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath)
        finishedJob(
            tags = listOf(ConversionWorker::class.java.name, JobTags.displayName("input.mp4")),
            output = output,
        )
        finishedJob(
            tags = listOf(ConversionWorker::class.java.name, JobTags.displayName("input.mkv")),
            output = output,
        )

        val converted = awaitConversion<ConversionState.Converted>()

        assertEquals(staged.absolutePath, converted.staged.absolutePath)
        assertTrue(
            "attributed an aliased file to one of the jobs: ${converted.input.displayName}",
            converted.input.displayName !in setOf("input.mp4", "input.mkv"),
        )
    }

    @Test
    fun reattachesToAConversionStillWaitingInTheQueue() {
        queuedJob(
            tags = listOf(
                ConversionWorker::class.java.name,
                JobTags.displayName("queued.mp4"),
                JobTags.sizeBytes(2_048L),
            ),
        )

        val converting = awaitConversion<ConversionState.Converting>()

        assertEquals("queued.mp4", converting.input.displayName)
        assertEquals(2_048L, converting.input.sizeBytes)
    }

    /**
     * The pair with the test above: same job, same tags, and the only difference is that the
     * user cancelled it. Reattaching to it would undo their decision.
     */
    @Test
    fun doesNotResurrectAConversionTheUserCancelled() {
        val id = queuedJob(
            tags = listOf(ConversionWorker::class.java.name, JobTags.displayName("queued.mp4")),
        )
        workManager.cancelWorkById(id).result.get()
        assertEquals(WorkInfo.State.CANCELLED, awaitFinished(id).state)

        assertStaysIdle(conversionViewModel())
    }

    /** A pick the user has already made owns the screen; a job found afterwards must not take it. */
    @Test
    fun doesNotOverwriteAPickTheUserHasAlreadyMade() {
        val staged = stage("holiday_converted.mp4")
        finishedJob(
            tags = listOf(ConversionWorker::class.java.name, JobTags.displayName("holiday.mp4")),
            output = workDataOf(ConversionWorker.KEY_OUTPUT_PATH to staged.absolutePath),
        )

        val picked = Uri.fromFile(stage("picked.mp4"))
        val viewModel = conversionViewModel()
        onMainThread { viewModel.onInputPicked(picked) }

        runBlocking {
            val ready = withTimeout(TIMEOUT_MS) {
                viewModel.state.first { it is ConversionState.Ready }
            } as ConversionState.Ready
            assertEquals(picked, ready.input.uri)
            // And it stays the user's pick rather than being replaced a moment later.
            val stolen = withTimeoutOrNull(SETTLE_MS) {
                viewModel.state.first { it !is ConversionState.Ready }
            }
            assertNull("reattachment took the screen from the user: $stolen", stolen)
        }
    }

    @Test
    fun reattachesToAJoinThatFinishedWhileTheViewModelWasGone() {
        val staged = stage("joined.mp4")
        finishedJob(
            tags = listOf(ConcatWorker::class.java.name, JobTags.inputCount(3)),
            output = workDataOf(
                ConcatWorker.KEY_OUTPUT_PATH to staged.absolutePath,
                ConcatWorker.KEY_STRATEGY to ConcatStrategy.STREAM_COPY.name,
            ),
        )

        val viewModel = joinViewModel()
        val joined = runBlocking {
            withTimeout(TIMEOUT_MS) { viewModel.state.first { it is JoinState.Joined } }
        } as JoinState.Joined

        assertEquals(staged.absolutePath, joined.staged.absolutePath)
        assertEquals(ConcatStrategy.STREAM_COPY, joined.strategy)
    }

    // --- staging the situation --------------------------------------------------------------

    /** Enqueues a job that runs immediately and finishes with [output] as its result. */
    private fun finishedJob(tags: List<String>, output: Data): UUID {
        val builder = OneTimeWorkRequestBuilder<EchoWorker>().setInputData(output)
        tags.forEach(builder::addTag)
        val request = builder.build()
        workManager.enqueue(request).result.get()
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(request.id).state)
        return request.id
    }

    /**
     * Enqueues a job that stays [WorkInfo.State.ENQUEUED]. The delay is what holds it there: it
     * is long enough that nothing can run it during a test, and it is cancelled either way.
     */
    private fun queuedJob(tags: List<String>): UUID {
        val builder = OneTimeWorkRequestBuilder<EchoWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
        tags.forEach(builder::addTag)
        val request = builder.build()
        workManager.enqueue(request).result.get()
        return request.id
    }

    private fun stage(name: String): File {
        val dir = File(app.cacheDir, "conversions").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(ByteArray(1_024)) }
    }

    private fun emptyQueueAndStaging() {
        workManager.cancelAllWorkByTag(ConversionWorker::class.java.name).result.get()
        workManager.cancelAllWorkByTag(ConcatWorker::class.java.name).result.get()
        workManager.cancelAllWorkByTag(EchoWorker::class.java.name).result.get()
        workManager.pruneWork().result.get()
        File(app.cacheDir, "conversions").listFiles()?.forEach { it.delete() }
    }

    // --- reading the result -----------------------------------------------------------------

    /** A ViewModel built now is the next launch: no memory of the work, only what it can query. */
    private fun conversionViewModel(): ConversionViewModel = onMainThread { ConversionViewModel(app) }

    private fun joinViewModel(): JoinViewModel = onMainThread { JoinViewModel(app) }

    private inline fun <reified T : ConversionState> awaitConversion(): T = runBlocking {
        val viewModel = conversionViewModel()
        withTimeout(TIMEOUT_MS) { viewModel.state.first { it is T } } as T
    }

    private fun assertStaysIdle(viewModel: ConversionViewModel) = runBlocking {
        val moved = withTimeoutOrNull(SETTLE_MS) {
            viewModel.state.first { it !is ConversionState.Idle }
        }
        assertNull("reattached to work it should have left alone: $moved", moved)
    }

    private fun awaitFinished(id: UUID): WorkInfo = runBlocking {
        withTimeout(TIMEOUT_MS) {
            workManager.getWorkInfoByIdFlow(id).first { it != null && it.state.isFinished }
        }!!
    }

    private fun <T : Any> onMainThread(block: () -> T): T {
        lateinit var result: T
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        return result
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L

        /**
         * How long "nothing happened" is given to happen. Reattachment is one indexed query
         * against WorkManager's database, so this is generous rather than tuned.
         */
        const val SETTLE_MS = 5_000L
    }
}
