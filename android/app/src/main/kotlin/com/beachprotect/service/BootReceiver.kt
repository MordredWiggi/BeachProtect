package com.beachprotect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.beachprotect.store.GuardStore

/**
 * Brings the guard back after a reboot or an app update.
 *
 * Only if it was actually armed when the phone went down - restarting a guard
 * nobody asked for would be both surprising and a waste of battery. `armed` is
 * persisted precisely so this decision can be made without guessing.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val store = GuardStore(context)
        if (!store.hasGroup || !store.armed) return

        Log.i(TAG, "restoring guard after $action")
        runCatching {
            // BOOT_COMPLETED is one of the few exemptions that still allows a
            // foreground service to be started from the background.
            GuardService.send(context, GuardIntents.ACTION_ARM)
        }.onFailure { Log.w(TAG, "could not restore guard", it) }
    }

    companion object {
        private const val TAG = "BpBootReceiver"
    }
}
