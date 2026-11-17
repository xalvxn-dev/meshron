package com.xalvxnlab.meshron.ui.reels

import android.Manifest
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.reels.LiveService
import com.xalvxnlab.meshron.reels.ReelsService
import com.xalvxnlab.meshron.ui.call.VideoView
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LiveRed = Color(0xFFFF2D55)

private data class Quad(val a: String, val b: String, val c: androidx.compose.ui.graphics.vector.ImageVector, val d: String)

@Composable
private fun LiveBadge() = Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed).padding(horizontal = 8.dp, vertical = 3.dp))

/** Going live: a title, then the camera goes out to everyone who follows you. */
@Composable
fun LiveScreen(reels: ReelsService, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val live = app.live
    val scope = rememberCoroutineScope()
    val session by live.session.collectAsState()
    var title by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("camera") } // or "screen": games and apps
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == android.app.Activity.RESULT_OK && data != null) {
            com.xalvxnlab.meshron.background.ScreenLiveService.start(context, r.resultCode, data, title.ifBlank { "Live now" })
        } else { starting = false; com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Screen sharing wasn't allowed") }
    }
    fun go() {
        starting = true
        if (mode == "screen") capture.launch(context.getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent())
        else scope.launch { runCatching { live.start(title.ifBlank { "Live now" }) }; starting = false }
    }
    LaunchedEffect(session) { if (session?.hosting == true) starting = false }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) go()
        else com.xalvxnlab.meshron.ui.components.Toasts.show(context, if (mode == "screen") "Going live needs the microphone" else "Going live needs the camera and microphone", long = true)
    }
    fun end() { scope.launch { live.stop(); onClose() } }
    BackHandler { if (session?.hosting == true) end() else onClose() }

    val s = session
    if (s == null || !s.hosting) {
        // Before going live: a normal Meshron page.
        Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Go live", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                LiveBadge()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text("Title", color = MeshColors.TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MeshColors.Surface1).padding(horizontal = 18.dp, vertical = 16.dp)) {
                    BasicTextField(
                        title, { title = it.take(100) }, singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MeshColors.TextPrimary, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(MeshColors.Accent),
                        decorationBox = { inner -> Box { if (title.isEmpty()) Text(if (mode == "screen") "What are you playing?" else "What's your live about?", color = MeshColors.TextTertiary, style = MaterialTheme.typography.titleMedium); inner() } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("What to stream", color = MeshColors.TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp))
                for ((id, label, icon, sub) in listOf(
                    listOf("camera", "Camera", Icons.Rounded.Videocam, "You, live: front or back camera, with your mic."),
                    listOf("screen", "Game or screen", Icons.Rounded.SportsEsports, "Stream a game or any app in 720p at 30 fps, with game sound and your mic. Turn your phone sideways for landscape games."),
                ).map { Quad(it[0] as String, it[1] as String, it[2] as androidx.compose.ui.graphics.vector.ImageVector, it[3] as String) }) {
                    val on = mode == id
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.Surface1)
                            .border(2.dp, if (on) MeshColors.Accent else Color.Transparent, RoundedCornerShape(18.dp)).clickable { mode = id }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(if (on) MeshColors.Gradient else androidx.compose.ui.graphics.SolidColor(MeshColors.Surface2)), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = if (on) Color.White else MeshColors.TextSecondary)
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(sub, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Everyone on Meshron can find your live, watch, chat and send hearts. Nothing is recorded. Follow the Community Guidelines.",
                    color = MeshColors.TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp))
            }
            Box(
                Modifier.padding(16.dp).padding(bottom = 48.dp).fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.Gradient).clickable(enabled = !starting) {
                    val need = if (mode == "screen") listOf(Manifest.permission.RECORD_AUDIO) else listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    if (need.all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) go() else ask.launch(need.toTypedArray())
                },
                contentAlignment = Alignment.Center,
            ) {
                if (starting) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (mode == "screen") "Start streaming" else "Go live", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
        }
        return
    }

    // Live: my camera full screen, chat and hearts over it.
    val local by (live.localFormat ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    var muted by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (s.screen) Box(Modifier.fillMaxSize().background(MeshColors.Bg), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MeshColors.Surface1).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SportsEsports, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("You're streaming your screen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Open your game: everything on screen and its sound go live. Come back here for chat, or end it from the notification.",
                    color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(MeshColors.Gradient)
                    .clickable { context.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    contentAlignment = Alignment.Center) { Text("Open a game", color = Color.White, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
                var game by remember { mutableStateOf(0.9f) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Game sound", color = MeshColors.TextSecondary, modifier = Modifier.width(110.dp))
                    androidx.compose.material3.Slider(game, { game = it; live.setGameVolume(it) }, modifier = Modifier.weight(1f))
                }
            }
        } else VideoView(format = local, crop = true, onTexture = { live.setPreview(it) }, modifier = Modifier.fillMaxSize())
        LiveOverlay(live, s, onClose = { confirmEnd = true }) {
            RoundIcon(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute") { muted = !muted; live.setMuted(muted) }
            if (!s.screen) RoundIcon(Icons.Rounded.Cameraswitch, "Flip camera") { live.switchCamera() }
        }
    }
    if (confirmEnd) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("End your live?") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmEnd = false; end() }) { Text("End", color = LiveRed) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
    )
}

