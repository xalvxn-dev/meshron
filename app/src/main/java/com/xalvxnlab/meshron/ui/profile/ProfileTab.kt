package com.xalvxnlab.meshron.ui.profile

import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.foundation.border
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.Verified

import androidx.compose.material.icons.rounded.Shield

import androidx.compose.material.icons.rounded.Timer

import androidx.compose.material.icons.rounded.Lock

import androidx.compose.material.icons.rounded.VisibilityOff

import androidx.compose.material.icons.rounded.Public

import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import com.xalvxnlab.meshron.ui.components.SectionHeader
import com.xalvxnlab.meshron.ui.components.SettingsDivider
import com.xalvxnlab.meshron.ui.components.SettingsGroup
import com.xalvxnlab.meshron.ui.components.SettingsRow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import com.xalvxnlab.meshron.ui.components.RecoveryKeyDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.local.LocalTransports
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.NetworkState
import com.xalvxnlab.meshron.network.PeerInfo
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.GlowButton
import com.xalvxnlab.meshron.ui.components.QrCode
import com.xalvxnlab.meshron.ui.components.SectionLabel
import com.xalvxnlab.meshron.ui.home.shareIdentity
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ProfileTab(identity: Identity, repo: IdentityRepository, network: NetworkService, settingsOnly: Boolean = false) {
    val context = LocalContext.current
    val net by network.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingHandle by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showSos by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var selfTest by remember { mutableStateOf<Boolean?>(null) }
    val app = context.applicationContext as MeshronApp
    val stayConnected by app.settings.stayConnected.collectAsState(initial = true)
    val local by app.localTransports.status.collectAsState()
    val useInternet by app.settings.useInternet.collectAsState(initial = true)
    val sameWifi by app.settings.sameWifi.collectAsState(initial = true)
    val nearby by app.settings.nearby.collectAsState(initial = true)
    val carry by app.settings.carryForOthers.collectAsState(initial = true)
    // Notifications are optional: the switch works either way.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.localTransports.permissionsChanged()
    }
    val missingPermission = (sameWifi && local.lanMissingPermission) || (nearby && local.nearbyMissingPermission)
    val pinScope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmSwitch by rememberSaveable { mutableStateOf(false) }
    var settingPin by rememberSaveable { mutableStateOf(false) }
    val pinHash by app.settings.pinHash.collectAsState(initial = null)
    val disappear by app.settings.disappearHours.collectAsState(initial = 0)
    val hideIp by app.settings.hideIp.collectAsState(initial = false)
    if (settingPin) {
        var first by remember { mutableStateOf<String?>(null) }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { settingPin = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            com.xalvxnlab.meshron.ui.components.PinPad(
                title = if (first == null) "Choose a PIN" else "Repeat it",
                subtitle = "6 digits. Needed to open Meshron.",
                check = { p -> first == null || p == first },
                onDone = { p ->
                    if (first == null) first = p
                    else { settingPin = false; app.locked.value = false; pinScope.launch { app.settings.setPin(p) } }
                },
                footer = { androidx.compose.material3.TextButton(onClick = { settingPin = false }) { Text("Cancel") } },
            )
        }
    }
    // Profile photo and bio (shared with contacts).
    val myProfile by app.profiles.me.collectAsState()
    var editingBio by rememberSaveable { mutableStateOf(false) }
    var bioDraft by rememberSaveable { mutableStateOf("") }
    val profileScope = androidx.compose.runtime.rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) profileScope.launch { app.profiles.setMine(myProfile.bio, uri, context) }
    }
    if (editingBio) androidx.compose.material3.AlertDialog(
        onDismissRequest = { editingBio = false },
        title = { Text("Your bio") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = bioDraft, onValueChange = { bioDraft = it.take(com.xalvxnlab.meshron.profile.ProfileStore.BIO_MAX) },
                placeholder = { Text("A few words about you") }, minLines = 2, maxLines = 4,
                supportingText = { Text("${bioDraft.length}/${com.xalvxnlab.meshron.profile.ProfileStore.BIO_MAX} · your contacts see this") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                editingBio = false
                profileScope.launch { app.profiles.setMine(bioDraft, null, context) }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (myProfile.photo != null) androidx.compose.material3.TextButton(onClick = {
                    editingBio = false
                    profileScope.launch { app.profiles.setMine(myProfile.bio, null, context, removePhoto = true) }
                }) { Text("Remove photo", color = MeshColors.Danger) }
                androidx.compose.material3.TextButton(onClick = { editingBio = false }) { Text("Cancel") }
            }
        },
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // ---- Profile header (not on the Settings screen, which has its own) ----
        if (!settingsOnly) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Gradient cover with the avatar overlapping its lower edge.
            Box(Modifier.fillMaxWidth().height(164.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MeshColors.Gradient)
                )
                // Tap the photo to change it.
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .background(MeshColors.Bg, CircleShape)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !com.xalvxnlab.meshron.identity.Mode.anon) { pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                ) {
                    Avatar(identity.username, identity.publicKey.toHex(), 96.dp)
                }
                if (!com.xalvxnlab.meshron.identity.Mode.anon) Box(
                    Modifier.align(Alignment.BottomCenter).padding(start = 72.dp).size(32.dp)
                        .background(MeshColors.Bg, CircleShape).padding(3.dp)
                        .clip(CircleShape).background(MeshColors.Gradient)
                        .clickable { pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PhotoCamera, "Change photo", Modifier.size(16.dp), tint = Color.White) }
            }
            Spacer(Modifier.height(12.dp))
            Text(identity.username, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(2.dp))
            // Bio: shown to contacts on your profile. Tap to edit. (Anonymous: none.)
            if (!com.xalvxnlab.meshron.identity.Mode.anon) Text(
                myProfile.bio.ifBlank { "Add a bio" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (myProfile.bio.isBlank()) MeshColors.GradMid else MeshColors.TextPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { bioDraft = myProfile.bio; editingBio = true }.padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(
                listOfNotNull(identity.handle.takeIf { it.isNotEmpty() }?.let { "@$it" }, "ID " + identity.shortId).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MeshColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderAction(Icons.Rounded.Share, "Share", Modifier.weight(1f)) { shareIdentity(context, identity) }
                HeaderAction(Icons.Rounded.QrCode2, "QR code", Modifier.weight(1f)) { showQr = true }
                HeaderAction(Icons.Rounded.ContentCopy, "Copy ID", Modifier.weight(1f)) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Meshron ID", identity.id))
                    com.xalvxnlab.meshron.ui.components.Toasts.show(context, "ID copied")
                }
            }
        }

        // ---- Account ----
        // ---- Public / anonymous ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp))
                .background(if (com.xalvxnlab.meshron.identity.Mode.anon) MeshColors.AccentContainer else MeshColors.Surface1)
                .clickable { confirmSwitch = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                Icon(if (com.xalvxnlab.meshron.identity.Mode.anon) Icons.Rounded.Public else Icons.Rounded.VisibilityOff, null, tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (com.xalvxnlab.meshron.identity.Mode.anon) "You're anonymous" else "Go anonymous", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (com.xalvxnlab.meshron.identity.Mode.anon) "Separate identity, nothing links it to you. Tap to go back to public."
                    else "A separate identity nobody can link to you, with every privacy feature on.",
                    style = MaterialTheme.typography.bodySmall, color = MeshColors.TextSecondary,
                )
            }
        }
        if (confirmSwitch) com.xalvxnlab.meshron.ui.components.PageDialog(
            onDismissRequest = { confirmSwitch = false },
            title = { Text(if (com.xalvxnlab.meshron.identity.Mode.anon) "Go public?" else "Go anonymous?") },
            text = {
                Text(
                    if (com.xalvxnlab.meshron.identity.Mode.anon) "Meshron restarts as your public self: your name, @username, photo and public chats. Your anonymous identity and its chats stay hidden until you switch back."
                    else "Meshron restarts as a separate anonymous identity: a random name, no @username, photo or bio, connections only through relays (your IP stays hidden), messages that disappear after 24 hours, no screenshots, and silent notifications. Your public chats stay hidden until you switch back, and messages to them arrive then."
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { com.xalvxnlab.meshron.identity.Mode.switchTo(context, !com.xalvxnlab.meshron.identity.Mode.anon) }) { Text("Switch") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmSwitch = false }) { Text("Cancel") } },
        )

        // ---- Account ----
        if (!com.xalvxnlab.meshron.identity.Mode.anon) com.xalvxnlab.meshron.ui.account.SecuritySection() else SectionHeader("Account")
        // Recovery key (and name in anonymous mode).
        Spacer(Modifier.height(8.dp))
        SettingsGroup {
            // Public: name and @username are changed in Edit profile. Stealth has no profile page.
            if (com.xalvxnlab.meshron.identity.Mode.anon) {
                SettingsRow(Icons.Rounded.Person, MeshColors.TileBlue, "Name", value = identity.username) { editing = true }
                SettingsDivider()
            }
            SettingsRow(Icons.Rounded.Key, MeshColors.TileOrange, "Recovery key", subtitle = "Restore your ID on a new phone") { showBackup = true }
        }


        // ---- Privacy ----
        SectionHeader("Privacy & safety")
        SettingsGroup {
            SettingsRow(Icons.Rounded.Lock, MeshColors.TileIndigo, "App lock",
                subtitle = if (pinHash != null) "PIN on · locks after a minute away" else "Ask for a PIN to open Meshron",
                checked = pinHash != null) { if (pinHash != null) profileScope.launch { app.settings.setPin(null) } else settingPin = true }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Timer, MeshColors.TileTeal, "Disappearing messages",
                value = when (disappear) { 0 -> "Off"; 1 -> "1 hour"; 24 -> "24 hours"; 168 -> "7 days"; else -> "$disappear h" }) {
                val next = listOf(0, 1, 24, 168)
                profileScope.launch { app.settings.setDisappearHours(next[(next.indexOf(disappear) + 1) % next.size]) }
            }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Shield, MeshColors.TileGreen, "Hide my IP address",
                subtitle = "Internet links go through relays (slower). Applies after restart.",
                checked = hideIp) { profileScope.launch { app.settings.setHideIp(!hideIp) } }
            SettingsDivider()
            val allowRequests by app.settings.allowRequests.collectAsState(initial = true)
            SettingsRow(Icons.Rounded.PersonAdd, MeshColors.TileBlue, "Chat requests",
                subtitle = if (allowRequests) "People who aren't your friends can ask to chat" else "Off: only friends can reach you",
                checked = allowRequests) { profileScope.launch { app.settings.setAllowRequests(!allowRequests) } }
            SettingsDivider()
            val autoTr by app.settings.autoTranslate.collectAsState(initial = true)
            SettingsRow(Icons.Rounded.ChatBubble, MeshColors.TileGreen, "Translate chats automatically",
                subtitle = if (autoTr) "Messages in another language show in yours. Those messages are sent to Meshron's AI to translate." else "Off: messages stay as they were sent",
                checked = autoTr) { profileScope.launch { app.settings.setAutoTranslate(!autoTr) } }
            SettingsDivider()
            val readReceipts by app.settings.readReceipts.collectAsState(initial = true)
            SettingsRow(Icons.Filled.DoneAll, MeshColors.TileTeal, "Read receipts",
                subtitle = if (readReceipts) "People see when you've read their messages" else "Off: you won't send or see \"Seen\"",
                checked = readReceipts) { profileScope.launch { app.settings.setReadReceipts(!readReceipts) } }
            if (!com.xalvxnlab.meshron.identity.Mode.anon) {
                SettingsDivider()
                var cmFrom by remember { mutableStateOf("everyone") }
                LaunchedEffect(Unit) { app.reels.ready.value?.let { ch -> runCatching { app.reels.profile(ch) }.getOrNull()?.let { cmFrom = it.author.cm } } }
                val labels = mapOf("everyone" to "Everyone", "followers" to "Followers", "friends" to "Friends", "off" to "No one")
                SettingsRow(Icons.Rounded.ChatBubble, MeshColors.TileOrange, "Who can comment on my videos", value = labels[cmFrom] ?: "Everyone") {
                    val order = listOf("everyone", "followers", "friends", "off")
                    val next = order[(order.indexOf(cmFrom) + 1) % order.size]
                    cmFrom = next
                    profileScope.launch { runCatching { app.reels.setCommentsFrom(next) } }
                }
            }
            SettingsDivider()
            var showBlocked by remember { mutableStateOf(false) }
            SettingsRow(Icons.Rounded.Block, MeshColors.TileGrey, "Blocked accounts", subtitle = "People you blocked can't reach you") { showBlocked = true }
            if (showBlocked) BlockedList(onClose = { showBlocked = false })
        }

        // ---- Notifications ----
        SectionHeader("Notifications")
        SettingsGroup {
            SettingsRow(Icons.Rounded.NotificationsActive, MeshColors.TileRose, "Stay connected",
                subtitle = "Messages and calls when the app is closed", checked = stayConnected) {
                val on = !stayConnected
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                scope.launch {
                    app.settings.setStayConnected(on)
                    app.applyBackgroundSetting()
                }
            }
        }

        // ---- Connections ----
        SectionHeader("Connections")
        if (missingPermission) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(16.dp)).background(MeshColors.Waiting.copy(alpha = 0.10f))
                    .clickable { askLocal.launch(LocalTransports.allPermissions()) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WarningAmber, null, tint = MeshColors.Waiting)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Allow nearby devices", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Needed to find phones without internet. Meshron never reads your location.",
                        style = MaterialTheme.typography.bodySmall, color = MeshColors.TextSecondary,
                    )
                }
            }
        }
        SettingsGroup {
            SettingsRow(Icons.Rounded.Language, MeshColors.TileBlue, "Internet", subtitle = "Reach contacts anywhere", checked = useInternet) {
                scope.launch { app.settings.setUseInternet(!useInternet) }
            }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Wifi, MeshColors.TileTeal, "Same Wi-Fi",
                subtitle = "Phones on your network or hotspot" + count(sameWifi, local.lanLinks), checked = sameWifi) {
                if (!sameWifi) askLocal.launch(LocalTransports.allPermissions())
                scope.launch { app.settings.setSameWifi(!sameWifi) }
            }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Bluetooth, MeshColors.TileIndigo, "Bluetooth & Wi-Fi Direct",
                subtitle = if (nearby && local.nearbyError != null) "Not available on this phone" else "Phones close by" + count(nearby, local.nearbyLinks),
                checked = nearby) {
                if (!nearby) askLocal.launch(LocalTransports.allPermissions())
                scope.launch { app.settings.setNearby(!nearby) }
            }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Hub, MeshColors.TileGreen, "Pass messages on",
                subtitle = "Carry others' locked messages", checked = carry) {
                scope.launch { app.settings.setCarryForOthers(!carry) }
            }
        }
        Text(
            "Meshron uses every way that's switched on. Messages and calls are end-to-end encrypted on all of them.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshColors.TextTertiary,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )

        // ---- Appearance ----
        if (!com.xalvxnlab.meshron.identity.Mode.anon) {
            val theme by app.settings.theme.collectAsState(initial = "system")
            SectionHeader("Appearance")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((id, label) in listOf("system" to "Auto", "light" to "Light", "dark" to "Dark")) {
                    val on = theme == id
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                            .background(if (on) MeshColors.AccentContainer else MeshColors.Surface1)
                            .border(2.dp, if (on) MeshColors.Accent else Color.Transparent, RoundedCornerShape(18.dp))
                            .clickable { profileScope.launch { app.settings.setTheme(id) } }.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // A tiny preview of the look.
                        val (bg, fg) = when (id) { "light" -> Color(0xFFF5F6F9) to Color(0xFF11141B); "dark" -> Color(0xFF0E1015) to Color(0xFFEEF1F7); else -> Color(0xFF7A808C) to Color.White }
                        Box(Modifier.size(width = 44.dp, height = 30.dp).clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, MeshColors.Hairline, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(width = 24.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(fg))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MeshColors.OnAccentContainer else MeshColors.TextPrimary)
                    }
                }
            }
        }


        // ---- Verification & moderation (public profile only) ----
        if (!com.xalvxnlab.meshron.identity.Mode.anon) {
            val myId = remember(identity) { identity.publicKey.toHex() }
            val badges by app.moderation.badges.collectAsState()
            val role by app.moderation.role.collectAsState()
            var asking by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { app.moderation.want(listOf(myId)); app.moderation.checkRole() }
            SectionHeader("Verification & badges")
            SettingsGroup {
                val verified = badges[myId]?.verified == true
                var guide by remember { mutableStateOf(false) }
                SettingsRow(Icons.Rounded.Shield, MeshColors.TileIndigo, "Meshron badges", subtitle = "What the verified, moderator and founder marks mean") { guide = true }
                if (guide) com.xalvxnlab.meshron.ui.components.PageDialog(onDismissRequest = { guide = false }, title = { Text("Meshron badges") }, text = { com.xalvxnlab.meshron.ui.components.BadgeGuide() }, confirmButton = { TextButton(onClick = { guide = false }) { Text("Got it") } })
                SettingsDivider()
                SettingsRow(Icons.Rounded.Verified, MeshColors.TileBlue, if (verified) "You're verified" else "Request verification",
                    subtitle = if (verified) "Your profile shows the verified check" else "For known people, creators and brands. A person reviews it.") { if (!verified) asking = true }
                var modOpen by remember { mutableStateOf(false) }
                var applying by remember { mutableStateOf(false) }
                SettingsDivider()
                if (role != null) {
                    SettingsRow(Icons.Rounded.Shield, MeshColors.TileIndigo, "Moderator", subtitle = if (role == "admin") "Reports, appeals, verification, applications and your team" else "Reports, appeals, removals and verification") { modOpen = true }
                } else {
                    SettingsRow(Icons.Rounded.Shield, MeshColors.TileIndigo, "Become a moderator", subtitle = "Help keep Meshron safe. The CEOs review applications.") { applying = true }
                }
                if (modOpen) ModeratorApp { modOpen = false; profileScope.launch { app.moderation.checkRole() } }
                if (applying) ModeratorApply(identity.username) { applying = false }
            }
            if (asking) VerificationRequest(identity.username, identity.handle, onClose = { asking = false })
        }

        // ---- Your data & legal ----
        SectionHeader("Your data & legal")
        SettingsGroup {
            val context = androidx.compose.ui.platform.LocalContext.current
            val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) profileScope.launch {
                    val ok = runCatching { exportMyData(app, uri) }.isSuccess
                    com.xalvxnlab.meshron.ui.components.Toasts.show(context, if (ok) "Saved your data" else "Couldn't save it")
                }
            }
            SettingsRow(Icons.Rounded.Download, MeshColors.TileTeal, "Download my data", subtitle = "Profile, friends, groups and messages on this phone, as a file") {
                save.launch("meshron-data-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json")
            }
            var reading by remember { mutableStateOf<com.xalvxnlab.meshron.ui.legal.LegalDoc?>(null) }
            for (d in listOf(com.xalvxnlab.meshron.ui.legal.TERMS, com.xalvxnlab.meshron.ui.legal.PRIVACY, com.xalvxnlab.meshron.ui.legal.GUIDELINES)) {
                SettingsDivider()
                SettingsRow(Icons.Rounded.Description, MeshColors.TileGrey, d.title) { reading = d }
            }
            reading?.let { com.xalvxnlab.meshron.ui.legal.LegalDocScreen(it) { reading = null } }
        }

        // ---- About ----
        SectionHeader("About")
        SettingsGroup {
            SettingsRow(Icons.Rounded.Info, MeshColors.TileGrey, "About Meshron", subtitle = "Version, privacy policy, licenses") { showAbout = true }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Tune, MeshColors.TileGrey, "Connection details", subtitle = "For troubleshooting") { showDetails = true }
        }

        // ---- Emergency (SOS lives here, at the very bottom) ----
        SectionHeader("Emergency")
        SettingsGroup {
            SettingsRow(Icons.Rounded.WarningAmber, androidx.compose.ui.graphics.Color(0xFFE5243B), "Emergency SOS", subtitle = "Send an urgent message with your location to all your contacts") { showSos = true }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
    }
    if (showSos) com.xalvxnlab.meshron.ui.sos.SosScreen(onClose = { showSos = false })

    if (showDetails) com.xalvxnlab.meshron.ui.components.PageDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("Connection details") },
        text = {
            Column {
                val dht = net.dht
                InfoRow(
                    "Network", when (net.status) {
                        NetworkState.Status.STOPPED -> "Not started"
                        NetworkState.Status.STARTING -> "Starting…"
                        NetworkState.Status.ONLINE -> when {
                            dht?.internet == false -> "Nearby only"
                            dht?.bootstrapped == true -> "Online"
                            else -> "Joining DHT…"
                        }
                        NetworkState.Status.FAILED -> "Failed"
                    }
                )
                InfoRow("Connected now", net.peers.values.count { it.state == PeerInfo.State.CONNECTED }.toString())
                InfoRow("Public address", dht?.host?.let { "$it:${dht.port}" } ?: "–")
                InfoRow("NAT", dht?.natLabel ?: "–")
                InfoRow("Open connections", (dht?.connections ?: 0).toString())
                InfoRow("Relay", "None")
                InfoRow("Key matches ID", when (net.keyMatches) { true -> "Yes"; false -> "NO"; null -> "–" })
                InfoRow("Key type", "Ed25519 · Android Keystore")
                InfoRow("Messages", "SQLCipher database")
                InfoRow("Identity created", DateFormat.getDateInstance().format(Date(identity.createdAt)))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { selfTest = repo.selfTest() } }) { Text("Run key check") }
                    when (selfTest) {
                        true -> Text("Passed", color = MeshColors.Online)
                        false -> Text("Failed", color = MaterialTheme.colorScheme.error)
                        null -> {}
                    }
                }
                net.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                Text(
                    net.log.takeLast(15).joinToString("\n").ifEmpty { "–" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshColors.TextSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } },
    )

    if (showBackup) RecoveryKeyDialog(load = { repo.recoveryKey() }, onDismiss = { showBackup = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    if (editingHandle) HandleDialog(
        initial = identity.handle,
        myKey = identity.publicKey.toHex(),
        check = { network.findHandle(it) },
        onDismiss = { editingHandle = false },
        onSave = { h -> scope.launch { repo.setHandle(h); editingHandle = false } },
    )

    if (editing) RenameDialog(
        initial = identity.username,
        onDismiss = { editing = false },
        onSave = { name -> scope.launch { repo.rename(name); editing = false } },
    )

    if (showQr) AlertDialog(
        onDismissRequest = { showQr = false },
        title = { Text("Scan to add ${identity.username}") },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(identity.shareLink) } },
        confirmButton = { TextButton(onClick = { showQr = false }) { Text("Done") } },
    )
}

