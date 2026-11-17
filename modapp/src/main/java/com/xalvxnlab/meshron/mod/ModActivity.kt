package com.xalvxnlab.meshron.mod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ---- Look (Meshron's colours) -------------------------------------------------------------

private object C {
    val Bg = Color(0xFF0B0D14)
    val S1 = Color(0xFF151925)
    val S2 = Color(0xFF1F2433)
    val Text = Color(0xFFF2F4F8)
    val Text2 = Color(0xFF9AA3B5)
    val Text3 = Color(0xFF656E82)
    val Accent = Color(0xFF7C95FF)
    val Danger = Color(0xFFF0545C)
    val Good = Color(0xFF2EC98A)
    val Warn = Color(0xFFF5A524)
    val Grad = Brush.linearGradient(listOf(Color(0xFF4F7BFF), Color(0xFF19D3C5), Color(0xFF8B5CFF)))
}

class ModActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val api = Api(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = C.Bg, surface = C.S1, primary = C.Accent, onSurface = C.Text, onBackground = C.Text)) {
                Box(Modifier.fillMaxSize().background(C.Bg)) { ModApp(api) }
            }
        }
    }
}

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
private fun ago(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
}
private fun short(id: String) = if (id.length > 12) id.take(6) + "…" + id.takeLast(4) else id

@Composable
private fun ModApp(api: Api) {
    var signedIn by remember { mutableStateOf(api.signedIn) }
    if (!signedIn) SignIn(api) { signedIn = true } else Home(api) { api.signOut(); signedIn = false }
}

// ---- Sign in ----------------------------------------------------------------------------------

