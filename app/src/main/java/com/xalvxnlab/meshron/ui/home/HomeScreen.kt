package com.xalvxnlab.meshron.ui.home

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VideoCall
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.crypto.ZBase32
import com.xalvxnlab.meshron.crypto.hexToBytes
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.files.FileService
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.identity.Mode
import com.xalvxnlab.meshron.messaging.MessagingService
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.ui.chat.ChatScreen
import com.xalvxnlab.meshron.ui.chat.ChatTarget
import com.xalvxnlab.meshron.ui.chat.Inbox
import com.xalvxnlab.meshron.ui.chat.NewGroupDialog
import com.xalvxnlab.meshron.ui.components.AuroraDock
import com.xalvxnlab.meshron.ui.components.AuroraHeader
import com.xalvxnlab.meshron.ui.components.DockItem
import com.xalvxnlab.meshron.ui.components.HeaderButton
import com.xalvxnlab.meshron.ui.components.MenuTile
import com.xalvxnlab.meshron.ui.components.MeshronLogo
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch

/** Full-screen places opened on top of the tabs. */
private sealed interface Overlay {
    data object Search : Overlay
    data object Settings : Overlay
    data object EditProfile : Overlay
    data object Ai : Overlay
    class Player(val items: List<com.xalvxnlab.meshron.reels.Reel>, val start: Int) : Overlay
}

