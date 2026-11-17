package com.xalvxnlab.meshron.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** What a contact shares about themselves: a short bio and a small photo. */
data class PeerProfile(val pv: Int, val bio: String, val photo: ImageBitmap?, val link: String = "")

/**
 * Profiles: my bio and photo (sent to contacts who ask, see app.js "profile"), and the ones
 * my contacts have shared with me. Photos are small square JPEGs (256 px, ~15 KB), so the
 * whole thing stays light. [dir] is per identity (public / anonymous).
 */
class ProfileStore(context: Context, dirName: String = "profiles") {
    private val dir = File(context.filesDir, dirName).apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val meFile = File(dir, "me.json")
    private val mePhoto = File(dir, "me.jpg")

    private val _me = MutableStateFlow(loadMe())
    /** My own profile (bio + photo), for the Me tab. */
    val me: StateFlow<PeerProfile> = _me

    private val _peers = MutableStateFlow(loadPeers())
    /** Contacts' profiles by key (hex). */
    val peers: StateFlow<Map<String, PeerProfile>> = _peers

    private fun loadMe(): PeerProfile {
        val o = runCatching { JSONObject(meFile.readText()) }.getOrNull()
        return PeerProfile(o?.optInt("pv") ?: 0, o?.optString("bio").orEmpty(), decode(mePhoto), o?.optString("link").orEmpty())
    }

    private fun loadPeers(): Map<String, PeerProfile> {
        val o = runCatching { JSONObject(index.readText()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { k ->
            val p = o.getJSONObject(k)
            PeerProfile(p.optInt("pv"), p.optString("bio"), decode(File(dir, "$k.jpg")))
        }
    }

    private fun decode(f: File): ImageBitmap? =
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() else null

    /** The version of a contact's profile I have (0 = none), to know when to ask again. */
    fun versionOf(key: String): Int = _peers.value[key]?.pv ?: 0

    /** What the network sends to contacts who ask. */
    fun myProfileJson(): JSONObject {
        // Anonymous identity: never shares a bio or photo.
        if (com.xalvxnlab.meshron.identity.Mode.anon) return JSONObject().put("pv", 0).put("bio", "").put("photo", "")
        val m = _me.value
        val photo = if (mePhoto.exists()) Base64.encodeToString(mePhoto.readBytes(), Base64.NO_WRAP) else ""
        return JSONObject().put("pv", m.pv).put("bio", m.bio).put("photo", photo).put("link", m.link)
    }

    /** Saves my bio and (optionally) a new photo; bumps the version so contacts refresh. */
    suspend fun setMine(bio: String, photo: Uri?, context: Context, removePhoto: Boolean = false, link: String? = null) = withContext(Dispatchers.IO) {
        if (bio.trim() != _me.value.bio.trim()) com.xalvxnlab.meshron.moderation.ModerationService.guardText("bio", bio)
        if (removePhoto) mePhoto.delete()
        if (photo != null) squareJpeg(context, photo)?.let { mePhoto.writeBytes(it) }
        val pv = _me.value.pv + 1
        meFile.writeText(JSONObject().put("pv", pv).put("bio", bio.trim().take(BIO_MAX)).put("link", cleanLink(link ?: _me.value.link)).toString())
        _me.value = loadMe()
    }

    /** A contact's profile arrived. */
    suspend fun savePeer(key: String, pv: Int, bio: String, photoB64: String) = withContext(Dispatchers.IO) {
        if (!HEX64.matches(key)) return@withContext
        val f = File(dir, "$key.jpg")
        val bytes = runCatching { if (photoB64.isEmpty()) null else Base64.decode(photoB64, Base64.DEFAULT) }.getOrNull()
        if (bytes != null && bytes.size <= 100_000) f.writeBytes(bytes) else f.delete()
        val o = runCatching { JSONObject(index.readText()) }.getOrNull() ?: JSONObject()
        o.put(key, JSONObject().put("pv", pv).put("bio", bio.take(BIO_MAX)))
        index.writeText(o.toString())
        val profile = PeerProfile(pv, bio.take(BIO_MAX), decode(f))
        _peers.update { it + (key to profile) }
    }

    /** Centre-cropped, 256 px, JPEG ~80: a sharp avatar that's still tiny to send. */
    private fun squareJpeg(context: Context, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val src = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
    }.getOrNull()

    companion object {
        const val BIO_MAX = 150

        /** A website for the profile: https only, up to 200 characters ("" = none). */
        fun cleanLink(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) return ""
            val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
            val ok = runCatching { java.net.URI(withScheme).host?.contains('.') == true }.getOrDefault(false)
            return if (ok) withScheme.replaceFirst("http://", "https://").take(200) else ""
        }
        private const val SIZE = 256
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