@Composable
private fun SignIn(api: Api, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp))
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(C.Grad), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(46.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Meshron Mod", color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("For Meshron's moderators. Sign in with your Meshron username and password.", color = C.Text2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        Field(user, { user = it.take(30) }, "@username")
        Spacer(Modifier.height(10.dp))
        Field(pw, { pw = it }, "Password", password = true)
        error?.let { Text(it, color = C.Danger, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) }
        Spacer(Modifier.height(18.dp))
        BigButton(if (busy) "Signing in…" else "Sign in", enabled = !busy && user.isNotBlank() && pw.isNotEmpty()) {
            busy = true; error = null
            scope.launch {
                runCatching { api.signIn(user, pw) }.onSuccess { pw = ""; onDone() }.onFailure { error = it.message }
                busy = false
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Not a moderator? Apply in Meshron: Settings → Become a moderator.", color = C.Text3, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ---- Home -------------------------------------------------------------------------------------

private enum class Tab(val label: String, val icon: ImageVector) { Queue("Reports", Icons.Rounded.Flag), Appeals("Appeals", Icons.Rounded.Gavel), Removed("Removed", Icons.Rounded.RestoreFromTrash), Activity("Activity", Icons.Rounded.History), People("People", Icons.Rounded.Person), More("More", Icons.Rounded.MoreHoriz) }

/** A decision waiting for a reason (or a username / a message). */
private class Ask(val title: String, val action: String, val extra: JSONObject, val field: String = "reason", val danger: Boolean = false, val done: String = "Done")

@Composable
private fun Home(api: Api, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Queue) }
    var q by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var flags by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { flags = runCatching { api.log(flagged = true).optJSONArray("log")?.length() ?: 0 }.getOrDefault(flags) }
    LaunchedEffect(reload) {
        error = null
        runCatching { api.queue() }.onSuccess { q = it }.onFailure { error = it.message; if ((it as? ApiError)?.status == 401 || (it as? ApiError)?.status == 403) onSignOut() }
    }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(2500); toast = null } }
    val realCeo = q?.optString("role") == "admin"
    val ceo = realCeo
    // Someone's page, opened from the team list.
    var openPerson by remember { mutableStateOf<String?>(null) }
    var personData by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(openPerson, reload) { personData = openPerson?.let { n -> runCatching { api.person(n) }.getOrNull() } }
    fun act(action: String, extra: JSONObject, done: String) {
        scope.launch {
            runCatching { api.act(action, extra) }.onSuccess { toast = done; reload++ }.onFailure { toast = it.message }
        }
    }
    Column(Modifier.fillMaxSize()) {
        // Header
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(C.Grad), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(tab.label, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("@" + api.username + " · " + (if (ceo) "CEO" else "Moderator"), color = C.Text2, fontSize = 12.sp)
            }
            IconButton(onClick = { reload++ }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = C.Text2) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val data = q
            when {
                data == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                data == null -> Text(error.orEmpty(), color = C.Danger, modifier = Modifier.padding(20.dp))
                else -> when (tab) {
                    Tab.Queue -> QueueTab(data, onAsk = { ask = it }, onAct = ::act)
                    Tab.Appeals -> AppealsTab(data, onAsk = { ask = it }, onAct = ::act)
                    Tab.Removed -> RemovedTab(data, onAct = ::act)
                    Tab.People -> PeopleTab(api, onAsk = { ask = it }, onAct = ::act, reloadKey = reload)
                    Tab.Activity -> ActivityTab(api, ceo, onAct = ::act, reloadKey = reload, onFlags = { flags = it })
                    Tab.More -> MoreTab(api, data, ceo, onOpenPerson = { openPerson = it }, onAsk = { ask = it }, onAct = ::act, onSignOut = onSignOut)
                }
            }
            toast?.let {
                Text(it, color = C.Text, fontSize = 14.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(RoundedCornerShape(50)).background(C.S2).padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
        NavigationBar(containerColor = C.S1) {
            for (t in Tab.entries) {
                val n = when (t) { Tab.Queue -> q?.optJSONArray("reports")?.length() ?: 0; Tab.Appeals -> q?.optJSONArray("appeals")?.length() ?: 0; Tab.Activity -> flags; else -> 0 }
                NavigationBarItem(
                    selected = tab == t, onClick = { tab = t; if (t != Tab.People) reload++ },
                    icon = { Box { Icon(t.icon, t.label); if (n > 0) Text(if (n > 99) "99+" else "$n", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(start = 14.dp).clip(CircleShape).background(C.Danger).padding(horizontal = 4.dp)) } },
                    label = { Text(t.label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = C.Text, selectedTextColor = C.Text, unselectedIconColor = C.Text3, unselectedTextColor = C.Text3, indicatorColor = C.S2),
                )
            }
        }
    }
    personData?.let { x -> if (openPerson != null) PersonPage(x, onBack = { openPerson = null; personData = null }, onAsk = { ask = it }, onAct = ::act) }
    ask?.let { a -> AskDialog(a, onClose = { ask = null }) { value ->
        ask = null
        when (a.field) {
            "who" -> act(a.action, a.extra.put("who", value.trim().removePrefix("@")).put("role", "mod"), a.done)
            "notice" -> act(a.action, a.extra.put("text", value), a.done)
            else -> act(a.action, a.extra.put("reason", value), a.done)
        }
    } }
}

// ---- Reports ----------------------------------------------------------------------------------

@Composable
private fun QueueTab(q: JSONObject, onAsk: (Ask) -> Unit, onAct: (String, JSONObject, String) -> Unit) {
    var filter by remember { mutableStateOf("all") }
    val all = q.optJSONArray("reports").objs()
    val list = when (filter) {
        "ai" -> all.filter { it.optJSONObject("ai")?.optString("action").let { a -> a != null && a != "none" } && it.optString("status") == "actioned" }
        "person" -> all.filter { it.optString("status") != "actioned" }
        else -> all
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All ${all.size}", filter == "all") { filter = "all" }
                Chip("Needs a person", filter == "person") { filter = "person" }
                Chip("AI already acted", filter == "ai") { filter = "ai" }
            }
        }
        if (list.isEmpty()) item { Empty("No reports here", "New reports show up here. The AI moderator looks at each one first.") }
        items(list, key = { it.optString("id") }) { r ->
            val ai = r.optJSONObject("ai")
            val ex = { JSONObject().put("report", r.optString("id")).put("kind", r.optString("kind")).put("target", r.optString("target")).put("owner", r.optString("owner")) }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(r.optString("kind").replaceFirstChar { it.uppercase() }, C.Accent)
                    Spacer(Modifier.width(6.dp))
                    Text(r.optString("reason"), color = C.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(ago(r.optLong("ts")), color = C.Text3, fontSize = 12.sp)
                }
                val content = r.optString("content").ifBlank { r.optString("name") }
                if (content.isNotBlank()) Text("“" + content.take(400) + "”", color = C.Text, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.S2).padding(10.dp).fillMaxWidth())
                if (r.optString("details").isNotBlank()) Text("Reporter: " + r.optString("details"), color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                if (ai != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag("AI: " + ai.optString("action") + (if (ai.optBoolean("human")) " · unsure" else ""), if (ai.optString("action") == "none") C.Good else C.Warn)
                    Text("  " + ai.optString("category") + " · severity " + ai.optInt("severity"), color = C.Text2, fontSize = 12.sp)
                }
                if (ai != null && ai.optString("reason").isNotBlank()) Text(ai.optString("reason"), color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Owner " + short(r.optString("owner").ifBlank { "unknown" }) + (if (r.optBoolean("shot")) " · screenshot emailed" else ""), color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Actions {
                    if (r.optString("kind") in listOf("reel", "comment", "live", "moment")) Pill("Remove", C.Danger) { onAsk(Ask("Remove it?", "hide", ex(), danger = true, done = "Removed · the owner was told")) }
                    if (r.optString("owner").isNotBlank()) {
                        Pill("Warn", C.Warn) { onAsk(Ask("Warn the owner?", "warn", ex().put("who", r.optString("owner")), done = "Warning sent")) }
                        Pill("Suspend", C.Danger) { onAsk(Ask("Suspend the owner?", "ban", ex().put("who", r.optString("owner")), danger = true, done = "Account suspended")) }
                    }
                    if (r.optString("status") == "actioned") Pill("Undo AI", C.S2) { onAct("restore", ex(), "Restored") }
                    Pill("Looks fine", C.S2) { onAct("dismiss", ex(), "Dismissed") }
                }
            }
        }
    }
}

