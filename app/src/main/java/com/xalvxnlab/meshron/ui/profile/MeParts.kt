package com.xalvxnlab.meshron.ui.profile

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.reels.ReelAuthor
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.Toasts
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun fmtN(n: Int) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 10_000 -> "${n / 1000}K"; n >= 1000 -> "%.1fK".format(n / 1e3); else -> "$n" }

/** Me header: photo, name with badges, @username, bio, link, and Following · Followers · Likes. */
@Composable
internal fun MeHead(identity: Identity, bio: String, link: String, following: Int?, followers: Int?, likes: Int?, onPeople: (String) -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.border(2.5.dp, MeshColors.GradientH, CircleShape).padding(5.dp)) { Avatar(identity.username, identity.publicKey.toHex(), 104.dp) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(identity.username, style = MaterialTheme.typography.headlineSmall)
            if (!com.xalvxnlab.meshron.identity.Mode.anon) com.xalvxnlab.meshron.ui.components.Badges(identity.publicKey.toHex(), 18.dp, full = true)
        }
        com.xalvxnlab.meshron.ui.components.RoleBio(identity.publicKey.toHex(), bio)
        if (link.isNotBlank()) Text("🔗 " + link.removePrefix("https://"), color = MeshColors.Accent, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp).clip(RoundedCornerShape(6.dp)).clickable { runCatching { uri.openUri(link) } }.padding(4.dp))
        Row(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            following?.let { StatBox(fmtN(it), "Following") { onPeople("following") } }
            followers?.let { StatBox(fmtN(it), "Followers") { onPeople("followers") } }
            likes?.let { StatBox(fmtN(it), "Likes") {} }
        }
    }
}

@Composable
private fun StatBox(value: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** Following / Followers / Friends (people who follow each other), with follow back and unfollow. */
@Composable
fun PeoplePage(start: String, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(start) }
    var people by remember { mutableStateOf<com.xalvxnlab.meshron.reels.ReelsService.People?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmPurge by remember { mutableStateOf(false) }
    suspend fun load() { people = runCatching { app.reels.people(tab) }.getOrNull() }
    LaunchedEffect(tab) { people = null; load() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Connections", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            val p = people
            Row(Modifier.fillMaxWidth()) {
                for ((id, label) in listOf("following" to "Following", "followers" to "Followers", "friends" to "Friends")) {
                    val n = when (id) { "following" -> p?.following; "followers" -> p?.followers; else -> p?.friends }
                    val on = tab == id
                    Column(Modifier.weight(1f).clickable { tab = id }.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label + (n?.let { " $it" } ?: ""), fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) MeshColors.TextPrimary else MeshColors.TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) MeshColors.TextPrimary else MeshColors.Surface3))
                    }
                }
            }
            if (tab == "following" && (p?.list?.any { !it.followsMe } == true)) {
                Text("Unfollow everyone who doesn't follow you back", color = MeshColors.Danger, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { confirmPurge = true }.padding(horizontal = 20.dp, vertical = 14.dp))
            }
            if (p == null) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (p.list.isEmpty()) Text(when (tab) {
                "following" -> "You aren't following anyone yet. Follow people in Discover."
                "followers" -> "Nobody follows you yet."
                else -> "Friends are people you follow who follow you back."
            }, color = MeshColors.TextSecondary, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(p?.list.orEmpty(), key = { it.id + it.ch }) { a ->
                    PersonRow(a, busy == a.ch, onOpen = { if (a.ch.isNotEmpty()) { onClose(); onOpen(a.ch) } }) {
                        if (a.ch.isEmpty()) return@PersonRow
                        busy = a.ch
                        scope.launch { runCatching { app.reels.follow(a.ch, !a.following) }; load(); busy = null }
                    }
                }
            }
        }
    }
    if (confirmPurge) AlertDialog(
        onDismissRequest = { confirmPurge = false },
        title = { Text("Unfollow people who don't follow back?") },
        text = { Text("You'll stop following everyone in this list who doesn't follow you. You can follow them again any time.") },
        confirmButton = { TextButton(onClick = {
            confirmPurge = false
            scope.launch { val n = runCatching { app.reels.unfollowNonFollowers() }.getOrDefault(0); load(); Toasts.show(context, "Unfollowed $n") }
        }) { Text("Unfollow", color = MeshColors.Danger) } },
        dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } },
    )
}

