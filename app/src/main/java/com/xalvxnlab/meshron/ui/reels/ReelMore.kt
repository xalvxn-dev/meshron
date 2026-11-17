package com.xalvxnlab.meshron.ui.reels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.reels.Reel
import com.xalvxnlab.meshron.reels.ReelsService
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch

private val REPORT_REASONS = listOf(
    "Bullying or harassment", "Hate speech or symbols", "Nudity or sexual content", "Violence or dangerous acts",
    "Self-harm or suicide", "Scam or fraud", "Spam", "False information", "Child safety", "Intellectual property", "Something else",
)

/**
 * "More" for a reel: friends to send it to (one tap each), apps to share the link to, then
 * actions (repost, add to moment, sound, owner tools, safety).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    r: Reel, muted: Boolean, onClose: () -> Unit, onSend: () -> Unit, onShare: () -> Unit, onMute: () -> Unit, onDelete: () -> Unit,
    onNotInterested: () -> Unit, onHideCreator: () -> Unit, onReport: (String) -> Unit, onFeedSettings: () -> Unit,
    reposted: Boolean = false, onRepost: (() -> Unit)? = null, onToMoment: (() -> Unit)? = null, onPrivate: (() -> Unit)? = null, onCollab: (() -> Unit)? = null,
    onModRemove: (() -> Unit)? = null, onModBan: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    val contacts by app.contactsRepository.contacts.collectAsState(initial = emptyList())
    val friends = contacts.filter { it.status == Contact.Status.ADDED }
    val sent = remember { mutableStateMapOf<String, Boolean>() }
    var reporting by remember { mutableStateOf(false) }
    val link = reelLink(r)
    fun shareTo(pkg: String?) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        if (pkg != null) i.setPackage(pkg)
        runCatching { context.startActivity(if (pkg == null) Intent.createChooser(i, "Share") else i) }
            .onFailure { com.xalvxnlab.meshron.ui.components.Toasts.show(context, "That app isn't installed") }
    }
    val shareApps = remember { installedShareApps(context) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (reporting) {
                Text("Why are you reporting this?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
                Text("Reports are private. The video is hidden for you right away and Meshron's moderators review it.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                LazyColumn(Modifier.height(380.dp)) {
                    items(REPORT_REASONS) { reason ->
                        Text(reason, style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().clickable { onReport(reason) }.padding(horizontal = 20.dp, vertical = 14.dp))
                    }
                }
                return@Column
            }
            // Friends, one tap each.
            if (friends.isNotEmpty()) {
                Text("Send to", style = MaterialTheme.typography.titleSmall, color = MeshColors.TextSecondary, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(friends, key = { it.keyHex }) { c ->
                        Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(c.displayName, c.keyHex, 54.dp)
                            Text(c.displayName.substringBefore(' '), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            val done = sent[c.keyHex] == true
                            Box(
                                Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(if (done) MeshColors.Surface3 else MeshColors.AccentStrong)
                                    .clickable(enabled = !done) { sent[c.keyHex] = true; scope.launch { runCatching { app.messagingService.send(c.keyHex, link) } } }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            ) { Text(if (done) "Sent" else "Send", color = if (done) MeshColors.TextSecondary else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            // Apps.
            Text("Share to", style = MaterialTheme.typography.titleSmall, color = MeshColors.TextSecondary, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item { AppTile("Copy link", Icons.Rounded.ContentCopy, MeshColors.TileGrey) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Reel", link))
                    com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Link copied")
                } }
                items(shareApps, key = { it.pkg + "/" + it.cls }) { a ->
                    Column(Modifier.width(66.dp).clip(RoundedCornerShape(14.dp)).clickable {
                        runCatching { context.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link).setClassName(a.pkg, a.cls)) }
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.Image(a.icon, a.label, Modifier.size(52.dp).clip(CircleShape))
                        Text(a.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    }
                }
                item { AppTile("More", Icons.Rounded.Share, MeshColors.TileIndigo) { shareTo(null) } }
            }
            Spacer(Modifier.height(16.dp))
            // Actions.
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                onRepost?.let { item { AppTile(if (reposted) "Undo repost" else "Repost", Icons.Rounded.Repeat, MeshColors.TileTeal, onClick = it) } }
                onToMoment?.let { item { AppTile("To moment", Icons.Rounded.AutoStories, MeshColors.TileOrange, onClick = it) } }
                item { AppTile(if (muted) "Sound on" else "Mute", if (muted) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, MeshColors.TileGrey, onClick = onMute) }
                if (r.mine) {
                    onCollab?.let { item { AppTile("Collab", Icons.Rounded.GroupAdd, MeshColors.TileIndigo, onClick = it) } }
                    onPrivate?.let { item { AppTile(if (r.private) "Make public" else "Private", Icons.Rounded.Lock, MeshColors.TileIndigo, onClick = it) } }
                    item { AppTile("Delete", Icons.Rounded.Delete, MeshColors.Danger, onClick = onDelete) }
                } else {
                    item { AppTile("Not interested", Icons.Rounded.HeartBroken, MeshColors.TileGrey, onClick = onNotInterested) }
                    item { AppTile("Hide creator", Icons.Rounded.Block, MeshColors.TileGrey, onClick = onHideCreator) }
                    item { AppTile("Report", Icons.Rounded.Flag, MeshColors.Danger) { reporting = true } }
                }
                item { AppTile("Your feed", Icons.Rounded.Tune, MeshColors.TileBlue, onClick = onFeedSettings) }
                // Meshron moderators only.
                onModRemove?.let { item { AppTile("Mod: remove", Icons.Rounded.Delete, MeshColors.TileIndigo, onClick = it) } }
                onModBan?.let { item { AppTile("Mod: ban", Icons.Rounded.Block, MeshColors.Danger, onClick = it) } }
            }
        }
    }
}

private class ShareApp(val pkg: String, val cls: String, val label: String, val icon: androidx.compose.ui.graphics.ImageBitmap)

/** Apps on this phone that can take a link, with their real icons; popular chat apps first. */
private fun installedShareApps(context: android.content.Context): List<ShareApp> {
    val pm = context.packageManager
    val order = listOf("com.whatsapp", "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android", "org.telegram.messenger", "com.snapchat.android",
        "com.facebook.orca", "com.facebook.katana", "com.google.android.apps.messaging", "com.google.android.gm")
    val list = runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"), 0) }.getOrDefault(emptyList())
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .sortedBy { order.indexOf(it.activityInfo.packageName).let { i -> if (i < 0) 99 else i } }
        .take(8)
    return list.mapNotNull { ri ->
        runCatching {
            val d = ri.loadIcon(pm)
            val bmp = android.graphics.Bitmap.createBitmap(144, 144, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 144, 144); d.draw(android.graphics.Canvas(bmp))
            ShareApp(ri.activityInfo.packageName, ri.activityInfo.name, ri.loadLabel(pm).toString(), bmp.asImageBitmap())
        }.getOrNull()
    }
}