// ---- Appeals ----------------------------------------------------------------------------------

@Composable
private fun AppealsTab(q: JSONObject, onAsk: (Ask) -> Unit, onAct: (String, JSONObject, String) -> Unit) {
    val list = q.optJSONArray("appeals").objs()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (list.isEmpty()) item { Empty("No appeals", "When someone appeals a removal or a suspension, it shows here.") }
        items(list, key = { it.optString("key") }) { a ->
            val ex = { JSONObject().put("kind", a.optString("kind")).put("target", a.optString("target")).put("who", a.optString("owner")) }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(a.optString("kind").replaceFirstChar { it.uppercase() }, C.Accent)
                    Spacer(Modifier.weight(1f))
                    Text(ago(a.optLong("ts")), color = C.Text3, fontSize = 12.sp)
                }
                Text("Removed for", color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text(a.optString("reason"), color = C.Text2)
                Text("They say", color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text(a.optString("text"), color = C.Text)
                Actions {
                    if (a.optString("kind") == "person") Pill("Lift suspension", C.Good) { onAct("unban", ex(), "Account restored · they were told") }
                    else Pill("Restore it", C.Good) { onAct("restore", ex(), "Restored · they were told") }
                    Pill("Keep removed", C.S2) { onAsk(Ask("Keep the decision?", "deny-appeal", ex(), done = "Appeal answered")) }
                }
            }
        }
    }
}

// ---- Removed ----------------------------------------------------------------------------------

@Composable
private fun RemovedTab(q: JSONObject, onAct: (String, JSONObject, String) -> Unit) {
    var filter by remember { mutableStateOf("all") }
    val all = q.optJSONArray("hidden").objs().sortedByDescending { it.optLong("ts") }
    val list = when (filter) { "ai" -> all.filter { it.optString("by") == "ai" }; "people" -> all.filter { it.optString("by") != "ai" }; else -> all }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All ${all.size}", filter == "all") { filter = "all" }
                Chip("By AI", filter == "ai") { filter = "ai" }
                Chip("By moderators", filter == "people") { filter = "people" }
            }
        }
        if (list.isEmpty()) item { Empty("Nothing removed", "Removed videos, comments, lives and moments show here. Nothing is deleted: restoring brings it back.") }
        items(list, key = { it.optString("kind") + it.optString("target") }) { h ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(h.optString("kind").replaceFirstChar { it.uppercase() }, C.Accent)
                    Spacer(Modifier.width(6.dp))
                    Tag(if (h.optString("by") == "ai") "AI" else h.optString("by"), if (h.optString("by") == "ai") C.Warn else C.Text3)
                    Spacer(Modifier.weight(1f))
                    Text(ago(h.optLong("ts")), color = C.Text3, fontSize = 12.sp)
                }
                Text(h.optString("reason"), color = C.Text, modifier = Modifier.padding(top = 8.dp))
                Text(h.optString("category") + " · owner " + short(h.optString("owner")), color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Actions { Pill("Restore", C.Good) { onAct("restore", JSONObject().put("kind", h.optString("kind")).put("target", h.optString("target")), "Restored · the owner was told") } }
            }
        }
    }
}

