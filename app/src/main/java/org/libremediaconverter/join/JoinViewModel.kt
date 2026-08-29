package org.libremediaconverter.join

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.work.Data
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
import org.libremediaconverter.convert.InputQuery
import org.libremediaconverter.convert.PendingSave
import org.libremediaconverter.convert.SAVE_FAILED_MESSAGE
import org.libremediaconverter.convert.STAGED_FILE_GONE_MESSAGE
import org.libremediaconverter.convert.ScreenOwnership
import org.libremediaconverter.model.ConcatStrategy
import org.libremediaconverter.work.ConcatWorker
import org.libremediaconverter.work.JobTags
import org.libremediaconverter.work.Reattachment
import org.libremediaconverter.work.jobSnapshots
import java.io.File
import java.util.UUID

/**
 * One update about a running join, as WorkManager last reported it.
 *
 * The join-side twin of `ConversionUpdate`, and deliberately the same shape: this pair of seams is
 * one refactor done twice, and letting them diverge would make the two flows harder to compare than
 * the duplication costs. There is no `progressPercent` here because `ConcatWorker` publishes none —
 * a join is indeterminate.
 */
internal data class JoinUpdate(val state: WorkInfo.State, val runAttemptCount: Int, val outputData: Data)

/**
 * What the join screen should show, given what WorkManager last said about the job.
 *
 * The join-side twin of `conversionStateFrom`, extracted for the same reason and with the same two
 * exclusions: the ownership check stays at the call site, and this takes no responsibility for the
 * staged file. See that function's KDoc for the argument in full.
 *
 * Five arms had never been chosen by any test before this was cut out, because a real `ConcatWorker`
 * only ever produces a terminal state with well-formed output.
 *
 * @param cancelled where a cancellation lands, which differs for a reattached job — see [observe].
 */
@UnstableApi
internal fun joinStateFrom(update: JoinUpdate, inputs: List<InputFile>, cancelled: JoinState): JoinState =
    when (update.state) {
        // BLOCKED is a job waiting on a prerequisite, which the user has nothing to do about and
        // nothing useful to be told about. It reads as "starting", like a fresh ENQUEUED.
        WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> JoinState.Joining(inputs)

        // ENQUEUED after a run means a retry is pending -- the same rule, and the same reasoning, as
        // the convert side. See `conversionStateFrom`.
        WorkInfo.State.ENQUEUED ->
            if (update.runAttemptCount > 0) {
                JoinState.Waiting(inputs)
            } else {
                JoinState.Joining(inputs)
            }

        WorkInfo.State.SUCCEEDED -> joinedFrom(update.outputData)

        // A worker that dies before it can report anything leaves no output data at all, and an
        // exception's message can be an empty string. Both would read as a failure with nothing said,
        // so blank falls back like missing does.
        WorkInfo.State.FAILED -> JoinState.Failed(
            update.outputData.getString(ConcatWorker.KEY_ERROR)
                ?.takeIf { it.isNotBlank() }
                ?: ConcatWorker.GENERIC_FAILURE_MESSAGE,
        )

        WorkInfo.State.CANCELLED -> cancelled
    }

/**
 * The `SUCCEEDED` arm. A join that reported success and named no file has nothing to offer.
 */
