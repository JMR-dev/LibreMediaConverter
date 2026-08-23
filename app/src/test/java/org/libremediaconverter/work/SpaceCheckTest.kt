package org.libremediaconverter.work

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.convert.installTestWorkManager
import org.libremediaconverter.model.DeviceCodecs
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.UUID

/**
 * What the space check is actually asked, which is where D5 lived.
 *
 * The worker read its input size out of `Data` with `getLong(KEY_SIZE_BYTES, 0L)`, so a job whose
 * size nobody could report asked "is there room for 0 bytes?" — which the headroom answers yes to
 * on any device with 128 MB free, whatever the file turns out to weigh.
 *
 * These assert on the *question*, not on the verdict. A test that only checked whether the job ran
 * would pass against the defect: the defect is that the guard is vacuous, not that it refuses.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
class SpaceCheckTest {

    private lateinit var app: Application
    private lateinit var publisher: RecordingSpacePublisher

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        publisher = RecordingSpacePublisher(app)
        ConversionDependencies.publisher = { publisher }
        ConversionDependencies.probe = { _, _ -> InputProbe() }
        ConversionDependencies.deviceCodecs = { DeviceCodecs.PERMISSIVE }
        // The progress notification builds its cancel action from WorkManager.getInstance().
        installTestWorkManager(app, Data.EMPTY)
    }

    @After
    fun tearDown() {
        ConversionDependencies.reset()
    }

    @Test
    fun `a job with no declared size measures its input rather than asking for room for nothing`() {
        val input = fileOfSize(INPUT_BYTES, "holiday.mp4")

        runBlocking { conversionWorker(Uri.fromFile(input), declaredSize = null).doWork() }

        assertEquals(listOf(INPUT_BYTES.toLong()), publisher.requested)
    }

    @Test
    fun `a declared size is trusted rather than re-measured`() {
        // The ordinary path, pinned so the measurement stays a fallback. Opening a descriptor per
        // job is cheap, but doing it when the picker already answered would be work for nothing --
        // and the declared number is the one the user was shown.
        val input = fileOfSize(INPUT_BYTES, "holiday.mp4")

        runBlocking { conversionWorker(Uri.fromFile(input), declaredSize = DECLARED_BYTES).doWork() }

        assertEquals(listOf(DECLARED_BYTES), publisher.requested)
    }

    @Test
    fun `a join with no declared total measures its inputs rather than asking for room for nothing`() {
        val first = fileOfSize(FIRST_JOIN_BYTES, "one.mp4")
        val second = fileOfSize(SECOND_JOIN_BYTES, "two.mp4")

        runBlocking { concatWorker(listOf(Uri.fromFile(first), Uri.fromFile(second))).doWork() }

        assertEquals(listOf(FIRST_JOIN_BYTES.toLong() + SECOND_JOIN_BYTES), publisher.requested)
    }

    @Test
    fun `a job whose input nothing can size asks the unknown-size question instead of claiming zero`() {
        // A file:// URI at a path that does not exist: the resolver answers no metadata, and
        // opening a descriptor throws. Nothing left can say how big it is.
        runBlocking { conversionWorker(MISSING_INPUT, declaredSize = null).doWork() }

        // The point of the whole change, in one line. `[0L]` -- what this recorded before -- is a
        // claim that the file is empty; the unknown question is the absence of a claim.
        assertEquals(emptyList<Long>(), publisher.requested)
        assertEquals(1, publisher.unknownQuestions)
    }

    @Test
    fun `a join whose inputs are not all measurable has no total, rather than the ones that answered`() {
        val known = fileOfSize(FIRST_JOIN_BYTES, "one.mp4")

        runBlocking { concatWorker(listOf(Uri.fromFile(known), MISSING_INPUT)).doWork() }

        // Emphatically not `[1111]`. A lower bound is indistinguishable from a total once it
        // reaches the space check, and the check would then be reserving for half the job.
        assertEquals(emptyList<Long>(), publisher.requested)
        assertEquals(1, publisher.unknownQuestions)
    }

    @Test
    fun `a size nothing can determine is not by itself a reason to refuse the conversion`() {
        // The decision this defect had to make, pinned so it cannot be quietly reversed. Refusing
        // an unmeasurable input would turn "no provider answered the SIZE column" into "this file
        // cannot be converted", which is a worse defect than the vacuous guard it replaces -- and
        // one the user could do nothing at all about.
        ConversionDependencies.publisher = { AlwaysRoomPublisher(app) }
        ConversionDependencies.software = { WritingTranscoder }

        val result = runBlocking {
            conversionWorker(MISSING_INPUT, declaredSize = null, engine = EnginePreference.FORCE_SOFTWARE).doWork()
        }

        assertTrue("an unknown size must not end the job; got $result", result is ListenableWorker.Result.Success)
    }

    @Test
    fun `a full disk still refuses a job whose size is unknown`() {
        // The other half of that decision, and what keeps `FakeFailures.FullDisk` -- which
        // overrides `hasSpaceFor` and nothing else -- still meaning what it says. An independent
        // implementation of the unknown-size question could stop honouring a full disk without a
        // single caller changing.
        ConversionDependencies.publisher = { NoRoomPublisher(app) }

        val result = runBlocking { conversionWorker(MISSING_INPUT, declaredSize = null).doWork() }

        assertEquals(
            ListenableWorker.Result.failure(
                workDataOf(ConversionWorker.KEY_ERROR to "Not enough free space to convert."),
            ),
            result,
        )
    }

    /**
     * A worker whose input `Data` carries a size only when [declaredSize] is given.
     *
     * Built entry by entry rather than through `ConversionWorker.request`, because "the key is
     * simply not there" is the shape being tested and `request` is one of the two things that
     * produces it.
     */
    private fun conversionWorker(
        input: Uri,
        declaredSize: Long?,
        engine: EnginePreference = EnginePreference.AUTO,
    ): ConversionWorker {
        val data = mutableMapOf<String, Any>(
            ConversionWorker.KEY_INPUT_URI to input.toString(),
            ConversionWorker.KEY_DISPLAY_NAME to "holiday.mp4",
            ConversionWorker.KEY_CONTAINER to SPEC.container.name,
            ConversionWorker.KEY_VIDEO_CODEC to SPEC.videoCodec.name,
            ConversionWorker.KEY_AUDIO_CODEC to SPEC.audioCodec.name,
            ConversionWorker.KEY_ENGINE_PREFERENCE to engine.name,
        )
        declaredSize?.let { data[ConversionWorker.KEY_SIZE_BYTES] = it }
        return TestListenableWorkerBuilder<ConversionWorker>(
            context = app,
            inputData = workDataOf(*data.map { it.key to it.value }.toTypedArray()),
            runAttemptCount = 0,
        ).setId(CONVERSION_ID).build()
    }

    private fun concatWorker(inputs: List<Uri>): ConcatWorker = TestListenableWorkerBuilder<ConcatWorker>(
        context = app,
        inputData = workDataOf(
            ConcatWorker.KEY_INPUT_URIS to inputs.map(Uri::toString).toTypedArray(),
            ConcatWorker.KEY_FORMAT to OutputFormat.MP4_H264.name,
        ),
        runAttemptCount = 0,
    ).setId(CONCAT_ID).build()

    private fun fileOfSize(bytes: Int, name: String): File =
        File(app.cacheDir, name).apply { writeBytes(ByteArray(bytes)) }

    private companion object {
        const val INPUT_BYTES = 4_321
        const val DECLARED_BYTES = 9_999L
        const val FIRST_JOIN_BYTES = 1_111
        const val SECOND_JOIN_BYTES = 2_222

        /**
         * An input nothing can size.
         *
         * A `file://` path that does not exist, which under Robolectric behaves exactly as the
         * device pass recorded for a real one: `contentResolver.query` returns null, so no SIZE
         * column is ever reached, and `openFileDescriptor` throws `FileNotFoundException`. It is
         * also a scheme the worker handles without the FFmpegKit SAF bridge, which is native and
         * therefore unavailable here.
         */
        val MISSING_INPUT: Uri = Uri.parse("file:///nonexistent/holiday.mp4")
        val SPEC = OutputFormat.MP4_H265.spec
        val CONVERSION_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000011")
        val CONCAT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000012")
    }
}

