package com.xalvxnlab.meshron.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.ui.components.Toasts
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
private fun ago(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" }
}

/**
 * The moderator app inside Meshron: reports that need a person, appeals, removed content,
 * verification requests and, for the CEOs only, applications and the moderator team.
 */
@Composable
fun ModeratorApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("reports") }
    var reload by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf<Triple<String, String, JSONObject>?>(null) } // title, action, extra
    LaunchedEffect(reload) { error = null; runCatching { app.moderation.queue() }.onSuccess { q = it }.onFailure { error = it.message ?: "Couldn't load" } }
    val ceo = q?.optString("role") == "admin"
    fun act(action: String, extra: JSONObject, done: String) {
        scope.launch {
            runCatching { app.moderation.act(action, extra) }
                .onSuccess { Toasts.show(context, done); reload++ }
                .onFailure { Toasts.show(context, it.message ?: "Didn't work", long = true) }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Moderator", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (ceo) "CEO · full access" else "Moderator", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Refresh") }
            }
            val tabs = buildList {
                add("reports" to "Reports (${q?.optJSONArray("reports")?.length() ?: 0})")
                add("appeals" to "Appeals (${q?.optJSONArray("appeals")?.length() ?: 0})")
                add("hidden" to "Removed")
                add("verify" to "Verification (${q?.optJSONArray("verifications")?.length() ?: 0})")
                if (ceo) { add("apps" to "Applications (${q?.optJSONArray("applications")?.length() ?: 0})"); add("team" to "Team") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, label) in tabs) {
                    val on = tab == id
                    Text(label, color = if (on) MeshColors.Bg else MeshColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) MeshColors.TextPrimary else MeshColors.Surface2).clickable { tab = id }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            val data = q
            when {
                error != null -> Text(error!!, color = MeshColors.Danger, modifier = Modifier.padding(20.dp))
                data == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val list = when (tab) {
                        "reports" -> data.optJSONArray("reports").objs()
                        "appeals" -> data.optJSONArray("appeals").objs()
                        "hidden" -> data.optJSONArray("hidden").objs()
                        "verify" -> data.optJSONArray("verifications").objs()
                        "apps" -> data.optJSONArray("applications").objs()
                        else -> data.optJSONArray("team").objs()
                    }
                    if (tab == "team") item {
                        ModCard("Add a moderator", "Only the CEOs can add or remove moderators. They need a Meshron account.") {
                            Pill("Add by @username", primary = true) { ask = Triple("Add moderator", "role-add", JSONObject()) }
                        }
                    }
                    if (list.isEmpty()) item { Text("Nothing here. 🎉", color = MeshColors.TextSecondary, modifier = Modifier.padding(20.dp)) }
                    items(list) { o ->
                        when (tab) {
                            "reports" -> {
                                val ai = o.optJSONObject("ai")
                                ModCard("${o.optString("reason")} · ${o.optString("kind")}", buildString {
                                    append(o.optString("details").ifBlank { o.optString("content") }.take(300))
                                    if (ai != null) append("\nAI: ${ai.optString("action")} (${ai.optString("category")}, severity ${ai.optInt("severity")}) — ${ai.optString("reason")}")
                                    append("\n${ago(o.optLong("ts"))} ago")
                                }) {
                                    val ex = { JSONObject().put("report", o.optString("id")).put("kind", o.optString("kind")).put("target", o.optString("target")).put("owner", o.optString("owner")) }
                                    if (o.optString("kind") in listOf("reel", "comment", "live", "moment")) Pill("Remove", danger = true) { ask = Triple("Remove it?", "hide", ex()) }
                                    Pill("Warn") { ask = Triple("Warn the owner?", "warn", ex().put("who", o.optString("owner"))) }
                                    Pill("Ban", danger = true) { ask = Triple("Suspend the owner?", "ban", ex().put("who", o.optString("owner"))) }
                                    Pill("Restore") { act("restore", ex(), "Restored") }
                                    Pill("Dismiss") { act("dismiss", ex(), "Dismissed") }
                                }
                            }
                            "appeals" -> ModCard("Appeal · ${o.optString("kind")}", "Removed for: ${o.optString("reason")}\nThey say: ${o.optString("text")}\n${ago(o.optLong("ts"))} ago") {
                                val ex = { JSONObject().put("kind", o.optString("kind")).put("target", o.optString("target")).put("who", o.optString("owner")) }
                                if (o.optString("kind") == "person") Pill("Lift ban", primary = true) { act("unban", ex(), "Account restored") }
                                else Pill("Restore", primary = true) { act("restore", ex(), "Restored") }
                                Pill("Keep decision") { ask = Triple("Deny the appeal?", "deny-appeal", ex()) }
                            }
                            "hidden" -> ModCard("${o.optString("kind")} · ${o.optString("category")}", "${o.optString("reason")}\nBy ${o.optString("by")} · ${ago(o.optLong("ts"))} ago") {
                                Pill("Restore") { act("restore", JSONObject().put("kind", o.optString("kind")).put("target", o.optString("target")), "Restored") }
                            }
                            "verify" -> ModCard("${o.optString("name")} @${o.optString("username")}", "${o.optString("category")}\n${o.optString("reason")}\n${o.optString("links")}") {
                                Pill("Verify ✓", primary = true) { act("verify", JSONObject().put("who", o.optString("id")), "Verified") }
                                Pill("Deny") { ask = Triple("Deny verification?", "deny-verify", JSONObject().put("who", o.optString("id"))) }
                            }
                            "apps" -> ModCard("${o.optString("name")} @${o.optString("username")}", "${o.optString("why")}\nHours/week: ${o.optString("hours")} · Languages: ${o.optString("languages")}\n${ago(o.optLong("ts"))} ago") {
                                Pill("Approve", primary = true) { act("approve-mod", JSONObject().put("who", o.optString("id")), "@${o.optString("username")} is now a moderator") }
                                Pill("Deny") { ask = Triple("Deny the application?", "deny-mod", JSONObject().put("who", o.optString("id"))) }
                            }
                            else -> ModCard("@${o.optString("username")}", if (o.optString("role") == "mod") "Moderator" else o.optString("role")) {
                                Pill("Remove", danger = true) { ask = Triple("Remove @${o.optString("username")} as moderator?", "role", JSONObject().put("who", o.optString("username")).put("role", "")) }
                            }
                        }
                    }
                }
            }
        }
    }
    ask?.let { (title, action, extra) ->
        var text by remember(title) { mutableStateOf("") }
        val adding = action == "role-add"
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(text, { text = it.take(200) }, label = { Text(if (adding) "@username" else "Reason (the person reads this)") }, singleLine = adding, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = { TextButton(onClick = {
                ask = null
                if (adding) act("role", JSONObject().put("who", text.trim().removePrefix("@")).put("role", "mod"), "Moderator added")
                else act(action, extra.put("reason", text.trim()), "Done")
            }) { Text(if (adding) "Add" else "Confirm") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModCard(title: String, body: String, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MeshColors.Surface1).padding(14.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        if (body.isNotBlank()) Text(body, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun Pill(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Text(label, color = if (primary || danger) Color.White else MeshColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(when { danger -> MeshColors.Danger; primary -> MeshColors.AccentStrong; else -> MeshColors.Surface2 })
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

/** Ask to become a moderator: a short form; the CEOs decide. */
@Composable
fun ModeratorApply(name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    var why by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var langs by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    com.xalvxnlab.meshron.ui.components.PageDialog(
        onDismissRequest = onClose,
        title = { Text("Become a moderator") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Moderators keep Meshron safe: they review reports, appeals and verification requests. Meshron's CEOs read every application and add moderators themselves.",
                    color = MeshColors.TextSecondary)
                OutlinedTextField(why, { why = it.take(1500) }, label = { Text("Why do you want to help?") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(hours, { hours = it.take(60) }, label = { Text("Hours a week you can give") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(langs, { langs = it.take(120) }, label = { Text("Languages you speak") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("You need a Meshron account. You must be 18 or older.", color = MeshColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = !busy && why.trim().length >= 20, onClick = {
            busy = true
            scope.launch {
                runCatching { app.moderation.applyToModerate(name, why.trim(), hours.trim(), langs.trim()) }
                    .onSuccess { Toasts.show(context, "Application sent"); onClose() }
                    .onFailure { Toasts.show(context, it.message ?: "Couldn't send it", long = true) }
                busy = false
            }
        }) { Text(if (busy) "Sending…" else "Apply") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
