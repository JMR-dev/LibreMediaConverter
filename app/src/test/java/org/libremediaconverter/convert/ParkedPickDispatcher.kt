package org.libremediaconverter.convert

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * A dispatcher that holds a pick in flight until the test lets it finish.
 *
 * Issue #49 is about what a ViewModel does *while* a pick is between the tap and the write it
 * eventually makes. Both ViewModels put a blocking hop there — the metadata query, and on the
 * convert side the probe as well — and both hops go through an injectable dispatcher. Handing
 * them this one turns "the pick has been made but has not landed yet" from a window a test has
 * to race into a state it can simply sit in.
 *
 * Nothing here is a fake pick. The real `InputQuery.describe` still runs, on this thread,
 * whenever [runAll] is called; the only thing under the test's control is *when*.
 *
 * Confined to the thread that drives the test. Both ViewModels reach `withContext(pickDispatcher)`
 * from a coroutine on the main dispatcher, so [dispatch] is only ever called from there — the
 * queue is concurrent anyway, because a dispatcher that quietly dropped a block from another
 * thread would fail as a hang rather than as an assertion.
 */
class ParkedPickDispatcher : CoroutineDispatcher() {

    private val parked = ConcurrentLinkedQueue<Runnable>()

    /**
     * How many blocks are waiting.
     *
     * Asserted on before the interesting part of a test, because "the pick was in flight" is a
     * premise rather than a detail: a zero here means the pick had already landed and whatever
     * the test went on to prove was proved about a different situation.
     */
    val parkedCount: Int get() = parked.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        parked += block
    }

    /**
     * Removes everything parked, oldest first, and hands it to the caller to run.
     *
     * What [runAll] cannot express: two picks are two hops through this dispatcher, and the defect
     * they can produce is the *first* one finishing last. Running them in the order they arrived
     * is the one order in which nothing goes wrong, so a test has to be able to choose.
     */
    fun takeParked(): List<Runnable> = generateSequence { parked.poll() }.toList()

    /**
     * Runs everything parked, and everything that parks as a result.
     *
     * The loop is not defensive: `ConversionViewModel.onInputPicked` makes two hops through this
     * dispatcher — the metadata query, then the probe — and the second is only enqueued once the
     * first has run. Draining once would leave the probe parked for the rest of the process.
     */
    fun runAll() {
        while (true) {
            val next = parked.poll() ?: return
            next.run()
        }
    }
}
