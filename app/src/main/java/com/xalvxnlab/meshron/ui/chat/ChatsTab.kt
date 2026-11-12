package com.xalvxnlab.meshron.ui.chat

import androidx.compose.foundation.background
import com.xalvxnlab.meshron.ui.components.pressScale

import androidx.compose.animation.AnimatedVisibility

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.draw.shadow

import androidx.compose.ui.draw.clip

import androidx.compose.material3.ripple

import androidx.compose.material.icons.rounded.Add

import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.compose.foundation.layout.defaultMinSize

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.border

import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.ui.components.ActionCircle
import com.xalvxnlab.meshron.ui.components.DarkPill
import com.xalvxnlab.meshron.ui.components.InsetDivider
import com.xalvxnlab.meshron.ui.components.ListRow
import com.xalvxnlab.meshron.ui.components.SectionHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Color
import com.xalvxnlab.meshron.ui.components.GlassCard
import com.xalvxnlab.meshron.ui.theme.MeshColors
import com.xalvxnlab.meshron.ui.components.GlowButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.data.ConversationSummary
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.messaging.MessagingService
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.PeerInfo
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.CountBadge
import com.xalvxnlab.meshron.ui.components.EmptyState
import com.xalvxnlab.meshron.ui.components.SectionLabel
import com.xalvxnlab.meshron.ui.components.friendlyTime
import com.xalvxnlab.meshron.ui.home.shareIdentity

private data class ChatRow(
    val conversation: String,
    val title: String,
    val isGroup: Boolean,
    val online: Boolean,
    val fallback: String,
    val summary: ConversationSummary?,
)

/** Contacts you can talk to and groups you're in; most recent activity first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsTab(
    identity: Identity,
    contactsRepo: ContactsRepository,
    messaging: MessagingService,
    network: NetworkService,
    nameOf: (String) -> String,
    query: String = "",
    onOpen: (String) -> Unit,
    onAddContact: () -> Unit,
    onNewGroup: () -> Unit,
) {
    val context = LocalContext.current
    val contacts by contactsRepo.contacts.collectAsState(initial = emptyList())
    val groups by messaging.groups.collectAsState(initial = emptyList())
    val summaries by messaging.summaries.collectAsState(initial = emptyList())
    val net by network.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    val byConv = summaries.associateBy { it.last.peerKey }
    val added = contacts.filter { it.status == Contact.Status.ADDED }
    val online = { key: String -> net.peers[key]?.state == PeerInfo.State.CONNECTED }

    val rows = (
        contacts
            .filter { it.status != Contact.Status.BLOCKED && (it.status == Contact.Status.ADDED || byConv.containsKey(it.keyHex)) }
            .map { ChatRow(it.keyHex, it.displayName, false, online(it.keyHex), "Tap to say hi", byConv[it.keyHex]) } +
            groups.map {
                ChatRow(it.conversationKey, it.name, true, false, "${it.memberList.size} members", byConv[it.conversationKey])
            }
        ).sortedByDescending { it.summary?.last?.localTime ?: 0L }
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }

    Box(Modifier.fillMaxSize()) {
        if (rows.isEmpty() && query.isNotBlank()) {
            EmptyState(Icons.AutoMirrored.Outlined.Chat, "No match", "No chat is called \"$query\".")
        } else if (rows.isEmpty()) {
            EmptyState(
                Icons.AutoMirrored.Outlined.Chat,
                "No chats yet",
                "Add a friend with their Meshron ID, or send them yours. Once one of you adds the other, you can chat.",
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlowButton(onClick = onAddContact) {
                        Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Add a contact", color = Color.White)
                    }
                    Spacer(Modifier.size(8.dp))
                    DarkPill(onClick = { shareIdentity(context, identity) }) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share my ID")
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                if (query.isBlank()) {
                    item(key = "stories") {
                        com.xalvxnlab.meshron.ui.stories.StoriesStrip(identity, added, online, nameOf, onOpen, onAddContact)
                    }
                }
                itemsIndexed(rows, key = { _, r -> r.conversation }) { _, r ->
                    Column(Modifier.animateItem()) {
                        ConversationRow(r, nameOf) { onOpen(r.conversation) }
                    }
                }
            }
            // Full label at the top of the list; shrinks to just the icon while you scroll.
            GradientFab(
                expanded = listState.firstVisibleItemIndex == 0,
                onClick = { picking = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                SheetAction(Icons.Filled.PersonAdd, "Add a contact", "By @username, ID or QR code") {
                    picking = false
                    onAddContact()
                }
                SheetAction(Icons.Filled.GroupAdd, "New group", "Chat with several contacts at once") {
                    picking = false
                    onNewGroup()
                }
                if (added.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SectionHeader("Your contacts")
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        added.forEach { c ->
                            ListItem(
                                headlineContent = { Text(c.displayName) },
                                supportingContent = {
                                    Text(if (online(c.keyHex)) "Online" else "Offline · they'll get messages later")
                                },
                                leadingContent = { Avatar(c.displayName, c.keyHex, 48.dp, online = online(c.keyHex)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    picking = false
                                    onOpen(c.keyHex)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(detail, color = MeshColors.TextSecondary) },
        leadingContent = { ActionCircle(icon) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ConversationRow(r: ChatRow, nameOf: (String) -> String, onClick: () -> Unit) {
    val s = r.summary
    val unread = s?.unread ?: 0
    val last = s?.last
    ListRow(
        title = r.title,
        onClick = onClick,
        leadingSize = 56.dp,
        titleBold = unread > 0,
        leading = { Avatar(r.title, r.conversation, 56.dp, group = r.isGroup, online = r.online) },
        // Time on top, unread count under it, both right-aligned.
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    last?.let { friendlyTime(it.localTime) } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unread > 0) MeshColors.GradMid else MeshColors.TextTertiary,
                    fontWeight = if (unread > 0) FontWeight.SemiBold else null,
                )
                Spacer(Modifier.height(6.dp))
                if (unread > 0) CountBadge(unread) else Spacer(Modifier.height(20.dp))
            }
        },
        subtitle = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (last != null && last.outgoing) {
                    // Your last message: show its delivery state, like in the chat.
                    val (icon, tint) = when (last.status) {
                        MessageEntity.PENDING -> Icons.Rounded.Schedule to MeshColors.TextTertiary
                        MessageEntity.SENT -> Icons.Rounded.Done to MeshColors.TextTertiary
                        else -> Icons.Rounded.DoneAll to MeshColors.Accent
                    }
                    Icon(icon, null, Modifier.size(16.dp), tint = tint)
                    Spacer(Modifier.width(4.dp))
                }
                val preview = last?.let { m ->
                    val who = if (r.isGroup && !m.outgoing && m.senderKey != null) nameOf(m.senderKey) + ": " else ""
                    who + displayBody(m)
                } ?: r.fallback
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread > 0) MeshColors.TextPrimary else MeshColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

@Composable
fun NewGroupDialog(contacts: List<Contact>, onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 48) name = it },
                    label = { Text("Group name") },
                    placeholder = { Text("e.g. Family") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (contacts.isEmpty()) {
                    Text(
                        "You need contacts first. Only people you've added can join a group.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Text(
                        "Who's in it? (${picked.size} picked)",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        contacts.forEach { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (c.keyHex in picked) picked.remove(c.keyHex) else picked.add(c.keyHex)
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = c.keyHex in picked, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Avatar(c.displayName, c.keyHex, 32.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(c.displayName)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && picked.isNotEmpty(),
                onClick = { onCreate(name.trim(), picked.toList()) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * "Active now": a story-style strip of your contacts. Online friends come first with the
 * gradient ring; tap anyone to open the chat. The first bubble adds someone new.
 */
