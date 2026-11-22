package com.xalvxnlab.meshron.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line in the conversation on screen. */
sealed class Line {
    data class You(val text: String) : Line()
    data class Me(val text: String) : Line()
    /** A tool the assistant ran: "flashlight · on" and how it went. */
    data class Act(val label: String, val result: String, val ok: Boolean) : Line()
    data class Oops(val text: String) : Line()
}

/**
 * The conversation: what the person says, the model's steps, the tools it runs.
 * A few things work with no internet at all (apps, flashlight, alarms, timers, camera, battery, lock).
 */
class Brain(private val api: Api, private val host: Host) {
    val lines = mutableStateListOf<Line>()
    val thinking = mutableStateOf(false)
    private val device = Device(host)
    private var messages = JSONArray()
    var onReply: ((String) -> Unit)? = null

    fun reset() { messages = JSONArray(); lines.clear() }

    /** [shown] is what the transcript shows when it differs from what's sent (a whole document, say). */
    suspend fun ask(text: String, image: Bitmap? = null, shown: String? = null) {
        val t = text.trim()
        if (t.isEmpty() && image == null) return
        lines += Line.You(shown ?: if (image != null) "📷 $t" else t)
        thinking.value = true
        try {
            if (shown == null && image == null) local(t)?.let { say(it); return }
            if (!online()) { say("I'm offline right now. I can still open apps, use the flashlight, set alarms and timers, open the camera, check the battery and lock the phone."); return }
            messages.put(JSONObject().put("role", "user").put("content", t.ifEmpty { "What is this?" }))
            converse(image?.let { Device.dataUrl(it) })
        } catch (e: ApiError) {
            lines += Line.Oops(e.message ?: "Something went wrong")
        } catch (e: Exception) {
            lines += Line.Oops("Something went wrong: " + (e.message ?: e.javaClass.simpleName))
        } finally { thinking.value = false }
    }

