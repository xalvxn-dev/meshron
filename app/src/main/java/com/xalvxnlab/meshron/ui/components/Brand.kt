package com.xalvxnlab.meshron.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.ui.theme.MeshColors

/** The Meshron "M" mark: one purple stroke. */
@Composable
fun MeshronLogo(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.15f
        val p = Path().apply {
            moveTo(w * 0.08f, h * 0.86f)
            lineTo(w * 0.22f, h * 0.16f)
            lineTo(w * 0.50f, h * 0.62f)
            lineTo(w * 0.78f, h * 0.16f)
            lineTo(w * 0.92f, h * 0.86f)
        }
        drawPath(
            p,
            Brush.linearGradient(listOf(MeshColors.GradStart, MeshColors.GradMid, MeshColors.GradEnd), Offset.Zero, Offset(w, h)),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Soft purple waves behind the screens. Drawn once, no animation. */
@Composable
fun MeshBackground(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Top right sweep
        val top = Path().apply {
            moveTo(w * 0.35f, 0f)
            cubicTo(w * 0.6f, h * 0.06f, w * 0.75f, h * 0.02f, w, h * 0.10f)
            lineTo(w, 0f)
            close()
        }
        drawPath(top, Brush.linearGradient(listOf(Color(0x553B1F7A), Color(0x00000000)), Offset(w, 0f), Offset(w * 0.4f, h * 0.12f)))
        // Bottom left band
        val band = Path().apply {
            moveTo(0f, h * 0.60f)
            cubicTo(w * 0.35f, h * 0.66f, w * 0.30f, h * 0.84f, w * 0.62f, h * 0.88f)
            cubicTo(w * 0.80f, h * 0.90f, w * 0.92f, h * 0.84f, w, h * 0.80f)
            lineTo(w, h * 0.86f)
            cubicTo(w * 0.85f, h * 0.95f, w * 0.55f, h * 0.97f, w * 0.40f, h * 0.90f)
            cubicTo(w * 0.22f, h * 0.82f, w * 0.15f, h * 0.70f, 0f, h * 0.68f)
            close()
        }
        drawPath(band, Brush.linearGradient(listOf(Color(0x664C1D95), Color(0x227C3AED), Color(0x00000000)), Offset(0f, h * 0.6f), Offset(w, h * 0.95f)))
        // A thin bright edge on the band
        val edge = Path().apply {
            moveTo(0f, h * 0.60f)
            cubicTo(w * 0.35f, h * 0.66f, w * 0.30f, h * 0.84f, w * 0.62f, h * 0.88f)
            cubicTo(w * 0.80f, h * 0.90f, w * 0.92f, h * 0.84f, w, h * 0.80f)
        }
        drawPath(edge, Color(0x338B5CF6), style = Stroke(width = 2f))
    }
}

/** The brand-gradient pill used for the main action on a screen. */
@Composable
fun GlowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    content: @Composable RowScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(src)
            .clip(shape)
            .then(if (enabled) Modifier.background(MeshColors.GradientH) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest))
            .clickable(interactionSource = src, indication = ripple(), enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A dark pill with a faint outline, for secondary actions. */
@Composable
fun DarkPill(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(src)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = src, indication = ripple(), onClick = onClick)
            .defaultMinSize(minHeight = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A rounded, slightly see-through card with a faint border. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    brush: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (onClick != null) Modifier.pressScale(src, 0.98f) else Modifier)
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(MaterialTheme.colorScheme.surfaceContainer))
            .then(if (onClick != null) Modifier.clickable(interactionSource = src, indication = ripple(), onClick = onClick) else Modifier),
        content = content,
    )
}

/** Round dark button holding an icon (e.g. search). */
@Composable
fun RoundIconButton(onClick: () -> Unit, size: Dp = 52.dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Shrinks slightly while pressed, then springs back: tells you the tap landed. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}
