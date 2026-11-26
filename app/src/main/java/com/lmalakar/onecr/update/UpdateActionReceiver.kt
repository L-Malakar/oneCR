package com.lmalakar.onecr.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lmalakar.onecr.OneCRApplication

/**
 * Handles the update notification's **Cancel** action (v0.3.3): dismiss
 * the notification and the in-app update page for this session — both come
 * back on the next app open, by design.
 *
 * The **Update** action is a plain activity PendingIntent (see
 * [UpdateCoordinator.showUpdatePageIntent]) — no receiver round-trip, so the
 * tap keeps its notification activity-start exemption and opens the app
 * straight on the update page even from the background.
 */
class UpdateActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val container = (context.applicationContext as OneCRApplication).container
        container.updateCoordinator.dismissForSession()
    }

    companion object {
        const val ACTION_CANCEL = "com.lmalakar.onecr.action.UPDATE_CANCEL"
    }
}
