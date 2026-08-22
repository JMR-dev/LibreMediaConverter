package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
    val sizeBytes: Long,
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

    /** Budget for foreground work ran out; WorkManager will retry when it can. */
    data class Waiting(val input: InputFile) : ConversionState
    data class Converted(
        val input: InputFile,
        val staged: File,
        val engineUsed: String = "",
        val routeReason: String = "",
    ) : ConversionState
    data class Saved(val displayName: String) : ConversionState
    data class Failed(val message: String) : ConversionState
}

@UnstableApi
class ConversionViewModel(app: Application) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)
    private val publisher = OutputPublisher(app)

    private val _state = MutableStateFlow<ConversionState>(ConversionState.Idle)
    val state: StateFlow<ConversionState> = _state.asStateFlow()

    private var observer: Job? = null
    private var activeWorkId: UUID? = null

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
     * One known wart, not fixed here because it is a different defect: the save dialog's
     * suggested name and MIME type come from the current picker rather than from the job that
     * ran, so a reattached job converting to something other than the default format is offered
     * the default extension. That derivation is wrong on its own terms and is left to the change
     * that fixes it properly.
     */
    private fun reattach() {
        viewModelScope.launch {
            val reattachment = Reattachment.choose(
                workManager.jobSnapshots(
                    tag = ConversionWorker::class.java.name,
                    outputPathKey = ConversionWorker.KEY_OUTPUT_PATH,
                ),
            ) ?: return@launch

            // The query suspends, so by now the user may have picked a file or started a
            // conversion of their own. Either owns the screen; reattaching over it would throw
            // away what they just did. Both this check and the assignment below run on the main
            // dispatcher with no suspension point between them, so nothing can interleave.
            if (_state.value !is ConversionState.Idle || activeWorkId != null) return@launch

            // Only a job that is the sole explanation for its staged file gets to name the input.
            // When several jobs report the same file — which nothing prevents while the staging
            // name is derived from the input's display name — the file is still the user's, but
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
                sizeBytes = JobTags.sizeBytesOf(tags) ?: 0L,
            )
            activeWorkId = reattachment.job.id
            // No initial state of our own: the flow's first emission carries the job's real
            // state, so observe() maps it exactly as it would for a conversion started here.
            observe(reattachment.job.id, input, cancelled = ConversionState.Idle)
        }
    }

    fun setPreset(format: OutputFormat) = _settings.update { it.copy(spec = format.spec) }
    fun setContainer(container: Container) = _settings.update { it.copy(spec = it.spec.copy(container = container)) }

    fun setVideoCodec(codec: VideoCodec) = _settings.update { it.copy(spec = it.spec.copy(videoCodec = codec)) }

    fun setAudioCodec(codec: AudioCodec) = _settings.update { it.copy(spec = it.spec.copy(audioCodec = codec)) }

    fun applySuggestion(spec: OutputSpec) = _settings.update { it.copy(spec = spec) }

    fun setQuality(quality: QualityTier) = _settings.update { it.copy(quality = quality) }
    fun setEnginePreference(preference: EnginePreference) = _settings.update { it.copy(enginePreference = preference) }

    fun onInputPicked(uri: Uri) {
        viewModelScope.launch {
            // Both the metadata query and the probe touch disk, and the probe spawns FFprobe.
            // Neither belongs on the main thread.
            val file = withContext(Dispatchers.IO) { queryFile(uri) }
            // Show the file as soon as its name and size are known. Probing now runs FFprobe on
            // every pick, which is a native process spawn, and making the whole screen wait on it
            // would read as the app having ignored the tap.
            _state.value = ConversionState.Ready(file)

            val probe = withContext(Dispatchers.IO) { MediaProbe.probe(getApplication(), uri) }
            // Only fill in the probe if the user has not moved on in the meantime.
            _state.update { current ->
                if (current is ConversionState.Ready && current.input.uri == uri) {
                    ConversionState.Ready(file.copy(probe = probe))
                } else {
                    current
                }
            }
        }
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
        activeWorkId = request.id
        workManager.enqueue(request)
        _state.value = ConversionState.Converting(input, 0)
        observe(request.id, input)
    }

    /**
     * @param cancelled where a cancellation lands. For a conversion started here that is the
     *   picked file, ready to convert again. For one picked up by [reattach] there is no picked
     *   file — the URI that job holds belongs to a process that no longer exists — so it lands
     *   on Idle instead, rather than offering a Convert button over a file nothing can open.
     */
    private fun observe(id: UUID, input: InputFile, cancelled: ConversionState = ConversionState.Ready(input)) {
        observer?.cancel()
        observer = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                _state.value = when (info.state) {
                    WorkInfo.State.RUNNING -> ConversionState.Converting(
                        input,
                        info.progress.getInt(ConversionWorker.KEY_PROGRESS, 0),
                    )

                    // ENQUEUED after a run means a retry is pending — most likely the
                    // six-hour foreground budget was exhausted mid-job.
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
                            ConversionState.Converted(
                                input = input,
                                staged = File(path),
                                engineUsed = info.outputData
                                    .getString(ConversionWorker.KEY_ENGINE_USED).orEmpty(),
                                routeReason = info.outputData
                                    .getString(ConversionWorker.KEY_ROUTE_REASON).orEmpty(),
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

    fun save(destination: Uri) {
        val converted = _state.value as? ConversionState.Converted ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    publisher.publish(converted.staged, destination)
                    converted.staged.delete()
                }
            }.onSuccess {
                _state.value = ConversionState.Saved(
                    ConversionWorker.outputNameFor(
                        converted.input.displayName,
                        _settings.value.spec,
                    ),
                )
            }.onFailure { e ->
                _state.value = ConversionState.Failed(e.message ?: "Could not save the file.")
            }
        }
    }

    fun reset() {
        observer?.cancel()
        observer = null
        activeWorkId = null
        _state.value = ConversionState.Idle
    }

    fun suggestedOutputName(): String = ConversionWorker.outputNameFor(
        currentInput()?.displayName ?: "output",
        _settings.value.spec,
    )

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

    private fun queryFile(uri: Uri): InputFile {
        var name = "input"
        var size = 0L
        getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it) ?: name }
                    cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 }
                        ?.let { size = cursor.getLong(it) }
                }
            }
        return InputFile(uri, name, size)
    }

    private companion object {
        /**
         * Shown for a reattached job whose tags predate them — work enqueued by an earlier
         * version of the app. Neutral on purpose: it is a real file of the user's, and calling
         * it "unknown" would read as an error rather than as a gap in what survived.
         */
        const val UNKNOWN_INPUT_NAME = "Media file"
    }
}
