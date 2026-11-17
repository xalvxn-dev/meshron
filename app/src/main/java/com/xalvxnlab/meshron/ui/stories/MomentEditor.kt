package com.xalvxnlab.meshron.ui.stories

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.ui.components.Toasts
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Text or a sticker placed on the moment: centre (fraction of the canvas), size, turn, colour, filled box. */
private class TextLayer(text: String, color: Int, boxed: Boolean, val sticker: Boolean = false) {
    var text by mutableStateOf(text)
    var color by mutableStateOf(color)
    var boxed by mutableStateOf(boxed)
    var cx by mutableStateOf(0.5f)
    var cy by mutableStateOf(0.42f)
    var scale by mutableStateOf(1f)
    var turn by mutableStateOf(0f)
}

/** A drawn line: points as fractions of the canvas, colour, width as a fraction of the canvas width. */
private class Stroke2(val color: Int, val width: Float) { val points = mutableStateListOf<Offset>() }

private val TEXT_COLORS = listOf(Color.White, Color.Black, Color(0xFFFF3B5C), Color(0xFFFFC53D), Color(0xFF2EE59D), Color(0xFF25C6F5), Color(0xFF6B5BFF), Color(0xFFFF7A00), Color(0xFFE040FB))
private val STICKERS = listOf("😂", "😍", "🔥", "💯", "😭", "🥹", "😎", "🤯", "👀", "🙌", "❤️", "💜", "✨", "🎉", "🥳", "👏", "💀", "🤝", "🙏", "😅", "🤔", "😴", "🎶", "📍", "⭐", "🌙", "☀️", "🌈", "🍕", "⚽", "🎮", "🎬", "📸", "💪", "🫶", "👑")
private const val BASE_SP = 28f

/**
 * Full-screen moment editor in the style of Instagram stories: tools down the right side (Text,
 * Stickers, Mention, Background, Draw, Download), a shared video shown as a tilted card you can
 * move, pinch and turn, a caption, and "Your moment" to share. Sharing flattens it into one photo.
 */
