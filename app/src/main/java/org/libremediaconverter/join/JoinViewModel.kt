package org.libremediaconverter.join

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libremediaconverter.convert.InputFile
import org.libremediaconverter.convert.OutputPublisher
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.work.ConcatWorker
import java.io.File
import java.util.UUID

sealed interface JoinState {
    data object Idle : JoinState
    data class Ready(val inputs: List<InputFile>) : JoinState
    data class Joining(val inputs: List<InputFile>) : JoinState
    data class Waiting(val inputs: List<InputFile>) : JoinState
    data class Joined(val staged: File, val strategy: ConcatStrategy) : JoinState
    data class Saved(val displayName: String) : JoinState
    data class Failed(val message: String) : JoinState
}

@UnstableApi
class JoinViewModel(app: Application) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)
    private val publisher = OutputPublisher(app)

    private val _state = MutableStateFlow<JoinState>(JoinState.Idle)
    val state: StateFlow<JoinState> = _state.asStateFlow()

    private var observer: Job? = null
    private var activeWorkId: UUID? = null

    fun onInputsPicked(uris: List<Uri>) {
        if (uris.size < 2) {
            _state.value = JoinState.Failed("Pick at least two files to join.")
            return
        }
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { uris.map(::queryFile) }
            _state.value = JoinState.Ready(files)
        }
    }

    fun join() {
        val inputs = (_state.value as? JoinState.Ready)?.inputs ?: return
        val request = ConcatWorker.request(
            inputs = inputs.map { it.uri },
            totalBytes = inputs.sumOf { it.sizeBytes },
        )
        activeWorkId = request.id
        workManager.enqueue(request)
        _state.value = JoinState.Joining(inputs)

        observer?.cancel()
        observer = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info == null) return@collect
                _state.value = when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> JoinState.Joining(inputs)
                    WorkInfo.State.ENQUEUED ->
                        if (info.runAttemptCount > 0) {
                            JoinState.Waiting(inputs)
                        } else {
                            JoinState.Joining(inputs)
                        }

                    WorkInfo.State.SUCCEEDED -> {
                        val path = info.outputData.getString(ConcatWorker.KEY_OUTPUT_PATH)
                        val strategy = info.outputData.getString(ConcatWorker.KEY_STRATEGY)
                            ?.let(ConcatStrategy::valueOf) ?: ConcatStrategy.REENCODE
                        if (path == null) {
                            JoinState.Failed("Joining reported success but produced no file.")
                        } else {
                            JoinState.Joined(File(path), strategy)
                        }
                    }

                    WorkInfo.State.FAILED -> JoinState.Failed(
                        info.outputData.getString(ConcatWorker.KEY_ERROR) ?: "Joining failed.",
                    )

                    WorkInfo.State.CANCELLED -> JoinState.Ready(inputs)
                }
            }
        }
    }

    fun cancel() {
        activeWorkId?.let(workManager::cancelWorkById)
    }

    fun save(destination: Uri) {
        val joined = _state.value as? JoinState.Joined ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    publisher.publish(joined.staged, destination)
                    joined.staged.delete()
                }
            }.onSuccess {
                _state.value = JoinState.Saved("joined.mp4")
            }.onFailure { e ->
                _state.value = JoinState.Failed(e.message ?: "Could not save the file.")
            }
        }
    }

    fun reset() {
        observer?.cancel()
        observer = null
        activeWorkId = null
        _state.value = JoinState.Idle
    }

    private fun queryFile(uri: Uri): InputFile {
        var name = "input"
        var size = 0L
        getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it) ?: name }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.let { size = cursor.getLong(it) }
                }
            }
        return InputFile(uri, name, size)
    }
}
