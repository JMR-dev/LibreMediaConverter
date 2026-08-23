package org.libremediaconverter.work

import android.content.Context
import org.libremediaconverter.convert.OutputPublisher

/**
 * A real [OutputPublisher] that never refuses on space. Shared by the worker unit tests.
 *
 * The space check reads the host's free disk, which has nothing to do with what any of those tests
 * are about and would make them pass or fail on how full the machine is. Where staging lives, and
 * the delete, stay the production implementation — the assertions are about the real filesystem.
 *
 * Only what more than one test needs lives here. The stubs each test uses to force *its own*
 * failure stay in that test, next to the assertion they serve.
 */
open class AlwaysRoomPublisher(context: Context) : OutputPublisher(context) {
    override fun hasSpaceFor(bytes: Long): Boolean = true
}
