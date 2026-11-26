package com.lmalakar.onecr.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lmalakar.onecr.OneCRApplication

/**
 * Handles the "new update" notification's action buttons (v0.3.3).
 *
 * - **Update** → dismiss the notification, open the app on the update page
 *   and immediately begin the permission → download → install flow.
 * - **Cancel** → dismiss the notification and snooze reminders for this
 *   version for 24 hours (a *newer* version always overrides the snooze).
 *
 * Declared `exported="false"`: only oneCR's own PendingIntents land here.
 */
class UpdateActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val manager = (appContext as? OneCRApplication)?.container?.updateManager ?: return

        when (intent.action) {
            UpdateIntents.ACTION_UPDATE_NOW -> {
                UpdateNotifier.cancel(appContext)
                manager.snoozeNotification()
                val page = UpdateIntents.showUpdatePage(appContext, startDownload = true)
                runCatching { appContext.startActivity(page) }
            }

            UpdateIntents.ACTION_CANCEL -> {
                UpdateNotifier.cancel(appContext)
                manager.snoozeNotification()
                // The page auto-open policy shares the same snooze window.
                manager.closePage()
            }
        }
    }
}