// ---- Activity: every moderator action, and what the AI thought of it ---------------------------

private val ACTION_WORDS = mapOf(
    "hide" to "removed", "restore" to "restored", "warn" to "warned", "ban" to "suspended", "unban" to "lifted a suspension for",
    "verify" to "verified", "unverify" to "removed the check from", "deny-verify" to "declined verification for", "deny-appeal" to "kept a removal after an appeal on",
    "dismiss" to "dismissed a report", "confirm" to "confirmed", "notify" to "sent a notice to", "role" to "changed the role of",
    "approve-mod" to "approved a moderator application", "deny-mod" to "declined a moderator application", "ai-auto" to "switched AI moderation",
)

@Composable
private fun ActivityTab(api: Api, ceo: Boolean, onAct: (String, JSONObject, String) -> Unit, reloadKey: Int, onFlags: (Int) -> Unit) {
    var flaggedOnly by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reloadKey, flaggedOnly) {
        runCatching { api.log(flaggedOnly).optJSONArray("log").objs() }
            .onSuccess { log = it; error = null; if (flaggedOnly) onFlags(it.size) }
            .onFailure { error = it.message }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Everything", !flaggedOnly) { flaggedOnly = false }
                Chip("Flagged by AI", flaggedOnly) { flaggedOnly = true }
            }
            Text("Every moderator action is recorded here for all moderators to see. The AI checks each removal, warning and suspension; anything it thinks is unfair is flagged to the CEOs.",
                color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        }
        error?.let { item { Text(it, color = C.Danger) } }
        val list = log
        if (list == null && error == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (list != null && list.isEmpty()) item { Empty(if (flaggedOnly) "Nothing flagged" else "No actions yet", if (flaggedOnly) "The AI hasn't found any moderator decision that looks unfair." else "Moderator actions show up here as they happen.") }
        items(list.orEmpty(), key = { it.optString("id") }) { e ->
            val check = e.optJSONObject("check")
            val flagged = check != null && !check.optBoolean("fair", true) && e.optString("cleared").isEmpty()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.S1)
                .then(if (flagged) Modifier.border(1.5.dp, C.Danger.copy(alpha = 0.7f), RoundedCornerShape(20.dp)) else Modifier).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.optString("by"), color = C.Text, fontWeight = FontWeight.Bold)
                    if (e.optString("role") == "admin") { Spacer(Modifier.width(6.dp)); Tag("CEO", C.Accent) }
                    Spacer(Modifier.weight(1f))
                    Text(ago(e.optLong("ts")), color = C.Text3, fontSize = 12.sp)
                }
                val target = when {
                    e.optString("kind").isNotBlank() -> "a " + e.optString("kind")
                    e.optString("who").isNotBlank() -> short(e.optString("who"))
                    else -> ""
                }
                Text((ACTION_WORDS[e.optString("action")] ?: e.optString("action")) + (if (target.isNotBlank()) " $target" else ""), color = C.Text, modifier = Modifier.padding(top = 4.dp))
                if (e.optString("reason").isNotBlank()) Text("Reason: " + e.optString("reason"), color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                if (e.optString("content").isNotBlank()) Text("“" + e.optString("content").take(300) + "”", color = C.Text2, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.S2).padding(8.dp))
                when {
                    check == null && e.optString("action") in listOf("hide", "warn", "ban", "deny-appeal") -> Text("AI check unavailable", color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    check != null && check.optBoolean("fair", true) -> Row(Modifier.padding(top = 8.dp)) { Tag("AI: looks fair", C.Good) }
                    check != null -> Column(Modifier.padding(top = 8.dp)) {
                        Row { Tag(if (flagged) "AI: possibly unfair" else "AI flag · reviewed by " + e.optString("cleared"), if (flagged) C.Danger else C.Text3) }
                        Text(check.optString("concern"), color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (ceo && flagged) Actions {
                    when (e.optString("action")) {
                        "hide", "deny-appeal" -> Pill("Undo: restore it", C.Good) { onAct("restore", JSONObject().put("kind", e.optString("kind")).put("target", e.optString("target")), "Restored · the owner was told") }
                        "ban" -> Pill("Undo: lift suspension", C.Good) { onAct("unban", JSONObject().put("who", e.optString("who")), "Suspension lifted") }
                    }
                    Pill("It was fine", C.S2) { onAct("clear-flag", JSONObject().put("target", e.optString("id")), "Marked as reviewed") }
                    if (e.optString("role") == "mod") Pill("Remove " + e.optString("by"), C.Danger) { onAct("role", JSONObject().put("who", e.optString("by").removePrefix("@")).put("role", "").put("reason", "Moderator decisions broke Meshron's moderation rules."), "Removed from the team") }
                }
            }
        }
    }
}

