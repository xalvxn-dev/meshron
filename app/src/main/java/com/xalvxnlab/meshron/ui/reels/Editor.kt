package com.xalvxnlab.meshron.ui.reels

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xalvxnlab.meshron.reels.Edit
import com.xalvxnlab.meshron.reels.Look
import com.xalvxnlab.meshron.reels.MusicTrack
import com.xalvxnlab.meshron.reels.Reel
import com.xalvxnlab.meshron.reels.ReelSound
import com.xalvxnlab.meshron.reels.ReelsService
import com.xalvxnlab.meshron.reels.TextLayer
import com.xalvxnlab.meshron.reels.VideoEditor
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

private enum class Tool(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Trim("Trim", Icons.Rounded.ContentCut), Speed("Speed", Icons.Rounded.Speed), Looks("Filters", Icons.Rounded.AutoAwesome),
    Effects("Effects", Icons.Rounded.AutoFixHigh), Adjust("Adjust", Icons.Rounded.Tune), Text("Text", Icons.Rounded.TextFields),
    Stickers("Stickers", Icons.Rounded.EmojiEmotions), Crop("Canvas", Icons.Rounded.Crop),
    Sound("Sound", Icons.Rounded.MusicNote), Cover("Cover", Icons.Rounded.Image),
}

private val STICKERS = listOf("😂", "😍", "🔥", "💯", "😭", "🥹", "😎", "🤯", "👀", "🙌", "❤️", "💜", "✨", "🎉", "🥳", "👏", "💀", "🤝", "🙏", "😅", "🤔", "🎶", "📍", "⭐", "🌙", "☀️", "🌈", "🍕", "⚽", "🎮", "🎬", "📸", "💪", "🫶", "👑", "➡️", "⬆️", "❓", "‼️", "✅")

private fun fontOf(name: String) = when (name) {
    "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
    "monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
    "cursive" -> androidx.compose.ui.text.font.FontFamily.Cursive
    else -> androidx.compose.ui.text.font.FontFamily.SansSerif
}

private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFF111111, 0xFFB6F36A, 0xFF22D3C5, 0xFF8E9BFF, 0xFFFF8A2B, 0xFFFF3B5C).map { it.toInt() }

