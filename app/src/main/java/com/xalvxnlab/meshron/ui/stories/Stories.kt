package com.xalvxnlab.meshron.ui.stories

import com.xalvxnlab.meshron.ui.components.rememberPress
import com.xalvxnlab.meshron.ui.components.pressScale
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.messaging.FriendStories
import com.xalvxnlab.meshron.messaging.StoriesView
import com.xalvxnlab.meshron.messaging.StoryItem
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Story backgrounds; the same list (and order) as the iPhone/Windows app. */
val STORY_BGS: List<Brush> = listOf(
    Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF), Color(0xFFFF7A59))),
    Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF6366F1))),
    Brush.linearGradient(listOf(Color(0xFFF43F5E), Color(0xFFF59E0B))),
    Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF0EA5E9))),
    Brush.linearGradient(listOf(Color(0xFF111827), Color(0xFF4B5563))),
    Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFF8B5CF6))),
    Brush.linearGradient(listOf(Color(0xFFF97316), Color(0xFFEF4444), Color(0xFF7C3AED))),
    Brush.radialGradient(listOf(Color(0xFF22D3EE), Color(0xFF6366F1), Color(0xFF0A0A0F))),
)
private val REACTIONS = listOf("❤️", "🔥", "😂", "😮", "😢", "👏")
private const val STORY_MS = 5500

private fun timeAgo(ms: Long): String {
    val m = maxOf(1, ((System.currentTimeMillis() - ms) / 60_000).toInt())
    return if (m < 60) "${m}m" else "${m / 60}h"
}

/**
 * Story strip at the top of Chats: your story first, then friends with new stories (spinning
 * gradient ring), seen stories (grey ring), then friends online right now (green dot).
 */
@Composable
fun StoriesStrip(
    me: Identity,
    added: List<Contact>,
    online: (String) -> Boolean,
    nameOf: (String) -> String,
    onOpenChat: (String) -> Unit,
    onAddContact: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val stories by app.storyService.stories.collectAsState(initial = StoriesView())
    var viewing by remember { mutableStateOf<String?>(null) } // "me" or a key
    var composing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { app.openMomentComposer.collect { composing = true } }
    val withStories = stories.friends.map { it.key }.toSet()
    val activeOnly = added.filter { online(it.keyHex) && it.keyHex !in withStories }
    val live = activeOnly.size + stories.friends.count { online(it.key) }

    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Moments", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (live > 0) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).background(MeshColors.Online, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("$live active", style = MaterialTheme.typography.labelMedium, color = MeshColors.Online)
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "me") {
                StoryBubble("You", if (stories.mine.isEmpty()) RingStyle.NONE else RingStyle.MINE,
                    onClick = { if (stories.mine.isEmpty()) composing = true else viewing = "me" },
                    badge = {
                        Box(Modifier.size(22.dp).align(Alignment.BottomEnd).background(MeshColors.Bg, CircleShape).padding(2.dp)
                            .background(MeshColors.Gradient, CircleShape).clickable { composing = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, "New story", Modifier.size(14.dp), tint = Color.White)
                        }
                    }) { Avatar(me.username, me.publicKey.joinToString("") { "%02x".format(it) }, 54.dp) }
            }
            items(stories.friends, key = { "s" + it.key }) { f ->
                val name = nameOf(f.key)
                StoryBubble(name.substringBefore(' '), if (f.allSeen) RingStyle.SEEN else RingStyle.NEW, onClick = { viewing = f.key },
                    badge = { if (online(f.key)) OnlineDot() }) { Avatar(name, f.key, 54.dp) }
            }
            items(activeOnly, key = { "a" + it.keyHex }) { c ->
                StoryBubble(c.displayName.substringBefore(' '), RingStyle.NONE, onClick = { onOpenChat(c.keyHex) }, badge = { OnlineDot() }) {
                    Avatar(c.displayName, c.keyHex, 54.dp)
                }
            }
        }
    }

    viewing?.let { owner ->
        StoryViewer(stories, owner, nameOf, me, onClose = { viewing = null }, onAdd = { viewing = null; composing = true })
    }
    if (composing) StoryComposer(onClose = { composing = false })
}

private enum class RingStyle { NEW, SEEN, MINE, NONE }

@Composable
private fun BoxScope.OnlineDot() {
    Box(Modifier.size(16.dp).align(Alignment.BottomEnd).background(MeshColors.Bg, CircleShape).padding(3.dp).background(MeshColors.Online, CircleShape))
}

