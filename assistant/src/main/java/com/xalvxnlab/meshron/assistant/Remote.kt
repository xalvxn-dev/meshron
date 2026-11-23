package com.xalvxnlab.meshron.assistant

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Controlling the person's other devices, and being controlled by them (same Meshron account
 * only). Off by default: this phone only takes commands when "Control from my other devices" is
 * on. Commands run here with the phone's own tools; anything that would ask for approval asks on
 * the device that sent the command. Everything goes through the Meshron server: the account's
 * device list and one small mailbox per device, checked every few seconds while it matters.
 */
object Remote {
    data class Dev(val id: String, val name: String, val platform: String, val accept: Boolean, val online: Boolean, val me: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile var devices: List<Dev> = emptyList(); private set
    private val pending = ConcurrentHashMap<String, Pair<String, Long>>() // command id -> (device name, sent at)
    private val decisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var lastRegister = 0L

    /** Runs commands that arrive (set by the floating assistant service). */
    @Volatile var onCommand: ((JSONObject) -> Unit)? = null
    /** Asks the person here to approve something a command they sent is about to do elsewhere. */
    @Volatile var approver: (suspend (String, String) -> Boolean)? = null
    /** The conversation that sent the latest command (answers show up there). */
    @Volatile var replyTo: WeakReference<Brain>? = null
    val listeners = mutableListOf<() -> Unit>() // device list changed

    private fun prefs(c: Context) = c.getSharedPreferences("memory", Context.MODE_PRIVATE)
    fun id(c: Context): String = prefs(c).getString("deviceId", null) ?: ("ph-" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)).also { prefs(c).edit().putString("deviceId", it).apply() }
    fun accept(c: Context) = prefs(c).getBoolean("remote", false)
    fun setAccept(c: Context, on: Boolean) { prefs(c).edit().putBoolean("remote", on).apply(); lastRegister = 0; start(c) }
    fun target(c: Context) = prefs(c).getString("remoteTarget", "").orEmpty()
    fun setTarget(c: Context, id: String) = prefs(c).edit().putString("remoteTarget", id).apply()
    private fun name() = (Build.MODEL ?: "Phone").take(40)

    fun start(c: Context) {
        val app = c.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            val api = Api(app)
            while (true) {
                if (api.signedIn) runCatching { tick(app, api) }
                delay(if (accept(app) || pending.isNotEmpty() || decisions.isNotEmpty()) 3500 else 30000)
            }
        }
    }

    suspend fun register(c: Context, api: Api = Api(c)): List<Dev> {
        val r = api.call("/v1/remote/devices", JSONObject().put("device", JSONObject().put("id", id(c)).put("name", name()).put("platform", "android")).put("accept", accept(c)))
        val arr = r.optJSONArray("devices")
        devices = (0 until (arr?.length() ?: 0)).map { i -> arr!!.getJSONObject(i).let { Dev(it.optString("id"), it.optString("name"), it.optString("platform"), it.optBoolean("accept"), it.optBoolean("online"), it.optBoolean("me")) } }
        lastRegister = System.currentTimeMillis()
        listeners.toList().forEach { runCatching { it() } }
        return devices
    }

    private suspend fun send(c: Context, api: Api, to: String, msg: JSONObject) = api.call("/v1/remote/send", JSONObject().put("from", id(c)).put("to", to).put("msg", msg))

    private suspend fun tick(c: Context, api: Api) {
        if (System.currentTimeMillis() - lastRegister > 60_000) register(c, api)
        val r = api.call("/v1/remote/inbox", JSONObject().put("device", id(c)))
        val msgs = r.optJSONArray("messages") ?: return
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            when (m.optString("kind")) {
                "cmd" -> if (accept(c)) onCommand?.invoke(m)
                "reply" -> {
                    val p = pending[m.optString("id")]
                    if (m.optBoolean("done")) pending.remove(m.optString("id"))
                    val who = p?.first ?: m.optString("fromName", "Other device")
                    replyTo?.get()?.remoteAnswer(who, m.optString("text"))
                }
                "ask" -> scope.launch {
                    val ok = approver?.invoke(m.optString("fromName", "Other device") + ": " + m.optString("title"), m.optString("detail")) ?: false
                    runCatching { send(c, api, m.optString("from"), JSONObject().put("kind", "decide").put("id", m.optString("id")).put("aid", m.optString("aid")).put("ok", ok)) }
                }
                "decide" -> decisions.remove(m.optString("aid"))?.complete(m.optBoolean("ok"))
            }
        }
        pending.entries.removeIf { System.currentTimeMillis() - it.value.second > 180_000 }
    }

    /** Which device a request is for: "on my PC, …" picks one by type; else the chosen target. */
    sealed class Route { data class To(val dev: Dev, val text: String) : Route(); data class Missing(val what: String) : Route(); data class Here(val text: String) : Route() }
    fun route(c: Context, text: String): Route? {
        val m = Regex("^(?:on|with|using) (?:my )?(phone|mobile|android|pc|computer|laptop|desktop)[,:]?\\s+(.+)$", RegexOption.IGNORE_CASE).find(text.trim())
        val others = devices.filter { !it.me }
        if (m != null) {
            val want = if (Regex("phone|mobile|android", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[1])) "android" else "windows"
            val d = others.firstOrNull { it.platform == want && it.online } ?: others.firstOrNull { it.platform == want }
            if (want == "android" && d == null) return Route.Here(m.groupValues[2])
            return if (d != null) Route.To(d, m.groupValues[2]) else Route.Missing(m.groupValues[1])
        }
        val t = target(c)
        if (t.isNotEmpty() && t != id(c)) others.firstOrNull { it.id == t }?.let { return Route.To(it, text) }
        return null
    }

    /** Sends a command; its answer arrives in [brain]'s conversation. */
    suspend fun command(c: Context, brain: Brain, dev: Dev, text: String) {
        val cid = java.util.UUID.randomUUID().toString().take(16)
        send(c, Api(c), dev.id, JSONObject().put("kind", "cmd").put("id", cid).put("text", text))
        pending[cid] = dev.name to System.currentTimeMillis()
        replyTo = WeakReference(brain)
        start(c)
    }

    /** Sends the answer to a command back to the device that sent it. */
    fun answer(c: Context, cmd: JSONObject, text: String) { scope.launch { runCatching { send(c, Api(c), cmd.optString("from"), JSONObject().put("kind", "reply").put("id", cmd.optString("id")).put("text", text).put("done", true)) } } }

    /** While running another device's command: ask that device to approve. */
    suspend fun askApproval(c: Context, cmd: JSONObject, title: String, detail: String): Boolean {
        val aid = java.util.UUID.randomUUID().toString().take(12)
        val d = CompletableDeferred<Boolean>()
        decisions[aid] = d
        runCatching { send(c, Api(c), cmd.optString("from"), JSONObject().put("kind", "ask").put("id", cmd.optString("id")).put("aid", aid).put("title", title).put("detail", detail)) }.onFailure { decisions.remove(aid); return false }
        return withTimeoutOrNull(120_000) { d.await() } ?: false.also { decisions.remove(aid) }
    }
}
