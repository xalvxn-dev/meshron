package com.xalvxnlab.meshron.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.data.ConversationSummary
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.messaging.MessagingService
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.PeerInfo
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.FilterChips
import com.xalvxnlab.meshron.ui.components.StreakBadge
import com.xalvxnlab.meshron.ui.components.friendlyTime
import com.xalvxnlab.meshron.ui.theme.MeshColors

private class InboxRow(
    val conv: String, val title: String, val group: Boolean, val online: Boolean,
    val summary: ConversationSummary?, val request: Boolean,
    val icon: androidx.compose.ui.graphics.ImageBitmap? = null,
)

/**
 * Home: stories, then every chat (most recent first) with filters. Long-press a chat for
 * quick actions ([onLongPress]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Inbox(
    identity: Identity,
    contactsRepo: ContactsRepository,
    messaging: MessagingService,
    network: NetworkService,
    nameOf: (String) -> String,
    onOpen: (String) -> Unit,
    onAddFriend: () -> Unit,
    onNewGroup: () -> Unit,
    onLongPress: (String) -> Unit,
    stories: Boolean,
) {
    val contacts by contactsRepo.contacts.collectAsState(initial = emptyList())
    val groups by messaging.groups.collectAsState(initial = emptyList())
    val summaries by messaging.summaries.collectAsState(initial = emptyList())
    val streaks by messaging.streaks.collectAsState(initial = emptyMap())
    val typing by network.typing.collectAsState()
    val net by network.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    val byConv = summaries.associateBy { it.last.peerKey }
    val online = { key: String -> net.peers[key]?.state == PeerInfo.State.CONNECTED }
    val added = contacts.filter { it.status == Contact.Status.ADDED }

    val rows = (
        contacts.filter { it.status != Contact.Status.BLOCKED && (it.status == Contact.Status.ADDED || byConv.containsKey(it.keyHex)) }
            .map { InboxRow(it.keyHex, it.displayName, false, online(it.keyHex), byConv[it.keyHex], it.status == Contact.Status.REQUEST) } +
            groups.map { InboxRow(it.conversationKey, it.name, true, false, byConv[it.conversationKey], false, groupIcon(it)) }
        ).sortedByDescending { it.summary?.last?.localTime ?: 0L }
    val shown = when (filter) {
        "unread" -> rows.filter { (it.summary?.unread ?: 0) > 0 }
        "groups" -> rows.filter { it.group }
        "requests" -> rows.filter { it.request }
        else -> rows.filter { !it.request }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        if (stories) item(key = "stories") {
            com.xalvxnlab.meshron.ui.stories.StoriesStrip(identity, added, online, nameOf, onOpen, onAddFriend)
        }
        item(key = "filters") {
            Box(Modifier.padding(vertical = 6.dp)) {
                FilterChips(
                    listOf("all" to "All", "unread" to "Unread", "groups" to "Groups", "requests" to "Requests"),
                    filter, { filter = it },
                    counts = mapOf("unread" to rows.count { (it.summary?.unread ?: 0) > 0 }, "requests" to rows.count { it.request }),
                )
            }
        }
        // New group lives with the groups.
        if (filter == "groups") item(key = "newgroup") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.Surface2)
                    .combinedClickable(onClick = onNewGroup).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.GroupAdd, null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("New group", style = MaterialTheme.typography.titleMedium)
                    Text("Chat with several friends at once", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (shown.isEmpty() && filter != "groups") item(key = "empty") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PersonAdd, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    when (filter) { "unread" -> "All caught up"; "groups" -> "No groups yet"; "requests" -> "No requests"; else -> "Start a conversation" },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when (filter) {
                        "unread" -> "You've read everything."
                        "groups" -> "Make one from the + button."
                        "requests" -> "People who add you show up here first."
                        else -> "Search for friends by @username, or scan their QR code from search."
                    },
                    color = MeshColors.TextSecondary, textAlign = TextAlign.Center,
                )
                if (filter == "all") {
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(MeshColors.TextPrimary).combinedClickable(onClick = onAddFriend).padding(horizontal = 22.dp, vertical = 12.dp)) {
                        Text("Find friends", color = MeshColors.Bg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        items(shown, key = { it.conv }) { r ->
            Row(
                Modifier.animateItem().fillMaxWidth()
                    .combinedClickable(onClick = { onOpen(r.conv) }, onLongClick = { onLongPress(r.conv) })
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(r.title, r.conv, 56.dp, group = r.group, online = r.online, image = r.icon)
                Spacer(Modifier.width(14.dp))
                val s = r.summary
                val unread = s?.unread ?: 0
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false),
                        )
                        StreakBadge(streaks[r.conv] ?: 0, Modifier.padding(start = 6.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isTyping = !r.group && (typing[r.conv] ?: 0L) > System.currentTimeMillis() - 6000
                        val last = s?.last
                        if (!isTyping && last != null && last.outgoing) {
                            val (icon, tint) = when (last.status) {
                                MessageEntity.PENDING -> Icons.Rounded.Schedule to MeshColors.TextTertiary
                                MessageEntity.SENT -> Icons.Rounded.Done to MeshColors.TextTertiary
                                else -> Icons.Rounded.DoneAll to MeshColors.Accent
                            }
                            Icon(icon, null, Modifier.size(15.dp), tint = tint)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            when {
                                isTyping -> "typing…"
                                r.request -> "Wants to chat · tap to see"
                                last == null -> if (r.group) "Say hi to the group" else "Say hi 👋"
                                else -> (if (r.group && !last.outgoing && last.senderKey != null) nameOf(last.senderKey) + ": " else "") + displayBody(last)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when { isTyping -> MeshColors.Accent; unread > 0 -> MeshColors.TextPrimary; else -> MeshColors.TextSecondary },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        s?.last?.let { friendlyTime(it.localTime) } ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (unread > 0) MeshColors.Accent else MeshColors.TextTertiary,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (unread > 0) Box(
                        Modifier.clip(RoundedCornerShape(50)).background(MeshColors.Gradient).padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (unread > 99) "99+" else "$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    else Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}

/** Little "disappearing on" timer shown next to a chat name. */
@Composable
fun DisappearingMark(on: Boolean) {
    if (on) Icon(Icons.Rounded.Timer, "Disappearing messages on", tint = MeshColors.TextTertiary, modifier = Modifier.size(14.dp).clip(CircleShape))
}

fun disappearLabel(hours: Int): String = when (hours) { 0 -> "Off"; 1 -> "1 hour"; 24 -> "24 hours"; 168 -> "7 days"; else -> "$hours hours" }
