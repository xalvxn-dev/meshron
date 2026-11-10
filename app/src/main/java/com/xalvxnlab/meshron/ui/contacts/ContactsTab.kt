package com.xalvxnlab.meshron.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpOffset
import com.xalvxnlab.meshron.ui.components.ActionCircle
import com.xalvxnlab.meshron.ui.components.InsetDivider
import com.xalvxnlab.meshron.ui.components.ListRow
import com.xalvxnlab.meshron.ui.components.RowSubtitle
import com.xalvxnlab.meshron.ui.components.SectionHeader
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.input.ImeAction
import com.xalvxnlab.meshron.crypto.ZBase32
import com.xalvxnlab.meshron.crypto.hexToBytes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.ui.components.DarkPill
import com.xalvxnlab.meshron.ui.components.GlassCard
import com.xalvxnlab.meshron.ui.components.GlowButton
import com.xalvxnlab.meshron.ui.components.QrCode
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactParser
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.contacts.ParsedContact
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.PeerInfo
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.EmptyState
import com.xalvxnlab.meshron.ui.components.SectionLabel
import com.xalvxnlab.meshron.ui.home.shareIdentity
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch

@Composable
fun ContactsTab(
    identity: Identity,
    contactsRepo: ContactsRepository,
    network: NetworkService,
    query: String = "",
    onOpen: (String) -> Unit,
    onAddContact: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by contactsRepo.contacts.collectAsState(initial = emptyList())
    val net by network.state.collectAsState()
    var removing by remember { mutableStateOf<Contact?>(null) }
    val isOnline = { c: Contact -> net.peers[c.keyHex]?.state == PeerInfo.State.CONNECTED }

    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) contacts else contacts.filter {
        it.displayName.lowercase().contains(q) || it.handle.contains(q.removePrefix("@"))
    }
    val requests = shown.filter { it.status == Contact.Status.REQUEST }
    val blocked = shown.filter { it.status == Contact.Status.BLOCKED }
    val added = shown.filter { it.status == Contact.Status.ADDED }.sortedBy { it.displayName.lowercase() }
    val online = added.filter(isOnline)
    val offline = added.filterNot(isOnline)
    val block = { c: Contact ->
        scope.launch { contactsRepo.block(c.keyHex) }
        network.disconnect(c.keyHex)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (q.isEmpty()) {
            item {
                ListRow("Add contact", leading = { ActionCircle(Icons.Rounded.PersonAdd) }, titleColor = MeshColors.Accent, onClick = onAddContact,
                    subtitle = { RowSubtitle("By @username, ID or QR code") })
            }
            item { InsetDivider() }
            item {
                ListRow("Invite friends", leading = { ActionCircle(Icons.Rounded.Share) }, titleColor = MeshColors.Accent,
                    onClick = { shareIdentity(context, identity) },
                    subtitle = {
                        RowSubtitle(if (identity.handle.isNotEmpty()) "Send your link, or tell them @${identity.handle}" else "Send them your Meshron link")
                    })
            }
        }

        if (requests.isNotEmpty()) {
            item { SectionHeader("Waiting for you") }
            itemsIndexed(requests, key = { _, c -> c.keyHex }) { i, c ->
                Column(Modifier.animateItem()) {
                    RequestRow(
                        contact = c,
                        onAccept = { scope.launch { contactsRepo.add(c.keyHex, c.name) } },
                        onIgnore = {
                            scope.launch { contactsRepo.remove(c.keyHex) }
                            network.disconnect(c.keyHex)
                        },
                        onPreview = { onOpen(c.keyHex) },
                        onBlock = { block(c) },
                    )
                    if (i < requests.lastIndex) InsetDivider()
                }
            }
        }

        fun section(title: String, list: List<Contact>) {
            if (list.isEmpty()) return
            item(key = "h-$title") { SectionHeader("$title · ${list.size}") }
            itemsIndexed(list, key = { _, c -> c.keyHex }) { i, c ->
                Column(Modifier.animateItem()) {
                    ContactRow(
                        c, isOnline(c),
                        onOpen = { onOpen(c.keyHex) },
                        onRemove = { removing = c },
                        onBlock = { block(c) },
                    )
                    if (i < list.lastIndex) InsetDivider()
                }
            }
        }
        section("Online", online)
        section("Offline", offline)

        if (added.isEmpty() && requests.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    EmptyState(
                        Icons.Outlined.People,
                        if (q.isEmpty()) "No contacts yet" else "No match",
                        if (q.isEmpty()) "Add a friend by their @username, ID or QR code, or send them your link."
                        else "Nobody in your contacts matches \"$query\".",
                    )
                }
            }
        }

        if (blocked.isNotEmpty()) {
            item { SectionHeader("Blocked") }
            itemsIndexed(blocked, key = { _, c -> c.keyHex }) { i, c ->
                Column(Modifier.animateItem()) {
                    ListRow(
                        c.displayName,
                        leading = { Avatar(c.displayName, c.keyHex, 48.dp) },
                        subtitle = { RowSubtitle("Can't message or call you") },
                        trailing = { TextButton(onClick = { scope.launch { contactsRepo.unblock(c.keyHex) } }) { Text("Unblock") } },
                    )
                    if (i < blocked.lastIndex) InsetDivider()
                }
            }
        }
    }

    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${c.displayName}?") },
            text = { Text("You'll stop connecting to them. Your chat history stays. You can add them again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { contactsRepo.remove(c.keyHex) }
                    removing = null
                }) { Text("Remove", color = MeshColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** A contact: tap to chat, long-press for options. */
@Composable
private fun ContactRow(c: Contact, online: Boolean, onOpen: () -> Unit, onRemove: () -> Unit, onBlock: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        ListRow(
            title = c.displayName,
            onClick = onOpen,
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            leading = { Avatar(c.displayName, c.keyHex, 48.dp, online = online) },
            titleExtra = {
                if (c.verified) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Verified, "Verified", Modifier.size(16.dp), tint = MeshColors.Accent)
                }
            },
            subtitle = {
                val handle = c.handle.takeIf { it.isNotEmpty() }?.let { "@$it" }
                when {
                    online -> RowSubtitle(listOfNotNull("online", handle).joinToString(" · "), MeshColors.Online)
                    handle != null -> RowSubtitle(handle)
                    else -> RowSubtitle("offline", MeshColors.TextTertiary)
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(80.dp, 0.dp)) {
            DropdownMenuItem(text = { Text("Message") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Chat, null) }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Block") }, leadingIcon = { Icon(Icons.Rounded.Block, null) }, onClick = { menu = false; onBlock() })
            DropdownMenuItem(
                text = { Text("Remove", color = MeshColors.Danger) },
                leadingIcon = { Icon(Icons.Rounded.PersonRemove, null, tint = MeshColors.Danger) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}

/** Someone who added you: accept, or dismiss; long-press to block. */
@Composable
private fun RequestRow(contact: Contact, onAccept: () -> Unit, onIgnore: () -> Unit, onPreview: () -> Unit, onBlock: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = contact.displayName,
            onClick = onPreview,
            onLongClick = { menu = true },
            leading = { Avatar(contact.displayName, contact.keyHex, 48.dp) },
            subtitle = { RowSubtitle(contact.handle.takeIf { it.isNotEmpty() }?.let { "@$it · added you" } ?: "Added you · ID ${contact.shortId}") },
            trailing = {
                IconButton(onClick = onIgnore) { Icon(Icons.Rounded.Close, "Ignore", tint = MeshColors.TextSecondary) }
                GlowButton(onClick = onAccept, modifier = Modifier.height(36.dp)) {
                    Text("Accept", style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(80.dp, 0.dp)) {
            DropdownMenuItem(
                text = { Text("Block", color = MeshColors.Danger) },
                leadingIcon = { Icon(Icons.Rounded.Block, null, tint = MeshColors.Danger) },
                onClick = { menu = false; onBlock() },
            )
        }
    }
}

/**
 * "Add a contact" as a bottom sheet, like "New chat": a short list of ways to add someone.
 * Picking @username or ID opens its field in the same sheet, with a back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactDialog(
    myKeyHex: String,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
    search: suspend (String) -> NetworkService.HandleResult,
    knownName: (String) -> String? = { null },
    onScan: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // null = list of options, "handle" = search by @username, "id" = paste ID or link
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<NetworkService.HandleResult?>(null) }
    var searched by remember { mutableStateOf("") }
    val parsed = remember(text) { if (text.isBlank()) null else ContactParser.parse(text) }
    val error = when {
        parsed is ParsedContact.Invalid -> parsed.reason
        parsed is ParsedContact.Ok && parsed.keyHex == myKeyHex -> "That's your own ID"
        else -> null
    }
    val ok = (parsed as? ParsedContact.Ok)?.takeIf { error == null }
    val handleProblem = if (handle.isBlank()) null else Identity.handleProblem(handle)
    val canSearch = Identity.isValidHandle(handle) && !searching

    fun runSearch() {
        val h = Identity.cleanHandle(handle)
        if (!Identity.isValidHandle(h) || searching) return
        searching = true
        results = null
        scope.launch {
            results = search(h)
            searched = h
            searching = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            AnimatedContent(targetState = mode, transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false)
            }, label = "addMode") { m ->
                Column {
                    when (m) {
                        null -> {
                            Text(
                                "Add a contact",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                            )
                            SheetAction(Icons.Filled.AlternateEmail, "Search by @username", "Find a friend by the name they picked") { mode = "handle" }
                            SheetAction(Icons.Filled.Link, "Paste ID or link", "The 52-character ID or meshron:// link") { mode = "id" }
                            if (onScan != null) SheetAction(Icons.Filled.QrCodeScanner, "Scan QR code", "Point your camera at their code", onScan)
                            if (onShare != null) {
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                SheetAction(Icons.Filled.Share, "Share my ID instead", "Let them add you", onShare)
                            }
                        }
                        "handle" -> {
                            SheetHeader("Search by @username") { mode = null; results = null }
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                OutlinedTextField(
                                    value = handle,
                                    onValueChange = { handle = it.take(24); results = null },
                                    label = { Text("Their @username") },
                                    prefix = { Text("@") },
                                    singleLine = true,
                                    isError = handleProblem != null,
                                    supportingText = { Text(handleProblem ?: "Works over the internet, while they're online.") },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                GlowButton(onClick = ::runSearch, enabled = canSearch, modifier = Modifier.fillMaxWidth()) {
                                    Spacer(Modifier.weight(1f))
                                    if (searching) {
                                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                        Spacer(Modifier.width(10.dp))
                                        Text("Searching…", color = Color.White, style = MaterialTheme.typography.titleSmall)
                                    } else {
                                        Text(
                                            "Search", style = MaterialTheme.typography.titleSmall,
                                            color = if (canSearch) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            val r = results
                            when {
                                r == null -> Unit
                                r.error == "offline" -> Hint("Searching needs the internet. Turn it on in Me, or add them by ID or QR code.")
                                r.keys.isEmpty() -> Hint("Nobody found as @$searched. Check the spelling, or ask them to open Meshron with internet on.")
                                else -> {
                                    if (r.keys.size > 1) Hint("${r.keys.size} people use @$searched. Ask your friend which ID is theirs.")
                                    Spacer(Modifier.height(4.dp))
                                    r.keys.forEach { key ->
                                        val mine = key == myKeyHex
                                        val id = ZBase32.encode(key.hexToBytes())
                                        ListItem(
                                            headlineContent = { Text(knownName(key) ?: "@$searched") },
                                            supportingContent = {
                                                Text(if (mine) "That's you" else "ID " + id.take(6) + "…" + id.takeLast(4), fontFamily = FontFamily.Monospace)
                                            },
                                            leadingContent = { Avatar(knownName(key) ?: searched, key, 40.dp) },
                                            trailingContent = {
                                                if (!mine) Text("Add", color = MeshColors.PurpleBright, style = MaterialTheme.typography.labelLarge)
                                            },
                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                            modifier = Modifier.clickable(enabled = !mine) { onAdd(key, knownName(key).orEmpty()) },
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            SheetHeader("Paste ID or link") { mode = null }
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    label = { Text("Their ID or link") },
                                    isError = error != null,
                                    supportingText = {
                                        Text(
                                            when {
                                                error != null -> error
                                                ok != null -> "Looks good ✓"
                                                else -> "52 letters and numbers, or a meshron:// link"
                                            }
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it.trim() }
                                        }) { Icon(Icons.Filled.ContentPaste, "Paste") }
                                    },
                                    minLines = 2,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { if (it.length <= 32) name = it },
                                    label = { Text("Name (optional)") },
                                    placeholder = { Text(ok?.name?.ifBlank { null } ?: "We'll use the name they chose") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(12.dp))
                                GlowButton(
                                    onClick = { ok?.let { onAdd(it.keyHex, name.trim().ifBlank { it.name }) } },
                                    enabled = ok != null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "Add contact", style = MaterialTheme.typography.titleSmall,
                                        color = if (ok != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A row in the sheet, same look as in "New chat". */
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
private fun SheetHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