// ---- People -----------------------------------------------------------------------------------

@Composable
private fun PeopleTab(api: Api, onAsk: (Ask) -> Unit, onAct: (String, JSONObject, String) -> Unit, reloadKey: Int) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<JSONObject?>(null) } // open page
    var openName by remember { mutableStateOf<String?>(null) }
    // Names appear as you type; tapping one opens their page.
    LaunchedEffect(q) {
        val t = q.trim().removePrefix("@")
        if (t.isEmpty()) { results = null; error = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        busy = true
        runCatching { api.search(t).optJSONArray("people").objs() }.onSuccess { results = it; error = null }.onFailure { error = it.message }
        busy = false
    }
    fun open(name: String) {
        openName = name
        scope.launch { runCatching { api.person(name) }.onSuccess { person = it }.onFailure { error = it.message; openName = null } }
    }
    LaunchedEffect(reloadKey) { openName?.let { n -> runCatching { api.person(n) }.onSuccess { person = it } } }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(q, { q = it.take(70) }, "Search @username") }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(C.Grad), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Icons.Rounded.Search, "Search", tint = Color.White)
            }
        }
        error?.let { Text(it, color = C.Danger, modifier = Modifier.padding(top = 10.dp)) }
        val list = results
        when {
            list == null -> Empty("Find someone", "Type the start of their @username. Tap a name to see their whole record: reports, removals, warnings, suspensions and appeals.")
            list.isEmpty() -> Text("Nobody's username starts with \"" + q.trim().removePrefix("@") + "\".", color = C.Text3, modifier = Modifier.padding(top = 14.dp, start = 4.dp))
            else -> LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.optString("username") }) { r ->
                    val n = r.optString("username")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.S1).clickable { open(n) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(CircleShape).background(C.Grad), contentAlignment = Alignment.Center) {
                            Text(n.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("@$n", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (r.optString("title").isNotEmpty() || r.optString("role").isNotEmpty()) RoleTags(r, Modifier.padding(top = 4.dp))
                        }
                        if (openName == n && person == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("›", color = C.Text3, fontSize = 26.sp)
                    }
                }
            }
        }
    }
    val x = person
    if (x != null && openName != null) PersonPage(x, onBack = { person = null; openName = null }, onAsk = onAsk, onAct = onAct)
}

