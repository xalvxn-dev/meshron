package com.xalvxnlab.meshron.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.identity.Mode
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.reels.Reel
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.HeaderButton
import com.xalvxnlab.meshron.ui.home.shareIdentity
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch

private fun fmt(n: Int) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 10_000 -> "${n / 1000}K"; n >= 1000 -> "%.1fK".format(n / 1e3); else -> "$n" }

/** Profile tab: you as others see you, your videos, and doors to Edit profile and Settings. */
@Composable
fun MyProfile(identity: Identity, onEdit: () -> Unit, onSettings: () -> Unit, onAddFriend: () -> Unit, onOpenVideo: (List<Reel>, Int) -> Unit = { _, _ -> }, onOpenPerson: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val me by app.profiles.me.collectAsState()
    var following by remember { mutableStateOf<Int?>(null) }
    var privateVideos by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var peopleTab by remember { mutableStateOf<String?>(null) }
    peopleTab?.let { PeoplePage(it, onOpen = onOpenPerson) { peopleTab = null } }
    val ready by app.reels.ready.collectAsState()
    var followers by remember { mutableIntStateOf(0) }
    var likes by remember { mutableIntStateOf(0) }
    var videos by remember { mutableStateOf<List<Reel>>(emptyList()) }
    var tab by remember { mutableStateOf("videos") }
    var other by remember { mutableStateOf<List<Reel>>(emptyList()) }
    // Reposts, saved and liked load when their tab opens (and refresh when reels change).
    LaunchedEffect(ready, tab) {
        val ch = ready ?: return@LaunchedEffect
        if (tab == "videos" || tab == "private") return@LaunchedEffect
        suspend fun load() { other = runCatching { app.reels.feed(tab, ch = ch, limit = 200).first }.getOrDefault(emptyList()) }
        load()
        app.reels.changed.collect { load() }
    }
    LaunchedEffect(ready) {
        val ch = ready ?: return@LaunchedEffect
        suspend fun load() {
            runCatching { app.reels.profile(ch) }.getOrNull()?.let { p -> likes = p.likes; videos = p.reels.filter { !it.private } }
            // Private videos have their own tab (only you see them), not mixed into the grid.
            runCatching { app.reels.feed("private", limit = 200).first }.getOrNull()?.let { privateVideos = it }
            runCatching { app.reels.people("following") }.getOrNull()?.let { following = it.following; followers = it.followers }
        }
        load()
        app.reels.changed.collect { if (ch in it) load() }
    }
    val anon = Mode.anon
    var showQr by remember { mutableStateOf(false) }
    if (showQr) ShareCard(identity) { showQr = false }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(span = { GridItemSpan(3) }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (identity.handle.isNotEmpty()) "@" + identity.handle else identity.username, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (!anon) HeaderButton(Icons.Rounded.QrCode2, "My QR code") { showQr = true }
                    if (!anon) HeaderButton(Icons.Rounded.Share, "Share profile") { shareIdentity(context, identity) }
                    HeaderButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
                }
                Spacer(Modifier.height(14.dp))
                MeHead(identity, me.bio.takeIf { !anon }.orEmpty(), me.link.takeIf { !anon }.orEmpty(), following.takeIf { !anon }, followers.takeIf { !anon }, likes.takeIf { !anon }) { peopleTab = it }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Edit profile", Modifier.weight(1f), primary = true, onClick = onEdit)
                    Btn("Add friends", Modifier.weight(1f), onClick = onAddFriend)
                }
                Spacer(Modifier.height(18.dp))
                // Tabs like other apps: your videos, reposts, saved (only you) and liked (only you).
                if (!anon) Row(Modifier.fillMaxWidth()) {
                    for ((id, ic) in listOfNotNull("videos" to Icons.Rounded.GridView, ("private" to Icons.Rounded.Lock).takeIf { privateVideos.isNotEmpty() || tab == "private" }, "reposts" to Icons.Rounded.Repeat, "favourites" to Icons.Rounded.Bookmark, "liked" to Icons.Rounded.Favorite)) {
                        val on = tab == id
                        Column(Modifier.weight(1f).clickable { tab = id }.padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(ic, id, tint = if (on) MeshColors.TextPrimary else MeshColors.TextTertiary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) MeshColors.TextPrimary else MeshColors.Surface3))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                val shownHere = when (tab) { "videos" -> videos; "private" -> privateVideos; else -> other }
                if (!anon && shownHere.isEmpty()) Text(when (tab) {
                    "private" -> "Videos you make private show up here. Only you can see them."
                    "reposts" -> "Videos you repost show up here."
                    "favourites" -> "Videos you save show up here. Only you can see them."
                    "liked" -> "Videos you like show up here. Only you can see them."
                    else -> "Your reels show up here. Post one from the + button."
                }, color = MeshColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        }
        val shown = when (tab) { "videos" -> videos; "private" -> privateVideos; else -> other }
        if (!anon) items(shown.size, key = { tab + shown[it].key + shown[it].private }) { i -> VideoTile(app, shown[i], repost = tab == "reposts", onClick = { onOpenVideo(shown, i) }) }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Btn(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(if (primary) MeshColors.TextPrimary else MeshColors.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) MeshColors.Bg else MeshColors.TextPrimary, fontWeight = FontWeight.Bold) }
}

