package com.xalvxnlab.meshron.moderation

import android.content.Context
import android.net.Uri
import com.xalvxnlab.meshron.identity.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Reports from people about content, people or groups. Each one is saved on the phone with an
 * optional screenshot, then handed to Meshron's moderation (AI review + human moderators).
 * Nothing here is shared with the person reported.
 */
object Reports {
    val REASONS = listOf(
        "Bullying or harassment", "Hate speech or symbols", "Nudity or sexual content", "Violence or dangerous acts",
        "Self-harm or suicide", "Scam or fraud", "Spam", "False information", "Child safety", "Impersonation",
        "Intellectual property", "Something else",
    )

    /** [kind]: "reel", "comment", "person", "group", "message". [target]: its id/key. [page]: what was on screen. */
    data class Report(val kind: String, val target: String, val name: String, val reason: String, val details: String, val page: String, val owner: String = "", val content: String = "")

    private fun dir(context: Context) = File(context.filesDir, Mode.name("reports")).apply { mkdirs() }

    /** Saves the report (and copies the screenshot, if any). Returns its id. */
    suspend fun submit(context: Context, r: Report, screenshot: Uri?): String = withContext(Dispatchers.IO) {
        val id = System.currentTimeMillis().toString(36) + (1000..9999).random()
        val shot = screenshot?.let { u ->
            runCatching {
                val out = File(dir(context), "$id.jpg")
                context.contentResolver.openInputStream(u)?.use { i -> out.outputStream().use { i.copyTo(it) } }
                out.name
            }.getOrNull()
        }
        val o = JSONObject().put("id", id).put("ts", System.currentTimeMillis()).put("kind", r.kind).put("target", r.target)
            .put("name", r.name).put("reason", r.reason).put("details", r.details.take(1000)).put("page", r.page.take(2000))
            .put("screenshot", shot ?: "").put("sent", false).put("owner", r.owner).put("content", r.content.take(2000))
        File(dir(context), "$id.json").writeText(o.toString())
        id
    }

    /** Reports not yet handed to moderation. */
    fun pending(context: Context): List<JSONObject> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }.filter { !it.optBoolean("sent") }
}