/** Watching someone's live. */
@Composable
fun WatchLive(item: ReelsService.LiveNow, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val live = app.live
    val scope = rememberCoroutineScope()
    val session by live.session.collectAsState()
    val me by app.identityRepository.identity.collectAsState(initial = null)
    LaunchedEffect(item.author.id) { live.watch(item.author.id, item.lid, item.title, me?.username ?: "") }
    DisposableEffect(Unit) { onDispose { scope.launch { live.stop() } } }
    BackHandler(onBack = onClose)
    val s = session ?: return
    val remote by (live.remoteFormat ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val activity = remember(context) { generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull() }
    var landscape by remember { mutableStateOf(false) }
    DisposableEffect(landscape) {
        activity?.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    val wide = remote?.let { it.width > it.height } == true
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoView(format = remote, crop = !item.screen, onTexture = { st -> live.setRemoteSurface(st?.let { Surface(it) }) }, modifier = Modifier.fillMaxSize())
        if (remote == null && !s.ended) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Text("Joining ${item.author.label}…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
        }
        if (s.ended) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (s.full) "This live is full" else "This live has ended", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Spacer(Modifier.height(14.dp))
            Text("Close", color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(onClick = onClose).padding(horizontal = 26.dp, vertical = 10.dp))
        }
        LiveOverlay(live, s, author = item.author, onClose = onClose, chatOpen = !landscape) {
            // Landscape games fill the screen sideways.
            if (item.screen || wide) RoundIcon(Icons.Rounded.ScreenRotation, if (landscape) "Portrait" else "Full screen") { landscape = !landscape }
        }
    }
}

/** Top: who/LIVE/viewers/close. Bottom: chat, input, hearts. */
@Composable
private fun LiveOverlay(live: LiveService, s: LiveService.Session, author: com.xalvxnlab.meshron.reels.ReelAuthor? = null, onClose: () -> Unit, chatOpen: Boolean = true, extra: @Composable () -> Unit) {
    val viewers by live.viewers.collectAsState()
    val chat by live.chat.collectAsState()
    var text by remember { mutableStateOf("") }
    val floating = remember { mutableStateListOf<Long>() }
    LaunchedEffect(Unit) { live.hearts.collect { n -> repeat(n.coerceAtMost(8)) { floating += System.nanoTime() + it } } }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))).statusBarsPadding().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (author != null) {
                Avatar(author.name, author.id, 36.dp)
                Column(Modifier.padding(start = 8.dp).weight(1f, fill = false)) {
                    Text(author.label, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (s.title.isNotBlank()) Text(s.title, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
            } else if (s.title.isNotBlank()) Text(s.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp))
            Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(LiveRed).padding(horizontal = 7.dp, vertical = 2.dp))
            Row(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Visibility, null, tint = Color.White, modifier = Modifier.size(13.dp))
                Text(" $viewers", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            extra()
            RoundIcon(Icons.Rounded.Close, if (s.hosting) "End live" else "Close", onClose)
        }
        // Hearts float up the right side.
        for (id in floating.toList()) key(id) {
            val y = remember { Animatable(0f) }
            val dx = remember { (-30..30).random() }
            LaunchedEffect(Unit) { y.animateTo(1f, tween(1800)); floating.remove(id) }
            Icon(Icons.Rounded.Favorite, null, tint = listOf(LiveRed, Color(0xFFFFC83D), Color(0xFFB36BFF), Color(0xFF2EE59D)).random(),
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 22.dp, bottom = 90.dp)
                    .offset(x = dx.dp, y = (-380 * y.value).dp).size(30.dp).graphicsLayer { alpha = 1f - y.value })
        }
        if (chatOpen) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
            .navigationBarsPadding().imePadding().padding(12.dp)) {
            val list = rememberLazyListState()
            LaunchedEffect(chat.size) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.lastIndex) }
            LazyColumn(Modifier.fillMaxWidth(0.78f).heightIn(max = 240.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(chat) { c ->
                    if (c.joined) Text("${c.name} joined", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    else Text(buildString { append(c.name); append("  ") }.let { androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.75f))); append(it); pop(); append(c.text)
                    } }, color = Color.White, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!s.ended) Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f)).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(text, { text = it.take(200) }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color.White),
                        decorationBox = { inner -> Box { if (text.isEmpty()) Text("Say something…", color = Color.White.copy(alpha = 0.6f)); inner() } },
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    if (text.isNotBlank()) RoundIcon(Icons.AutoMirrored.Rounded.Send, "Send") { live.sendChat(text); text = "" }
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable { live.heart() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Favorite, "Send a heart", tint = LiveRed)
                }
            }
        }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.padding(start = 6.dp).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * The Live page: everyone live on Meshron right now, as cards (people you follow first), with a
 * Go live button. Refreshes every few seconds while open.
 */
