package com.xalvxnlab.meshron.ui.chat

import androidx.compose.ui.draw.shadow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.xalvxnlab.meshron.ui.components.SafetyCodeDialog
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.text.style.TextOverflow
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.dayLabel
import com.xalvxnlab.meshron.ui.components.sameDay
import com.xalvxnlab.meshron.ui.theme.MeshColors
import com.xalvxnlab.meshron.MeshronApp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.data.GroupEntity
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.files.FileInfo
import com.xalvxnlab.meshron.files.FileService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.xalvxnlab.meshron.messaging.MessagingService
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.PeerInfo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** What the chat screen shows: a 1:1 chat with a contact, or a group. */
sealed interface ChatTarget {
    val conversation: String

    data class Direct(val contact: Contact) : ChatTarget {
        override val conversation get() = contact.keyHex
    }

    data class Group(val group: GroupEntity) : ChatTarget {
        override val conversation get() = group.conversationKey
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    target: ChatTarget,
    myKey: String,
    nameOf: (String) -> String,
    messaging: MessagingService,
    network: NetworkService,
    files: FileService,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fileStatus by files.status.collectAsState()
    var sendingFile by remember { mutableStateOf(false) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            sendingFile = true
            files.send(messaging, target.conversation, uri).onFailure {
                Toast.makeText(context, it.message ?: "Couldn't send the file", Toast.LENGTH_LONG).show()
            }
            sendingFile = false
        }
    }
    val app = context.applicationContext as MeshronApp
    val haptics = LocalHapticFeedback.current
    val calls = app.calls
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && target is ChatTarget.Direct) calls.call(target.contact.keyHex)
        else if (!ok) Toast.makeText(context, "Calls need the microphone", Toast.LENGTH_LONG).show()
    }
    val askVideo = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val mic = r[Manifest.permission.RECORD_AUDIO] == true || calls.hasMicPermission()
        if (!mic) Toast.makeText(context, "Calls need the microphone", Toast.LENGTH_LONG).show()
        else if (target is ChatTarget.Direct) {
            // Without the camera it becomes a voice call; the camera can be allowed later.
            calls.call(target.contact.keyHex, video = r[Manifest.permission.CAMERA] == true)
        }
    }
    val conversation = target.conversation
    val messages by remember(conversation) { messaging.conversation(conversation) }
        .collectAsState(initial = emptyList())
    val acks by remember(conversation) {
        if (target is ChatTarget.Group) messaging.groupAcks(target.group.id) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val net by network.state.collectAsState()
    var draft by rememberSaveable(conversation) { mutableStateOf("") }
    var showMembers by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showSafety by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmBlock by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Keep the newest message in view and clear the unread badge while the chat is open.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(0) // reverse layout: 0 = newest, at the bottom
        messaging.markRead(conversation)
    }

    val online = { key: String -> net.peers[key]?.state == PeerInfo.State.CONNECTED }
    val (title, subtitle) = when (target) {
        is ChatTarget.Direct -> {
            val peer = net.peers[target.contact.keyHex]
            target.contact.displayName to when {
                target.contact.status == Contact.Status.REQUEST -> "Not in your contacts yet"
                peer?.state == PeerInfo.State.CONNECTED -> (if (target.contact.verified) "Verified · " else "") + "Online · ${peer.viaLabel}"
                else -> "Offline · they'll get messages later"
            }
        }
        is ChatTarget.Group -> {
            val others = target.group.memberList.filter { it != myKey }
            target.group.name to "${others.size + 1} members · ${others.count(online)} online"
        }
    }
    val ackByMember = acks.associate { it.member to it.length }
    val isRequest = target is ChatTarget.Direct && target.contact.status == Contact.Status.REQUEST

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Row(
                        Modifier.clickable(enabled = target is ChatTarget.Group) { showMembers = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            title, conversation, 40.dp,
                            group = target is ChatTarget.Group,
                            online = target is ChatTarget.Direct && online(target.contact.keyHex),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (target is ChatTarget.Direct && !isRequest) {
                        IconButton(onClick = {
                            if (calls.hasMicPermission() && calls.hasCameraPermission()) calls.call(target.contact.keyHex, video = true)
                            else askVideo.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                        }) { Icon(Icons.Filled.Videocam, "Video call") }
                        IconButton(onClick = {
                            if (calls.hasMicPermission()) calls.call(target.contact.keyHex)
                            else askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Icon(Icons.Filled.Call, "Voice call") }
                    }
                    if (target is ChatTarget.Group) {
                        IconButton(onClick = { showMembers = true }) { Icon(Icons.Filled.People, "Members") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (target is ChatTarget.Direct && !isRequest) {
                                DropdownMenuItem(
                                    text = { Text(if (target.contact.verified) "Safety code (verified)" else "Verify safety code") },
                                    leadingIcon = { Icon(Icons.Filled.VerifiedUser, null) },
                                    onClick = { menu = false; showSafety = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete chat") },
                                leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                            if (target is ChatTarget.Direct) {
                                DropdownMenuItem(
                                    text = { Text("Block ${target.contact.displayName}", color = MeshColors.Danger) },
                                    leadingIcon = { Icon(Icons.Filled.Block, null, tint = MeshColors.Danger) },
                                    onClick = { menu = false; confirmBlock = true },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MeshColors.Surface1),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (target is ChatTarget.Group) "Say hello to the group" else "Say hello to $title",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (target is ChatTarget.Group) "Only group members can read what you send here."
                            else "Messages are end-to-end encrypted and stored only on your two phones. " +
                                "If they're offline, they'll get them when you next meet.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val ordered = messages.asReversed()
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    // Anchored at the bottom, so photos that load late don't push new messages off screen.
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(ordered, key = { _, m -> m.id + m.outgoing }) { i, m ->
                        val status = when {
                            !m.outgoing -> ""
                            m.status == MessageEntity.PENDING -> "Sending"
                            target is ChatTarget.Group -> {
                                val others = target.group.memberList.filter { it != myKey }
                                val got = others.count { (ackByMember[it] ?: 0L) > (m.seq ?: Long.MAX_VALUE) }
                                if (got == 0) "Sent" else "Delivered to $got of ${others.size}"
                            }
                            m.status == MessageEntity.SENT -> "Sent"
                            else -> "Delivered"
                        }
                        // Reverse layout: the item drawn above this one is the next index.
                        val older = ordered.getOrNull(i + 1)
                        val newer = ordered.getOrNull(i - 1)
                        val newDay = older == null || !sameDay(older.localTime, m.localTime)
                        // Messages from the same person within a few minutes form one group.
                        val first = newDay || older == null || !sameGroup(older, m)
                        val last = newer == null || !sameGroup(m, newer) || !sameDay(m.localTime, newer.localTime)
                        Column(
                            Modifier.animateItem(fadeInSpec = tween(250), placementSpec = spring(stiffness = 400f))
                                .padding(top = if (first) 8.dp else 2.dp),
                        ) {
                            if (newDay) DaySeparator(dayLabel(m.localTime))
                            Bubble(
                                m = m,
                                first = first,
                                last = last,
                                sender = if (target is ChatTarget.Group && !m.outgoing && first) m.senderKey?.let(nameOf) else null,
                                status = status,
                                fileStatus = fileStatus[FileService.keyOf(m)],
                                onOpenFile = { openFile(context, m) },
                                onCopy = {
                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                        .setPrimaryClip(android.content.ClipData.newPlainText("Message", displayBody(m)))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                },
                                onDelete = { scope.launch { messaging.deleteMessage(m) } },
                            )
                        }
                    }
                }
            }
            if (isRequest && target is ChatTarget.Direct) {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(16.dp).navigationBarsPadding(),
                ) {
                    Text(
                        "${target.contact.displayName} isn't in your contacts. Accept to reply, or ignore to stop them writing to you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { app.contactsRepository.add(target.contact.keyHex, target.contact.name) } },
                            colors = ButtonDefaults.buttonColors(containerColor = MeshColors.Accept, contentColor = Color.White),
                        ) { Text("Accept") }
                        FilledTonalButton(onClick = {
                            scope.launch { app.contactsRepository.remove(target.contact.keyHex) }
                            network.disconnect(target.contact.keyHex)
                            onBack()
                        }) { Text("Ignore") }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().background(MeshColors.Bg)) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(MeshColors.Hairline))
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).navigationBarsPadding(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        IconButton(enabled = !sendingFile, onClick = { pickFile.launch("*/*") }) {
                            if (sendingFile) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.AddCircleOutline, "Send a photo or file", tint = MeshColors.TextSecondary)
                        }
                        TextField(
                            value = draft,
                            onValueChange = { if (it.length <= MessagingService.MAX_BODY) draft = it },
                            placeholder = { Text("Message", color = MeshColors.TextTertiary) },
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MeshColors.Surface2,
                                unfocusedContainerColor = MeshColors.Surface2,
                                cursorColor = MeshColors.GradMid,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        )
                        // The send button appears only when there's something to send.
                        AnimatedVisibility(
                            visible = draft.isNotBlank(),
                            enter = scaleIn(tween(160)) + fadeIn(tween(160)),
                            exit = scaleOut(tween(120)) + fadeOut(tween(120)),
                        ) {
                            Row {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.size(48.dp)
                                        .shadow(10.dp, CircleShape, ambientColor = MeshColors.GradMid, spotColor = MeshColors.GradMid)
                                        .clip(CircleShape)
                                        .background(MeshColors.Gradient)
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val text = draft
                                            draft = ""
                                            scope.launch { messaging.send(conversation, text) }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.Send, "Send", Modifier.size(22.dp), tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSafety && target is ChatTarget.Direct) {
        SafetyCodeDialog(
            name = target.contact.displayName,
            myKeyHex = myKey,
            theirKeyHex = target.contact.keyHex,
            verified = target.contact.verified,
            onSetVerified = { v -> scope.launch { app.contactsRepository.setVerified(target.contact.keyHex, v) } },
            onDismiss = { showSafety = false },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this chat?") },
        text = { Text("All messages and files in it are removed from this phone. The other side keeps their copy. This can't be undone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { messaging.deleteConversation(conversation) }
            }) { Text("Delete", color = MeshColors.Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    if (confirmBlock && target is ChatTarget.Direct) AlertDialog(
        onDismissRequest = { confirmBlock = false },
        title = { Text("Block ${target.contact.displayName}?") },
        text = { Text("They won't be able to message or call you, and you won't connect to them. They aren't told. You can unblock them in Contacts.") },
        confirmButton = {
            TextButton(onClick = {
                confirmBlock = false
                scope.launch { app.contactsRepository.block(target.contact.keyHex) }
                network.disconnect(target.contact.keyHex)
                onBack()
            }) { Text("Block", color = MeshColors.Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel") } },
    )

    if (showMembers && target is ChatTarget.Group) {
        AlertDialog(
            onDismissRequest = { showMembers = false },
            title = { Text(target.group.name) },
            text = {
                Column {
                    target.group.memberList.forEach { key ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val on = key == myKey || online(key)
                            Avatar(if (key == myKey) "You" else nameOf(key), key, 36.dp, online = on && key != myKey)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                (if (key == myKey) "You" else nameOf(key)) +
                                    if (key == target.group.creatorKey) " · creator" else ""
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMembers = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: MessageEntity,
    first: Boolean,
    last: Boolean,
    sender: String?,
    status: String,
    fileStatus: String?,
    onOpenFile: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val mine = m.outgoing
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val textColor = if (mine) Color.White else MeshColors.TextPrimary
    val metaColor = if (mine) Color.White.copy(alpha = 0.7f) else MeshColors.TextTertiary
    // Rounded all round, except the author's side tightens where the group continues.
    val big = 18.dp
    val small = 4.dp
    val shape = if (mine) RoundedCornerShape(topStart = big, bottomStart = big, topEnd = if (first) big else small, bottomEnd = if (last) big else small)
    else RoundedCornerShape(topEnd = big, bottomEnd = big, topStart = if (first) big else small, bottomStart = if (last) big else small)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .then(if (mine) Modifier.background(MeshColors.BubbleGradient) else Modifier.background(MeshColors.Surface2))
                .combinedClickable(
                    onClick = { if (m.kind == MessageEntity.KIND_FILE) onOpenFile() },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                )
                .padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 6.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (m.kind != MessageEntity.KIND_FILE) {
                    DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; onCopy() })
                }
                DropdownMenuItem(
                    text = { Text("Delete for me") },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
            if (sender != null) {
                Text(
                    sender,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MeshColors.avatar(m.senderKey ?: sender),
                )
            }
            if (m.kind == MessageEntity.KIND_FILE) {
                FileContent(m, fileStatus)
            } else {
                Text(displayBody(m), color = textColor, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.sentAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor,
                )
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    when (status) {
                        "Sending" -> Icon(Icons.Filled.Schedule, "Sending", Modifier.size(14.dp), tint = metaColor)
                        "Sent" -> Icon(Icons.Filled.Done, "Sent", Modifier.size(14.dp), tint = metaColor)
                        "Delivered" -> Icon(Icons.Filled.DoneAll, "Delivered", Modifier.size(14.dp), tint = Color.White)
                        else -> Text(status, style = MaterialTheme.typography.labelSmall, color = metaColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileContent(m: MessageEntity, fileStatus: String?) {
    val info = remember(m.body) { FileInfo.parse(m.body) }
    if (info == null) {
        Text("Unreadable file message", color = MaterialTheme.colorScheme.error)
        return
    }
    val path = m.localPath
    val thumb by produceState<ImageBitmap?>(null, path) {
        value = if (path != null && info.isImage) withContext(Dispatchers.IO) { loadThumbnail(path) } else null
    }
    Column {
        thumb?.let {
            Image(
                it, contentDescription = info.name,
                modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(info.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Text(
            humanSize(info.size) + " · " + when {
                path != null -> "Tap to open"
                m.outgoing -> "Not on this device"
                else -> fileStatus ?: "Waiting to download"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun loadThumbnail(path: String): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 720 || bounds.outHeight / sample > 720) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun openFile(context: Context, m: MessageEntity) {
    val path = m.localPath ?: return
    val info = FileInfo.parse(m.body) ?: return
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", File(path))
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, info.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, info.name))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

/** Invites are stored as JSON; show them as a sentence. */
fun displayBody(m: MessageEntity): String = when (m.kind) {
    MessageEntity.KIND_FILE -> FileInfo.parse(m.body)?.let { (if (it.isImage) "Photo: " else "File: ") + it.name } ?: "File"
    MessageEntity.KIND_INVITE -> {
        val name = MessagingService.parseInvite(m.body)?.name ?: "a group"
        if (m.outgoing) "You invited them to “$name”" else "Added you to the group “$name”"
    }
    else -> m.body
}

/** Same author, sent within 3 minutes: shown as one tight group of bubbles. */
private fun sameGroup(a: MessageEntity, b: MessageEntity): Boolean =
    a.outgoing == b.outgoing && a.senderKey == b.senderKey && kotlin.math.abs(b.localTime - a.localTime) < 3 * 60_000
