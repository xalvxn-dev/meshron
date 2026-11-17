package com.xalvxnlab.meshron.ui.reels

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xalvxnlab.meshron.reels.Reel
import com.xalvxnlab.meshron.reels.ReelAuthor
import com.xalvxnlab.meshron.reels.ReelComment
import com.xalvxnlab.meshron.reels.ReelProfile
import com.xalvxnlab.meshron.reels.ReelSound
import com.xalvxnlab.meshron.reels.ReelsService
import com.xalvxnlab.meshron.reels.Transcoder
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Page {
    data class Profile(val ch: String) : Page
    data class Sound(val sound: ReelSound) : Page
    data object Search : Page
    data class Player(val items: List<Reel>, val start: Int) : Page
    data class Create(val uri: Uri, val sound: ReelSound?, val soundItem: Reel?) : Page
    data class PickForSound(val sound: ReelSound, val item: Reel) : Page
    data object Live : Page
    data class Watch(val item: ReelsService.LiveNow) : Page
    data object FeedSettings : Page
    data class Camera(val sound: ReelSound?, val soundItem: Reel?) : Page
}

private val Pink = Color(0xFFFF2D55)

/** Panels drawn over all of Discover (comments), so they sit above the tab bar and follow the keyboard. */
private val LocalOverlay = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<(@Composable () -> Unit)?>?> { null }
private val Gold = Color(0xFFFFC83D)

private fun fmt(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> "%.1fK".format(n / 1e3)
    else -> "$n"
}

private fun ago(ms: Long): String {
    val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(1)
    return when {
        s < 3600 -> "${(s + 59) / 60}m"
        s < 86400 -> "${s / 3600}h"
        s < 86400 * 30 -> "${s / 86400}d"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
}

/** Reels: For You / Following, swiped vertically, with profiles, sounds, comments and posting. */
@Composable
fun ReelsScreen(reels: ReelsService, onMessage: (String) -> Unit, onEditProfile: () -> Unit, postRequest: Int = 0, recordRequest: Int = 0, onRequestHandled: () -> Unit = {}, onImmersive: (Boolean) -> Unit = {}, onSearch: () -> Unit = {}, openProfile: String? = null, onProfileOpened: () -> Unit = {}) {
    val ready by reels.ready.collectAsState()
    val stack = remember { mutableStateListOf<Page>() }
    var kind by remember { mutableStateOf("foryou") }
    val context = LocalContext.current
    var pendingSound by remember { mutableStateOf<Pair<ReelSound, Reel>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val s = pendingSound
            stack.removeAll { it is Page.PickForSound }
            stack.add(Page.Create(uri, s?.first, s?.second))
        }
        pendingSound = null
    }
    val pickVideo: (Pair<ReelSound, Reel>?) -> Unit = { s ->
        pendingSound = s
        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    // Camera, editor, live and full-screen players hide the app's navigation.
    val overlay = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val immersive = overlay.value != null || stack.lastOrNull().let { it is Page.Camera || it is Page.Create || it is Page.PickForSound || it is Page.Live || it is Page.Watch || it is Page.Player }
    LaunchedEffect(immersive) { onImmersive(immersive) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onImmersive(false) } }
    // The + button elsewhere asked to post a reel (> 0) or go live (< 0).
    LaunchedEffect(recordRequest) { if (recordRequest > 0) { stack.add(Page.Camera(null, null)); onRequestHandled() } }
    LaunchedEffect(openProfile) { if (openProfile != null) { stack.add(Page.Profile(openProfile)); onProfileOpened() } }
    LaunchedEffect(postRequest) {
        if (postRequest > 0) { pickVideo(null); onRequestHandled() }
        else if (postRequest < 0) { stack.add(Page.Live); onRequestHandled() }
    }

    val nav = Nav(
        profile = { stack.add(Page.Profile(it)) },
        sound = { stack.add(Page.Sound(it)) },
        player = { items, i -> stack.add(Page.Player(items, i)) },
        create = { pickVideo(null) },
        useSound = { s, item -> stack.add(Page.PickForSound(s, item)) },
        message = { a -> reels.contactLink(a)?.let(onMessage) },
        editProfile = onEditProfile,
        back = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
        feedSettings = { stack.add(Page.FeedSettings) },
    )

    androidx.compose.runtime.CompositionLocalProvider(LocalOverlay provides overlay) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (ready == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Starting Reels…", color = MeshColors.TextSecondary)
            }
        } else {
            val visible = stack.isEmpty()
            Feed(reels, kind, active = visible, nav = nav, key = kind)
            // Top bar: search, For You / Following, me.
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search everything", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                for ((id, label) in listOf("following" to "Following", "foryou" to "For You")) {
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { kind = id }.padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = if (kind == id) Color.White else Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Box(Modifier.padding(top = 3.dp).width(22.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (kind == id) Color.White else Color.Transparent))
                    }
                }
                Spacer(Modifier.weight(1f))
                LiveButton(reels, onGoLive = { stack.add(Page.Live) }) { stack.add(Page.Watch(it)) }
            }
        }
        // Pages on top of the feed.
        stack.forEachIndexed { i, page ->
            if (i == stack.lastIndex) Box(Modifier.fillMaxSize().background(MeshColors.Bg)) {
                when (page) {
                    is Page.Profile -> ProfilePage(reels, page.ch, nav)
                    is Page.Sound -> SoundPage(reels, page.sound, nav)
                    Page.Search -> SearchPage(reels, nav)
                    is Page.Player -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                        Pager(reels, page.items, page.start, active = true, nav = nav, loadMore = null)
                        IconButton(onClick = nav.back, modifier = Modifier.statusBarsPadding().padding(6.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                        }
                    }
                    is Page.PickForSound -> CameraScreen(reels, page.sound, page.item, onClose = nav.back, onDone = { uri -> stack.removeAll { it is Page.PickForSound }; stack.add(Page.Create(uri, page.sound, page.item)) })
                    Page.Live -> LivePage(reels, nav)
                    is Page.Watch -> WatchLive(page.item, onClose = nav.back)
                    Page.FeedSettings -> FeedSettingsPage(reels, onBack = nav.back)
                    is Page.Camera -> com.xalvxnlab.meshron.ui.reels.CameraScreen(
                        reels, page.sound, page.soundItem, onClose = nav.back,
                        onDone = { uri -> stack.removeAll { it is Page.Camera }; stack.add(Page.Create(uri, page.sound, page.soundItem)) },
                    )
                    is Page.Create -> EditorScreen(reels, page.uri, page.sound, page.soundItem, onBack = nav.back) {
                        stack.clear()
                        reels.ready.value?.let { stack.add(Page.Profile(it)) }
                        com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Your reel is live")
                    }
                }
            }
        }
        overlay.value?.invoke()
    }
    }
}

