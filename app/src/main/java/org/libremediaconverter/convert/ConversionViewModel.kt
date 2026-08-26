package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libremediaconverter.ffmpeg.isNativeLoadFailure
import org.libremediaconverter.model.AudioCodec
import org.libremediaconverter.model.Container
import org.libremediaconverter.model.ContainerCapabilities
import org.libremediaconverter.model.EnginePreference
import org.libremediaconverter.model.InputProbe
import org.libremediaconverter.model.OutputFormat
import org.libremediaconverter.model.OutputSpec
import org.libremediaconverter.model.QualityTier
import org.libremediaconverter.model.Validation
import org.libremediaconverter.model.VideoCodec
import org.libremediaconverter.work.ConversionWorker
import org.libremediaconverter.work.JobTags
import org.libremediaconverter.work.Reattachment
import org.libremediaconverter.work.jobSnapshots
import java.io.File
import java.util.UUID

/** User-chosen conversion settings. */
data class ConversionSettings(
    val spec: OutputSpec = OutputFormat.MP4_H265.spec,
    val quality: QualityTier = QualityTier.FAST,
    val enginePreference: EnginePreference = EnginePreference.AUTO,
) {
    /** The preset this spec corresponds to, or null once it has been edited past all of them. */
    val matchingPreset: OutputFormat?
        get() = OutputFormat.entries.firstOrNull { it.spec == spec }
}

data class InputFile(
    val uri: Uri,
    val displayName: String,
    /**
     * How big the file is, or null when nothing could say.
     *
     * Nullable rather than `0L`, and that is the point of it. The two were the same value before,
     * so an unmeasurable file arrived at the space check claiming to be empty. [InputQuery] owns
     * how the answer is found and what it means; every reader of this has to decide what an
     * unknown size does, which is exactly the decision the old default made silently.
     */
    val sizeBytes: Long?,
    /**
     * What probing found. Null only while the probe is still running.
     *
     * Held here rather than recomputed because three things need it: the source-info card, the
     * validity check for the chosen output, and the copy planner's decision about whether a track
     * can be stream-copied.
     */
    val probe: InputProbe? = null,
)

sealed interface ConversionState {
    data object Idle : ConversionState
    data class Ready(val input: InputFile) : ConversionState
    data class Converting(val input: InputFile, val percent: Int) : ConversionState

    /**
     * Something stopped the job from running for now, and WorkManager will try again.
     *
     * Two causes reach here and the state cannot tell them apart, because `ENQUEUED` with an
     * attempt behind it is all `WorkInfo` says: the six-hour-a-day foreground-service budget
     * running out mid-job, and the system refusing to let a job restart while the app is in the
     * background. See [org.libremediaconverter.work.FailureOutcome].
     */
    data class Waiting(val input: InputFile) : ConversionState
    data class Converted(
        val input: InputFile,
        val staged: File,
        val engineUsed: String = "",
        val routeReason: String = "",
        /**
         * What to call the file, and what type to open the save dialog with.
         *
         * Carried on the state rather than derived when the Save button is tapped, because the
         * only thing that knows them is the job — see `ConversionWorker.KEY_SUGGESTED_NAME`. The
         * staged file's own name says nothing: it is the job's id.
         */
        val suggestedName: String = "",
        val mimeType: String = "",
    ) : ConversionState
    data class Saved(val displayName: String) : ConversionState

    /**
     * The job, or the save that followed it, could not be finished.
     *
     * [retry] is non-null for exactly one cause: a [ConversionViewModel.save] whose copy to the
     * user's destination threw. That save deliberately keeps the staged file -- it can be the only
     * copy of an hour of transcoding -- and this is what lets the screen offer it again. Every
     * other failure leaves it null, because there is nothing staged to offer: a transcode that
     * died produced no output, and a save that found the file gone has nothing left to save.
     *
     * Nullable rather than a `SaveFailed` state of its own. What the screen does with the message
     * is identical either way, so a second variant would make every exhaustive `when` grow an arm
     * that duplicates this one.
     *
     * A view of the file, not a second owner of it -- see [PendingSave].
     */
    data class Failed(val message: String, val retry: PendingSave? = null) : ConversionState
}

