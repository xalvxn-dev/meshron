package com.xalvxnlab.meshron.ui.activity

import androidx.compose.foundation.background
import com.xalvxnlab.meshron.ui.components.FilterChips
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.identity.Mode
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.ui.components.AuroraHeader
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.HeaderButton
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch

/** Activity: people who want to chat, people you may know, and what happened on your reels. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun ActivityScreen(contactsRepo: ContactsRepository, network: NetworkService, onOpen: (String) -> Unit, onAddFriend: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    val contacts by contactsRepo.contacts.collectAsState(initial = emptyList())
    val net by network.state.collectAsState()
    val requests = contacts.filter { it.status == Contact.Status.REQUEST }
    val suggestions by app.suggestions.list.collectAsState()
    val nearbyOn by app.suggestions.enabled.collectAsState(initial = false)
    val anon = Mode.anon
    val notices by app.moderation.notices.collectAsState()
    val banned by app.moderation.banned.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var refreshing by remember { mutableStateOf(false) }
    var appealing by remember { mutableStateOf<com.xalvxnlab.meshron.moderation.ModerationService.Notice?>(null) }
    fun refresh() = scope.launch {
        refreshing = true
        runCatching { app.moderation.refresh() }
        runCatching { app.suggestions.refresh() }
        kotlinx.coroutines.delay(600)
        refreshing = false
    }
    LaunchedEffect(Unit) { if (!anon) runCatching { app.moderation.refresh() } }
    val show = { what: String -> filter == "all" || filter == what }
    // New notices only: the number goes away once you've looked at them.
    val seenAt by app.settings.noticesSeen.collectAsState(initial = Long.MAX_VALUE)
    val unseen = notices.count { it.ts > seenAt }
    LaunchedEffect(filter, notices) { if ((filter == "moderation" || filter == "all") && notices.isNotEmpty()) app.settings.setNoticesSeen(notices.maxOf { it.ts }) }
    // Pinned at the top: title, filters and the nearby switch. Everything else scrolls under them.
    Column(Modifier.fillMaxSize()) {
        AuroraHeader("Activity") { HeaderButton(Icons.Rounded.PersonAdd, "Add friends", onClick = onAddFriend) }
        FilterChips(
            listOfNotNull("all" to "All", "requests" to "Requests", if (anon) null else "people" to "Suggestions", if (anon) null else "moderation" to "From Meshron"),
            filter, { filter = it },
            counts = mapOf("requests" to requests.size, "moderation" to unseen),
        )
        if (!anon) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.Surface1).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(MeshColors.TileTeal.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.NearMe, null, tint = MeshColors.TileTeal, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Suggest people near me", style = MaterialTheme.typography.titleSmall)
                Text("Only your rough area (about 5 km), never your exact location.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Switch(nearbyOn, { on -> scope.launch { app.suggestions.setEnabled(on) } }, colors = SwitchDefaults.colors(checkedTrackColor = MeshColors.AccentStrong))
        }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxWidth().weight(1f)) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        // Suspended: say why, and how to appeal.
        if (!anon && banned != null) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.Danger.copy(alpha = 0.12f)).padding(16.dp)) {
                Text("Your account is suspended", style = MaterialTheme.typography.titleMedium, color = MeshColors.Danger)
                Text(banned.orEmpty(), color = MeshColors.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                Pill("Appeal", primary = true) { appealing = com.xalvxnlab.meshron.moderation.ModerationService.Notice("", 0, "Suspension", "", "person", "", "ban") }
            }
        }
        if (filter == "all" && notices.isEmpty() && requests.isEmpty() && (anon || suggestions.isEmpty())) item { Note("Nothing new. Chat requests, people you may know and updates from Meshron show up here.") }
        // In All, empty sections are left out; their hints show only on their own tab.
        if (!anon && show("moderation") && (filter == "moderation" || notices.isNotEmpty())) {
            item { Section("From Meshron") }
            if (notices.isEmpty()) item { Note("Updates on your reports, and anything from Meshron's moderators, show up here.") }
            items(notices, key = { "n" + it.key }) { n ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.Surface1).padding(14.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MeshColors.TileIndigo.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Shield, null, tint = MeshColors.TileIndigo)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(android.text.format.DateUtils.getRelativeTimeSpanString(n.ts, System.currentTimeMillis(), 60_000).toString(), color = MeshColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(n.text, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                        if (n.canAppeal) Row(Modifier.padding(top = 8.dp)) { Pill("Appeal", primary = true) { appealing = n } }
                    }
                }
            }
        }
        if (show("requests") && (filter == "requests" || requests.isNotEmpty())) item { Section("Requests") }
        if (filter == "requests" && requests.isEmpty()) item { Note("When someone adds you, you decide here whether to chat.") }
        if (show("requests")) items(requests, key = { "r" + it.keyHex }) { c ->
            val name = c.name.ifBlank { net.peers[c.keyHex]?.announcedName.orEmpty() }.ifBlank { "Someone new" }
            Person(name, c.keyHex, if (c.handle.isNotEmpty()) "@${c.handle} wants to chat" else "Wants to chat", onClick = { onOpen(c.keyHex) }) {
                Pill("Accept", primary = true) { scope.launch { contactsRepo.add(c.keyHex, name) } }
                Spacer(Modifier.width(6.dp))
                Pill("Ignore") { scope.launch { contactsRepo.remove(c.keyHex) } }
            }
        }
        if (!anon && show("people") && (filter == "people" || suggestions.isNotEmpty())) {
            item { Section("People you may know") }
            if (suggestions.isEmpty()) item { Note(if (nearbyOn) "Looking around… suggestions update over time." else "Turn this on, or add friends: friends of friends show up here too.") }
            items(suggestions, key = { "s" + it.key }) { s ->
                Person(s.name, s.key, s.reason, onClick = {}) {
                    Pill("Add", primary = true) { scope.launch { contactsRepo.add(s.key, s.name); app.suggestions.dismiss(s.key) } }
                    Spacer(Modifier.width(6.dp))
                    Pill("Remove") { app.suggestions.dismiss(s.key) }
                }
            }
        }
    }
    }
    }
    appealing?.let { n -> AppealDialog(n, onClose = { appealing = null }) }
}

/** Appeal a removal or a suspension: a person at Meshron reviews it. */
@Composable
private fun AppealDialog(n: com.xalvxnlab.meshron.moderation.ModerationService.Notice, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (n.kind == "person") "Appeal your suspension" else "Appeal this removal") },
        text = {
            Column {
                Text("Tell us why this should come back. A person at Meshron reviews every appeal. If you win, everything is restored as it was.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.TextField(text, { text = it.take(1500) }, minLines = 3, placeholder = { Text("Your side of the story") },
                    colors = androidx.compose.material3.TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val r = runCatching { app.moderation.appeal(n.kind, n.target, text) }
                    com.xalvxnlab.meshron.ui.components.Toasts.show(context, r.fold({ "Appeal sent. We'll tell you here." }, { it.message ?: "Couldn't send it" }), long = true)
                    onClose()
                }
            }) { Text(if (busy) "Sending…" else "Send appeal") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
}
@Composable private fun Note(t: String) = Text(t, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

@Composable
private fun Person(name: String, key: String, detail: String, onClick: () -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, key, 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        actions()
    }
}

@Composable
private fun Pill(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(if (primary) MeshColors.TextPrimary else MeshColors.Surface2).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) MeshColors.Bg else MeshColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
}
