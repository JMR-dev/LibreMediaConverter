package dev.jasonmross.mediaconverter.convert

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class InputFile(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

sealed interface ConversionState {
    data object Idle : ConversionState
    data class Ready(val input: InputFile) : ConversionState
    data class Converting(val input: InputFile, val percent: Int) : ConversionState
    data class Converted(
        val input: InputFile,
        val staged: File,
        val elapsedMs: Long,
    ) : ConversionState
    data class Saved(val displayName: String) : ConversionState
    data class Failed(val message: String) : ConversionState
}

@UnstableApi
class ConversionViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = Media3Engine(app)
    private val publisher = OutputPublisher(app)

    private val _state = MutableStateFlow<ConversionState>(ConversionState.Idle)
    val state: StateFlow<ConversionState> = _state.asStateFlow()

    fun onInputPicked(uri: Uri) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { queryFile(uri) }
            _state.value = ConversionState.Ready(info)
        }
    }

    fun convert() {
        val input = when (val s = _state.value) {
            is ConversionState.Ready -> s.input
            is ConversionState.Converted -> s.input
            else -> return
        }

        viewModelScope.launch {
            // Staging plus the source means peak usage is roughly both at once.
            if (!publisher.hasSpaceFor(input.sizeBytes)) {
                _state.value = ConversionState.Failed(
                    "Not enough free space to convert this file."
                )
                return@launch
            }

            _state.value = ConversionState.Converting(input, 0)
            val staged = publisher.createStagingFile(outputNameFor(input.displayName))
            val startedAt = System.currentTimeMillis()

            runCatching {
                engine.transcode(input.uri, staged) { percent ->
                    _state.update { current ->
                        if (current is ConversionState.Converting) {
                            current.copy(percent = percent)
                        } else {
                            current
                        }
                    }
                }
            }.onSuccess {
                _state.value = ConversionState.Converted(
                    input = input,
                    staged = staged,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            }.onFailure { e ->
                staged.delete()
                _state.value = ConversionState.Failed(e.message ?: "Conversion failed.")
            }
        }
    }

    /** Copies the staged result out to the destination the user chose. */
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
                    outputNameFor(converted.input.displayName)
                )
            }.onFailure { e ->
                _state.value = ConversionState.Failed(e.message ?: "Could not save the file.")
            }
        }
    }

    fun reset() {
        _state.value = ConversionState.Idle
    }

    fun suggestedOutputName(): String {
        val s = _state.value
        val base = when (s) {
            is ConversionState.Converted -> s.input.displayName
            is ConversionState.Ready -> s.input.displayName
            else -> "output"
        }
        return outputNameFor(base)
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

    private fun outputNameFor(inputName: String): String =
        inputName.substringBeforeLast('.', inputName) + "_converted.mp4"

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }
}
