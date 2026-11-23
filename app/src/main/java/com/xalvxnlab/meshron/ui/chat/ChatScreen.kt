package com.xalvxnlab.meshron.ui.chat

import androidx.compose.material.icons.filled.LocationOn

import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Translate

import androidx.compose.foundation.gestures.detectHorizontalDragGestures

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.systemBarsPadding

import androidx.compose.ui.draw.drawBehind

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.focus.focusRequester
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    // Picked files are shown first (photo preview, name, size, caption) and sent on "Send".
    var pendingFile by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingFile = uri
    }
    val app = context.applicationContext as MeshronApp
    val haptics = LocalHapticFeedback.current
    val calls = app.calls
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && target is ChatTarget.Direct) calls.call(target.contact.keyHex)
        else if (!ok) com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Calls need the microphone", long = true)
    }
    val askVideo = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val mic = r[Manifest.permission.RECORD_AUDIO] == true || calls.hasMicPermission()
        if (!mic) com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Calls need the microphone", long = true)
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
    val liveGroup by remember(conversation) {
        if (target is ChatTarget.Group) messaging.group(target.group.id) else flowOf(null)
    }.collectAsState(initial = null)
    val grp = (target as? ChatTarget.Group)?.let { liveGroup ?: it.group }
    val grpIcon = remember(grp?.icon) { grp?.let { groupIcon(it) } }
    val inGroup = grp == null || myKey in grp.memberList
    var reportChat by remember { mutableStateOf(false) }
    var draft by rememberSaveable(conversation) { mutableStateOf("") }
    val mods by remember(conversation) { messaging.modifiers(conversation) }.collectAsState(initial = emptyList())
    val extras = remember(mods, myKey) { summarize(mods, myKey) }
    var replyTo by remember(conversation) { mutableStateOf<MessageEntity?>(null) }
    var editing by remember(conversation) { mutableStateOf<MessageEntity?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var lastTyping by remember { mutableStateOf(0L) }
    var urgentMenu by remember { mutableStateOf(false) }
    fun sendNow(urgent: Boolean) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val text = draft
        val edit = editing
        val reply = replyTo
        draft = ""
        editing = null
        replyTo = null
        lastTyping = 0L
        scope.launch {
            when {
                edit != null -> messaging.edit(edit, text)
                urgent -> messaging.sendUrgent(conversation, text, reply)
                else -> messaging.send(conversation, text, reply)
            }
            if (edit == null && target is ChatTarget.Group && com.xalvxnlab.meshron.account.MeshronAi.tagged(text)) {
                val app = context.applicationContext as com.xalvxnlab.meshron.MeshronApp
                val ctx = messages.filter { it.kind == MessageEntity.KIND_TEXT }.takeLast(12).map { (if (it.outgoing) "Me" else "Member") + ": " + displayBody(it) }
                runCatching { app.ai.ask(conversation, java.util.UUID.randomUUID().toString().take(12), text, context = ctx) }
                    .onSuccess { messaging.send(conversation, com.xalvxnlab.meshron.account.MeshronAi.body(it)) }
                    .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Meshron AI couldn't answer", long = true) }
            }
        }
    }
    val typingNow by network.typing.collectAsState()
    val theyAreTyping = target is ChatTarget.Direct && typingNow.containsKey(target.contact.keyHex)
    val recordingNow by network.recording.collectAsState()
    val theyAreRecording = target is ChatTarget.Direct && recordingNow.containsKey(target.contact.keyHex)
    val chatApp = LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
    val readReceipts by chatApp.settings.readReceipts.collectAsState(initial = true)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var showMembers by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showSafety by rememberSaveable { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmBlock by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Keep the newest message in view and clear the unread badge while the chat is open.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(0) // reverse layout: 0 = newest, at the bottom
        messaging.markRead(conversation)
    }
    // "Seen": tell a friend I've read their messages while the chat is open (and again when they come online).
    val peerOnline = target is ChatTarget.Direct && net.peers[target.contact.keyHex]?.state == PeerInfo.State.CONNECTED
    LaunchedEffect(messages.size, peerOnline, readReceipts) {
        if (target is ChatTarget.Direct && readReceipts && peerOnline && target.contact.status == Contact.Status.ADDED && !com.xalvxnlab.meshron.identity.Mode.anon)
            runCatching { messaging.sendSeen(target.contact.keyHex) }
    }

    val online = { key: String -> net.peers[key]?.state == PeerInfo.State.CONNECTED }
    val (title, subtitle) = when (target) {
        is ChatTarget.Direct -> {
            val peer = net.peers[target.contact.keyHex]
            target.contact.displayName to when {
                target.contact.status == Contact.Status.REQUEST -> "Not in your contacts yet"
                theyAreRecording -> "recording a voice note…"
                theyAreTyping -> "typing…"
                peer?.state == PeerInfo.State.CONNECTED -> (if (target.contact.verified) "Verified · " else "") + "Online · ${peer.viaLabel}"
                else -> "Offline · they'll get messages later"
            }
        }
        is ChatTarget.Group -> {
            val g = grp ?: target.group
            val others = g.memberList.filter { it != myKey }
            g.name to if (myKey !in g.memberList) "You're not in this group" else (if (g.adult) "18+ · " else "") + "${others.size + 1} members · ${others.count(online)} online"
        }
    }
    val ackByMember = acks.associate { it.member to it.length }
    val isRequest = target is ChatTarget.Direct && target.contact.status == Contact.Status.REQUEST
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var stickerOpen by remember { mutableStateOf(false) }
    var recordingVoice by remember { mutableStateOf(false) }
    LaunchedEffect(recordingVoice) {
        if (target !is ChatTarget.Direct || com.xalvxnlab.meshron.identity.Mode.anon) return@LaunchedEffect
        while (recordingVoice) { network.typing(target.contact.keyHex, "recording"); kotlinx.coroutines.delay(4000) }
    }
    var suggesting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Row(
                        Modifier.clickable { if (target is ChatTarget.Group) showMembers = true else showProfile = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            title, conversation, 40.dp,
                            group = target is ChatTarget.Group,
                            online = target is ChatTarget.Direct && online(target.contact.keyHex),
                            image = grpIcon,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (target is ChatTarget.Direct) com.xalvxnlab.meshron.ui.components.Badges(target.contact.keyHex, 12.dp)
                            }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (theyAreTyping) MeshColors.GradMid else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            DropdownMenuItem(
                                text = { Text(if (target is ChatTarget.Group) "Report group" else "Report") },
                                leadingIcon = { Icon(Icons.Filled.Flag, null) },
                                onClick = { menu = false; reportChat = true },
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MeshColors.Bg),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).drawBehind {
                // Meshron's chat wallpaper: faint diagonal mesh lines.
                val step = 34.dp.toPx()
                val c = MeshColors.TextPrimary.copy(alpha = 0.035f)
                var x = -size.height
                while (x < size.width) {
                    drawLine(c, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x + size.height, size.height), 1f)
                    drawLine(c, androidx.compose.ui.geometry.Offset(x + size.height, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                    x += step
                }
            }) {
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
                    val lastOutId = ordered.firstOrNull { it.outgoing }?.id
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
                            m.status >= MessageEntity.SEEN && readReceipts -> if (m.id == lastOutId) "SeenLast" else "Seen"
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
                            // Calls: a small centred note, like WhatsApp.
                            if (m.kind == MessageEntity.KIND_CALL) { DaySeparator(MessageEntity.callText(m.body, m.outgoing) + " · " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(m.localTime))); return@Column }
                            val quote = remember(m.body) { MessagingService.REPLY.find(m.body)?.groupValues?.get(1) }
                            // Who wrote the quoted message: the newest earlier message it matches.
                            val quoted = remember(quote, ordered.size) {
                                quote?.let { q -> ordered.drop(i + 1).firstOrNull { MessagingService.quoteOf(it) == q } }
                            }
                            Bubble(
                                m = m,
                                first = first,
                                last = last,
                                sender = if (target is ChatTarget.Group && !m.outgoing && first) m.senderKey?.let(nameOf) else null,
                                status = status,
                                fileStatus = fileStatus[FileService.keyOf(m)],
                                reactions = extras.reactions[m.id].orEmpty(),
                                myKey = myKey,
                                edited = m.id in extras.edited,
                                quoteAuthor = quoted?.let { q -> if (q.outgoing) "You" else if (target is ChatTarget.Group) q.senderKey?.let(nameOf) else title },
                                highlighted = flash == m.id,
                                canEdit = m.outgoing && m.kind == MessageEntity.KIND_TEXT && System.currentTimeMillis() - m.sentAt < EDIT_WINDOW_MS,
                                onOpenFile = { openFile(context, m) },
                                onCopy = {
                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                        .setPrimaryClip(android.content.ClipData.newPlainText("Message", displayBody(m)))
                                    com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Copied")
                                },
                                onDelete = { scope.launch { messaging.deleteMessage(m) } },
                                onUnsend = { scope.launch { if (m.outgoing) messaging.unsend(m) else grp?.let { messaging.deleteForEveryone(it, m) } } },
                                canModerate = grp != null && !m.outgoing && inGroup && grp.isAdmin(myKey) && (grp.owner == myKey || m.senderKey != grp.owner) &&
                                    (m.kind == MessageEntity.KIND_TEXT || m.kind == MessageEntity.KIND_FILE),
                                onReply = {
                                    editing = null
                                    replyTo = m
                                    runCatching { focus.requestFocus() }
                                },
                                onEdit = {
                                    replyTo = null
                                    editing = m
                                    draft = MessagingService.bodyText(m.body)
                                    runCatching { focus.requestFocus() }
                                },
                                onReact = { e -> scope.launch { messaging.react(m, e) } },
                                onQuoteTap = {
                                    val idx = quoted?.let { q -> ordered.indexOfFirst { it.id == q.id } } ?: -1
                                    if (idx >= 0) scope.launch {
                                        listState.animateScrollToItem(idx)
                                        flash = quoted!!.id
                                        kotlinx.coroutines.delay(1400)
                                        flash = null
                                    }
                                },
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
            } else if (!inGroup) {
                Text("You're no longer in this group, so you can't send messages.", color = MeshColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp))
            } else {
                Column(Modifier.fillMaxWidth()) {
                    // AI reply suggestions: tap one to put it in the box.
                    if (suggestions.isNotEmpty()) androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        items(suggestions.size) { i ->
                            val text = suggestions[i]
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(MeshColors.AccentContainer)
                                    .clickable { if (!text.startsWith("⚠")) draft = text; suggestions = emptyList() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) { Text(text, color = MeshColors.OnAccentContainer, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                        }
                    }
                    if (recordingVoice) Text("● Recording… let go to send", color = MeshColors.Danger, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
                    if (stickerOpen) Box(Modifier.padding(horizontal = 12.dp)) { Stickers.Picker(onPick = { st -> stickerOpen = false; scope.launch { messaging.send(conversation, st) } }) }
                    val bar = editing ?: replyTo
                    AnimatedVisibility(visible = bar != null) {
                        val m = bar ?: return@AnimatedVisibility
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(MeshColors.Gradient))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (editing != null) "Editing message" else "Replying to " + when {
                                        m.outgoing -> "yourself"
                                        target is ChatTarget.Group -> m.senderKey?.let(nameOf) ?: "them"
                                        else -> title
                                    },
                                    style = MaterialTheme.typography.labelLarge, color = MeshColors.GradMid,
                                )
                                Text(
                                    MessagingService.quoteOf(m), style = MaterialTheme.typography.bodyMedium,
                                    color = MeshColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = {
                                if (editing != null) draft = ""
                                editing = null
                                replyTo = null
                            }) { Icon(Icons.Filled.Close, "Cancel", tint = MeshColors.TextSecondary) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp)
                            .shadow(12.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.15f), spotColor = Color.Black.copy(alpha = 0.15f))
                            .clip(RoundedCornerShape(28.dp)).background(MeshColors.Surface1)
                            .border(1.dp, MeshColors.Hairline, RoundedCornerShape(28.dp)).padding(4.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        if (!com.xalvxnlab.meshron.identity.Mode.anon && !isRequest) IconButton(onClick = {
                            suggesting = true
                            suggestions = emptyList()
                            scope.launch {
                                val recent = messages.filter { it.kind == MessageEntity.KIND_TEXT }.takeLast(8).map { com.xalvxnlab.meshron.account.AiService.Turn(it.outgoing, displayBody(it)) }
                                suggestions = runCatching { app.ai.replies(recent) }.getOrElse { listOf("⚠ " + (it.message ?: "AI unavailable")) }
                                suggesting = false
                            }
                        }) {
                            if (suggesting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.AutoAwesome, "Suggest replies", tint = MeshColors.Accent)
                        }
                        IconButton(enabled = !sendingFile, onClick = { pickFile.launch("*/*") }) {
                            if (sendingFile) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.AddCircleOutline, "Send a photo or file", tint = MeshColors.TextSecondary)
                        }
                        TextField(
                            value = draft,
                            onValueChange = {
                                if (it.length <= MessagingService.MAX_BODY) draft = it
                                // "typing…" for the other person, at most every 4 seconds.
                                val now = System.currentTimeMillis()
                                if (target is ChatTarget.Direct && !com.xalvxnlab.meshron.identity.Mode.anon && it.isNotBlank() && now - lastTyping > 4000) {
                                    lastTyping = now
                                    network.typing(target.contact.keyHex)
                                }
                            },
                            placeholder = { Text("Message", color = MeshColors.TextTertiary) },
                            maxLines = 6,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                cursorColor = MeshColors.GradMid,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(focus),
                        )
                        if (!isRequest) IconButton(onClick = { stickerOpen = !stickerOpen }) {
                            Icon(Icons.Filled.EmojiEmotions, "Stickers", tint = if (stickerOpen) MeshColors.Accent else MeshColors.TextSecondary)
                        }
                        // Voice note: hold the mic to record, let go to send.
                        if (draft.isBlank() && !isRequest) Box(
                            Modifier.size(48.dp).clip(CircleShape).background(if (recordingVoice) MeshColors.Danger else MeshColors.Surface2)
                                .pointerInput(conversation) {
                                    detectTapGestures(onPress = {
                                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            askMic.launch(Manifest.permission.RECORD_AUDIO); return@detectTapGestures
                                        }
                                        val rec = VoiceRecorder.start(context) ?: return@detectTapGestures
                                        recordingVoice = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        tryAwaitRelease()
                                        recordingVoice = false
                                        val file = rec.stop()
                                        if (file != null) scope.launch {
                                            sendingFile = true
                                            files.send(messaging, conversation, VoiceRecorder.uriOf(context, file))
                                                .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Couldn't send the voice note", long = true) }
                                            sendingFile = false
                                        } else com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Hold the mic to record")
                                    })
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Mic, "Hold to record a voice note", tint = if (recordingVoice) Color.White else MeshColors.TextSecondary) }
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
                                        .combinedClickable(
                                            onClick = { sendNow(false) },
                                            // Hold Send: urgent (1:1 chats).
                                            onLongClick = {
                                                if (editing == null && target is ChatTarget.Direct) {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    urgentMenu = true
                                                }
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (editing != null) Icon(Icons.Filled.Done, "Save", Modifier.size(24.dp), tint = Color.White)
                                    else Icon(Icons.AutoMirrored.Rounded.Send, "Send", Modifier.size(22.dp), tint = Color.White)
                                    DropdownMenu(expanded = urgentMenu, onDismissRequest = { urgentMenu = false }) {
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("Send urgently", fontWeight = FontWeight.SemiBold)
                                                    Text("Every Meshron phone nearby passes it on", style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            },
                                            leadingIcon = { Icon(Icons.Filled.Bolt, null, tint = Color(0xFFFFB020)) },
                                            onClick = { urgentMenu = false; sendNow(true) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingFile?.let { uri ->
        FilePreviewDialog(
            uri = uri,
            onCancel = { pendingFile = null },
            onSend = { caption ->
                pendingFile = null
                scope.launch {
                    sendingFile = true
                    files.send(messaging, conversation, uri)
                        .onSuccess { if (caption.isNotBlank()) messaging.send(conversation, caption) }
                        .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Couldn't send the file", long = true) }
                    sendingFile = false
                }
            },
        )
    }

    if (showProfile && target is ChatTarget.Direct) {
        com.xalvxnlab.meshron.ui.profile.PersonSheet(
            contact = target.contact,
            online = online(target.contact.keyHex),
            onDismiss = { showProfile = false },
            onMessage = null,
            onCall = if (isRequest) null else ({ showProfile = false; if (calls.hasMicPermission()) calls.call(target.contact.keyHex) else askMic.launch(Manifest.permission.RECORD_AUDIO) }),
            onVideo = if (isRequest) null else ({ showProfile = false; if (calls.hasMicPermission() && calls.hasCameraPermission()) calls.call(target.contact.keyHex, video = true) else askVideo.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) }),
            onVerify = { showProfile = false; showSafety = true },
        )
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

    if (showMembers && grp != null) {
        GroupInfo(grp, myKey, nameOf, online, onBack = { showMembers = false }, onLeft = { showMembers = false })
    }
    if (reportChat) {
        val recent = messages.filter { it.kind == MessageEntity.KIND_TEXT }.takeLast(10).joinToString("\n") { (if (it.outgoing) "me" else (it.senderKey?.let(nameOf) ?: title)) + ": " + displayBody(it) }
        com.xalvxnlab.meshron.ui.components.ReportSheet(
            if (target is ChatTarget.Group) "group" else "person",
            if (target is ChatTarget.Direct) target.contact.keyHex else grp?.id.orEmpty(), title,
            "Chat with ${title}. Last messages:\n" + recent, onClose = { reportChat = false },
            owner = if (target is ChatTarget.Direct) target.contact.keyHex else grp?.owner.orEmpty(), content = recent,
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

/** Reactions offered on long-press (any emoji works on the wire). */
private val QUICK_REACTIONS = listOf("❤️", "😂", "😮", "😢", "🙏", "👍")

/** Own text messages can be edited for this long after sending. */
const val EDIT_WINDOW_MS = 15 * 60_000L

/** Reactions (row ID -> [(who, emoji)]) and which rows were edited, from a chat's modifier rows. */
class ChatExtras(val reactions: Map<String, List<Pair<String, String>>>, val edited: Set<String>)

fun summarize(mods: List<MessageEntity>, myKey: String): ChatExtras {
    val latest = LinkedHashMap<Pair<String, String>, String>() // (row ID, who) -> emoji; newest wins
    val edited = HashSet<String>()
    for (r in mods) {
        val o = runCatching { org.json.JSONObject(r.body) }.getOrNull() ?: continue
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
        val who = if (r.outgoing) myKey else (r.senderKey ?: r.peerKey)
        when (r.kind) {
            MessageEntity.KIND_EDIT -> edited += if (r.isGroup) MessageEntity.groupRowId(who, id) else id
            MessageEntity.KIND_REACT -> {
                latest.remove(id to who)
                val e = o.optString("e")
                if (e.isNotEmpty()) latest[id to who] = e
            }
        }
    }
    return ChatExtras(latest.entries.groupBy({ it.key.first }, { it.key.second to it.value }), edited)
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
    reactions: List<Pair<String, String>>,
    myKey: String,
    edited: Boolean,
    quoteAuthor: String?,
    highlighted: Boolean,
    canEdit: Boolean,
    onOpenFile: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onUnsend: () -> Unit,
    canModerate: Boolean = false,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onReact: (String) -> Unit,
    onQuoteTap: () -> Unit,
) {
    val aiAnswer = remember(m.id, m.body) { if (m.kind == MessageEntity.KIND_TEXT && m.body.startsWith(com.xalvxnlab.meshron.account.MeshronAi.PREFIX)) com.xalvxnlab.meshron.account.MeshronAi.parse(m.peerKey, m.body) else null }
    val mine = m.outgoing && aiAnswer == null
    val deleted = m.kind == MessageEntity.KIND_DELETED
    var menu by remember { mutableStateOf(false) }
    var confirmUnsend by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val trigger = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    val myReaction = reactions.firstOrNull { it.first == myKey }?.second
    val sos = m.kind == MessageEntity.KIND_TEXT && m.body.startsWith(com.xalvxnlab.meshron.ui.sos.SOS_PREFIX)
    val gradient = mine && !deleted && !sos
    val textColor = if (gradient || sos) Color.White else MeshColors.TextPrimary
    val metaColor = if (gradient || sos) Color.White.copy(alpha = 0.75f) else MeshColors.TextTertiary
    val glow by androidx.compose.animation.animateColorAsState(
        if (highlighted) MeshColors.GradMid.copy(alpha = 0.18f) else Color.Transparent, tween(300), label = "flash",
    )
    // Rounded all round, except the author's side tightens where the group continues.
    val big = 22.dp
    val small = 6.dp
    var translation by remember(m.id) { mutableStateOf<String?>(null) }
    val aiApp = LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
    translation?.let { t ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { translation = null },
            title = { Text("Translation") },
            text = { if (t == "…") CircularProgressIndicator(Modifier.size(24.dp)) else Text(t) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { translation = null }) { Text("Done") } },
        )
    }
    val shape = if (mine) RoundedCornerShape(topStart = big, bottomStart = big, topEnd = if (first) big else small, bottomEnd = if (last) big else small)
    else RoundedCornerShape(topEnd = big, bottomEnd = big, topStart = if (first) big else small, bottomStart = if (last) big else small)

    Box(
        Modifier.fillMaxWidth().background(glow, RoundedCornerShape(12.dp))
            // Swipe a message sideways (either way) to reply to it.
            .pointerInput(m.id, deleted) {
                if (deleted) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (armed) onReply()
                        armed = false
                        scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                    },
                    onDragCancel = {
                        armed = false
                        scope.launch { drag.animateTo(0f) }
                    },
                ) { change, amount ->
                    change.consume()
                    val next = (drag.value + amount * 0.6f).coerceIn(-trigger * 1.4f, trigger * 1.4f)
                    scope.launch { drag.snapTo(next) }
                    val now = kotlin.math.abs(next) >= trigger
                    if (now && !armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    armed = now
                }
            },
    ) {
        val p = (kotlin.math.abs(drag.value) / trigger).coerceIn(0f, 1f)
        if (p > 0.05f) {
            Box(
                Modifier.align(if (drag.value > 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 10.dp)
                    .androidx_graphics(p).size(32.dp).clip(CircleShape).background(MeshColors.Surface2),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(18.dp), tint = MeshColors.GradMid) }
        }
        Column(
            Modifier.fillMaxWidth().graphicsLayer { translationX = drag.value },
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(shape)
                    .then(if (sos) Modifier.background(Color(0xFFE5243B)) else if (gradient) Modifier.background(MeshColors.BubbleGradient) else Modifier.background(MeshColors.BubbleOther).border(1.dp, if (MeshColors.isDark) Color.Transparent else MeshColors.Hairline, shape))
                    .combinedClickable(
                        onClick = { if (m.kind == MessageEntity.KIND_FILE) onOpenFile() },
                        // Double-tap: quick heart.
                        onDoubleClick = if (deleted) null else ({
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onReact(if (myReaction == "❤️") "" else "❤️")
                        }),
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    )
                    .padding(start = 13.dp, end = 11.dp, top = 7.dp, bottom = 6.dp),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!deleted) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                            QUICK_REACTIONS.forEach { e ->
                                Box(
                                    Modifier.size(42.dp).clip(CircleShape)
                                        .background(if (e == myReaction) MeshColors.GradMid.copy(alpha = 0.3f) else Color.Transparent)
                                        .clickable { menu = false; onReact(if (e == myReaction) "" else e) },
                                    contentAlignment = Alignment.Center,
                                ) { Text(e, fontSize = 22.sp) }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(MeshColors.Hairline))
                        DropdownMenuItem(text = { Text("Reply") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, null) }, onClick = { menu = false; onReply() })
                        if (m.kind != MessageEntity.KIND_FILE) {
                            DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; onCopy() })
                            if (!com.xalvxnlab.meshron.identity.Mode.anon && m.kind == MessageEntity.KIND_TEXT) DropdownMenuItem(
                                text = { Text("Translate") }, leadingIcon = { Icon(Icons.Filled.Translate, null) },
                                onClick = {
                                    menu = false
                                    translation = "…"
                                    scope.launch { translation = runCatching { aiApp.ai.translate(displayBody(m)) }.getOrElse { "Couldn't translate: " + it.message } }
                                },
                            )
                        }
                        if (canEdit) {
                            DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Delete for me") },
                        leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                        onClick = { menu = false; onDelete() },
                    )
                    if ((mine && (m.kind == MessageEntity.KIND_TEXT || m.kind == MessageEntity.KIND_FILE)) || canModerate) {
                        DropdownMenuItem(
                            text = { Text("Delete for everyone", color = MeshColors.Danger) },
                            leadingIcon = { Icon(Icons.Filled.DeleteForever, null, tint = MeshColors.Danger) },
                            onClick = { menu = false; confirmUnsend = true },
                        )
                    }
                }
                if (sender != null) {
                    val nameApp = LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
                    Text(
                        sender,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MeshColors.avatar(m.senderKey ?: sender),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = m.senderKey != null) { m.senderKey?.let { nameApp.openProfileRequests.tryEmit(it) } },
                    )
                }
                InlineMeta(alignEnd = mine, content = { when {
                    deleted -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Block, null, Modifier.size(15.dp), tint = metaColor)
                        Spacer(Modifier.width(6.dp))
                        Text(displayBody(m), color = textColor.copy(alpha = 0.7f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            style = MaterialTheme.typography.bodyLarge)
                    }
                    m.kind == MessageEntity.KIND_FILE -> FileContent(m, fileStatus)
                    else -> {
                        val story = STORY_REPLY.find(m.body)
                        val reply = MessagingService.REPLY.find(m.body)
                        if (m.kind == MessageEntity.KIND_TEXT && story != null) {
                            QuoteBlock(
                                if (mine) "Their story" else "Your story", story.groupValues[1], textColor, onClick = null,
                            )
                            Text(story.groupValues[2], color = textColor, style = MaterialTheme.typography.bodyLarge)
                        } else if (m.kind == MessageEntity.KIND_TEXT && reply != null) {
                            QuoteBlock(quoteAuthor ?: "Reply", reply.groupValues[1], textColor, onClick = onQuoteTap)
                            Text(reply.groupValues[2], color = textColor, style = MaterialTheme.typography.bodyLarge)
                        } else if (m.kind == MessageEntity.KIND_TEXT && Stickers.isSticker(m.body)) {
                            Stickers.Sticker(Stickers.emojiOf(m.body), 64)
                        } else if (m.kind == MessageEntity.KIND_TEXT && m.body.startsWith("meshron://reel/")) {
                            ReelLinkCard(m.body, textColor)
                        } else if (m.kind == MessageEntity.KIND_TEXT && m.body.startsWith("meshron://collab/")) {
                            CollabCard(m.body, m.outgoing, textColor)
                        } else if (aiAnswer != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                com.xalvxnlab.meshron.ui.reels.MeshronAiAvatar(20.dp)
                                Text(" Meshron AI", fontWeight = FontWeight.Bold, color = textColor, style = MaterialTheme.typography.labelLarge)
                                com.xalvxnlab.meshron.ui.components.Badges(com.xalvxnlab.meshron.account.MeshronAi.ID, 13.dp)
                            }
                            Text(aiAnswer.text, color = textColor, style = MaterialTheme.typography.bodyLarge)
                        } else {
                            // Someone else's words in another language: shown in mine, with a note.
                            val autoTr by aiApp.settings.autoTranslate.collectAsState(initial = true)
                            val account by aiApp.accounts.state.collectAsState(initial = null)
                            var auto by remember(m.id) { mutableStateOf<com.xalvxnlab.meshron.account.AiService.Translation?>(null) }
                            var showOriginal by remember(m.id) { mutableStateOf(false) }
                            LaunchedEffect(m.id, autoTr, account) {
                                if (!m.outgoing && autoTr && account != null && !sos && !com.xalvxnlab.meshron.identity.Mode.anon && m.kind == MessageEntity.KIND_TEXT)
                                    auto = runCatching { aiApp.ai.autoTranslate(m.id, displayBody(m)) }.getOrNull()
                            }
                            val tr = auto
                            Text(if (tr != null && !showOriginal) tr.text else displayBody(m), color = textColor, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (sos) FontWeight.SemiBold else null)
                            if (tr != null) Text(
                                if (showOriginal) "↺ " + tr.note else tr.note + " · " + tr.original,
                                color = textColor.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).clickable { showOriginal = !showOriginal },
                            )
                            if (sos) {
                                // Open the shared location in any maps app.
                                val link = remember(m.body) { Regex("https://maps\\.google\\.com/\\?q=[-0-9.,]+").find(m.body)?.value }
                                val ctx = LocalContext.current
                                if (link != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable {
                                            val q = link.substringAfter("q=")
                                            val geo = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$q?q=$q"))
                                            runCatching { ctx.startActivity(geo) }.onFailure {
                                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))) }
                                            }
                                        }.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.LocationOn, null, tint = Color(0xFFE5243B), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Open map", color = Color(0xFFE5243B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                } }, meta = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (edited && !deleted) {
                        Text("edited · ", style = MaterialTheme.typography.labelSmall, color = metaColor)
                    }
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.sentAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                    )
                    if (mine && !deleted) {
                        Spacer(Modifier.width(4.dp))
                        when (status) {
                            "Sending" -> Icon(Icons.Filled.Schedule, "Sending", Modifier.size(14.dp), tint = metaColor)
                            "Sent" -> Icon(Icons.Filled.Done, "Sent", Modifier.size(14.dp), tint = metaColor)
                            "Delivered" -> Icon(Icons.Filled.DoneAll, "Delivered", Modifier.size(14.dp), tint = Color.White)
                            "Seen", "SeenLast" -> {
                                Icon(Icons.Filled.DoneAll, "Seen", Modifier.size(14.dp), tint = Color(0xFF7DF9FF))
                                if (status == "SeenLast") Text(" Seen", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            else -> Text(status, style = MaterialTheme.typography.labelSmall, color = metaColor)
                        }
                    }
                } })
            }
            // Reactions sit on the bubble's lower edge; they pop in.
            AnimatedVisibility(
                visible = reactions.isNotEmpty() && !deleted,
                enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = 500f)) + fadeIn(tween(120)),
                exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            ) {
                val grouped = reactions.groupBy({ it.second }, { it.first }).entries.sortedByDescending { it.value.size }
                Row(
                    Modifier.padding(horizontal = 10.dp).offset(y = (-4).dp)
                        .shadow(4.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(MeshColors.Surface1)
                        .border(1.5.dp, MeshColors.Bg, RoundedCornerShape(14.dp))
                        .clickable { if (myReaction != null) onReact("") else menu = true }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    grouped.take(4).forEach { (e, _) -> Text(e, fontSize = 15.sp) }
                    if (reactions.size > 1) {
                        Spacer(Modifier.width(4.dp))
                        Text("${reactions.size}", style = MaterialTheme.typography.labelMedium, color = MeshColors.TextSecondary)
                    }
                }
            }
        }
    }

    if (confirmUnsend) AlertDialog(
        onDismissRequest = { confirmUnsend = false },
        title = { Text("Delete for everyone?") },
        text = { Text("It's removed from this chat on every device that has it, including theirs. Anyone who already read it may remember it.") },
        confirmButton = {
            TextButton(onClick = { confirmUnsend = false; onUnsend() }) { Text("Delete", color = MeshColors.Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmUnsend = false }) { Text("Cancel") } },
    )
}