private fun count(on: Boolean, n: Int) = if (on && n > 0) " · $n connected" else ""

/** A square-ish action under the profile header: icon over a short label. */
@Composable
private fun HeaderAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MeshColors.Surface1).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).background(MeshColors.Gradient, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MeshColors.TextPrimary)
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun SettingSwitch(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            Column {
                Text(
                    "This is what your contacts see.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Identity.MAX_NAME_LENGTH + 8) name = it },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = Identity.isValidName(name)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Choose the findable @username. Before saving, looks it up: with no central server nobody
 * can own a name, but we warn if someone else already announced it.
 */
@Composable
private fun HandleDialog(
    initial: String,
    myKey: String,
    check: suspend (String) -> NetworkService.HandleResult,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var value by rememberSaveable { mutableStateOf(initial) }
    var checking by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    val h = Identity.cleanHandle(value)
    val problem = if (h.isEmpty()) null else Identity.handleProblem(h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your @username") },
        text = {
            Column {
                Text(
                    "Friends can find you by typing it in \"Add contact\". Letters, numbers and _, 3 to 20 long.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(24); warning = null },
                    prefix = { Text("@") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = { Text(problem ?: warning ?: "Leave empty to not be findable by name.") },
                )
            }
        },
        confirmButton = {
            if (warning != null) {
                TextButton(onClick = { onSave(h) }) { Text("Use anyway") }
            } else {
                TextButton(
                    enabled = problem == null && !checking,
                    onClick = {
                        if (h.isEmpty() || h == initial) return@TextButton onSave(h)
                        checking = true
                        scope.launch {
                            val r = check(h)
                            checking = false
                            val others = r.keys.filter { it != myKey }
                            when {
                                others.isNotEmpty() -> warning = "Someone else already uses @$h. Pick another, or use it anyway (friends will see two and must pick by ID)."
                                else -> onSave(h)
                            }
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MeshColors.PurpleBright)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** Ask Meshron for the verified check: who you are, and links that prove it. */
@Composable
private fun VerificationRequest(name0: String, handle: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.xalvxnlab.meshron.MeshronApp
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(name0) }
    var category by remember { mutableStateOf("Creator") }
    var reason by remember { mutableStateOf("") }
    var links by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Request verification") },
        text = {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("The check shows people it's really you. A person at Meshron reviews every request.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Full name") }, singleLine = true)
                androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(vertical = 8.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                    for (c in listOf("Creator", "Public figure", "Business", "Organisation", "Journalist")) {
                        androidx.compose.material3.FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }, modifier = androidx.compose.ui.Modifier.padding(end = 6.dp))
                    }
                }
                OutlinedTextField(reason, { reason = it.take(1000) }, label = { Text("Why should you be verified?") }, minLines = 3)
                OutlinedTextField(links, { links = it.take(500) }, label = { Text("Links (website, other socials, news)") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && reason.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val r = runCatching { app.moderation.requestVerification(name, handle, category, reason, links) }
                    com.xalvxnlab.meshron.ui.components.Toasts.show(context, r.fold({ "Sent. You'll hear back in Activity." }, { it.message ?: "Couldn't send it" }), long = true)
                    onClose()
                }
            }) { Text(if (busy) "Sending…" else "Send request") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}


/** People you blocked, with Unblock. */
@Composable
private fun BlockedList(onClose: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
    val scope = rememberCoroutineScope()
    val contacts by app.contactsRepository.contacts.collectAsState(initial = emptyList())
    val blocked = contacts.filter { it.status == com.xalvxnlab.meshron.contacts.Contact.Status.BLOCKED }
    com.xalvxnlab.meshron.ui.components.PageDialog(
        onDismissRequest = onClose,
        title = { Text("Blocked accounts") },
        text = {
            androidx.compose.foundation.layout.Column {
                if (blocked.isEmpty()) Text("Nobody is blocked.", color = MeshColors.TextSecondary)
                for (c in blocked) androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    com.xalvxnlab.meshron.ui.components.Avatar(c.displayName, c.keyHex, 36.dp)
                    Text("  " + c.displayName, modifier = androidx.compose.ui.Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { app.contactsRepository.unblock(c.keyHex) } }) { Text("Unblock") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

/** Writes what this phone holds about me (no secrets: no keys, no PIN) to [uri] as JSON. */
private suspend fun exportMyData(app: com.xalvxnlab.meshron.MeshronApp, uri: android.net.Uri) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val me = app.identityRepository.identity.first()
    val o = org.json.JSONObject().put("exported", java.util.Date().toString()).put("app", "Meshron")
    o.put("identity", org.json.JSONObject().put("name", me?.username).put("handle", me?.handle).put("publicKey", me?.publicKey?.toHex()))
    o.put("profile", org.json.JSONObject().put("bio", app.profiles.me.value.bio))
    o.put("friends", org.json.JSONArray(app.contactsRepository.contacts.first().map { c ->
        org.json.JSONObject().put("name", c.displayName).put("handle", c.handle).put("id", c.keyHex).put("status", c.status.name).put("since", c.addedAt)
    }))
    o.put("groups", org.json.JSONArray(app.messagingService.allGroupsNow().map { g ->
        org.json.JSONObject().put("name", g.name).put("id", g.id).put("bio", g.bio).put("members", g.memberList.size)
    }))
    o.put("messages", org.json.JSONArray(app.messagingService.exportAll().map { m ->
        org.json.JSONObject().put("chat", m.peerKey).put("fromMe", m.outgoing).put("from", m.senderKey ?: if (m.outgoing) "me" else m.peerKey)
            .put("time", m.sentAt).put("kind", m.kind).put("text", if (m.kind == "text") m.body else if (m.kind == "file") "[file]" else "[deleted]")
    }))
    app.contentResolver.openOutputStream(uri)?.use { it.write(o.toString(2).toByteArray()) } ?: error("Couldn't open the file")
}