private class Nav(
    val profile: (String) -> Unit,
    val sound: (ReelSound) -> Unit,
    val player: (List<Reel>, Int) -> Unit,
    val create: () -> Unit,
    val useSound: (ReelSound, Reel) -> Unit,
    val message: (ReelAuthor) -> Unit,
    val editProfile: () -> Unit,
    val back: () -> Unit,
    val feedSettings: () -> Unit,
)

// ---- Feed -----------------------------------------------------------------------------

@Composable
private fun Feed(reels: ReelsService, kind: String, active: Boolean, nav: Nav, key: String) {
    val items = remember(key) { mutableStateListOf<Reel>() }
    var more by remember(key) { mutableStateOf(true) }
    var loading by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var loaded by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loadMore: () -> Unit = {
        if (more && !loading) {
            loading = true
            scope.launch {
                runCatching { reels.feed(kind, offset = items.size) }
                    .onSuccess { (list, m) -> items.addAll(list.filter { n -> items.none { it.key == n.key } }); more = m; error = null }
                    .onFailure { error = it.message }
                loading = false
                loaded = true
            }
        }
    }
    LaunchedEffect(key) { loadMore() }
    // Counts change as people like and comment.
    LaunchedEffect(key) {
        reels.changed.collect { chans ->
            for (ch in chans.distinct()) {
                if (items.none { it.ch == ch }) continue
                val fresh = runCatching { reels.feed("user", ch = ch, limit = 200).first }.getOrNull() ?: continue
                for (i in items.indices) {
                    val f = fresh.find { it.key == items[i].key } ?: continue
                    items[i] = items[i].copy(likes = f.likes, liked = f.liked, comments = f.comments, faved = f.faved, views = f.views)
                }
            }
            // New reels while the list was empty.
            if (items.isEmpty()) { more = true; loadMore() }
        }
    }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (!loaded) CircularProgressIndicator(color = Color.White)
            else {
                Box(Modifier.size(76.dp).clip(RoundedCornerShape(26.dp)).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(if (kind == "following") "Nobody to show yet" else "No reels yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(
                    error ?: if (kind == "following") "Follow people in For You and their reels show up here."
                    else "Reels from people on Meshron appear here as you meet them online. Be the first: tap + to share one.",
                    color = MeshColors.TextSecondary, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = nav.create, colors = ButtonDefaults.buttonColors(containerColor = MeshColors.AccentStrong)) { Text("Share a reel") }
            }
        }
        return
    }
    Pager(reels, items, 0, active, nav, loadMore)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pager(reels: ReelsService, items: List<Reel>, start: Int, active: Boolean, nav: Nav, loadMore: (() -> Unit)?) {
    val local = remember(items) { if (items is androidx.compose.runtime.snapshots.SnapshotStateList) items else mutableStateListOf<Reel>().apply { addAll(items) } }
    val pager = rememberPagerState(initialPage = start) { local.size }
    var muted by remember { mutableStateOf(false) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { p ->
            if (loadMore != null && p >= local.size - 3) loadMore()
            // Get the next two ready.
            for (j in listOf(p + 1, p + 2)) local.getOrNull(j)?.let { r -> launch { runCatching { reels.load(r.ch, r.id) } } }
        }
    }
    VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { local.getOrNull(it)?.key ?: it }) { i ->
        val r = local.getOrNull(i) ?: return@VerticalPager
        ReelPage(
            reels, r, playing = active && pager.currentPage == i && !pager.isScrollInProgress || active && pager.currentPage == i && pager.settledPage == i,
            muted = muted, onMute = { muted = !muted }, nav = nav,
            update = { n -> val k = local.indexOfFirst { it.key == n.key }; if (k >= 0) local[k] = n },
            removed = { local.removeAll { it.key == r.key } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReelPage(
    reels: ReelsService, r: Reel, playing: Boolean, muted: Boolean, onMute: () -> Unit, nav: Nav,
    update: (Reel) -> Unit, removed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var file by remember(r.key) { mutableStateOf<File?>(null) }
    var failed by remember(r.key) { mutableStateOf<String?>(null) }
    var paused by remember(r.key) { mutableStateOf(false) }
    var progress by remember(r.key) { mutableFloatStateOf(0f) }
    var showComments by remember { mutableStateOf(false) }
    var heartAt by remember { mutableStateOf<Offset?>(null) }
    val heart = remember { Animatable(0f) }
    var capOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var reposting by remember { mutableStateOf(false) }
    var toMoment by remember { mutableStateOf(false) }
    val app0 = context.applicationContext as com.xalvxnlab.meshron.MeshronApp
    val modRole by app0.moderation.role.collectAsState()
    var confirmBan by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmPrivate by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    val contactsNow by (context.applicationContext as com.xalvxnlab.meshron.MeshronApp).contactsRepository.contacts.collectAsState(initial = emptyList())
    val friendKeys = remember(contactsNow) { contactsNow.filter { it.status == com.xalvxnlab.meshron.contacts.Contact.Status.ADDED }.map { it.keyHex }.toSet() }
    LaunchedEffect(r.key, playing) {
        if (playing && file == null) runCatching { reels.load(r.ch, r.id) }.onSuccess { file = it }.onFailure { failed = it.message }
    }
    LaunchedEffect(playing) { if (playing) paused = false }

    fun like(on: Boolean) {
        if (on == r.liked) return
        update(r.copy(liked = on, likes = (r.likes + if (on) 1 else -1).coerceAtLeast(0)))
        scope.launch { runCatching { reels.like(r.ch, r.id, on) } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val thumb = remember(r.thumb) { reels.image(r.thumb) }
        if (thumb != null && (file == null || !playing)) Image(thumb, null, Modifier.fillMaxSize(), contentScale = if (r.h >= r.w) ContentScale.Crop else ContentScale.Fit)
        file?.let { f ->
            if (playing || progress > 0f) VideoSurface(f, play = playing && !paused, muted = muted, cover = r.h >= r.w) { progress = it }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(r.key) {
                detectTapGestures(
                    onTap = { paused = !paused },
                    onDoubleTap = { pos ->
                        heartAt = pos
                        scope.launch { heart.snapTo(0f); heart.animateTo(1f, tween(800)) }
                        like(true)
                    },
                )
            },
        )
        if (playing && file == null && failed == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        failed?.let { Text(it, color = MeshColors.TextSecondary, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp)) }
        if (paused) Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center).size(72.dp))
        heartAt?.let { p ->
            val v = heart.value
            if (v in 0.01f..0.99f) {
                val d = LocalDensity.current
                Icon(
                    Icons.Rounded.Favorite, null, tint = Pink,
                    modifier = Modifier.offset { IntOffset((p.x - with(d) { 48.dp.toPx() }).toInt(), (p.y - with(d) { 48.dp.toPx() } - v * 120).toInt()) }
                        .size(96.dp).rotate(-12f).graphicsLayer { val s = if (v < 0.15f) v / 0.15f * 1.15f else 1f + v * 0.4f; scaleX = s; scaleY = s; alpha = if (v > 0.7f) (1 - v) / 0.3f else 1f },
                )
            }
        }

        // Bottom left: who, when, caption and sound.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                .navigationBarsPadding().padding(start = 16.dp, end = 84.dp, top = 60.dp, bottom = 100.dp),
        ) {
            // Instagram-style: a small "You reposted" bubble with your photo, tap to undo.
            if (r.reposted && r.reposter == null) {
                val meId by (context.applicationContext as com.xalvxnlab.meshron.MeshronApp).identityRepository.identity.collectAsState(initial = null)
                Row(Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f)).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    meId?.let { Avatar(it.username, it.publicKey.joinToString("") { b -> "%02x".format(b) }, 22.dp) }
                    Icon(Icons.Rounded.Repeat, null, tint = Color.White, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                    Text(" You reposted", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            r.reposter?.let { rp ->
                Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).clickable { nav.profile(rp.ch) }.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Repeat, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(" Reposted by " + rp.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (r.quote.isNotBlank()) Text("“" + r.quote + "”", color = Color.White, fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { nav.profile(r.ch) }) {
                Text(r.author.label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                com.xalvxnlab.meshron.ui.components.Badges(r.author.id, 13.dp)
                if (r.mine) Text("  ·  You", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                // Friends follow each other; someone I follow who doesn't follow back is "Following".
                if (!r.mine && r.author.friend) Text("  ·  Friend", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                else if (r.author.following && !r.mine) Text("  ·  Following", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                // Each collaborator opens their own page.
                if (r.collabs.isNotEmpty()) {
                    Text("with ", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                    r.collabs.forEachIndexed { i, cb ->
                        Text(cb.label + if (i < r.collabs.size - 1) ", " else "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.clickable(enabled = cb.ch.isNotEmpty()) { nav.profile(cb.ch) })
                    }
                    Text("  ·  ", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                }
                Text(ago(r.ts) + (if (r.views > 0) " · ${fmt(r.views)} views" else "") + (if (r.reposts > 0) " · ↻ ${fmt(r.reposts)}" else ""), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (r.cap.isNotBlank()) Text(
                r.cap, color = Color.White, fontSize = 14.5.sp, maxLines = if (capOpen) 12 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).clickable { capOpen = !capOpen },
            )
            Row(Modifier.padding(top = 10.dp).clickable { nav.sound(r.sound) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(r.sound.title, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(220.dp).basicMarquee())
            }
            // Progress: a thin aurora line.
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth(progress).height(2.dp).background(MeshColors.GradientH))
            }
        }
        // Right: avatar + follow, then actions on soft glass circles.
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 10.dp, bottom = 104.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.padding(bottom = 8.dp)) {
                Box(Modifier.border(2.dp, MeshColors.GradientH, CircleShape).padding(2.dp).clickable { nav.profile(r.ch) }) { Face(reels, r.author, 46) }
                if (r.author.following && !r.author.mine) Box(
                    Modifier.align(Alignment.BottomCenter).offset(y = 10.dp).size(22.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, "Following", tint = MeshColors.AccentStrong, modifier = Modifier.size(15.dp)) }
                if (!r.author.mine && !r.author.following) Box(
                    Modifier.align(Alignment.BottomCenter).offset(y = 10.dp).size(22.dp).clip(CircleShape).background(MeshColors.Gradient)
                        .clickable { update(r.copy(author = r.author.copy(following = true))); scope.launch { runCatching { reels.follow(r.ch, true) } } },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Follow", tint = Color.White, modifier = Modifier.size(15.dp)) }
            }
            Action(if (r.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, fmt(r.likes), if (r.liked) Pink else Color.White) { like(!r.liked) }
            Action(Icons.Rounded.ChatBubble, fmt(r.comments)) { showComments = true }
            Action(if (r.faved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "", if (r.faved) Gold else Color.White) {
                update(r.copy(faved = !r.faved))
                scope.launch { runCatching { reels.fav(r.ch, r.id, !r.faved) } }
            }
            Action(Icons.Rounded.MoreHoriz, "") { more = true }
            val spin = rememberInfiniteTransition(label = "disc").animateFloat(0f, 360f, infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart), label = "r")
            Box(
                Modifier.size(44.dp).rotate(if (playing && !paused) spin.value else 0f).clip(CircleShape).background(MeshColors.Gradient).padding(4.dp).clip(CircleShape)
                    .background(Color(0xFF222222)).clickable { nav.sound(r.sound) },
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) Image(thumb, r.sound.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Rounded.MusicNote, r.sound.title, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }

    if (sending) SendToFriend(r, onClose = { sending = false })
    if (toMoment) ToMomentDialog(reels, r, onClose = { toMoment = false })
    if (confirmPrivate) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmPrivate = false },
        title = { Text(if (r.private) "Make public?" else "Make private?") },
        text = { Text(if (r.private) "It's posted again for everyone. Likes and comments start from zero." else "It disappears for everyone and stays only on this phone. Its likes and comments go away. People who already saved a copy may keep it.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            confirmPrivate = false
            scope.launch {
                runCatching { reels.setPrivate(r.id, !r.private) }
                    .onSuccess { com.xalvxnlab.meshron.ui.components.Toasts.show(context, if (r.private) "Posted again for everyone" else "Now only you can see it"); removed() }
                    .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Couldn't change it", long = true) }
            }
        }) { Text(if (r.private) "Make public" else "Make private") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmPrivate = false }) { Text("Cancel") } },
    )
    if (confirmBan) ModReasonDialog("Suspend " + r.author.label + "?", "Their account is suspended and they can appeal. Every moderator action is logged and checked by AI.", "Suspend", onClose = { confirmBan = false }) { why ->
        confirmBan = false
        scope.launch {
            runCatching { app0.moderation.act("ban", org.json.JSONObject().put("who", r.author.id).put("content", "Their video caption: " + r.cap).put("reason", why)) }
                .onSuccess { com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Account suspended") }
                .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Couldn't do that", long = true) }
        }
    }
    if (confirmRemove) ModReasonDialog("Remove this video?", "The owner is told why and can appeal. Every moderator action is logged and checked by AI.", "Remove", onClose = { confirmRemove = false }) { why ->
        confirmRemove = false
        scope.launch {
            runCatching { app0.moderation.act("hide", org.json.JSONObject().put("kind", "reel").put("target", r.key).put("owner", r.author.id).put("content", "Video caption: " + r.cap).put("reason", why)) }
                .onSuccess { com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Removed. The owner can appeal."); removed() }
                .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Couldn't remove it", long = true) }
        }
    }
    if (inviting) InviteCollab(reels, r, onClose = { inviting = false })
    if (reposting) RepostSheet(r, onClose = { reposting = false }) { thought ->
        reposting = false
        update(r.copy(reposted = true, reposts = r.reposts + 1))
        scope.launch { runCatching { reels.repost(r, true, thought) } }
        com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Reposted to your followers")
    }
    if (more) MoreSheet(
        r, muted, onClose = { more = false },
        onSend = { more = false; sending = true },
        onShare = {
            more = false
            val text = (if (r.cap.isNotBlank()) r.cap + "\n" else "") + "Watch ${r.author.label} on Meshron Reels"
            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "Share"))
        },
        onMute = { more = false; onMute() },
        onDelete = { more = false; confirmDelete = true },
        onNotInterested = { more = false; scope.launch { runCatching { reels.notInterested(r) }; removed() } },
        onHideCreator = { more = false; scope.launch { runCatching { reels.hideCreator(r.ch, true) }; removed() } },
        onReport = { reason -> more = false; scope.launch { runCatching { reels.report(r, reason) }; removed() } },
        onFeedSettings = { more = false; nav.feedSettings() },
        reposted = r.reposted,
        onRepost = {
            more = false
            // Repost opens a sheet to add a thought (like TikTok / Instagram); undo is instant.
            if (!r.reposted) reposting = true
            else {
                update(r.copy(reposted = false, reposts = (r.reposts - 1).coerceAtLeast(0)))
                scope.launch { runCatching { reels.repost(r, false) } }
                com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Repost removed")
            }
        },
        onToMoment = { more = false; toMoment = true },
        onCollab = if (r.mine) ({ more = false; inviting = true }) else null,
        onModRemove = if (modRole != null && !r.mine) ({ more = false; confirmRemove = true }) else null,
        onModBan = if (modRole != null && !r.mine) ({ more = false; confirmBan = true }) else null,
        onPrivate = if (r.mine) ({ more = false; confirmPrivate = true }) else null,
    )

    if (showComments) Comments(reels, r, onClose = { showComments = false }, onCount = { update(r.copy(comments = it)) }, onProfile = { showComments = false; nav.profile(it) })
    if (confirmDelete) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this reel?") },
        text = { Text("It disappears for everyone. People who already downloaded it may keep their copy.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                confirmDelete = false
                scope.launch { runCatching { reels.delete(r.id) }.onSuccess { removed() } }
            }) { Text("Delete", color = MeshColors.Danger) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    val bump = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable {
            scope.launch { bump.snapTo(0.8f); bump.animateTo(1.25f, tween(120)); bump.animateTo(1f, tween(160)) }
            onClick()
        }.padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp).graphicsLayer { scaleX = bump.value; scaleY = bump.value }.clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(25.dp))
        }
        if (label.isNotEmpty()) Text(label, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Face(reels: ReelsService, a: ReelAuthor, size: Int) {
    val img = remember(a.photo) { reels.image(a.photo) }
    if (img != null) Image(img, a.name, Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    else Avatar(a.name.ifBlank { "?" }, a.id.ifBlank { a.ch }, size.dp)
}

/** Plays a local video on a TextureView (so it scrolls smoothly in the pager). */
@Composable
internal fun VideoSurface(file: File, play: Boolean, muted: Boolean, cover: Boolean, onProgress: (Float) -> Unit = {}) {
    val player = remember(file) { MediaPlayer() }
    var prepared by remember(file) { mutableStateOf(false) }
    var view by remember { mutableStateOf<TextureView?>(null) }
    fun fit() {
        val v = view ?: return
        if (!prepared || player.videoWidth == 0 || v.width == 0) return
        val vw = player.videoWidth.toFloat()
        val vh = player.videoHeight.toFloat()
        val s = if (cover) maxOf(v.width / vw, v.height / vh) else minOf(v.width / vw, v.height / vh)
        val m = Matrix()
        m.setScale(vw * s / v.width, vh * s / v.height, v.width / 2f, v.height / 2f)
        v.setTransform(m)
    }
    DisposableEffect(file) {
        onDispose { runCatching { player.stop() }; player.release() }
    }
    LaunchedEffect(play, prepared) { if (prepared) { if (play) player.start() else if (player.isPlaying) player.pause() } }
    LaunchedEffect(muted, prepared) { if (prepared) player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }
    LaunchedEffect(prepared, play) {
        while (prepared && play) {
            runCatching { if (player.duration > 0) onProgress(player.currentPosition.toFloat() / player.duration) }
            kotlinx.coroutines.delay(200)
        }
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                view = tv
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            player.setSurface(Surface(st))
                            player.setDataSource(file.path)
                            player.isLooping = true
                            player.setOnPreparedListener { prepared = true; fit() }
                            player.setOnVideoSizeChangedListener { _, _, _ -> fit() }
                            player.prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ---- Comments -------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Comments(reels: ReelsService, r: Reel, onClose: () -> Unit, onCount: (Int) -> Unit, onProfile: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<ReelComment>?>(null) }
    var text by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf<String?>(null) }
    var aiThinking by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(androidx.compose.ui.text.TextRange(0)) }
    var replyTo by remember { mutableStateOf<ReelComment?>(null) }
    var stickers by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val contactsC by (LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp).contactsRepository.contacts.collectAsState(initial = emptyList())
    val isFriend = r.author.friend
    val context = LocalContext.current
    val app = context.applicationContext as com.xalvxnlab.meshron.MeshronApp
    suspend fun reload() { runCatching { reels.comments(r.ch, r.id) }.onSuccess { list = it; onCount(it.size) } }
    LaunchedEffect(r.key) { reload() }
    LaunchedEffect(r.key) { reels.changed.collect { if (r.ch in it) reload() } }
    fun post(body: String) {
        val t = body.trim()
        if (t.isEmpty()) return
        scope.launch {
            // Safety: harmful comments aren't posted.
            if (!com.xalvxnlab.meshron.ui.chat.Stickers.isSticker(t)) {
                val v = app.ai.check(t)
                if (!v.ok) { blocked = "Not posted: " + v.reason.ifBlank { "it may be harmful" }; return@launch }
            }
            blocked = null; text = ""
            val re = replyTo?.let { it.re ?: it.cid } // replies stay one level deep
            replyTo = null
            val cid = runCatching { reels.comment(r.ch, r.id, t, re) }.getOrNull(); reload()
            if (re == null) runCatching { listState.animateScrollToItem(0) }
            // Tagged @meshronai: Meshron AI answers under the comment.
            if (cid != null && com.xalvxnlab.meshron.account.MeshronAi.tagged(t)) {
                aiThinking = true
                runCatching {
                    val ctx = list.orEmpty().takeLast(10).map { it.name + ": " + it.text }
                    val a = app.ai.ask(r.ch + ":" + r.id, re ?: cid, t, r.cap, r.author.label, ctx)
                    reels.aiComment(r.ch, r.id, a)
                }.onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, it.message ?: "Meshron AI couldn't answer", long = true) }
                aiThinking = false
                reload()
            }
        }
    }
    // Like TikTok: a panel over the lower part of the video. The list scrolls, the box to type in
    // rides on top of the keyboard, tapping the list hides the keyboard, tapping above closes it.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    fun close() { focus.clearFocus(); shown.targetState = false }
    val box = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(replyTo) { if (replyTo != null) runCatching { box.requestFocus() } }
    LaunchedEffect(shown.isIdle, shown.currentState) { if (shown.isIdle && !shown.currentState && !shown.targetState) onClose() }
    val host = LocalOverlay.current
    val panel: @Composable () -> Unit = {
      val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
      BackHandler { close() }
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { close() })
        androidx.compose.animation.AnimatedVisibility(shown, modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically { it }, exit = androidx.compose.animation.slideOutVertically { it }) {
        Column(Modifier.fillMaxWidth().height(screenH * 0.7f).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(MeshColors.Surface1)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { focus.clearFocus() }) {
            Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp)) {
                Text(list?.let { if (it.size == 1) "1 comment" else "${fmt(it.size)} comments" } ?: "Comments", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                IconButton(onClick = { close() }, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) { Icon(Icons.Rounded.Close, "Close", tint = MeshColors.TextSecondary) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
                val l = list
                if (l == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else if (l.isEmpty()) Text("No comments yet. Say something nice.", color = MeshColors.TextSecondary, modifier = Modifier.align(Alignment.Center))
                else {
                    // Top-level comments, each followed by its replies.
                    // My own comments first (newest on top), so they are easy to find, edit or delete.
                    val top = l.filter { it.re == null || l.none { p -> p.cid == it.re } }.let { t -> t.filter { it.mine }.sortedByDescending { it.ts } + t.filter { !it.mine } }
                    val ordered = top.flatMap { t -> listOf(t) + l.filter { it.re == t.cid } }
                    LazyColumn(state = listState) {
                        items(ordered, key = { it.cid }) { c ->
                            val isReply = c.re != null && c !in top
                            Row(
                                Modifier.fillMaxWidth().padding(start = if (isReply) 46.dp else 0.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                val img = remember(c.photo) { reels.image(c.photo) }
                                val size = if (isReply) 28.dp else 36.dp
                                // Tapping someone's photo or name opens their page.
                                val openPage: () -> Unit = {
                                    if (c.by != com.xalvxnlab.meshron.account.MeshronAi.ID) scope.launch {
                                        val ch = runCatching { reels.channelOf(c.by) }.getOrNull()
                                        if (ch != null) onProfile(ch) else com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Their page isn't reachable right now")
                                    }
                                }
                                Box(Modifier.clip(CircleShape).clickable(onClick = openPage)) {
                                    if (c.by == com.xalvxnlab.meshron.account.MeshronAi.ID) MeshronAiAvatar(size)
                                    else if (img != null) Image(img, null, Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop) else Avatar(c.name.ifBlank { "?" }, c.by, size)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(c.name.ifBlank { "Someone" }, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MeshColors.TextSecondary,
                                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = openPage))
                                        com.xalvxnlab.meshron.ui.components.Badges(c.by, 11.dp)
                                        if (c.by == r.author.id) Text(" Creator", color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                        Text(" · " + if (c.sent) ago(c.ts) else "sending…", fontSize = 13.sp, color = MeshColors.TextTertiary)
                                    }
                                    // Comments in another language show in mine, with a note.
                                    val tr = com.xalvxnlab.meshron.ui.components.rememberAutoTranslation("c:" + r.key + ":" + c.cid, c.text,
                                        enabled = !c.mine && !com.xalvxnlab.meshron.ui.chat.Stickers.isSticker(c.text))
                                    var original by remember(c.cid) { mutableStateOf(false) }
                                    com.xalvxnlab.meshron.ui.chat.Stickers.Body(if (tr != null && !original) tr.text else c.text, MeshColors.TextPrimary)
                                    if (tr != null) com.xalvxnlab.meshron.ui.components.TranslationNote(tr, original, MeshColors.TextPrimary) { original = !original }
                                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Text("Reply", color = MeshColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { replyTo = c }.padding(vertical = 2.dp))
                                        if (!com.xalvxnlab.meshron.ui.chat.Stickers.isSticker(c.text)) Text("Repost", color = MeshColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                                scope.launch { runCatching { reels.repost(r, true, c.text, c.cid) } }
                                                com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Reposted with this comment")
                                            }.padding(vertical = 2.dp))
                                    }
                                }
                                if (c.canDelete) IconButton(onClick = { scope.launch { runCatching { reels.uncomment(r.ch, c.cid) }; reload() } }) {
                                    Icon(Icons.Rounded.Delete, "Delete", tint = MeshColors.TextTertiary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            // The creator limits who can comment: say so instead of showing the box.
            val closed = when {
                r.mine -> null
                r.author.cm == "off" -> "Comments are turned off"
                r.author.cm == "followers" && !r.author.following -> "Only followers can comment. Follow to join in."
                r.author.cm == "friends" && !isFriend -> "Only friends can comment on this video."
                else -> null
            }
            if (closed != null) Text(closed, color = MeshColors.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(MeshColors.Surface1).navigationBarsPadding().padding(16.dp))
            if (closed == null) Column(Modifier.fillMaxWidth().background(MeshColors.Surface1).navigationBarsPadding().imePadding().padding(horizontal = 12.dp)) {
            blocked?.let { Text(it, color = MeshColors.Danger, style = MaterialTheme.typography.bodySmall) }
            replyTo?.let { p ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Replying to " + p.name.ifBlank { "Someone" }, color = MeshColors.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { replyTo = null }) { Icon(Icons.Rounded.Close, "Cancel reply", tint = MeshColors.TextSecondary) }
                }
            }
            if (stickers) com.xalvxnlab.meshron.ui.chat.Stickers.Picker(onPick = { post(it); stickers = false })
            if (aiThinking) MeshronAiThinking()
            // Typing "@" (or "@me…"): suggest Meshron AI.
            val tagFrag = Regex("(^|\\s)@(\\w*)$").find(text)?.groupValues?.get(2)
            if (tagFrag != null && "meshronai".startsWith(tagFrag.lowercase())) MeshronAiSuggestion {
                text = text.dropLast(tagFrag.length + 1) + "@meshronai "
                sel = androidx.compose.ui.text.TextRange(text.length)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { stickers = !stickers }) { Icon(Icons.Rounded.EmojiEmotions, "Stickers", tint = if (stickers) MeshColors.Accent else MeshColors.TextSecondary) }
                TextField(
                    value = androidx.compose.ui.text.input.TextFieldValue(text, sel), onValueChange = { text = it.text.take(300); sel = it.selection }, placeholder = { Text(if (replyTo != null) "Write a reply…" else "Add a comment… (@meshronai for AI)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    maxLines = 4, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).focusRequester(box),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(48.dp).clip(CircleShape).background(MeshColors.Gradient).clickable { post(text) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Post", tint = Color.White)
                }
            }
            }
        }
        }
      }
    }
    if (host == null) panel()
    else androidx.compose.runtime.DisposableEffect(Unit) {
        host.value = panel
        onDispose { if (host.value === panel) host.value = null }
    }
}

// ---- Pages ----------------------------------------------------------------------------

@Composable
private fun PageHead(title: String, nav: Nav) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun Tiles(reels: ReelsService, list: List<Reel>, nav: Nav, header: @Composable () -> Unit, empty: String) {
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(span = { GridItemSpan(3) }) { header() }
        if (list.isEmpty()) item(span = { GridItemSpan(3) }) { Text(empty, color = MeshColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(30.dp)) }
        itemsIndexed(list, key = { _, r -> r.key }) { i, r ->
            Box(Modifier.aspectRatio(9f / 16f).background(MeshColors.Surface2).clickable { nav.player(list, i) }) {
                val img = remember(r.thumb) { reels.image(r.thumb) }
                if (img != null) Image(img, r.cap, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Rounded.PlayArrow, null, tint = MeshColors.TextTertiary, modifier = Modifier.align(Alignment.Center))
                Row(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Text(" " + fmt(r.likes), color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ProfilePage(reels: ReelsService, ch: String, nav: Nav) {
    val scope = rememberCoroutineScope()
    var p by remember(ch) { mutableStateOf<ReelProfile?>(null) }
    var failed by remember(ch) { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf("videos") }
    var list by remember { mutableStateOf<List<Reel>>(emptyList()) }
    suspend fun load() {
        runCatching { reels.profile(ch) }.onSuccess { p = it; if (it == null) failed = "Looking for this person… try again in a moment." }.onFailure { failed = it.message }
    }
    LaunchedEffect(ch) { load() }
    LaunchedEffect(ch) { reels.changed.collect { if (ch in it) load() } }
    LaunchedEffect(p, tab) {
        val pr = p ?: return@LaunchedEffect
        list = when (tab) {
            "videos" -> pr.reels
            "reposts" -> runCatching { reels.feed("reposts", ch = ch, limit = 200).first }.getOrDefault(emptyList())
            else -> runCatching { reels.feed(tab, limit = 200).first }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxSize()) {
        PageHead(p?.author?.label ?: "", nav)
        val pr = p
        if (pr == null) {
            Box(Modifier.fillMaxSize()) {
                if (failed == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else Text(failed!!, color = MeshColors.TextSecondary, modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
            }
            return@Column
        }
        Tiles(reels, list, nav, header = {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.border(2.dp, MeshColors.GradMid, CircleShape).padding(4.dp)) { Face(reels, pr.author, 96) }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pr.author.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    com.xalvxnlab.meshron.ui.components.Badges(pr.author.id, 16.dp, full = true)
                }
                if (pr.author.handle.isNotEmpty()) Text("@" + pr.author.handle, color = MeshColors.TextSecondary)
                Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    for ((n, label) in listOf(pr.following to "Following", pr.followers to "Followers", pr.likes to "Likes")) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(fmt(n), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(label, color = MeshColors.TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (pr.author.mine) {
                        OutlinedButton(onClick = nav.editProfile) { Text("Edit profile") }
                        Button(onClick = nav.create, colors = ButtonDefaults.buttonColors(containerColor = MeshColors.AccentStrong)) { Text("New reel") }
                    } else {
                        val following = pr.author.following
                        Button(
                            onClick = { scope.launch { runCatching { reels.follow(ch, !following) }; load() } },
                            colors = ButtonDefaults.buttonColors(containerColor = if (following) MeshColors.Surface3 else Pink),
                        ) { Text(when { following && pr.author.followsMe -> "Friends"; following -> "Following"; pr.author.followsMe -> "Follow back"; else -> "Follow" }) }
                        OutlinedButton(onClick = { nav.message(pr.author) }) { Text("Message") }
                    }
                }
                com.xalvxnlab.meshron.ui.components.RoleBio(pr.author.id, pr.bio, Modifier.padding(top = 4.dp))
                if (!pr.author.mine && pr.author.followsMe && !pr.author.following) Text("Follows you", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (pr.link.isNotBlank()) {
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    Text("🔗 " + pr.link.removePrefix("https://"), color = MeshColors.Accent, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp).clickable { runCatching { uri.openUri(pr.link) } }.padding(4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    val tabs = if (pr.author.mine) listOf("videos" to Icons.Rounded.PlayArrow, "reposts" to Icons.Rounded.Repeat, "favourites" to Icons.Rounded.Bookmark, "liked" to Icons.Rounded.Favorite)
                        else listOf("videos" to Icons.Rounded.PlayArrow, "reposts" to Icons.Rounded.Repeat)
                    for ((id, ic) in tabs) {
                        Column(Modifier.weight(1f).clickable { tab = id }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(ic, id, tint = if (tab == id) MeshColors.TextPrimary else MeshColors.TextTertiary, modifier = Modifier.padding(10.dp))
                            Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == id) MeshColors.TextPrimary else MeshColors.Hairline))
                        }
                    }
                }
            }
        }, empty = when (tab) {
            "favourites" -> "Reels you save show up here."
            "reposts" -> "Reposts show up here."
            "liked" -> "Reels you like show up here."
            else -> if (pr.author.mine) "Share your first reel with the + button." else "No reels yet."
        })
    }
}

@Composable
private fun SoundPage(reels: ReelsService, sound: ReelSound, nav: Nav) {
    var list by remember { mutableStateOf<List<Reel>?>(null) }
    LaunchedEffect(sound) { list = runCatching { reels.feed("sound", sound = sound, limit = 200).first }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxSize()) {
        PageHead("Sound", nav)
        val l = list ?: return@Column Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        val orig = l.find { it.ch == sound.ch && it.id == sound.id }
        Tiles(reels, l, nav, header = {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(MeshColors.Surface2), contentAlignment = Alignment.Center) {
                    val img = remember(orig?.thumb) { orig?.let { reels.image(it.thumb) } }
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Rounded.MusicNote, null, Modifier.size(36.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(sound.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(fmt(l.size) + if (l.size == 1) " reel" else " reels", color = MeshColors.TextSecondary)
                    if (orig != null) Button(
                        onClick = { nav.useSound(sound, orig) }, modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MeshColors.AccentStrong),
                    ) { Icon(Icons.Rounded.MusicNote, null, Modifier.size(16.dp)); Text(" Use this sound") }
                }
            }
        }, empty = "No reels with this sound yet.")
    }
}

@Composable
private fun SearchPage(reels: ReelsService, nav: Nav) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ReelAuthor>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.isBlank()) { results = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        busy = true
        results = runCatching { reels.search(q.trim()) }.getOrDefault(emptyList())
        busy = false
    }
    Column(Modifier.fillMaxSize()) {
        PageHead("Search", nav)
        TextField(
            value = q, onValueChange = { q = it }, placeholder = { Text("Search names or @usernames") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (busy) Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(if (Regex("^@?[a-zA-Z0-9_]{3,24}$").matches(q.trim())) "  Searching everyone on Meshron…" else "  Searching…", color = MeshColors.TextSecondary)
        }
        val r = results
        if (r != null && !busy && r.isEmpty()) Text("Nobody found. For an exact match, type their whole @username.", color = MeshColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        LazyColumn {
            items(r.orEmpty(), key = { it.ch }) { u ->
                Row(Modifier.fillMaxWidth().clickable { nav.profile(u.ch) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Face(reels, u, 48)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.name, fontWeight = FontWeight.SemiBold)
                        Text((if (u.handle.isNotEmpty()) "@${u.handle} · " else "") + "${fmt(u.followers)} followers · ${fmt(u.reels)} reels", color = MeshColors.TextSecondary, fontSize = 13.sp)
                    }
                    if (u.mine) Text("You", color = MeshColors.TextSecondary) else if (u.following) Text("Following", color = MeshColors.TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PickForSoundPage(sound: ReelSound, nav: Nav, pick: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PageHead("New reel", nav)
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Text(sound.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 14.dp))
            Text("Pick a video. Its own sound is replaced with this one.", color = MeshColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = pick, colors = ButtonDefaults.buttonColors(containerColor = MeshColors.AccentStrong)) { Icon(Icons.Rounded.Add, null); Text(" Choose video") }
        }
    }
}

@Composable
private fun LivePage(reels: ReelsService, nav: Nav) {
    com.xalvxnlab.meshron.ui.reels.LiveScreen(reels, onClose = nav.back)
}

/** Send a reel straight to friends in Meshron (as a message they can tap to watch). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendToFriend(r: Reel, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
    val scope = rememberCoroutineScope()
    val contacts by app.contactsRepository.contacts.collectAsState(initial = emptyList())
    val friends = contacts.filter { it.status == com.xalvxnlab.meshron.contacts.Contact.Status.ADDED }
    val sent = remember { mutableStateListOf<String>() }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Send to", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            if (friends.isEmpty()) Text("Add friends first, then you can send them reels.", color = MeshColors.TextSecondary, modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(friends, key = { it.keyHex }) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.displayName, c.keyHex, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(c.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        val done = c.keyHex in sent
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (done) MeshColors.Surface3 else MeshColors.TextPrimary).clickable(enabled = !done) {
                                sent += c.keyHex
                                scope.launch { runCatching { app.messagingService.send(c.keyHex, reelLink(r)) } }
                            }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(if (done) "Sent" else "Send", color = if (done) MeshColors.TextSecondary else MeshColors.Bg, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

/** A reel as a chat message: a link the chat turns into a playable card. */
fun reelLink(r: Reel) = "meshron://reel/${r.ch}/${r.id}" + (if (r.cap.isNotBlank()) "\n" + r.cap.take(120) else "") + "\n— " + r.author.label

/** Plays a list of reels full screen (e.g. your own, from your profile). */
@Composable
fun ReelProfileScreen(reels: ReelsService, ch: String, onMessage: (String) -> Unit, onBack: () -> Unit) {
    // Someone's page on top of where you are (a chat, a group): their videos, follow, message.
    val stack = remember(ch) { mutableStateListOf<Page>(Page.Profile(ch)) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    BackHandler(onBack = back)
    val nav = Nav(
        profile = { stack.add(Page.Profile(it)) }, sound = { }, player = { items, i -> stack.add(Page.Player(items, i)) }, create = { },
        useSound = { _, _ -> }, message = { a -> reels.contactLink(a)?.let(onMessage) }, editProfile = { }, back = back, feedSettings = { },
    )
    Box(Modifier.fillMaxSize().background(MeshColors.Bg)) {
        when (val page = stack.last()) {
            is Page.Profile -> ProfilePage(reels, page.ch, nav)
            is Page.Player -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                Pager(reels, page.items, page.start, active = true, nav = nav, loadMore = null)
                IconButton(onClick = back, modifier = Modifier.statusBarsPadding().padding(6.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            }
            else -> Unit
        }
    }
}

@Composable
fun ReelPlayerScreen(reels: ReelsService, items: List<Reel>, start: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var settings by remember { mutableStateOf(false) }
    val nav = Nav(
        profile = { onBack() }, sound = { }, player = { _, _ -> }, create = { }, useSound = { _, _ -> },
        message = { }, editProfile = { }, back = onBack, feedSettings = { settings = true },
    )
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Pager(reels, items, start, active = !settings, nav = nav, loadMore = null)
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(6.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
        }
        if (settings) FeedSettingsPage(reels, onBack = { settings = false })
    }
}

/** Shares a reel to your moment: opens the full-screen editor on the reel's cover (text, colours, @tags). */
@Composable
private fun ToMomentDialog(reels: ReelsService, r: Reel, onClose: () -> Unit) {
    // A sharp frame from the video itself; the small cover if the video isn't here yet.
    val bmp by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, r.key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                try { mmr.setDataSource(reels.load(r.ch, r.id).path); mmr.getFrameAtTime(500_000) } finally { mmr.release() }
            }.getOrNull() ?: runCatching { android.util.Base64.decode(r.thumb, android.util.Base64.DEFAULT).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
                ?: android.graphics.Bitmap.createBitmap(9, 16, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }
    val b = bmp ?: return
    val bmp2 = b
    com.xalvxnlab.meshron.ui.stories.MomentEditor(bmp2, "🎬 " + r.author.label + " · meshron://reel/" + r.ch + "/" + r.id, onClose)
}

/** Invites a friend to collaborate on your reel: once they accept, it shows on both profiles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteCollab(reels: ReelsService, r: Reel, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
    val scope = rememberCoroutineScope()
    val contacts by app.contactsRepository.contacts.collectAsState(initial = emptyList())
    val friends = contacts.filter { it.status == com.xalvxnlab.meshron.contacts.Contact.Status.ADDED }
    val sent = remember { mutableStateListOf<String>() }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Invite a collaborator", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp))
            Text("They get a request in your chat. Once they accept, the reel shows on their profile too.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (friends.isEmpty()) Text("Add friends first, then you can invite them.", color = MeshColors.TextSecondary, modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(friends, key = { it.keyHex }) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.displayName, c.keyHex, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(c.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        val done = c.keyHex in sent
                        Box(Modifier.clip(RoundedCornerShape(50)).background(if (done) MeshColors.Surface3 else MeshColors.TextPrimary).clickable(enabled = !done) {
                            sent += c.keyHex
                            scope.launch {
                                runCatching {
                                    reels.invite(r.id, c.keyHex)
                                    app.messagingService.send(c.keyHex, "meshron://collab/" + r.ch + "/" + r.id + "\n" + r.cap.take(100))
                                }
                            }
                        }.padding(horizontal = 16.dp, vertical = 8.dp)) { Text(if (done) "Invited" else "Invite", color = if (done) MeshColors.TextSecondary else MeshColors.Bg, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

/** Repost with an optional thought, shown above the video for your followers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepostSheet(r: Reel, onClose: () -> Unit, onRepost: (String) -> Unit) {
    var thought by remember { mutableStateOf("") }
    val app = LocalContext.current.applicationContext as com.xalvxnlab.meshron.MeshronApp
    val me by app.identityRepository.identity.collectAsState(initial = null)
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).imePadding()) {
            Text("Repost", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Your followers will see this video with your thought.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Top) {
                me?.let { Avatar(it.username, it.publicKey.joinToString("") { b -> "%02x".format(b) }, 40.dp) }
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.OutlinedTextField(thought, { thought = it.take(150) }, placeholder = { Text("Add a thought…") }, minLines = 2, maxLines = 4, modifier = Modifier.weight(1f))
            }
            Text("${thought.length}/150", color = MeshColors.TextTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MeshColors.Surface2).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val img = remember(r.thumb) { app.reels.image(r.thumb) }
                Box(Modifier.size(44.dp, 64.dp).clip(RoundedCornerShape(8.dp)).background(MeshColors.Surface3)) { if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.author.label, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (r.cap.isNotBlank()) Text(r.cap, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onRepost(thought.trim()) }, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = MeshColors.AccentStrong)) {
                Icon(Icons.Rounded.Repeat, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Repost", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Meshron AI's picture: the sparkle on Meshron's gradient. */
@Composable
fun MeshronAiAvatar(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.AutoAwesome, "Meshron AI", tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** The "@meshronai" suggestion while typing a mention. */
@Composable
fun MeshronAiSuggestion(onPick: () -> Unit) {
    Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(MeshColors.Surface2).clickable(onClick = onPick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MeshronAiAvatar(28.dp)
        Column(Modifier.padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Meshron AI", fontWeight = FontWeight.Bold)
                com.xalvxnlab.meshron.ui.components.Badges(com.xalvxnlab.meshron.account.MeshronAi.ID, 13.dp)
            }
            Text("@meshronai · ask about this video", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "Meshron AI is answering…" */
@Composable
fun MeshronAiThinking() {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MeshronAiAvatar(22.dp)
        Text("  Meshron AI is answering…", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
    }
}

/** A moderator picks the rule that was broken (the person reads it; AI checks the decision). */
@Composable
private fun ModReasonDialog(title: String, note: String, confirm: String, onClose: () -> Unit, onPick: (String) -> Unit) {
    val reasons = listOf("Spam", "Harassment or bullying", "Hate speech", "Nudity or sexual content", "Violence or threats", "Scam or fraud", "Impersonation", "Dangerous misinformation", "Self-harm promotion")
    var pick by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(note, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                for (r in reasons) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pick = r }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = pick == r, onClick = { pick = r })
                    Text(r)
                }
                androidx.compose.material3.OutlinedTextField(detail, { detail = it.take(150) }, placeholder = { Text("Add details (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = pick != null, onClick = { onPick(pick + (if (detail.isNotBlank()) ": " + detail.trim() else "")) }) { Text(confirm, color = MeshColors.Danger) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
