package com.xalvxnlab.meshron.messaging

import kotlinx.coroutines.flow.map

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

    /** Disappearing messages: hours to keep messages (0 = forever). Set before [start]. */
    var retentionHours: kotlinx.coroutines.flow.Flow<Int>? = null
    /** Per-chat disappearing timers (conversation -> hours); they override [retentionHours]. */
    var chatTimers: (suspend () -> Map<String, Int>)? = null

    /** Called after any new incoming message is stored (used for notifications). */
    var onIncoming: (suspend (MessageEntity) -> Unit)? = null

    val summaries = dao.summaries()

    /** Messages containing [q] (text only), newest first. */
    suspend fun exportAll(): List<MessageEntity> = dao.exportAll()
    suspend fun allGroupsNow() = dao.allGroups()

    suspend fun search(q: String): List<MessageEntity> = if (q.trim().length < 2) emptyList() else dao.searchText(q.trim().replace("%", "").replace("_", ""))

    /**
     * Streaks: for each 1:1 chat, how many days in a row (up to today, or yesterday if today
     * hasn't happened yet) you both sent something. 0 when broken.
     */
    val streaks: kotlinx.coroutines.flow.Flow<Map<String, Int>> = run {
        val tz = java.util.TimeZone.getDefault().rawOffset.toLong()
        dao.activeDays(System.currentTimeMillis() - 400L * 86_400_000, tz).map { rows ->
            val today = (System.currentTimeMillis() + tz) / 86_400_000
            rows.groupBy { it.peerKey }.mapValues { (_, days) ->
                val mine = days.filter { it.outgoing }.map { it.day }.toSet()
                val theirs = days.filter { !it.outgoing }.map { it.day }.toSet()
                val both = mine intersect theirs
                var d = if (today in both) today else today - 1
                var n = 0
                while (d in both) { n++; d-- }
                n
            }.filterValues { it > 0 }
        }
    }
    val groups = dao.groups()
    fun conversation(key: String) = dao.conversation(key)
    fun modifiers(key: String) = dao.modifiers(key)
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
        // Disappearing messages: every minute, delete what's older than the chosen time.
        retentionHours?.let { hours ->
            scope.launch {
                while (true) {
                    val h = hours.first()
                    val now = System.currentTimeMillis()
                    runCatching {
                        val timers = chatTimers?.invoke().orEmpty()
                        if (h > 0) deleteOlderThan(now - h * 3_600_000L, except = timers.keys)
                        for ((conv, ch) in timers) deleteChatOlderThan(conv, now - ch * 3_600_000L)
                    }.onFailure { Log.w(TAG, "disappearing", it) }
                    kotlinx.coroutines.delay(60_000)
                }
            }
        }
    }

    private suspend fun deleteOlderThan(before: Long, except: Set<String> = emptySet()) {
        val convs = dao.conversationsOlderThan(before).filter { it !in except }
        for (c in convs) deleteChatOlderThan(c, before)
    }

    private suspend fun deleteChatOlderThan(conv: String, before: Long) {
        val paths = dao.chatFilesOlderThan(conv, before)
        if (dao.chatCountOlderThan(conv, before) == 0) return
        keepReadPosition(conv)
        paths.forEach { runCatching { java.io.File(it).delete() } }
        dao.deleteChatOlderThan(conv, before)
    }

    private suspend fun myKey(): String = identity.identity.filterNotNull().first().publicKey.toHex()
    private fun newId() = ByteArray(16).also(random::nextBytes).toHex()

    /**
     * Sends text to a 1:1 chat (hex key) or a group ("g:<id>"). A reply starts with
     * "↩ <quote>" on its own line (same format on every platform).
     */
    suspend fun send(conversation: String, text: String, replyTo: MessageEntity? = null) {
        val clean = text.trim()
        val body = (if (replyTo != null && clean.isNotEmpty()) "↩ " + quoteOf(replyTo) + "\n" + clean else clean).take(MAX_BODY)
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
        if (group) checkGroupMessage(conversation, msg, clean)
    }

    /**
     * Meshron AI looks at what I said in a group (right after it's sent, so chatting stays fast).
     * A rule break gets a warning at once; outside 18+ groups the message is also deleted for everyone.
     */
    private fun checkGroupMessage(conversation: String, msg: MessageEntity, text: String) {
        val mod = com.xalvxnlab.meshron.moderation.ModerationService.current ?: return
        scope.launch {
            val g = dao.findGroup(conversation.removePrefix(MessageEntity.GROUP_PREFIX))
            val broke = mod.check("group", text, adult = g?.adult == true) ?: return@launch
            if (g?.adult != true || broke.severity >= 4) runCatching { dao.find(msg.peerKey, msg.id)?.let { unsend(it) } }
        }
    }

    /**
     * Creates a group with me plus [memberKeys] (all contacts), then invites each member
     * through their 1:1 log.
     */
    suspend fun createGroup(name: String, memberKeys: List<String>, adult: Boolean = false): GroupEntity {
        require(!adult || com.xalvxnlab.meshron.settings.Age.isAdult) { "Only adults can make 18+ groups" }
        com.xalvxnlab.meshron.moderation.ModerationService.guardText("name", name, adult)
        val me = myKey()
        val group = GroupEntity(
            id = newId(),
            name = name.trim().take(48),
            secret = ByteArray(32).also(random::nextBytes).toHex(),
            creatorKey = me,
            members = (listOf(me) + memberKeys).distinct().joinToString(","),
            createdAt = System.currentTimeMillis(),
            adult = adult,
        )
        dao.insertGroup(group)
        if (network.isOnline) openGroup(group)
        sendInvites(group, memberKeys)
        return group
    }

    private fun inviteJson(g: GroupEntity) = JSONObject()
        .put("id", g.id).put("name", g.name).put("secret", g.secret)
        .put("creator", g.creatorKey).put("members", JSONArray(g.memberList))
        .put("owner", g.owner).put("admins", JSONArray(g.adminList)).put("bio", g.bio).put("icon", g.icon).put("adult", g.adult)
        .toString()

    private suspend fun sendInvites(group: GroupEntity, memberKeys: List<String>) {
        val invite = inviteJson(group)
        for (member in memberKeys) {
            val now = System.currentTimeMillis()
            val row = MessageEntity(
                peerKey = member, id = newId(), outgoing = true, body = invite, sentAt = now, localTime = now,
                status = MessageEntity.PENDING, signature = null, read = true, kind = MessageEntity.KIND_INVITE,
            )
            dao.insert(row)
            if (network.isOnline) appendLock.withLock { appendRow(row) }
        }
    }

    // ---- Group roles and settings ----
    // Every change is a signed "gmeta" entry in the sender's group log. Each member applies it only
    // if the sender is allowed to make it (owner > admins > members), so nobody can take over a group.

    fun group(id: String) = dao.group(id)

    private suspend fun meta(g: GroupEntity, o: JSONObject) {
        val me = myKey()
        applyMeta(g.id, me, o)
        sendKind(g.conversationKey, o.toString(), MessageEntity.KIND_GMETA)
    }

    /** Owner/admins: name, bio and photo. */
    suspend fun setGroupInfo(g: GroupEntity, name: String, bio: String, icon: String) {
        if (name.trim() != g.name) com.xalvxnlab.meshron.moderation.ModerationService.guardText("name", name, g.adult)
        if (bio.trim() != g.bio) com.xalvxnlab.meshron.moderation.ModerationService.guardText("description", bio, g.adult)
        meta(g, JSONObject().put("op", "info").put("name", name.trim().take(48)).put("bio", bio.trim().take(200)).put("icon", icon.take(24_000)))
    }

    /** Owner only: make someone an admin, or take it away. */
    suspend fun setAdmin(g: GroupEntity, who: String, on: Boolean) = meta(g, JSONObject().put("op", "admin").put("who", who).put("on", on))

    /** Owner removes anyone; admins remove members (not admins or the owner). */
    suspend fun removeMember(g: GroupEntity, who: String) = meta(g, JSONObject().put("op", "remove").put("who", who))

    /** Owner/admins add contacts: the group learns it, and they get an invite. */
    suspend fun addMembers(g: GroupEntity, keys: List<String>) {
        val fresh = keys.filter { it !in g.memberList }
        if (fresh.isEmpty()) return
        meta(g, JSONObject().put("op", "add").put("who", JSONArray(fresh)))
        dao.findGroup(g.id)?.let { sendInvites(it, fresh) }
    }

    /** Owner only: hand the group to another member (the old owner stays as an admin). */
    suspend fun transferOwnership(g: GroupEntity, who: String) = meta(g, JSONObject().put("op", "owner").put("who", who))

    /** Leave the group. An owner has to hand it over first. */
    suspend fun leaveGroup(g: GroupEntity) {
        require(g.owner != myKey() || g.memberList.size <= 1) { "Make someone else the owner first" }
        meta(g, JSONObject().put("op", "leave"))
    }

    /** Owner/admins: delete anyone's message for everyone. */
    suspend fun deleteForEveryone(g: GroupEntity, m: MessageEntity) = meta(g, JSONObject().put("op", "del").put("id", m.id))

    private suspend fun applyMeta(groupId: String, from: String, o: JSONObject) {
        val g = dao.findGroup(groupId) ?: return
        if (from !in g.memberList) return
        val who = o.optString("who")
        val members = g.memberList.toMutableList()
        val admins = g.adminList.toMutableList()
        var next: GroupEntity? = null
        when (o.optString("op")) {
            "info" -> if (g.isAdmin(from)) next = g.copy(
                name = o.optString("name").take(48).ifBlank { g.name },
                bio = o.optString("bio").take(200),
                icon = o.optString("icon").take(24_000),
            )
            "admin" -> if (from == g.owner && who in members && who != g.owner) {
                admins.remove(who); if (o.optBoolean("on")) admins += who
                next = g.copy(admins = admins.joinToString(","))
            }
            "remove" -> if (who in members && who != g.owner && (from == g.owner || (from in admins && who !in admins))) {
                members.remove(who); admins.remove(who)
                next = g.copy(members = members.joinToString(","), admins = admins.joinToString(","))
            }
            "add" -> if (g.isAdmin(from)) {
                val arr = o.optJSONArray("who") ?: JSONArray()
                for (i in 0 until minOf(arr.length(), 64)) arr.optString(i).takeIf { HEX64.matches(it) && it !in members }?.let { members += it }
                if (members.size <= 256) next = g.copy(members = members.joinToString(","))
            }
            "owner" -> if (from == g.owner && who in members && who != from) {
                admins.remove(who); if (from !in admins) admins += from
                next = g.copy(ownerKey = who, admins = admins.joinToString(","))
            }
            "leave" -> if (from != g.owner) {
                members.remove(from); admins.remove(from)
                next = g.copy(members = members.joinToString(","), admins = admins.joinToString(","))
            }
            "del" -> if (g.isAdmin(from)) {
                val id = o.optString("id").take(100)
                val target = dao.find(g.conversationKey, id)
                // Admins can't delete the owner's messages; the owner can delete anything.
                if (target != null && (from == g.owner || target.senderKey != g.owner)) {
                    target.localPath?.let { runCatching { java.io.File(it).delete() } }
                    dao.markUnsent(g.conversationKey, id)
                }
            }
        }
        if (next != null && next != g) {
            dao.updateGroup(next)
            // Membership changed: the network layer follows the new member list.
            if (next.members != g.members && network.isOnline && myKey() in next.memberList) openGroup(next)
        }
    }

    /** Sends a file message whose blob is already stored (see FileService). */
    suspend fun sendFile(conversation: String, body: String, localPath: String) =
        sendKind(conversation, body, MessageEntity.KIND_FILE, localPath)

    /** Appends an entry of any kind (file, story, seen) to a chat's log. */
    suspend fun sendKind(conversation: String, body: String, kind: String, localPath: String? = null) {
        val now = System.currentTimeMillis()
        val wireId = newId()
        val me = myKey()
        val group = conversation.startsWith(MessageEntity.GROUP_PREFIX)
        val msg = MessageEntity(
            peerKey = conversation,
            id = if (group) MessageEntity.groupRowId(me, wireId) else wireId,
            outgoing = true, body = body, sentAt = now, localTime = now,
            status = MessageEntity.PENDING, signature = null, read = true,
            senderKey = if (group) me else null, kind = kind, localPath = localPath,
        )
        dao.insert(msg)
        if (network.isOnline) appendLock.withLock { appendRow(msg) }
    }

    suspend fun markRead(conversation: String) = dao.markRead(conversation)

    /** Lets a contact see that I've read their messages (if read receipts are on). */
    suspend fun sendSeen(keyHex: String) {
        val top = dao.maxIncomingSeq(keyHex) ?: return
        network.readUpTo(keyHex, top + 1)
    }

    /** Changes the text of my own message, on every device that has it. */
    suspend fun edit(m: MessageEntity, text: String) {
        val clean = text.trim().take(MAX_BODY)
        if (!m.outgoing || m.kind != MessageEntity.KIND_TEXT || clean.isEmpty() || clean == bodyText(m.body)) return
        // Keep the quote of a reply; only the reply text changes.
        val quote = REPLY.find(m.body)?.groupValues?.get(1)
        val body = (if (quote != null) "↩ $quote\n$clean" else clean).take(MAX_BODY)
        sendKind(m.peerKey, JSONObject().put("id", m.wireId).put("text", body).toString(), MessageEntity.KIND_EDIT)
        dao.updateBody(m.peerKey, m.id, body)
    }

    /** A note only this phone keeps (calls: missed, declined, how long). Never sent. */
    /**
     * Records a call I made in the chat, as a real message: the other person gets it whenever
     * they're next online (a missed call shows even if they were offline when I called).
     */
    suspend fun callNote(peer: String, video: Boolean, result: String, seconds: Long) {
        val body = JSONObject().put("v", video).put("r", result).put("d", seconds).toString()
        sendKind(peer, body, MessageEntity.KIND_CALL)
    }

    /** Deletes my own message for everyone: their copies lose the content too. */
    suspend fun unsend(m: MessageEntity) {
        if (!m.outgoing || m.kind !in UNSENDABLE) return
        sendKind(m.peerKey, JSONObject().put("id", m.wireId).toString(), MessageEntity.KIND_UNSEND)
        m.localPath?.let { runCatching { java.io.File(it).delete() } }
        dao.markUnsent(m.peerKey, m.id)
    }

    /** Sets (or with an empty [emoji], removes) my reaction to any message in the chat. */
    suspend fun react(m: MessageEntity, emoji: String) {
        if (m.kind == MessageEntity.KIND_DELETED) return
        sendKind(m.peerKey, JSONObject().put("id", m.id).put("e", emoji.take(16)).toString(), MessageEntity.KIND_REACT)
    }

    /** Applies an incoming edit or unsend to the author's own earlier message. */
    private suspend fun applyChange(row: MessageEntity) {
        if (row.kind != MessageEntity.KIND_EDIT && row.kind != MessageEntity.KIND_UNSEND) return
        val o = runCatching { JSONObject(row.body) }.getOrNull() ?: return
        val wire = o.optString("id").takeIf { it.isNotEmpty() && it.length <= 64 } ?: return
        // Group row IDs carry the author, so nobody can change someone else's message.
        val targetId = if (row.isGroup) MessageEntity.groupRowId(row.senderKey ?: return, wire) else wire
        val target = dao.find(row.peerKey, targetId) ?: return
        if (target.outgoing != row.outgoing || target.senderKey != row.senderKey) return
        if (row.kind == MessageEntity.KIND_EDIT) {
            val text = o.optString("text").take(MAX_BODY)
            if (target.kind == MessageEntity.KIND_TEXT && text.isNotBlank()) dao.updateBody(row.peerKey, targetId, text)
        } else if (target.kind in UNSENDABLE) {
            target.localPath?.let { runCatching { java.io.File(it).delete() } }
            dao.markUnsent(row.peerKey, targetId)
        }
    }

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
            network.append(m.peerKey, m.id, m.body, m.sentAt, m.kind, urgent = urgentIds.remove(m.id))
        }
    }

    /** Messages sent "urgently" that may still be waiting to go into the log. */
    private val urgentIds = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Sends text that every Meshron phone nearby passes on (even with "pass messages" off),
     * hop by hop, until it reaches them or a phone with internet delivers it. 1:1 chats only.
     */
    suspend fun sendUrgent(conversation: String, text: String, replyTo: MessageEntity? = null) {
        if (conversation.startsWith(MessageEntity.GROUP_PREFIX)) return send(conversation, text, replyTo)
        val clean = text.trim()
        val body = (if (replyTo != null && clean.isNotEmpty()) "↩ " + quoteOf(replyTo) + "\n" + clean else clean).take(MAX_BODY)
        if (body.isEmpty()) return
        val now = System.currentTimeMillis()
        val msg = MessageEntity(
            peerKey = conversation, id = newId(), outgoing = true, body = body, sentAt = now, localTime = now,
            status = MessageEntity.PENDING, signature = null, read = true,
        )
        urgentIds.add(msg.id)
        dao.insert(msg)
        if (network.isOnline) appendLock.withLock { appendRow(msg) }
    }

    /** SOS: an urgent message to every added contact. Returns how many it went to. */
    suspend fun sendSos(text: String): Int {
        val to = contacts.contacts.first().filter { it.status == Contact.Status.ADDED }
        for (c in to) sendUrgent(c.keyHex, text)
        return to.size
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
            is PeerEvent.Read -> dao.markSeenBelow(e.key, e.length)
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
        // Stories, receipts, edits and reactions aren't chat messages: no unread count, no notification.
        val hidden = e.kind in MessageEntity.HIDDEN_KINDS
        val row = MessageEntity(
            peerKey = e.key, id = e.id, outgoing = false, body = e.body, sentAt = e.sentAt,
            localTime = System.currentTimeMillis(), status = MessageEntity.RECEIVED,
            signature = e.signature, read = hidden, seq = e.seq, kind = e.kind,
        )
        if (dao.insert(row) != -1L) {
            applyChange(row)
            if (row.kind == MessageEntity.KIND_FILE || row.kind == MessageEntity.KIND_STORY) onFileMessage?.invoke(row)
            if (!hidden) runCatching { onIncoming?.invoke(row) }
        }
        // Ack after the row is on disk (duplicates are re-acked too).
        network.ack(e.key, e.seq)
    }

    private suspend fun acceptInvite(from: String, json: String): Boolean {
        val g = parseInvite(json) ?: return false
        if (from !in g.memberList || myKey() !in g.memberList) return false
        if (g.adult && !com.xalvxnlab.meshron.settings.Age.isAdult) return true // shown as an invite they can't open
        if (dao.insertGroup(g) != -1L) openGroup(g)
        else {
            // Added back after leaving or being removed: take the fresh member list and settings.
            val old = dao.findGroup(g.id)
            if (old != null && myKey() !in old.memberList && old.secret == g.secret) {
                dao.updateGroup(g.copy(createdAt = old.createdAt)); openGroup(g)
            }
        }
        return true
    }

    private suspend fun onGroupMessage(e: PeerEvent.GroupMessage) {
        val group = dao.allGroups().find { it.id == e.group } ?: return
        if (e.from !in group.memberList) return
        val hidden = e.kind in MessageEntity.HIDDEN_KINDS
        val row = MessageEntity(
            peerKey = group.conversationKey, id = MessageEntity.groupRowId(e.from, e.id), outgoing = false,
            body = e.body, sentAt = e.sentAt, localTime = System.currentTimeMillis(),
            status = MessageEntity.RECEIVED, signature = e.signature, read = hidden, seq = e.seq,
            senderKey = e.from, kind = e.kind,
        )
        if (dao.insert(row) != -1L) {
            applyChange(row)
            if (row.kind == MessageEntity.KIND_GMETA) runCatching { applyMeta(group.id, e.from, JSONObject(row.body)) }
            if (row.kind == MessageEntity.KIND_FILE) onFileMessage?.invoke(row)
            if (!hidden) runCatching { onIncoming?.invoke(row) }
        }
        network.groupAck(e.group, e.from, e.seq)
    }

    companion object {
        const val MAX_BODY = 4000
        private const val TAG = "Messaging"

        private val HEX32 = Regex("^[0-9a-f]{32}$")
        private val UNSENDABLE = setOf(MessageEntity.KIND_TEXT, MessageEntity.KIND_FILE)

        /** "↩ <quote>" line, newline, reply (same on every platform). */
        val REPLY = Regex("^↩ (.*)\\n([\\s\\S]+)$")

        /** A reply's own text, without the quote line. */
        fun bodyText(body: String): String = REPLY.find(body)?.groupValues?.get(2) ?: body

        /** One short line that stands for [m] in a reply. */
        fun quoteOf(m: MessageEntity): String {
            val text = when (m.kind) {
                MessageEntity.KIND_FILE -> com.xalvxnlab.meshron.files.FileInfo.parse(m.body)
                    ?.let { if (it.isImage) "📷 Photo" else "📎 " + it.name } ?: "📎 File"
                else -> bodyText(m.body)
            }
            val line = text.replace(Regex("\\s+"), " ").trim()
            return if (line.length > 90) line.take(89) + "…" else line
        }
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parseInvite(json: String): GroupEntity? = runCatching {
            val o = JSONObject(json)
            val id = o.getString("id")
            val secret = o.getString("secret")
            val creator = o.getString("creator")
            val arr = o.getJSONArray("members")
            val members = List(arr.length()) { arr.getString(it) }
            if (!HEX32.matches(id) || !HEX64.matches(secret) || !HEX64.matches(creator)) return null
            if (members.any { !HEX64.matches(it) } || members.size > 256) return null
            val owner = o.optString("owner").takeIf { HEX64.matches(it) && it in members } ?: creator
            val adm = o.optJSONArray("admins")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty().filter { it in members }
            GroupEntity(
                id = id, name = o.optString("name").take(48).ifBlank { "Group" }, secret = secret,
                creatorKey = creator, members = members.distinct().joinToString(","),
                createdAt = System.currentTimeMillis(),
                ownerKey = owner, admins = adm.joinToString(","), bio = o.optString("bio").take(200), icon = o.optString("icon").take(24_000),
                adult = o.optBoolean("adult"),
            )
        }.getOrNull()
    }
}
