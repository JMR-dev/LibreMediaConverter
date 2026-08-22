package org.libremediaconverter.join

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libremediaconverter.convert.ConversionDependencies
import org.libremediaconverter.convert.InputFile
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
class JoinViewModel @JvmOverloads constructor(
    app: Application,
    /** Where [reset] runs its delete. See the same parameter on `ConversionViewModel`. */
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)

    // Through ConversionDependencies, like the workers, rather than `OutputPublisher(app)`
    // direct -- see the same line in ConversionViewModel.
    private val publisher = ConversionDependencies.publisher(app)

    private val _state = MutableStateFlow<JoinState>(JoinState.Idle)
    val state: StateFlow<JoinState> = _state.asStateFlow()

    private var observer: Job? = null
    private var activeWorkId: UUID? = null

    /**
     * The staged output this ViewModel is responsible for deleting.
     *
     * Held here rather than read back out of [_state] for the same reason as in
     * `ConversionViewModel`: a failed [save] lands on [JoinState.Failed], which carries a
     * message and no file, so the state machine cannot answer this on the one path that
     * most needs it.
     */
    private var pendingStaged: File? = null

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
                            val staged = File(path)
                            // Take responsibility for the file at the same moment the state
                            // starts referring to it, so the two cannot disagree.
                            pendingStaged = staged
                            JoinState.Joined(staged, strategy)
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
                // publish() already deleted it; nothing left to clean up.
                pendingStaged = null
                _state.value = JoinState.Saved("joined.mp4")
            }.onFailure { e ->
                // Deliberately NOT cleared -- see the same branch in ConversionViewModel.
                // A failed save can leave the staged file as the only copy of the work, so
                // it is left for a later reset() or for the sweep to collect once its age
                // makes it certain nobody is coming back for it.
                _state.value = JoinState.Failed(e.message ?: "Could not save the file.")
            }
        }
    }

    /**
     * Returns to [JoinState.Idle], deleting anything staged on the way out.
     *
     * Best effort, not a guarantee: the delete is cancelled with [viewModelScope] if the
     * Activity finishes first. `OutputPublisher.sweepStaging` is the backstop.
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