@UnstableApi
private fun joinedFrom(outputData: Data): JoinState {
    val path = outputData.getString(ConcatWorker.KEY_OUTPUT_PATH)
        ?: return JoinState.Failed(JOINED_WITHOUT_A_FILE_MESSAGE)
    return JoinState.Joined(
        staged = File(path),
        strategy = strategyFrom(outputData.getString(ConcatWorker.KEY_STRATEGY)),
        // A join enqueued before the worker reported these carries neither, and the fallback is
        // the format such a job really used -- ConcatWorker.request has always defaulted to it,
        // and the join screen has never offered a choice.
        suggestedName = outputData.getString(ConcatWorker.KEY_SUGGESTED_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: ConcatWorker.outputNameFor(ConcatWorker.DEFAULT_FORMAT),
        mimeType = outputData.getString(ConcatWorker.KEY_MIME_TYPE)
            ?.takeIf { it.isNotBlank() }
            ?: ConcatWorker.DEFAULT_FORMAT.mimeType,
    )
}

/**
 * The strategy a finished join reported, or [ConcatStrategy.REENCODE] when it named none.
 *
 * **Looked up rather than `valueOf`, and that is a fix rather than a style choice.** `valueOf`
 * throws `IllegalArgumentException` on a name this build does not define, and this runs inside a
 * `viewModelScope` collect with no handler -- so the throw does not become a `Failed` state, it
 * takes the process down.
 *
 * Reachable for the reason `WorkerEnumFallbackTest` and `JobTags` are both written on: WorkManager
 * keeps finished work for about a week, so a downgrade or a rollback hands this build a job
 * enqueued by another one. `ConcatWorker` writes `result.strategy.name` into the output `Data`, so
 * a build that added a third strategy would leave this one crashing on its own completed joins.
 *
 * `ConcatWorker.kt` already made exactly this change for `KEY_FORMAT`, and says why in as many
 * words: *"Looked up rather than `valueOf` … a format name this build does not define used to throw
 * past the catch."* The same read on this side had not been changed with it.
 *
 * REENCODE is the safe default rather than an arbitrary one: it is the answer for inputs that do
 * not match, so a job whose strategy cannot be read is described as the more conservative of the
 * two rather than being claimed as a lossless stream copy.
 */
private fun strategyFrom(name: String?): ConcatStrategy =
    ConcatStrategy.entries.firstOrNull { it.name == name } ?: ConcatStrategy.REENCODE

/** A join that reported success and named no file. There is nothing to offer the user to save. */
internal const val JOINED_WITHOUT_A_FILE_MESSAGE: String =
    "Joining reported success but produced no file."

sealed interface JoinState {
    data object Idle : JoinState
    data class Ready(val inputs: List<InputFile>) : JoinState
    data class Joining(val inputs: List<InputFile>) : JoinState
    data class Waiting(val inputs: List<InputFile>) : JoinState
    data class Joined(
        val staged: File,
        val strategy: ConcatStrategy,
        /**
         * What to call the file, and what type to open the save dialog with.
         *
         * From the job, not from a literal. See `ConcatWorker.KEY_SUGGESTED_NAME`.
         */
        val suggestedName: String,
        val mimeType: String,
    ) : JoinState
    data class Saved(val displayName: String) : JoinState

    /**
     * The join, or the save that followed it, could not be finished.
     *
     * [retry] is non-null for exactly one cause, and for the same reason as on
     * `ConversionState.Failed`: a [JoinViewModel.save] whose copy to the user's destination threw
     * keeps the staged file, and this is what lets the screen offer it again. Every other failure
     * leaves it null — a join that died produced no output, and a save that found the file gone
     * has nothing left to save.
     */
    data class Failed(val message: String, val retry: PendingSave? = null) : JoinState
}

/**
 * The staged output a save would target from this state, or null when there is nothing to save.
 *
 * The join tab's half of `ConversionState.pendingSave`, and it exists for the same reason: `save`
 * and `JoinScreen`'s `CreateDocument` registration both have to answer this question, and answering
 * it twice is how a retry ends up opening the dialog with a type the finished job never chose.
 */
internal fun JoinState.pendingSave(): PendingSave? = when (this) {
    is JoinState.Joined -> PendingSave(staged, suggestedName, mimeType)
    is JoinState.Failed -> retry
    else -> null
}

@UnstableApi
class JoinViewModel @JvmOverloads constructor(
    app: Application,
    /** Where [reset] runs its delete. See the same parameter on `ConversionViewModel`. */
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Where the metadata query behind a pick runs. See the same parameter on `ConversionViewModel`.
     *
     * The join side had no such seam, and the gap was not cosmetic: the one write `onInputsPicked`
     * makes lands *after* this hop, so a test that wants to ask what happens while a pick is still
     * in flight had no way to hold one there. Issue #49's race is exactly that question, and it
     * went unasked on this side for as long as the dispatcher was a literal.
     */
    private val pickDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
     * Who is allowed to write to this screen -- see [ScreenOwnership], which carries the rule and
     * the reason cancelling the superseded coroutine is not one.
     *
     * The convert side had issue #49 reported against it four times in two days; this side has the
     * identical shape and was never reported, because nothing was watching. Every write below that
     * lands after a suspension point is guarded: the one in [onInputsPicked] and the one in
     * [observe]. [save] is the one left out, deliberately and with the same limit its counterpart
     * in `ConversionViewModel` spells out: nothing can overwrite what it writes, but it can still
     * land on top of a [reset] taken while its copy was in flight. Which way that should go is a
     * question about the save screen rather than about this race -- issue #123.
     */
    private val ownership = ScreenOwnership()

    /**
     * The staged output this ViewModel is responsible for deleting.
     *
     * Held here rather than read back out of [_state] for the same reason as in
     * `ConversionViewModel`, and still held here now that [JoinState.Failed] carries a
     * [PendingSave] after a failed [save]: that handle is a view for the screen to offer a retry
     * through, this one is the single reference [reset] deletes through, and keeping the two
     * apart is what stops a second owner of the file appearing.
     */
    private var pendingStaged: File? = null

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
        // Read before the launch, and before the query it is about to suspend in: this is the
        // claim the answer belongs to. Reading it on the far side of the query would read whatever
        // superseded it, which is the bug rather than the check for it.
        val token = ownership.current
        viewModelScope.launch {
            val reattachment = Reattachment.choose(
                workManager.jobSnapshots(
                    tag = ConcatWorker::class.java.name,
                    outputPathKey = ConcatWorker.KEY_OUTPUT_PATH,
                ),
            ) ?: return@launch

            // The query suspends, so the user may have picked files or started a join in the
            // meantime. Theirs wins.
            //
            // This catches a pick that has already *landed*, and only that. It used to claim there
            // was "no suspension point between this check and the assignment below", which was the
            // opposite of what happens: there is no assignment below, only observe(), which
            // launches a separate coroutine that cannot write until its `collect` resumes. The
            // check happens at one moment and the write lands at another, with a whole pick able
            // to fit in between -- issue #49. The token observe() carries is what holds that line;
            // see [ScreenOwnership].
            if (_state.value !is JoinState.Idle || activeWorkId != null) return@launch

            // Joins used to stage under one constant name, so two finished joins always reported
            // the same file and no tag of either could be trusted to describe it. That is what
            // Ambiguous means here, and the count falls back rather than being borrowed — which
            // costs nothing in practice, since the count is only rendered while a job is live and
            // a live job names no file to be aliased on. Staging on the job id has closed that for
            // new work, including the stream-copy-or-re-encode line on Joined, which comes from
            // the picked job's output; joins already in the queue keep the old shape.
            val tags = (reattachment as? Reattachment.Certain)?.job?.tags.orEmpty()
            // Placeholders, and safe only because of where they can go. Joining reads nothing
            // but the size of this list, Waiting and Joined read none of it, and a reattached
            // job that is cancelled lands on Idle rather than Ready — the one state that would
            // render these individually and offer to join them. Anything that starts drawing
            // this list has to carry the names in the tags first.
            val inputs = List(JobTags.inputCountOf(tags) ?: MIN_JOIN_INPUTS) {
                InputFile(Uri.EMPTY, "", sizeBytes = null)
            }
            activeWorkId = reattachment.job.id
            observe(reattachment.job.id, inputs, cancelled = JoinState.Idle, token = token)
        }
    }

    /**
     * The tap is the claim, which is why it is taken here rather than inside the `launch` -- and
     * above the early return, so the refusal below is covered by it too. A claim made in the
     * coroutine is only immediate while `Dispatchers.Main.immediate` happens to run it inline, and
     * a deferred claim leaves exactly the gap this closes.
     */
    fun onInputsPicked(uris: List<Uri>) {
        val token = ownership.claim()
        if (uris.size < 2) {
            _state.value = JoinState.Failed(ConcatWorker.TOO_FEW_INPUTS_MESSAGE)
            return
        }
        viewModelScope.launch {
            val files = withContext(pickDispatcher) {
                uris.map { InputQuery.describe(getApplication(), it) }
            }
            // Guarded like every other write that lands after a hop: two picks in quick succession
            // suspend here together, and the slower one would otherwise land last.
            if (!ownership.stillHeldBy(token)) return@launch
            _state.value = JoinState.Ready(files)
        }
    }

    fun join() {
        val inputs = (_state.value as? JoinState.Ready)?.inputs ?: return
        val request = ConcatWorker.request(
            inputs = inputs.map { it.uri },
            // Not `sumOf`, which cannot express what is being summed any more. A join's
            // total is only as good as its least-known part, and adding up the inputs that
            // did answer would hand the space check a lower bound it would read as a total.
            totalBytes = InputQuery.total(inputs.map { it.sizeBytes }),
        )
        // Tapping Join claims the screen for this job, superseding any reattachment that has not
        // finished asking.
        val token = ownership.claim()
        activeWorkId = request.id
        workManager.enqueue(request)
        _state.value = JoinState.Joining(inputs)
        observe(request.id, inputs, token = token)
    }

    /**
     * @param cancelled where a cancellation lands. For a join started here that is the picked
     *   files, ready to join again. For one picked up by [reattach] there are no picked files —
     *   what that job holds are URIs granted to a process that no longer exists — so it lands on
     *   Idle rather than offering to re-join files nothing can open.
     * @param token the claim this observation belongs to. Nothing here can write until `collect`
     *   has resumed with a `WorkInfo`, which is some time after the caller decided to observe, so
     *   the claim is checked again at the last possible moment rather than trusted from then. See
     *   [ScreenOwnership], and issue #49.
     */
    private fun observe(
        id: UUID,
        inputs: List<InputFile>,
        cancelled: JoinState = JoinState.Ready(inputs),
        token: Long,
    ) {
        observer?.cancel()
        observer = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { info ->
                if (info == null) return@collect
                // Ahead of the `when`, not merely ahead of the assignment: the SUCCEEDED branch
                // takes ownership of the staged file, and a superseded observation must not do
                // that either.
                if (!ownership.stillHeldBy(token)) return@collect
                val next = joinStateFrom(
                    JoinUpdate(
                        state = info.state,
                        runAttemptCount = info.runAttemptCount,
                        outputData = info.outputData,
                    ),
                    inputs = inputs,
                    cancelled = cancelled,
                )
                // Read off the result rather than assigned inside the mapping -- see the same
                // three lines in ConversionViewModel for why that is the better half of the swap.
                if (next is JoinState.Joined) pendingStaged = next.staged
                _state.value = next
            }
        }
    }

    fun cancel() {
        activeWorkId?.let(workManager::cancelWorkById)
    }

    /**
     * Copies the staged result out to the destination the user picked.
     *
     * The existence check is the same one `ConversionViewModel.save` makes, for the same reason: a
     * join offered by reattachment was last seen during a tag query that may be hours old, and
     * `cacheDir` is reclaimed by the OS and swept by this app. Without it the file's absence
     * reached the screen as a raw ENOENT path.
     *
     * Reached from [JoinState.Joined] and again from a [JoinState.Failed] an earlier save left
     * carrying its file; [pendingSave] is what makes those the same call.
     */
    fun save(destination: Uri) {
        val pending = _state.value.pendingSave() ?: return
        if (!pending.staged.isFile) {
            // No retry handle -- the file it would offer again is the one that has gone.
            _state.value = JoinState.Failed(STAGED_FILE_GONE_MESSAGE)
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
                _state.value = JoinState.Saved(pending.suggestedName)
            }.onFailure { e ->
                // Deliberately NOT cleared -- see the same branch in ConversionViewModel.
                // A failed save can leave the staged file as the only copy of the work, so
                // it is left for a later reset() or for the sweep to collect once its age
                // makes it certain nobody is coming back for it.
                //
                // `pending` travels on the state so the screen can offer the file again rather
                // than leaving "Start over" -- which deletes it -- as the only thing on offer.
                // Passing `pending` rather than rebuilding it is what keeps a retry that fails
                // again on a carrying Failed instead of a bare one.
                _state.value = JoinState.Failed(e.message ?: SAVE_FAILED_MESSAGE, pending)
            }
        }
    }

    /**
     * Returns to [JoinState.Idle], deleting anything staged on the way out.
     *
     * Best effort, not a guarantee: the delete is cancelled with [viewModelScope] if the
     * Activity finishes first. `OutputPublisher.sweepStaging` is the backstop.
     *
     * It deletes from a [JoinState.Failed] carrying a [PendingSave] too, deliberately and for the
     * reason `ConversionViewModel.reset` writes out: the screen offers "Try saving again" above
     * this button, so deletion is what the user chose rather than all this state could do.
     */
    fun reset() {
        // Start over is a claim like any other. The cancel below is a request honoured at the next
        // suspension point, so a collector already on its way to a write has nothing left to
        // honour it at; the claim is what stops that write landing on top of Idle.
        ownership.claim()
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

    private companion object {
        /**
         * Used when a reattached job carries no count tag — work enqueued by an earlier version
         * of the app. Both the picker and the worker refuse fewer than two inputs, so this is a
         * floor rather than a guess, and it keeps the screen from claiming a join of no files.
         */
        const val MIN_JOIN_INPUTS = 2
    }
}