@Composable
private fun VideoTile(app: MeshronApp, r: Reel, repost: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(9f / 16f).background(MeshColors.Surface2).clickable(onClick = onClick)) {
        val img = remember(r.thumb) { app.reels.image(r.thumb) }
        if (img != null) Image(img, r.cap, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.PlayArrow, null, tint = MeshColors.TextTertiary, modifier = Modifier.align(Alignment.Center))
        // Removed by moderators: still yours, marked; appeal from Activity → From Meshron.
        if (r.removed) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Text("Removed\nAppeal in Activity", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        if (repost) Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(5.dp)) {
            Icon(Icons.Rounded.Repeat, "Reposted", tint = Color.White, modifier = Modifier.size(13.dp))
        }
        if (r.private) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(5.dp)) {
            Icon(Icons.Rounded.Lock, "Private", tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(13.dp))
            Text(" " + fmt(r.likes), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PageTop(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Field(label: String, value: String, max: Int, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(max)) }, label = { Text(label) },
        singleLine = lines == 1, minLines = lines, maxLines = lines + 1, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        supportingText = if (lines > 1) ({ Text("${value.length}/$max") }) else null,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshColors.Accent, unfocusedBorderColor = MeshColors.Hairline, focusedContainerColor = MeshColors.Surface1, unfocusedContainerColor = MeshColors.Surface1),
    )
}

/** Settings: everything that isn't your profile. */
@Composable
fun SettingsScreen(identity: Identity, repo: IdentityRepository, network: NetworkService, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MeshColors.Bg)) {
        PageTop("Settings", onBack)
        Box(Modifier.weight(1f)) { ProfileTab(identity, repo, network, settingsOnly = true) }
    }
}

/**
 * The Meshron Card: your profile as an ID card on a living aurora mesh. Tap to flip it over
 * to your QR code, so friends can scan it right there.
 */