@Composable
private fun ActiveNow(people: List<Contact>, online: (String) -> Boolean, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val count = people.count { online(it.keyHex) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (count > 0) "Active now" else "Friends",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MeshColors.TextPrimary,
            )
            if (count > 0) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).background(MeshColors.Online, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("$count", style = MaterialTheme.typography.labelMedium, color = MeshColors.Online)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "add") {
                StoryBubble(label = "Add", onClick = onAdd) {
                    Box(
                        Modifier.size(68.dp).border(2.dp, MeshColors.Hairline, CircleShape).padding(5.dp)
                            .background(MeshColors.Surface2, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, "Add a contact", Modifier.size(28.dp), tint = MeshColors.Accent)
                    }
                }
            }
            items(people, key = { it.keyHex }) { c ->
                val on = online(c.keyHex)
                StoryBubble(label = c.displayName.substringBefore(' '), dim = !on, onClick = { onOpen(c.keyHex) }) {
                    Box {
                        Avatar(c.displayName, c.keyHex, 68.dp, ring = on)
                        if (on) {
                            Box(
                                Modifier.size(18.dp).align(Alignment.BottomEnd)
                                    .background(MeshColors.Bg, CircleShape).padding(3.dp)
                                    .background(MeshColors.Online, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryBubble(label: String, dim: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(
        Modifier.width(80.dp).pressScale(src, 0.92f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(vertical = 6.dp)
            .graphicsLayer { alpha = if (dim) 0.55f else 1f },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MeshColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Gradient "New chat" button; collapses to just the pencil while you scroll. */
@Composable
private fun GradientFab(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(src)
            .shadow(14.dp, RoundedCornerShape(20.dp), ambientColor = MeshColors.GradMid, spotColor = MeshColors.GradMid)
            .clip(RoundedCornerShape(20.dp))
            .background(MeshColors.Gradient)
            .clickable(interactionSource = src, indication = ripple(color = Color.White), onClick = onClick)
            .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Edit, "New chat", tint = Color.White)
        AnimatedVisibility(visible = expanded) {
            Row {
                Spacer(Modifier.width(10.dp))
                Text("New chat", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}
