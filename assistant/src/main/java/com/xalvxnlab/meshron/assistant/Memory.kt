package com.xalvxnlab.meshron.assistant

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/** What the assistant keeps on the phone: tasks, reminders and settings. Never sent anywhere except as tool results. */
object Memory {
    private fun prefs(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE)

    // ---- Settings ----
    fun speak(c: Context) = prefs(c).getBoolean("speak", true)
    fun setSpeak(c: Context, on: Boolean) = prefs(c).edit().putBoolean("speak", on).apply()
    fun sentry(c: Context) = prefs(c).getBoolean("sentry", false)
    fun setSentry(c: Context, on: Boolean) = prefs(c).edit().putBoolean("sentry", on).apply()
    /** Saying this phrase locks the phone at once, even offline. */
    fun lockPhrase(c: Context) = prefs(c).getString("lockPhrase", "").orEmpty()
    fun setLockPhrase(c: Context, p: String) = prefs(c).edit().putString("lockPhrase", p.trim().lowercase()).apply()

    // ---- Tasks ----
    fun tasks(c: Context): JSONArray = runCatching { JSONArray(prefs(c).getString("tasks", "[]")) }.getOrDefault(JSONArray())
    fun saveTasks(c: Context, a: JSONArray) = prefs(c).edit().putString("tasks", a.toString()).apply()
    fun addTask(c: Context, text: String, due: String?) {
        val a = tasks(c)
        a.put(JSONObject().put("text", text.take(200)).put("due", due ?: "").put("done", false).put("at", System.currentTimeMillis()))
        // Keep it tidy: at most 100, dropping the oldest done ones first.
        while (a.length() > 100) { val i = (0 until a.length()).firstOrNull { a.getJSONObject(it).optBoolean("done") } ?: 0; a.remove(i) }
        saveTasks(c, a)
    }

    // ---- Reminders ----
    fun reminders(c: Context): JSONArray = runCatching { JSONArray(prefs(c).getString("reminders", "[]")) }.getOrDefault(JSONArray())
    fun addReminder(c: Context, text: String, at: LocalDateTime): Long {
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = (System.currentTimeMillis() % Int.MAX_VALUE)
        val a = reminders(c).apply { put(JSONObject().put("id", id).put("text", text.take(200)).put("at", ms)) }
        prefs(c).edit().putString("reminders", a.toString()).apply()
        schedule(c, id.toInt(), text, ms)
        return ms
    }
    fun schedule(c: Context, id: Int, text: String, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, id, Intent(c, ReminderReceiver::class.java).putExtra("text", text).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
    /** After a restart, future reminders are set again; past ones are dropped. */
    fun reschedule(c: Context) {
        val now = System.currentTimeMillis()
        val keep = JSONArray()
        val a = reminders(c)
        for (i in 0 until a.length()) { val r = a.getJSONObject(i); if (r.getLong("at") > now) { keep.put(r); schedule(c, r.getLong("id").toInt(), r.getString("text"), r.getLong("at")) } }
        prefs(c).edit().putString("reminders", keep.toString()).apply()
    }

    fun channel(c: Context, id: String, name: String, importance: Int = NotificationManager.IMPORTANCE_HIGH) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id, name, importance))
    }
}

/** What was on the screen when the person opened the assistant (from the assist gesture). */
object ScreenContext {
    @Volatile var text: String = ""
    @Volatile var shot: Bitmap? = null
    @Volatile var at: Long = 0
    val fresh get() = System.currentTimeMillis() - at < 10 * 60_000 && (text.isNotBlank() || shot != null)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val text = i.getStringExtra("text").orEmpty()
        Memory.channel(c, "reminders", "Reminders")
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, "reminders").setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Reminder").setContentText(text).setAutoCancel(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(i.getIntExtra("id", 1), n) }
        if (Memory.speak(c)) Speaker.say(c, "Reminder: $text")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        Memory.reschedule(c)
        if (Memory.sentry(c)) runCatching { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, SentryService::class.java)) }
    }
}

class LockAdmin : android.app.admin.DeviceAdminReceiver()
