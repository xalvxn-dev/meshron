package com.xalvxnlab.meshron.assistant

import android.content.Context
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Skins for the orb (the same set as on Windows): the classic Reactor, Nebula, Ember, Aurora, Mono
 * and Gold, and faces (Buddy, Cosmo, Pixel, Kitty) that blink, look around, talk and show feelings.
 * Face skins sit at the top of the screen like an island instead of on a side.
 */
data class Skin(val id: String, val name: String, val face: Boolean, val c1: Int, val c2: Int, val c3: Int)

object Look {
    val SKINS = listOf(
        Skin("reactor", "Reactor", false, 0xFF22D3C5.toInt(), 0xFF6E7BFF.toInt(), 0xFFB6F36A.toInt()),
        Skin("nebula", "Nebula", false, 0xFFB57BFF.toInt(), 0xFFFF6BD6.toInt(), 0xFF6E7BFF.toInt()),
        Skin("ember", "Ember", false, 0xFFFF8A3D.toInt(), 0xFFFFB020.toInt(), 0xFFFF4D5E.toInt()),
        Skin("aurora", "Aurora", false, 0xFF3DFFB0.toInt(), 0xFF22D3C5.toInt(), 0xFF8E7BFF.toInt()),
        Skin("mono", "Mono", false, 0xFFF2F5FA.toInt(), 0xFF9BA3B4.toInt(), 0xFFF2F5FA.toInt()),
        Skin("gold", "Gold", false, 0xFFFFD86B.toInt(), 0xFFFFB020.toInt(), 0xFFFFF1C4.toInt()),
        Skin("buddy", "Buddy", true, 0xFF22D3C5.toInt(), 0xFF6E7BFF.toInt(), 0xFFB6F36A.toInt()),
        Skin("cosmo", "Cosmo", true, 0xFF8E7BFF.toInt(), 0xFF22D3C5.toInt(), 0xFFFF6BD6.toInt()),
        Skin("pixel", "Pixel", true, 0xFF3DFF8A.toInt(), 0xFF22D3C5.toInt(), 0xFFB6F36A.toInt()),
        Skin("kitty", "Kitty", true, 0xFFFF9BD2.toInt(), 0xFFB57BFF.toInt(), 0xFFFFD1E8.toInt())
    )
    fun of(id: String) = SKINS.firstOrNull { it.id == id } ?: SKINS[0]

    /** The chosen skin (every orb in the app follows it). */
    var skin by mutableStateOf("reactor")
    /** A moment's expression on face skins: happy, sad, surprised, love. */
    var mood by mutableStateOf<String?>(null)
    var moodUntil by mutableLongStateOf(0L)
    fun feel(m: String, ms: Long = 2200) { mood = m; moodUntil = System.currentTimeMillis() + ms }
    fun load(c: Context) { skin = c.getSharedPreferences("memory", Context.MODE_PRIVATE).getString("skin", "reactor").orEmpty().ifBlank { "reactor" } }
    fun save(c: Context, id: String) { skin = id; c.getSharedPreferences("memory", Context.MODE_PRIVATE).edit().putString("skin", id).apply() }
    fun isFace(c: Context) = of(c.getSharedPreferences("memory", Context.MODE_PRIVATE).getString("skin", "reactor").orEmpty()).face
}

/** Per-orb animation memory (eyes, blinks, stars, sparks). */
private class FaceState {
    var lx = 0f; var ly = 0f; var tx = 0f; var ty = 0f; var nextLook = 0f; var blink = 0f; var nextBlink = 1.5f; var mouth = 0f; var talk = 0f; var shown = 0f
    val stars = List(60) { floatArrayOf(Random.nextFloat() * 6.283f, Random.nextFloat(), Random.nextFloat() * 1.4f + 0.3f, Random.nextFloat()) }
    val sparks = List(22) { floatArrayOf(Random.nextFloat() * 6.283f, 0.2f + Random.nextFloat() * 0.6f, Random.nextFloat()) }
}

