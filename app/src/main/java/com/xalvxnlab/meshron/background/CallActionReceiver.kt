package com.xalvxnlab.meshron.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xalvxnlab.meshron.MeshronApp

/** "Decline" / "Hang up" buttons on call notifications. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = (context.applicationContext as MeshronApp).calls
        when (intent.action) {
            ACTION_DECLINE, ACTION_HANG_UP -> calls.hangUp()
        }
    }

    companion object {
        const val ACTION_DECLINE = "com.xalvxnlab.meshron.DECLINE_CALL"
        const val ACTION_HANG_UP = "com.xalvxnlab.meshron.HANG_UP"
    }
}
