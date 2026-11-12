package com.xalvxnlab.meshron.ui.home

import android.content.Context
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Close
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.xalvxnlab.meshron.ui.components.MeshBackground
import com.xalvxnlab.meshron.ui.components.MeshronLogo
import com.xalvxnlab.meshron.ui.components.RoundIconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.crypto.ZBase32
import com.xalvxnlab.meshron.crypto.hexToBytes
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.data.MessageEntity
import com.xalvxnlab.meshron.files.FileService
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.messaging.MessagingService
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.NetworkState
import com.xalvxnlab.meshron.network.PeerInfo
import com.xalvxnlab.meshron.ui.chat.ChatScreen
import com.xalvxnlab.meshron.ui.chat.ChatTarget
import com.xalvxnlab.meshron.ui.chat.ChatsTab
import com.xalvxnlab.meshron.ui.chat.NewGroupDialog
import com.xalvxnlab.meshron.ui.contacts.AddContactDialog
import com.xalvxnlab.meshron.ui.contacts.ContactsTab
import com.xalvxnlab.meshron.ui.profile.ProfileTab
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Chats("Chats", "Chats", Icons.AutoMirrored.Outlined.Chat, Icons.AutoMirrored.Rounded.Chat),
    Contacts("Contacts", "Contacts", Icons.Outlined.People, Icons.Rounded.People),
    Profile("Me", "Me", Icons.Outlined.AccountCircle, Icons.Rounded.AccountCircle),
}

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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openChat by rememberSaveable { mutableStateOf<String?>(null) }
    var showSos by rememberSaveable { mutableStateOf(false) }
    if (showSos) com.xalvxnlab.meshron.ui.sos.SosScreen(onClose = { showSos = false })
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
    LaunchedEffect(Unit) { appCtx.openChatRequests.collect { openChat = it } }
    var addingContact by rememberSaveable { mutableStateOf(false) }
    var creatingGroup by rememberSaveable { mutableStateOf(false) }
    val current = Tab.entries[tab]
    val contacts by contactsRepo.contacts.collectAsState(initial = emptyList())
    val groups by messaging.groups.collectAsState(initial = emptyList())
    val summaries by messaging.summaries.collectAsState(initial = emptyList())
    val net by network.state.collectAsState()
    val myKey = remember(identity) { identity.publicKey.toHex() }

    // Best name we know for a key: contact name, then the name they announced, then short ID.
    val nameOf: (String) -> String = { key ->
        contacts.find { it.keyHex == key }?.name?.takeIf { it.isNotBlank() }
            ?: net.peers[key]?.announcedName?.takeIf { it.isNotBlank() }
            ?: ZBase32.encode(key.hexToBytes()).let { it.take(6) + "…" + it.takeLast(4) }
    }

    // A simple two-level layout: tabs, or one open chat on top of them. If the target
    // isn't loaded yet (e.g. a group that was just created), the tabs show until it is.
    val target: ChatTarget? = openChat?.let { key ->
        if (key.startsWith(MessageEntity.GROUP_PREFIX)) {
            groups.find { it.conversationKey == key }?.let { ChatTarget.Group(it) }
        } else {
            contacts.find { it.keyHex == key }?.let { ChatTarget.Direct(it) }
        }
    }

    val unread = summaries.sumOf { it.unread }
    val requests = contacts.count { it.status == Contact.Status.REQUEST }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Scanning someone's QR code adds them directly.
    val scanQr = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        when (val p = com.xalvxnlab.meshron.contacts.ContactParser.parse(text)) {
            is com.xalvxnlab.meshron.contacts.ParsedContact.Ok -> if (p.keyHex == myKey) {
                android.widget.Toast.makeText(context, "That's your own code", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val known = net.peers[p.keyHex]?.announcedName.orEmpty()
                scope.launch { contactsRepo.add(p.keyHex, p.name.ifBlank { known }) }
                openChat = p.keyHex
            }
            is com.xalvxnlab.meshron.contacts.ParsedContact.Invalid ->
                android.widget.Toast.makeText(context, "That isn't a Meshron code", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(tab) { searching = false; query = "" }

    AnimatedContent(
        targetState = target,
        contentKey = { it?.conversation },
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(180))
            } else {
                fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(200)))
            }
        },
        label = "chat",
    ) { open ->
    if (open != null) {
        ChatScreen(open, myKey, nameOf, messaging, network, files, onBack = { openChat = null })
    } else {
    // (The header is fixed, like the iPhone app's. No collapsing-toolbar scroll hook: without a
    // collapsing bar attached it swallowed every scroll and the lists couldn't move.)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // Same header as the iPhone app: big title, SOS, status, quick actions on one
                // line, and the search bar always there underneath.
                Column(Modifier.fillMaxWidth().background(MeshColors.Bg).statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            current.title, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = (-0.5).sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        // Emergency SOS, always one tap away.
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE5243B))
                                .clickable { showSos = true }.padding(horizontal = 13.dp, vertical = 7.dp),
                        ) { Text("SOS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp) }
                        StatusPill(net, compact = true) { tab = Tab.Profile.ordinal }
                        when (current) {
                            Tab.Chats -> {
                                IconButton(onClick = { creatingGroup = true }) { Icon(Icons.Outlined.People, "New group", tint = MeshColors.TextSecondary) }
                                IconButton(onClick = { addingContact = true }) { Icon(Icons.Rounded.PersonAdd, "Add contact", tint = MeshColors.TextSecondary) }
                            }
                            Tab.Contacts -> IconButton(onClick = { addingContact = true }) { Icon(Icons.Rounded.PersonAdd, "Add contact", tint = MeshColors.TextSecondary) }
                            Tab.Profile -> Spacer(Modifier.width(8.dp))
                        }
                    }
                    if (current != Tab.Profile) {
                        Spacer(Modifier.height(10.dp))
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(if (current == Tab.Chats) "Search chats and messages" else "Search contacts", color = MeshColors.TextTertiary) },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MeshColors.TextTertiary) },
                            trailingIcon = if (query.isNotEmpty()) ({
                                IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = MeshColors.TextTertiary) }
                            }) else null,
                            singleLine = true,
                            shape = RoundedCornerShape(50),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MeshColors.Surface2,
                                unfocusedContainerColor = MeshColors.Surface2,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = MeshColors.GradMid,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(end = 10.dp).heightIn(min = 50.dp),
                        )
                    }
                }
            },
            bottomBar = {
                val haptics = LocalHapticFeedback.current
                PillNavBar(
                    selected = tab,
                    count = { t ->
                        when (t) {
                            Tab.Chats -> unread
                            Tab.Contacts -> requests
                            Tab.Profile -> 0
                        }
                    },
                    onSelect = { i ->
                        if (tab != i) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        tab = i
                    },
                )
            },
        ) { padding ->
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { dir * it / 12 }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { -dir * it / 12 })
                },
                modifier = Modifier.padding(padding).fillMaxSize(),
                label = "tab",
            ) { shownTab ->
                Box(Modifier.fillMaxSize()) {
                when (shownTab) {
                    Tab.Chats -> ChatsTab(
                        identity, contactsRepo, messaging, network, nameOf,
                        query = query,
                        onOpen = { openChat = it },
                        onAddContact = { addingContact = true },
                        onNewGroup = { creatingGroup = true },
                    )
                    Tab.Contacts -> ContactsTab(
                        identity, contactsRepo, network,
                        query = query,
                        onOpen = { openChat = it },
                        onAddContact = { addingContact = true },
                    )
                    Tab.Profile -> ProfileTab(identity, repo, network)
                }
                }
            }
        }
    }
    }

    }
    if (addingContact) AddContactDialog(
        myKeyHex = myKey,
        search = { network.findHandle(it) },
        knownName = { k -> contacts.find { it.keyHex == k }?.displayName },
        onShare = { shareIdentity(context, identity) },
        onScan = {
            addingContact = false
            scanQr.launch(
                ScanOptions().setPrompt("Point at a Meshron QR code").setBeepEnabled(false)
                    .setOrientationLocked(true).setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            )
        },
        onDismiss = { addingContact = false },
        onAdd = { key, name ->
            // If they're already connected (e.g. via a group), use the name they announced.
            val known = net.peers[key]?.announcedName.orEmpty()
            scope.launch { contactsRepo.add(key, name.ifBlank { known }) }
            addingContact = false
            openChat = key
        },
    )

    if (creatingGroup) NewGroupDialog(
        contacts = contacts.filter { it.status == Contact.Status.ADDED },
        onDismiss = { creatingGroup = false },
        onCreate = { name, members ->
            creatingGroup = false
            scope.launch {
                val g = messaging.createGroup(name, members)
                openChat = g.conversationKey
            }
        },
    )
}

