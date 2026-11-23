package com.xalvxnlab.meshron.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.messaging.MessagingService
import com.xalvxnlab.meshron.reels.Reel
import com.xalvxnlab.meshron.reels.ReelAuthor
import com.xalvxnlab.meshron.reels.ReelsService
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.FilterChips
import com.xalvxnlab.meshron.ui.components.friendlyTime
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.delay

/** One search for everything: people, chats, messages and videos. */
@Composable
fun GlobalSearch(
    identity: Identity,
    contactsRepo: ContactsRepository,
    messaging: MessagingService,
    reels: ReelsService?,
    onScan: () -> Unit,
    onOpenChat: (String) -> Unit,
    onAdd: (Found) -> Unit,
    onClose: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MeshronApp
    BackHandler(onBack = onClose)
    var q by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("all") }
    val contacts by contactsRepo.contacts.collectAsState(initial = emptyList())
    val groups by messaging.groups.collectAsState(initial = emptyList())
    var messages by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var people by remember { mutableStateOf<List<Found>>(emptyList()) }
    var creators by remember { mutableStateOf<List<ReelAuthor>>(emptyList()) }
    var videos by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val myKey = remember(identity) { identity.publicKey.toHex() }
    val text = q.trim()

    val chats = remember(text, contacts, groups) {
        if (text.isEmpty()) emptyList()
        else contacts.filter { it.status != Contact.Status.BLOCKED && it.displayName.contains(text, true) }.map { it.keyHex to it.displayName } +
            groups.filter { it.name.contains(text, true) }.map { it.conversationKey to it.name }
    }
    LaunchedEffect(text) {
        messages = emptyList(); people = emptyList(); creators = emptyList(); videos = emptyList()
        if (text.length < 2) return@LaunchedEffect
        delay(300)
        busy = true
        messages = runCatching { messaging.search(text) }.getOrDefault(emptyList())
        if (reels != null) {
            videos = runCatching { reels.searchVideos(text) }.getOrDefault(emptyList())
            creators = runCatching { reels.search(text) }.getOrDefault(emptyList())
        }
        // A pasted Meshron link: that person.
        if (text.contains("meshron://") || text.length > 40) {
            (com.xalvxnlab.meshron.contacts.ContactParser.parse(text) as? com.xalvxnlab.meshron.contacts.ParsedContact.Ok)?.let { p ->
                if (p.keyHex != myKey && contacts.none { it.keyHex == p.keyHex }) people = listOf(Found(p.keyHex, "", p.name))
            }
        }
        // An exact @username: look the person up even if we've never met.
        val h = text.removePrefix("@").lowercase()
        if (Regex("^[a-z0-9_]{3,24}$").matches(h) && contacts.none { it.displayName.equals(h, true) }) {
            app.accounts.resolve(h)?.takeIf { it != myKey && contacts.none { c -> c.keyHex == it } }?.let { people = listOf(Found(it, h, "")) }
        }
        busy = false
    }
    val nameOf = { key: String -> contacts.find { it.keyHex == key }?.displayName ?: groups.find { it.conversationKey == key }?.name ?: "Chat" }

    Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            TextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Search, @username or link", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                    else IconButton(onClick = onScan) { Icon(Icons.Rounded.QrCodeScanner, "Scan a QR code", tint = MeshColors.Accent) }
                },
                shape = RoundedCornerShape(50), modifier = Modifier.weight(1f).focusRequester(focus),
                colors = TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
        }
        val tabs = buildList {
            add("all" to "All"); add("people" to "People"); add("chats" to "Chats"); add("messages" to "Messages")
            if (reels != null) add("videos" to "Videos")
        }
        FilterChips(tabs, scope, { scope = it })
        val show = { s: String -> scope == "all" || scope == s }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (text.isEmpty()) item { Hint("Try a name, an @username, words from a message, or a #tag.") }
            if (show("people") && (people.isNotEmpty() || creators.isNotEmpty())) {
                item { Section("People") }
                items(people, key = { "p" + it.key }) { f ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(f.handle, f.key, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text("@" + f.handle, style = MaterialTheme.typography.titleMedium); Text("On Meshron", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
                        Box(Modifier.clip(RoundedCornerShape(50)).background(MeshColors.TextPrimary).clickable { onAdd(f) }.padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Add", color = MeshColors.Bg, fontWeight = FontWeight.Bold) }
                    }
                }
                items(creators, key = { "c" + it.ch }) { a ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val img = remember(a.photo) { reels?.image(a.photo) }
                        if (img != null) Image(img, null, Modifier.size(48.dp).clip(RoundedCornerShape(50)), contentScale = ContentScale.Crop) else Avatar(a.name, a.id.ifBlank { a.ch }, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleMedium)
                            Text((if (a.handle.isNotEmpty()) "@${a.handle} · " else "") + "${a.followers} followers", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (show("chats") && chats.isNotEmpty()) {
                item { Section("Chats") }
                items(chats, key = { "h" + it.first }) { (key, name) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenChat(key) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, key, 48.dp, group = key.startsWith(MessageEntity.GROUP_PREFIX))
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (show("messages") && messages.isNotEmpty()) {
                item { Section("Messages") }
                items(messages, key = { "m" + it.peerKey + it.id }) { m ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenChat(m.peerKey) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(nameOf(m.peerKey), m.peerKey, 44.dp, group = m.isGroup)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row { Text(nameOf(m.peerKey), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text(friendlyTime(m.localTime), color = MeshColors.TextTertiary, style = MaterialTheme.typography.labelMedium) }
                            Text(com.xalvxnlab.meshron.ui.chat.displayBody(m), color = MeshColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (show("videos") && videos.isNotEmpty()) {
                item { Section("Videos") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(videos, key = { it.key }) { r ->
                            Box(Modifier.width(120.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp)).background(MeshColors.Surface2)) {
                                val img = remember(r.thumb) { reels?.image(r.thumb) }
                                if (img != null) Image(img, r.cap, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                                    Text(r.cap, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                        Text(" ${r.likes}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (text.length >= 2 && !busy && chats.isEmpty() && messages.isEmpty() && people.isEmpty() && creators.isEmpty() && videos.isEmpty()) item { Hint("Nothing found for \"$text\".") }
        }
    }
}

@Composable private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, color = MeshColors.TextSecondary, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
@Composable private fun Hint(t: String) = androidx.compose.foundation.layout.Column(
    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(MeshColors.Surface2),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Search, null, tint = MeshColors.TextSecondary, modifier = Modifier.size(26.dp)) }
    Text(t, color = MeshColors.TextSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 14.dp).widthIn(max = 280.dp))
}
