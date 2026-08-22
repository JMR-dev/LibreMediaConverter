package org.libremediaconverter.convert

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
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
     * The staged output this ViewModel is responsible for deleting.
     *
     * A field rather than something read back out of [_state], because the state machine
     * cannot answer the question on the path that needs it most: a failed [save] lands on
     * [ConversionState.Failed], which carries a message and no file at all. By then the
     * only remaining reference would have been lost.
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

            val probe = withContext(Dispatchers.IO) { ConversionDependencies.probe(getApplication(), uri) }
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

    private fun observe(id: UUID, input: InputFile) {
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
                            )
                        }
                    }

                    WorkInfo.State.FAILED -> ConversionState.Failed(
                        info.outputData.getString(ConversionWorker.KEY_ERROR)
                            ?: "Conversion failed.",
                    )

                    WorkInfo.State.CANCELLED -> ConversionState.Ready(input)
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
                // publish() already deleted it; nothing left to clean up.
                pendingStaged = null
                _state.value = ConversionState.Saved(
                    ConversionWorker.outputNameFor(
                        converted.input.displayName,
                        _settings.value.spec,
                    ),
                )
            }.onFailure { e ->
                // Deliberately NOT cleared. A failed save may mean the staged file is the
                // only copy of an hour of transcoding, and the user's destination did not
                // receive it -- deleting here would destroy the work to tidy up a cache
                // directory. It stays collectable: by a later reset(), or by the sweep once
                // it is old enough to be certain nobody is coming back for it.
                _state.value = ConversionState.Failed(e.message ?: "Could not save the file.")
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
     */
    fun reset() {
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
}
