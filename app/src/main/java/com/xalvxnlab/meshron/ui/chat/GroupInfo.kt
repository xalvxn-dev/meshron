package com.xalvxnlab.meshron.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.data.GroupEntity
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.ReportSheet
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A group's photo (if it has one) as an image. */
fun groupIcon(g: GroupEntity): ImageBitmap? = if (g.icon.isEmpty()) null else runCatching {
    Base64.decode(g.icon, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
}.getOrNull()

/** Group page: photo, name, bio, members with roles, search, and owner/admin tools. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfo(group: GroupEntity, myKey: String, nameOf: (String) -> String, online: (String) -> Boolean, onBack: () -> Unit, onLeft: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val messaging = app.messagingService
    val scope = rememberCoroutineScope()
    val g by remember(group.id) { messaging.group(group.id) }.collectAsState(initial = group)
    val gr = g ?: group
    val iAmIn = myKey in gr.memberList
    val iAdmin = iAmIn && gr.isAdmin(myKey)
    val iOwner = iAmIn && gr.owner == myKey
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var reportGroup by remember { mutableStateOf(false) }
    var reportWho by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    fun act(msg: String, block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onSuccess { if (msg.isNotEmpty()) com.xalvxnlab.meshron.ui.components.Toasts.show(context, msg) }
            .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Couldn't do that", long = true) }
    }
    val icon = remember(gr.icon) { groupIcon(gr) }
    // Owner first, then admins, then everyone else by name.
    val members = remember(gr.members, gr.admins, gr.ownerKey) {
        gr.memberList.sortedWith(compareBy({ if (it == gr.owner) 0 else if (it in gr.adminList) 1 else 2 }, { if (it == myKey) "" else nameOf(it).lowercase() }))
    }

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        LazyColumn(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().navigationBarsPadding()) {
            item {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    if (iAdmin) IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit group") }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(gr.name, gr.conversationKey, 104.dp, group = true, image = icon)
                    Spacer(Modifier.height(12.dp))
                    Text(gr.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text("Group · ${gr.memberList.size} members", color = MeshColors.TextSecondary)
                    if (gr.bio.isNotBlank()) Text(gr.bio, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction(Icons.Rounded.Search, "Search", Modifier.weight(1f)) { searching = true }
                    if (iAdmin) QuickAction(Icons.Rounded.PersonAdd, "Add", Modifier.weight(1f)) { adding = true }
                    QuickAction(Icons.Rounded.Flag, "Report", Modifier.weight(1f)) { reportGroup = true }
                }
                if (!iAmIn) Text("You're no longer in this group.", color = MeshColors.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Text("${gr.memberList.size} members", style = MaterialTheme.typography.titleSmall, color = MeshColors.TextSecondary, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
            }
            items(members, key = { it }) { k ->
                Box {
                    Row(Modifier.fillMaxWidth().clickable(enabled = k != myKey) { memberMenu = k }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(if (k == myKey) "You" else nameOf(k), k, 44.dp, online = k != myKey && online(k))
                        Spacer(Modifier.width(12.dp))
                        Text(if (k == myKey) "You" else nameOf(k), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val role = if (k == gr.owner) "Owner" else if (k in gr.adminList) "Admin" else null
                        role?.let { Text(it, color = MeshColors.AccentStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MeshColors.AccentContainer).padding(horizontal = 8.dp, vertical = 3.dp)) }
                    }
                    DropdownMenu(expanded = memberMenu == k, onDismissRequest = { memberMenu = null }) {
                        val isAdm = k in gr.adminList
                        if (iOwner) DropdownMenuItem(text = { Text(if (isAdm) "Remove as admin" else "Make admin") }, onClick = {
                            memberMenu = null; act(if (isAdm) "No longer an admin" else "Now an admin") { messaging.setAdmin(gr, k, !isAdm) }
                        })
                        if (iOwner) DropdownMenuItem(text = { Text("Make owner") }, onClick = {
                            memberMenu = null
                            confirm = ("Make ${nameOf(k)} the owner? You'll stay as an admin." to suspend { messaging.transferOwnership(gr, k) })
                        })
                        val canRemove = k != gr.owner && (iOwner || (iAdmin && !isAdm))
                        if (canRemove) DropdownMenuItem(text = { Text("Remove from group", color = MeshColors.Danger) }, onClick = {
                            memberMenu = null
                            confirm = ("Remove ${nameOf(k)} from the group?" to suspend { messaging.removeMember(gr, k) })
                        })
                        DropdownMenuItem(text = { Text("Report") }, onClick = { memberMenu = null; reportWho = k })
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                if (iAmIn) Row(Modifier.fillMaxWidth().clickable { confirmLeave = true }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.ExitToApp, null, tint = MeshColors.Danger)
                    Text("  Leave group", color = MeshColors.Danger, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (editing) EditGroup(gr, onClose = { editing = false }) { name, bio, ic -> act("Saved") { messaging.setGroupInfo(gr, name, bio, ic) } }
    if (adding) AddMembers(gr, onClose = { adding = false }) { keys -> act("Added") { messaging.addMembers(gr, keys) } }
    if (searching) SearchGroup(gr, myKey, nameOf, onClose = { searching = false })
    if (reportGroup) ReportSheet("group", gr.id, gr.name, "Group \"${gr.name}\", ${gr.memberList.size} members. Bio: ${gr.bio}", onClose = { reportGroup = false }, owner = gr.owner)
    reportWho?.let { k -> ReportSheet("person", k, nameOf(k), "Member of group \"${gr.name}\" (${gr.id})", onClose = { reportWho = null }, owner = k) }
    confirm?.let { (q, run) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(q) },
            confirmButton = { TextButton(onClick = { confirm = null; act("") { run() } }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Leave ${gr.name}?") },
        text = { Text(if (iOwner && gr.memberList.size > 1) "You own this group. Make someone else the owner first (tap a member)." else "You'll stop getting its messages. Someone in the group can add you back.") },
        confirmButton = {
            if (!(iOwner && gr.memberList.size > 1)) TextButton(onClick = { confirmLeave = false; act("You left the group") { messaging.leaveGroup(gr); onLeft() } }) { Text("Leave", color = MeshColors.Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(if (iOwner && gr.memberList.size > 1) "OK" else "Cancel") } },
    )
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MeshColors.Surface2).clickable(onClick = onClick).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Owner/admins: photo, name and bio. */
@Composable
private fun EditGroup(g: GroupEntity, onClose: () -> Unit, onSave: (String, String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(g.name) }
    var bio by remember { mutableStateOf(g.bio) }
    var icon by remember { mutableStateOf(g.icon) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            // A small square photo (it travels inside the group's log).
            icon = withContext(Dispatchers.IO) {
                runCatching {
                    val src = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching icon
                    val side = minOf(src.width, src.height)
                    val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                    val small = Bitmap.createScaledBitmap(sq, 160, 160, true)
                    val out = java.io.ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }.getOrDefault(icon)
            }
        }
    }
    val preview = remember(icon) { groupIcon(g.copy(icon = icon)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit group") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Avatar(name, g.conversationKey, 88.dp, group = true, image = preview)
                    Box(Modifier.align(Alignment.BottomEnd).size(30.dp).clip(CircleShape).background(MeshColors.TextPrimary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.CameraAlt, "Change photo", tint = MeshColors.Bg, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Field(name, "Group name") { name = it.take(48) }
                Spacer(Modifier.height(8.dp))
                Field(bio, "Bio (what this group is about)", lines = 3) { bio = it.take(200) }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, bio, icon); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun Field(v: String, hint: String, lines: Int = 1, on: (String) -> Unit) {
    TextField(v, on, placeholder = { Text(hint) }, minLines = lines, singleLine = lines == 1,
        colors = TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
}

/** Owner/admins: add friends who aren't in the group yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMembers(g: GroupEntity, onClose: () -> Unit, onAdd: (List<String>) -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val contacts by app.contactsRepository.contacts.collectAsState(initial = emptyList())
    val options = contacts.filter { it.status == Contact.Status.ADDED && it.keyHex !in g.memberList }
    val picked = remember { mutableStateListOf<String>() }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Add members", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
            if (options.isEmpty()) Text("All your friends are already here.", color = MeshColors.TextSecondary, modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.fillMaxWidth().height(minOf(420, 64 * options.size + 8).dp)) {
                items(options, key = { it.keyHex }) { c ->
                    val on = c.keyHex in picked
                    Row(Modifier.fillMaxWidth().clickable { if (on) picked.remove(c.keyHex) else picked.add(c.keyHex) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.displayName, c.keyHex, 44.dp)
                        Text("  " + c.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) MeshColors.AccentStrong else MeshColors.Surface3), contentAlignment = Alignment.Center) {
                            if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (picked.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(14.dp)).background(MeshColors.TextPrimary).clickable { onAdd(picked.toList()); onClose() }.padding(14.dp), contentAlignment = Alignment.Center) {
                Text("Add ${picked.size}", color = MeshColors.Bg, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Search this group's messages. */
@Composable
private fun SearchGroup(g: GroupEntity, myKey: String, nameOf: (String) -> String, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val all by remember(g.id) { app.messagingService.conversation(g.conversationKey) }.collectAsState(initial = emptyList())
    var q by remember { mutableStateOf("") }
    val hits = remember(q, all) {
        if (q.trim().length < 2) emptyList()
        else all.filter { it.kind == MessageEntity.KIND_TEXT && displayBody(it).contains(q.trim(), ignoreCase = true) }.sortedByDescending { it.sentAt }.take(100)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                TextField(q, { q = it }, placeholder = { Text("Search in ${g.name}") }, singleLine = true,
                    colors = TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)))
            }
            if (q.trim().length >= 2 && hits.isEmpty()) Text("No messages found", color = MeshColors.TextSecondary, modifier = Modifier.padding(20.dp))
            LazyColumn {
                items(hits, key = { it.id }) { m ->
                    val who = if (m.outgoing) "You" else m.senderKey?.let(nameOf) ?: ""
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Text(who + " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.sentAt)),
                            color = MeshColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                        Text(displayBody(m), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