    private suspend fun converse(firstImage: String?) {
        var image = firstImage
        repeat(16) {
            val r = api.step(messages, context(), image)
            image = null
            if (r.has("text")) {
                val reply = r.getString("text")
                messages.put(JSONObject().put("role", "assistant").put("content", reply))
                trim()
                say(reply)
                return
            }
            messages.put(r.getJSONObject("message"))
            r.optJSONArray("done")?.let { d -> for (i in 0 until d.length()) { messages.put(d.getJSONObject(i)); lines += Line.Act("web search", "looked it up", true) } }
            val calls = r.getJSONArray("calls")
            var nextPhoto: Outcome? = null
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val name = c.getString("name"); val args = c.optJSONObject("args") ?: JSONObject()
                val out = device.run(name, args)
                lines += Line.Act(label(name, args), out.text.lineSequence().first().take(90), !out.text.startsWith("Failed") && !out.text.startsWith("The person didn't"))
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", c.getString("id")).put("content", out.text))
                if (out.image != null) nextPhoto = out
            }
            // A photo (camera, screenshot, document) goes to the model with the question, as the next message.
            nextPhoto?.let { p ->
                messages.put(JSONObject().put("role", "user").put("content", p.question ?: "What is this?"))
                image = Device.dataUrl(p.image!!)
            }
        }
        say("That took more steps than I'd like. Try asking it another way.")
    }

    private fun say(text: String) { lines += Line.Me(text); onReply?.invoke(text) }

    /** Keeps the conversation short enough to send (the server keeps the last 24 anyway). */
    private fun trim() {
        if (messages.length() <= 30) return
        val keep = JSONArray()
        var start = messages.length() - 24
        while (start < messages.length() && messages.getJSONObject(start).optString("role") != "user") start++
        for (i in start until messages.length()) keep.put(messages.get(i))
        messages = keep
    }

    private fun context() = JSONObject()
        .put("now", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm, EEEE")))
        .put("timezone", ZoneId.systemDefault().id)
        .put("battery", batteryPercent(host.ctx))
        .put("name", api.username)
        .put("language", Locale.getDefault().displayLanguage)
        .put("screen", ScreenContext.fresh)
        .put("platform", "android")
        .put("assistantName", Memory.name(host.ctx))
        .put("canAct", ControlService.on)

    private fun online(): Boolean {
        val cm = host.ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun label(name: String, a: JSONObject) = when (name) {
        "open_app" -> "open · " + a.optString("name")
        "flashlight" -> "flashlight · " + if (a.optBoolean("on")) "on" else "off"
        "set_alarm" -> "alarm · %02d:%02d".format(a.optInt("hour"), a.optInt("minute"))
        "set_timer" -> "timer · " + a.optInt("seconds") / 60 + " min"
        "add_reminder" -> "reminder · " + a.optString("at").replace('T', ' ')
        "add_task" -> "task · " + a.optString("text").take(30)
        "send_sms" -> "text · " + a.optString("to")
        "call" -> "call · " + a.optString("to")
        "reply_notification" -> "reply"
        else -> name.replace('_', ' ')
    }

    // ---- Offline commands ------------------------------------------------------------------------

    private suspend fun local(t: String): String? {
        val s = t.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')
        val phrase = Memory.lockPhrase(host.ctx)
        if (phrase.isNotEmpty() && s.contains(phrase)) return act("lock", device.lock())
        Regex("^(?:please )?(?:open|launch|start)(?: the)? (.+?)(?: app)?$").find(s)?.let { m ->
            val app = m.groupValues[1]
            if (app == "camera") { host.ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return act("open · camera", Outcome("Camera open")) }
            return act("open · $app", device.openApp(app))
        }
        if (Regex("\\b(flash ?light|torch)\\b").containsMatchIn(s)) {
            val on = !Regex("\\boff\\b|turn it off|disable").containsMatchIn(s)
            return act("flashlight · " + if (on) "on" else "off", device.flashlight(on))
        }
        Regex("^(?:set |wake me up )?(?:an? )?alarm (?:for |at )?(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a\\.m|p\\.m)?$").find(s)?.let { m ->
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val ap = m.groupValues[3]
            if (ap.startsWith("p") && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            return act("alarm · %02d:%02d".format(h, min), device.run("set_alarm", JSONObject().put("hour", h).put("minute", min)))
        }
        Regex("^(?:set |start )?(?:a )?timer (?:for )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)$").find(s)?.let { m ->
            val n = m.groupValues[1].toInt(); val u = m.groupValues[2]
            val sec = when { u.startsWith("h") -> n * 3600; u.startsWith("m") -> n * 60; else -> n }
            return act("timer · ${m.groupValues[1]} ${u}", device.run("set_timer", JSONObject().put("seconds", sec)))
        }
        if (Regex("^(?:what'?s (?:my |the )?)?battery(?: level| status)?$|^how much battery").containsMatchIn(s)) return act("battery", device.battery())
        if (Regex("^(?:take a (?:photo|picture|selfie)|open (?:the )?camera)$").matches(s)) {
            host.ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return act("open · camera", Outcome("Camera open"))
        }
        if (Regex("^(?:lock (?:the |my )?phone|lockdown)$").matches(s)) return act("lock", device.lock())
        ControlService.instance?.let { cs ->
            when {
                Regex("^(?:go )?home(?: screen)?$").matches(s) -> return act("home", Outcome(cs.global("home")))
                Regex("^(?:go )?back$").matches(s) -> return act("back", Outcome(cs.global("back")))
                Regex("^(?:show )?recent(?:s| apps)?$").matches(s) -> return act("recents", Outcome(cs.global("recents")))
                Regex("^(?:take a )?screenshot$").matches(s) -> return act("screenshot", Outcome(cs.global("screenshot")))
                Regex("^close (?:this|the)(?: app)?$").matches(s) -> return act("close app", Outcome(cs.closeApp()))
                Regex("^scroll (up|down)$").find(s) != null -> return act("scroll", Outcome(cs.scroll(Regex("^scroll (up|down)$").find(s)!!.groupValues[1])))
                Regex("^(?:next|next video|swipe up)$").matches(s) -> return act("next", Outcome(cs.swipe("up")))
            }
        }
        return null
    }

    private fun act(label: String, o: Outcome): String {
        val ok = !o.text.startsWith("Failed") && !o.text.startsWith("I couldn't") && !o.text.startsWith("Locking needs")
        lines += Line.Act(label, o.text, ok)
        return if (ok) o.text + "." else o.text
    }
}
