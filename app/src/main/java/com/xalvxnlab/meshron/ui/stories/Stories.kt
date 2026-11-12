package com.xalvxnlab.meshron.ui.stories

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
    val withStories = stories.friends.map { it.key }.toSet()
    val activeOnly = added.filter { online(it.keyHex) && it.keyHex !in withStories }
    val live = activeOnly.size + stories.friends.count { online(it.key) }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Stories", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (live > 0) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).background(MeshColors.Online, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("$live active", style = MaterialTheme.typography.labelMedium, color = MeshColors.Online)
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "me") {
                StoryBubble("Your story", if (stories.mine.isEmpty()) RingStyle.NONE else RingStyle.MINE,
                    onClick = { if (stories.mine.isEmpty()) composing = true else viewing = "me" },
                    badge = {
                        Box(Modifier.size(22.dp).align(Alignment.BottomEnd).background(MeshColors.Bg, CircleShape).padding(2.dp)
                            .background(MeshColors.Gradient, CircleShape).clickable { composing = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, "New story", Modifier.size(14.dp), tint = Color.White)
                        }
                    }) { Avatar(me.username, me.publicKey.joinToString("") { "%02x".format(it) }, 58.dp) }
            }
            items(stories.friends, key = { "s" + it.key }) { f ->
                val name = nameOf(f.key)
                StoryBubble(name.substringBefore(' '), if (f.allSeen) RingStyle.SEEN else RingStyle.NEW, onClick = { viewing = f.key },
                    badge = { if (online(f.key)) OnlineDot() }) { Avatar(name, f.key, 58.dp) }
            }
            items(activeOnly, key = { "a" + it.keyHex }) { c ->
                StoryBubble(c.displayName.substringBefore(' '), RingStyle.NONE, onClick = { onOpenChat(c.keyHex) }, badge = { OnlineDot() }) {
                    Avatar(c.displayName, c.keyHex, 58.dp)
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
    val spin by rememberInfiniteTransition(label = "ring").animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    Column(
        Modifier.width(80.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(70.dp)) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    when (ring) {
                        RingStyle.NEW -> rotate(spin) {
                            drawArc(Brush.sweepGradient(listOf(MeshColors.GradStart, MeshColors.GradMid, MeshColors.GradEnd, MeshColors.GradMid, MeshColors.GradStart)),
                                0f, 360f, false, topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                        }
                        RingStyle.MINE -> drawCircle(MeshColors.Gradient, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                        RingStyle.SEEN -> drawCircle(Color(0xFF3A3A46), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
                        RingStyle.NONE -> Unit
                    }
                },
                contentAlignment = Alignment.Center,
            ) { avatar() }
            badge()
        }
        Spacer(Modifier.height(6.dp))
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
                if (item.text.isNotEmpty()) {
                    Text(item.text, Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 120.dp), color = Color.White,
                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                }
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
                        scope.launch { app.storyService.delete(item.sid); Toast.makeText(context, "Story deleted", Toast.LENGTH_SHORT).show() }
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
                        Toast.makeText(context, if (text.length <= 4) "Sent $text" else "Reply sent", Toast.LENGTH_SHORT).show()
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

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(if (photo == null) STORY_BGS[bg] else Brush.linearGradient(listOf(Color.Black, Color.Black)))) {
            if (photo != null) {
                preview?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            } else {
                val len = text.length
                TextField(
                    value = text, onValueChange = { text = it.take(700) },
                    placeholder = { Text("Type something…", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold) },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold,
                        fontSize = (if (len < 40) 32 else if (len < 120) 24 else 19).sp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Color.White),
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(24.dp),
                )
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("New story", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Your contacts can see it for 24 hours", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (photo == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        STORY_BGS.forEachIndexed { i, b ->
                            Box(Modifier.size(if (i == bg) 34.dp else 28.dp).clip(CircleShape).background(b).border(2.dp, if (i == bg) Color.White else Color.White.copy(alpha = 0.6f), CircleShape).clickable { bg = i })
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f)).clickable {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Image, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Photo", color = Color.White, fontWeight = FontWeight.Bold)
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
                                result.onSuccess { Toast.makeText(context, "Shared to your story", Toast.LENGTH_SHORT).show(); onClose() }
                                    .onFailure { Toast.makeText(context, it.message ?: "Couldn't share", Toast.LENGTH_LONG).show() }
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
