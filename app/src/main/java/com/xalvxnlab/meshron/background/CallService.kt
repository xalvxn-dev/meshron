package com.xalvxnlab.meshron.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.MeshronApp

/**
 * Runs only during a call, so the microphone and the connection keep working when the
 * user switches to another app or turns the screen off. Shows an ongoing "On a call" notification.
 */
class CallService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MeshronApp
        val notification = app.notifier.ongoingCall(app.calls.state.value.name)
        // Camera too once allowed, so video keeps running when you switch apps. Android refuses
        // these types if the app is in the background at that moment: then try less, or give up
        // (the call still works while the app is open).
        val types = listOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (app.calls.hasCameraPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        val ok = types.any { type ->
            runCatching { ServiceCompat.startForeground(this, Notifier.CALL_ID, notification, type) }
                .onFailure { android.util.Log.w("CallService", "Foreground refused: ${it.message}") }
                .isSuccess
        }
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, CallService::class.java))
    }
}
