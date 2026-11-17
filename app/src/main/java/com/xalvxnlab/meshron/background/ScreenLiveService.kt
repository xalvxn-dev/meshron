package com.xalvxnlab.meshron.background

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.MeshronApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Streams the screen to a live (games and apps). Android only lets the app capture the screen
 * from a foreground service of type mediaProjection, started right after the user allows it.
 * Shows an ongoing "You're live" notification with a Stop button.
 */
class ScreenLiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MeshronApp
        if (intent?.action == ACTION_STOP) {
            scope.launch { app.live.stop(); stopSelf() }
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        val mic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        val stop = android.app.PendingIntent.getService(this, 7, Intent(this, ScreenLiveService::class.java).setAction(ACTION_STOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        val ok = runCatching { ServiceCompat.startForeground(this, Notifier.LIVE_ID, app.notifier.ongoingLive(title, stop), type) }
            .onFailure { android.util.Log.w("ScreenLive", "Foreground refused: ${it.message}") }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        if (projection == null) { stopSelf(); return START_NOT_STICKY }
        scope.launch { runCatching { app.live.startScreen(title, projection) }.onFailure { stopSelf() } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.launch { (application as MeshronApp).live.let { if (it.session.value?.hosting == true && it.session.value?.screen == true) it.stop() } }
        super.onDestroy()
    }

    // A game going landscape (or back): the stream follows the screen's shape.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (application as MeshronApp).live.screenRotated()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_TITLE = "title"
        private const val ACTION_STOP = "com.xalvxnlab.meshron.LIVE_STOP"

        fun start(context: Context, code: Int, data: Intent, title: String) = ContextCompat.startForegroundService(
            context, Intent(context, ScreenLiveService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data).putExtra(EXTRA_TITLE, title),
        )

        fun stop(context: Context) = context.stopService(Intent(context, ScreenLiveService::class.java))
    }
}
