package com.xalvxnlab.meshron.moderation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.xalvxnlab.meshron.account.AccountService
import com.xalvxnlab.meshron.crypto.Ed25519
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.identity.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Talks to Meshron moderation (the account server): sends reports, learns what moderators hid,
 * reads the moderators' notices, and sends appeals. Requests are signed with this phone's
 * Meshron identity, so nobody can report or appeal in someone else's name.
 */
class ModerationService(private val context: Context, private val accounts: AccountService, private val identity: IdentityRepository) {

    data class Notice(val key: String, val ts: Long, val title: String, val text: String, val kind: String, val target: String, val action: String) {
        val canAppeal get() = action == "hide" || action == "ban"
    }

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()
    private val _banned = MutableStateFlow<String?>(null)
    /** Why my account is suspended (null if it isn't). */
    val banned: StateFlow<String?> = _banned.asStateFlow()

    init { current = this }

    // ---- Meshron AI's rule check (as things are posted) ----

    /** What Meshron AI found wrong with something just posted. */
    data class RuleBreak(val kind: String, val rule: String, val reason: String, val severity: Int)

    /** Thrown when Meshron AI stops a post; the app shows [warning] as a full warning. */
    class RuleBroken(val b: RuleBreak) : Exception(b.reason)

    private val _warning = MutableStateFlow<RuleBreak?>(null)
    /** The latest warning to show (null once it's been seen). */
    val warning: StateFlow<RuleBreak?> = _warning.asStateFlow()
    fun dismissWarning() { _warning.value = null }