/**
 * The app once you're set up: a floating dock (Home, Discover, +, Activity, Profile), with
 * chats, search, settings and profile pages opening over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    identity: Identity,
    repo: IdentityRepository,
    contactsRepo: ContactsRepository,
    network: NetworkService,
    messaging: MessagingService,
    files: FileService,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val anon = Mode.anon
    var tab by rememberSaveable { mutableStateOf("home") }
    var openReelsProfile by remember { mutableStateOf<String?>(null) }
    var profilePage by remember { mutableStateOf<String?>(null) }
    var openChat by rememberSaveable { mutableStateOf<String?>(null) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var showSos by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var addingFriend by rememberSaveable { mutableStateOf(false) }
    var newGroup by rememberSaveable { mutableStateOf(false) }
    var chatMenu by rememberSaveable { mutableStateOf<String?>(null) }
    // Asks Discover to open the reel picker (bumped by the + sheet).
    var postReel by remember { mutableIntStateOf(0) }
    var recordReel by remember { mutableIntStateOf(0) }
    var immersive by remember { mutableStateOf(false) }
    if (showSos) com.xalvxnlab.meshron.ui.sos.SosScreen(onClose = { showSos = false })
    LaunchedEffect(Unit) { app.openChatRequests.collect { openChat = it } }
    // Someone's page (tapped a name in a group): open it in Discover.
    LaunchedEffect(Unit) {
        app.openProfileRequests.collect { id ->
            val ch = runCatching { app.reels.channelOf(id) }.getOrNull()
            if (ch != null) profilePage = ch
            else com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Their page isn't reachable right now")
        }
    }
    // A reel shared in a chat: find it and play it.
    LaunchedEffect(Unit) {
        app.openReelRequests.collect { link ->
            val parts = link.removePrefix("meshron://reel/").split("/")
            if (parts.size < 2) return@collect
            val list = runCatching { app.reels.feed("user", ch = parts[0], limit = 200).first }.getOrDefault(emptyList())
            val i = list.indexOfFirst { it.id == parts[1] }
            if (i >= 0) { openChat = null; overlay = Overlay.Player(list, i) }
            else com.xalvxnlab.meshron.ui.components.Toasts.show(context, "That reel isn't reachable right now")
        }
    }

    val contacts by contactsRepo.contacts.collectAsState(initial = emptyList())
    val groups by messaging.groups.collectAsState(initial = emptyList())
    val summaries by messaging.summaries.collectAsState(initial = emptyList())
    val net by network.state.collectAsState()
    val myKey = remember(identity) { identity.publicKey.toHex() }
    val nameOf: (String) -> String = { key ->
        contacts.find { it.keyHex == key }?.name?.takeIf { it.isNotBlank() }
            ?: net.peers[key]?.announcedName?.takeIf { it.isNotBlank() }
            ?: ZBase32.encode(key.hexToBytes()).let { it.take(6) + "…" + it.takeLast(4) }
    }
    val unread = summaries.sumOf { it.unread }
    val requests = contacts.count { it.status == Contact.Status.REQUEST }

    val scanQr = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        when (val p = com.xalvxnlab.meshron.contacts.ContactParser.parse(text)) {
            is com.xalvxnlab.meshron.contacts.ParsedContact.Ok -> if (p.keyHex == myKey) {
                com.xalvxnlab.meshron.ui.components.Toasts.show(context, "That's your own code")
            } else {
                scope.launch { contactsRepo.add(p.keyHex, p.name.ifBlank { net.peers[p.keyHex]?.announcedName.orEmpty() }) }
                openChat = p.keyHex
            }
            is com.xalvxnlab.meshron.contacts.ParsedContact.Invalid ->
                com.xalvxnlab.meshron.ui.components.Toasts.show(context, "That isn't a Meshron code", long = true)
        }
    }
    val scan = {
        scanQr.launch(ScanOptions().setPrompt("Point at a Meshron QR code").setBeepEnabled(false).setOrientationLocked(true).setDesiredBarcodeFormats(ScanOptions.QR_CODE))
    }
    val haptics = LocalHapticFeedback.current

    val target: ChatTarget? = openChat?.let { key ->
        if (key.startsWith(MessageEntity.GROUP_PREFIX)) groups.find { it.conversationKey == key }?.let { ChatTarget.Group(it) }
        else contacts.find { it.keyHex == key }?.let { ChatTarget.Direct(it) }
    }
    BackHandler(enabled = overlay != null) { overlay = null }
    // Someone's page opened from a chat or group: on top of everything, Back returns there.
    profilePage?.let { ch ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { profilePage = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            com.xalvxnlab.meshron.ui.reels.ReelProfileScreen(app.reels, ch, onMessage = { link ->
                when (val p = com.xalvxnlab.meshron.contacts.ContactParser.parse(link)) {
                    is com.xalvxnlab.meshron.contacts.ParsedContact.Ok -> if (p.keyHex != myKey) {
                        if (contacts.none { it.keyHex == p.keyHex }) scope.launch { contactsRepo.add(p.keyHex, p.name) }
                        profilePage = null
                        openChat = p.keyHex
                    }
                    else -> Unit
                }
            }, onBack = { profilePage = null })
        }
    }
    BackHandler(enabled = overlay == null && target == null && tab != "home") { tab = "home" }

    AnimatedContent(
        targetState = target,
        contentKey = { it?.conversation },
        transitionSpec = {
            val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
            if (targetState != null) (slideInHorizontally(tween(340, easing = ease)) { it / 3 } + fadeIn(tween(220))) togetherWith fadeOut(tween(160))
            else fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(300, easing = ease)) { it / 3 } + fadeOut(tween(200)))
        },
        label = "chat",
    ) { open ->
        if (open != null) {
            ChatScreen(open, myKey, nameOf, messaging, network, files, onBack = { openChat = null })
            return@AnimatedContent
        }
        Box(Modifier.fillMaxSize().background(MeshColors.Bg)) {
            AnimatedContent(tab, transitionSpec = { (fadeIn(tween(220)) + androidx.compose.animation.scaleIn(tween(260), initialScale = 0.985f)) togetherWith fadeOut(tween(120)) }, label = "tab") { t ->
                when (t) {
                    "discover" -> com.xalvxnlab.meshron.ui.reels.ReelsScreen(
                        app.reels,
                        onMessage = { link ->
                            when (val p = com.xalvxnlab.meshron.contacts.ContactParser.parse(link)) {
                                is com.xalvxnlab.meshron.contacts.ParsedContact.Ok -> if (p.keyHex != myKey) {
                                    if (contacts.none { it.keyHex == p.keyHex }) scope.launch { contactsRepo.add(p.keyHex, p.name) }
                                    openChat = p.keyHex
                                }
                                else -> Unit
                            }
                        },
                        onEditProfile = { overlay = Overlay.EditProfile },
                        postRequest = postReel,
                        recordRequest = recordReel,
                        // One-shot: once Discover acted on it, coming back must not repeat it.
                        onRequestHandled = { postReel = 0; recordReel = 0 },
                        onImmersive = { immersive = it },
                        onSearch = { overlay = Overlay.Search },
                        openProfile = openReelsProfile,
                        onProfileOpened = { openReelsProfile = null },
                    )
                    "activity" -> com.xalvxnlab.meshron.ui.activity.ActivityScreen(
                        contactsRepo, network, onOpen = { openChat = it }, onAddFriend = { overlay = Overlay.Search },
                    )
                    "profile" -> com.xalvxnlab.meshron.ui.profile.MyProfile(
                        identity,
                        onEdit = { overlay = Overlay.EditProfile },
                        onSettings = { overlay = Overlay.Settings },
                        onAddFriend = { overlay = Overlay.Search },
                        onOpenVideo = { list, i -> overlay = Overlay.Player(list, i) },
                        onOpenPerson = { openReelsProfile = it; tab = "discover" },
                    )
                    else -> Column(Modifier.fillMaxSize()) {
                        AuroraHeader(
                            if (anon) "Ghost" else "Meshron",
                            leading = { MeshronLogo(30.dp); Spacer(Modifier.width(14.dp)) },
                        ) {
                            if (anon) Box(Modifier.padding(end = 4.dp).clip(RoundedCornerShape(50)).background(MeshColors.AccentContainer).padding(horizontal = 9.dp, vertical = 4.dp)) {
                                Text("ANON", color = MeshColors.Accent, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.sp)
                            }
                            HeaderButton(Icons.Rounded.Search, "Search everything") { overlay = Overlay.Search }
                        }
                        if (!anon) com.xalvxnlab.meshron.ui.ai.AiEntry { overlay = Overlay.Ai }
                        Inbox(
                            identity, contactsRepo, messaging, network, nameOf,
                            onOpen = { openChat = it },
                            onAddFriend = { overlay = Overlay.Search },
                            onNewGroup = { newGroup = true },
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); chatMenu = it },
                            stories = true,
                        )
                    }
                }
            }
            // The dock floats over everything (over videos on Discover).
            if (!(immersive && tab == "discover")) Box(Modifier.align(Alignment.BottomCenter)) {
                val items = buildList {
                    // Same icons as the Windows app: chat bubble, compass, heart, person.
                    add(DockItem("home", "Chats", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble, unread))
                    if (!anon) add(DockItem("discover", "Discover", Icons.Outlined.Explore, Icons.Rounded.Explore))
                    add(DockItem("activity", "Activity", Icons.Outlined.FavoriteBorder, Icons.Rounded.Favorite, requests))
                    add(DockItem("profile", "Me", Icons.Outlined.Person, Icons.Rounded.Person))
                }
                // Anonymous has no videos to create: the shutter goes away.
                AuroraDock(items, tab, onSelect = { if (tab != it) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); tab = it }, onCreate = if (anon) null else ({ creating = true }), overVideo = tab == "discover")
            }
            when (overlay) {
                Overlay.Search -> GlobalSearch(
                    identity, contactsRepo, messaging, app.reels.takeIf { !anon },
                    onScan = { scan() },
                    onOpenChat = { openChat = it; overlay = null },
                    onAdd = { f -> scope.launch { contactsRepo.add(f.key, f.name.ifBlank { f.handle }) }; openChat = f.key; overlay = null },
                    onClose = { overlay = null },
                )
                Overlay.Settings -> com.xalvxnlab.meshron.ui.profile.SettingsScreen(identity, repo, network, onBack = { overlay = null })
                Overlay.EditProfile -> com.xalvxnlab.meshron.ui.profile.EditProfile(identity, onBack = { overlay = null })
                Overlay.Ai -> com.xalvxnlab.meshron.ui.ai.AiChat(onBack = { overlay = null })
                is Overlay.Player -> com.xalvxnlab.meshron.ui.reels.ReelPlayerScreen(app.reels, (overlay as Overlay.Player).items, (overlay as Overlay.Player).start, onBack = { overlay = null })
                null -> Unit
            }
        }
    }

    if (creating) ModalBottomSheet(onDismissRequest = { creating = false }, containerColor = MeshColors.Surface1) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
            Text("Create", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            MenuTile(Icons.Rounded.CameraAlt, MeshColors.TileRose, "Record a video", "Film clips, add music and effects") { creating = false; tab = "discover"; recordReel = 1 }
            MenuTile(Icons.Rounded.VideoCall, MeshColors.TileIndigo, "Upload a video", "From your gallery, then edit it") { creating = false; tab = "discover"; postReel = 1 }
            MenuTile(Icons.Rounded.Favorite, MeshColors.TileOrange, "Share a moment", "Friends see it for 24 hours") { creating = false; tab = "home"; app.openMomentComposer.tryEmit(Unit) }
            MenuTile(Icons.Rounded.Sensors, MeshColors.Danger, "Go live", "Stream to your followers right now") { creating = false; tab = "discover"; postReel = -1 }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 12.dp))
        }
    }

    if (addingFriend) AddFriendSheet(
        identity, onScan = { addingFriend = false; scan() },
        onAdd = { f ->
            addingFriend = false
            scope.launch { contactsRepo.add(f.key, f.name.ifBlank { f.handle }) }
            openChat = f.key
        },
        onDismiss = { addingFriend = false },
    )

    if (newGroup) NewGroupDialog(
        contacts = contacts.filter { it.status == Contact.Status.ADDED },
        onDismiss = { newGroup = false },
        onCreate = { name, members, adult ->
            newGroup = false
            scope.launch { runCatching { messaging.createGroup(name, members, adult) }.onSuccess { openChat = it.conversationKey } }
        },
    )

    // Long-press a chat: quick actions.
    chatMenu?.let { conv ->
        val isGroup = conv.startsWith(MessageEntity.GROUP_PREFIX)
        val disappear by app.chatPrefs.disappearHours(conv).collectAsState(initial = 0)
        ModalBottomSheet(onDismissRequest = { chatMenu = null }, containerColor = MeshColors.Surface1) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
                Text(
                    if (isGroup) groups.find { it.conversationKey == conv }?.name.orEmpty() else nameOf(conv),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
                MenuTile(Icons.Rounded.Timer, MeshColors.TileTeal, "Disappearing messages", com.xalvxnlab.meshron.ui.chat.disappearLabel(disappear) + " · tap to change") {
                    val next = listOf(0, 1, 24, 168)
                    scope.launch { app.chatPrefs.setDisappearHours(conv, next[(next.indexOf(disappear) + 1) % next.size]) }
                }
                MenuTile(Icons.Rounded.Delete, MeshColors.Danger, "Delete chat", "Removes it from this phone") {
                    chatMenu = null
                    scope.launch { messaging.deleteConversation(conv) }
                }
            }
        }
    }
}

fun shareIdentity(context: Context, identity: Identity) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        val who = if (identity.handle.isNotEmpty()) "@${identity.handle}" else identity.username
        putExtra(Intent.EXTRA_TEXT, "Add me on Meshron ($who): ${identity.shareLink}")
    }
    context.startActivity(Intent.createChooser(send, "Share your link"))
}

@Composable
private fun ChatTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(MeshColors.Surface2).clickable(onClick = onClick).padding(vertical = 11.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(icon, null, Modifier.padding(end = 6.dp).size(18.dp), tint = MeshColors.Accent)
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
