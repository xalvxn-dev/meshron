package com.xalvxnlab.meshron.network

import android.content.Context
import android.util.Log
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.identity.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import to.holepunch.bare.kit.Ipc
import to.holepunch.bare.kit.Worklet
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the Hyperswarm worklet (assets/app.bundle, source in src/main/js/app.js) inside
 * Bare Kit and exposes its state. Every contact with status ADDED is dialled by public
 * key with `swarm.joinPeer`. Unknown peers that dial us become contact requests.
 */
class NetworkService(
    private val context: Context,
    private val identity: IdentityRepository,
    private val contacts: ContactsRepository,
    private val settings: com.xalvxnlab.meshron.settings.AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(NetworkState())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    // Never drops: emit() suspends if collectors fall behind.
    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<PeerEvent> = _events.asSharedFlow()

    fun isConnected(keyHex: String) = _state.value.peers[keyHex]?.state == PeerInfo.State.CONNECTED
    val isOnline: Boolean get() = _state.value.status == NetworkState.Status.ONLINE

    /**
     * Supplies the resume point (next inbox seq) per peer, read from the message database,
     * so logs aren't re-read from the start after every restart. Set before [start].
     */
    var inboxCursors: (suspend () -> Map<String, Long>)? = null

    /**
     * Lets the network tell group members (who may not be contacts) apart from strangers,
     * so they don't show up as contact requests. Set before [start].
     */
    var isGroupMember: (suspend (String) -> Boolean)? = null

    /** Appends a message to our outbox log for [keyHex]. Works offline; replicates later. */
    suspend fun append(keyHex: String, id: String, body: String, sentAt: Long, kind: String = "text") {
        send(
            cmd("append").put("key", keyHex).put("id", id).put("body", body).put("ts", sentAt).put("kind", kind)
                .toString()
        )
    }

    /** Starts replicating a group: joins its topic and announces my log for it. */
    suspend fun groupOpen(
        id: String,
        secret: String,
        members: List<String>,
        cursors: Map<String, Long>,
        logs: Map<String, Pair<String, String>>, // member -> (log key, signature)
    ) {
        val c = JSONObject().apply { cursors.forEach { (k, v) -> put(k, v) } }
        val a = JSONObject().apply {
            logs.forEach { (m, l) -> put(m, JSONObject().put("outbox", l.first).put("sig", l.second)) }
        }
        send(
            cmd("group-open").put("id", id).put("secret", secret).put("members", JSONArray(members))
                .put("cursors", c).put("anns", a).toString()
        )
    }

    suspend fun groupAppend(group: String, id: String, body: String, sentAt: Long, kind: String = "text") {
        send(
            cmd("group-append").put("group", group).put("id", id).put("body", body).put("ts", sentAt)
                .put("kind", kind).toString()
        )
    }

    /** Streams a file into my blob log for [conversation] ("dm:<peer>" or "g:<id>"); answer is FileStored. */
    suspend fun fileUpload(xfer: String, conversation: String, input: java.io.InputStream) {
        send(cmd("file-begin").put("xfer", xfer).put("conv", conversation).toString())
        val buf = ByteArray(48 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val b64 = android.util.Base64.encodeToString(buf, 0, n, android.util.Base64.NO_WRAP)
            send(cmd("file-chunk").put("xfer", xfer).put("data", b64).toString())
        }
        send(cmd("file-end").put("xfer", xfer).toString())
    }

    /** Fetches a blob written by [from]; answers are FileData chunks then FileDone (or FileError). */
    suspend fun fileGet(req: String, conversation: String, from: String, blobs: String, blob: JSONObject) {
        send(
            cmd("file-get").put("req", req).put("conv", conversation).put("from", from).put("blobs", blobs)
                .put("blob", blob).toString()
        )
    }

    /** Contacts typing to me right now: key -> when I last heard (ms). Live only. */
    private val _typing = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    val typing: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = _typing

    /** Tells a connected contact I'm typing (at most every few seconds; never stored). */
    fun typing(keyHex: String) = scope.launch { if (isOnline) send(cmd("typing").put("key", keyHex).toString()) }

    /** Android switched networks; the worklet rebinds its sockets. */
    fun networkChanged() = scope.launch { if (isOnline) send(cmd("network-change").toString()) }

    suspend fun groupAck(group: String, member: String, seq: Long) {
        send(cmd("group-ack").put("group", group).put("member", member).put("seq", seq).toString())
    }

    /** Confirms that inbox entry [seq] from [keyHex] is stored, so the sender can mark it delivered. */
    suspend fun ack(keyHex: String, seq: Long) {
        send(cmd("ack").put("key", keyHex).put("seq", seq).toString())
    }

    private val started = AtomicBoolean(false)
    private val writeLock = Mutex()
    private var worklet: Worklet? = null
    private var ipc: Ipc? = null
    private var myKeyHex: String? = null
    private val dialled = mutableSetOf<String>()

    /** Logs this phone carries for others (mesh), remembered across restarts. */
    private val carried = CarriedLogs(File(context.filesDir, "mesh-carry.json"))

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                _state.update { it.copy(status = NetworkState.Status.STARTING, lastError = null) }
                val me = identity.identity.filterNotNull().first()
                myKeyHex = me.publicKey.toHex()

                val w = Worklet(context)
                val channel = Ipc(w)
                worklet = w
                ipc = channel
                launch { readEvents(channel) }
                w.start("app.bundle")
                log("Worklet started")

                // The seed crosses into the worklet's memory once, so Hyperswarm can
                // authenticate as us (Noise handshake). It is never written to disk.
                val cursors = JSONObject()
                inboxCursors?.invoke()?.forEach { (peer, next) -> cursors.put(peer, next) }
                val storage = File(context.filesDir, "corestore").absolutePath
                val internet = settings.useInternet.first()
                val carry = settings.carryForOthers.first()
                val startCmd = identity.withSeed { seed ->
                    JSONObject().put("cmd", "start").put("seed", seed.toHex()).put("name", me.username).put("handle", me.handle)
                        .put("storage", storage).put("cursors", cursors)
                        .put("internet", internet).put("carry", carry).put("carried", carried.load())
                        .toString()
                }
                send(startCmd)
            } catch (t: Throwable) {
                fail("Could not start network: ${t.message}", t)
            }
        }
    }

    fun connect(keyHex: String) = scope.launch { send(cmd("connect").put("key", keyHex).toString()) }

    fun disconnect(keyHex: String) = scope.launch {
        send(cmd("disconnect").put("key", keyHex).toString())
        _state.update { it.copy(peers = it.peers - keyHex) }
    }

    private fun cmd(name: String) = JSONObject().put("cmd", name)

    // ---- Local links (same Wi-Fi, Bluetooth / Wi-Fi Direct) ----------------------------

    /** Where the worklet's bytes for a local link go (a socket or a Nearby endpoint). */
    interface LinkSink {
        fun write(bytes: ByteArray)
        fun close()
    }

    /** One local connection. Bytes received from the other phone go in with [receive]. */
    inner class Link internal constructor(val id: String, internal val sink: LinkSink) {
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private val pump = scope.launch {
            for (bytes in inbox) {
                send(
                    cmd("link-data").put("id", id)
                        .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).toString()
                )
            }
        }

        fun receive(bytes: ByteArray) {
            inbox.trySend(bytes)
        }

        /** The other side went away. */
        fun closed() {
            if (links.remove(id) == null) return
            inbox.close()
            scope.launch {
                pump.join()
                send(cmd("link-close").put("id", id).toString())
            }
        }
    }

    private val links = java.util.concurrent.ConcurrentHashMap<String, Link>()

    /**
     * Opens a local link. The worklet runs the same Noise handshake over it as over the
     * internet, so the other phone's identity is verified before anything else happens.
     * [via] is "lan" or "nearby". Exactly one side must be the [initiator].
     */
    fun openLink(via: String, initiator: Boolean, sink: LinkSink): Link {
        val link = Link(java.util.UUID.randomUUID().toString(), sink)
        links[link.id] = link
        scope.launch {
            send(cmd("link-open").put("id", link.id).put("initiator", initiator).put("via", via).toString())
        }
        return link
    }

    /** Call signalling, audio and video from contacts (set by CallManager). Called on the IPC reader. */
    @Volatile var onCallSignal: ((key: String, msg: JSONObject) -> Unit)? = null
    @Volatile var onCallAudio: ((key: String, data: ByteArray) -> Unit)? = null
    @Volatile var onCallVideo: ((key: String, data: ByteArray) -> Unit)? = null

    /** [extra] adds fields such as video, on or seq (the worklet passes on only known ones). */
    fun callSend(keyHex: String, type: String, id: String, reason: String? = null, extra: Map<String, Any> = emptyMap()) = scope.launch {
        val msg = JSONObject().put("t", type).put("id", id)
        if (reason != null) msg.put("reason", reason)
        for ((k, v) in extra) msg.put(k, v)
        send(cmd("call-send").put("key", keyHex).put("msg", msg).toString())
    }

    /** One encoded video frame. A small queue: if it overflows, the receiver asks for a key frame. */
    fun callVideo(keyHex: String, frame: ByteArray) {
        videoOut.trySend(
            cmd("call-video").put("key", keyHex)
                .put("data", android.util.Base64.encodeToString(frame, android.util.Base64.NO_WRAP)).toString()
        )
    }
    private val videoOut = Channel<String>(6, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST).also { ch ->
        scope.launch { for (json in ch) send(json) }
    }

    /** Queued in order; old packets are dropped if the worklet falls behind (late audio is useless). */
    fun callAudio(keyHex: String, packet: ByteArray) {
        audioOut.trySend(
            cmd("call-audio").put("key", keyHex)
                .put("data", android.util.Base64.encodeToString(packet, android.util.Base64.NO_WRAP)).toString()
        )
    }
    private val audioOut = Channel<String>(32, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST).also { ch ->
        scope.launch { for (json in ch) send(json) }
    }

    fun setInternet(on: Boolean) = scope.launch { send(cmd("internet").put("on", on).toString()) }
    fun setCarry(on: Boolean) = scope.launch { send(cmd("carry").put("on", on).toString()) }

    private suspend fun send(json: String) {
        val channel = ipc ?: return
        try {
            writeLock.withLock { channel.write((json + "\n").toByteArray()) }
        } catch (t: Throwable) {
            fail("Worklet channel closed: ${t.message}", t)
        }
    }

    private suspend fun readEvents(channel: Ipc) {
        val pending = ByteArrayOutputStream()
        try {
            channel.data.collect { chunk ->
                var start = 0
                for (i in chunk.indices) {
                    if (chunk[i] == '\n'.code.toByte()) {
                        pending.write(chunk, start, i - start)
                        val line = pending.toString(Charsets.UTF_8.name())
                        pending.reset()
                        start = i + 1
                        if (line.isNotEmpty()) runCatching { onEvent(JSONObject(line)) }
                            .onFailure { log("Bad event: ${it.message}") }
                    }
                }
                pending.write(chunk, start, chunk.size - start)
            }
            fail("Worklet stopped", null)
        } catch (t: Throwable) {
            fail("Worklet channel error: ${t.message}", t)
        }
    }

    private suspend fun onEvent(e: JSONObject) {
        when (e.optString("ev")) {
            "ready" -> {
                val matches = e.optString("publicKey") == myKeyHex
                _state.update {
                    it.copy(status = NetworkState.Status.ONLINE, keyMatches = matches)
                }
                if (!matches) {
                    fail("Worklet derived a different key, refusing to continue", null)
                    return
                }
                log("Network ready")
                _events.emit(PeerEvent.Ready)
                scope.launch { followContacts() }
                scope.launch { followDialled() }
                scope.launch { followName() }
                scope.launch { settings.useInternet.distinctUntilChanged().collect { send(cmd("internet").put("on", it).toString()) } }
                scope.launch { settings.carryForOthers.distinctUntilChanged().collect { send(cmd("carry").put("on", it).toString()) } }
            }
            "dht" -> _state.update {
                it.copy(
                    dht = DhtInfo(
                        bootstrapped = e.optBoolean("bootstrapped"),
                        host = e.optString("host").takeIf { h -> h.isNotEmpty() && h != "null" },
                        port = e.optInt("port"),
                        firewalled = e.optBoolean("firewalled"),
                        randomized = e.optBoolean("randomized"),
                        connections = e.optInt("connections"),
                        internet = e.optBoolean("internet", true),
                    )
                )
            }
            "link-data" -> links[e.getString("id")]?.sink?.write(
                android.util.Base64.decode(e.getString("data"), android.util.Base64.DEFAULT)
            )
            "link-close" -> links.remove(e.getString("id"))?.sink?.close()
            "typing" -> {
                val key = e.getString("key")
                val at = System.currentTimeMillis()
                _typing.update { it + (key to at) }
                scope.launch {
                    kotlinx.coroutines.delay(TYPING_MS)
                    _typing.update { if (it[key] == at) it - key else it }
                }
            }
            "call-msg" -> {
                onCallSignal?.invoke(e.getString("key"), e.getJSONObject("msg"))
            }
            "call-video" -> onCallVideo?.invoke(
                e.getString("key"), android.util.Base64.decode(e.getString("data"), android.util.Base64.DEFAULT)
            )
            "call-audio" -> onCallAudio?.invoke(
                e.getString("key"), android.util.Base64.decode(e.getString("data"), android.util.Base64.DEFAULT)
            )
            "handle-found" -> {
                val arr = e.optJSONArray("keys") ?: JSONArray()
                handleLookups[e.getString("req")]?.complete(
                    HandleResult(List(arr.length()) { arr.getString(it) }, e.optString("error").ifEmpty { null })
                )
            }
            "carry" -> carried.add(e.getString("k"), e.getString("t"))
            "peer" -> onPeer(e)
            "hello" -> {
                val key = e.getString("key")
                val name = e.optString("name").trim()
                _state.update { s ->
                    val p = s.peers[key] ?: return@update s
                    s.copy(peers = s.peers + (key to p.copy(announcedName = name)))
                }
                if (name.isNotEmpty()) contacts.updateName(key, name)
                contacts.updateHandle(key, e.optString("handle"))
            }
            "msg" -> _events.emit(
                PeerEvent.Message(
                    key = e.getString("key"),
                    seq = e.getLong("seq"),
                    id = e.getString("id"),
                    sentAt = e.getLong("ts"),
                    body = e.getString("body"),
                    kind = e.optString("kind", "text"),
                    signature = e.getString("sig"),
                )
            )
            "gmsg" -> _events.emit(
                PeerEvent.GroupMessage(
                    group = e.getString("group"),
                    from = e.getString("from"),
                    seq = e.getLong("seq"),
                    id = e.getString("id"),
                    sentAt = e.getLong("ts"),
                    body = e.getString("body"),
                    kind = e.optString("kind", "text"),
                    signature = e.getString("sig"),
                )
            )
            "file-stored" -> _events.emit(
                PeerEvent.FileStored(e.getString("xfer"), e.getString("blobs"), e.getJSONObject("blob").toString())
            )
            "file-data" -> _events.emit(
                PeerEvent.FileData(e.getString("req"), android.util.Base64.decode(e.getString("data"), android.util.Base64.DEFAULT))
            )
            "file-done" -> _events.emit(PeerEvent.FileDone(e.getString("req")))
            "file-error" -> _events.emit(
                PeerEvent.FileError(e.optString("xfer").ifEmpty { e.optString("req") }, e.optString("msg"))
            )
            "gann" -> _events.emit(
                PeerEvent.GroupLogLearned(e.getString("group"), e.getString("member"), e.getString("outbox"), e.getString("sig"))
            )
            "gappended" -> _events.emit(PeerEvent.GroupAppended(e.getString("group"), e.getString("id"), e.getLong("seq")))
            "gdelivered" -> _events.emit(
                PeerEvent.GroupDelivered(e.getString("group"), e.getString("member"), e.getLong("length"))
            )
            "appended" -> _events.emit(PeerEvent.Appended(e.getString("key"), e.getString("id"), e.getLong("seq")))
            "delivered" -> _events.emit(PeerEvent.Delivered(e.getString("key"), e.getLong("length")))
            "log" -> log(e.optString("msg"))
            "error" -> {
                val msg = e.optString("msg")
                Log.w(TAG, "worklet: $msg")
                log("Error: ${msg.lineSequence().first()}")
                _state.update { it.copy(lastError = msg.lineSequence().first()) }
            }
        }
    }

    private suspend fun onPeer(e: JSONObject) {
        val key = e.getString("key")
        val state = when (e.optString("state")) {
            "connected" -> PeerInfo.State.CONNECTED
            "connecting" -> PeerInfo.State.CONNECTING
            else -> PeerInfo.State.DISCONNECTED
        }
        _state.update { s ->
            val old = s.peers[key]
            val info = PeerInfo(
                keyHex = key,
                state = state,
                host = if (e.optString("via", "internet") != "internet") null
                    else e.optString("host").takeIf { it.isNotEmpty() && it != "null" } ?: old?.host,
                port = if (e.has("port")) e.optInt("port") else old?.port ?: 0,
                relayed = e.optBoolean("relayed", old?.relayed ?: false),
                via = e.optString("via").ifEmpty { old?.via ?: "internet" },
                initiator = e.optBoolean("initiator", old?.initiator ?: false),
                announcedName = old?.announcedName,
            )
            s.copy(peers = s.peers + (key to info))
        }
        val short = key.take(8)
        when (state) {
            PeerInfo.State.CONNECTED -> {
                val via = e.optString("via", "internet")
                log(
                    if (via == "internet") "Connected to $short… (internet, ${e.optString("host")})"
                    else "Connected to $short… (${_state.value.peers[key]?.viaLabel ?: via})"
                )
                // Someone we don't know dialled our ID: keep it as a request for the user.
                // Unknown phones met nearby are just mesh neighbours (they carry messages).
                val list = contacts.contacts.first()
                val known = list.any { it.keyHex == key } || isGroupMember?.invoke(key) == true
                if (!known && via == "internet") {
                    // Strangers can make endless new IDs, so cap how many requests we keep.
                    if (list.count { it.status == Contact.Status.REQUEST } >= MAX_REQUESTS) {
                        log("Too many pending requests, dropped $short…")
                        send(cmd("disconnect").put("key", key).toString())
                        _state.update { it.copy(peers = it.peers - key) }
                        return
                    }
                    contacts.addRequest(key)
                }
                _events.emit(PeerEvent.Connected(key))
            }
            PeerInfo.State.DISCONNECTED -> {
                log("Disconnected from $short…")
                _events.emit(PeerEvent.Disconnected(key))
            }
            PeerInfo.State.CONNECTING -> log("Looking for $short… on the DHT")
        }
    }

    /**
     * Keeps the set of dialled peers equal to the ADDED contacts, and tells the worklet who
     * may write to this device (added: everything; requests: a few messages until accepted).
     */
    private suspend fun followContacts() {
        contacts.contacts.distinctUntilChanged().collect { list ->
            val added = list.filter { it.status == Contact.Status.ADDED }.map { it.keyHex }
            val requests = list.filter { it.status == Contact.Status.REQUEST }.map { it.keyHex }
            send(cmd("trust").put("added", JSONArray(added)).put("requests", JSONArray(requests)).toString())
        }
    }

    private suspend fun followDialled() {
        contacts.contacts
            .map { list -> list.filter { it.status == Contact.Status.ADDED }.map { it.keyHex }.toSet() }
            .distinctUntilChanged()
            .collect { wanted ->
                for (key in wanted - dialled) send(cmd("connect").put("key", key).toString())
                for (key in dialled - wanted) {
                    send(cmd("disconnect").put("key", key).toString())
                    _state.update { it.copy(peers = it.peers - key) }
                }
                dialled.clear()
                dialled.addAll(wanted)
            }
    }

    private suspend fun followName() {
        scope.launch {
            identity.identity.filterNotNull().map { it.handle }.distinctUntilChanged().collect {
                send(cmd("handle").put("handle", it).toString())
            }
        }
        identity.identity.filterNotNull().map { it.username }.distinctUntilChanged().collect {
            send(cmd("name").put("name", it).toString())
        }
    }

    /** Result of an @username lookup: identity keys (hex) that announced it. */
    data class HandleResult(val keys: List<String>, val error: String?)
    private val handleLookups = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<HandleResult>>()

    /** Looks up who announced @[handle] on the DHT (needs internet, ~1–8 s). */
    suspend fun findHandle(handle: String): HandleResult {
        if (!isOnline) return HandleResult(emptyList(), "offline")
        val req = java.util.UUID.randomUUID().toString()
        val d = kotlinx.coroutines.CompletableDeferred<HandleResult>()
        handleLookups[req] = d
        send(cmd("find-handle").put("req", req).put("handle", handle).toString())
        return try {
            kotlinx.coroutines.withTimeoutOrNull(15_000) { d.await() } ?: HandleResult(emptyList(), "timeout")
        } finally {
            handleLookups.remove(req)
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val line = TIME.get()!!.format(Date()) + "  " + msg
        _state.update { it.copy(log = (it.log + line).takeLast(60)) }
    }

    private fun fail(msg: String, t: Throwable?) {
        Log.e(TAG, msg, t)
        log(msg)
        _state.update { it.copy(status = NetworkState.Status.FAILED, lastError = msg) }
    }

    private companion object {
        const val TYPING_MS = 6000L
        const val TAG = "NetworkService"
        const val MAX_REQUESTS = 30
        val TIME = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
    }
}
