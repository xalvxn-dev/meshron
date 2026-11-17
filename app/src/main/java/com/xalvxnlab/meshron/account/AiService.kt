package com.xalvxnlab.meshron.account

import com.xalvxnlab.meshron.identity.Mode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Meshron AI: helper, captions, smart replies, translation, moderation. Runs on the account
 * server (the AI key never ships in the app). Only text the person chooses is sent, and
 * never in anonymous mode.
 */
class AiService(private val accounts: AccountService, private val context: android.content.Context) {
    /** Usable now: a public account is signed in on this phone. */
    val available: Flow<Boolean> = accounts.state.map { it != null && !Mode.anon }

    private suspend fun need() {
        if (Mode.anon) throw Exception("AI is off while you are anonymous")
        if (!available.first()) throw Exception("Create a Meshron account (Settings) to use AI")
    }

    class Turn(val mine: Boolean, val text: String)

    /** Asks Meshron AI (someone tagged @meshronai). Returns its answer and signature. */
    suspend fun ask(ref: String, re: String, question: String, caption: String = "", creator: String = "", context: List<String> = emptyList()): MeshronAi.Answer {
        need()
        val c = JSONArray(); context.takeLast(12).forEach { c.put(it) }
        val o = accounts.ai2("/v1/ai/ask", JSONObject().put("ref", ref).put("re", re).put("question", question).put("caption", caption).put("creator", creator).put("context", c))
        val a = MeshronAi.Answer(re, o.optString("text"), o.optString("sig"))
        if (!MeshronAi.verify(ref, re, a.text, a.sig)) throw Exception("Meshron AI's answer couldn't be checked")
        return a
    }

    suspend fun helper(text: String, history: List<Turn>): String {
        need()
        val h = JSONArray()
        history.takeLast(12).forEach { h.put(JSONObject().put("role", if (it.mine) "user" else "assistant").put("content", it.text)) }
        return accounts.ai("helper", JSONObject().put("text", text).put("history", h)).optString("text")
    }

    suspend fun caption(description: String): Pair<String, List<String>> {
        need()
        val o = accounts.ai("caption", JSONObject().put("text", description))
        val tags = o.optJSONArray("hashtags") ?: JSONArray()
        return o.optString("caption") to List(tags.length()) { tags.getString(it) }
    }

    suspend fun replies(recent: List<Turn>): List<String> {
        need()
        val h = JSONArray()
        recent.takeLast(8).forEach { h.put(JSONObject().put("mine", it.mine).put("text", it.text)) }
        val arr = accounts.ai("replies", JSONObject().put("history", h)).optJSONArray("replies") ?: JSONArray()
        return List(arr.length()) { arr.getString(it) }
    }

    /** A message shown in my language: [text], with [note] ("Translated from Spanish", in my language). */
    data class Translation(val text: String, val note: String, val original: String)

    private val tcache = java.util.concurrent.ConcurrentHashMap<String, Translation?>()
    private val tprefs by lazy { context.getSharedPreferences("translations", android.content.Context.MODE_PRIVATE) }

    /**
     * Translates someone's message into my language when it's in another one; null when it's
     * already mine (or there's nothing to translate). Remembered per message, so each is sent once.
     */
    suspend fun autoTranslate(id: String, text: String): Translation? {
        if (tcache.containsKey(id)) return tcache[id]
        tprefs.getString(id, null)?.let { raw ->
            val t = if (raw.isEmpty()) null else JSONObject(raw).let { Translation(it.optString("t"), it.optString("n"), it.optString("o")) }
            tcache[id] = t; return t
        }
        // Nothing to translate: emoji, numbers, links, very short.
        if (text.length < 2 || text.none { it.isLetter() } || text.startsWith("meshron://") || Regex("^https?://\\S+$").matches(text.trim())) { tcache[id] = null; return null }
        need()
        val lang = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)
        val r = accounts.ai("autotranslate", JSONObject().put("text", text).put("lang", lang))
        val t = if (r.optBoolean("same", true) || r.optString("text").isBlank()) null
            else Translation(r.optString("text"), r.optString("note").ifBlank { "Translated from " + r.optString("from") }, r.optString("original").ifBlank { "See original" })
        tcache[id] = t
        tprefs.edit().putString(id, t?.let { JSONObject().put("t", it.text).put("n", it.note).put("o", it.original).toString() } ?: "").apply()
        return t
    }

    suspend fun translate(text: String, lang: String = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)): String {
        need()
        return accounts.ai("translate", JSONObject().put("text", text).put("lang", lang)).optString("text")
    }

    class Verdict(val ok: Boolean, val reason: String)

    /** Checks text before it's posted publicly. If AI isn't usable, lets it through. */
    suspend fun check(text: String): Verdict {
        if (text.isBlank() || !available.first()) return Verdict(true, "")
        return runCatching {
            val o = accounts.ai("moderate", JSONObject().put("text", text))
            Verdict(o.optBoolean("ok", true), o.optString("reason"))
        }.getOrDefault(Verdict(true, ""))
    }
}
