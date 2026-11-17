package com.xalvxnlab.meshron.reels

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.identity.Mode
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.PeerEvent
import com.xalvxnlab.meshron.profile.ProfileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ReelSound(val ch: String, val id: String, val title: String) {
    fun json(): JSONObject = JSONObject().put("ch", ch).put("id", id).put("title", title)
}

data class ReelAuthor(
    val ch: String, val id: String, val name: String, val handle: String, val photo: String,
    val following: Boolean, val mine: Boolean, val followers: Int = 0, val reels: Int = 0,
    /** Who may comment on their reels: "everyone", "followers", "friends" or "off". */
    val cm: String = "everyone",
    /** They follow me back (with [following]: we're friends). */
    val followsMe: Boolean = false,
) {
    val friend get() = following && followsMe
    val label get() = if (handle.isNotEmpty()) "@$handle" else name
}

data class Reel(
    val ch: String, val id: String, val ts: Long, val cap: String, val dur: Int, val w: Int, val h: Int,
    val thumb: String, val size: Long, val sound: ReelSound, val original: Boolean, val author: ReelAuthor,
    val likes: Int, val liked: Boolean, val comments: Int, val views: Int, val faved: Boolean, val mine: Boolean,
    /** Only I can see it (kept encrypted on this phone, not shared). */
    val private: Boolean = false,
    val reposts: Int = 0,
    val reposted: Boolean = false,
    /** Confirmed collaborators. */
    val collabs: List<ReelAuthor> = emptyList(),
    /** In Following / Reposts: who reposted it, and the comment they quoted. */
    val reposter: ReelAuthor? = null,
    val quote: String = "",
    /** Removed by Meshron moderators (only its owner still sees it, marked). */
    val removed: Boolean = false,
) {
    val key get() = "$ch:$id"
}

data class ReelComment(
    val cid: String, val by: String, val name: String, val text: String, val ts: Long,
    val mine: Boolean, val sent: Boolean, val canDelete: Boolean, val photo: String,
    /** The comment this one replies to (null for top-level comments). */
    val re: String? = null,
)

data class ReelProfile(val author: ReelAuthor, val bio: String, val followers: Int, val following: Int, val likes: Int, val reels: List<Reel>, val link: String = "")

/**
 * Reels on Android: the worklet's reels.js does the peer-to-peer work; this sends it
 * commands, waits for the answers, and keeps downloaded videos in a small disk cache.
 */