@Composable
fun MomentEditor(background: Bitmap, caption: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val layers = remember { mutableStateListOf<TextLayer>() }
    val strokes = remember { mutableStateListOf<Stroke2>() }
    var editing by remember { mutableStateOf<TextLayer?>(null) }
    var dragging by remember { mutableStateOf<TextLayer?>(null) }
    var overTrash by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(IntSize(1, 1)) }
    var busy by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf(false) }
    var penColor by remember { mutableStateOf(Color.White.toArgb()) }
    var penSize by remember { mutableStateOf(0.012f) }
    var toolsOpen by remember { mutableStateOf(true) }
    var userCaption by remember { mutableStateOf("") }
    val image = remember(background) { background.asImageBitmap() }

    // A shared video sits on a background as a card (Instagram style); a photo fills the screen.
    val reelCard = caption.contains("meshron://reel/")
    val creator = remember(caption) { Regex("🎬 (@?[^·]+?) ·").find(caption)?.groupValues?.get(1)?.trim() }
    val backgrounds = remember(background) { listOf(sampleGradient(background)) + BG_PRESETS }
    var bgIndex by remember { mutableStateOf(0) }
    var cardCx by remember { mutableStateOf(0.5f) }
    var cardCy by remember { mutableStateOf(0.46f) }
    var cardScale by remember { mutableStateOf(1f) }
    var cardTurn by remember { mutableStateOf(-5f) }
    var rotating by remember { mutableStateOf(false) }
    // A shared video plays inside its card (and in the story, for viewers).
    val reelLink = remember(caption) { Regex("meshron://reel/([0-9a-f]{64})/([A-Za-z0-9_-]+)").find(caption) }
    val video by androidx.compose.runtime.produceState<File?>(null, reelLink?.value) {
        value = reelLink?.let { m -> runCatching { app.reels.load(m.groupValues[1], m.groupValues[2]) }.getOrNull() }
    }

    fun addText(prefill: String = "", boxed: Boolean = false) { val l = TextLayer(prefill, Color.White.toArgb(), boxed); layers += l; editing = l }
    fun doneEditing() { editing?.let { if (it.text.isBlank() || it.text == "@") layers.remove(it) }; editing = null }
    fun state() = EditorState(background, reelCard, creator, backgrounds[bgIndex], cardCx, cardCy, cardScale, cardTurn, layers.toList(), strokes.toList(), box, density.density * density.fontScale)

    fun share() {
        busy = true
        scope.launch {
            val res = runCatching {
                val f = withContext(Dispatchers.Default) { flatten(state()) }
                val out = File(context.cacheDir, "moment-edit.jpg")
                withContext(Dispatchers.IO) { out.outputStream().use { f.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                val meta = if (reelCard) "⧉card:" + listOf(cardCx, cardCy, cardScale, cardTurn).joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) } else ""
                val full = listOf(userCaption.trim(), caption, meta).filter { it.isNotBlank() }.joinToString("\n")
                app.storyService.postPhoto(Uri.fromFile(out), full).getOrThrow()
            }
            busy = false
            res.onSuccess { Toasts.show(context, "Added to your moment"); onClose() }.onFailure { Toasts.show(context, it.message ?: "Couldn't share", long = true) }
        }
    }

    BackHandler { when { editing != null -> doneEditing(); drawing -> drawing = false; stickers -> stickers = false; !busy -> onClose() } }
    Dialog(onDismissRequest = { if (editing != null) doneEditing() else if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // ---- The canvas (what gets shared) ----
            Box(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 132.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (reelCard) Brush.verticalGradient(backgrounds[bgIndex].map { Color(it) }) else SolidColor(Color(0xFF111111)))
                    .onSizeChanged { box = it }
                    .pointerInput(drawing) { if (!drawing) detectTapGestures { if (editing == null) addText() } },
            ) {
                if (reelCard) {
                    // The video as a card: drag, pinch, turn (like Instagram).
                    val cardW = with(density) { (box.width * 0.62f).toDp() }
                    Box(
                        Modifier.align(Alignment.TopStart)
                            .offset { IntOffset((cardCx * box.width - box.width * 0.31f).roundToInt(), (cardCy * box.height - box.width * 0.31f * 16f / 9f).roundToInt()) }
                            .width(cardW).height(cardW * 16f / 9f)
                            .pointerInput(drawing) {
                                if (drawing) return@pointerInput
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val ev = awaitPointerEvent()
                                        val pan = ev.calculatePan(); val zoom = ev.calculateZoom(); val rot = ev.calculateRotation()
                                        if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                                            cardCx = (cardCx + pan.x * cardScale / box.width).coerceIn(0.05f, 0.95f)
                                            cardCy = (cardCy + pan.y * cardScale / box.height).coerceIn(0.05f, 0.95f)
                                            cardScale = (cardScale * zoom).coerceIn(0.5f, 1.6f)
                                            cardTurn += rot
                                            ev.changes.forEach { it.consume() }
                                        }
                                    } while (ev.changes.any { it.pressed })
                                }
                            }
                            .graphicsLayer { scaleX = cardScale; scaleY = cardScale; rotationZ = cardTurn }
                            .shadow(18.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                    ) {
                        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        video?.let { com.xalvxnlab.meshron.ui.reels.VideoSurface(it, play = true, muted = false, cover = true) }
                        if (creator != null) Text(creator, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 6.dp, vertical = 2.dp))
                        Text("Meshron", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                    }
                } else {
                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                // Drawing (always visible; captures touches only in draw mode).
                ComposeCanvas(Modifier.fillMaxSize().then(if (drawing) Modifier.pointerInput(penColor, penSize) {
                    detectDragGestures(
                        onDragStart = { o -> strokes += Stroke2(penColor, penSize).also { it.points += Offset(o.x / size.width, o.y / size.height) } },
                        onDrag = { ch, _ -> strokes.lastOrNull()?.points?.add(Offset(ch.position.x / size.width, ch.position.y / size.height)) },
                    )
                } else Modifier)) {
                    for (s in strokes) {
                        if (s.points.size < 2) continue
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(s.points[0].x * size.width, s.points[0].y * size.height)
                            for (q in s.points.drop(1)) lineTo(q.x * size.width, q.y * size.height)
                        }
                        drawPath(p, Color(s.color), style = Stroke(width = s.width * size.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                // Text and stickers.
                for (l in layers) {
                    if (l === editing) continue
                    androidx.compose.runtime.key(l) {
                        var pos by remember { mutableStateOf(IntSize.Zero) }
                        Box(
                            Modifier.offset { IntOffset((l.cx * box.width - pos.width / 2f).roundToInt(), (l.cy * box.height - pos.height / 2f).roundToInt()) }
                                .onSizeChanged { pos = it }
                                .pointerInput(l, drawing) {
                                    if (drawing) return@pointerInput
                                    // One finger drags, two pinch/turn; a tap edits text; letting go on the bin removes it.
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var travel = 0f
                                        var moved = false
                                        do {
                                            val ev = awaitPointerEvent()
                                            val pan = ev.calculatePan(); val zoom = ev.calculateZoom(); val rot = ev.calculateRotation()
                                            travel += pan.getDistance()
                                            if (travel > viewConfiguration.touchSlop || zoom != 1f || rot != 0f) {
                                                moved = true; dragging = l
                                                l.cx = (l.cx + pan.x / box.width).coerceIn(0f, 1f)
                                                l.cy = (l.cy + pan.y / box.height).coerceIn(0f, 1f)
                                                l.scale = (l.scale * zoom).coerceIn(0.4f, 6f)
                                                l.turn += rot
                                                overTrash = l.cy > 0.88f && l.cx in 0.3f..0.7f
                                                ev.changes.forEach { it.consume() }
                                            }
                                        } while (ev.changes.any { it.pressed })
                                        if (!moved) { if (!l.sticker) editing = l }
                                        else { if (overTrash) layers.remove(l); overTrash = false; dragging = null }
                                    }
                                }
                                .graphicsLayer { scaleX = l.scale; scaleY = l.scale; rotationZ = l.turn }
                                .widthIn(max = with(density) { (box.width * 0.85f).toDp() }),
                        ) { LayerText(l) }
                    }
                }
                if (dragging != null) Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).size(if (overTrash) 64.dp else 52.dp).clip(CircleShape)
                        .background(if (overTrash) MeshColors.Danger else Color.Black.copy(alpha = 0.45f)).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Delete, "Remove", tint = Color.White) }
                // Caption, inside the canvas at the bottom (Instagram's "Add a caption…").
                if (!drawing && editing == null) BasicTextField(
                    userCaption, { userCaption = it.take(200) },
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White), maxLines = 2,
                    decorationBox = { inner -> Box { if (userCaption.isEmpty()) Text("Add a caption…", color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp); inner() } },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))).padding(horizontal = 18.dp, vertical = 16.dp),
                )
            }

            // ---- Top-left: close. Right side: the tools ----
            if (editing == null && !drawing && !rotating) {
                Box(Modifier.statusBarsPadding().padding(14.dp)) { RoundButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) } }
                Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 14.dp, end = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tool("Text", toolsOpen, label = { Text("Aa", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) }) { addText() }
                    Tool("Stickers", toolsOpen, Icons.Rounded.EmojiEmotions) { stickers = true }
                    Tool("Mention", toolsOpen, Icons.Rounded.AlternateEmail) { addText("@", boxed = true) }
                    if (reelCard) Tool("Background", toolsOpen, Icons.Rounded.Gradient) { bgIndex = (bgIndex + 1) % backgrounds.size }
                    if (reelCard) Tool("Rotate", toolsOpen, Icons.AutoMirrored.Rounded.RotateRight) { rotating = true }
                    Tool("Draw", toolsOpen, Icons.Rounded.Brush) { drawing = true }
                    Tool("Download", toolsOpen, Icons.Rounded.Download) {
                        scope.launch {
                            val ok = runCatching { withContext(Dispatchers.Default) { flatten(state()) }.let { saveToPhotos(context, it) } }.isSuccess
                            Toasts.show(context, if (ok) "Saved to your photos" else "Couldn't save")
                        }
                    }
                    Box(Modifier.width(46.dp).height(30.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f)).clickable { toolsOpen = !toolsOpen }, contentAlignment = Alignment.Center) {
                        Icon(if (toolsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (toolsOpen) "Hide labels" else "Show labels", tint = Color.White)
                    }
                }
            }

            // ---- Draw mode: colours, pen size, undo, done ----
            if (drawing) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    for (w in listOf(0.006f, 0.012f, 0.024f)) Box(Modifier.padding(horizontal = 4.dp).size(34.dp).clip(CircleShape).background(if (penSize == w) Color.White.copy(alpha = 0.3f) else Color.Transparent).clickable { penSize = w }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size((w * 900).dp.coerceAtMost(22.dp)).clip(CircleShape).background(Color.White))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Done", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { drawing = false }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (c in TEXT_COLORS) {
                        val on = c.toArgb() == penColor
                        Box(Modifier.size(if (on) 34.dp else 28.dp).clip(CircleShape).background(c).border(2.dp, Color.White, CircleShape).clickable { penColor = c.toArgb() })
                    }
                }
            }

            // ---- Rotate: a dial for the video card ----
            if (rotating) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rotate  " + cardTurn.roundToInt() + "°", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    for ((label, deg) in listOf("−90°" to -90f, "0°" to 0f, "+90°" to 90f)) Text(label, color = Color.White, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f)).clickable { cardTurn = if (deg == 0f) 0f else ((cardTurn + deg + 180f).mod(360f)) - 180f }.padding(horizontal = 12.dp, vertical = 6.dp))
                    Text("Done", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50)).clickable { rotating = false }.padding(8.dp))
                }
                androidx.compose.material3.Slider(cardTurn.coerceIn(-180f, 180f), { cardTurn = it }, valueRange = -180f..180f,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
            }

            // ---- Bottom: share (Instagram's "Your stories" + arrow) ----
            if (editing == null && !drawing && !rotating) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).clickable(enabled = !busy) { share() }
                    .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Text("  Your moment", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(54.dp).clip(CircleShape).background(MeshColors.GradientH).clickable(enabled = !busy) { share() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Share", tint = Color.White)
                }
            }

            // ---- Stickers ----
            if (stickers) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { stickers = false }) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Color(0xFF1C1C22)).navigationBarsPadding().padding(16.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
                    Text("Stickers", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 12.dp))
                    LazyVerticalGrid(GridCells.Fixed(6), Modifier.fillMaxWidth().height(260.dp)) {
                        items(STICKERS) { e ->
                            Text(e, fontSize = 34.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(10.dp)).clickable {
                                layers += TextLayer(e, Color.White.toArgb(), false, sticker = true).also { it.scale = 2.2f; it.cy = 0.35f }
                                stickers = false
                            }.padding(4.dp))
                        }
                    }
                }
            }

            // ---- Typing: dimmed screen, the text in the middle, colours and box toggle ----
            editing?.let { l ->
                val focus = remember(l) { FocusRequester() }
                LaunchedEffect(l) { focus.requestFocus() }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).pointerInput(l) { detectTapGestures { doneEditing() } }) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(onClick = { l.boxed = !l.boxed }) {
                            Text("A", color = if (l.boxed) Color.Black else Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (l.boxed) Color.White else Color.Transparent).padding(horizontal = 6.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("Done", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { doneEditing() }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(bottom = 8.dp), verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                            BasicTextField(
                                value = l.text, onValueChange = { l.text = it.take(240) },
                                textStyle = TextStyle(color = Color(if (l.boxed) contrast(l.color) else l.color), fontSize = BASE_SP.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center),
                                cursorBrush = SolidColor(Color.White),
                                modifier = Modifier.focusRequester(focus).clip(RoundedCornerShape(10.dp)).background(if (l.boxed) Color(l.color) else Color.Transparent).padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (c in TEXT_COLORS) {
                                val on = c.toArgb() == l.color
                                Box(Modifier.size(if (on) 34.dp else 28.dp).clip(CircleShape).background(c).border(2.dp, Color.White, CircleShape).clickable { l.color = c.toArgb() })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One tool on the right: its name (when open) and a round icon. */
@Composable
private fun Tool(name: String, showName: Boolean, icon: ImageVector? = null, label: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        if (showName) Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp),
            style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 6f)))
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
            if (label != null) label() else if (icon != null) Icon(icon, name, tint = Color.White)
        }
    }
}

