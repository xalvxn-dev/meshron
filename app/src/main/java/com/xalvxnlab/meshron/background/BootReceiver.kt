package com.xalvxnlab.meshron.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xalvxnlab.meshron.MeshronApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * After the phone restarts (or Meshron is updated), start "Stay connected" again if it's on,
 * so messages and notifications keep arriving without opening the app first.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as MeshronApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Only once an ID exists; a fresh install starts from the app.
                if (app.settings.stayConnected.first() && app.identityRepository.identity.first() != null) {
                    runCatching { ConnectionService.start(app) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
