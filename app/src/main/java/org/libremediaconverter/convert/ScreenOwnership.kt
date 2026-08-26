package org.libremediaconverter.convert

/**
 * Which of the things writing to a screen is still allowed to.
 *
 * Both ViewModels are a state machine written to from several coroutines that each suspend before
 * they write: a pick hops to a dispatcher for the metadata query, a reattachment hops for the tag
 * query, and an observation of a WorkManager job cannot write at all until its `collect` has
 * resumed with a `WorkInfo`. Whoever resumes last wins, which is how issue #49 let a finished job
 * from an earlier session take a screen the user had already picked a file on.
 *
 * The rule this makes enforceable is one line: **every write that lands after a suspension point
 * checks the claim it was made under, and drops itself if that claim has been superseded.** The
 * claim is taken synchronously, when the user acts; the check happens immediately before the
 * write. Superseded work is *dropped*, not reordered — a dropped write cannot come back later.
 *
 * Cancelling the superseded coroutine is not a substitute and was never going to be. `Job.cancel`
 * is a request, honoured at the next suspension point; a collector that has already resumed and is
 * on its way to `_state.value = …` has no suspension point left to honour it at, so the write
 * lands anyway. Cancellation also cannot help at all in the case #49 actually reported, where
 * nothing supersedes the observation until after it has been launched. Both ViewModels still
 * cancel their old observer, because leaving a collector running is a leak — but the guarantee
 * does not rest on it.
 *
 * **Confined to the main dispatcher, and that confinement is the atomicity argument.** Every
 * claim and every check runs there, with no suspension point between a check and the write it
 * guards, so a claim can never land between the two. Nothing here is synchronized and nothing is
 * `@Volatile`: making the field visible across threads would invite exactly the off-main use this
 * cannot support, and would replace an argument that holds with one that only looks like it does.
 */
internal class ScreenOwnership {

    private var claims = 0L

    /**
     * The claim in force now.
     *
     * Read by work that is about to suspend and will want to know, when it comes back, whether
     * the screen it was reading is still the screen it is writing to. Read it *before* the
     * suspension, not after — reading it afterwards would return whatever claim superseded it,
     * which is the bug rather than the check for it.
     */
    val current: Long get() = claims

    /**
     * Takes the screen, invalidating every write still in flight under an older claim.
     *
     * Called synchronously from the user's action rather than from inside the coroutine it
     * starts. A claim made inside a `launch` is only immediate while the dispatcher happens to
     * run it inline, and a deferred claim is no claim at all: it would leave the same gap this
     * exists to close.
     */
    fun claim(): Long = ++claims

    /** Whether [token] is still the claim in force, and may therefore write. */
    fun stillHeldBy(token: Long): Boolean = token == claims
}