/** Edit a reel (trim, speed, looks, adjust, text, crop, sound, cover), then caption and post. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(reels: ReelsService, uri: Uri, sound: ReelSound?, soundItem: Reel?, onBack: () -> Unit, onPosted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var durMs by remember { mutableStateOf(0L) }
    var vw by remember { mutableStateOf(0) }
    var vh by remember { mutableStateOf(0) }
    var e by remember { mutableStateOf(Edit()) }
    var tool by remember { mutableStateOf(Tool.Trim) }
    var library by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var newText by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf(false) }
    val aiApp = context.applicationContext as com.xalvxnlab.meshron.MeshronApp
    var selectedText by remember { mutableStateOf(-1) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                r.setDataSource(context, uri)
                durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                if (rot % 180 != 0) { vw = h; vh = w } else { vw = w; vh = h }
                r.release()
            }
        }
        e = e.copy(trimEndMs = minOf(durMs, 60_000L), coverMs = minOf(500L, durMs / 3))
        // Filmed to a sound: it's the reel's music from the start.
        if (soundItem != null && sound != null) runCatching { reels.load(soundItem.ch, soundItem.id) }.getOrNull()?.let {
            e = e.copy(music = MusicTrack(it, sound.title, sound = sound), originalVolume = 0f, musicVolume = 1f)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07080B)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
            Text("Edit", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(MeshColors.GradientH).clickable(enabled = durMs > 0) { posting = true }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) { Text("Next", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        // Preview.
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            val ratio = when (e.aspect) {
                "9:16" -> 9f / 16f; "1:1" -> 1f; "4:5" -> 4f / 5f
                else -> if (vw > 0 && vh > 0) (if (e.rotation % 180 != 0) vh.toFloat() / vw else vw.toFloat() / vh) else 9f / 16f
            }
            BoxWithConstraints(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                // The preview lets go of the video decoder while the reel is being made.
                val turned = e.rotation % 180 != 0
                if (!busy) Box(Modifier.align(Alignment.Center).then(if (turned) Modifier.requiredSize(maxHeight, maxWidth) else Modifier.fillMaxSize())
                    .rotate(e.rotation.toFloat()).graphicsLayer { scaleX = (if (e.flip) -1f else 1f) * e.zoom; scaleY = e.zoom }) {
                    PreviewPlayer(uri, e, cover = e.aspect != "original" && !e.fit)
                }
                // Text layers: drag to place, tap to select.
                val d = LocalDensity.current
                e.texts.forEachIndexed { i, t ->
                    var pos by remember(i, t) { mutableStateOf(t.x to t.y) }
                    val wPx = with(d) { maxWidth.toPx() }
                    val hPx = with(d) { maxHeight.toPx() }
                    Text(
                        t.text, color = Color(t.color), fontWeight = if (t.font == "sans-serif-black") FontWeight.Black else FontWeight.Bold, fontSize = (22 * t.size).sp, fontFamily = fontOf(t.font),
                        modifier = Modifier.offset { IntOffset((pos.first * wPx).roundToInt() - 60, (pos.second * hPx).roundToInt() - 30) }
                            .then(if (t.pill) Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)) else Modifier)
                            .border(if (selectedText == i) 2.dp else 0.dp, if (selectedText == i) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickable { selectedText = i; tool = Tool.Text }
                            .pointerInput(i) {
                                detectDragGestures(onDragEnd = {
                                    e = e.copy(texts = e.texts.toMutableList().also { l -> l[i] = t.copy(x = pos.first, y = pos.second) })
                                }) { _, drag -> pos = ((pos.first + drag.x / wPx).coerceIn(0.05f, 0.95f)) to ((pos.second + drag.y / hPx).coerceIn(0.05f, 0.95f)) }
                            },
                    )
                }
            }
        }
        // Tool bar.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool.entries.forEach { t ->
                val on = t == tool
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent).clickable { tool = t }.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, t.label, tint = if (on) Color.White else Color.White.copy(alpha = 0.6f))
                    Text(t.label, color = if (on) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Tool panel.
        Column(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(horizontal = 18.dp, vertical = 8.dp)) {
            val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = MeshColors.GradMid, inactiveTrackColor = Color.White.copy(alpha = 0.2f))
            when (tool) {
                Tool.Trim -> {
                    val end = if (e.trimEndMs == Long.MAX_VALUE) durMs else e.trimEndMs
                    Label("Keep ${fmtMs(end - e.trimStartMs)} of ${fmtMs(durMs)} · up to 1:00")
                    if (durMs > 0) RangeSlider(
                        value = e.trimStartMs.toFloat()..end.toFloat(), valueRange = 0f..durMs.toFloat(), colors = sliderColors,
                        onValueChange = { r ->
                            var s = r.start.toLong(); var en = r.endInclusive.toLong()
                            if (en - s > 60_000) { if (s != e.trimStartMs) en = s + 60_000 else s = en - 60_000 }
                            if (en - s >= 1000) e = e.copy(trimStartMs = s, trimEndMs = en)
                        },
                    )
                }
                Tool.Speed -> {
                    Label("Speed")
                    Chips(listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f).map { (if (it == it.toInt().toFloat()) "${it.toInt()}×" else "$it×") to it }, e.speed) { e = e.copy(speed = it) }
                }
                Tool.Looks -> {
                    Label("Looks")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Look.entries.forEach { l -> LookChip(uri, l, e.copy(look = l), on = e.look == l) { e = e.copy(look = l) } }
                    }
                }
                Tool.Effects -> {
                    Label("Effects")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Toggle("Fade in", e.fadeIn) { e = e.copy(fadeIn = !e.fadeIn) }
                        Toggle("Fade out", e.fadeOut) { e = e.copy(fadeOut = !e.fadeOut) }
                        Toggle("Mirror", e.flip) { e = e.copy(flip = !e.flip) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Slide("Zoom", e.zoom, sliderColors, 1f..2f) { e = e.copy(zoom = it) }
                }
                Tool.Stickers -> {
                    Label("Tap to add · drag it on the video · tap it to resize")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        STICKERS.forEach { s ->
                            Text(s, fontSize = 30.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                                e = e.copy(texts = e.texts + TextLayer(s, 0.5f, 0.4f, size = 2f, pill = false)); selectedText = e.texts.lastIndex
                            }.padding(6.dp))
                        }
                    }
                }
                Tool.Adjust -> {
                    Slide("Brightness", e.brightness, sliderColors) { e = e.copy(brightness = it) }
                    Slide("Warmth", e.warmth, sliderColors) { e = e.copy(warmth = it) }
                    Slide("Contrast", e.contrast, sliderColors) { e = e.copy(contrast = it) }
                    Slide("Saturation", e.saturation, sliderColors) { e = e.copy(saturation = it) }
                }
                Tool.Text -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = newText, onValueChange = { newText = it.take(80) }, singleLine = true, placeholder = { Text("Type text, then Add") },
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.White.copy(alpha = 0.1f), unfocusedContainerColor = Color.White.copy(alpha = 0.1f), focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        )
                        Spacer(Modifier.width(8.dp))
                        Pill("Add") { if (newText.isNotBlank()) { e = e.copy(texts = e.texts + TextLayer(newText.trim(), 0.5f, 0.45f)); selectedText = e.texts.lastIndex; newText = "" } }
                    }
                    if (selectedText in e.texts.indices) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TEXT_COLORS.forEach { c ->
                                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color(c)).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(9.dp)).clickable {
                                    val t = e.texts[selectedText]; e = e.copy(texts = e.texts.toMutableList().also { it[selectedText] = t.copy(color = c) })
                                })
                            }
                            Pill("Bigger") { val t = e.texts[selectedText]; e = e.copy(texts = e.texts.toMutableList().also { it[selectedText] = t.copy(size = (t.size * 1.2f).coerceAtMost(4f)) }) }
                            Pill("Smaller") { val t = e.texts[selectedText]; e = e.copy(texts = e.texts.toMutableList().also { it[selectedText] = t.copy(size = (t.size / 1.2f).coerceAtLeast(0.4f)) }) }
                            Pill(if (e.texts[selectedText].pill) "No box" else "Box") { val t = e.texts[selectedText]; e = e.copy(texts = e.texts.toMutableList().also { it[selectedText] = t.copy(pill = !t.pill) }) }
                            Pill("Remove") { e = e.copy(texts = e.texts.filterIndexed { i, _ -> i != selectedText }); selectedText = -1 }
                        }
                        if (selectedText in e.texts.indices) {
                            Spacer(Modifier.height(8.dp))
                            Chips(com.xalvxnlab.meshron.reels.TEXT_FONTS, e.texts[selectedText].font) { f -> val t = e.texts[selectedText]; e = e.copy(texts = e.texts.toMutableList().also { it[selectedText] = t.copy(font = f) }) }
                        }
                    } else Label("Drag text on the video to place it. Tap it to change colour or size.")
                }
                Tool.Crop -> {
                    Label("Shape")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Chips(listOf("Original" to "original", "9:16" to "9:16", "1:1" to "1:1", "4:5" to "4:5"), e.aspect) { e = e.copy(aspect = it) }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { e = e.copy(rotation = (e.rotation + 90) % 360) }) { Icon(Icons.Rounded.RotateRight, "Rotate", tint = Color.White) }
                    }
                    if (e.aspect != "original") {
                        Spacer(Modifier.height(8.dp))
                        Chips(listOf("Fill (crop)" to false, "Fit (bars)" to true), e.fit) { e = e.copy(fit = it) }
                    }
                }
                Tool.Sound -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, null, tint = Color.White)
                        Text(" " + (e.music?.title ?: "No music added"), color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                        Pill(if (e.music == null) "Add music" else "Change") { library = true }
                        if (e.music != null) { Spacer(Modifier.width(6.dp)); Pill("Remove") { e = e.copy(music = null, originalVolume = 1f) } }
                    }
                    Slide("Original sound", e.originalVolume, sliderColors, 0f..1.5f) { e = e.copy(originalVolume = it) }
                    if (e.music != null) Slide("Music", e.musicVolume, sliderColors, 0f..1.5f) { e = e.copy(musicVolume = it) }
                }
                Tool.Cover -> {
                    Label("Pick the frame people see before it plays")
                    if (durMs > 0) Slider(e.coverMs.toFloat(), { e = e.copy(coverMs = it.toLong()) }, valueRange = e.trimStartMs.toFloat()..(if (e.trimEndMs == Long.MAX_VALUE) durMs else e.trimEndMs).toFloat(), colors = sliderColors)
                }
            }
        }
    }

    if (library) SongLibrary(reels, onPick = { m -> library = false; e = e.copy(music = m, originalVolume = if (e.originalVolume == 1f) 0.3f else e.originalVolume) }, onDismiss = { library = false })

    if (posting) ModalBottomSheet(onDismissRequest = { if (!busy) posting = false }, containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
            Text("Post", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            TextField(
                value = caption, onValueChange = { caption = it.take(300) }, enabled = !busy, minLines = 2, maxLines = 4,
                placeholder = { Text("Say something… #tags welcome") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            e.music?.let { Text("♪ " + it.title, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            // ✨ Let the AI write a caption and hashtags (from what you typed, if anything).
            if (!com.xalvxnlab.meshron.identity.Mode.anon) Row(
                Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).background(MeshColors.AccentContainer).clickable(enabled = !busy && !writing) {
                    writing = true; error = null
                    scope.launch {
                        runCatching { aiApp.ai.caption(caption.ifBlank { "A short video" + (e.music?.let { " with the song " + it.title } ?: "") }) }
                            .onSuccess { (c, tags) -> caption = (c + "\n" + tags.joinToString(" ")).trim().take(300) }
                            .onFailure { error = it.message }
                        writing = false
                    }
                }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (writing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.AutoAwesome, null, tint = MeshColors.OnAccentContainer, modifier = Modifier.size(16.dp))
                Text("  Write caption & hashtags", color = MeshColors.OnAccentContainer, style = MaterialTheme.typography.labelLarge)
            }
            error?.let { Text(it, color = MeshColors.Danger, modifier = Modifier.padding(top = 8.dp)) }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = MeshColors.GradMid)
                Text(stage, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp)).background(if (busy) MeshColors.Surface3 else MeshColors.TextPrimary).clickable(enabled = !busy) {
                    busy = true; error = null
                    scope.launch {
                        try {
                            // Safety: harmful captions aren't posted.
                            val v = aiApp.ai.check(caption)
                            if (!v.ok) throw Exception("This caption can't be posted: " + v.reason.ifBlank { "it may be harmful" })
                            val out = File(context.cacheDir, "reel-out.mp4")
                            stage = "Making your reel…"
                            VideoEditor.export(context, uri, e, vw, vh, out) { progress = it * 0.8f; stage = "Making your reel… ${(it * 100).toInt()}%" }
                            val thumb = withContext(Dispatchers.IO) { cover(context, out, ((e.coverMs - e.trimStartMs) / e.speed).toLong().coerceAtLeast(0)) }
                            stage = "Sharing…"
                            val m = e.music
                            reels.post(out, caption.trim(), (e.lengthMs / 1000).toInt().coerceAtLeast(1), thumb.second, thumb.third, thumb.first,
                                sound = m?.sound, onProgress = { progress = 0.8f + it * 0.2f }, title = if (m != null && m.sound == null) m.title else "")
                            out.delete()
                            onPosted()
                        } catch (t: Throwable) {
                            error = t.message ?: "Couldn't post that"
                            busy = false
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Post reel", color = MeshColors.Bg, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Cover: a small JPEG of the exported video at [atMs] (≤ 30 KB), with its size. */
private fun cover(context: android.content.Context, file: File, atMs: Long): Triple<String, Int, Int> {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.path)
        val f = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: r.getFrameAtTime(0) ?: return Triple("", 720, 1280)
        val w = 270
        val h = minOf(480, (w * f.height.toFloat() / f.width).roundToInt())
        val small = Bitmap.createScaledBitmap(f, w, maxOf(1, h), true)
        var q = 74
        var bytes: ByteArray
        do { val o = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, q, o); bytes = o.toByteArray(); q -= 12 } while (bytes.size > 28_000 && q > 25)
        return Triple(Base64.encodeToString(bytes, Base64.NO_WRAP), f.width, f.height)
    } finally { r.release() }
}