@Composable
private fun PersonRow(a: ReelAuthor, busy: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(a.name, a.id, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                com.xalvxnlab.meshron.ui.components.Badges(a.id, 13.dp)
            }
            Text(listOfNotNull(a.handle.takeIf { it.isNotEmpty() }?.let { "@$it" }, if (a.friend) "Friends" else if (a.followsMe) "Follows you" else null).joinToString(" · "),
                color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        val label = when { a.ch.isEmpty() -> null; a.following -> if (a.followsMe) "Friends" else "Following"; a.followsMe -> "Follow back"; else -> "Follow" }
        if (label != null) {
            val primary = !a.following
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (primary) MeshColors.AccentStrong else MeshColors.Surface2).clickable(enabled = !busy, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = if (primary) Color.White else MeshColors.TextPrimary)
                else Text(label, color = if (primary) Color.White else MeshColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

// ---- Profile card (QR) ----------------------------------------------------------------

private fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H))
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(android.graphics.Color.WHITE)
    val cell = size.toFloat() / m.width
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF11141B.toInt() }
    for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) c.drawRoundRect(RectF(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell), cell * 0.28f, cell * 0.28f, p)
    // Meshron's "M" in the middle (the code still scans: high error correction).
    val r = size * 0.11f
    val cx = size / 2f
    c.drawRoundRect(RectF(cx - r, cx - r, cx + r, cx + r), r * 0.45f, r * 0.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(cx - r, cx - r, cx + r, cx + r, intArrayOf(0xFF19D3C5.toInt(), 0xFF4F7BFF.toInt(), 0xFF8B5CFF.toInt()), null, Shader.TileMode.CLAMP)
    }
    c.drawRoundRect(RectF(cx - r * 0.8f, cx - r * 0.8f, cx + r * 0.8f, cx + r * 0.8f), r * 0.35f, r * 0.35f, g)
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = r * 1.1f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    c.drawText("M", cx, cx + r * 0.38f, t)
    return b
}

/** The card as an image (for saving and sharing): gradient, photo, name, @username, QR. */
private fun cardBitmap(identity: Identity, photo: Bitmap?): Bitmap {
    val w = 1080; val h = 1440
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val bg = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF19D3C5.toInt(), 0xFF4F7BFF.toInt(), 0xFF8B5CFF.toInt()), null, Shader.TileMode.CLAMP) }
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
    val card = RectF(90f, 220f, w - 90f, h - 120f)
    c.drawRoundRect(card, 64f, 64f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    // Photo (or initial) overlapping the top of the card.
    val pr = 120f
    val pc = w / 2f
    c.drawCircle(pc, card.top, pr + 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    if (photo != null) {
        val s = Bitmap.createScaledBitmap(photo, (pr * 2).toInt(), (pr * 2).toInt(), true)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(s, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(android.graphics.Matrix().apply { setTranslate(pc - pr, card.top - pr) }) } }
        c.drawCircle(pc, card.top, pr, sp)
    } else {
        c.drawCircle(pc, card.top, pr, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bg.shader })
        c.drawText(identity.username.take(1).uppercase(), pc, card.top + 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 120f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER })
    }
    val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF11141B.toInt(); textSize = 64f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    c.drawText(identity.username, pc, card.top + pr + 110f, name)
    val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B7280.toInt(); textSize = 44f; textAlign = Paint.Align.CENTER }
    if (identity.handle.isNotEmpty()) c.drawText("@" + identity.handle, pc, card.top + pr + 175f, handle)
    val q = qrBitmap(identity.shareLink, 600)
    c.drawBitmap(q, pc - 300f, card.top + pr + 230f, null)
    c.drawText("Scan with Meshron to add me", pc, card.bottom - 70f, Paint(handle).apply { textSize = 38f })
    c.drawText("Meshron", pc, 140f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 72f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER })
    return b
}

