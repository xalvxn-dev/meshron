package com.xalvxnlab.meshron.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.MainActivity

/**
 * Local notifications only (nothing goes through a push server). New-message notifications
 * are shown while the app is in the background; on a locked screen they hide the text.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.createNotificationChannel(
            NotificationChannel(CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        // Silent: the app plays the ringtone itself (it honours silent mode).
        manager.createNotificationChannel(
            NotificationChannel(CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
        )
    }

    private fun action(action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Tapping it opens the app on the incoming call screen. */
    @SuppressLint("MissingPermission") // checked in canNotify()
    fun incomingCall(name: String, video: Boolean = false) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(name)
            .setContentText(if (video) "Incoming video call · tap to answer" else "Incoming voice call · tap to answer")
            .setContentIntent(openApp())
            .addAction(0, "Decline", action(CallActionReceiver.ACTION_DECLINE))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        manager.notify(INCOMING_ID, n)
    }

    fun ongoingCall(name: String): Notification =
        NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("On a call with $name")
            .setContentText("Tap to return to the call")
            .setContentIntent(openApp())
            .addAction(0, "Hang up", action(CallActionReceiver.ACTION_HANG_UP))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

    @SuppressLint("MissingPermission") // checked in canNotify()
    fun missedCall(name: String) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call")
            .setContentText(name)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        manager.notify(MISSED_ID, n)
    }

    fun cancelCall() = manager.cancel(INCOMING_ID)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun connectionNotification(): Notification =
        NotificationCompat.Builder(context, CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Staying connected")
            .setContentText("Messages keep arriving while the app is closed")
            .setContentIntent(openApp())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** One notification per conversation, replaced by the newest message. */
    @SuppressLint("MissingPermission") // checked in canNotify()
    fun newMessage(conversation: String, title: String, text: String) {
        if (!canNotify()) return
        val hidden = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("New message")
            .build()
        val n = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .build()
        manager.notify(conversation, MESSAGE_ID, n)
    }

    fun clearMessages() {
        manager.activeNotifications.filter { it.id == MESSAGE_ID }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled()

    companion object {
        const val MESSAGES = "messages"
        const val CONNECTION = "connection"
        const val CONNECTION_ID = 1
        const val MESSAGE_ID = 2
        const val CALLS = "calls"
        const val CALL_ID = 3
        const val INCOMING_ID = 4
        const val MISSED_ID = 5
    }
}
