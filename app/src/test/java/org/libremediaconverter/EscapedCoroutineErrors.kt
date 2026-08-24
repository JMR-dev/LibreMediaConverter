package org.libremediaconverter

import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.test.runTest

/**
 * Clears coroutine errors this module's tests deliberately let escape, so they land on the test
 * that caused them instead of on the next one to start.
 *
 * **Every Compose test class in `src/test` has to start here.** `createComposeRule` runs the
 * composition inside `runTest`, and `runTest` opens by throwing `UncaughtExceptionsBeforeTest` for
 * anything already sitting in kotlinx-coroutines-test's collector -- a process-wide
 * `CoroutineExceptionHandler` it installs once and never removes.
 *
 * There is one deposit into that collector here, and it is not a mistake:
 * `ConversionViewModelProbeFailureTest.an OutOfMemoryError is not swallowed` proves an OOM raised
 * inside the probe is rethrown rather than reported as an unreadable file. `onInputPicked` runs it
 * in `viewModelScope.launch`, which has no exception handler by design -- the ViewModel's own KDoc
 * says a real OOM should reach the thread's handler and take the process down. On the JVM the
 * collector takes it instead, holds it, and hands it to whichever `runTest` starts next.
 *
 * It surfaced as two *different* Compose test classes failing on two consecutive runs of the same,
 * green, code, with a message naming neither the test nor the error's origin. Which class catches
 * it moves because the throw happens on a real `Dispatchers.IO` thread, after the state assertion
 * that ends the test that caused it -- so it can be delivered long after that class is done.
 *
 * A `@Before` method cannot do this: the compose rule's `runTest` wraps the statement that calls
 * `@Before`, so it has already thrown. `@BeforeClass` cannot either -- Robolectric runs it outside
 * the sandbox classloader, where the collector is a different object. Draining while the rule is
 * being *constructed* is early enough, because JUnit builds a fresh test-class instance, and with
 * it every `@get:Rule` field, before evaluating any rule.
 *
 * The real fix is a seam: give the probe hop an injectable dispatcher the way
 * `ConversionViewModel`'s constructor already does for `cleanupDispatcher`, and the error would
 * have somewhere to land. That is a production change, so it belongs in its own commit.
 */
fun drainEscapedCoroutineErrors() {
    // Entering a test scope is what flushes the collector; the flush is reported as this
    // throwing, and there is nothing to assert about an error another test already asserted on.
    runCatching { runTest {} }
}

/**
 * [createComposeRule], with [drainEscapedCoroutineErrors] run first. Use this rather than
 * `createComposeRule` directly in `src/test`.
 *
 * It also keeps the one mixed import in one place: the rule comes from the **v2** package
 * (`androidx.compose.ui.test.junit4.v2`) while `StateRestorationTester`, which takes it, does not.
 */
fun createDrainedComposeRule() = run {
    drainEscapedCoroutineErrors()
    createComposeRule()
}
