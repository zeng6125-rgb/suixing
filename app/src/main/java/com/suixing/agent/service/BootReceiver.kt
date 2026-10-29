package com.suixing.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.suixing.agent.core.Core
import kotlinx.coroutines.launch

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
        // Reading the board is file IO and aiming the alarm is AlarmManager work: neither belongs
        // on the receiver's main thread. `goAsync` keeps the receiver alive while the same work
        // runs on the app's own scope, so the alarm is still aimed before the process may go.
        Core.startup.backgroundStarting()
        val pending = goAsync()
        Core.appScope.launch {
            // `refresh` is the documented entry for this (see its KDoc): it asks the service to
            // re-read the board, which is the same call the app makes on launch. A background
            // receiver is not always allowed to start a service — Android 12+ refuses
            // `startService` unless the broadcast is one of the exempted ones — so a refused
            // start falls back to aiming the alarm here, which is the work the service would
            // have done anyway.
            runCatching { SchedulerService.refresh(context) }
                .onFailure { runCatching { SchedulerService.arm(context) } }
            pending.finish()
        }
    }
}
