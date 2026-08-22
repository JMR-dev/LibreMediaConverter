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
import org.libremediaconverter.work.JobTags
import org.libremediaconverter.work.Reattachment
import org.libremediaconverter.work.jobSnapshots
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

    init {
        reattach()
    }

    /**
     * Picks up a join this ViewModel did not start.
     *
     * The same defect as on the convert side, and the same shape of fix: a join outlives the
     * process on purpose, so a process reclaimed after one finished came back to an empty screen
     * with the joined file sitting unreachable in `cacheDir`. Found by querying for the worker's
     * own class name, which WorkManager tags every request with, so nothing has to be persisted
     * and work from an earlier version of the app is found too. [Reattachment.choose] carries
     * the rules about which job and why.
     */
    private fun reattach() {
        viewModelScope.launch {
            val reattachment = Reattachment.choose(
                workManager.jobSnapshots(
                    tag = ConcatWorker::class.java.name,
                    outputPathKey = ConcatWorker.KEY_OUTPUT_PATH,
                ),
            ) ?: return@launch

            // The query suspends, so the user may have picked files or started a join in the
            // meantime. Theirs wins. No suspension point between this check and the assignment
            // below, and both run on the main dispatcher, so nothing can interleave.
            if (_state.value !is JoinState.Idle || activeWorkId != null) return@launch

            // Every join stages under the same constant name, so two finished joins always
            // report the same file and no tag of either can be trusted to describe it. That is
            // what Ambiguous means here, and the count falls back rather than being borrowed —
            // which costs nothing in practice, since the count is only rendered while a job is
            // live and a live job names no file to be aliased on. What it does not cover is the
            // stream-copy-or-re-encode line on Joined, which comes from the picked job's output
            // and can therefore belong to the other one. Same cause, same fix: one staging name
            // per job.
            val tags = (reattachment as? Reattachment.Certain)?.job?.tags.orEmpty()
            // Placeholders, and safe only because of where they can go. Joining reads nothing
            // but the size of this list, Waiting and Joined read none of it, and a reattached
            // job that is cancelled lands on Idle rather than Ready — the one state that would
            // render these individually and offer to join them. Anything that starts drawing
            // this list has to carry the names in the tags first.
            val inputs = List(JobTags.inputCountOf(tags) ?: MIN_JOIN_INPUTS) {
                InputFile(Uri.EMPTY, "", 0L)
            }
            activeWorkId = reattachment.job.id
            observe(reattachment.job.id, inputs, cancelled = JoinState.Idle)
        }
    }

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
        observe(request.id, inputs)
    }

    /**
     * @param cancelled where a cancellation lands. For a join started here that is the picked
     *   files, ready to join again. For one picked up by [reattach] there are no picked files —
     *   what that job holds are URIs granted to a process that no longer exists — so it lands on
     *   Idle rather than offering to re-join files nothing can open.
     */
    private fun observe(id: UUID, inputs: List<InputFile>, cancelled: JoinState = JoinState.Ready(inputs)) {
        observer?.cancel()
        observer = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
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

                    // A worker that dies before it can report anything leaves no output data at
                    // all, and an exception's message can be an empty string. Both would read as
                    // a failure with nothing said, so blank falls back like missing does.
                    WorkInfo.State.FAILED -> JoinState.Failed(
                        info.outputData.getString(ConcatWorker.KEY_ERROR)
                            ?.takeIf { it.isNotBlank() }
                            ?: "Joining failed.",
                    )

                    WorkInfo.State.CANCELLED -> cancelled
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

    private companion object {
        /**
         * Used when a reattached job carries no count tag — work enqueued by an earlier version
         * of the app. Both the picker and the worker refuse fewer than two inputs, so this is a
         * floor rather than a guess, and it keeps the screen from claiming a join of no files.
         */
        const val MIN_JOIN_INPUTS = 2
    }
}