class ReelsService(
    private val context: Context,
    private val net: NetworkService,
    private val identity: IdentityRepository,
    private val profiles: ProfileStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val waits = ConcurrentHashMap<String, Wait>()
    private val seq = AtomicLong()
    private val _ready = MutableStateFlow<String?>(null)
    /** My channel key once Reels is running (never in anonymous mode). */
    val ready: StateFlow<String?> = _ready
    private val _changed = MutableSharedFlow<List<String>>(extraBufferCapacity = 32)
    /** Channels with new likes, comments or reels. */
    val changed: SharedFlow<List<String>> = _changed
    private val cacheDir = File(context.cacheDir, "reels").apply { mkdirs() }
    private val loads = ConcurrentHashMap<String, CompletableDeferred<File>>()
    private val loadLock = Mutex()

    /** Someone in the same rough area (see [Suggestions]). */
    @Volatile var onNearPerson: ((JSONObject) -> Unit)? = null

    private class Wait(val done: CompletableDeferred<JSONObject>, val out: FileOutputStream? = null)

    init {
        net.onReelsEvent = ::onEvent
        if (!Mode.anon) scope.launch {
            net.events.collect { if (it is PeerEvent.Ready) start() }
        }
        // My name, @username, bio and photo appear on my public channel too.
        if (!Mode.anon) scope.launch {
            combine(identity.identity.filterNotNull(), profiles.me) { i, p -> Triple(i.username, i.handle, listOf(p.bio, p.pv, p.link)) }
                .distinctUntilChanged()
                .collect { if (_ready.value != null) net.reelsSend(meJson().put("cmd", "reels-me")) }
        }
    }

    private fun onEvent(e: JSONObject) {
        val req = e.optString("req")
        val ev = e.optString("ev")
        if (ev == "reels-near-person") { onNearPerson?.invoke(e); return }
        if (ev == "reels-changed") {
            val arr = e.optJSONArray("chans") ?: JSONArray()
            _changed.tryEmit(List(arr.length()) { arr.getString(it) })
            return
        }
        val w = waits[req] ?: return
        when (ev) {
            "reel-data" -> runCatching { w.out?.write(Base64.decode(e.getString("data"), Base64.NO_WRAP)) }
            "reel-error" -> { waits.remove(req); w.done.completeExceptionally(Exception(e.optString("msg", "Reels failed"))) }
            else -> { waits.remove(req); w.done.complete(e) }
        }
    }

    private suspend fun call(cmd: JSONObject, timeoutMs: Long = 30_000, req: String = "a" + seq.incrementAndGet(), out: FileOutputStream? = null): JSONObject {
        val done = CompletableDeferred<JSONObject>()
        waits[req] = Wait(done, out)
        try {
            net.reelsSend(cmd.put("req", req))
            return withTimeout(timeoutMs) { done.await() }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            throw Exception("Reels took too long. Check your connection.")
        } finally {
            waits.remove(req)
        }
    }

    private suspend fun meJson(): JSONObject {
        val i = identity.identity.filterNotNull().first()
        val p = profiles.myProfileJson()
        val photo = p.optString("photo").takeIf { it.length <= 60 * 1024 } ?: ""
        return JSONObject().put("name", i.username).put("handle", i.handle).put("bio", p.optString("bio")).put("photo", photo).put("link", p.optString("link"))
    }

    private fun start() = scope.launch {
        if (_ready.value != null || Mode.anon) return@launch
        runCatching {
            val r = call(meJson().put("cmd", "reels-start"), 60_000)
            _ready.value = r.optString("ch")
        }
    }

    private fun need() {
        if (Mode.anon) throw Exception("Reels are off while you are anonymous")
        if (_ready.value == null) throw Exception("Reels are starting. Try again in a moment.")
    }

    // ---- Reading -----------------------------------------------------------------------

    suspend fun feed(kind: String, ch: String? = null, offset: Int = 0, limit: Int = 10, sound: ReelSound? = null): Pair<List<Reel>, Boolean> {
        need()
        val c = JSONObject().put("cmd", "reels-feed").put("kind", kind).put("offset", offset).put("limit", limit)
        if (ch != null) c.put("ch", ch)
        if (sound != null) c.put("sound", sound.json())
        val r = call(c)
        val arr = r.optJSONArray("items") ?: JSONArray()
        return moderated(List(arr.length()) { reel(arr.getJSONObject(it)) }) to r.optBoolean("more")
    }

    /** Set by the app: which of these keys ("reel:ch:id", "comment:ch:id:cid") moderators hid. */
    @Volatile var hiddenKeys: (suspend (List<String>) -> Set<String>)? = null

    /** Drops reels moderators removed; my own stay, marked [Reel.removed]. */
    private suspend fun moderated(list: List<Reel>): List<Reel> {
        val f = hiddenKeys ?: return list
        val hidden = runCatching { f(list.map { "reel:" + it.key }) }.getOrDefault(emptySet())
        if (hidden.isEmpty()) return list
        return list.mapNotNull { r -> if ("reel:" + r.key !in hidden) r else if (r.mine) r.copy(removed = true) else null }
    }

    /** The Reels channel of someone (by their Meshron ID), or null when it isn't known yet. */
    suspend fun channelOf(id: String): String? {
        need()
        return call(JSONObject().put("cmd", "reels-ch-of").put("id", id)).optString("ch").takeIf { it.length == 64 }
    }

    suspend fun profile(ch: String): ReelProfile? {
        need()
        val p = call(JSONObject().put("cmd", "reels-profile").put("ch", ch), 20_000).optJSONObject("profile") ?: return null
        val arr = p.optJSONArray("reels") ?: JSONArray()
        return ReelProfile(author(p), p.optString("bio"), p.optInt("followers"), p.optInt("followingCount"), p.optInt("likes"), moderated(List(arr.length()) { reel(arr.getJSONObject(it)) }), p.optString("link"))
    }

    data class SoundUse(val sound: ReelSound, val uses: Int, val thumb: String)

    /** Sounds people have posted reels with (most used first). */
    /** Someone live right now (followed people first). [lid] identifies the stream. */
    data class LiveNow(val author: ReelAuthor, val lid: String, val title: String, val ts: Long, val screen: Boolean = false)

    suspend fun lives(): List<LiveNow> {
        need()
        val arr = call(JSONObject().put("cmd", "reels-lives")).optJSONArray("list") ?: JSONArray()
        return List(arr.length()) { val o = arr.getJSONObject(it); LiveNow(author(o), o.optString("lid"), o.optString("title"), o.optLong("ts"), o.optBoolean("screen")) }
    }

    data class People(val list: List<ReelAuthor>, val following: Int, val followers: Int, val friends: Int)

    /** "following", "followers" or "friends" (people who follow each other). */
    suspend fun people(kind: String): People {
        need()
        val r = call(JSONObject().put("cmd", "reels-people").put("kind", kind))
        val arr = r.optJSONArray("list") ?: JSONArray()
        val c = r.optJSONObject("counts") ?: JSONObject()
        return People(List(arr.length()) { author(arr.getJSONObject(it)) }, c.optInt("following"), c.optInt("followers"), c.optInt("friends"))
    }

    /** Stops following everyone who doesn't follow back. Returns how many. */
    suspend fun unfollowNonFollowers(): Int {
        val drop = people("following").list.filter { !it.followsMe && it.ch.isNotEmpty() }
        for (a in drop) runCatching { follow(a.ch, false) }
        return drop.size
    }

    /** Who may comment on my reels. */
    suspend fun setCommentsFrom(v: String) = act(JSONObject().put("cmd", "reel-cmfrom").put("v", v))

    /** Marks me live (or not) on my channel, so followers see it. */
    suspend fun setLive(on: Boolean, id: String, title: String, screen: Boolean = false) =
        act(JSONObject().put("cmd", "reel-live").put("on", on).put("id", id).put("title", title).put("screen", screen))

    suspend fun sounds(q: String = ""): List<SoundUse> {
        need()
        val arr = call(JSONObject().put("cmd", "reels-sounds").put("q", q)).optJSONArray("list") ?: JSONArray()
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            val s = o.getJSONObject("sound")
            SoundUse(ReelSound(s.optString("ch"), s.optString("id"), s.optString("title", "Original sound")), o.optInt("uses"), o.optString("thumb"))
        }
    }

    /** Videos whose caption, #tags, sound or creator match [q]. */
    suspend fun searchVideos(q: String): List<Reel> {
        need()
        val arr = call(JSONObject().put("cmd", "reels-videos").put("q", q.take(60))).optJSONArray("items") ?: JSONArray()
        return List(arr.length()) { reel(arr.getJSONObject(it)) }
    }

    suspend fun search(q: String): List<ReelAuthor> {
        need()
        val arr = call(JSONObject().put("cmd", "reels-search").put("q", q.take(40)), 30_000).optJSONArray("users") ?: JSONArray()
        return List(arr.length()) { author(arr.getJSONObject(it)) }
    }

    suspend fun comments(ch: String, id: String): List<ReelComment> {
        need()
        val arr = call(JSONObject().put("cmd", "reel-comments").put("ch", ch).put("id", id)).optJSONArray("list") ?: JSONArray()
        val all = List(arr.length()) {
            val o = arr.getJSONObject(it)
            ReelComment(o.optString("cid"), o.optString("by"), o.optString("name"), o.optString("text"), o.optLong("ts"),
                o.optBoolean("mine"), o.optBoolean("sent"), o.optBoolean("canDelete"), o.optString("photo"),
                o.optString("re").takeIf { it.isNotEmpty() && it != "null" })
        }
        // Comments moderators removed disappear for everyone except their author.
        val f = hiddenKeys ?: return all
        val hidden = runCatching { f(all.map { "comment:$ch:$id:${it.cid}" }) }.getOrDefault(emptySet())
        return all.filter { "comment:$ch:$id:${it.cid}" !in hidden || it.mine }
    }

    // ---- Doing -------------------------------------------------------------------------

    private suspend fun act(c: JSONObject) { need(); call(c) }
    suspend fun like(ch: String, id: String, on: Boolean) = act(JSONObject().put("cmd", "reel-like").put("ch", ch).put("id", id).put("on", on))
    suspend fun fav(ch: String, id: String, on: Boolean) = act(JSONObject().put("cmd", "reel-fav").put("ch", ch).put("id", id).put("on", on))
    suspend fun follow(ch: String, on: Boolean) = act(JSONObject().put("cmd", "reel-follow").put("ch", ch).put("on", on))
    suspend fun comment(ch: String, id: String, text: String, re: String? = null): String {
        need()
        return call(JSONObject().put("cmd", "reel-comment").put("ch", ch).put("id", id).put("text", text).apply { if (re != null) put("re", re) }).optString("cid")
    }

    /** Posts Meshron AI's signed answer under a comment. */
    suspend fun aiComment(ch: String, id: String, a: com.xalvxnlab.meshron.account.MeshronAi.Answer) =
        act(JSONObject().put("cmd", "reel-comment").put("ch", ch).put("id", id).put("text", a.text).put("re", a.re).put("ai", a.sig))
    suspend fun uncomment(ch: String, cid: String) = act(JSONObject().put("cmd", "reel-uncomment").put("ch", ch).put("cid", cid))
    /** Repost (or undo); [quote] is a comment you're sharing with it. */
    suspend fun repost(r: Reel, on: Boolean, quote: String = "", cid: String? = null) =
        act(JSONObject().put("cmd", "reel-repost").put("ch", r.ch).put("id", r.id).put("on", on).put("quote", quote).apply { if (cid != null) put("cid", cid) })

    /** Invite a friend (identity key) to be a collaborator on one of my reels. */
    suspend fun invite(id: String, who: String) = act(JSONObject().put("cmd", "reel-invite").put("id", id).put("who", who))

    /** Accept a collaboration invite. */
    suspend fun collabAccept(ch: String, id: String) = act(JSONObject().put("cmd", "reel-collab-accept").put("ch", ch).put("id", id))

    /** Feed settings: how For You ranks (0..10 each) and muted words. */
    data class FeedPrefs(val weights: Map<String, Int>, val words: List<String>, val hidden: List<ReelAuthor>)

    suspend fun prefs(): FeedPrefs {
        need()
        val o = call(JSONObject().put("cmd", "reels-prefs"))
        val w = o.optJSONObject("weights") ?: JSONObject()
        val words = o.optJSONArray("words") ?: JSONArray()
        val hidden = o.optJSONArray("hidden") ?: JSONArray()
        return FeedPrefs(
            w.keys().asSequence().associateWith { w.optInt(it, 5) },
            List(words.length()) { words.getString(it) },
            List(hidden.length()) { author(hidden.getJSONObject(it)) },
        )
    }

    suspend fun setPrefs(weights: Map<String, Int>, words: List<String>) {
        val w = JSONObject(); weights.forEach { (k, v) -> w.put(k, v) }
        act(JSONObject().put("cmd", "reels-prefs-set").put("weights", w).put("words", JSONArray(words)))
    }

    /** Show fewer like this (and never this one again). */
    suspend fun notInterested(r: Reel) = act(JSONObject().put("cmd", "reel-notint").put("ch", r.ch).put("id", r.id))

    /** Hide everything from a creator (also unfollows). */
    suspend fun hideCreator(ch: String, on: Boolean) = act(JSONObject().put("cmd", "reel-hidech").put("ch", ch).put("on", on))

    /** Reported videos disappear for you right away; the report goes to Meshron's moderators. */
    @Volatile var onReport: (suspend (Reel, String) -> Unit)? = null
    suspend fun report(r: Reel, reason: String) {
        notInterested(r)
        onReport?.invoke(r, reason)
    }

    /** Private: off the public channel, only on this phone. Public again: posted anew (likes start over). */
    suspend fun setPrivate(id: String, on: Boolean) = act(JSONObject().put("cmd", "reel-private").put("id", id).put("on", on))

    suspend fun delete(id: String) {
        act(JSONObject().put("cmd", "reel-delete").put("id", id))
        _ready.value?.let { fileOf(it, id).delete() }
    }

    private fun fileOf(ch: String, id: String) = File(cacheDir, ch.take(16) + "-" + id + ".mp4")

    /** The video on disk, downloaded from peers first if needed. */
    suspend fun load(ch: String, id: String): File {
        need()
        val file = fileOf(ch, id)
        if (file.exists() && file.length() > 0) { file.setLastModified(System.currentTimeMillis()); return file }
        val (job, mine) = loadLock.withLock {
            loads[file.path]?.let { it to false } ?: CompletableDeferred<File>().also { loads[file.path] = it } to true
        }
        if (!mine) return job.await()
        try {
            val part = File(file.path + ".part")
            FileOutputStream(part).use { out ->
                call(JSONObject().put("cmd", "reel-get").put("ch", ch).put("id", id), 240_000, out = out)
            }
            part.renameTo(file)
            trimCache()
            job.complete(file)
            return file
        } catch (t: Throwable) {
            job.completeExceptionally(t)
            throw t
        } finally {
            loads.remove(file.path)
        }
    }

    /** Keeps the video cache under 300 MB (oldest out first). */
    private fun trimCache() {
        val files = cacheDir.listFiles()?.filter { it.name.endsWith(".mp4") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= 300L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    /** Shares a finished video. Returns its id. */
    suspend fun post(video: File, caption: String, durSec: Int, w: Int, h: Int, thumbB64: String, sound: ReelSound?, onProgress: (Float) -> Unit, title: String = ""): String {
        need()
        val xfer = "x" + seq.incrementAndGet()
        net.reelsSend(JSONObject().put("cmd", "reel-begin").put("xfer", xfer))
        val total = video.length().coerceAtLeast(1)
        var sent = 0L
        video.inputStream().use { input ->
            val buf = ByteArray(384 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                net.reelsSend(JSONObject().put("cmd", "reel-chunk").put("xfer", xfer).put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
                sent += n
                onProgress(sent.toFloat() / total)
            }
        }
        val end = JSONObject().put("cmd", "reel-end").put("xfer", xfer).put("cap", caption).put("dur", durSec).put("w", w).put("h", h).put("thumb", thumbB64)
        if (sound != null) end.put("sound", sound.json())
        if (title.isNotBlank()) end.put("title", title.take(60))
        // reel-end is answered with the transfer id as its request id.
        val done = CompletableDeferred<JSONObject>()
        waits[xfer] = Wait(done)
        try {
            net.reelsSend(end)
            return withTimeout(120_000) { done.await() }.optString("id")
        } finally {
            waits.remove(xfer)
        }
    }

    // ---- Parsing + thumbnails --------------------------------------------------------------

    private fun author(o: JSONObject) = ReelAuthor(
        o.optString("ch"), o.optString("id"), o.optString("name", "Someone"), o.optString("handle"), o.optString("photo"),
        o.optBoolean("following"), o.optBoolean("mine"), o.optInt("followers"), o.optInt("reels"), o.optString("cm", "everyone"), o.optBoolean("followsMe"),
    )

    private fun reel(o: JSONObject): Reel {
        val s = o.optJSONObject("sound") ?: JSONObject()
        return Reel(
            o.optString("ch"), o.optString("id"), o.optLong("ts"), o.optString("cap"), o.optInt("dur"), o.optInt("w"), o.optInt("h"),
            o.optString("thumb"), o.optLong("size"), ReelSound(s.optString("ch"), s.optString("id"), s.optString("title", "Original sound")),
            o.optBoolean("original"), author(o.optJSONObject("author") ?: JSONObject()),
            o.optInt("likes"), o.optBoolean("liked"), o.optInt("comments"), o.optInt("views"), o.optBoolean("faved"), o.optBoolean("mine"),
            o.optBoolean("private"),
            o.optInt("reposts"), o.optBoolean("reposted"),
            (o.optJSONArray("collabs") ?: JSONArray()).let { a -> List(a.length()) { author(a.getJSONObject(it)) } },
            o.optJSONObject("reposter")?.let { author(it) }, o.optString("quote"),
        )
    }

    private val images = LruCache<String, ImageBitmap>(120)

    /** Decodes a base64 JPEG (thumbnail or profile photo), cached. */
    fun image(b64: String): ImageBitmap? {
        if (b64.isEmpty()) return null
        val key = b64.length.toString() + b64.takeLast(32)
        images.get(key)?.let { return it }
        return runCatching {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()?.also { images.put(key, it) }
    }

    /** A link that opens Add contact for someone met in Reels. */
    fun contactLink(a: ReelAuthor): String? {
        if (a.id.length != 64) return null
        val bytes = ByteArray(32) { a.id.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return "meshron://id/" + com.xalvxnlab.meshron.crypto.ZBase32.encode(bytes) + "?name=" + java.net.URLEncoder.encode(a.name, "UTF-8")
    }
}
