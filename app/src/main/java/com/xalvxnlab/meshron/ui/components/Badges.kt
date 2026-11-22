package com.xalvxnlab.meshron.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.ui.theme.MeshColors

// Meshron's badge family: every mark is drawn here (no stock icons), so they look like nobody else's.
private val VerifiedFill = listOf(Color(0xFF19D3C5), Color(0xFF4F7BFF), Color(0xFF8B5CFF))
private val ModFill = listOf(Color(0xFF6B5BFF), Color(0xFF9B4DFF), Color(0xFFD946EF))
private val FounderFill = listOf(Color(0xFFFFF1B8), Color(0xFFFFC83D), Color(0xFFD99A00), Color(0xFF8A5A00))
private val CofounderFill = listOf(Color(0xFFFFFFFF), Color(0xFFE4E8EE), Color(0xFFB4BBC6), Color(0xFF7D8591)) // silver
private val FounderInk = Color(0xFF3D2A00)
private val CofounderInk = Color(0xFF1F242C)

/**
 * Badges after a name: founders' crowns, verified hexagon, moderator shield.
 * [full] also shows the founders' title ("CEO · Founder") as a pill, for profile pages.
 */
@Composable
fun Badges(id: String, size: Dp = 16.dp, full: Boolean = false) {
    if (id.length != 64) return
    val app = LocalContext.current.applicationContext as MeshronApp
    val all by app.moderation.badges.collectAsState()
    LaunchedEffect(id) { app.moderation.want(listOf(id)) }
    val b = all[id] ?: return
    BadgeRow(b.verified, b.mod, b.title, size, full)
}

@Composable
fun BadgeRow(verified: Boolean, mod: Boolean, title: String?, size: Dp, full: Boolean) {
    Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        when (title) {
            "founder" -> if (full) TitlePill("CEO · Founder", FounderFill, FounderInk, size) else FounderMark(size)
            "cofounder" -> if (full) TitlePill("Co-CEO · Co-founder", CofounderFill, CofounderInk, size) else CofounderMark(size)
            "ai" -> AiPill(size)
        }
        if (verified) VerifiedMark(size)
        if (mod) ModMark(size)
    }
}

private fun hexagon(w: Float): Path = Path().apply {
    val r = w / 2f
    for (i in 0 until 6) {
        val a = Math.toRadians(60.0 * i - 90.0)
        val x = r + r * kotlin.math.cos(a).toFloat()
        val y = r + r * kotlin.math.sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun DrawScope.rim(path: Path, w: Float) =
    drawPath(path, Color.White.copy(alpha = 0.4f), style = Stroke(width = w * 0.06f, join = StrokeJoin.Round))

/** Verified: a mesh node (hexagon), teal → blue → violet, with a check. */
@Composable
fun VerifiedMark(size: Dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = "Verified" }) {
        val w = this.size.width
        val hex = hexagon(w)
        drawPath(hex, Brush.linearGradient(VerifiedFill, Offset.Zero, Offset(w, w)))
        rim(hex, w)
        val check = Path().apply { moveTo(w * 0.30f, w * 0.52f); lineTo(w * 0.45f, w * 0.66f); lineTo(w * 0.71f, w * 0.38f) }
        drawPath(check, Color.White, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Moderator: a shield in Meshron violet → magenta, holding a small mesh node. */
@Composable
fun ModMark(size: Dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = "Meshron moderator" }) {
        val w = this.size.width
        val shield = Path().apply {
            moveTo(w * 0.5f, w * 0.02f)
            lineTo(w * 0.9f, w * 0.17f)
            cubicTo(w * 0.92f, w * 0.55f, w * 0.75f, w * 0.83f, w * 0.5f, w * 0.98f)
            cubicTo(w * 0.25f, w * 0.83f, w * 0.08f, w * 0.55f, w * 0.1f, w * 0.17f)
            close()
        }
        drawPath(shield, Brush.linearGradient(ModFill, Offset.Zero, Offset(w, w)))
        rim(shield, w)
        // The mesh node inside: a small hexagon outline with a centre dot.
        val r = w * 0.2f
        val c = Offset(w * 0.5f, w * 0.47f)
        val node = Path().apply {
            for (i in 0 until 6) {
                val a = Math.toRadians(60.0 * i - 90.0)
                val x = c.x + r * kotlin.math.cos(a).toFloat()
                val y = c.y + r * kotlin.math.sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(node, Color.White, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
        drawCircle(Color.White, radius = w * 0.06f, center = c)
    }
}

/** A small crown, drawn inside the founders' hexagons. */
private fun DrawScope.crown(w: Float, ink: Color) {
    val crown = Path().apply {
        moveTo(w * 0.24f, w * 0.66f)
        lineTo(w * 0.27f, w * 0.36f)
        lineTo(w * 0.40f, w * 0.50f)
        lineTo(w * 0.50f, w * 0.30f)
        lineTo(w * 0.60f, w * 0.50f)
        lineTo(w * 0.73f, w * 0.36f)
        lineTo(w * 0.76f, w * 0.66f)
        close()
    }
    drawPath(crown, ink)
    drawRect(ink, topLeft = Offset(w * 0.24f, w * 0.69f), size = androidx.compose.ui.geometry.Size(w * 0.52f, w * 0.06f))
}

/** CEO & founder: a gold hexagon with a crown. */
@Composable
fun FounderMark(size: Dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = "CEO and founder of Meshron" }) {
        val w = this.size.width
        val hex = hexagon(w)
        drawPath(hex, Brush.linearGradient(FounderFill, Offset.Zero, Offset(w, w)))
        rim(hex, w)
        crown(w, FounderInk)
    }
}

/** Co-CEO & co-founder: a rose-gold → violet hexagon with a crown. */
@Composable
fun CofounderMark(size: Dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = "Co-CEO and co-founder of Meshron" }) {
        val w = this.size.width
        val hex = hexagon(w)
        drawPath(hex, Brush.linearGradient(CofounderFill, Offset.Zero, Offset(w, w)))
        rim(hex, w)
        crown(w, CofounderInk)
    }
}