/** One-glance network status. Tap to see the details in the Me tab. */
@Composable
private fun StatusPill(net: NetworkState, compact: Boolean = false, onClick: () -> Unit) {
    val connected = net.peers.values.count { it.state == PeerInfo.State.CONNECTED }
    val (label, color) = when (net.status) {
        NetworkState.Status.FAILED -> "Problem" to MeshColors.Danger
        NetworkState.Status.ONLINE -> when {
            net.dht?.internet == false -> "Nearby only" to MeshColors.Waiting
            net.dht?.bootstrapped == true || connected > 0 -> "Online" to MeshColors.Online
            else -> "Connecting" to MeshColors.Waiting
        }
        else -> "Starting" to MeshColors.Waiting
    }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pulsing = color != MeshColors.Online
        val alpha = if (pulsing) {
            rememberInfiniteTransition(label = "pulse").animateFloat(
                0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
            ).value
        } else 1f
        Box(Modifier.size(if (compact) 10.dp else 8.dp).graphicsLayer { this.alpha = alpha }.background(color, CircleShape))
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MeshColors.TextSecondary)
        }
    }
}

fun shareIdentity(context: Context, identity: Identity) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Add me on Meshron: ${identity.shareLink}")
    }
    context.startActivity(Intent.createChooser(send, "Share your ID"))
}

