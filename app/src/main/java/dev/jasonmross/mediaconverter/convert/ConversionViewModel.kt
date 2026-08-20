package dev.jasonmross.mediaconverter.convert

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.jasonmross.mediaconverter.model.EnginePreference
import dev.jasonmross.mediaconverter.model.OutputFormat
import dev.jasonmross.mediaconverter.model.QualityTier
import dev.jasonmross.mediaconverter.work.ConversionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** User-chosen conversion settings. */
data class ConversionSettings(
    val format: OutputFormat = OutputFormat.MP4_H265,
    val quality: QualityTier = QualityTier.FAST,
    val enginePreference: EnginePreference = EnginePreference.AUTO,
)

data class InputFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
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

    fun setFormat(format: OutputFormat) = _settings.update { it.copy(format = format) }
    fun setQuality(quality: QualityTier) = _settings.update { it.copy(quality = quality) }
    fun setEnginePreference(preference: EnginePreference) =
        _settings.update { it.copy(enginePreference = preference) }

    fun onInputPicked(uri: Uri) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { queryFile(uri) }
            _state.value = ConversionState.Ready(info)
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
            format = settings.format,
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

                    WorkInfo.State.FAILED -> ConversionState.Failed(
                        info.outputData.getString(ConversionWorker.KEY_ERROR)
                            ?: "Conversion failed."
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
                _state.value = ConversionState.Saved(
                    ConversionWorker.outputNameFor(
                        converted.input.displayName,
                        _settings.value.format,
                    )
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

    fun suggestedOutputName(): String =
        ConversionWorker.outputNameFor(
            currentInput()?.displayName ?: "output",
            _settings.value.format,
        )

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
