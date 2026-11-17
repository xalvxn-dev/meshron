package com.xalvxnlab.meshron.account

import com.xalvxnlab.meshron.crypto.Ed25519
import org.json.JSONObject

/**
 * Meshron AI's own account (@meshronai, verified + moderator). People tag it in comments and
 * groups; the server answers and signs with this account's key, so every app can check an
 * answer really came from Meshron AI before showing it as such.
 */
object MeshronAi {
    const val ID = "1814b421f78a892ee898487af96ec45421cf50f996fae9ab4bf1f9754167824e"
    const val HANDLE = "meshronai"
    const val NAME = "Meshron AI"
    /** Group messages that carry an AI answer start with this. */
    const val PREFIX = "meshron://ai/"

    private val TAG = Regex("(?i)@meshronai")
    fun tagged(text: String) = TAG.containsMatchIn(text)

    fun verify(ref: String, re: String, text: String, sigHex: String): Boolean = runCatching {
        val sig = sigHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val pub = ID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        sig.size == 64 && Ed25519.verify(pub, ("meshron-ai-v1\n$ref\n$re\n$text").toByteArray(), sig)
    }.getOrDefault(false)

    /** An answer in a group: the body we send, and reading it back (null when it doesn't check out). */
    data class Answer(val re: String, val text: String, val sig: String)

    fun body(a: Answer) = PREFIX + JSONObject().put("re", a.re).put("text", a.text).put("sig", a.sig).toString()

    fun parse(conversation: String, body: String): Answer? {
        if (!body.startsWith(PREFIX)) return null
        val o = runCatching { JSONObject(body.removePrefix(PREFIX)) }.getOrNull() ?: return null
        val a = Answer(o.optString("re"), o.optString("text"), o.optString("sig"))
        return if (verify(conversation, a.re, a.text, a.sig)) a else null
    }
}
