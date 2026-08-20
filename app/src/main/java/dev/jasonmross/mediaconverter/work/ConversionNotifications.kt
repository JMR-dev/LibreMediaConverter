package dev.jasonmross.mediaconverter.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import dev.jasonmross.mediaconverter.R
import java.util.UUID

/** Progress notification for a running conversion. */
class ConversionNotifications(private val context: Context) {

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_conversions),
            // Low importance: a long-running progress bar should not make noise or
            // push a heads-up card on every update.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_conversions_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun build(id: UUID, title: String, percent: Int, indeterminate: Boolean = false) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(
                if (indeterminate) {
                    context.getString(R.string.notification_preparing)
                } else {
                    context.getString(R.string.notification_progress, percent)
                }
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            // Progress updates far outpace what the UI can use; alerting once keeps
            // the system UI from being hammered.
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()

    /**
     * True when notifications can actually be shown.
     *
     * A foreground service still starts without POST_NOTIFICATIONS, but its
     * notification appears only in the Task Manager rather than the shade — so
     * progress silently vanishes from the user's point of view.
     */
    fun areEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java)
            .areNotificationsEnabled()
            .also { if (!it) Log.i(TAG, "Notifications disabled; progress will not be visible.") }

    companion object {
        const val CHANNEL_ID = "conversions"
        private const val TAG = "ConversionNotifications"
    }
}
