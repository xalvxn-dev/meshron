package com.xalvxnlab.meshron.assistant

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Base64
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the screen provides to the tools: approvals, permissions, the camera and the file picker. */
interface Host {
    val ctx: android.content.Context
    suspend fun approve(title: String, detail: String): Boolean
    suspend fun permission(name: String): Boolean
    suspend fun takePhoto(): Bitmap?
    suspend fun pickDocument(): Picked?
    /** Asks Android for something that needs a screen (see ProxyActivity). */
    suspend fun system(r: ProxyActivity.Req): Any? = ProxyActivity.ask(ctx, r)
}

/** A picked document: its text, or a picture of it (photos, the first page of a PDF). */
class Picked(val name: String, val text: String?, val image: Bitmap?)

/** A tool's result: words for the model, and maybe a picture to look at next. */
class Outcome(val text: String, val image: Bitmap? = null, val question: String? = null)

/** Runs the tools the model asks for. Anything that sends, calls or replies asks the person first. */
class Device(internal val host: Host) {
    internal val a get() = host.ctx
    private val zone = ZoneId.systemDefault()

    suspend fun run(name: String, args: JSONObject): Outcome = runCatching { exec(name, args) }.getOrElse { Outcome("Failed: " + (it.message ?: it.javaClass.simpleName)) }

