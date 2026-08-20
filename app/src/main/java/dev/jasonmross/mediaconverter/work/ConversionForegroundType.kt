package dev.jasonmross.mediaconverter.work

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Picks the foreground service type for a conversion job.
 *
 * There are three regimes across the supported range, which is why this is not a
 * single constant:
 *
 * | API        | Regime                                                          |
 * |------------|-----------------------------------------------------------------|
 * | 33         | Foreground service types are not required at all.               |
 * | 34         | A type is mandatory, but `mediaProcessing` does not exist yet,   |
 * |            | so `dataSync` is the only sensible fit.                          |
 * | 35+        | `mediaProcessing` exists and is the correct type. Its own docs   |
 * |            | describe it as "converting media to different formats".          |
 *
 * Both types carry the same budget: **six hours out of every twenty-four**, shared
 * across all of the app's foreground services. On expiry the system calls
 * `Service.onTimeout` and the app has seconds to stop before taking an ANR.
 */
object ConversionForegroundType {

    /**
     * The `foregroundServiceType` to pass to `ForegroundInfo`.
     *
     * Returns 0 on API 33, where passing a type is unnecessary — and where the
     * `mediaProcessing` constant does not exist to pass in the first place.
     */
    fun current(): Int = when {
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }
}
