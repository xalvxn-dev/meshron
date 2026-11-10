package com.xalvxnlab.meshron.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Meshron design tokens.
 *
 * Neutrals: four cool surface steps on an inky base, so depth comes from tone, not shadows.
 * The brand is a violet → fuchsia → coral gradient. It marks what is yours or what acts:
 * the main button, your own messages, unread counts, the selected tab, online story rings.
 * Green means "online", red means destructive.
 */
object MeshColors {
    // Neutrals
    val Bg = Color(0xFF0A0A0F)
    val Surface1 = Color(0xFF13131A)
    val Surface2 = Color(0xFF1B1B24)
    val Surface3 = Color(0xFF25252F)
    val Hairline = Color(0xFF23232D)
    val TextPrimary = Color(0xFFF4F3F8)
    val TextSecondary = Color(0xFFA3A2B3)
    val TextTertiary = Color(0xFF6B6A7B)

    // Accent
    val Accent = Color(0xFFB79CFF) // text, icons, links
    val AccentStrong = Color(0xFF8B5CF6) // solid fallback for the gradient
    val AccentContainer = Color(0xFF2A1F4D) // tinted chips and tiles
    val BubbleOwn = Color(0xFF7C3AED)

    // Brand gradient
    val GradStart = Color(0xFF8B5CF6) // violet
    val GradMid = Color(0xFFD946EF) // fuchsia
    val GradEnd = Color(0xFFFF7A59) // coral
    val Gradient: Brush = Brush.linearGradient(listOf(GradStart, GradMid, GradEnd))
    val GradientH: Brush = Brush.horizontalGradient(listOf(GradStart, GradMid, GradEnd))
    /** Your message bubbles: violet into fuchsia, stays readable with white text. */
    val BubbleGradient: Brush = Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFB62BD9)))

    // Status
    val Online = Color(0xFF3CCB7F)
    val Offline = Color(0xFF5E5E68)
    val Waiting = Color(0xFFE5B04A)
    val Danger = Color(0xFFF0545C)
    val Accept = Color(0xFF2E9E62)

    // Settings icon tiles (muted, distinct)
    val TileBlue = Color(0xFF3D7BE0)
    val TileTeal = Color(0xFF239C8E)
    val TileIndigo = Color(0xFF5F55D6)
    val TileOrange = Color(0xFFD9822B)
    val TileGreen = Color(0xFF2F9E5B)
    val TileRose = Color(0xFFC94F72)
    val TileGrey = Color(0xFF5B5B66)

    // Older names still used in places
    val Purple = Accent
    val PurpleBright = Accent
    val PurpleDeep = AccentStrong
    val ButtonBrush: Brush = GradientH

    /** Avatar gradients: two neighbouring hues each, picked from the person's key. */
    private val avatars = listOf(
        Color(0xFF6366F1) to Color(0xFFA855F7), Color(0xFF14B8A6) to Color(0xFF3B82F6),
        Color(0xFFEC4899) to Color(0xFFF97316), Color(0xFFF59E0B) to Color(0xFFEF4444),
        Color(0xFF8B5CF6) to Color(0xFFEC4899), Color(0xFF06B6D4) to Color(0xFF6366F1),
        Color(0xFF22C55E) to Color(0xFF0EA5E9), Color(0xFFF43F5E) to Color(0xFFA855F7),
    )

    private fun pair(seed: String) = avatars[(seed.hashCode() and 0x7fffffff) % avatars.size]

    fun avatar(seed: String): Color = pair(seed).first

    fun avatarBrush(seed: String): Brush = pair(seed).let { (a, b) -> Brush.linearGradient(listOf(a, b)) }
}

private val Dark = darkColorScheme(
    primary = MeshColors.Accent,
    onPrimary = Color(0xFF14102E),
    primaryContainer = MeshColors.AccentContainer,
    onPrimaryContainer = Color(0xFFE6E2FF),
    secondary = MeshColors.Accent,
    onSecondary = Color(0xFF14102E),
    secondaryContainer = MeshColors.Surface3,
    onSecondaryContainer = MeshColors.TextPrimary,
    tertiary = MeshColors.Waiting,
    background = MeshColors.Bg,
    onBackground = MeshColors.TextPrimary,
    surface = MeshColors.Bg,
    onSurface = MeshColors.TextPrimary,
    surfaceVariant = MeshColors.Surface2,
    onSurfaceVariant = MeshColors.TextSecondary,
    surfaceContainerLowest = Color(0xFF0B0B0D),
    surfaceContainerLow = MeshColors.Surface1,
    surfaceContainer = MeshColors.Surface1,
    surfaceContainerHigh = MeshColors.Surface2,
    surfaceContainerHighest = MeshColors.Surface3,
    outline = Color(0xFF3A3A43),
    outlineVariant = MeshColors.Hairline,
    error = MeshColors.Danger,
)

private val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** One type scale, used everywhere. Sizes in sp; line heights keep rows evenly spaced. */
private val Type = Typography().let { t ->
    fun TextStyle.with(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) =
        copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)
    t.copy(
        displaySmall = t.displaySmall.with(34, 40, FontWeight.ExtraBold, -0.8),
        headlineLarge = t.headlineLarge.with(30, 36, FontWeight.ExtraBold, -0.6),
        headlineMedium = t.headlineMedium.with(26, 32, FontWeight.Bold, -0.4),
        headlineSmall = t.headlineSmall.with(22, 28, FontWeight.SemiBold, -0.2),
        titleLarge = t.titleLarge.with(20, 26, FontWeight.SemiBold, -0.1),
        titleMedium = t.titleMedium.with(16, 22, FontWeight.SemiBold),
        titleSmall = t.titleSmall.with(15, 20, FontWeight.Medium),
        bodyLarge = t.bodyLarge.with(16, 22, FontWeight.Normal),
        bodyMedium = t.bodyMedium.with(14, 20, FontWeight.Normal),
        bodySmall = t.bodySmall.with(13, 18, FontWeight.Normal),
        labelLarge = t.labelLarge.with(14, 18, FontWeight.Medium, 0.1),
        labelMedium = t.labelMedium.with(12, 16, FontWeight.Medium, 0.2),
        labelSmall = t.labelSmall.with(11, 14, FontWeight.Medium, 0.2),
    )
}

/** Dark-first: the app always uses the dark scheme. */
@Composable
fun MeshronTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, shapes = Shapes, typography = Type, content = content)
}