/** Floating pill tab bar: the selected tab grows into a gradient chip with its label. */
@Composable
private fun PillNavBar(selected: Int, count: (Tab) -> Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp)
                .shadow(20.dp, shape, ambientColor = MeshColors.GradStart, spotColor = MeshColors.GradStart)
                .clip(shape)
                .background(MeshColors.Surface2)
                .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEachIndexed { i, t ->
                val isSel = i == selected
                val weight by animateFloatAsState(if (isSel) 1.7f else 1f, spring(dampingRatio = 0.75f, stiffness = 500f), label = "w")
                val n = count(t)
                Row(
                    Modifier.weight(weight).fillMaxHeight()
                        .clip(RoundedCornerShape(26.dp))
                        .then(if (isSel) Modifier.background(MeshColors.GradientH) else Modifier)
                        .clickable { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgedBox(badge = {
                        if (n > 0 && !isSel) Badge(containerColor = MeshColors.GradMid, contentColor = Color.White) {
                            Text(if (n > 99) "99+" else "$n")
                        }
                    }) {
                        Icon(
                            if (isSel) t.selectedIcon else t.icon,
                            contentDescription = t.label,
                            tint = if (isSel) Color.White else MeshColors.TextSecondary,
                        )
                    }
                    AnimatedVisibility(
                        visible = isSel,
                        enter = fadeIn(tween(180)) + expandHorizontally(tween(220)),
                        exit = fadeOut(tween(100)) + shrinkHorizontally(tween(180)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(8.dp))
                            Text(t.label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                            if (n > 0) {
                                Spacer(Modifier.width(6.dp))
                                Text(if (n > 99) "99+" else "$n", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