    private suspend fun exec(name: String, x: JSONObject): Outcome = when (name) {
        "open_app" -> openApp(x.optString("name"))
        "open_url" -> {
            val url = x.optString("url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) Outcome("Only web links can be opened")
            else { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Outcome("Opened $url") }
        }
        "set_alarm" -> {
            val h = x.optInt("hour", -1); val m = x.optInt("minute", 0)
            if (h !in 0..23 || m !in 0..59) Outcome("That time isn't valid")
            else {
                a.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, x.optString("label").take(60)).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Outcome("Alarm set for %02d:%02d".format(h, m))
            }
        }
        "set_timer" -> {
            val s = x.optInt("seconds", 0)
            if (s !in 1..86_400) Outcome("That length isn't valid")
            else {
                a.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_MESSAGE, x.optString("label").take(60))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Outcome("Timer started for ${s / 60} min ${s % 60} s")
            }
        }
        "add_reminder" -> {
            val at = parseTime(x.optString("at")) ?: return Outcome("I couldn't read that time")
            if (at.isBefore(LocalDateTime.now())) return Outcome("That time has already passed")
            host.permission(Manifest.permission.POST_NOTIFICATIONS)
            Memory.addReminder(a, x.optString("text"), at)
            Outcome("Reminder set for " + at.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")))
        }
        "add_task" -> { Memory.addTask(a, x.optString("text"), x.optString("due").ifBlank { null }); Outcome("Added to tasks") }
        "list_tasks" -> {
            val t = Memory.tasks(a)
            val open = (0 until t.length()).map { t.getJSONObject(it) }.filter { !it.optBoolean("done") }
            Outcome(if (open.isEmpty()) "No open tasks" else open.joinToString("\n") { "- " + it.optString("text") + (it.optString("due").takeIf { d -> d.isNotBlank() }?.let { d -> " (due $d)" } ?: "") })
        }
        "complete_task" -> {
            val q = x.optString("text").lowercase()
            val t = Memory.tasks(a)
            val hit = (0 until t.length()).map { t.getJSONObject(it) }.firstOrNull { !it.optBoolean("done") && it.optString("text").lowercase().contains(q) }
            if (hit == null) Outcome("No open task matches \"$q\"") else { hit.put("done", true); Memory.saveTasks(a, t); Outcome("Done: " + hit.optString("text")) }
        }
        "read_calendar" -> readCalendar(x.optInt("days", 1).coerceIn(1, 14))
        "read_notifications" -> {
            if (NotificationWatcher.instance == null) Outcome("Notification access is off. The person can turn it on in Meshron Assistant's settings.")
            else {
                val app = x.optString("app").lowercase()
                val items = NotificationWatcher.list().filter { app.isBlank() || it.app.lowercase().contains(app) }.take(15)
                Outcome(if (items.isEmpty()) "No notifications" else items.joinToString("\n") {
                    "[key=${it.key}] ${it.app} · ${ago(it.at)} · ${it.title}: ${it.text.take(240)}" + if (it.reply != null) " (can reply)" else ""
                })
            }
        }
        "reply_notification" -> {
            val item = NotificationWatcher.find(x.optString("key")) ?: return Outcome("That notification is gone")
            val text = x.optString("text").take(1000)
            if (item.reply == null) Outcome("That notification has no reply box")
            else if (!host.approve("Reply to ${item.title} on ${item.app}?", text)) Outcome("The person didn't approve the reply")
            else if (NotificationWatcher.instance?.reply(item, text) == true) Outcome("Reply sent") else Outcome("The reply couldn't be sent")
        }
        "send_sms" -> {
            val to = x.optString("to"); val text = x.optString("text").take(1000)
            val number = number(to) ?: return Outcome("I couldn't find a number for $to")
            if (!host.approve("Text $to?", text)) Outcome("The person didn't approve the message")
            else { a.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Outcome("The message is ready in Messages for the person to send") }
        }
        "call" -> {
            val to = x.optString("to")
            val number = number(to) ?: return Outcome("I couldn't find a number for $to")
            if (!host.approve("Call $to?", number)) Outcome("The person didn't approve the call")
            else { a.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); Outcome("The dialer is open with $to's number") }
        }
        "flashlight" -> flashlight(x.optBoolean("on", true))
        "set_volume" -> {
            val am = a.getSystemService(AudioManager::class.java)
            val stream = when (x.optString("stream")) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_MUSIC }
            val level = x.optInt("level", 50).coerceIn(0, 100)
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream) * level / 100, AudioManager.FLAG_SHOW_UI)
            Outcome("Volume set to $level%")
        }
        "battery_status" -> battery()
        "read_screen" -> when {
            ScreenContext.fresh && ScreenContext.text.isNotBlank() -> Outcome("Screen text:\n" + ScreenContext.text.take(8000))
            ScreenContext.fresh && ScreenContext.shot != null -> Outcome("A screenshot of the screen is attached.", ScreenContext.shot, "What's on this screen?")
            else -> Outcome("I don't have the screen. The person has to open me with the assist gesture (long-press home or power) with Meshron Assistant set as the phone's assistant and \"Use text from screen\" on.")
        }
        "look" -> {
            val q = x.optString("question").ifBlank { "What is this?" }
            val photo = host.takePhoto() ?: return Outcome("No photo was taken")
            Outcome("Photo taken; it's attached.", photo, q)
        }
        "read_document" -> {
            val q = x.optString("question").ifBlank { "Explain this." }
            val p = host.pickDocument() ?: return Outcome("No document was picked")
            if (p.text != null) Outcome("Document \"${p.name}\":\n" + p.text.take(12_000)) else Outcome("The document \"${p.name}\" is attached as a picture.", p.image, q)
        }
        "lock_phone" -> lock()
        else -> extra(name, x)
    }

    // ---- Helpers ----

    fun openApp(name: String): Outcome {
        val q = name.trim().lowercase().removeSuffix(" app")
        if (q.isEmpty()) return Outcome("Which app?")
        val pm = a.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it to it.loadLabel(pm).toString() }
        val hit = apps.firstOrNull { it.second.lowercase() == q } ?: apps.firstOrNull { it.second.lowercase().startsWith(q) } ?: apps.firstOrNull { it.second.lowercase().contains(q) }
            ?: return Outcome("I couldn't find an app called $name")
        val i = pm.getLaunchIntentForPackage(hit.first.activityInfo.packageName) ?: return Outcome("${hit.second} can't be opened")
        a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Outcome("Opened ${hit.second}")
    }

    fun flashlight(on: Boolean): Outcome {
        val cm = a.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return Outcome("This phone has no flashlight")
        cm.setTorchMode(id, on)
        return Outcome("Flashlight " + if (on) "on" else "off")
    }

    fun battery(): Outcome {
        val i = a.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        return Outcome("Battery ${batteryPercent(a)}%, " + (if (plugged) "charging" else "not charging") + ", ${temp.toInt()}°C")
    }

    fun lock(): Outcome {
        val dpm = a.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isAdminActive(ComponentName(a, LockAdmin::class.java))) return Outcome("Locking needs \"Lock the phone\" turned on in Meshron Assistant's settings.")
        dpm.lockNow()
        return Outcome("Phone locked")
    }

    private suspend fun readCalendar(days: Int): Outcome {
        if (!host.permission(Manifest.permission.READ_CALENDAR)) return Outcome("The person didn't allow calendar access")
        val start = System.currentTimeMillis()
        val end = start + days * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, start); ContentUris.appendId(it, end) }.build()
        val out = mutableListOf<String>()
        a.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION), null, null, CalendarContract.Instances.BEGIN)?.use { c ->
            val f = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
            while (c.moveToNext() && out.size < 40) {
                val b = Instant.ofEpochMilli(c.getLong(1)).atZone(zone); val e = Instant.ofEpochMilli(c.getLong(2)).atZone(zone)
                out += "- " + (if (c.getInt(3) == 1) b.format(DateTimeFormatter.ofPattern("EEE d MMM")) + " all day" else b.format(f) + "–" + e.format(DateTimeFormatter.ofPattern("HH:mm"))) +
                    " " + c.getString(0).orEmpty() + (c.getString(4)?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: "")
            }
        }
        return Outcome(if (out.isEmpty()) "No events in the next $days day(s)" else out.joinToString("\n"))
    }

    /** A phone number from a contact's name (or the number itself). */
    private suspend fun number(to: String): String? {
        val digits = to.filter { it.isDigit() || it == '+' }
        if (digits.length >= 5 && digits.length >= to.count { it.isLetterOrDigit() } - 1) return digits
        if (!host.permission(Manifest.permission.READ_CONTACTS)) return null
        a.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%" + to.trim() + "%"), null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun parseTime(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s.take(16)) }.getOrNull()
        ?: runCatching { LocalDate.parse(s.take(10)).atTime(9, 0) }.getOrNull()

    private fun ago(t: Long): String { val m = (System.currentTimeMillis() - t) / 60_000; return if (m < 1) "now" else if (m < 60) "${m}m ago" else "${m / 60}h ago" }

    companion object {
        /** A photo as a JPEG data URL for the vision model (longest side at most 1024 px). */
        fun dataUrl(b: Bitmap): String {
            val k = 1024f / maxOf(b.width, b.height)
            val s = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
            val out = ByteArrayOutputStream()
            s.compress(Bitmap.CompressFormat.JPEG, 82, out)
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        fun granted(c: android.content.Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val emptyArray get() = JSONArray()
    }
}
