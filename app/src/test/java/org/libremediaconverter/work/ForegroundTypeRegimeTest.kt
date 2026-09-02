package org.libremediaconverter.work

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ConversionForegroundType.current] answers differently on each of the three API regimes, and
 * until this file only one of them was ever executed.
 *
 * `app/src/test/resources/robolectric.properties` pins the whole JVM suite to `sdk=36`, so every
 * Robolectric test that reaches a `ForegroundInfo` takes the `mediaProcessing` arm and no other.
 * The 33 and 34 arms were cold: 3 lines and 3 of 4 branches, measured on `main` at `d354f64`.
 *
 * **The instrumented test is not a substitute, and the reason is specific.**
 * `ConversionWorkerTest.foregroundTypeMatchesTheRunningApiLevel` asserts against whichever API the
 * leg happens to be — one arm per leg, never the other two — and the legs that would cover 33 and
 * 34 are the ones issue #122 wedges. `docs/coverage-read-findings.md` records an API 33 run that
 * reported `received: 60` and `failed: unknown`: the regime *was* exercised, and that leg could
 * not have said so if it had broken. Four `@Config` classes here pin all three arms
 * deterministically, in the same `./gradlew` invocation as everything else.
 *
 * `minSdk` is 33, so none of these is dead code — each is a device someone is running the app on.
 *
 * **SDK 35 is in the list for the boundary, not for the answer.** It shares its answer with 36,
 * which would make it look redundant. It is not: relaxing `>= VANILLA_ICE_CREAM` to `>` is invisible
 * at every level except exactly 35, so without this class that mutation survives the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ForegroundTypeApi33Test {

    /**
     * Zero rather than a named constant because there is no constant to name: API 33 does not
     * require a type, and `mediaProcessing` does not exist here to pass. `ForegroundInfo` reads 0
     * as "no type at all", which is what this regime wants.
     */
    @Test
    fun `api 33 asks for no foreground service type`() {
        assertEquals(0, ConversionForegroundType.current())
    }
}

/**
 * API 34 makes a type mandatory and still has no `mediaProcessing`, so `dataSync` is the only
 * sensible fit. See [ForegroundTypeApi33Test] for why this file exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundTypeApi34Test {

    @Test
    fun `api 34 falls back to dataSync, the only type that fits`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, ConversionForegroundType.current())
    }
}

/**
 * The first level with `mediaProcessing`, and therefore the one that tells `>=` from `>`.
 * See [ForegroundTypeApi33Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundTypeApi35Test {

    @Test
    fun `api 35 is the first level that takes mediaProcessing`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, ConversionForegroundType.current())
    }
}

/**
 * The level the rest of the suite runs at, asserted here rather than assumed — it is the one arm
 * that was already covered, and leaving it out would make this file look like it is about the old
 * levels rather than about all three regimes. See [ForegroundTypeApi33Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ForegroundTypeApi36Test {

    @Test
    fun `api 36 keeps mediaProcessing`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, ConversionForegroundType.current())
    }
}
