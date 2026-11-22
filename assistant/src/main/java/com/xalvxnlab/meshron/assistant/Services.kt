package com.xalvxnlab.meshron.assistant

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

// ---- Default digital assistant -----------------------------------------------------------------
// When the person picks Meshron Assistant as the phone's assistant, the assist gesture (long-press
// home or power) opens it, and Android hands over the text and a screenshot of what was on screen
// (if they allowed "Use text from screen" / "Use screenshot" in the assistant settings).

class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        ScreenContext.text = ""; ScreenContext.shot = null; ScreenContext.at = System.currentTimeMillis()
        startAssistantActivity(Intent(context, MainActivity::class.java).putExtra("assist", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Stay a moment so the screen's text and picture can arrive, then step aside.
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { hide() } }, 2500)
    }

    override fun onHandleAssist(state: AssistState) {
        val s = state.assistStructure ?: return
        val out = StringBuilder()
        fun walk(n: AssistStructure.ViewNode, depth: Int) {
            if (out.length > 12_000 || depth > 60) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append('\n') }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && n.text == null }?.let { out.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
        }
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, 0)
        ScreenContext.text = out.toString().take(12_000)
        ScreenContext.at = System.currentTimeMillis()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot != null) { ScreenContext.shot = screenshot; ScreenContext.at = System.currentTimeMillis() }
    }
}

/** Android requires an assistant to name a speech recogniser; Meshron Assistant uses the phone's own (see Voice). */
class AssistRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) { runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

// ---- Notifications -----------------------------------------------------------------------------
// Only after the person allows "Notification access": the assistant can read recent notifications
// to triage them, and reply to message notifications that offer a reply box (after approval).

class NotificationWatcher : NotificationListenerService() {
    data class Item(val key: String, val app: String, val title: String, val text: String, val at: Long, val reply: Notification.Action?)

    companion object {
        @Volatile var instance: NotificationWatcher? = null
        private val recent = ArrayDeque<Item>()
        fun list(): List<Item> = synchronized(recent) { recent.toList().sortedByDescending { it.at } }
        fun find(key: String): Item? = synchronized(recent) { recent.firstOrNull { it.key == key } }
    }

    override fun onListenerConnected() {
        instance = this
        runCatching { activeNotifications?.forEach { keep(it) } }
    }
    override fun onListenerDisconnected() { instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) = keep(sbn)
    override fun onNotificationRemoved(sbn: StatusBarNotification) { synchronized(recent) { recent.removeAll { it.key == sbn.key } } }

    private fun keep(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val reply = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        synchronized(recent) {
            recent.removeAll { it.key == sbn.key }
            recent.addLast(Item(sbn.key, app, title.take(120), text.take(600), sbn.postTime, reply))
            while (recent.size > 40) recent.removeFirst()
        }
    }

    /** Sends [text] through the notification's own reply box. */
    fun reply(item: Item, text: String): Boolean {
        val action = item.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        for (r in inputs) results.putCharSequence(r.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(this, 0, intent); true }.getOrDefault(false)
    }
}

// ---- Sentry mode ------------------------------------------------------------------------------
// Optional: a quiet foreground service that speaks up when the charger is plugged in or out, when
// the battery is low or full, and when the phone runs hot.

class SentryService : Service() {
    private var lastLevelSpoken = -1
    private var hotWarned = false
    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pct = batteryPercent(c)
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> announce("Charger connected. Battery at $pct percent.")
                Intent.ACTION_POWER_DISCONNECTED -> announce("Charger disconnected. Battery at $pct percent.")
                Intent.ACTION_BATTERY_LOW -> announce("Battery low, $pct percent. Want me to remind you to charge?")
                Intent.ACTION_BATTERY_CHANGED -> {
                    val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (plugged && pct >= 100 && lastLevelSpoken != 100) { lastLevelSpoken = 100; announce("Fully charged.") }
                    if (pct < 100) lastLevelSpoken = pct
                    val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                    if (temp >= 45f && !hotWarned) { hotWarned = true; announce("Heads up: the phone is running hot, ${temp.toInt()} degrees.") }
                    if (temp < 40f) hotWarned = false
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Memory.channel(this, "sentry", "Sentry mode", NotificationManager.IMPORTANCE_MIN)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "sentry").setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Sentry mode is on").setContentText("I'll tell you about charger and battery changes.")
            .setOngoing(true).setContentIntent(open).build()
        ServiceCompat.startForeground(this, 7, n, if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_BATTERY_CHANGED)
        })
    }

    override fun onDestroy() { runCatching { unregisterReceiver(power) }; super.onDestroy() }

    private fun announce(text: String) {
        Memory.channel(this, "alerts", "Alerts")
        val n = NotificationCompat.Builder(this, "alerts").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Meshron Assistant").setContentText(text).setAutoCancel(true).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(8, n) }
        if (Memory.speak(this)) Speaker.say(this, text)
    }
}

fun batteryPercent(c: Context): Int = c.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