/** "My QR card": a card to show, save to photos, or share. */
@Composable
fun ShareCard(identity: Identity, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val me by app.profiles.me.collectAsState()
    val scope = rememberCoroutineScope()
    val qr = remember(identity.shareLink) { qrBitmap(identity.shareLink, 720).asImageBitmap() }
    suspend fun image(): Bitmap = withContext(Dispatchers.Default) { cardBitmap(identity, me.photo?.asAndroidBitmap()) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MeshColors.Gradient).statusBarsPadding().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Text("My card", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(40.dp))
            Box(Modifier.padding(horizontal = 28.dp), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.padding(top = 48.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White).padding(top = 60.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(identity.username, color = Color(0xFF11141B), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        com.xalvxnlab.meshron.ui.components.Badges(identity.publicKey.toHex(), 18.dp)
                    }
                    if (identity.handle.isNotEmpty()) Text("@" + identity.handle, color = Color(0xFF6B7280), fontSize = 15.sp)
                    Spacer(Modifier.height(18.dp))
                    Image(qr, "QR code", Modifier.size(230.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Scan with Meshron to add me", color = Color(0xFF6B7280), fontSize = 13.sp)
                }
                Box(Modifier.size(96.dp).clip(CircleShape).background(Color.White).padding(4.dp)) { Avatar(identity.username, identity.publicKey.toHex(), 88.dp) }
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.padding(horizontal = 28.dp).navigationBarsPadding().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardAction(Icons.Rounded.Download, "Save", Modifier.weight(1f)) {
                    scope.launch {
                        val ok = runCatching { saveToPhotos(context, image(), "meshron-card-" + (identity.handle.ifEmpty { identity.shortId })) }.isSuccess
                        Toasts.show(context, if (ok) "Saved to your photos" else "Couldn't save it")
                    }
                }
                CardAction(Icons.Rounded.Share, "Share", Modifier.weight(1f)) {
                    scope.launch {
                        runCatching {
                            val dir = File(context.cacheDir, "share").apply { mkdirs() }
                            val f = File(dir, "meshron-card.png")
                            withContext(Dispatchers.IO) { f.outputStream().use { image().compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_TEXT, "Add me on Meshron: " + identity.shareLink).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share my card"))
                        }.onFailure { Toasts.show(context, "Couldn't share it") }
                    }
                }
                CardAction(Icons.Rounded.ContentCopy, "Copy link", Modifier.weight(1f)) {
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Meshron", identity.shareLink))
                    Toasts.show(context, "Link copied")
                }
            }
        }
    }
}

@Composable
private fun CardAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.18f)).clickable(onClick = onClick).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

private fun saveToPhotos(context: android.content.Context, b: Bitmap, name: String) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Meshron")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No storage")
    context.contentResolver.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("No storage")
}

// ---- Edit profile (TikTok-style, edited in place) ------------------------------------