@Composable
fun LiveHub(reels: ReelsService, start: List<ReelsService.LiveNow>, onWatch: (ReelsService.LiveNow) -> Unit, onGoLive: () -> Unit, onClose: () -> Unit) {
    var lives by remember { mutableStateOf(start) }
    var tab by remember { mutableStateOf("all") }
    LaunchedEffect(Unit) { while (true) { lives = runCatching { reels.lives() }.getOrDefault(lives); delay(6_000) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        val shown = when (tab) { "following" -> lives.filter { it.author.following }; "games" -> lives.filter { it.screen }; else -> lives }
        Box(Modifier.fillMaxSize().background(MeshColors.Bg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text("Live", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    LiveBadge()
                    Text("  ${lives.size} now", color = MeshColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in listOf("all" to "For you", "following" to "Following", "games" to "Games & screens")) {
                        val on = tab == id
                        Text(label, color = if (on) MeshColors.Bg else MeshColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) MeshColors.TextPrimary else MeshColors.Surface2).clickable { tab = id }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                if (shown.isEmpty()) Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Videocam, null, tint = MeshColors.TextTertiary, modifier = Modifier.size(48.dp))
                    Text(when (tab) { "following" -> "Nobody you follow is live."; "games" -> "Nobody is streaming a game right now."; else -> "Nobody is live right now." },
                        color = MeshColors.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    Text("Be the first: go live with your camera or a game.", color = MeshColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown.size, key = { shown[it].author.ch }) { i -> LiveCard(shown[i]) { onWatch(shown[i]) } }
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp).clip(RoundedCornerShape(50)).background(MeshColors.Gradient).clickable(onClick = onGoLive)
                .padding(horizontal = 28.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Videocam, null, tint = Color.White)
                Text("  Go live", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun LiveCard(l: ReelsService.LiveNow, onClick: () -> Unit) {
    val hues = listOf(listOf(MeshColors.GradStart, MeshColors.GradMid), listOf(MeshColors.GradMid, MeshColors.GradEnd), listOf(MeshColors.GradEnd, MeshColors.GradStart), listOf(MeshColors.Surface2, MeshColors.GradMid))
    val g = hues[(l.author.ch.hashCode() and 0x7fffffff) % hues.size]
    Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(g)).clickable(onClick = onClick)) {
        Box(Modifier.align(Alignment.Center).border(3.dp, Color.White, CircleShape).padding(4.dp)) { Avatar(l.author.name, l.author.id, 78.dp) }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(LiveRed).padding(horizontal = 6.dp, vertical = 2.dp))
            if (l.screen) Icon(Icons.Rounded.SportsEsports, "Game", tint = Color.White, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 4.dp, vertical = 1.dp).size(14.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))).padding(10.dp)) {
            Text(l.title.ifBlank { "Live now" }, color = Color.White, fontWeight = FontWeight.ExtraBold, maxLines = 2, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.author.label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
                com.xalvxnlab.meshron.ui.components.Badges(l.author.id, 11.dp)
                if (l.author.following) Text(" · Following", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** A small "LIVE" button for the Discover header: shows how many people are live. */
@Composable
fun LiveButton(reels: ReelsService, onGoLive: () -> Unit = {}, onWatch: (ReelsService.LiveNow) -> Unit) {
    var lives by remember { mutableStateOf<List<ReelsService.LiveNow>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { lives = runCatching { reels.lives() }.getOrDefault(lives); delay(15_000) } }
    Box(Modifier.size(48.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
        Text("LIVE", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (lives.isNotEmpty()) LiveRed else Color.White.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 3.dp))
        if (lives.isNotEmpty()) Text("${lives.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).clip(CircleShape).background(Color.Black).padding(horizontal = 4.dp))
    }
    if (open) LiveHub(reels, lives, onWatch = { open = false; onWatch(it) }, onGoLive = { open = false; onGoLive() }, onClose = { open = false })
}