@Composable
private fun LayerText(l: TextLayer) {
    if (l.sticker) { Text(l.text, fontSize = BASE_SP.sp); return }
    Text(
        l.text, color = Color(if (l.boxed) contrast(l.color) else l.color), fontSize = BASE_SP.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
        style = if (l.boxed) TextStyle() else TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 8f)),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (l.boxed) Color(l.color) else Color.Transparent).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/** Black text on light fills, white on dark ones. */
private fun contrast(argb: Int): Int {
    val c = Color(argb)
    return if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue > 0.6f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
}

/** Background gradients for a shared video: the first comes from the video's own colours. */
private val BG_PRESETS = listOf(
    listOf(0xFF4F7BFF.toInt(), 0xFF19D3C5.toInt()), listOf(0xFF8B5CFF.toInt(), 0xFFFF3B8D.toInt()), listOf(0xFFFF8A00.toInt(), 0xFFE5243B.toInt()),
    listOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt()), listOf(0xFF111111.toInt(), 0xFF333333.toInt()), listOf(0xFFF5F5F5.toInt(), 0xFFD9D9D9.toInt()),
)

private fun sampleGradient(b: Bitmap): List<Int> {
    fun avg(y0: Int, y1: Int): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        val step = maxOf(1, b.width / 24)
        for (y in y0 until y1 step maxOf(1, (y1 - y0) / 12)) for (x in 0 until b.width step step) {
            val p = b.getPixel(x, y.coerceIn(0, b.height - 1)); r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255; n++
        }
        if (n == 0) return 0xFF333333.toInt()
        // A little darker, so the card stands out.
        return (0xFF shl 24) or (((r / n) * 8 / 10).toInt() shl 16) or (((g / n) * 8 / 10).toInt() shl 8) or ((bl / n) * 8 / 10).toInt()
    }
    return listOf(avg(0, b.height / 3), avg(b.height * 2 / 3, b.height))
}