@Composable
fun MeshronCard(identity: Identity, bio: String, friends: Int, followers: Int?, likes: Int?) {
    var back by remember { mutableStateOf(false) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (back) 180f else 0f, androidx.compose.animation.core.tween(450), label = "flip")

    // Still (a drifting background redrew every frame while Me was open).
    val drift = 0.5f
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(220.dp)
            .graphicsLayer { rotationY = turn; cameraDistance = 14f * density }
            .clip(shape).clickable { back = !back },
    ) {
        if (turn <= 90f) {
            // Front: layered aurora blobs that drift slowly.
            Box(Modifier.fillMaxSize().background(MeshColors.Surface2).drawBehind {
                val w = size.width; val h = size.height
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(MeshColors.GradStart.copy(alpha = 0.85f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * (0.15f + drift * 0.2f), h * 0.2f), radius = w * 0.6f), radius = w, center = center)
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(MeshColors.GradMid.copy(alpha = 0.75f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * (0.85f - drift * 0.15f), h * 0.35f), radius = w * 0.55f), radius = w, center = center)
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(MeshColors.GradEnd.copy(alpha = 0.6f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * (1.05f - drift * 0.2f)), radius = w * 0.5f), radius = w, center = center)
                // Fine mesh lines: Meshron's signature.
                val step = 26.dp.toPx()
                var x = 0f
                while (x < w) { drawLine(Color.White.copy(alpha = 0.07f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x + h * 0.4f, h), 1f); x += step }
            }) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(26.dp)).padding(3.dp)) { Avatar(identity.username, identity.publicKey.toHex(), 72.dp) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(identity.username, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                            if (identity.handle.isNotEmpty()) Text("@" + identity.handle, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall)
                        }
                        Icon(Icons.Rounded.QrCode2, "Show my QR code", tint = Color.White.copy(alpha = 0.9f))
                    }
                    if (bio.isNotBlank()) Text(bio, color = Color.White.copy(alpha = 0.92f), maxLines = 2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CardStat(fmt(friends), "friends")
                        followers?.let { CardStat(fmt(it), "followers") }
                        likes?.let { CardStat(fmt(it), "likes") }
                    }
                }
            }
        } else {
            // Back: the QR code (mirrored so it reads the right way round).
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.background(Color.White), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.xalvxnlab.meshron.ui.components.QrCode(identity.shareLink, size = 170.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Scan to add", color = Color(0xFF11141B), style = MaterialTheme.typography.titleMedium)
                        if (identity.handle.isNotEmpty()) Text("@" + identity.handle, color = Color(0xFF5A6274))
                        Text("Tap to flip back", color = Color(0xFF8B92A3), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CardStat(value: String, label: String) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.22f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(value, color = Color.White, fontWeight = FontWeight.ExtraBold)
        Text(" $label", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Profile header: a living aurora-mesh cover with your photo overlapping its edge, then your
 * name, @username, bio and counts. The QR button on the cover shows your code.
 */
@Composable
fun MeshronCover(identity: Identity, bio: String, friends: Int, followers: Int?, likes: Int?) {
    var qr by remember { mutableStateOf(false) }

    // Still (a drifting background redrew every frame while Me was open).
    val drift = 0.5f
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(186.dp)) {
            Box(
                Modifier.fillMaxWidth().height(132.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(28.dp)).background(MeshColors.Surface2).drawBehind {
                    val w = size.width; val h = size.height
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(MeshColors.GradStart.copy(alpha = 0.9f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * (0.15f + drift * 0.25f), h * 0.3f), radius = w * 0.55f), radius = w, center = center)
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(MeshColors.GradMid.copy(alpha = 0.8f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * (0.85f - drift * 0.2f), h * 0.4f), radius = w * 0.5f), radius = w, center = center)
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(MeshColors.GradEnd.copy(alpha = 0.65f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * (1.1f - drift * 0.3f)), radius = w * 0.45f), radius = w, center = center)
                    val step = 24.dp.toPx()
                    var x = -h
                    while (x < w) { drawLine(Color.White.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x + h, h), 1f); x += step }
                },
            ) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(38.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.25f)).clickable { qr = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.QrCode2, "Show my QR code", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
            Box(Modifier.align(Alignment.BottomCenter).background(MeshColors.Bg, CircleShape).padding(5.dp)) {
                Box(Modifier.border(2.5.dp, MeshColors.GradientH, CircleShape).padding(4.dp)) { Avatar(identity.username, identity.publicKey.toHex(), 96.dp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(identity.username, style = MaterialTheme.typography.headlineSmall)
            if (!com.xalvxnlab.meshron.identity.Mode.anon) com.xalvxnlab.meshron.ui.components.Badges(identity.publicKey.toHex(), 16.dp, full = true)
        }
        if (identity.handle.isNotEmpty()) Text("@" + identity.handle, color = MeshColors.TextSecondary, style = MaterialTheme.typography.titleSmall)
        if (bio.isNotBlank()) Text(bio, color = MeshColors.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp))
        Row(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat(fmt(friends), "Friends")
            followers?.let { Stat(fmt(it), "Followers") }
            likes?.let { Stat(fmt(it), "Likes") }
        }
    }
    if (qr) AlertDialog(
        onDismissRequest = { qr = false },
        title = { Text("Scan to add me") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp)) { com.xalvxnlab.meshron.ui.components.QrCode(identity.shareLink) }
                if (identity.handle.isNotEmpty()) Text("@" + identity.handle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { qr = false }) { Text("Done") } },
    )
}



/** Change @username: with an account the password is needed (it re-locks the account). */
@Composable
internal fun RenameDialog(identity: Identity, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    val account by app.accounts.state.collectAsState(initial = null)
    var name by remember { mutableStateOf(identity.handle) }
    var pw by remember { mutableStateOf("") }
    var free by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val clean = name.trim().removePrefix("@").lowercase()
    val valid = Regex("^[a-z0-9_]{3,24}$").matches(clean)
    LaunchedEffect(clean) {
        free = null
        if (!valid || clean == identity.handle) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        free = if (account != null) runCatching { app.accounts.available(clean) }.getOrNull() else true
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Change username") },
        text = {
            Column {
                Field("@username", name, 25) { name = it }
                Text(
                    when { !valid -> "3–24 letters, numbers or _"; clean == identity.handle -> "That's your username now"; free == false -> "@$clean is taken"; free == true -> "@$clean is free"; else -> "Checking…" },
                    color = if (free == false || !valid) MeshColors.Danger else MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
                if (account != null) {
                    Spacer(Modifier.height(10.dp))
                    com.xalvxnlab.meshron.ui.account.PasswordField(pw, "Your password") { pw = it }
                    Text("Your old username becomes free for others.", color = MeshColors.TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                error?.let { Text(it, color = MeshColors.Danger, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && valid && free == true && (account == null || pw.isNotEmpty()), onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { if (account != null) app.accounts.rename(clean, pw) else app.identityRepository.setHandle(clean, byUser = true) }
                        .onSuccess { onClose() }.onFailure { error = it.message }
                    busy = false
                }
            }) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
