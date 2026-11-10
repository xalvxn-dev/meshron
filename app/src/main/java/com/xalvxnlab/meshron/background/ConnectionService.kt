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
 * Optional ("Stay connected" in Profile). A foreground service keeps the app's process, and
 * so the Hyperswarm worklet, running while the app is in the background, so messages keep
 * arriving and syncing. There's no push server: without this, messages arrive only while
 * the app is open. Android shows a quiet ongoing notification while it runs.
 */
class ConnectionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MeshronApp
        ServiceCompat.startForeground(
            this,
            Notifier.CONNECTION_ID,
            app.notifier.connectionNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // Also covers Android restarting the service after killing the process.
        app.startMessaging()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, ConnectionService::class.java))
    }
}
