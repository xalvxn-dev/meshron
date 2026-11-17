package com.xalvxnlab.meshron.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Meshron "Aurora" design tokens.
 *
 * Three looks: Dark (soft ink), Light (soft paper) and Stealth (anonymous: true black, quiet
 * teal). The brand is an iris → teal → lime aurora. It marks what is yours or what acts: the
 * main button, your messages, unread counts, the selected tab, streaks, story rings.
 * Colours are read from [MeshColors] at draw time, so switching theme is instant.
 */
class Palette(
    val dark: Boolean,
    val bg: Long, val s1: Long, val s2: Long, val s3: Long, val hair: Long,
    val text: Long, val text2: Long, val text3: Long,
    val accent: Long, val accentStrong: Long, val accentContainer: Long, val onAccentContainer: Long,
    val g1: Long, val g2: Long, val g3: Long, val bubble1: Long, val bubble2: Long,
    val bubbleOther: Long,
)

private val DarkP = Palette(
    true, 0xFF0E1015, 0xFF161922, 0xFF1E222D, 0xFF282D3A, 0xFF22262F,
    0xFFEEF1F7, 0xFF9BA3B4, 0xFF636B7D,
    0xFF8E9BFF, 0xFF6E7BFF, 0xFF1F2547, 0xFFDDE1FF,
    0xFF6E7BFF, 0xFF22D3C5, 0xFFB6F36A, 0xFF5C6CFF, 0xFF1FB8B0,
    0xFF1E222D,
)
private val LightP = Palette(
    false, 0xFFF5F6F9, 0xFFFFFFFF, 0xFFEEF0F5, 0xFFE3E6EE, 0xFFE6E8EE,
    0xFF11141B, 0xFF5A6274, 0xFF8B92A3,
    0xFF4F5BFF, 0xFF4F5BFF, 0xFFE4E7FF, 0xFF1B2266,
    0xFF5B67FF, 0xFF14B8AA, 0xFF8BD13A, 0xFF5263FF, 0xFF14A8A0,
    0xFFFFFFFF,
)
private val StealthP = Palette(
    true, 0xFF000000, 0xFF0C0E0F, 0xFF15191A, 0xFF1E2426, 0xFF1A2022,
    0xFFE8EFEE, 0xFF93A3A1, 0xFF5A6967,
    0xFF5EEAD4, 0xFF14B8A6, 0xFF0F2A27, 0xFFCFF7F1,
    0xFF0F766E, 0xFF14B8A6, 0xFF5EEAD4, 0xFF115E59, 0xFF0F766E,
    0xFF15191A,
)

object MeshColors {
    internal var palette by mutableStateOf(if (com.xalvxnlab.meshron.identity.Mode.anon) StealthP else DarkP)
    private fun c(v: Long) = Color(v)

    val isDark get() = palette.dark
    val stealth get() = com.xalvxnlab.meshron.identity.Mode.anon

    // Neutrals
    val Bg get() = c(palette.bg)
    val Surface1 get() = c(palette.s1)
    val Surface2 get() = c(palette.s2)
    val Surface3 get() = c(palette.s3)
    val Hairline get() = c(palette.hair)
    val TextPrimary get() = c(palette.text)
    val TextSecondary get() = c(palette.text2)
    val TextTertiary get() = c(palette.text3)

    // Accent
    val Accent get() = c(palette.accent)
    val AccentStrong get() = c(palette.accentStrong)
    val AccentContainer get() = c(palette.accentContainer)
    val OnAccentContainer get() = c(palette.onAccentContainer)
    val BubbleOwn get() = c(palette.bubble1)
    val BubbleOther get() = c(palette.bubbleOther)

    // Brand aurora
    val GradStart get() = c(palette.g1)
    val GradMid get() = c(palette.g2)
    val GradEnd get() = c(palette.g3)
    val Gradient: Brush get() = Brush.linearGradient(listOf(GradStart, GradMid, GradEnd))
    val GradientH: Brush get() = Brush.horizontalGradient(listOf(GradStart, GradMid, GradEnd))
    val BubbleGradient: Brush get() = Brush.linearGradient(listOf(c(palette.bubble1), c(palette.bubble2)))

    // Status
    val Online = Color(0xFF2FD07F)
    val Offline = Color(0xFF6B7280)
    val Waiting = Color(0xFFF2B13D)
    val Danger = Color(0xFFF0545C)
    val Accept = Color(0xFF22A863)
    val Streak = Color(0xFFFF8A2B)

    // Settings icon tiles
    val TileBlue = Color(0xFF3D7BE0)
    val TileTeal = Color(0xFF16A394)
    val TileIndigo = Color(0xFF5B67FF)
    val TileOrange = Color(0xFFE5862F)
    val TileGreen = Color(0xFF27A35E)
    val TileRose = Color(0xFFD94F78)
    val TileGrey = Color(0xFF6B7280)