@Composable
private fun AppTile(label: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Column(Modifier.width(66.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (color == Color(0xFF111111)) MeshColors.TextPrimary else color)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 5.dp))
    }
}

/** Your feed: how For You ranks things, muted words, hidden creators. */
@Composable
fun FeedSettingsPage(reels: ReelsService, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var weights by remember { mutableStateOf<Map<String, Int>?>(null) }
    val words = remember { mutableStateListOf<String>() }
    val hidden = remember { mutableStateListOf<com.xalvxnlab.meshron.reels.ReelAuthor>() }
    var word by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching { reels.prefs() }.onSuccess { p -> weights = p.weights; words.addAll(p.words); hidden.addAll(p.hidden) }
    }
    fun save() { val w = weights ?: return; scope.launch { runCatching { reels.setPrefs(w, words.toList()) } } }
    val labels = listOf(
        "fresh" to ("New videos" to "Newer reels first"), "popular" to ("Popular" to "Lots of likes and comments"),
        "following" to ("People I follow" to "More from creators you follow"), "interests" to ("My interests" to "Tags you like and save"),
        "explore" to ("Surprise me" to "Mix in random new things"),
    )
    Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Your feed", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("Reset", color = MeshColors.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { weights = labels.associate { it.first to 5 }; save() }.padding(12.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            Text("For You shows more of what you turn up here. It all runs on your phone: nobody else sees these settings.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            val w = weights
            if (w != null) labels.forEach { (k, l) ->
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.first, style = MaterialTheme.typography.titleSmall)
                        Text(l.second, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(listOf("Off", "Low", "Low", "Some", "Some", "Normal", "More", "More", "High", "High", "Max")[(w[k] ?: 5).coerceIn(0, 10)], color = MeshColors.Accent, style = MaterialTheme.typography.labelLarge)
                }
                Slider((w[k] ?: 5).toFloat(), { v -> weights = w + (k to v.toInt()) }, onValueChangeFinished = { save() }, valueRange = 0f..10f, steps = 9,
                    colors = SliderDefaults.colors(thumbColor = MeshColors.AccentStrong, activeTrackColor = MeshColors.GradMid))
            }
            Spacer(Modifier.height(20.dp))
            Text("Muted words", style = MaterialTheme.typography.titleMedium)
            Text("Reels with these words or #tags won't show up.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = word, onValueChange = { word = it.take(30) }, singleLine = true, placeholder = { Text("Add a word or #tag") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                )
                Text("Add", color = MeshColors.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                    val wd = word.trim().lowercase()
                    if (wd.isNotEmpty() && wd !in words) { words += wd; word = ""; save() }
                }.padding(14.dp))
            }
            words.forEach { wd ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(wd, modifier = Modifier.weight(1f))
                    IconButton(onClick = { words.remove(wd); save() }) { Icon(Icons.Rounded.Close, "Remove") }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Hidden creators", style = MaterialTheme.typography.titleMedium)
            if (hidden.isEmpty()) Text("Nobody hidden.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            hidden.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.label, modifier = Modifier.weight(1f))
                    Text("Show again", color = MeshColors.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                        hidden.remove(a); scope.launch { runCatching { reels.hideCreator(a.ch, false) } }
                    }.padding(8.dp))
                }
            }
        }
    }
}