/** The orb in a skin. [skinId] null: the chosen skin. */
@Composable
fun SkinOrb(mode: Mode, level: Float, modifier: Modifier = Modifier, skinId: String? = null, mood: String? = null) {
    val st = remember { FaceState() }
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { val start = withFrameNanos { it }; while (true) withFrameNanos { t = (it - start) / 1e9f } }
    val skin = Look.of(skinId ?: Look.skin)
    val m = mood ?: Look.mood?.takeIf { System.currentTimeMillis() < Look.moodUntil }
    Canvas(modifier) {
        val time = t
        drawIntoCanvas { cv ->
            val c = cv.nativeCanvas
            st.shown += ((if (mode == Mode.Listening) level else 0f) - st.shown) * 0.25f
            val d = Draw(c, size.width / 2, size.height / 2, min(size.width, size.height) / 2, time, mode, st, skin, m)
            when (skin.id) {
                "nebula" -> d.nebula(); "ember" -> d.ember(); "aurora" -> d.aurora()
                "mono" -> d.rings(ticks = true, coils = false, thin = true); "gold" -> { d.rings(ticks = true, coils = true, thin = false); d.hexagon() }
                "buddy", "cosmo", "pixel", "kitty" -> d.face(skin.id)
                else -> d.rings(ticks = true, coils = true, thin = false)
            }
        }
    }
}