/** Fades and grows the reply hint as the swipe gets closer to replying. */
private fun Modifier.androidx_graphics(p: Float) = graphicsLayer { alpha = p; scaleX = 0.6f + 0.4f * p; scaleY = 0.6f + 0.4f * p }

/** The quoted message above a reply (or the story it answers). Tap to jump to it. */
@Composable
private fun QuoteBlock(author: String, text: String, textColor: Color, onClick: (() -> Unit)?) {
    Column(
        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.18f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .drawBehind { drawRect(MeshColors.GradEnd, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 5.dp),
    ) {
        Text(author, color = if (textColor == Color.White) Color.White else MeshColors.GradMid,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(text, color = textColor.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A picked file before it's sent: full preview for photos, name and size for the rest, plus a caption. */
@Composable
private fun FilePreviewDialog(uri: android.net.Uri, onCancel: () -> Unit, onSend: (String) -> Unit) {
    val context = LocalContext.current
    val meta by produceState<Triple<String, Long, String>?>(null, uri) {
        value = withContext(Dispatchers.IO) { fileMeta(context, uri) }
    }
    val image by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { loadUriPreview(context, uri) }
    }
    var caption by remember { mutableStateOf("") }
    val tooBig = (meta?.second ?: 0L) > 25L * 1024 * 1024
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(meta?.first ?: "File", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    meta?.let { Text(humanSize(it.second), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val img = image
                if (img != null) {
                    Image(img, contentDescription = meta?.first, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AttachFile, null, Modifier.size(44.dp), tint = Color.White)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(meta?.first ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(meta?.third ?: "", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (tooBig) {
                Text("This file is over 25 MB, the most Meshron can send.", color = MeshColors.Danger,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = caption,
                    onValueChange = { if (it.length <= 1000) caption = it },
                    placeholder = { Text("Add a caption…", color = MeshColors.TextTertiary) },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MeshColors.GradMid,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(52.dp).clip(CircleShape).then(if (tooBig || meta == null) Modifier.background(MeshColors.Surface2) else Modifier.background(MeshColors.Gradient))
                        .clickable(enabled = !tooBig && meta != null) { onSend(caption.trim()) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", Modifier.size(24.dp), tint = Color.White) }
            }
        }
    }
}

private fun fileMeta(context: Context, uri: android.net.Uri): Triple<String, Long, String> {
    var name = "File"
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return Triple(name, size, mime)
}

private fun loadUriPreview(context: Context, uri: android.net.Uri): ImageBitmap? = runCatching {
    val mime = context.contentResolver.getType(uri) ?: return null
    if (!mime.startsWith("image/")) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }?.asImageBitmap()
}.getOrNull()

@Composable
private fun FileContent(m: MessageEntity, fileStatus: String?) {
    val info = remember(m.body) { FileInfo.parse(m.body) }
    if (info == null) {
        Text("Unreadable file message", color = MaterialTheme.colorScheme.error)
        return
    }
    val path = m.localPath
    if (info.mime.startsWith("audio/") && path != null) { VoiceNote(path); return }
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
        com.xalvxnlab.meshron.ui.components.Toasts.show(context, "No app can open this file")
    }
}

/** Invites are stored as JSON; show them as a sentence. */
fun displayBody(m: MessageEntity): String = when (m.kind) {
    MessageEntity.KIND_DELETED -> if (m.outgoing) "You deleted this message" else "This message was deleted"
    MessageEntity.KIND_TEXT -> when {
        Stickers.isSticker(m.body) -> Stickers.emojiOf(m.body) + " Sticker"
        m.body.startsWith("meshron://reel/") -> "🎬 Reel"
        m.body.startsWith("meshron://collab/") -> "🤝 Collab invite"
        m.body.startsWith(com.xalvxnlab.meshron.account.MeshronAi.PREFIX) -> com.xalvxnlab.meshron.account.MeshronAi.parse(m.peerKey, m.body)?.let { "✨ Meshron AI: " + it.text } ?: "A message that couldn't be checked"
        else -> MessagingService.bodyText(m.body)
    }
    MessageEntity.KIND_CALL -> MessageEntity.callText(m.body, m.outgoing)
    MessageEntity.KIND_FILE -> FileInfo.parse(m.body)?.let { if (it.mime.startsWith("audio/")) "🎤 Voice note" else (if (it.isImage) "Photo: " else "File: ") + it.name } ?: "File"
    MessageEntity.KIND_INVITE -> {
        val name = MessagingService.parseInvite(m.body)?.name ?: "a group"
        if (m.outgoing) "You invited them to “$name”" else "Added you to the group “$name”"
    }
    else -> m.body
}

/** Same author, sent within 3 minutes: shown as one tight group of bubbles. */
private fun sameGroup(a: MessageEntity, b: MessageEntity): Boolean =
    a.outgoing == b.outgoing && a.senderKey == b.senderKey && kotlin.math.abs(b.localTime - a.localTime) < 3 * 60_000

/** A story reply: "↪ Story: <quote>", a newline, then the reply (same format on every platform). */
private val STORY_REPLY = Regex("^↪ Story: (.*)\\n([\\s\\S]+)$")

/** A reel shared into the chat: a card that opens it. */
@Composable
private fun ReelLinkCard(body: String, textColor: Color) {
    val lines = body.lines()
    val app = LocalContext.current.applicationContext as MeshronApp
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.18f)).clickable { app.openReelRequests.tryEmit(lines.first()) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 42.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = 200.dp)) {
            Text("Reel", color = textColor, fontWeight = FontWeight.Bold)
            lines.drop(1).take(2).forEach { Text(it, color = textColor.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
}

/** A collab invite for a reel: the invited friend can accept, which puts it on their profile too. */
@Composable
private fun CollabCard(body: String, outgoing: Boolean, textColor: Color) {
    val lines = body.lines()
    val parts = lines.first().removePrefix("meshron://collab/").split("/")
    val app = LocalContext.current.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    // Remembered across visits, so an accepted invite never asks again.
    val prefs = remember { app.getSharedPreferences("collabs", android.content.Context.MODE_PRIVATE) }
    val key = parts.joinToString("/")
    var state by remember(body) { mutableStateOf(if (prefs.getBoolean(key, false)) "ok" else "") }
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(12.dp).widthIn(max = 240.dp)) {
        Text("🤝 Collab invite", color = textColor, fontWeight = FontWeight.Bold)
        Text(if (outgoing) "You invited them to collaborate on your reel." else "They want you as a collaborator on their reel.", color = textColor.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        lines.drop(1).firstOrNull()?.takeIf { it.isNotBlank() }?.let { Text(it, color = textColor.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("View", color = textColor, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                .clickable { if (parts.size == 2) app.openReelRequests.tryEmit("meshron://reel/" + parts[0] + "/" + parts[1]) }.padding(horizontal = 14.dp, vertical = 6.dp))
            if (!outgoing && parts.size == 2) Text(when (state) { "ok" -> "Accepted"; "busy" -> "…"; "" -> "Accept"; else -> "Try again" }, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (state == "ok") MeshColors.Surface3 else MeshColors.AccentStrong).clickable(enabled = state != "ok" && state != "busy") {
                    state = "busy"
                    scope.launch {
                        state = if (runCatching { app.reels.collabAccept(parts[0], parts[1]) }.isSuccess) "ok" else "err"
                        if (state == "ok") prefs.edit().putBoolean(key, true).apply()
                    }
                }.padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** Voice note: play/pause, progress and length. */
@Composable
private fun VoiceNote(path: String) {
    val player = remember(path) { android.media.MediaPlayer().apply { runCatching { setDataSource(path); prepare() } } }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0f) }
    val dur = remember(player) { runCatching { player.duration }.getOrDefault(0).coerceAtLeast(1) }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            pos = player.currentPosition.toFloat() / dur
            if (!player.isPlaying) { playing = false; pos = 0f }
            kotlinx.coroutines.delay(100)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 200.dp)) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).clickable {
                if (playing) { player.pause(); playing = false } else { player.start(); playing = true }
            },
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(pos).height(4.dp).background(Color.White))
            }
            Text("%d:%02d".format(dur / 60000, (dur / 1000) % 60), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * A bubble's words and its time and ticks. A one-line message keeps the time on the same line,
 * at the end; longer ones put it under the text, bottom-right.
 */
@Composable
private fun InlineMeta(alignEnd: Boolean, content: @Composable () -> Unit, meta: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = {
        Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) { content() }
        Box { meta() }
    }) { ms, c ->
        val gap = 8.dp.roundToPx()
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val body = ms[0].measure(loose)
        val tag = ms[1].measure(loose)
        val oneLine = body.height <= 30.dp.roundToPx()
        if (oneLine && body.width + gap + tag.width <= c.maxWidth) {
            val w = maxOf(c.minWidth, body.width + gap + tag.width)
            val h = maxOf(body.height, tag.height + 2.dp.roundToPx())
            layout(w, h) { body.place(0, 0); tag.place(w - tag.width, h - tag.height) }
        } else {
            val w = maxOf(c.minWidth, body.width, tag.width)
            val h = body.height + 1.dp.roundToPx() + tag.height
            layout(w, h) { body.place(if (alignEnd) w - body.width else 0, 0); tag.place(w - tag.width, h - tag.height) }
        }
    }
}