@Composable
private fun StoryBubble(label: String, ring: RingStyle, onClick: () -> Unit, badge: @Composable BoxScope.() -> Unit = {}, avatar: @Composable () -> Unit) {
    // A still ring: many bubbles turning forever kept the home screen redrawing (slow phones felt it).
    val spin = 0f
    val press = rememberPress()
    Column(
        Modifier.pressScale(press, 0.94f).width(76.dp).clip(RoundedCornerShape(16.dp)).clickable(press, androidx.compose.foundation.LocalIndication.current, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(66.dp)) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    // Soft-square frames (Meshron's shape), with a slowly turning aurora for new ones.
                    val tl = Offset(inset, inset)
                    val sz = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    val r = androidx.compose.ui.geometry.CornerRadius(size.width * 0.36f)
                    when (ring) {
                        RingStyle.NEW -> {
                            val a = Math.toRadians(spin.toDouble())
                            val c = Offset(size.width / 2, size.height / 2)
                            val d = Offset((kotlin.math.cos(a) * size.width / 2).toFloat(), (kotlin.math.sin(a) * size.height / 2).toFloat())
                            drawRoundRect(Brush.linearGradient(listOf(MeshColors.GradStart, MeshColors.GradMid, MeshColors.GradEnd), start = c - d, end = c + d), tl, sz, r, style = Stroke(stroke))
                        }
                        RingStyle.MINE -> drawRoundRect(MeshColors.Gradient, tl, sz, r, style = Stroke(stroke))
                        RingStyle.SEEN -> drawRoundRect(MeshColors.Surface3, tl, sz, r, style = Stroke(stroke))
                        RingStyle.NONE -> Unit
                    }
                },
                contentAlignment = Alignment.Center,
            ) { avatar() }
            badge()
        }
        Spacer(Modifier.height(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MeshColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun storyPhoto(path: String?): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, path) {
        value = path?.let { p -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() } }
    }
    return state
}

