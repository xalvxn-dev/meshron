package com.xalvxnlab.meshron.assistant

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64

/** Pictures the AI made: saved to Pictures/Meshron (the gallery), and opened full screen on request. */
object Pictures {
    private val DATA = Regex("^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$")

    /** Saves a data: URL picture; returns where it went and a small copy to show in the chat. */
    fun save(c: Context, dataUrl: String): Pair<Uri, Bitmap?>? {
        val m = DATA.matchEntire(dataUrl) ?: return null
        val type = m.groupValues[1]
        val bytes = runCatching { Base64.decode(m.groupValues[2], Base64.DEFAULT) }.getOrNull() ?: return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "meshron-" + System.currentTimeMillis() + "." + (if (type == "jpeg") "jpg" else type))
            put(MediaStore.Images.Media.MIME_TYPE, "image/$type")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Meshron")
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        val thumb = runCatching {
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        }.getOrNull()
        return uri to thumb
    }

    fun open(c: Context, uri: Uri) {
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