/** Everything about one person, for moderators: who they are, their record, and what you can do. */
@Composable
private fun PersonPage(x: JSONObject, onBack: () -> Unit, onAsk: (Ask) -> Unit, onAct: (String, JSONObject, String) -> Unit) {
    val id = x.optString("id")
    val name = x.optString("username")
    val banned = x.optJSONObject("banned")
    val reports = x.optJSONArray("reports").objs()
    val hidden = x.optJSONArray("hidden").objs()
    val history = x.optJSONArray("history").objs()
    val appeals = x.optJSONArray("appeals").objs()
    FullPage(if (name.isNotBlank()) "@$name" else "Person", onBack) {
        // Who
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(C.Grad), contentAlignment = Alignment.Center) {
                Text(name.ifBlank { "?" }.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp)
            }
            Text(if (name.isNotBlank()) "@$name" else "No username", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp))
            Text(short(id), color = C.Text3, fontSize = 12.sp)
            RoleTags(x, Modifier.padding(top = 8.dp))
        }
        // Numbers
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(if (banned != null) "Suspended" else "Active", "Status", if (banned != null) C.Danger else C.Good, Modifier.weight(1f))
            Stat("" + x.optInt("strikes"), "Strikes", if (x.optInt("strikes") > 0) C.Warn else C.Text, Modifier.weight(1f))
            Stat("" + reports.size, "Reports", if (reports.isNotEmpty()) C.Warn else C.Text, Modifier.weight(1f))
            Stat("" + history.count { it.optString("action") == "hide" }, "Removals", C.Text, Modifier.weight(1f))
        }
        if (x.optLong("joined") > 0) Text("Account since " + java.text.DateFormat.getDateInstance().format(java.util.Date(x.optLong("joined"))), color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        if (banned != null) Text("Suspended: " + banned.optString("reason"), color = C.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

        // Actions
        Section("Actions")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Warn", C.Warn, Modifier.weight(1f)) { onAsk(Ask("Warn @$name", "warn", JSONObject().put("who", id), done = "Warning sent")) }
                if (banned == null) ActionButton("Suspend", C.Danger, Modifier.weight(1f)) { onAsk(Ask("Suspend @$name", "ban", JSONObject().put("who", id), danger = true, done = "Suspended")) }
                else ActionButton("Lift suspension", C.Good, Modifier.weight(1f)) { onAct("unban", JSONObject().put("who", id), "Suspension lifted") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (x.optBoolean("verified")) ActionButton("Remove check", C.S2, Modifier.weight(1f)) { onAsk(Ask("Remove @$name's verified check", "unverify", JSONObject().put("who", id), done = "Check removed")) }
                else ActionButton("Verify ✓", C.Accent, Modifier.weight(1f)) { onAct("verify", JSONObject().put("who", id), "Verified") }
                ActionButton("Send a notice", C.S2, Modifier.weight(1f)) { onAsk(Ask("Message @$name", "notify", JSONObject().put("who", id).put("reason", "From Meshron"), field = "notice", done = "Notice sent")) }
            }
        }

        // Reports about them
        Section("Reports about them (" + reports.size + ")")
        if (reports.isEmpty()) Text("Nobody has reported them.", color = C.Text3, modifier = Modifier.padding(start = 4.dp))
        for (r in reports.take(30)) Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(r.optString("kind").replaceFirstChar { it.uppercase() }, C.Accent)
                Text("  " + r.optString("reason"), color = C.Text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(ago(r.optLong("ts")), color = C.Text3, fontSize = 12.sp)
            }
            if (r.optString("details").isNotBlank()) Text(r.optString("details"), color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            val ai = r.optJSONObject("ai")
            Text("Outcome: " + r.optString("status") + (if (ai != null) " · AI: " + ai.optString("action") + " (" + ai.optString("category") + ")" else ""), color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }

        // Removed right now
        Section("Removed right now (" + hidden.size + ")")
        if (hidden.isEmpty()) Text("Nothing of theirs is removed.", color = C.Text3, modifier = Modifier.padding(start = 4.dp))
        for (h in hidden) Card {
            Text(h.optString("kind").replaceFirstChar { it.uppercase() } + " · " + h.optString("reason"), color = C.Text)
            Text("By " + (if (h.optString("by") == "ai") "AI" else h.optString("by")) + " · " + ago(h.optLong("ts")), color = C.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Actions { Pill("Restore", C.Good) { onAct("restore", JSONObject().put("kind", h.optString("kind")).put("target", h.optString("target")), "Restored") } }
        }

        // What moderators did
        Section("Moderation history (" + history.size + ")")
        if (history.isEmpty()) Text("No moderator has acted on them.", color = C.Text3, modifier = Modifier.padding(start = 4.dp))
        for (e in history.take(50)) Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.optString("by") + " " + (ACTION_WORDS[e.optString("action")] ?: e.optString("action")) + (if (e.optString("kind").isNotBlank()) " a " + e.optString("kind") else ""), color = C.Text, modifier = Modifier.weight(1f))
                Text(ago(e.optLong("ts")), color = C.Text3, fontSize = 12.sp)
            }
            if (e.optString("reason").isNotBlank()) Text(e.optString("reason"), color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            if (e.optBoolean("flagged")) Row(Modifier.padding(top = 6.dp)) { Tag("AI flagged this action", C.Danger) }
        }

        // Appeals
        if (appeals.isNotEmpty()) {
            Section("Their appeals (" + appeals.size + ")")
            for (a in appeals) Card {
                Text(a.optString("kind").replaceFirstChar { it.uppercase() } + " · " + a.optString("status"), color = C.Text, fontWeight = FontWeight.SemiBold)
                Text(a.optString("text"), color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(C.S1).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.ExtraBold, fontSize = if (value.length > 4) 13.sp else 18.sp, maxLines = 1)
        Text(label, color = C.Text3, fontSize = 11.sp)
    }
}

@Composable
private fun ActionButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val solid = color != C.S2
    Box(modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(if (solid) color.copy(alpha = 0.92f) else C.S2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (solid) Color.White else C.Text, fontWeight = FontWeight.Bold)
    }
}

