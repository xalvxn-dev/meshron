package com.xalvxnlab.meshron.messaging

import android.net.Uri
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.data.MessageDao
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.files.FileInfo
import com.xalvxnlab.meshron.files.FileService
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.network.NetworkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * What a story entry carries (the entry body). Same JSON as the iPhone/Windows app:
 * { sid, type: "text" | "photo" | "delete", text?, bg?, file?: FileInfo, exp }.
 */
data class StoryBody(val sid: String, val type: String, val text: String, val bg: Int, val file: FileInfo?) {
    companion object {
        private val SID = Regex("^[0-9a-f]{16,32}$")

        fun parse(json: String): StoryBody? = runCatching {
            val o = JSONObject(json)
            val sid = o.getString("sid")
            if (!SID.matches(sid)) return null
            StoryBody(
                sid = sid,
                type = o.optString("type", "text"),
                text = o.optString("text").take(700),
                bg = o.optInt("bg"),
                file = o.optJSONObject("file")?.let { FileInfo.parse(it.toString()) },
            )
        }.getOrNull()
    }
}

data class StoryItem(
    val sid: String,
    val isPhoto: Boolean,
    val text: String,
    val bg: Int,
    val sentAt: Long,
    /** The row holding it (for the photo file). */
    val peerKey: String,
    val rowId: String,
    val localPath: String?,
    val seen: Boolean,
    val viewers: List<String>,
)

data class FriendStories(val key: String, val items: List<StoryItem>, val allSeen: Boolean)

data class StoriesView(val mine: List<StoryItem> = emptyList(), val friends: List<FriendStories> = emptyList())

/**
 * Instagram-style 24-hour stories. A story is a "story" entry appended to each added contact's
 * 1:1 log, so it travels like any message (internet, relays, nearby, mesh) and stays
 * end-to-end encrypted. Viewers answer with a "seen" entry. Photo files are removed on expiry.
 */