private class EditorState(
    val bg: Bitmap, val card: Boolean, val creator: String?, val gradient: List<Int>, val cardCx: Float, val cardCy: Float, val cardScale: Float, val cardTurn: Float,
    val layers: List<TextLayer>, val strokes: List<Stroke2>, val box: IntSize, val spPx: Float,
)

/** Draws everything into one 1080-wide image the same shape as the editor. */
private fun flatten(s: EditorState): Bitmap {
    val box = s.box
    val outW = 1080
    val k = outW / box.width.toFloat()
    val outH = (box.height * k).roundToInt()
    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val cv = Canvas(out)
    if (s.card) {
        cv.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, outH.toFloat(), s.gradient[0], s.gradient[1], Shader.TileMode.CLAMP) })
        // The card: 62% of the width, 9:16, rounded, turned and scaled around its centre.
        val cw = outW * 0.62f; val ch = cw * 16f / 9f
        cv.save()
        cv.translate(s.cardCx * outW, s.cardCy * outH + (ch / 2 - outW * 0.31f * 16f / 9f) )
        cv.rotate(s.cardTurn); cv.scale(s.cardScale, s.cardScale)
        val rect = RectF(-cw / 2, -ch / 2, cw / 2, ch / 2)
        cv.drawRoundRect(RectF(rect).apply { offset(0f, 14f) }, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 })
        val clip = Path().apply { addRoundRect(rect, 40f, 40f, Path.Direction.CW) }
        cv.save(); cv.clipPath(clip)
        val sc = maxOf(cw / s.bg.width, ch / s.bg.height)
        val w = s.bg.width * sc; val h = s.bg.height * sc
        cv.drawBitmap(s.bg, null, RectF(-w / 2, -h / 2, w / 2, h / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        cv.restore()
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(6f, 0f, 0f, 0x88000000.toInt()) }
        s.creator?.let { cv.drawText(it, rect.left + 24f, rect.top + 48f, small) }
        cv.drawText("Meshron", rect.right - 24f - small.measureText("Meshron"), rect.bottom - 24f, small)
        cv.restore()
    } else {
        cv.drawColor(0xFF111111.toInt())
        val sc = minOf(outW / s.bg.width.toFloat(), outH / s.bg.height.toFloat())
        val w = s.bg.width * sc; val h = s.bg.height * sc
        cv.drawBitmap(s.bg, null, RectF((outW - w) / 2, (outH - h) / 2, (outW + w) / 2, (outH + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    for (st in s.strokes) {
        if (st.points.size < 2) continue
        val p = Path().apply { moveTo(st.points[0].x * outW, st.points[0].y * outH); for (q in st.points.drop(1)) lineTo(q.x * outW, q.y * outH) }
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = st.color; style = Paint.Style.STROKE; strokeWidth = st.width * outW; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND })
    }
    for (l in s.layers) {
        if (l.text.isBlank()) continue
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BASE_SP * s.spPx * k
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = if (l.sticker) android.graphics.Color.BLACK else if (l.boxed) contrast(l.color) else l.color
            if (!l.boxed && !l.sticker) setShadowLayer(8f * k, 0f, 0f, 0x73000000)
        }
        val padX = 10 * s.spPx * k; val padY = 4 * s.spPx * k
        val maxW = (box.width * 0.85f * k - 2 * padX).roundToInt()
        val textW = minOf(maxW, l.text.lines().maxOf { paint.measureText(it) }.toInt() + 2)
        val layout = StaticLayout.Builder.obtain(l.text, 0, l.text.length, paint, maxOf(1, textW)).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        cv.save()
        cv.translate(l.cx * outW, l.cy * outH)
        cv.rotate(l.turn)
        cv.scale(l.scale, l.scale)
        val bw = textW + 2 * padX; val bh = layout.height + 2 * padY
        if (l.boxed) cv.drawRoundRect(RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), 10 * s.spPx * k, 10 * s.spPx * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.color })
        cv.translate(-textW / 2f, -layout.height / 2f)
        layout.draw(cv)
        cv.restore()
    }
    return out
}

private fun saveToPhotos(context: android.content.Context, b: Bitmap) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "meshron-moment-" + System.currentTimeMillis() + ".jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Meshron")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("No storage")
    context.contentResolver.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: error("No storage")
}
