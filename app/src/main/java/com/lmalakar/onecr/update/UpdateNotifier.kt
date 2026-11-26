package com.lmalakar.onecr.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.lmalakar.onecr.MainActivity
import com.lmalakar.onecr.R

/**
 * The "new version available" notification (v0.3.3).
 *
 * - Tap on the body → opens the app and shows the full update page
 *   ([MainActivity] with [UpdateIntents.EXTRA_SHOW_UPDATE_PAGE]).
 * - Expanded (BigTextStyle) → shows the release notes; the **Update** and
 *   **Cancel** action buttons appear under the expanded text (and on most
 *   launchers also collapse into the compact row).
 * - Update → opens the app and immediately starts the permission/download flow.
 * - Cancel → dismisses and snoozes update reminders for this version (24 h).
 */
object UpdateNotifier {

    const val CHANNEL_UPDATES = "app_updates"
    const val NOTIFICATION_ID_UPDATE = 60

    fun post(context: Context, info: UpdateInfo) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a newer oneCR release is published on GitHub"
                setShowBadge(false)
            },
        )

        val openPage = PendingIntent.getActivity(
            context,
            10,
            UpdateIntents.showUpdatePage(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val updateNow = PendingIntent.getBroadcast(
            context,
            11,
            UpdateIntents.notificationAction(context, UpdateIntents.ACTION_UPDATE_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getBroadcast(
            context,
            12,
            UpdateIntents.notificationAction(context, UpdateIntents.ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notes = UpdateNotes.forNotification(info.releaseNotes)

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("oneCR v${info.versionName} available")
            .setContentText("You are running an older version — tap to see what's new.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes).setBigContentTitle("oneCR v${info.versionName}"))
            .setContentIntent(openPage)
            .addAction(0, "Update", updateNow)
            .addAction(0, "Cancel", cancel)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        manager.notify(NOTIFICATION_ID_UPDATE, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID_UPDATE)
    }
}

/** String contracts shared by the notifier, the receiver and the activity. */
object UpdateIntents {

    const val ACTION_UPDATE_NOW = "com.lmalakar.onecr.action.UPDATE_NOW"
    const val ACTION_CANCEL = "com.lmalakar.onecr.action.UPDATE_CANCEL"

    const val EXTRA_SHOW_UPDATE_PAGE = "com.lmalakar.onecr.extra.SHOW_UPDATE_PAGE"
    const val EXTRA_START_UPDATE_NOW = "com.lmalakar.onecr.extra.START_UPDATE_NOW"

    /** Opens the app with the update page on top (notification tap / Update action). */
    fun showUpdatePage(context: Context, startDownload: Boolean = false): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_SHOW_UPDATE_PAGE, true)
            putExtra(EXTRA_START_UPDATE_NOW, startDownload)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    /** Broadcast for the notification's Update / Cancel buttons. */
    fun notificationAction(context: Context, action: String): Intent =
        Intent(context, UpdateActionReceiver::class.java).setAction(action)
}

/**
 * Release-notes rendering shared by the notification and the update page:
 * strips markdown noise (images, links, headings, emphasis) so the raw body
 * reads as plain text.
 */
object UpdateNotes {

    /** Compact version for the notification BigText. */
    fun forNotification(body: String, maxChars: Int = 480): String =
        clean(body).take(maxChars).let { if (clean(body).length > maxChars) "$it…" else it }

    /** Full version for the update page scroll. */
    fun forPage(body: String, maxChars: Int = 2_400): String {
        val cleaned = clean(body)
        return if (cleaned.length > maxChars) cleaned.take(maxChars) + "…" else cleaned
    }

    /** Markdown → readable plain text (images/links/headings/bold removed). */
    fun clean(body: String): String = body
        .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "") // images
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1") // links → text
        .replace(Regex("(?m)^#{1,6}\\s*"), "") // headings
        .replace(Regex("(?m)^\\s*[-*]{3,}\\s*$"), "") // horizontal rules
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