private fun fmtMs(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

/** Live preview: plays the trimmed part, at the chosen speed, with the look applied. */
@Composable
private fun PreviewPlayer(uri: Uri, e: Edit, cover: Boolean) {
    val context = LocalContext.current
    val player = remember { MediaPlayer() }
    val music = remember { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<TextureView?>(null) }
    val coverNow by androidx.compose.runtime.rememberUpdatedState(cover)
    DisposableEffect(Unit) { onDispose { player.release(); music.release() } }
    fun fitPicture(tv: TextureView) {
        val vw = runCatching { player.videoWidth.toFloat() }.getOrDefault(0f); val vh = runCatching { player.videoHeight.toFloat() }.getOrDefault(0f)
        if (vw <= 0f || vh <= 0f || tv.width <= 0 || tv.height <= 0) return
        val s = if (coverNow) maxOf(tv.width / vw, tv.height / vh) else minOf(tv.width / vw, tv.height / vh)
        tv.setTransform(android.graphics.Matrix().apply { setScale(vw * s / tv.width, vh * s / tv.height, tv.width / 2f, tv.height / 2f) })
    }
    LaunchedEffect(cover, ready, view) { view?.let { fitPicture(it) } }
    // Look + adjustments, applied to the video layer directly.
    LaunchedEffect(e.look, e.brightness, e.contrast, e.saturation, view) {
        view?.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(VideoEditor.colorMatrix(e)) })
    }
    LaunchedEffect(e.speed, ready) { if (ready) runCatching { player.playbackParams = player.playbackParams.setSpeed(e.speed) } }
    LaunchedEffect(e.originalVolume, ready) { if (ready) player.setVolume(e.originalVolume.coerceAtMost(1f), e.originalVolume.coerceAtMost(1f)) }
    LaunchedEffect(e.music) {
        runCatching { music.reset() }
        val m = e.music ?: return@LaunchedEffect
        runCatching { music.setDataSource(m.file.path); music.prepare(); music.isLooping = true; music.seekTo(m.startMs.toInt()); music.start() }
    }
    LaunchedEffect(e.musicVolume, e.music) { runCatching { music.setVolume(e.musicVolume.coerceAtMost(1f), e.musicVolume.coerceAtMost(1f)) } }
    // Loop inside the trim.
    LaunchedEffect(ready, e.trimStartMs, e.trimEndMs) {
        if (!ready) return@LaunchedEffect
        player.seekTo(e.trimStartMs.toInt())
        while (true) {
            if (player.currentPosition >= e.trimEndMs || player.currentPosition < e.trimStartMs - 500) {
                player.seekTo(e.trimStartMs.toInt())
                e.music?.let { runCatching { music.seekTo(it.startMs.toInt()) } }
            }
            delay(100)
        }
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                view = tv
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPicture(tv) }
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        runCatching {
                            player.setSurface(Surface(st))
                            player.setDataSource(context, uri)
                            player.isLooping = true
                            player.setOnPreparedListener { p -> fitPicture(tv); p.start(); ready = true }
                            player.setOnVideoSizeChangedListener { _, _, _ -> fitPicture(tv) }
                            player.prepareAsync()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fitPicture(tv) }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** A look, shown on a frame of the video. */
@Composable
private fun LookChip(uri: Uri, look: Look, e: Edit, on: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        frame = withContext(Dispatchers.IO) {
            runCatching { MediaMetadataRetriever().run { setDataSource(context, uri); val f = getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 200); release(); f } }.getOrNull()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(Modifier.size(width = 58.dp, height = 78.dp).clip(RoundedCornerShape(14.dp)).border(2.dp, if (on) Color.White else Color.Transparent, RoundedCornerShape(14.dp)).background(Color.DarkGray)) {
            frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(VideoEditor.colorMatrix(e).array))) }
        }
        Text(look.label, color = if (on) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable private fun Label(t: String) = Text(t, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun Slide(label: String, v: Float, colors: androidx.compose.material3.SliderColors, range: ClosedFloatingPointRange<Float> = -1f..1f, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Slider(v, onChange, valueRange = range, colors = colors, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun <T> Chips(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            val on = v == selected
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.12f)).clickable { onSelect(v) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
