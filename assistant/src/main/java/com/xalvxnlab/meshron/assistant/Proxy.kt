package com.xalvxnlab.meshron.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred

/**
 * An invisible screen that asks Android for one thing and closes: a permission, a system
 * confirmation (moving or deleting photos), the camera, a document or a folder. The floating orb
 * and the tools use it, since those requests need a screen.
 */
class ProxyActivity : ComponentActivity() {
    sealed class Req {
        data class Perm(val name: String) : Req()
        data class Sender(val sender: IntentSender) : Req()
        object Photo : Req()
        object Doc : Req()
        object Folder : Req()
    }

    companion object {
        private var pending: Pair<Req, CompletableDeferred<Any?>>? = null
        suspend fun ask(c: Context, r: Req): Any? {
            pending?.second?.complete(null)
            val d = CompletableDeferred<Any?>()
            pending = r to d
            c.startActivity(Intent(c, ProxyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            return d.await()
        }
    }

    private fun done(v: Any?) { pending?.second?.complete(v); pending = null; finish() }
    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { done(it) }
    private val sender = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { done(it.resultCode == RESULT_OK) }
    private val photo = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { done(it) }
    private val doc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { done(it) }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { done(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the launcher's result comes back by itself
        when (val r = pending?.first) {
            is Req.Perm -> perm.launch(r.name)
            is Req.Sender -> sender.launch(IntentSenderRequest.Builder(r.sender).build())
            Req.Photo -> photo.launch(null)
            Req.Doc -> doc.launch(arrayOf("image/*", "application/pdf", "text/*"))
            Req.Folder -> folder.launch(null)
            null -> finish()
        }
    }
}

/** Reads a picked document: its text, or a picture of it (photos, the first page of a PDF). */
fun readDocument(c: Context, uri: Uri): Picked? = runCatching {
    val cr = c.contentResolver
    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "document"
    val type = cr.getType(uri).orEmpty()
    when {
        type.startsWith("image/") -> Picked(name, null, ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE })
        type == "application/pdf" -> cr.openFileDescriptor(uri, "r")!!.use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { p ->
                    val k = 1400f / maxOf(p.width, p.height)
                    val bmp = Bitmap.createBitmap((p.width * k).toInt(), (p.height * k).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Picked(name + if (pdf.pageCount > 1) " (page 1 of ${pdf.pageCount})" else "", null, bmp)
                }
            }
        }
        else -> Picked(name, cr.openInputStream(uri)!!.use { it.readBytes().take(60_000).toByteArray().toString(Charsets.UTF_8) }, null)
    }
}.getOrNull()
