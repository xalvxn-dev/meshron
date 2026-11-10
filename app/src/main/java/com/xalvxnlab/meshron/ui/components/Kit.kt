package com.xalvxnlab.meshron.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.ui.theme.MeshColors

/*
 * Layout grid: 16dp side margins, 8dp steps. List rows: 48dp leading slot, 16dp gap, so text
 * always starts 80dp from the edge and dividers start there too.
 */
val RowTextStart: Dp = 80.dp

/** The standard list row: leading (avatar or tile), title, optional subtitle, trailing. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    titleExtra: (@Composable RowScope.() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leadingSize: Dp = 48.dp,
    titleBold: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .then(if (onClick != null || onLongClick != null) Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier)
            .defaultMinSize(minHeight = 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(leadingSize), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(if (leadingSize > 48.dp) 14.dp else 16.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, color = titleColor,
                    fontWeight = if (titleBold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                titleExtra?.invoke(this)
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                subtitle()
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Secondary line under a row title. */
@Composable
fun RowSubtitle(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Hairline divider aligned with row text. */
@Composable
fun InsetDivider(start: Dp = RowTextStart) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(0.5.dp).background(MeshColors.Hairline))
}

/** Small header above a list section. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (accent) MeshColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** A 32dp rounded square with a white icon: identifies a settings row at a glance. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(color),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.58f), tint = Color.White) }
}

/** A 48dp circle with an accent icon: for action rows that sit among avatars. */
@Composable
fun ActionCircle(icon: ImageVector) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(MeshColors.Gradient),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White) }
}

/** Rounded container for a group of settings rows, separated by inset hairlines. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(MeshColors.Surface1),
        content = content,
    )
}

/** One settings row: tile, title, optional subtitle, and a switch, value text or chevron. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    showChevron: Boolean = checked == null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick).defaultMinSize(minHeight = 56.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        when {
            checked != null -> Switch(
                checked = checked, onCheckedChange = { onClick() },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MeshColors.AccentStrong, checkedThumbColor = Color.White,
                    uncheckedTrackColor = MeshColors.Surface3, uncheckedThumbColor = MeshColors.TextSecondary,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
            showChevron -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MeshColors.TextTertiary)
        }
    }
}

/** Hairline between settings rows (starts after the tile). */
@Composable
fun SettingsDivider() = InsetDivider(start = 64.dp)

/** Small rounded tag, e.g. "Verified". */
@Composable
fun Tag(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

