package com.suixing.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarms do not survive a reboot, so without this a reminder set weeks ago would go quiet
 * until the user happened to open the app again.
 *
 * `Core` is already built by the time a manifest receiver runs — the Application is created
 * first — so the board is readable here, and re-aiming is the same call the app makes on
 * launch: the file is the record, and the alarm is derived from it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SchedulerService.arm(context)
    }
}