/** Full-screen viewer: progress bars, tap left/right, hold to pause, reply and reactions. */
@Composable
private fun StoryViewer(stories: StoriesView, start: String, nameOf: (String) -> String, me: Identity, onClose: () -> Unit, onAdd: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var owner by remember { mutableStateOf(start) }
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var showViewers by remember { mutableStateOf(false) }
    val order = stories.friends.map { it.key }
    val list: List<StoryItem> = if (owner == "me") stories.mine else stories.friends.firstOrNull { it.key == owner }?.items.orEmpty()
    if (list.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    if (index >= list.size) index = list.size - 1
    val item = list[index]
    val mine = owner == "me"
    val progress = remember(owner, index) { Animatable(0f) }
    val photo = if (item.isPhoto) storyPhoto(item.localPath) else null
    val ready = !item.isPhoto || photo != null

    fun step(dir: Int) {
        val next = index + dir
        if (next in list.indices) { index = next; return }
        if (mine) { if (dir > 0) onClose(); return }
        val at = order.indexOf(owner)
        val other = order.getOrNull(at + dir)
        if (other == null) { if (dir > 0) onClose(); return }
        owner = other
        index = if (dir > 0) 0 else (stories.friends.first { it.key == other }.items.size - 1)
    }

    LaunchedEffect(owner, index, item.sid) {
        if (!mine && !item.seen) app.storyService.markSeen(owner, item.sid)
    }
    LaunchedEffect(owner, index, paused, ready) {
        if (paused || !ready) return@LaunchedEffect
        val total = if (item.isPhoto) STORY_MS else minOf(12000, STORY_MS + item.text.length * 35)
        val left = ((1f - progress.value) * total).toInt()
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        step(1)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Content
            if (item.isPhoto) {
                if (photo != null) {
                    if (Build.VERSION.SDK_INT >= 31) Image(photo, null, Modifier.fillMaxSize().blur(30.dp), contentScale = ContentScale.Crop, alpha = 0.55f)
                    Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.fillMaxSize().background(STORY_BGS[4]), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Text("Downloading photo…", color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                }
                // A reel shared to a moment carries its link: shown as a button, not as raw text.
                val reelLink = remember(item.text) { Regex("meshron://reel/[0-9a-f]{64}/[A-Za-z0-9_-]+").find(item.text)?.value }
                // The poster's own caption (not the auto lines that carry the reel link).
                val shown = remember(item.text) { item.text.lines().filterNot { it.contains("meshron://reel/") || it.startsWith("Watch this") || it.startsWith("🎬") || it.startsWith("⧉") }.joinToString("\n").trim() }
                // The shared video plays right where its card sits in the picture.
                val card = remember(item.text) { Regex("⧉card:([-0-9.]+),([-0-9.]+),([-0-9.]+),([-0-9.]+)").find(item.text)?.groupValues?.drop(1)?.mapNotNull { it.toFloatOrNull() } }
                val reelM = remember(item.text) { Regex("meshron://reel/([0-9a-f]{64})/([A-Za-z0-9_-]+)").find(item.text) }
                if (photo != null && card != null && card.size == 4 && reelM != null) {
                    val file by androidx.compose.runtime.produceState<java.io.File?>(null, reelM.value) {
                        value = runCatching { app.reels.load(reelM.groupValues[1], reelM.groupValues[2]) }.getOrNull()
                    }
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val d = androidx.compose.ui.platform.LocalDensity.current
                        val bw = constraints.maxWidth.toFloat(); val bh = constraints.maxHeight.toFloat()
                        val s = minOf(bw / photo.width, bh / photo.height)
                        val iw = photo.width * s; val ih = photo.height * s
                        val ix = (bw - iw) / 2; val iy = (bh - ih) / 2
                        val cw = iw * 0.62f; val chh = cw * 16f / 9f
                        val cx = ix + card[0] * iw; val cy = iy + card[1] * ih
                        file?.let { f ->
                            Box(
                                Modifier.offset { androidx.compose.ui.unit.IntOffset((cx - cw / 2).toInt(), (cy - chh / 2).toInt()) }
                                    .size(with(d) { cw.toDp() }, with(d) { chh.toDp() })
                                    .graphicsLayer { scaleX = card[2]; scaleY = card[2]; rotationZ = card[3]; clip = true; shape = RoundedCornerShape(16.dp) },
                            ) { com.xalvxnlab.meshron.ui.reels.VideoSurface(f, play = !paused, muted = false, cover = true) }
                        }
                    }
                }
                if (shown.isNotEmpty()) Text(shown, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = if (reelLink != null) 150.dp else 110.dp))
            } else {
                Box(Modifier.fillMaxSize().background(STORY_BGS[Math.floorMod(item.bg, STORY_BGS.size)]).padding(32.dp), contentAlignment = Alignment.Center) {
                    val len = item.text.length
                    Text(item.text, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold,
                        fontSize = (if (len < 40) 34 else if (len < 120) 26 else 20).sp, lineHeight = (if (len < 40) 42 else if (len < 120) 33 else 26).sp)
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.2f to Color.Transparent, 0.72f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))

            // Tap zones: left third back, rest forward; long-press pauses.
            Row(Modifier.fillMaxSize().padding(top = 80.dp, bottom = 100.dp)) {
                for ((weight, dir) in listOf(0.32f to -1, 0.68f to 1)) {
                    Box(Modifier.weight(weight).fillMaxHeight().pointerInput(owner, index) {
                        detectTapGestures(
                            onTap = { scope.launch { progress.snapTo(0f) }; step(dir) },
                            onPress = { paused = true; tryAwaitRelease(); paused = false },
                        )
                    })
                }
            }

            // A shared video: a small TikTok-style "Original video" chip (above the tap zones) opens it.
            if (item.isPhoto) {
                val link = remember(item.text) { Regex("meshron://reel/[0-9a-f]{64}/[A-Za-z0-9_-]+").find(item.text)?.value }
                if (link != null) {
                    val by = remember(item.text) { Regex("🎬 (@?[^·]+?) ·").find(item.text)?.groupValues?.get(1)?.trim() }
                    Row(
                        Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 14.dp, bottom = 96.dp)
                            .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f))
                            .clickable { app.openReelRequests.tryEmit(link); onClose() }.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                            Text("▶", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("Original video", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(by ?: "Meshron", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                        }
                        Text("  ›", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Top: progress and header
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    list.forEachIndexed { i, _ ->
                        val f = when { i < index -> 1f; i == index -> progress.value; else -> 0f }
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.3f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
                        }
                    }
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val name = if (mine) "Your story" else nameOf(owner)
                    Avatar(if (mine) me.username else name, if (mine) me.publicKey.joinToString("") { "%02x".format(it) } else owner, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(timeAgo(item.sentAt), color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                    if (mine) IconButton(onClick = {
                        scope.launch { app.storyService.delete(item.sid); com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Story deleted") }
                    }) { Icon(Icons.Rounded.Delete, "Delete", tint = Color.White) }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                }
            }

            // Bottom: viewers (mine) or reply + reactions
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mine) {
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f)).clickable { showViewers = true; paused = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Visibility, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (item.viewers.isEmpty()) "No views yet" else "Seen by ${item.viewers.size}", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(50)).background(MeshColors.GradientH).clickable(onClick = onAdd).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("+ Add", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                } else {
                    val send = { text: String ->
                        val quote = if (item.isPhoto) (if (item.text.isNotEmpty()) "📷 ${item.text}" else "📷 Photo") else item.text
                        val flat = quote.replace(Regex("\\s+"), " ")
                        val body = "↪ Story: ${flat.take(60)}${if (flat.length > 60) "…" else ""}\n$text"
                        scope.launch { app.messagingService.send(owner, body) }
                        com.xalvxnlab.meshron.ui.components.Toasts.show(context, if (text.length <= 4) "Sent $text" else "Reply sent")
                    }
                    TextField(
                        value = reply, onValueChange = { reply = it.take(1000); paused = it.isNotEmpty() },
                        placeholder = { Text("Reply to ${nameOf(owner).substringBefore(' ')}…", color = Color.White.copy(alpha = 0.75f)) },
                        singleLine = true, shape = RoundedCornerShape(50),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (reply.isNotBlank()) { send(reply.trim()); reply = ""; paused = false } }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Black.copy(alpha = 0.45f), unfocusedContainerColor = Color.Black.copy(alpha = 0.25f),
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f).border(1.5.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(50)),
                    )
                    if (reply.isEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        REACTIONS.take(3).forEach { r ->
                            Text(r, fontSize = 24.sp, modifier = Modifier.clip(CircleShape).clickable { send(r) }.padding(5.dp))
                        }
                    }
                }
            }
        }
    }

    if (showViewers) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showViewers = false; paused = false },
            title = { Text("Seen by") },
            text = {
                if (item.viewers.isEmpty()) Text("Nobody has seen it yet.")
                else Column { item.viewers.forEach { k ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(nameOf(k), k, 36.dp); Spacer(Modifier.width(12.dp)); Text(nameOf(k))
                    }
                } }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showViewers = false; paused = false }) { Text("Close") } },
        )
    }
}