/**
 * The staged output a save would target from this state, or null when there is nothing to save.
 *
 * One function for two callers that have to agree. [ConversionViewModel.save] picks the file to
 * copy with it, and `ConverterScreen` registers its `CreateDocument` contract with the MIME type
 * it returns; when those two read the state separately, a retry offered after a failed save opened
 * the dialog with the *picker's* current type instead of the finished job's -- wrong for any job
 * whose spec has been edited since, and for every reattached job, whose spec was never in these
 * settings at all.
 *
 * Top-level and `internal` rather than a member of the ViewModel, so the screen can call it
 * without one -- which is also what makes the derivation testable on the JVM.
 */
internal fun ConversionState.pendingSave(): PendingSave? = when (this) {
    is ConversionState.Converted -> PendingSave(staged, suggestedName, mimeType)
    is ConversionState.Failed -> retry
    else -> null
}

@UnstableApi
class ConversionViewModel @JvmOverloads constructor(
    app: Application,
    /**
     * Where [reset] runs its delete.
     *
     * A parameter so a test can make the cleanup run inline and assert on the result. It
     * also makes the ordering an explicit choice rather than an accident: the state flips
     * to `Idle` synchronously while the delete is dispatched, and naming the dispatcher is
     * what says that was decided rather than inherited.
     *
     * `@JvmOverloads` keeps the single-argument constructor that `viewModel()`'s default
     * `AndroidViewModelFactory` looks up reflectively; without it the app would crash on
     * the first screen.
     */
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where the two blocking hops behind a pick run — the metadata query and the probe.
     *
     * A seam for the probe above all, because that is the one call in this class that throws
     * on purpose. [probeOrUnreadable] rethrows anything that is not a native load failure, and
     * the `launch` it runs in has no exception handler by design: on a device the error reaches
     * the thread's default handler and takes the process down, which is what an
     * [OutOfMemoryError] should do.
     *
     * On the JVM there is no such handler. kotlinx-coroutines-test installs a process-wide
     * collector, once and for the life of the classloader, that keeps an escaped error and
     * hands it to whichever `runTest` starts next — so it failed a Compose test class that had
     * nothing to do with it, and *which* class moved between runs of identical code. Naming the
     * dispatcher is what lets a test keep the throw inside its own window, where it fails the
     * test that caused it and is consumed rather than collected.
     *
     * Both hops rather than the probe alone, which is where this differs from the seam issue #66
     * proposed: leaving the metadata query on a real [Dispatchers.IO] makes the coroutine resume
     * on a main looper that Robolectric leaves paused, and that bounce is precisely the
     * asynchrony that made delivery unpredictable. One dispatcher covers a whole pick, and
     * leaves nothing about it to timing.
     */
    private val pickDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)

    // Through ConversionDependencies, like the workers, rather than `OutputPublisher(app)`
    // direct: the ViewModels were the only place bypassing the seam, which left the
    // cleanup wiring impossible to substitute in a test.
    private val publisher = ConversionDependencies.publisher(app)

    private val _state = MutableStateFlow<ConversionState>(ConversionState.Idle)
    val state: StateFlow<ConversionState> = _state.asStateFlow()

    private var observer: Job? = null
    private var activeWorkId: UUID? = null

    /**
     * Who is allowed to write to this screen — see [ScreenOwnership] for the rule and why
     * cancelling the superseded coroutine is not one.
     *
     * Every write below that lands after a suspension point is guarded by it: the two in
     * [onInputPicked] and the one in [observe].
     *
     * [save] is the one left out, deliberately — and not because it is safe in both directions.
     * Nothing can overwrite what it writes: it is reachable only from [ConversionState.Converted]
     * or a [ConversionState.Failed] carrying its file, so the only observation that could belongs
     * to a job already in a terminal state, which will not emit again. What it can still do is
     * land on top of a [reset] taken while its copy was in flight, putting `Saved` on a screen the
     * user has just cleared. Guarding it would drop that write instead, reporting nothing for a
     * file that may genuinely have reached the user's destination. Which of those two is right is
     * a question about what the screen should offer during a save, not about this race, and it is
     * left open rather than answered in passing.
     */
    private val ownership = ScreenOwnership()

    /**
     * The staged output this ViewModel is responsible for deleting.
     *
     * A field rather than something read back out of [_state], and still one now that
     * [ConversionState.Failed] carries a [PendingSave] after a failed [save]. That handle is a
     * view for the screen to offer a retry through; this one is the single reference [reset]
     * deletes through, and keeping the two apart is what stops a second owner appearing. Reading
     * the file back out of the state machine instead would mean trusting every state that has no
     * file -- `Idle`, `Saved`, a transcode failure -- to say so.
     */
    private var pendingStaged: File? = null

    /** Conversion settings, kept separate from the job state machine. */
    private val _settings = MutableStateFlow(ConversionSettings())
    val settings: StateFlow<ConversionSettings> = _settings.asStateFlow()

    /**
     * Whether the chosen output can actually be produced from the chosen input.
     *
     * Derived rather than stored so it cannot go stale: it recomputes when either the settings or
     * the picked file changes. The Advanced picker deliberately allows an invalid combination to be
     * selected, so this is what turns that into an explanation and a disabled Convert button.
     */
    val validation: StateFlow<Validation> = combine(_state, _settings) { state, settings ->
        ContainerCapabilities.validate(settings.spec, state.probe() ?: InputProbe())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Validation.Valid)

    init {
        reattach()
    }

    /**
     * Picks up a conversion this ViewModel did not start.
     *
     * The queue outliving the process is the entire reason [ConversionWorker] exists, but the
     * ViewModel used to be where that stopped: its `activeWorkId` is a plain field, so a process
     * reclaimed after a conversion finished came back to an empty screen while the output sat in
     * `cacheDir` with nothing in the UI able to reach it. The realistic case is not a crash
     * mid-transcode — it is the job finishing, the user not saving yet, and the process being
     * reclaimed hours later as an ordinary background one.
     *
     * Nothing is persisted for this. The query is by worker class name, which WorkManager tags
     * every request with on its own, so it finds work enqueued by an earlier run of the app —
     * and by an earlier *version* of it — which an id saved in a `SavedStateHandle` would not.
     *
     * The save dialog's suggested name and MIME type used to be built from the current picker,
     * which made a reattached job the worst case: its spec was never in these settings at all, so
     * a job that converted to MP3 was offered `.mp4`. Both now travel in the job's own output
     * `Data` — see [ConversionState.Converted].
     */
    private fun reattach() {
        // Read before the launch, and before the query it is about to suspend in. This is the
        // claim the answer will belong to: anything the user does from here on supersedes it, and
        // reading it on the far side of the query would read whatever superseded it instead.
        val token = ownership.current
        viewModelScope.launch {
            val reattachment = Reattachment.choose(
                workManager.jobSnapshots(
                    tag = ConversionWorker::class.java.name,
                    outputPathKey = ConversionWorker.KEY_OUTPUT_PATH,
                ),
            ) ?: return@launch

            // The query suspends, so by now the user may have picked a file or started a
            // conversion of their own. Either owns the screen; reattaching over it would throw
            // away what they just did.
            //
            // This catches a pick that has already *landed*, and only that. It used to claim that
            // "both this check and the assignment below run on the main dispatcher with no
            // suspension point between them, so nothing can interleave" — which was the exact
            // opposite of what happens. There is no assignment below. There is observe(), which
            // launches a *separate* coroutine that must suspend on `collect` before it can write
            // anything, so the check happens at one moment and the write lands at another with a
            // whole pick able to fit in between. That was issue #49, and believing this comment is
            // why it read as flaky CI for two days. What actually holds the line is the token
            // observe() carries: see [ScreenOwnership].
            if (_state.value !is ConversionState.Idle || activeWorkId != null) return@launch

            // Only a job that is the sole explanation for its staged file gets to name the input.
            // When several jobs report the same file — which staging on the job id has stopped for
            // new work, but not for work already in the queue — the file is still the user's, but
            // saying which of them produced it would be a guess, so the card falls back to a
            // neutral label rather than borrowing the other job's.
            val tags = (reattachment as? Reattachment.Certain)?.job?.tags.orEmpty()
            val input = InputFile(
                // The picked URI is not recoverable — WorkManager gives back a job's tags and
                // its output, never the Data it was enqueued with — and nothing in the states
                // reattachment produces reads it. The card shows the name and size, which the
                // tags carry; a reattached job that is cancelled goes to Idle rather than Ready,
                // so this can never reach the Convert button. Leaving the probe unset costs the
                // card its source details, and re-probing is what there is no URI for.
                uri = Uri.EMPTY,
                displayName = JobTags.displayNameOf(tags) ?: UNKNOWN_INPUT_NAME,
                // No `?: 0L`. A job tagged before sizes were tagged at all, or one enqueued
                // for a file nothing could measure, has no size -- and answering that with
                // zero is the same conflation this whole change is about. See [InputQuery].
                sizeBytes = JobTags.sizeBytesOf(tags),
            )
            activeWorkId = reattachment.job.id
            // No initial state of our own: the flow's first emission carries the job's real
            // state, so observe() maps it exactly as it would for a conversion started here.
            observe(reattachment.job.id, input, cancelled = ConversionState.Idle, token = token)
        }
    }

    fun setPreset(format: OutputFormat) = _settings.update { it.copy(spec = format.spec) }
    fun setContainer(container: Container) = _settings.update { it.copy(spec = it.spec.copy(container = container)) }

    fun setVideoCodec(codec: VideoCodec) = _settings.update { it.copy(spec = it.spec.copy(videoCodec = codec)) }

    fun setAudioCodec(codec: AudioCodec) = _settings.update { it.copy(spec = it.spec.copy(audioCodec = codec)) }

    fun applySuggestion(spec: OutputSpec) = _settings.update { it.copy(spec = spec) }

    fun setQuality(quality: QualityTier) = _settings.update { it.copy(quality = quality) }
    fun setEnginePreference(preference: EnginePreference) = _settings.update { it.copy(enginePreference = preference) }

    /**
     * The tap is the claim, which is why [ScreenOwnership.claim] is called here and not inside the
     * `launch`. A claim made in the coroutine would only be immediate for as long as
     * `Dispatchers.Main.immediate` happened to run it inline, and a deferred claim leaves the same
     * gap this closes: it is the difference between the user owning the screen from the moment
     * they tapped and owning it from whenever their coroutine got around to running.
     */
    fun onInputPicked(uri: Uri) {
        val token = ownership.claim()
        viewModelScope.launch {
            // Both the metadata query and the probe touch disk, and the probe spawns FFprobe.
            // Neither belongs on the main thread.
            val file = withContext(pickDispatcher) { InputQuery.describe(getApplication(), uri) }
            // Every write below the hop above is guarded, this one included: two picks in quick
            // succession suspend here together, and without this the slower one would land last
            // and put the file the user did not choose on screen.
            if (!ownership.stillHeldBy(token)) return@launch
            // Show the file as soon as its name and size are known. Probing now runs FFprobe on
            // every pick, which is a native process spawn, and making the whole screen wait on it
            // would read as the app having ignored the tap.
            _state.value = ConversionState.Ready(file)

            val probe = withContext(pickDispatcher) { probeOrUnreadable(uri) }
            // Only fill in the probe if the user has not moved on in the meantime. The claim is
            // what says whether they have -- it covers a second pick of the same URI, which a
            // comparison of URIs cannot, and every state a later claim could have written.
            if (!ownership.stillHeldBy(token)) return@launch
            _state.value = ConversionState.Ready(file.copy(probe = probe))
        }
    }

    /**
     * Probing, with the one failure the pick must survive rather than propagate.
     *
     * This runs inside `viewModelScope.launch`, which has no exception handler, so anything
     * that escapes here abandons the launch — the file card never fills in — and reaches the
     * thread's default handler, which on a device takes the process down. Picking a file is
     * not a place to crash from.
     *
     * The one condition that reaches this is FFmpegKit's native library failing to load,
     * which arrives as an `Error` rather than an `Exception`; [MediaProbe] handles its own
     * FFprobe call now, and this covers the seam and the platform extractor beside it. The
     * answer is [MediaProbe.UNREADABLE] — the same value [MediaProbe.probe] returns when
     * neither of its probes could read the file, because that is what has happened.
     *
     * Anything else is rethrown deliberately. An [OutOfMemoryError] here is about this
     * process, not about this file, and reporting it as an unreadable video would let the app
     * carry on in a state it cannot honour. See
     * [org.libremediaconverter.ffmpeg.isNativeLoadFailure] for which is which and why the
     * distinction is drawn by a predicate rather than by the catch clause.
     */
    private fun probeOrUnreadable(uri: Uri): InputProbe = try {
        ConversionDependencies.probe(getApplication(), uri)
    } catch (e: Error) {
        if (!isNativeLoadFailure(e)) throw e
        Log.w(TAG, "Could not probe $uri; reporting it as unreadable.", e)
        MediaProbe.UNREADABLE
    }

    /**
     * Enqueues the conversion rather than running it inline.
     *
     * Going through WorkManager means the job outlives this ViewModel, survives the
     * process being killed, and keeps running when the user leaves the app — none of
     * which a viewModelScope coroutine would do.
     */
    fun convert() {
        val input = currentInput() ?: return

        val settings = _settings.value
        val request = ConversionWorker.request(
            inputUri = input.uri,
            displayName = input.displayName,
            sizeBytes = input.sizeBytes,
            spec = settings.spec,
            quality = settings.quality,
            enginePreference = settings.enginePreference,
        )
        // Tapping Convert claims the screen for this job, which is what supersedes the pick's
        // still-in-flight probe and any reattachment that has not finished asking.
        val token = ownership.claim()
        activeWorkId = request.id
        workManager.enqueue(request)
        _state.value = ConversionState.Converting(input, 0)
        observe(request.id, input, token = token)
    }

    /**
     * @param cancelled where a cancellation lands. For a conversion started here that is the
     *   picked file, ready to convert again. For one picked up by [reattach] there is no picked
     *   file — the URI that job holds belongs to a process that no longer exists — so it lands
     *   on Idle instead, rather than offering a Convert button over a file nothing can open.
     * @param token the claim this observation belongs to. Nothing here can write until `collect`
     *   has resumed with a `WorkInfo`, which is some time after the caller decided to observe, so
     *   the claim is checked again at the last possible moment rather than trusted from then. This
     *   is issue #49's fix and the only thing standing between a superseded observation and the
     *   user's screen — see [ScreenOwnership].
     */
    private fun observe(
        id: UUID,
        input: InputFile,
        cancelled: ConversionState = ConversionState.Ready(input),
        token: Long,
    ) {
        observer?.cancel()
        observer = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                // Ahead of the `when`, not merely ahead of the assignment: the SUCCEEDED branch
                // takes ownership of the staged file, and a superseded observation must not do
                // that either. The state and `pendingStaged` are meant to refer to the same file
                // or to no file, and this is where that stays true.
                if (!ownership.stillHeldBy(token)) return@collect
                _state.value = when (info.state) {
                    WorkInfo.State.RUNNING -> ConversionState.Converting(
                        input,
                        info.progress.getInt(ConversionWorker.KEY_PROGRESS, 0),
                    )

                    // ENQUEUED after a run means a retry is pending. Either the six-hour
                    // foreground budget ran out mid-job, or the system refused to let the job
                    // start again while the app was in the background — the second being the
                    // likelier of the two, since it needs only a process restart. Nothing here
                    // can tell them apart, and nothing needs to: the answer is the same.
                    WorkInfo.State.ENQUEUED ->
                        if (info.runAttemptCount > 0) {
                            ConversionState.Waiting(input)
                        } else {
                            ConversionState.Converting(input, 0)
                        }

                    WorkInfo.State.SUCCEEDED -> {
                        val path = info.outputData.getString(ConversionWorker.KEY_OUTPUT_PATH)
                        if (path == null) {
                            ConversionState.Failed("Conversion reported success but produced no file.")
                        } else {
                            val staged = File(path)
                            // Take responsibility for the file at the same moment the state
                            // starts referring to it, so the two cannot disagree.
                            pendingStaged = staged
                            ConversionState.Converted(
                                input = input,
                                staged = staged,
                                engineUsed = info.outputData
                                    .getString(ConversionWorker.KEY_ENGINE_USED).orEmpty(),
                                routeReason = info.outputData
                                    .getString(ConversionWorker.KEY_ROUTE_REASON).orEmpty(),
                                suggestedName = info.outputData
                                    .getString(ConversionWorker.KEY_SUGGESTED_NAME)
                                    ?.takeIf { it.isNotBlank() }
                                    // Work enqueued before the worker reported this carries
                                    // nothing, and WorkManager keeps finished work for about a
                                    // week -- so this branch is ordinary for a few days rather
                                    // than a corner. It is the old derivation, kept because it is
                                    // the same guess the app already made and there is genuinely
                                    // nothing better available for such a job. New work never
                                    // reaches it.
                                    ?: ConversionWorker.outputNameFor(
                                        input.displayName,
                                        _settings.value.spec,
                                    ),
                                mimeType = info.outputData
                                    .getString(ConversionWorker.KEY_MIME_TYPE)
                                    ?.takeIf { it.isNotBlank() }
                                    ?: _settings.value.spec.mimeType,
                            )
                        }
                    }

                    // A worker that dies before it can report anything leaves no output data at
                    // all — a foreground-service start refused after a process restart is one
                    // way — and an exception's message can be an empty string. Both would read
                    // as a failure with nothing said, so blank falls back like missing does.
                    WorkInfo.State.FAILED -> ConversionState.Failed(
                        info.outputData.getString(ConversionWorker.KEY_ERROR)
                            ?.takeIf { it.isNotBlank() }
                            ?: "Conversion failed.",
                    )

                    WorkInfo.State.CANCELLED -> cancelled
                    WorkInfo.State.BLOCKED -> ConversionState.Converting(input, 0)
                }
            }
        }
    }

    fun cancel() {
        activeWorkId?.let(workManager::cancelWorkById)
    }

    /**
     * Copies the staged result out to the destination the user picked.
     *
     * Reached from [ConversionState.Converted] and again from a [ConversionState.Failed] that an
     * earlier save left carrying its file. [pendingSave] is what makes those one call rather than
     * two, so a retry cannot drift from the first attempt in what it copies or what it calls it.
     *
     * The existence check is not redundant with the one reattachment already made. That one ran
     * inside a tag query which, for a result offered on launch, can be hours older than the tap —
     * and `cacheDir` is exactly the directory the OS empties when it wants space, which is also
     * what the sweep does to anything a day old. Without it the file's absence arrived as
     * `staged.inputStream()` throwing, and `e.message` put a raw ENOENT path on screen. A retry
     * meets that same check a second time, which is the point of reusing it here.
     */
    fun save(destination: Uri) {
        val pending = _state.value.pendingSave() ?: return
        if (!pending.staged.isFile) {
            // No retry handle: the file such a state would offer again is exactly the one that
            // has gone, so carrying it would put a button on screen that cannot do anything.
            _state.value = ConversionState.Failed(STAGED_FILE_GONE_MESSAGE)
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    publisher.publish(pending.staged, destination)
                    pending.staged.delete()
                }
            }.onSuccess {
                // publish() already deleted it; nothing left to clean up.
                pendingStaged = null
                _state.value = ConversionState.Saved(pending.suggestedName)
            }.onFailure { e ->
                // Deliberately NOT cleared. A failed save may mean the staged file is the
                // only copy of an hour of transcoding, and the user's destination did not
                // receive it -- deleting here would destroy the work to tidy up a cache
                // directory. It stays collectable: by a later reset(), or by the sweep once
                // it is old enough to be certain nobody is coming back for it.
                //
                // `pending` rides on the state so the screen can offer that file again. It used
                // to live only in `pendingStaged`, where nothing on screen could reach it -- so
                // the single button this branch rendered was "Start over", which deletes the very
                // file the paragraph above goes out of its way to keep. It is `pending` rather
                // than a fresh handle for the second failure's sake: a retry that fails again
                // lands back here still carrying the file, not on a bare Failed that would take
                // the offer away.
                _state.value = ConversionState.Failed(e.message ?: "Could not save the file.", pending)
            }
        }
    }

    /**
     * Returns to [ConversionState.Idle], deleting anything staged on the way out.
     *
     * "Start over" on a finished conversion is an ordinary path through the UI, and it used
     * to drop the only reference to a full-size file in cache. The delete runs on
     * [Dispatchers.IO] because it touches the filesystem, and is fire-and-forget: it is
     * cancelled with [viewModelScope] if the Activity finishes first, so it is a best
     * effort rather than a guarantee. `OutputPublisher.sweepStaging` is the backstop for
     * the times it does not run.
     *
     * **It still deletes from a [ConversionState.Failed] carrying a [PendingSave], and that is a
     * decision rather than something inherited.** Deletion is acceptable there only because the
     * alternative was offered first: the screen puts "Try saving again" directly above this
     * button, so reaching it is the user saying the work is not worth keeping. Until that button
     * existed, this delete was the only thing a failed save could lead to — which was the defect.
     */
    fun reset() {
        // Start over is a claim like any other. The cancel below is a request honoured at the next
        // suspension point, so a collector already on its way to a write has nothing left to
        // honour it at; the claim is what actually stops that write landing on top of Idle.
        ownership.claim()
        observer?.cancel()
        observer = null
        activeWorkId = null
        val staged = pendingStaged
        pendingStaged = null
        if (staged != null) {
            viewModelScope.launch(cleanupDispatcher) { publisher.discardStaged(staged) }
        }
        _state.value = ConversionState.Idle
    }

    private fun ConversionState.probe(): InputProbe? = when (this) {
        is ConversionState.Ready -> input.probe
        is ConversionState.Converting -> input.probe
        is ConversionState.Waiting -> input.probe
        is ConversionState.Converted -> input.probe
        else -> null
    }

    private fun currentInput(): InputFile? = when (val s = _state.value) {
        is ConversionState.Ready -> s.input
        is ConversionState.Converting -> s.input
        is ConversionState.Waiting -> s.input
        is ConversionState.Converted -> s.input
        else -> null
    }

    private companion object {
        /**
         * Shown for a reattached job whose tags predate them — work enqueued by an earlier
         * version of the app. Neutral on purpose: it is a real file of the user's, and calling
         * it "unknown" would read as an error rather than as a gap in what survived.
         */
        const val UNKNOWN_INPUT_NAME = "Media file"

        const val TAG = "ConversionViewModel"
    }
}