class StoryService(
    private val dao: MessageDao,
    private val messaging: MessagingService,
    private val files: FileService,
    private val contacts: ContactsRepository,
    private val identity: IdentityRepository,
    private val network: NetworkService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = SecureRandom()

    init {
        // Someone newly added gets my stories that are still live (like following someone).
        scope.launch {
            var known: Set<String>? = null
            contacts.contacts.collect { list ->
                val added = list.filter { it.status == Contact.Status.ADDED }.map { it.keyHex }.toSet()
                val before = known
                known = added
                if (before != null) for (key in added - before) runCatching { shareLiveStoriesWith(key) }
            }
        }
        // Expired photos are deleted from the phone.
        scope.launch {
            while (true) {
                runCatching {
                    for (row in dao.expiredStoryFiles(System.currentTimeMillis() - TTL)) {
                        row.localPath?.let { p ->
                            // Several rows share my own photo; delete the file once.
                            runCatching { File(p).delete() }
                        }
                        dao.clearLocalPath(row.peerKey, row.id)
                    }
                }
                delay(10 * 60_000L)
            }
        }
    }

    /** Re-evaluated every minute so stories drop out when they turn 24 hours old. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val stories: Flow<StoriesView> = flow {
        while (true) {
            emit(System.currentTimeMillis() - TTL)
            delay(60_000)
        }
    }.flatMapLatest { since ->
        combine(dao.stories(since), contacts.contacts) { rows, list -> build(rows, list) }
    }

    private fun build(rows: List<MessageEntity>, list: List<Contact>): StoriesView {
        val added = list.filter { it.status == Contact.Status.ADDED }.map { it.keyHex }.toSet()
        val deleted = mutableSetOf<String>()
        val viewers = mutableMapOf<String, MutableSet<String>>()
        val mySeen = mutableSetOf<String>() // "<owner>|<sid>" I have viewed
        val mine = linkedMapOf<String, StoryItem>()
        val friends = linkedMapOf<String, MutableList<StoryItem>>()
        for (r in rows) {
            val body = StoryBody.parse(r.body) ?: continue
            if (r.kind == MessageEntity.KIND_SEEN) {
                if (r.outgoing) mySeen += r.peerKey + "|" + body.sid
                else viewers.getOrPut(body.sid) { mutableSetOf() } += r.peerKey
            }
        }
        for (r in rows) {
            if (r.kind != MessageEntity.KIND_STORY || r.isGroup) continue
            val body = StoryBody.parse(r.body) ?: continue
            if (body.type == "delete") {
                deleted += (if (r.outgoing) "me" else r.peerKey) + "|" + body.sid
                continue
            }
            val item = StoryItem(
                sid = body.sid, isPhoto = body.type == "photo", text = body.text, bg = body.bg, sentAt = r.sentAt,
                peerKey = r.peerKey, rowId = r.id, localPath = r.localPath,
                seen = (r.peerKey + "|" + body.sid) in mySeen, viewers = emptyList(),
            )
            if (r.outgoing) {
                val old = mine[body.sid]
                if (old == null || (old.localPath == null && item.localPath != null)) mine[body.sid] = item
            } else if (r.peerKey in added) {
                friends.getOrPut(r.peerKey) { mutableListOf() } += item
            }
        }
        val myList = mine.values.filter { "me|${it.sid}" !in deleted }
            .map { it.copy(viewers = viewers[it.sid]?.toList().orEmpty()) }
        val friendList = friends.mapNotNull { (key, items) ->
            val live = items.filter { "$key|${it.sid}" !in deleted }
            if (live.isEmpty()) null else FriendStories(key, live, live.all { it.seen })
        }.sortedWith(compareBy<FriendStories> { it.allSeen }.thenByDescending { f -> f.items.last().sentAt })
        return StoriesView(myList, friendList)
    }

    private fun newSid() = ByteArray(16).also(random::nextBytes).toHex()

    private suspend fun audience(): List<String> =
        contacts.contacts.first().filter { it.status == Contact.Status.ADDED }.map { it.keyHex }

    suspend fun postText(text: String, bg: Int): Result<Unit> = runCatching {
        val clean = text.trim().take(700)
        require(clean.isNotEmpty()) { "Write something first" }
        val people = audience()
        require(people.isNotEmpty()) { "Add a contact first. Stories are shown to your contacts." }
        val body = JSONObject().put("sid", newSid()).put("type", "text").put("text", clean).put("bg", bg)
            .put("exp", System.currentTimeMillis() + TTL).toString()
        for (key in people) messaging.sendKind(key, body, MessageEntity.KIND_STORY)
    }

    suspend fun postPhoto(uri: Uri, caption: String): Result<Unit> = runCatching {
        if (!network.isOnline) error("The network is still starting. Try again in a moment.")
        val people = audience()
        require(people.isNotEmpty()) { "Add a contact first. Stories are shown to your contacts." }
        val sid = newSid()
        val local = files.prepareStoryPhoto(uri, sid)
        try {
            for (key in people) {
                val info = files.uploadLocal(key, local, "story.jpg", "image/jpeg").getOrThrow()
                val body = JSONObject().put("sid", sid).put("type", "photo").put("text", caption.trim().take(300))
                    .put("file", JSONObject(info.toJson())).put("exp", System.currentTimeMillis() + TTL).toString()
                messaging.sendKind(key, body, MessageEntity.KIND_STORY, local.absolutePath)
            }
        } catch (t: Throwable) {
            local.delete()
            throw t
        }
    }

    /** Sends [key] each of my live stories they haven't been sent yet. */
    private suspend fun shareLiveStoriesWith(key: String) {
        val view = stories.first()
        val rows = dao.stories(System.currentTimeMillis() - TTL).first()
        val alreadyTo = rows.filter { it.outgoing && it.peerKey == key && it.kind == MessageEntity.KIND_STORY }
            .mapNotNull { StoryBody.parse(it.body)?.sid }.toSet()
        for (item in view.mine) {
            if (item.sid in alreadyTo) continue
            val row = rows.firstOrNull { it.outgoing && it.id == item.rowId && it.peerKey == item.peerKey } ?: continue
            val body = JSONObject(row.body)
            if (item.isPhoto) {
                val local = item.localPath?.let { File(it) }?.takeIf { it.exists() } ?: continue
                val info = files.uploadLocal(key, local, "story.jpg", "image/jpeg").getOrNull() ?: continue
                body.put("file", JSONObject(info.toJson()))
            }
            messaging.sendKind(key, body.toString(), MessageEntity.KIND_STORY, if (item.isPhoto) item.localPath else null)
        }
    }

    suspend fun markSeen(owner: String, sid: String) {
        messaging.sendKind(owner, JSONObject().put("sid", sid).toString(), MessageEntity.KIND_SEEN)
    }

    suspend fun delete(sid: String) {
        val body = JSONObject().put("sid", sid).put("type", "delete").toString()
        for (key in audience()) messaging.sendKind(key, body, MessageEntity.KIND_STORY)
    }

    suspend fun myKey(): String = identity.identity.first()?.publicKey?.toHex().orEmpty()

    companion object {
        const val TTL = 24 * 60 * 60 * 1000L
    }
}