    /**
     * Meshron AI checks [text] ([kind]: comment, group, caption, description, username, name,
     * bio). Null when it's fine, or when the check can't run (no internet, AI busy): the check
     * never stops anyone posting by itself failing.
     */
    suspend fun check(kind: String, text: String, adult: Boolean = false): RuleBreak? {
        if (text.isBlank()) return null
        val o = runCatching {
            kotlinx.coroutines.withTimeout(8_000) {
                signed("/v1/mod/check", JSONObject().put("kind", kind).put("text", text.take(2000)).put("adult", adult)
                    .put("lang", java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)))
            }
        }.getOrNull() ?: return null
        if (o.optBoolean("ok", true)) return null
        val b = RuleBreak(kind, o.optString("rule").ifBlank { "Community Guidelines" }, o.optString("reason").ifBlank { "This breaks Meshron's Community Guidelines." }, o.optInt("severity", 2))
        _warning.value = b
        runCatching { refresh() } // the warning is in Activity too
        return b
    }

    /** Checks [text] and stops the post (throws [RuleBroken]) when it breaks a rule. */
    suspend fun guard(kind: String, text: String, adult: Boolean = false) {
        check(kind, text, adult)?.let { throw RuleBroken(it) }
    }

    companion object {
        /** The running service, for the places that check posts (set once at start). */
        @Volatile var current: ModerationService? = null
            private set
        /** Same as [guard], when moderation is running. */
        suspend fun guardText(kind: String, text: String, adult: Boolean = false) { current?.guard(kind, text, adult) }
    }

    private suspend fun myId() = identity.identity.filterNotNull().first().publicKey.toHex()

    private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).toHex()

    private suspend fun signed(path: String, payload: JSONObject): JSONObject {
        val p = payload.toString()
        val ts = System.currentTimeMillis()
        val msg = "meshron-mod-v1\n$path\n$ts\n${sha256Hex(p)}"
        val sig = identity.withSeed { Ed25519.sign(it, msg.toByteArray()) }
        return accounts.postRaw(path, JSONObject().put("id", myId()).put("ts", ts).put("sig", sig.toHex()).put("p", p))
    }

    // ---- Reports ----

    private val uploading = Mutex()

    /** Sends saved reports (with a smaller copy of the screenshot). Safe to call any time. */
    suspend fun uploadPending() = uploading.withLock {
        if (Mode.anon) return@withLock // anonymous mode never talks to the account server
        val dir = File(context.filesDir, Mode.name("reports"))
        for (r in Reports.pending(context)) {
            val shotName = r.optString("screenshot")
            val shot = if (shotName.isEmpty()) "" else withContext(Dispatchers.Default) { smallJpeg(File(dir, shotName)) }
            val res = runCatching {
                signed("/v1/report", JSONObject().put("kind", r.optString("kind")).put("target", r.optString("target"))
                    .put("owner", r.optString("owner")).put("name", r.optString("name")).put("reason", r.optString("reason"))
                    .put("details", r.optString("details")).put("page", r.optString("page")).put("content", r.optString("content"))
                    .put("screenshot", shot))
            }.getOrElse { return@withLock } // offline: try again later
            r.put("sent", true).put("serverId", res.optString("id")).put("action", res.optString("action"))
            File(dir, r.optString("id") + ".json").writeText(r.toString())
            if (shotName.isNotEmpty()) File(dir, shotName).delete()
        }
        runCatching { refresh() }
    }

    private fun smallJpeg(f: File): String = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 1400) sample *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ""
        val out = ByteArrayOutputStream()
        var q = 75
        do { out.reset(); b.compress(Bitmap.CompressFormat.JPEG, q, out); q -= 15 } while (out.size() > 600_000 && q > 20)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")

    // ---- What moderators hid ----

    private class Entry(val hidden: Boolean, val at: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    /** Which of [keys] ("reel:ch:id", "comment:ch:id:cid") are hidden. Cached for 10 minutes. */
    suspend fun hiddenAmong(keys: List<String>): Set<String> {
        if (keys.isEmpty() || Mode.anon) return emptySet()
        val now = System.currentTimeMillis()
        val unknown = keys.distinct().filter { (cache[it]?.at ?: 0) < now - 10 * 60_000 }
        for (chunk in unknown.chunked(300)) {
            val res = runCatching { accounts.postRaw("/v1/mod/status", JSONObject().put("items", JSONArray(chunk))) }.getOrNull() ?: break
            val hid = res.optJSONArray("hidden")?.let { a -> List(a.length()) { a.getJSONObject(it).optString("key") } }.orEmpty().toSet()
            for (k in chunk) cache[k] = Entry(k in hid, now)
        }
        return keys.filter { cache[it]?.hidden == true }.toSet()
    }

    // ---- Notices and appeals ----

    /** Fetches my notices from moderation (and whether I'm suspended). */
    suspend fun refresh() {
        if (Mode.anon) return
        val res = signed("/v1/mod/inbox", JSONObject())
        val a = res.optJSONArray("notices") ?: JSONArray()
        _notices.value = List(a.length()) {
            val o = a.getJSONObject(it)
            Notice(o.optString("key"), o.optLong("ts"), o.optString("title"), o.optString("text"), o.optString("kind"), o.optString("target"), o.optString("action"))
        }
        _banned.value = res.optJSONObject("banned")?.optString("reason")?.ifEmpty { "Your account broke the rules." }
    }

    suspend fun appeal(kind: String, target: String, text: String) {
        signed("/v1/mod/appeal", JSONObject().put("kind", kind).put("target", target).put("text", text.take(1500)))
        cache.remove("$kind:$target")
        runCatching { refresh() }
    }

    // ---- Badges ----

    /** verified ✓, moderator, and the founders' titles ("founder", "cofounder"). */
    data class Badge(val verified: Boolean, val mod: Boolean, val title: String?)

    // Meshron AI's own account always shows its badges (verified, moderator, AI).
    private val _badges = MutableStateFlow<Map<String, Badge?>>(mapOf(com.xalvxnlab.meshron.account.MeshronAi.ID to Badge(true, true, "ai")))
    val badges: StateFlow<Map<String, Badge?>> = _badges.asStateFlow()
    private val badgeAt = ConcurrentHashMap<String, Long>()
    private val badgeLock = Mutex()

    /** Makes sure badges for [ids] are known (looked up at most every 30 minutes). */
    suspend fun want(ids: Collection<String>) {
        if (Mode.anon) return
        val now = System.currentTimeMillis()
        val need = ids.filter { it.length == 64 && it != com.xalvxnlab.meshron.account.MeshronAi.ID && (badgeAt[it] ?: 0) < now - 30 * 60_000 }.distinct()
        if (need.isEmpty()) return
        need.forEach { badgeAt[it] = now }
        badgeLock.withLock {
            for (chunk in need.chunked(200)) {
                // Offline or still connecting: forget we asked, so the next screen asks again.
                val res = runCatching { accounts.postRaw("/v1/badges", JSONObject().put("people", JSONArray(chunk))) }.getOrNull()
                    ?: run { chunk.forEach { badgeAt.remove(it) }; null } ?: continue
                val o = res.optJSONObject("badges") ?: JSONObject()
                _badges.value = _badges.value + chunk.associateWith { id ->
                    o.optJSONObject(id)?.let { b -> Badge(b.optBoolean("verified"), b.optBoolean("mod"), b.optString("title").takeIf { it == "founder" || it == "cofounder" || it == "ai" }) }
                }
            }
        }
    }

    /** Ask Meshron for the verified check. */
    suspend fun requestVerification(name: String, username: String, category: String, reason: String, links: String) {
        signed("/v1/verify/request", JSONObject().put("name", name).put("username", username).put("category", category).put("reason", reason).put("links", links))
        runCatching { refresh() }
    }

    // ---- Moderators' own tools (signed-in moderator accounts) ----

    private val _role = MutableStateFlow<String?>(null)
    /** "admin" or "mod" when this account moderates Meshron. */
    val role: StateFlow<String?> = _role.asStateFlow()

    suspend fun checkRole() {
        if (Mode.anon) return
        _role.value = runCatching { accounts.postAsMe("/v1/mod/me", JSONObject()).optString("role").takeIf { it == "admin" || it == "mod" } }.getOrNull()
    }

    /** Everything waiting for moderators (and, for the CEOs, applications and the team). */
    suspend fun queue(): JSONObject = accounts.postAsMe("/v1/mod/queue", JSONObject())

    /** Apply to become a Meshron moderator; the CEOs decide. */
    suspend fun applyToModerate(name: String, why: String, hours: String, languages: String) {
        signed("/v1/mod/apply", JSONObject().put("name", name).put("why", why).put("hours", hours).put("languages", languages))
        runCatching { refresh() }
    }

    /** A moderator action (remove, restore, warn, ban…), same as the web panel. */
    suspend fun act(action: String, extra: JSONObject = JSONObject()) {
        accounts.postAsMe("/v1/mod/act", extra.put("action", action))
        cache.clear()
    }
}
