package com.xalvxnlab.meshron.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.data.MessageDao
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.messaging.MessagingService
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.PeerEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** What a file message carries (signed as the message body). */
data class FileInfo(
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val blobs: String,
    val blob: JSONObject,
) {
    val isImage: Boolean get() = mime.startsWith("image/")

    fun toJson(): String = JSONObject()
        .put("name", name).put("mime", mime).put("size", size).put("sha256", sha256)
        .put("blobs", blobs).put("blob", blob).toString()

    companion object {
        fun parse(json: String): FileInfo? = runCatching {
            val o = JSONObject(json)
            FileInfo(
                name = o.getString("name").take(200),
                mime = o.optString("mime", "application/octet-stream"),
                size = o.getLong("size"),
                sha256 = o.getString("sha256"),
                blobs = o.getString("blobs"),
                blob = o.getJSONObject("blob"),
            )
        }.getOrNull()
    }
}

/**
 * Sends and receives files through encrypted Hyperblobs logs (see app.js). Every received
 * file is downloaded automatically, checked against the SHA-256 in the signed message, and
 * only then shown. Failed or waiting downloads are retried whenever a peer connects.
 */
class FileService(
    private val context: Context,
    private val dao: MessageDao,
    private val network: NetworkService,
    messaging: MessagingService,
    private val contacts: ContactsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = SecureRandom()
    private val uploads = ConcurrentHashMap<String, CompletableDeferred<PeerEvent.FileStored>>()
    private val downloads = ConcurrentHashMap<String, Download>()
    private val sentDir = File(context.filesDir, "chatfiles/sent").apply { mkdirs() }
    private val receivedDir = File(context.filesDir, "chatfiles/received").apply { mkdirs() }

    private class Download(
        val row: MessageEntity,
        val info: FileInfo,
        val tmp: File,
        val out: FileOutputStream,
        val digest: MessageDigest,
        var bytes: Long = 0,
    )

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Per message ("peerKey|id"): a short status for files not yet available, e.g. "Downloading 40%". */
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    init {
        messaging.onFileMessage = { download(it) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            network.events.collect { e -> runCatching { handle(e) }.onFailure { Log.e(TAG, "event $e", it) } }
        }
        // Accepting a request unlocks files they already sent.
        scope.launch {
            contacts.contacts.map { l -> l.filter { it.status == Contact.Status.ADDED }.map { it.keyHex }.toSet() }
                .distinctUntilChanged().drop(1).collect { runCatching { retryPending() } }
        }
    }

    /** Group files, and files from contacts you added. Never from pending requests (could be spam). */
    private suspend fun mayDownload(row: MessageEntity): Boolean =
        row.isGroup || contacts.contacts.first().any { it.keyHex == row.peerKey && it.status == Contact.Status.ADDED }

    /** Stores an existing local file in my blob log for [conversation]; returns what a message carries. */
    suspend fun uploadLocal(conversation: String, local: File, name: String, mime: String): Result<FileInfo> = runCatching {
        if (!network.isOnline) error("The network is still starting. Try again in a moment.")
        val digest = MessageDigest.getInstance("SHA-256")
        withContext(Dispatchers.IO) { local.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; digest.update(b, 0, n) } } }
        val id = ByteArray(16).also(random::nextBytes).toHex()
        val done = CompletableDeferred<PeerEvent.FileStored>()
        uploads[id] = done
        try {
            withContext(Dispatchers.IO) { local.inputStream().use { network.fileUpload(id, wireConversation(conversation), it) } }
            val stored = withTimeout(120_000) { done.await() }
            FileInfo(name, mime, local.length(), digest.digest().toHex(), stored.blobs, JSONObject(stored.blob))
        } finally {
            uploads.remove(id)
        }
    }

    /** Decodes a picked picture, shrinks it to 1440 px on the long side and saves it as JPEG. */
    suspend fun prepareStoryPhoto(uri: Uri, sid: String): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1440) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Can't read that picture")
        val scale = minOf(1f, 1440f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = File(sentDir, "$sid-story.jpg")
        out.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        out
    }

    /** Copies the picked file, stores it in my blob log and sends the file message. */
    suspend fun send(messaging: MessagingService, conversation: String, uri: Uri): Result<Unit> = runCatching {
        if (!network.isOnline) error("The network is still starting. Try again in a moment.")
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_FILE) error("Files can be up to 25 MB")
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val id = ByteArray(16).also(random::nextBytes).toHex()
        val local = File(sentDir, "$id-${safeName(name)}")

        // Copy and hash in one pass.
        val digest = MessageDigest.getInstance("SHA-256")
        withContext(Dispatchers.IO) {
            (resolver.openInputStream(uri) ?: error("Can't read that file")).use { input ->
                local.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE) error("Files can be up to 25 MB")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        }

        val done = CompletableDeferred<PeerEvent.FileStored>()
        uploads[id] = done
        try {
            withContext(Dispatchers.IO) { local.inputStream().use { network.fileUpload(id, wireConversation(conversation), it) } }
            val stored = withTimeout(120_000) { done.await() }
            val info = FileInfo(name, mime, local.length(), digest.digest().toHex(), stored.blobs, JSONObject(stored.blob))
            messaging.sendFile(conversation, info.toJson(), local.absolutePath)
        } catch (t: Throwable) {
            local.delete()
            throw t
        } finally {
            uploads.remove(id)
        }
    }

    private suspend fun download(row: MessageEntity) {
        val key = keyOf(row)
        if (downloads.values.any { keyOf(it.row) == key } || row.localPath != null) return
        val info = (if (row.kind == MessageEntity.KIND_STORY) com.xalvxnlab.meshron.messaging.StoryBody.parse(row.body)?.file
            else FileInfo.parse(row.body)) ?: return if (row.kind == MessageEntity.KIND_STORY) Unit else setStatus(key, "Invalid file")
        if (info.size > MAX_FILE) return setStatus(key, "Too large")
        if (!mayDownload(row)) return setStatus(key, "Accept this contact to download")
        val req = ByteArray(8).also(random::nextBytes).toHex()
        val tmp = File(context.cacheDir, "dl-$req")
        downloads[req] = Download(row, info, tmp, FileOutputStream(tmp), MessageDigest.getInstance("SHA-256"))
        setStatus(key, "Downloading…")
        network.fileGet(req, wireConversation(row.peerKey), row.senderKey ?: row.peerKey, info.blobs, info.blob)
    }

    private suspend fun retryPending() {
        for (row in dao.filesToDownload()) download(row)
        for (row in dao.storyPhotosToDownload(System.currentTimeMillis() - com.xalvxnlab.meshron.messaging.StoryService.TTL)) download(row)
    }

    private suspend fun handle(e: PeerEvent) {
        when (e) {
            is PeerEvent.FileStored -> uploads[e.xfer]?.complete(e)
            is PeerEvent.FileData -> downloads[e.req]?.let { d ->
                d.out.write(e.data)
                d.digest.update(e.data)
                d.bytes += e.data.size
                if (d.bytes > d.info.size) fail(e.req, "Size mismatch")
                else if (d.info.size > 0) setStatus(keyOf(d.row), "Downloading ${d.bytes * 100 / d.info.size}%")
            }
            is PeerEvent.FileDone -> downloads.remove(e.req)?.let { d ->
                d.out.close()
                val hash = d.digest.digest().toHex()
                if (hash != d.info.sha256 || d.bytes != d.info.size) {
                    d.tmp.delete()
                    setStatus(keyOf(d.row), "Failed: file was altered")
                    return
                }
                val dest = File(receivedDir, "${d.row.wireId.take(12)}-${safeName(d.info.name)}")
                if (!d.tmp.renameTo(dest)) {
                    d.tmp.copyTo(dest, overwrite = true)
                    d.tmp.delete()
                }
                dao.setLocalPath(d.row.peerKey, d.row.id, dest.absolutePath)
                _status.update { it - keyOf(d.row) }
            }
            is PeerEvent.FileError -> {
                uploads[e.id]?.completeExceptionally(IllegalStateException(e.message))
                if (downloads.containsKey(e.id)) {
                    fail(e.id, if (e.message == "offline") "Waiting for sender to come online" else "Waiting to retry")
                }
            }
            // Someone came online: they may have the blocks we're waiting for.
            is PeerEvent.Connected, PeerEvent.Ready -> retryPending()
            else -> Unit
        }
    }

    private fun fail(req: String, message: String) {
        val d = downloads.remove(req) ?: return
        runCatching { d.out.close() }
        d.tmp.delete()
        setStatus(keyOf(d.row), message)
    }

    private fun setStatus(key: String, text: String) = _status.update { it + (key to text) }

    companion object {
        const val MAX_FILE = 25L * 1024 * 1024
        private const val TAG = "Files"

        fun keyOf(m: MessageEntity) = m.peerKey + "|" + m.id
        fun wireConversation(conversation: String) =
            if (conversation.startsWith(MessageEntity.GROUP_PREFIX)) conversation else "dm:$conversation"

        private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifEmpty { "file" }
    }
}