/** New story: text on a gradient (tap a colour) or a photo with a caption. */
@Composable
private fun StoryComposer(onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var bg by remember { mutableIntStateOf((STORY_BGS.indices).random()) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }
    val preview by produceState<ImageBitmap?>(null, photo) {
        value = photo?.let { u -> withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }?.asImageBitmap() }.getOrNull() } }
    }

    // A photo story opens the full editor (text, stickers, mentions, drawing), like Instagram.
    if (photo != null) {
        val full by produceState<android.graphics.Bitmap?>(null, photo) {
            value = photo?.let { u -> withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) } }.getOrNull() } }
        }
        full?.let { MomentEditor(it, "", onClose = { photo = null; onClose() }) }
        return
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Layout: header, then the story (fills what's left), then the controls; the whole
        // column moves up with the keyboard so nothing hides behind it on small phones.
        Box(Modifier.fillMaxSize().background(if (photo == null) STORY_BGS[bg] else Brush.linearGradient(listOf(Color.Black, Color.Black)))) {
            if (photo != null) {
                preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
          Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("New story", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("Contacts see it for 24 hours", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (photo == null) {
                    // Photo lives up here, so it never gets squeezed by the colours.
                    Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.3f)).clickable {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Image, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text("Photo", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // Big, easy-to-hit close button.
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Close", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (photo == null) {
                    val len = text.length
                    TextField(
                        value = text, onValueChange = { text = it.take(700) },
                        placeholder = { Text("Type something…", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold) },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold,
                            fontSize = (if (len < 40) 32 else if (len < 120) 24 else 19).sp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    )
                }
            }
            // Extra room at the bottom: some phones report no navigation-bar inset inside dialogs, which hid the colours and button.
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (photo == null) {
                    // Colours scroll sideways if the screen is narrow.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        STORY_BGS.forEachIndexed { i, b ->
                            Box(Modifier.size(if (i == bg) 36.dp else 30.dp).clip(CircleShape).background(b).border(2.dp, if (i == bg) Color.White else Color.White.copy(alpha = 0.6f), CircleShape).clickable { bg = i })
                        }
                    }
                } else {
                    TextField(value = caption, onValueChange = { caption = it.take(300) }, placeholder = { Text("Add a caption…", color = Color.White.copy(alpha = 0.75f)) },
                        singleLine = true, shape = RoundedCornerShape(50),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Black.copy(alpha = 0.45f), unfocusedContainerColor = Color.Black.copy(alpha = 0.35f),
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.fillMaxWidth())
                }
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(50)).background(MeshColors.GradientH)
                        .clickable(enabled = !busy && (photo != null || text.isNotBlank()), interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            busy = true
                            scope.launch {
                                val result = photo?.let { app.storyService.postPhoto(it, caption) } ?: app.storyService.postText(text, bg)
                                busy = false
                                result.onSuccess { com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Shared to your story"); onClose() }
                                    .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Couldn't share", long = true) }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("Share to story", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
          }
        }
    }
}