    // Older names still used in places
    val Purple get() = Accent
    val PurpleBright get() = Accent
    val PurpleDeep get() = AccentStrong
    val ButtonBrush: Brush get() = GradientH

    /** Avatar gradients: two neighbouring hues each, picked from the person's key. */
    private val avatars = listOf(
        Color(0xFF5B67FF) to Color(0xFF22D3C5), Color(0xFF14B8A6) to Color(0xFF84CC16),
        Color(0xFFF97316) to Color(0xFFF43F5E), Color(0xFFF59E0B) to Color(0xFFEF4444),
        Color(0xFF8B5CF6) to Color(0xFF5B67FF), Color(0xFF06B6D4) to Color(0xFF3B82F6),
        Color(0xFF22C55E) to Color(0xFF0EA5E9), Color(0xFFEC4899) to Color(0xFF8B5CF6),
    )

    private fun pair(seed: String) = avatars[(seed.hashCode() and 0x7fffffff) % avatars.size]
    fun avatar(seed: String): Color = pair(seed).first
    fun avatarBrush(seed: String): Brush = pair(seed).let { (a, b) -> Brush.linearGradient(listOf(a, b)) }
}

private fun scheme(p: Palette): ColorScheme {
    val c = { v: Long -> Color(v) }
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(p.accent), onPrimary = if (p.dark) Color(0xFF0E1015) else Color.White,
        primaryContainer = c(p.accentContainer), onPrimaryContainer = c(p.onAccentContainer),
        secondary = c(p.accent), onSecondary = if (p.dark) Color(0xFF0E1015) else Color.White,
        secondaryContainer = c(p.s3), onSecondaryContainer = c(p.text),
        tertiary = Color(0xFFF2B13D),
        background = c(p.bg), onBackground = c(p.text),
        surface = c(p.bg), onSurface = c(p.text),
        surfaceVariant = c(p.s2), onSurfaceVariant = c(p.text2),
        surfaceContainerLowest = c(p.bg), surfaceContainerLow = c(p.s1), surfaceContainer = c(p.s1),
        surfaceContainerHigh = c(p.s2), surfaceContainerHighest = c(p.s3),
        outline = c(p.text3), outlineVariant = c(p.hair),
        error = Color(0xFFF0545C),
    )
}

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** One type scale, used everywhere. */
private val Type = Typography().let { t ->
    fun TextStyle.with(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) =
        copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)
    t.copy(
        displaySmall = t.displaySmall.with(34, 40, FontWeight.Black, -1.0),
        headlineLarge = t.headlineLarge.with(30, 36, FontWeight.ExtraBold, -0.8),
        headlineMedium = t.headlineMedium.with(26, 32, FontWeight.ExtraBold, -0.5),
        headlineSmall = t.headlineSmall.with(22, 28, FontWeight.Bold, -0.3),
        titleLarge = t.titleLarge.with(20, 26, FontWeight.Bold, -0.2),
        titleMedium = t.titleMedium.with(16, 22, FontWeight.SemiBold),
        titleSmall = t.titleSmall.with(15, 20, FontWeight.Medium),
        bodyLarge = t.bodyLarge.with(16, 23, FontWeight.Normal),
        bodyMedium = t.bodyMedium.with(14, 20, FontWeight.Normal),
        bodySmall = t.bodySmall.with(13, 18, FontWeight.Normal),
        labelLarge = t.labelLarge.with(14, 18, FontWeight.SemiBold, 0.1),
        labelMedium = t.labelMedium.with(12, 16, FontWeight.Medium, 0.2),
        labelSmall = t.labelSmall.with(11, 14, FontWeight.SemiBold, 0.3),
    )
}

/** "system", "light" or "dark". Anonymous mode is always Stealth. */
@Composable
fun MeshronTheme(mode: String = "system", content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val p = when {
        com.xalvxnlab.meshron.identity.Mode.anon -> StealthP
        mode == "light" -> LightP
        mode == "dark" -> DarkP
        else -> if (systemDark) DarkP else LightP
    }
    SideEffect { if (MeshColors.palette !== p) MeshColors.palette = p }
    // Fit the screen: everything scales a little with the phone's width (small phones get a
    // touch smaller, big phones and tablets a touch bigger), around a 400 dp design width.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val d = androidx.compose.ui.platform.LocalDensity.current
    val k = (minOf(config.screenWidthDp, config.screenHeightDp) / 400f).coerceIn(0.85f, 1.15f)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * k, d.fontScale)) {
        MaterialTheme(colorScheme = scheme(p), shapes = Shapes, typography = Type, content = content)
    }
}
