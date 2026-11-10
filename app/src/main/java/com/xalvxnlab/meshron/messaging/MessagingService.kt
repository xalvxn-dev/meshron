package com.xalvxnlab.meshron.messaging

import android.util.Log
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.data.GroupEntity
import com.xalvxnlab.meshron.data.GroupLog
import com.xalvxnlab.meshron.data.MessageDao
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.data.ReadMark
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.PeerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1:1 and group messaging on top of Hypercore logs (see app.js).
 *
 * 1:1: save to Room (Waiting) → append to my outbox log for that peer (Sent) → peer
 * replicates, stores and acks its position (Delivered).
 *
 * Groups: every member appends to their own log for the group; members replicate each
 * other's logs (also via third members). Invites travel as signed "invite" entries in the
 * 1:1 logs, so they reach people who are offline. Room is this device's index of all logs.
 */
class MessagingService(
    private val dao: MessageDao,
    private val network: NetworkService,
    private val contacts: ContactsRepository,
    private val identity: IdentityRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val random = SecureRandom()
    private val appendLock = Mutex()

    /** Called after a new incoming file message is stored (FileService downloads it). */
    var onFileMessage: (suspend (MessageEntity) -> Unit)? = null

    /** Called after any new incoming message is stored (used for notifications). */
    var onIncoming: (suspend (MessageEntity) -> Unit)? = null

    val summaries = dao.summaries()
    val groups = dao.groups()
    fun conversation(key: String) = dao.conversation(key)
    fun groupAcks(groupId: String) = dao.groupAcks(groupId)

    /** Subscribes to network events, then starts the network. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        network.inboxCursors = {
            val marks = dao.readMarks().filter { it.conversation == it.sender }.associate { it.sender to it.next }
            val db = dao.inboxCursors().associate { it.peerKey to it.next }
            (marks.keys + db.keys).associateWith { maxOf(marks[it] ?: 0L, db[it] ?: 0L) }
        }
        network.isGroupMember = { key -> dao.allGroups().any { key in it.memberList } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            network.events.collect { e -> runCatching { handle(e) }.onFailure { Log.e(TAG, "event $e", it) } }
        }
        network.start()
    }

    private suspend fun myKey(): String = identity.identity.filterNotNull().first().publicKey.toHex()
    private fun newId() = ByteArray(16).also(random::nextBytes).toHex()

    /** Sends text to a 1:1 chat (hex key) or a group ("g:<id>"). */
    suspend fun send(conversation: String, text: String) {
        val body = text.trim().take(MAX_BODY)
        if (body.isEmpty()) return
        val now = System.currentTimeMillis()
        val wireId = newId()
        val me = myKey()
        val group = conversation.startsWith(MessageEntity.GROUP_PREFIX)
        val msg = MessageEntity(
            peerKey = conversation,
            id = if (group) MessageEntity.groupRowId(me, wireId) else wireId,
            outgoing = true,
            body = body,
            sentAt = now,
            localTime = now,
            status = MessageEntity.PENDING,
            signature = null,
            read = true,
            senderKey = if (group) me else null,
        )
        dao.insert(msg)
        if (network.isOnline) appendLock.withLock { appendRow(msg) }
    }

    /**
     * Creates a group with me plus [memberKeys] (all contacts), then invites each member
     * through their 1:1 log.
     */
    suspend fun createGroup(name: String, memberKeys: List<String>): GroupEntity {
        val me = myKey()
        val group = GroupEntity(
            id = newId(),
            name = name.trim().take(48),
            secret = ByteArray(32).also(random::nextBytes).toHex(),
            creatorKey = me,
            members = (listOf(me) + memberKeys).distinct().joinToString(","),
            createdAt = System.currentTimeMillis(),
        )
        dao.insertGroup(group)
        if (network.isOnline) openGroup(group)
        val invite = JSONObject()
            .put("id", group.id).put("name", group.name).put("secret", group.secret)
            .put("creator", group.creatorKey).put("members", JSONArray(group.memberList))
            .toString()
        for (member in memberKeys) {
            val now = System.currentTimeMillis()
            val row = MessageEntity(
                peerKey = member, id = newId(), outgoing = true, body = invite, sentAt = now, localTime = now,
                status = MessageEntity.PENDING, signature = null, read = true, kind = MessageEntity.KIND_INVITE,
            )
            dao.insert(row)
            if (network.isOnline) appendLock.withLock { appendRow(row) }
        }
        return group
    }

    /** Sends a file message whose blob is already stored (see FileService). */
    suspend fun sendFile(conversation: String, body: String, localPath: String) {
        val now = System.currentTimeMillis()
        val wireId = newId()
        val me = myKey()
        val group = conversation.startsWith(MessageEntity.GROUP_PREFIX)
        val msg = MessageEntity(
            peerKey = conversation,
            id = if (group) MessageEntity.groupRowId(me, wireId) else wireId,
            outgoing = true, body = body, sentAt = now, localTime = now,
            status = MessageEntity.PENDING, signature = null, read = true,
            senderKey = if (group) me else null, kind = MessageEntity.KIND_FILE, localPath = localPath,
        )
        dao.insert(msg)
        if (network.isOnline) appendLock.withLock { appendRow(msg) }
    }

    suspend fun markRead(conversation: String) = dao.markRead(conversation)

    /** Remembers how far [conversation] was read, so deleting rows doesn't make them come back. */
    private suspend fun keepReadPosition(conversation: String) {
        val cursors = if (conversation.startsWith(MessageEntity.GROUP_PREFIX)) dao.groupCursors(conversation)
        else dao.inboxCursors().filter { it.peerKey == conversation }
        val marks = dao.readMarks().filter { it.conversation == conversation }.associate { it.sender to it.next }
        for (c in cursors) dao.putReadMark(ReadMark(conversation, c.peerKey, maxOf(c.next, marks[c.peerKey] ?: 0L)))
    }

    /** Deletes every message (and downloaded file) of a chat on this phone only. */
    suspend fun deleteConversation(conversation: String) {
        keepReadPosition(conversation)
        dao.localFiles(conversation).forEach { runCatching { java.io.File(it).delete() } }
        dao.deleteConversation(conversation)
    }

    /** Deletes one message on this phone only. */
    suspend fun deleteMessage(m: MessageEntity) {
        keepReadPosition(m.peerKey)
        m.localPath?.let { runCatching { java.io.File(it).delete() } }
        dao.deleteMessage(m.peerKey, m.id, m.outgoing)
    }

    private suspend fun appendRow(m: MessageEntity) {
        if (m.isGroup) {
            network.groupAppend(m.peerKey.removePrefix(MessageEntity.GROUP_PREFIX), m.wireId, m.body, m.sentAt, m.kind)
        } else {
            network.append(m.peerKey, m.id, m.body, m.sentAt, m.kind)
        }
    }

    private suspend fun openGroup(g: GroupEntity) {
        val marks = dao.readMarks().filter { it.conversation == g.conversationKey }.associate { it.sender to it.next }
        val db = dao.groupCursors(g.conversationKey).associate { it.peerKey to it.next }
        val cursors = (marks.keys + db.keys).associateWith { maxOf(marks[it] ?: 0L, db[it] ?: 0L) }
        val logs = dao.groupLogs(g.id).associate { it.member to (it.outbox to it.sig) }
        network.groupOpen(g.id, g.secret, g.memberList, cursors, logs)
    }

    private suspend fun handle(e: PeerEvent) {
        when (e) {
            PeerEvent.Ready -> appendLock.withLock {
                // Groups first, so queued group messages have a log to go into.
                for (g in dao.allGroups()) openGroup(g)
                for (m in dao.notAppended()) appendRow(m)
            }
            is PeerEvent.Appended -> dao.markAppended(e.key, e.id, e.seq)
            is PeerEvent.Delivered -> dao.markDeliveredBelow(e.key, e.length)
            is PeerEvent.Message -> onDirectMessage(e)
            is PeerEvent.GroupAppended ->
                dao.markAppended(MessageEntity.groupConversation(e.group), MessageEntity.groupRowId(myKey(), e.id), e.seq)
            is PeerEvent.GroupDelivered -> dao.recordGroupAck(e.group, e.member, e.length)
            is PeerEvent.GroupLogLearned -> dao.insertGroupLog(GroupLog(e.group, e.member, e.outbox, e.sig))
            is PeerEvent.GroupMessage -> onGroupMessage(e)
            is PeerEvent.Connected, is PeerEvent.Disconnected -> Unit // replication is automatic
            is PeerEvent.FileStored, is PeerEvent.FileData, is PeerEvent.FileDone, is PeerEvent.FileError -> Unit
        }
    }

    private suspend fun onDirectMessage(e: PeerEvent.Message) {
        // Only peers in the contact list (added or pending request) may write to disk.
        val contact = contacts.contacts.first().find { it.keyHex == e.key } ?: return
        if (e.kind == MessageEntity.KIND_INVITE) {
            // Only people you added can put you in a group.
            if (contact.status != Contact.Status.ADDED || !acceptInvite(e.key, e.body)) {
                network.ack(e.key, e.seq) // consumed, but ignored
                return
            }
        }
        val row = MessageEntity(
            peerKey = e.key, id = e.id, outgoing = false, body = e.body, sentAt = e.sentAt,
            localTime = System.currentTimeMillis(), status = MessageEntity.RECEIVED,
            signature = e.signature, read = false, seq = e.seq, kind = e.kind,
        )
        if (dao.insert(row) != -1L) {
            if (row.kind == MessageEntity.KIND_FILE) onFileMessage?.invoke(row)
            runCatching { onIncoming?.invoke(row) }
        }
        // Ack after the row is on disk (duplicates are re-acked too).
        network.ack(e.key, e.seq)
    }

    private suspend fun acceptInvite(from: String, json: String): Boolean {
        val g = parseInvite(json) ?: return false
        if (from !in g.memberList || myKey() !in g.memberList) return false
        if (dao.insertGroup(g) != -1L) openGroup(g)
        return true
    }

    private suspend fun onGroupMessage(e: PeerEvent.GroupMessage) {
        val group = dao.allGroups().find { it.id == e.group } ?: return
        if (e.from !in group.memberList) return
        val row = MessageEntity(
            peerKey = group.conversationKey, id = MessageEntity.groupRowId(e.from, e.id), outgoing = false,
            body = e.body, sentAt = e.sentAt, localTime = System.currentTimeMillis(),
            status = MessageEntity.RECEIVED, signature = e.signature, read = false, seq = e.seq,
            senderKey = e.from, kind = e.kind,
        )
        if (dao.insert(row) != -1L) {
            if (row.kind == MessageEntity.KIND_FILE) onFileMessage?.invoke(row)
            runCatching { onIncoming?.invoke(row) }
        }
        network.groupAck(e.group, e.from, e.seq)
    }

    companion object {
        const val MAX_BODY = 4000
        private const val TAG = "Messaging"

        private val HEX32 = Regex("^[0-9a-f]{32}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parseInvite(json: String): GroupEntity? = runCatching {
            val o = JSONObject(json)
            val id = o.getString("id")
            val secret = o.getString("secret")
            val creator = o.getString("creator")
            val arr = o.getJSONArray("members")
            val members = List(arr.length()) { arr.getString(it) }
            if (!HEX32.matches(id) || !HEX64.matches(secret) || !HEX64.matches(creator)) return null
            if (members.any { !HEX64.matches(it) } || creator !in members || members.size > 64) return null
            GroupEntity(
                id = id, name = o.optString("name").take(48).ifBlank { "Group" }, secret = secret,
                creatorKey = creator, members = members.distinct().joinToString(","),
                createdAt = System.currentTimeMillis(),
            )
        }.getOrNull()
    }
}
