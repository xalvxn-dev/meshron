package com.xalvxnlab.meshron.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.ui.theme.MeshColors

/** Big screen title with actions on the right (no app bar chrome). */
@Composable
fun AuroraHeader(title: String, subtitle: String? = null, leading: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MeshColors.TextSecondary)
        }
        actions()
    }
}

/** Round tonal icon button used in headers. */
@Composable
fun HeaderButton(icon: ImageVector, label: String, tint: Color = MeshColors.TextPrimary, badge: Int = 0, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 3.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MeshColors.Surface1).border(1.dp, MeshColors.Hairline, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp)) }
        if (badge > 0) Box(
            Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(MeshColors.Danger).border(2.dp, MeshColors.Bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black) }
    }
}

/** Selectable filter pills. */
@Composable
fun FilterChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, counts: Map<String, Int> = emptyMap()) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (id, label) ->
            val on = id == selected
            val bg by animateColorAsState(if (on) MeshColors.TextPrimary else MeshColors.Surface1, label = "chip")
            val fg by animateColorAsState(if (on) MeshColors.Bg else MeshColors.TextSecondary, label = "chipText")
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(bg)
                    .border(1.dp, if (on) Color.Transparent else MeshColors.Hairline, RoundedCornerShape(50))
                    .clickable { onSelect(id) }.padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold)
                val n = counts[id] ?: 0
                if (n > 0) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).clip(RoundedCornerShape(50))
                            .background(if (on) MeshColors.Bg.copy(alpha = 0.16f) else MeshColors.AccentStrong).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$n", color = if (on) MeshColors.Bg else Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

class DockItem(val id: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val badge: Int = 0)

/**
 * Meshron's navigation: a text rail where a soft aurora glow slides to the current place,
 * and a separate round shutter for creating (record, upload, story, live). [overVideo] for
 * Discover, where it floats on top of videos.
 */
@Composable
fun AuroraDock(items: List<DockItem>, selected: String, onSelect: (String) -> Unit, onCreate: (() -> Unit)?, overVideo: Boolean = false) {
    val shape = RoundedCornerShape(24.dp)
    val bg = if (overVideo) Color(0xB30E1015) else MeshColors.Surface1
    val index = items.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.weight(1f).height(62.dp)
                .shadow(if (overVideo) 0.dp else 14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.2f))
                .clip(shape).background(bg).border(1.dp, if (overVideo) Color.White.copy(alpha = 0.08f) else MeshColors.Hairline, shape)
                .padding(6.dp),
        ) {
            val slot = maxWidth / items.size
            val x by animateDpAsState(slot * index, spring(dampingRatio = 0.78f, stiffness = 420f), label = "glow")
            // The glow under the current place.
            Box(
                Modifier.offset(x = x).width(slot).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                    .background(if (overVideo) Color.White.copy(alpha = 0.14f) else MeshColors.AccentContainer.copy(alpha = if (MeshColors.isDark) 0.85f else 1f)),
            )
            Row(Modifier.fillMaxSize()) {
                items.forEach { item ->
                    val on = item.id == selected
                    val tint = when {
                        on -> if (overVideo) Color.White else MeshColors.OnAccentContainer
                        else -> if (overVideo) Color.White.copy(alpha = 0.65f) else MeshColors.TextSecondary
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(19.dp))
                            .clickable(remember { MutableInteractionSource() }, null) { onSelect(item.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                Icon(if (on) item.selectedIcon else item.icon, null, tint = tint, modifier = Modifier.size(23.dp))
                                if (item.badge > 0) Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-5).dp)
                                        .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                                        .clip(RoundedCornerShape(50)).background(MeshColors.Danger)
                                        .border(1.5.dp, if (overVideo) Color.Transparent else MeshColors.Surface1, RoundedCornerShape(50))
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(if (item.badge > 9) "9+" else "${item.badge}", color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(item.label, color = tint, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.1.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (onCreate != null) {
            Spacer(Modifier.width(10.dp))
            Shutter(onCreate)
        }
    }
}

/** Round shutter: aurora ring around a solid core, like a camera's record button. */
@Composable
private fun Shutter(onClick: () -> Unit) {
    Box(
        Modifier.size(58.dp)
            .shadow(16.dp, CircleShape, ambientColor = MeshColors.GradStart.copy(alpha = 0.5f), spotColor = MeshColors.GradMid.copy(alpha = 0.6f))
            .clip(CircleShape).background(MeshColors.Gradient).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(androidx.compose.material.icons.Icons.Rounded.Add, "Create", tint = Color.White, modifier = Modifier.size(28.dp)) }
}

/** A large tappable card row for menus and sheets. */
@Composable
fun MenuTile(icon: ImageVector, tint: Color, title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).defaultMinSize(minHeight = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MeshColors.TextSecondary)
        }
    }
}

/** Streak flame with a count, e.g. "🔥 12". */
@Composable
fun StreakBadge(days: Int, modifier: Modifier = Modifier) {
    if (days < 2) return
    Row(modifier.clip(RoundedCornerShape(50)).background(MeshColors.Streak.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("🔥", fontSize = 11.sp)
        Text(" $days", color = MeshColors.Streak, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