/**
 * The bio on a profile. The CEOs' bios are special: a metallic card (gold for the CEO, silver for
 * the co-CEO) with their title on it, so anyone can tell it's really them.
 */
@Composable
fun RoleBio(id: String, bio: String, modifier: Modifier = Modifier) {
    // Just the bio, for everyone (the founders' badge by their name says who they are).
    @Suppress("UNUSED_VARIABLE") val unused = id
    if (bio.isNotBlank()) Text(bio, color = MeshColors.TextPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = modifier.padding(horizontal = 32.dp, vertical = 6.dp))
}

/** Meshron AI's tag: a small gradient "AI" pill. */
@Composable
fun AiPill(size: Dp) {
    androidx.compose.foundation.layout.Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(MeshColors.Gradient).padding(horizontal = (size.value * 0.3f).dp, vertical = (size.value * 0.08f).dp)) {
        Text("AI", color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.6f).sp, lineHeight = (size.value * 0.6f).sp,
            style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
    }
}

/** The founders' title on profiles: the mark plus "CEO · Founder" in a metallic pill. */
@Composable
private fun TitlePill(title: String, fill: List<Color>, ink: Color, size: Dp) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Brush.linearGradient(fill)).border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(start = 3.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (fill === FounderFill) FounderMark(size) else CofounderMark(size)
        Text(" $title", color = ink, fontWeight = FontWeight.Black, fontSize = (size.value * 0.68f).sp)
    }
}

/** "Meshron badges": what each badge means (Settings). */
@Composable
fun BadgeGuide() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GuideRow({ VerifiedMark(26.dp) }, "Verified", "A person at Meshron confirmed this is really them: known people, creators, brands.")
        GuideRow({ ModMark(26.dp) }, "Meshron moderator", "Reviews reports and keeps Meshron safe. Moderators are also verified.")
        GuideRow({ FounderMark(26.dp) }, "CEO · Founder", "Alvin Nana Kojo Pieterson (@alvinpieterson). Shown as a gold pill on their profile.")
        GuideRow({ CofounderMark(26.dp) }, "Co-CEO · Co-founder", "Clement Kofi Yeboah (@clementyeboah). Shown as a silver pill on their profile.")
        Text("On profiles", style = MaterialTheme.typography.titleSmall, color = MeshColors.TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Alvin", fontWeight = FontWeight.Bold); BadgeRow(true, true, "founder", 18.dp, full = true) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Clement", fontWeight = FontWeight.Bold); BadgeRow(true, true, "cofounder", 18.dp, full = true) }
    }
}

@Composable
private fun GuideRow(mark: @Composable () -> Unit, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        mark()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