/** A full-screen page (back arrow, title, scrolling body). */
@Composable
private fun FullPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onBack, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.activity.compose.BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = C.Text) }
                Text(title, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { content() }
        }
    }
}

/** Who someone is on Meshron: CEO, co-CEO, admin, Meshron AI, moderator, verified. */
@Composable
private fun RoleTags(x: JSONObject, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (x.optString("title")) {
            "founder" -> Tag("CEO · Founder", Color(0xFFFFC83D))
            "cofounder" -> Tag("Co-CEO · Co-founder", Color(0xFFC9CED6))
            "admin" -> Tag("Admin", C.Accent)
            "ai" -> Tag("Meshron AI", C.Accent)
        }
        if (x.optString("role") == "mod") Tag("Moderator", Color(0xFFB57BFF))
        if (x.optBoolean("verified")) Tag("Verified ✓", Color(0xFF4F9BFF))
        if (x.has("id") && x.optString("title").isEmpty() && x.optString("role").isEmpty() && !x.optBoolean("verified")) Tag("Member", C.Text3)
    }
}

// ---- More: verification, CEOs' tools, settings --------------------------------------------------

@Composable
private fun MoreTab(api: Api, q: JSONObject, ceo: Boolean, onOpenPerson: (String) -> Unit, onAsk: (Ask) -> Unit, onAct: (String, JSONObject, String) -> Unit, onSignOut: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Section("Verification requests")
        val ver = q.optJSONArray("verifications").objs()
        if (ver.isEmpty()) Text("No requests.", color = C.Text3, modifier = Modifier.padding(4.dp))
        for (v in ver) Card {
            Text(v.optString("name") + "  @" + v.optString("username"), color = C.Text, fontWeight = FontWeight.Bold)
            Text(v.optString("category"), color = C.Accent, fontSize = 13.sp)
            Text(v.optString("reason"), color = C.Text2, modifier = Modifier.padding(top = 4.dp))
            if (v.optString("links").isNotBlank()) Text(v.optString("links"), color = C.Text3, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Actions {
                Pill("Verify ✓", C.Good) { onAct("verify", JSONObject().put("who", v.optString("id")), "Verified · they were told") }
                Pill("Not now", C.S2) { onAsk(Ask("Decline verification?", "deny-verify", JSONObject().put("who", v.optString("id")), done = "Answered")) }
            }
        }
        if (ceo) {
            Section("AI moderation")
            Card {
                var on by remember(q) { mutableStateOf(q.optBoolean("aiAuto", true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI reviews every report first", color = C.Text, fontWeight = FontWeight.Bold)
                        Text(if (on) "It can remove, warn or suspend right away; unsure cases come to you." else "Off: every report waits for a person.", color = C.Text2, fontSize = 13.sp)
                    }
                    Switch(on, { on = it; onAct("ai-auto", JSONObject().put("reason", if (it) "on" else "off"), if (it) "AI moderation on" else "AI moderation off") })
                }
            }
            Section("Moderator applications")
            val apps = q.optJSONArray("applications").objs()
            if (apps.isEmpty()) Text("No applications.", color = C.Text3, modifier = Modifier.padding(4.dp))
            for (a in apps) Card {
                Text(a.optString("name") + "  @" + a.optString("username"), color = C.Text, fontWeight = FontWeight.Bold)
                Text(a.optString("why"), color = C.Text2, modifier = Modifier.padding(top = 4.dp))
                Text("Hours a week: " + a.optString("hours").ifBlank { "—" } + " · Languages: " + a.optString("languages").ifBlank { "—" }, color = C.Text3, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Actions {
                    Pill("Approve", C.Good) { onAct("approve-mod", JSONObject().put("who", a.optString("id")), "@" + a.optString("username") + " is a moderator now") }
                    Pill("Decline", C.S2) { onAsk(Ask("Decline the application?", "deny-mod", JSONObject().put("who", a.optString("id")), done = "Answered")) }
                }
            }
        }
        val team = q.optJSONArray("team").objs()
        Section("The team (" + team.size + ")")
        for (t in team) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(C.S1).clickable { onOpenPerson(t.optString("username")) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(C.Grad), contentAlignment = Alignment.Center) {
                Text(t.optString("username").take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("@" + t.optString("username") + (if (t.optString("username") == api.username) " (you)" else ""), color = C.Text, fontWeight = FontWeight.Bold)
                RoleTags(t, Modifier.padding(top = 4.dp))
            }
            if (ceo && t.optString("role") == "mod") Pill("Remove", C.Danger) { onAsk(Ask("Remove @" + t.optString("username") + " as moderator", "role", JSONObject().put("who", t.optString("username")).put("role", ""), danger = true, done = "Removed from the team")) }
            else Text("›", color = C.Text3, fontSize = 24.sp)
        }
        if (ceo) { Spacer(Modifier.height(4.dp)); BigButton("Add a moderator") { onAsk(Ask("Add a moderator", "role", JSONObject(), field = "who", done = "Added to the team")) } }
        Section("Account")
        Card {
            Text("Signed in as @" + api.username, color = C.Text)
            Text(if (ceo) "You're a CEO: you can add and remove moderators and turn AI moderation on or off. Every action is recorded with your username and checked by AI."
                else "Only the CEOs can add or remove moderators. Every action is recorded with your username and checked by AI.", color = C.Text3, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Actions { Pill("Sign out", C.S2, onClick = onSignOut) }
        }
        Spacer(Modifier.height(30.dp))
    }
}

// ---- Pieces -----------------------------------------------------------------------------------

private val QUICK = listOf("Spam", "Harassment or bullying", "Hate speech", "Nudity or sexual content", "Violence or threats", "Scam or fraud", "Impersonation", "Misinformation")

@Composable
private fun AskDialog(a: Ask, onClose: () -> Unit, onOk: (String) -> Unit) {
    var v by remember { mutableStateOf("") }
    FullPage(a.title, onClose) {
        Text(when (a.field) {
            "who" -> "Type their Meshron @username. They'll be told, and can open Settings → Moderator in Meshron. Only the CEOs can add or remove moderators."
            "notice" -> "Your message appears in their Activity, from Meshron."
            else -> "The person reads this reason, and can appeal. Pick the rule that was broken, or write your own. Every action is logged and checked by AI."
        }, color = C.Text2, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        if (a.field == "reason") {
            for (r in QUICK) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(if (v == r) C.Accent.copy(alpha = 0.18f) else C.S1)
                .border(1.5.dp, if (v == r) C.Accent else Color.Transparent, RoundedCornerShape(14.dp)).clickable { v = r }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (v == r) C.Accent else C.Text3, CircleShape).padding(4.dp)) {
                    if (v == r) Box(Modifier.fillMaxSize().clip(CircleShape).background(C.Accent))
                }
                Text("  " + r, color = C.Text)
            }
            Spacer(Modifier.height(6.dp))
        }
        Field(v, { v = it.take(if (a.field == "notice") 1000 else 200) }, when (a.field) { "who" -> "@username"; "notice" -> "Message"; else -> "Or write a reason" })
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(if (v.isBlank()) C.S2 else if (a.danger) C.Danger else C.Accent)
            .clickable(enabled = v.isNotBlank()) { onOk(v.trim()) }, contentAlignment = Alignment.Center) {
            Text(when (a.field) { "who" -> "Add moderator"; "notice" -> "Send"; else -> "Confirm" }, color = if (v.isBlank()) C.Text3 else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = label != "Message", modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.S2, focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedLabelColor = C.Accent, unfocusedLabelColor = C.Text3, focusedContainerColor = C.S1, unfocusedContainerColor = C.S1),
    )
}

@Composable
private fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (enabled) C.Grad else Brush.linearGradient(listOf(C.S2, C.S2))).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Color.White else C.Text3, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(RoundedCornerShape(20.dp)).background(C.S1).padding(14.dp)) { content() }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Pill(label: String, color: Color, onClick: () -> Unit) {
    val solid = color != C.S2
    Text(label, color = if (solid) Color.White else C.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (solid) color.copy(alpha = 0.9f) else C.S2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
private fun Tag(label: String, color: Color) {
    Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, color = if (on) C.Bg else C.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.Text else C.S2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun Section(t: String) = Text(t, color = C.Text2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp, start = 4.dp))

@Composable
private fun Empty(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Shield, null, tint = C.Text3, modifier = Modifier.size(40.dp))
        Text(title, color = C.Text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(sub, color = C.Text2, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}