/** Edit profile: photo on top, then Name, Username, Bio and Link, typed straight into the rows. */
@Composable
fun EditProfile(identity: Identity, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    val me by app.profiles.me.collectAsState()
    val account by app.accounts.state.collectAsState(initial = null)
    val anon = com.xalvxnlab.meshron.identity.Mode.anon
    var name by remember { mutableStateOf(identity.username) }
    var handle by remember { mutableStateOf(identity.handle) }
    var bio by remember(me.bio) { mutableStateOf(me.bio) }
    var link by remember(me.link) { mutableStateOf(me.link.removePrefix("https://")) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var nameAt by remember { mutableStateOf(0L) }
    var handleAt by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { nameAt = app.identityRepository.nextNameChange(); handleAt = app.identityRepository.nextHandleChange() }
    val now = System.currentTimeMillis()
    val day = { t: Long -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(t)) }
    val nameLocked = nameAt > now
    val handleLocked = handleAt > now
    val cleanHandle = handle.trim().removePrefix("@").lowercase()
    val handleChanged = !anon && cleanHandle != identity.handle
    val handleValid = Regex("^[a-z0-9_]{3,24}$").matches(cleanHandle)
    var handleFree by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(cleanHandle) {
        handleFree = null
        if (!handleChanged || !handleValid) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        handleFree = if (account != null) runCatching { app.accounts.available(cleanHandle) }.getOrNull() else true
    }
    val changed = name.trim() != identity.username || handleChanged || (!anon && (bio.trim() != me.bio || com.xalvxnlab.meshron.profile.ProfileStore.cleanLink(link) != me.link))
    val canSave = changed && !busy && name.isNotBlank() && (!handleChanged || (handleValid && handleFree == true && (account == null || password.isNotEmpty())))
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { app.profiles.setMine(me.bio, uri, context) }
    }
    fun save() {
        busy = true
        scope.launch {
            val problems = mutableListOf<String>()
            if (name.trim() != identity.username) runCatching { app.identityRepository.rename(name.trim()) }.onFailure { problems += it.message ?: "Name not saved" }
            if (handleChanged) runCatching {
                if (account != null) app.accounts.rename(cleanHandle, password) else app.identityRepository.setHandle(cleanHandle, byUser = true)
            }.onFailure { problems += it.message ?: "Username not saved" }
            if (!anon) {
                val clean = com.xalvxnlab.meshron.profile.ProfileStore.cleanLink(link)
                if (link.isNotBlank() && clean.isEmpty()) problems += "That link doesn't look like a website"
                else if (bio.trim() != me.bio || clean != me.link) app.profiles.setMine(bio.trim(), null, context, link = clean)
            }
            busy = false
            password = ""
            nameAt = app.identityRepository.nextNameChange(); handleAt = app.identityRepository.nextHandleChange()
            Toasts.show(context, problems.firstOrNull() ?: "Saved", long = problems.isNotEmpty())
        }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().imePadding()) {
        // Pinned header: Back, title and Save stay put while the rows scroll.
        Box(Modifier.fillMaxWidth().padding(6.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Edit profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            Text(if (busy) "Saving…" else "Save", color = if (canSave) MeshColors.Accent else MeshColors.TextTertiary, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(10.dp)).clickable(enabled = canSave) { save() }.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(CircleShape).clickable(enabled = !anon) { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Avatar(identity.username, identity.publicKey.toHex(), 96.dp)
                if (!anon) Box(Modifier.size(96.dp).background(Color.Black.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PhotoCamera, "Change photo", tint = Color.White) }
            }
            if (!anon) Text("Change photo", color = MeshColors.Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp).clickable { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            if (!anon && me.photo != null) Text("Remove photo", color = MeshColors.Danger, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).clickable { scope.launch { app.profiles.setMine(me.bio, null, context, removePhoto = true) } })
        }
        Group {
            InlineRow("Name", name, { name = it.take(32) }, enabled = !nameLocked,
                note = if (nameLocked) "You can change your name again on " + day(nameAt) else "You can change your name once every 7 days.")
            if (!anon) InlineRow("Username", handle, { handle = it.take(25) }, prefix = "@", enabled = !handleLocked,
                note = when {
                    handleLocked -> "You can change your username again on " + day(handleAt)
                    !handleChanged -> "You can change your username once every 30 days."
                    !handleValid -> "3–24 letters, numbers or _"
                    handleFree == false -> "@$cleanHandle is taken"
                    handleFree == true -> "@$cleanHandle is free · your old one becomes free for others"
                    else -> "Checking…"
                }, bad = handleChanged && (!handleValid || handleFree == false))
            if (handleChanged && account != null && handleValid && handleFree == true) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { com.xalvxnlab.meshron.ui.account.PasswordField(password, "Your password (needed to change @username)") { password = it } }
            }
        }
        if (!anon) {
            SectionLabel("Basic info")
            Group {
                InlineRow("Bio", bio, { bio = it.take(com.xalvxnlab.meshron.profile.ProfileStore.BIO_MAX) }, placeholder = "Add a bio", multi = true,
                    note = "${bio.length}/${com.xalvxnlab.meshron.profile.ProfileStore.BIO_MAX}")
                InlineRow("Link", link, { link = it.take(200) }, placeholder = "yourwebsite.com", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
            }
        }
        Spacer(Modifier.height(60.dp))
        }
    }
}

@Composable
private fun SectionLabel(t: String) = Text(t, color = MeshColors.TextSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp))

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.Surface1)) { content() }
}

/** A row you type into directly: label on the left, the value as a borderless field. */
@Composable
private fun InlineRow(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", prefix: String = "", enabled: Boolean = true,
    multi: Boolean = false, note: String? = null, bad: Boolean = false, keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = if (multi) Alignment.Top else Alignment.CenterVertically) {
            Text(label, color = MeshColors.TextSecondary, modifier = Modifier.width(96.dp).padding(top = if (multi) 2.dp else 0.dp))
            if (prefix.isNotEmpty()) Text(prefix, color = if (enabled) MeshColors.TextPrimary else MeshColors.TextTertiary, fontWeight = FontWeight.SemiBold)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, enabled = enabled, singleLine = !multi, maxLines = if (multi) 5 else 1,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) MeshColors.TextPrimary else MeshColors.TextTertiary, fontWeight = FontWeight.SemiBold),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MeshColors.Accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
                decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, color = MeshColors.TextTertiary); inner() } },
                modifier = Modifier.weight(1f),
            )
        }
        if (note != null) Text(note, color = if (bad) MeshColors.Danger else MeshColors.TextTertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 96.dp, top = 4.dp))
    }
}