/**
 * Records *which* space question was asked, and refuses either way.
 *
 * Both overrides, and neither calls `super`. That is what makes the two questions tell apart at
 * all: the production default answers `hasSpaceForUnknownSize()` by delegating to
 * `hasSpaceFor(0L)`, so a recorder that delegated would log an unknown size as a request for zero
 * bytes — the exact conflation being tested. The delegation itself is pinned separately, by
 * [NoRoomPublisher] and the full-disk test.
 *
 * Refusing keeps the worker to the one line under test: the check runs before anything is staged
 * or any engine is reached, so `false` ends `doWork` immediately and no native library is asked to
 * load.
 */
private class RecordingSpacePublisher(context: Context) : OutputPublisher(context) {
    val requested = mutableListOf<Long>()
    var unknownQuestions = 0
        private set

    override fun hasSpaceFor(bytes: Long): Boolean {
        requested += bytes
        return false
    }

    override fun hasSpaceForUnknownSize(): Boolean {
        unknownQuestions++
        return false
    }
}

/**
 * A full disk expressed the only way `FakeFailures.FullDisk` expresses it.
 *
 * `hasSpaceFor` and nothing else, so a job refused here is a job refused *through* the
 * delegation rather than by an override of its own.
 */
private class NoRoomPublisher(context: Context) : OutputPublisher(context) {
    override fun hasSpaceFor(bytes: Long): Boolean = false
}
