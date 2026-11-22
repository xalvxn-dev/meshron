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

/** Settings for the floating orb, listening and the voice. */
fun Memory.floating(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getBoolean("floating", false)
fun Memory.setFloating(c: Context, on: Boolean) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putBoolean("floating", on).apply()
/** Always listening for "Hey Meshron" (true) or push to talk (false). */
fun Memory.always(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getBoolean("always", false)
fun Memory.setAlways(c: Context, on: Boolean) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putBoolean("always", on).apply()
fun Memory.voice(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getString("voice", "").orEmpty()
fun Memory.setVoice(c: Context, name: String) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putString("voice", name).apply()
/** Where the orb sits: which side, and how far down (0..1). */
fun Memory.orbSpot(c: Context): Pair<Boolean, Float> = c.getSharedPreferences("memory", Context.MODE_PRIVATE).let { it.getBoolean("orbRight", true) to it.getFloat("orbY", 0.35f) }
fun Memory.setOrbSpot(c: Context, right: Boolean, y: Float) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putBoolean("orbRight", right).putFloat("orbY", y).apply()
/** The person's own button combo: off, down2 (volume down twice), up2, both (both volume keys), updown (up then down). */
fun Memory.combo(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getString("combo", "off").orEmpty()
fun Memory.setCombo(c: Context, v: String) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putString("combo", v).apply()
/** Shake the phone to talk (while the floating orb is on). */
fun Memory.shake(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getBoolean("shake", false)
fun Memory.setShake(c: Context, on: Boolean) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putBoolean("shake", on).apply()
/** The folder the person let the assistant organise (a tree URI). */
fun Memory.folder(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getString("folder", "").orEmpty()
fun Memory.setFolder(c: Context, uri: String) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putString("folder", uri).apply()
/** The realistic studio voice (online, from the server) instead of the phone's voice. */
fun Memory.studio(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getBoolean("studio", true)
fun Memory.setStudio(c: Context, on: Boolean) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putBoolean("studio", on).apply()
/** Which studio voice: troy, daniel, austin (male); autumn, diana, hannah (female). */
fun Memory.studioVoice(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getString("studioVoice", "troy").orEmpty()
fun Memory.setStudioVoice(c: Context, v: String) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putString("studioVoice", v).apply()
/** What the assistant is called (and the wake word): "Meshron" unless the person renamed it. */
fun Memory.name(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getString("name", "Meshron").orEmpty().ifBlank { "Meshron" }
fun Memory.setName(c: Context, n: String) = c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putString("name", n.trim().take(24)).apply()

/**
 * Hearing the wake word. Recognisers rarely spell a name the way it's written ("Meshron" comes out
 * as "mesh run", "machine", "mesh Ron"), so the name is matched loosely: one or two spoken words
 * that are close to it in spelling count, with or without "hey" / "ok" before them.
 */
object Wake {
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun squash(s: String) = s.replace(" ", "").replace("'", "")
    private fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
        }
        return d[b.length]
    }
    /** Extra spellings recognisers commonly produce for the default name. */
    private val MESHRON = listOf("meshron", "meshrun", "meshroom", "machine", "mashron", "meshran", "mishron", "marshron", "meshrom")

    /** null: not woken. "": woken, nothing more said. Otherwise the request that followed the name. */
    fun command(heard: String, name: String): String? {
        val words = norm(heard).split(' ').filter { it.isNotEmpty() }
        val target = squash(norm(name))
        if (words.isEmpty() || target.isEmpty()) return null
        val allowed = maxOf(1, target.length / 4)
        val alts = if (target == "meshron") MESHRON else listOf(target)
        for (i in words.indices) {
            for (span in 1..2) {
                if (i + span > words.size) continue
                val cand = words.subList(i, i + span).joinToString("")
                if (alts.any { a -> cand == a || (cand.length >= 3 && distance(cand, a) <= allowed) }) {
                    // Only when it's at the start ("hey Nova…") or after a greeting, so "nova" in a sentence doesn't wake it.
                    val before = words.subList(0, i)
                    if (before.isNotEmpty() && !before.all { it in setOf("hey", "hi", "ok", "okay", "yo", "hello", "a") }) continue
                    return words.drop(i + span).joinToString(" ")
                }
            }
        }
        return null
    }
}