private fun a(color: Int, alpha: Float): Int = (color and 0xFFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
private const val TAU = (PI * 2).toFloat()

private class Draw(val c: android.graphics.Canvas, val cx: Float, val cy: Float, val R: Float, val t: Float, val mode: Mode, val st: FaceState, val skin: Skin, val mood: String?) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val breathe = ((sin(t * (if (mode == Mode.Speaking) 14f else 2.4f)) + 1) / 2)
    val shown get() = st.shown

    fun glow(x: Float, y: Float, r: Float, vararg stops: Pair<Float, Int>) {
        if (r <= 0f) return
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, r, stops.map { it.second }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p); p.shader = null
    }
    fun stroke(color: Int, w: Float) { p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = w; p.strokeCap = Paint.Cap.ROUND; p.shader = null }
    fun fill(color: Int) { p.style = Paint.Style.FILL; p.color = color; p.shader = null }
    fun arc(x: Float, y: Float, r: Float, from: Float, sweep: Float) { c.drawArc(RectF(x - r, y - r, x + r, y + r), Math.toDegrees(from.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat(), false, p) }

    fun rings(ticks: Boolean, coils: Boolean, thin: Boolean) {
        val fast = mode == Mode.Thinking
        val accent = when (mode) { Mode.Thinking -> skin.c2; Mode.Speaking -> skin.c3; else -> skin.c1 }
        val swell = 1 + shown * 0.18f + breathe * (if (mode == Mode.Speaking) 0.06f else 0.025f)
        glow(cx, cy, R * 1.02f * swell, 0f to a(accent, 0.34f), 1f to a(accent, 0f))
        if (ticks) {
            val spinA = -t * (if (fast) 150f else 26f) * PI.toFloat() / 180
            for (i in 0 until 72) {
                val an = spinA + i * 5 * PI.toFloat() / 180; val long = i % 6 == 0
                val r1 = R * 0.94f; val r2 = R * (if (long) 0.86f else 0.9f)
                stroke(a(accent, if (long) 0.7f else 0.25f), if (long) R * 0.025f else R * 0.015f)
                c.drawLine(cx + r1 * cos(an), cy + r1 * sin(an), cx + r2 * cos(an), cy + r2 * sin(an), p)
            }
        }
        val spin = t * (if (fast) 225f else 40f) * PI.toFloat() / 180
        val arcR = R * 0.76f * swell
        listOf(0f to skin.c1, 95f to skin.c2, 190f to skin.c1, 280f to skin.c3).forEach { (s, col) ->
            stroke(a(col, 0.9f), max(1.5f, R * (if (thin) 0.02f else 0.035f)))
            arc(cx, cy, arcR, spin + s * PI.toFloat() / 180, (if (thin) 80f else 62f) * PI.toFloat() / 180)
        }
        val inR = R * 0.56f * swell; val counter = -t * (if (fast) 230f else 39f) * PI.toFloat() / 180
        stroke(a(accent, 0.55f), max(1f, R * 0.012f)); arc(cx, cy, inR, counter + 0.35f, 280 * PI.toFloat() / 180)
        if (coils) {
            stroke(a(accent, 0.45f), max(1.2f, R * 0.03f))
            for (i in 0 until 10) { val an = counter + i * 36 * PI.toFloat() / 180; c.drawLine(cx + inR * 0.72f * cos(an), cy + inR * 0.72f * sin(an), cx + inR * 0.92f * cos(an), cy + inR * 0.92f * sin(an), p) }
        }
        val coreR = R * (0.26f + breathe * 0.02f + shown * 0.1f)
        glow(cx, cy, coreR * 1.6f, 0f to 0xFFFFFFFF.toInt(), 0.45f to a(accent, 0.95f), 1f to a(accent, 0f))
        fill(0xFFFFFFFF.toInt()); c.drawCircle(cx, cy, coreR * 0.55f, p)
    }

    fun hexagon() {
        stroke(a(0xFFFFD86B.toInt(), 0.5f), max(1f, R * 0.015f))
        val path = android.graphics.Path()
        for (i in 0..6) { val an = t * 0.3f + i * TAU / 6; val x = cx + R * 0.98f * cos(an); val y = cy + R * 0.98f * sin(an); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        c.drawPath(path, p)
    }

    fun nebula() {
        val speed = when (mode) { Mode.Thinking -> 2.4f; Mode.Speaking -> 1.2f; else -> 0.4f }
        val swell = 1 + shown * 0.22f + breathe * (if (mode == Mode.Speaking) 0.08f else 0.03f)
        glow(cx, cy, R * swell, 0f to a(skin.c2, 0.45f), 0.5f to a(skin.c1, 0.22f), 1f to a(skin.c3, 0f))
        for (s in st.stars) {
            val an = s[0] + t * speed * (0.4f + (1 - s[1])); val d = R * (0.2f + s[1] * 0.75f) * swell
            fill(a(if (s[3] > 0.66f) skin.c1 else if (s[3] > 0.33f) skin.c2 else 0xFFFFFFFF.toInt(), 0.4f + 0.6f * s[3]))
            c.drawCircle(cx + cos(an) * d, cy + sin(an) * d * 0.82f, s[2] * (R / 40), p)
        }
        for (arm in 0..1) {
            stroke(a(if (arm == 1) skin.c1 else skin.c2, 0.55f), max(1f, R * 0.03f))
            val path = android.graphics.Path()
            for (i in 0 until 40) { val k = i / 40f; val an = t * speed + arm * PI.toFloat() + k * 4; val d = R * (0.1f + k * 0.7f) * swell; val x = cx + cos(an) * d; val y = cy + sin(an) * d * 0.82f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            c.drawPath(path, p)
        }
        glow(cx, cy, R * 0.32f * swell, 0f to 0xFFFFFFFF.toInt(), 0.4f to a(skin.c2, 0.9f), 1f to a(skin.c2, 0f))
    }

    fun ember() {
        val heat = when (mode) { Mode.Thinking -> 1.8f; Mode.Speaking -> 1.4f; else -> 0.8f }
        val swell = 1 + shown * 0.2f + breathe * (if (mode == Mode.Speaking) 0.07f else 0.03f)
        glow(cx, cy, R * swell, 0f to a(skin.c2, 0.55f), 0.55f to a(skin.c1, 0.22f), 1f to a(skin.c3, 0f))
        for (i in 0 until 9) {
            val an = i * TAU / 9 + sin(t * 2 + i) * 0.12f + t * 0.3f
            val len = R * (0.48f + 0.12f * sin(t * 6 * heat + i * 1.7f)) * swell
            fill(a(if (i % 2 == 1) skin.c1 else skin.c3, 0.55f))
            val path = android.graphics.Path()
            path.moveTo(cx + cos(an - 0.35f) * R * 0.2f, cy + sin(an - 0.35f) * R * 0.2f)
            path.quadTo(cx + cos(an) * len * 1.2f, cy + sin(an) * len * 1.2f, cx + cos(an + 0.35f) * R * 0.2f, cy + sin(an + 0.35f) * R * 0.2f)
            c.drawPath(path, p)
        }
        for (s in st.sparks) {
            s[2] += (1 / 60f) * s[1] * heat; if (s[2] > 1) { s[2] = 0f; s[0] = Random.nextFloat() * TAU }
            val d = R * (0.25f + s[2] * 0.7f)
            fill(a(skin.c2, 1 - s[2])); c.drawCircle(cx + cos(s[0]) * d, cy + sin(s[0]) * d - s[2] * R * 0.2f, max(0.6f, R * 0.025f * (1 - s[2])), p)
        }
        glow(cx, cy, R * 0.34f * swell, 0f to 0xFFFFF6E0.toInt(), 0.5f to a(skin.c2, 0.95f), 1f to a(skin.c1, 0f))
    }

    fun aurora() {
        val speed = when (mode) { Mode.Thinking -> 3f; Mode.Speaking -> 1.8f; else -> 0.7f }
        val swell = 1 + shown * 0.2f + breathe * (if (mode == Mode.Speaking) 0.07f else 0.03f)
        glow(cx, cy, R * swell, 0f to a(skin.c2, 0.3f), 1f to a(skin.c2, 0f))
        listOf(skin.c1, skin.c2, skin.c3).forEachIndexed { k, col ->
            stroke(a(col, 0.7f), max(1.5f, R * 0.05f))
            val path = android.graphics.Path()
            for (i in 0..60) {
                val an = i / 60f * TAU; val wob = sin(an * 3 + t * speed + k * 2) * 0.09f + sin(an * 5 - t * speed * 0.7f) * 0.04f
                val d = R * (0.5f + k * 0.09f + wob) * swell
                if (i == 0) path.moveTo(cx + cos(an) * d, cy + sin(an) * d) else path.lineTo(cx + cos(an) * d, cy + sin(an) * d)
            }
            c.drawPath(path, p)
        }
        glow(cx, cy, R * 0.36f * swell, 0f to 0xFFFFFFFF.toInt(), 0.45f to a(skin.c1, 0.9f), 1f to a(skin.c1, 0f))
    }

    // ---- Faces ----
    fun face(kind: String) {
        val f = st
        val ex = mood ?: when (mode) { Mode.Listening -> "listening"; Mode.Thinking -> "thinking"; Mode.Speaking -> "talking"; else -> "calm" }
        val bob = sin(t * 2) * R * 0.02f + (if (kind == "kitty") R * 0.1f else 0f)
        val swell = 1 + shown * 0.12f + (if (mode == Mode.Speaking) breathe * 0.03f else 0f)
        val fr = R * (if (kind == "kitty") 0.66f else 0.8f) * swell
        if (t > f.nextLook) { f.tx = (Random.nextFloat() - 0.5f) * 0.5f; f.ty = (Random.nextFloat() - 0.5f) * 0.3f; f.nextLook = t + 1.2f + Random.nextFloat() * 2.5f }
        var tx = f.tx; var ty = f.ty
        if (ex == "thinking") { tx = 0.35f * cos(t * 1.5f); ty = -0.35f }
        if (ex == "listening" || ex == "surprised") { tx = 0f; ty = 0f }
        f.lx += (tx - f.lx) * 0.12f; f.ly += (ty - f.ly) * 0.12f
        if (t > f.nextBlink) { f.blink = 1f; f.nextBlink = t + 2 + Random.nextFloat() * 3.5f }
        f.blink = max(0f, f.blink - 0.16f)
        val lid = if (ex == "surprised") 0f else if (f.blink > 0) sin(f.blink * PI.toFloat()) else 0f
        if (ex == "talking") { f.talk += 0.35f + Random.nextFloat() * 0.25f; f.mouth = 0.25f + 0.75f * abs(sin(f.talk) * sin(f.talk * 0.37f + 1)) } else f.mouth += ((if (ex == "listening") shown * 0.6f else 0f) - f.mouth) * 0.3f

        c.save(); c.translate(cx, cy + bob)
        glow(0f, 0f, R * 1.05f, 0f to a(skin.c1, 0.35f), 1f to a(skin.c1, 0f))
        if (kind == "kitty") {
            val perk = if (ex == "listening" || ex == "surprised") 0.12f else 0f
            for (s in listOf(-1f, 1f)) {
                val ear = android.graphics.Path().apply { moveTo(s * fr * 0.85f, -fr * 0.35f); lineTo(s * fr * (0.7f - perk * 0.4f), -fr * (1.15f + perk)); lineTo(s * fr * 0.25f, -fr * 0.78f); close() }
                fill(0xFF1A1220.toInt()); c.drawPath(ear, p); stroke(a(skin.c1, 0.9f), max(1f, R * 0.04f)); c.drawPath(ear, p)
                fill(a(skin.c1, 0.6f)); c.drawPath(android.graphics.Path().apply { moveTo(s * fr * 0.72f, -fr * 0.5f); lineTo(s * fr * (0.66f - perk * 0.4f), -fr * (0.98f + perk)); lineTo(s * fr * 0.38f, -fr * 0.74f); close() }, p)
            }
        }
        if (kind == "pixel") {
            val s = fr * 1.7f
            fill(0xFF050A08.toInt()); c.drawRoundRect(RectF(-s / 2, -s / 2.3f, s / 2, -s / 2.3f + s / 1.15f), s * 0.22f, s * 0.22f, p)
            stroke(a(skin.c1, 0.55f), max(1f, R * 0.04f)); c.drawRoundRect(RectF(-s / 2, -s / 2.3f, s / 2, -s / 2.3f + s / 1.15f), s * 0.22f, s * 0.22f, p)
        } else {
            val (h1, h2) = when (kind) { "cosmo" -> 0xFF2A1F5C.toInt() to 0xFF07061A.toInt(); "kitty" -> 0xFF3A2440.toInt() to 0xFF140C18.toInt(); else -> 0xFF16324A.toInt() to 0xFF060B14.toInt() }
            p.style = Paint.Style.FILL; p.shader = RadialGradient(-fr * 0.3f, -fr * 0.35f, fr * 1.2f, h1, h2, Shader.TileMode.CLAMP); c.drawCircle(0f, 0f, fr, p); p.shader = null
            stroke(a(skin.c1, 0.6f), max(1f, R * 0.035f)); c.drawCircle(0f, 0f, fr, p)
        }
        if (kind == "cosmo") {
            c.save(); c.clipPath(android.graphics.Path().apply { addCircle(0f, 0f, fr * 0.96f, android.graphics.Path.Direction.CW) })
            val v = when (mode) { Mode.Speaking -> 0.9f + f.mouth; Mode.Thinking -> 1.6f; else -> 0.25f }
            for (s in f.stars) { s[0] += 0.004f * v * (0.5f + s[3]); val d = fr * s[1]; fill(a(if (s[3] > 0.7f) skin.c3 else if (s[3] > 0.4f) skin.c2 else 0xFFFFFFFF.toInt(), 0.35f + 0.65f * s[3])); c.drawCircle(cos(s[0]) * d, sin(s[0]) * d, s[2] * (R / 50), p) }
            val px = cos(t * 0.25f) * fr * 0.55f; val py = fr * 0.45f
            fill(a(skin.c2, 0.5f)); c.drawCircle(px, py, fr * 0.12f, p)
            stroke(a(skin.c3, 0.5f), max(1f, R * 0.02f)); c.save(); c.rotate(-17f, px, py); c.drawOval(RectF(px - fr * 0.22f, py - fr * 0.06f, px + fr * 0.22f, py + fr * 0.06f), p); c.restore()
            c.restore()
        }
        // Eyes.
        val eyeY = -fr * 0.12f; val gap = fr * 0.38f
        val ew = fr * (if (kind == "pixel") 0.26f else 0.2f)
        var eh = fr * (if (ex == "surprised") 0.36f else if (ex == "listening") 0.32f else 0.28f)
        eh *= 1 - lid * 0.92f
        val eyeCol = if (kind == "pixel") skin.c1 else 0xFFF4FBFF.toInt()
        for (s in listOf(-1f, 1f)) {
            val x = s * gap + f.lx * fr * 0.18f; val y = eyeY + f.ly * fr * 0.18f
            p.setShadowLayer(R * 0.2f, 0f, 0f, skin.c1)
            when {
                ex == "love" -> heart(x, y, ew * 1.1f, skin.c3)
                ex == "happy" -> { stroke(eyeCol, max(1.5f, ew * 0.45f)); arc(x, y + ew * 0.35f, ew * 0.8f, PI.toFloat() * 1.15f, PI.toFloat() * 0.7f) }
                kind == "pixel" -> { fill(eyeCol); val px = ew / 3; val rows = max(1, (eh / px).roundToInt()); for (i in 0 until 3) for (j in 0 until rows) c.drawRect(x - ew / 2 + i * px + 0.5f, y - eh / 2 + j * px + 0.5f, x - ew / 2 + (i + 1) * px - 0.5f, y - eh / 2 + (j + 1) * px - 0.5f, p) }
                else -> {
                    fill(eyeCol); c.drawRoundRect(RectF(x - ew / 2, y - eh / 2, x + ew / 2, y + max(1.5f, eh) / 2), ew / 2, ew / 2, p)
                    p.clearShadowLayer()
                    if (ex == "sad") { fill(if (kind == "cosmo") 0xFF07061A.toInt() else 0xFF060B14.toInt()); c.drawPath(android.graphics.Path().apply { moveTo(x - ew, y - eh / 2 - 1); lineTo(x + ew, y - eh / 2 - 1); lineTo(x + s * ew, y - eh * 0.05f); close() }, p) }
                    if (eh > ew * 0.6f) { fill(0xE6FFFFFF.toInt()); c.drawCircle(x - ew * 0.18f, y - eh * 0.22f, ew * 0.14f, p) }
                }
            }
            p.clearShadowLayer()
        }
        if (ex == "happy" || ex == "love" || kind == "kitty") {
            fill(a(0xFFFF6B9A.toInt(), if (ex == "happy" || ex == "love") 0.4f else 0.22f))
            for (s in listOf(-1f, 1f)) c.drawOval(RectF(s * gap * 1.35f - fr * 0.13f, eyeY + fr * 0.22f, s * gap * 1.35f + fr * 0.13f, eyeY + fr * 0.38f), p)
        }
        // Mouth.
        val my = fr * 0.38f; val mw = fr * (if (kind == "pixel") 0.42f else 0.34f)
        val ink = if (kind == "pixel") skin.c1 else 0xFFF4FBFF.toInt()
        when {
            kind == "pixel" -> {
                fill(ink); val px = mw / 6; val open = if (ex == "talking" || ex == "listening") f.mouth else 0f
                for (i in 0 until 6) {
                    val curve = when (ex) { "sad" -> -abs(i - 2.5f) * 0.45f; "happy", "calm", "love" -> abs(i - 2.5f) * 0.45f; else -> 0f }
                    val hgt = max(1, (1 + open * 3 * (1 - abs(i - 2.5f) / 3)).roundToInt())
                    for (j in 0 until hgt) { val x0 = -mw / 2 + i * px; val y0 = my - curve * px + j * px - px; c.drawRect(x0 + 0.5f, y0 + 0.5f, x0 + px - 0.5f, y0 + px - 0.5f, p) }
                }
            }
            ex == "talking" || (ex == "listening" && f.mouth > 0.05f) -> {
                val oh = fr * 0.05f + f.mouth * fr * 0.24f
                val rect = RectF(-mw * 0.45f, my - oh / 2, mw * 0.45f, my + oh / 2)
                fill(0xFF1A0E14.toInt()); c.drawRoundRect(rect, min(oh, mw) / 2, min(oh, mw) / 2, p)
                stroke(ink, max(1f, fr * 0.04f)); c.drawRoundRect(rect, min(oh, mw) / 2, min(oh, mw) / 2, p)
                if (f.mouth > 0.45f) { fill(0xFFFF7A9A.toInt()); c.drawOval(RectF(-mw * 0.22f, my + oh * 0.07f, mw * 0.22f, my + oh * 0.43f), p) }
            }
            ex == "surprised" -> { stroke(ink, max(1.5f, fr * 0.07f)); c.drawOval(RectF(-fr * 0.08f, my - fr * 0.11f, fr * 0.08f, my + fr * 0.11f), p) }
            ex == "thinking" -> { stroke(ink, max(1.5f, fr * 0.07f)); c.drawLine(-mw * 0.3f, my + fr * 0.02f, mw * 0.35f, my - fr * 0.03f, p) }
            ex == "sad" -> { stroke(ink, max(1.5f, fr * 0.07f)); arc(0f, my + mw * 0.55f, mw * 0.5f, PI.toFloat() * 1.2f, PI.toFloat() * 0.6f) }
            else -> {
                val k = if (ex == "happy" || ex == "love") 1.25f else 1f
                if (kind == "kitty") { stroke(ink, max(1.2f, fr * 0.05f)); arc(-mw * 0.2f, my - fr * 0.02f, mw * 0.2f * k, 0.1f, PI.toFloat() - 0.2f); arc(mw * 0.2f, my - fr * 0.02f, mw * 0.2f * k, 0.1f, PI.toFloat() - 0.2f) }
                else { stroke(ink, max(1.5f, fr * 0.07f)); arc(0f, my - mw * 0.45f, mw * 0.6f * k, PI.toFloat() * 0.25f, PI.toFloat() * 0.5f) }
            }
        }
        if (kind == "kitty") {
            stroke(a(0xFFFFD1E8.toInt(), 0.55f), max(1f, fr * 0.02f))
            for (s in listOf(-1f, 1f)) for (d in listOf(-0.06f, 0.06f)) c.drawLine(s * fr * 0.48f, my - fr * 0.08f + d * fr, s * fr * 0.95f, my - fr * 0.14f + d * fr * 2.2f, p)
        }
        if (ex == "thinking") for (i in 0 until 3) { val an = t * 3 + i * 0.6f; fill(a(skin.c2, 0.5f + 0.5f * sin(an * 2))); c.drawCircle(fr * (0.55f + i * 0.22f), -fr * (0.95f + 0.06f * sin(an)), fr * 0.06f, p) }
        c.restore()
    }

    fun heart(x: Float, y: Float, s: Float, col: Int) {
        fill(col)
        c.drawPath(android.graphics.Path().apply {
            moveTo(x, y + s * 0.5f)
            cubicTo(x - s, y - s * 0.1f, x - s * 0.5f, y - s * 0.8f, x, y - s * 0.25f)
            cubicTo(x + s * 0.5f, y - s * 0.8f, x + s, y - s * 0.1f, x, y + s * 0.5f)
        }, p)
    }
}
