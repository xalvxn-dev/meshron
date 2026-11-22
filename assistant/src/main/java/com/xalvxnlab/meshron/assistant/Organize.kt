package com.xalvxnlab.meshron.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tools that act like a person on the phone: on screen (through the accessibility service),
 * in the gallery (MediaStore; Android itself confirms moves and deletes) and in a folder the
 * person chose (the system's file picker grants it once).
 */
suspend fun Device.extra(name: String, x: JSONObject): Outcome = when (name) {
    // ---- On screen ----
    "ui_read", "ui_tap", "ui_type", "ui_scroll", "ui_swipe", "ui_action", "close_app" -> {
        val s = ControlService.instance ?: return Outcome("Acting on screen is off. The person can turn it on in Meshron Assistant's settings (\"Act on screen\").")
        Outcome(when (name) {
            "ui_read" -> s.read()
            "ui_tap" -> s.tap(x.optString("target"), x.optBoolean("long"))
            "ui_type" -> s.type(x.optString("text"), x.optString("target").ifBlank { null })
            "ui_scroll" -> s.scroll(x.optString("direction", "down"))
            "ui_swipe" -> s.swipe(x.optString("direction", "up"))
            "ui_action" -> s.global(x.optString("action"))
            else -> s.closeApp()
        })
    }

    // ---- Gallery ----
    "list_albums" -> photos { cr ->
        val counts = sortedMapOf<String, Int>()
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) { val b = c.getString(0) ?: "(none)"; counts[b] = (counts[b] ?: 0) + 1 }
        }
        Outcome(if (counts.isEmpty()) "No photos" else counts.entries.joinToString("\n") { "${it.key}: ${it.value} photos" })
    }
    "list_photos" -> photos { cr ->
        val days = x.optInt("days", 0); val album = x.optString("album"); val limit = x.optInt("limit", 30).coerceIn(1, 100)
        val sel = mutableListOf<String>(); val args = mutableListOf<String>()
        if (days > 0) { sel += MediaStore.Images.Media.DATE_ADDED + " >= ?"; args += ((System.currentTimeMillis() / 1000) - days * 86400L).toString() }
        if (album.isNotBlank()) { sel += MediaStore.Images.Media.BUCKET_DISPLAY_NAME + " LIKE ?"; args += "%$album%" }
        val f = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        val out = mutableListOf<String>()
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE),
            sel.joinToString(" AND ").ifBlank { null }, args.toTypedArray().ifEmpty { null }, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) out += "id=${c.getLong(0)} · ${c.getString(1)} · ${c.getString(2)} · ${f.format(Date(c.getLong(3) * 1000))} · ${c.getLong(4) / 1024} KB"
        }
        Outcome(if (out.isEmpty()) "No photos match" else out.joinToString("\n"))
    }
    "move_photos" -> photos { cr ->
        val album = x.optString("album").trim().replace(Regex("[\\\\/:*?\"<>|]"), "").take(60)
        val uris = ids(x).map { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        if (album.isEmpty() || uris.isEmpty()) return@photos Outcome("Say which photos and which album")
        // Android asks the person to allow changing these photos.
        if (host.system(ProxyActivity.Req.Sender(MediaStore.createWriteRequest(cr, uris).intentSender)) != true) return@photos Outcome("The person didn't allow it")
        var n = 0
        for (u in uris) if (runCatching { cr.update(u, ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album/") }, null, null) }.getOrDefault(0) > 0) n++
        Outcome("Moved $n photo(s) to the album \"$album\"")
    }
    "delete_photos" -> photos { cr ->
        val uris = ids(x).map { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        if (uris.isEmpty()) return@photos Outcome("Say which photos")
        // Into the trash (kept 30 days), after Android asks the person.
        if (host.system(ProxyActivity.Req.Sender(MediaStore.createTrashRequest(cr, uris, true).intentSender)) == true) Outcome("Moved ${uris.size} photo(s) to the trash (recoverable for 30 days)")
        else Outcome("The person didn't allow it")
    }

    // ---- Files (inside the folder the person chose) ----
    "choose_folder" -> {
        val uri = host.system(ProxyActivity.Req.Folder) as? Uri ?: return Outcome("No folder was chosen")
        a.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Memory.setFolder(a, uri.toString())
        Outcome("I can now organise files in that folder")
    }
    "list_files", "make_folder", "move_files", "delete_files" -> files(name, x)
    else -> Outcome("Unknown tool $name")
}

private fun ids(x: JSONObject): List<Long> {
    val a = x.optJSONArray("ids") ?: JSONArray()
    return (0 until a.length()).mapNotNull { a.optString(it).filter(Char::isDigit).toLongOrNull() }.take(200)
}

private suspend fun Device.photos(block: suspend (android.content.ContentResolver) -> Outcome): Outcome {
    val perm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    if (!host.permission(perm)) return Outcome("The person didn't allow access to photos")
    return block(a.contentResolver)
}

/** A folder tree the person granted: list, make folders, move and delete inside it. */
private suspend fun Device.files(name: String, x: JSONObject): Outcome {
    val tree = Memory.folder(a).takeIf { it.isNotEmpty() }?.let(Uri::parse)
        ?: return Outcome("No folder is chosen yet. Call choose_folder first so the person can pick one (for example Downloads or Documents).")
    val cr = a.contentResolver
    val rootId = DocumentsContract.getTreeDocumentId(tree)

    fun children(docId: String): List<Triple<String, String, String>> { // id, name, mime
        val out = mutableListOf<Triple<String, String, String>>()
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1), c.getString(2))
        }
        return out
    }
    fun resolve(path: String, create: Boolean = false): String? {
        var id = rootId
        for (part in path.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }) {
            val hit = children(id).firstOrNull { it.second.equals(part, true) && it.third == DocumentsContract.Document.MIME_TYPE_DIR }
            id = hit?.first ?: if (create) DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id), DocumentsContract.Document.MIME_TYPE_DIR, part)
                ?.let { DocumentsContract.getDocumentId(it) } ?: return null else return null
        }
        return id
    }
    fun matches(n: String, x: JSONObject): Boolean {
        val names = x.optJSONArray("names")?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() } }.orEmpty()
        val pattern = x.optString("pattern").trim().lowercase()
        if (names.isNotEmpty()) return n.lowercase() in names
        if (pattern.isNotEmpty()) return Regex("^" + pattern.split('*').joinToString(".*") { Regex.escape(it) } + "$").matches(n.lowercase())
        return false
    }

    return when (name) {
        "list_files" -> {
            val id = resolve(x.optString("path")) ?: return Outcome("No such folder")
            val list = children(id)
            Outcome(if (list.isEmpty()) "Empty folder" else list.take(150).joinToString("\n") { (if (it.third == DocumentsContract.Document.MIME_TYPE_DIR) "[folder] " else "") + it.second })
        }
        "make_folder" -> if (resolve(x.optString("path"), create = true) != null) Outcome("Folder ready: " + x.optString("path")) else Outcome("Couldn't make that folder")
        "move_files" -> {
            val from = resolve(x.optString("from")) ?: return Outcome("No such folder: " + x.optString("from"))
            val to = resolve(x.optString("to"), create = true) ?: return Outcome("Couldn't open or make " + x.optString("to"))
            val picked = children(from).filter { it.third != DocumentsContract.Document.MIME_TYPE_DIR && matches(it.second, x) }
            if (picked.isEmpty()) return Outcome("No files matched")
            var n = 0
            for (f in picked) if (runCatching { DocumentsContract.moveDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, f.first), DocumentsContract.buildDocumentUriUsingTree(tree, from), DocumentsContract.buildDocumentUriUsingTree(tree, to)) }.getOrNull() != null) n++
            Outcome("Moved $n of ${picked.size} file(s) to " + x.optString("to"))
        }
        "delete_files" -> {
            val from = resolve(x.optString("path")) ?: return Outcome("No such folder")
            val picked = children(from).filter { matches(it.second, x) }
            if (picked.isEmpty()) return Outcome("No files matched")
            if (!host.approve("Delete ${picked.size} item(s)?", picked.take(6).joinToString(", ") { it.second } + if (picked.size > 6) "…" else "")) return Outcome("The person didn't approve deleting")
            var n = 0
            for (f in picked) if (runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, f.first)) }.getOrDefault(false)) n++
            Outcome("Deleted $n item(s)")
        }
        else -> Outcome("Unknown tool $name")
    }
}
