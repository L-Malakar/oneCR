package com.lmalakar.onecr.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * The update notification (v0.3.3):
 *
 *  - collapsed: "oneCR 0.3.4 available" — tap opens the app on the update page;
 *  - expanded: the release notes (BigTextStyle) plus two actions, exactly as
 *    the user asked — **Cancel** (dismiss for this session) and **Update**
 *    (straight to the update page, which then walks the install grant).
 */
object UpdateNotifier {

    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 40_01

    fun notify(context: Context, info: UpdateInfo) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "New oneCR versions on GitHub"
            },
        )

        val openPage = UpdateCoordinator.showUpdatePageIntent(context)
        val cancel = PendingIntent.getBroadcast(
            context,
            REQUEST_CANCEL,
            Intent(context, UpdateActionReceiver::class.java).setAction(UpdateActionReceiver.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.lmalakar.onecr.R.drawable.ic_update)
            .setContentTitle("oneCR ${info.versionName} is available")
            .setContentText("Tap to see what's new")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notesForNotification(info)),
            )
            .setContentIntent(openPage)
            .addAction(0, "Cancel", cancel)
            .addAction(0, "Update", openPage)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

        // POST_NOTIFICATIONS (Android 13+) may not be granted yet — the
        // in-app page still covers the announcement in that case.
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(NOTIFICATION_ID)
    }

    /** Notification bodies stay short: version line + a trimmed notes preview. */
    private fun notesForNotification(info: UpdateInfo): String {
        val header = if (info.publishedAt.isNotEmpty()) {
            "Released ${info.publishedAt.take(10)}"
        } else {
            "New release"
        }
        val notes = info.releaseNotes
        return if (notes.isEmpty()) {
            header
        } else {
            "$header\n\n${notes.take(600)}"
        }
    }

    private const val REQUEST_CANCEL = 40_02
}
