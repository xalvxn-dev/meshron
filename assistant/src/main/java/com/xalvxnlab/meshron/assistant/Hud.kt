package com.xalvxnlab.meshron.assistant

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The heads-up-display palette: near-black, cyan light, Meshron's blue and lime as accents. */
object Hud {
    val bg = Color(0xFF04060C)
    val panel = Color(0xFF0A1220)
    val line = Color(0x3322D3C5)
    val text = Color(0xFFE6F7FF)
    val dim = Color(0xFF7D8DA6)
    val cyan = Color(0xFF22D3C5)
    val blue = Color(0xFF6E7BFF)
    val lime = Color(0xFFB6F36A)
    val green = Color(0xFF4ADE80)
    val amber = Color(0xFFFFB020)
    val red = Color(0xFFFF5A6A)
}

enum class Mode { Idle, Listening, Thinking, Speaking }

/**
 * The "arc reactor": a glowing core in rings of light. Idle, it breathes slowly; listening, it
 * swells with your voice; thinking, the rings spin fast and the colours shift; speaking, it pulses.
 */
@Composable
fun Reactor(mode: Mode, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "reactor")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (mode == Mode.Thinking) 1600 else 9000, easing = LinearEasing)), label = "spin")
    val counter by t.animateFloat(360f, 0f, infiniteRepeatable(tween(if (mode == Mode.Thinking) 2400 else 14000, easing = LinearEasing)), label = "counter")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == Mode.Speaking) 420 else 2600), RepeatMode.Reverse), label = "breathe")
    val voice by animateFloatAsState(if (mode == Mode.Listening) level else 0f, tween(90), label = "voice")
    val accent = when (mode) { Mode.Listening -> Hud.cyan; Mode.Thinking -> Hud.blue; Mode.Speaking -> Hud.lime; Mode.Idle -> Hud.cyan }

    Canvas(modifier) {
        val c = center
        val r = min(size.width, size.height) / 2f
        val swell = 1f + voice * 0.18f + (if (mode == Mode.Speaking) breathe * 0.06f else breathe * 0.025f)

        // Glow behind everything.
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.32f), Color.Transparent), c, r * 1.05f * swell), r * 1.05f * swell, c)

        // Outer tick ring (like a dial).
        rotate(counter, c) {
            for (i in 0 until 72) {
                val a = Math.toRadians(i * 5.0)
                val long = i % 6 == 0
                val r1 = r * 0.94f; val r2 = r * (if (long) 0.86f else 0.90f)
                drawLine(accent.copy(alpha = if (long) 0.7f else 0.25f), Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()), Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), strokeWidth = if (long) 2.4f else 1.4f)
            }
        }
        // Broken arcs, spinning.
        val arcR = r * 0.76f * swell
        rotate(spin, c) {
            val st = Stroke(width = r * 0.035f, cap = StrokeCap.Round)
            listOf(0f to Hud.cyan, 95f to Hud.blue, 190f to Hud.cyan, 280f to Hud.lime).forEach { (start, col) ->
                drawArc(col.copy(alpha = 0.9f), start, 62f, false, Offset(c.x - arcR, c.y - arcR), Size(arcR * 2, arcR * 2), style = st)
            }
        }
        // Inner ring, the other way.
        val innerR = r * 0.56f * swell
        rotate(counter * 1.5f, c) {
            drawArc(accent.copy(alpha = 0.55f), 20f, 280f, false, Offset(c.x - innerR, c.y - innerR), Size(innerR * 2, innerR * 2), style = Stroke(width = r * 0.012f, cap = StrokeCap.Round))
            // Segments of the reactor's "coils".
            for (i in 0 until 10) {
                val a = Math.toRadians(i * 36.0)
                val r1 = innerR * 0.72f; val r2 = innerR * 0.92f
                drawLine(accent.copy(alpha = 0.45f), Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()), Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), strokeWidth = r * 0.03f, cap = StrokeCap.Round)
            }
        }
        // Core.
        val coreR = r * (0.26f + breathe * 0.02f + voice * 0.1f)
        drawCircle(Brush.radialGradient(listOf(Color.White, accent.copy(alpha = 0.95f), accent.copy(alpha = 0f)), c, coreR * 1.6f), coreR * 1.6f, c)
        drawCircle(Color(0xFFF2FFFD), coreR * 0.55f, c)
    }
}

/** A faint grid and vignette behind the screens, like a heads-up display. */
@Composable
fun HudGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 36f
        var x = 0f
        while (x < size.width) { drawLine(Color(0x0A22D3C5), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(Color(0x0A22D3C5), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0xCC000000)), center, size.maxDimension * 0.75f))
    }
}

/**
 * Replies without raw Markdown: "**bold**" becomes bold, "*x*" italic, `code` monospaced,
 * "# Heading" a bold line, "- item" / "* item" a "•" bullet, and stray asterisks disappear.
 */
fun pretty(text: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val lines = text.replace("\r", "").split('\n')
    lines.forEachIndexed { n, raw ->
        var l = raw.trimEnd()
        val heading = Regex("""^\s*#{1,4}\s+(.*)$""").find(l)?.groupValues?.get(1)
        if (heading != null) {
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)); append(heading.replace("*", "")); pop()
        } else {
            Regex("""^(\s*)[-*•]\s+(.*)$""").find(l)?.let { l = it.groupValues[1] + "•  " + it.groupValues[2] }
            var i = 0
            val tok = Regex("""\*\*([^*]+)\*\*|__([^_]+)__|`([^`]+)`|\*([^*\s][^*]*)\*""")
            for (m in tok.findAll(l)) {
                append(l.substring(i, m.range.first))
                when {
                    m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty() -> { pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)); append(m.groupValues[1].ifEmpty { m.groupValues[2] }); pop() }
                    m.groupValues[3].isNotEmpty() -> { pushStyle(androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = Color(0x2222D3C5))); append(m.groupValues[3]); pop() }
                    else -> { pushStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)); append(m.groupValues[4]); pop() }
                }
                i = m.range.last + 1
            }
            append(l.substring(i).replace("**", ""))
        }
        if (n < lines.size - 1) append('\n')
    }
}

/** The same, as plain text (for the floating orb's strip and for speaking). */
fun plain(text: String) = pretty(text).text
