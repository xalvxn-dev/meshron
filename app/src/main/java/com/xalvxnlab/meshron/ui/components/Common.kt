package com.xalvxnlab.meshron.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.ui.theme.MeshColors
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Up to two initials: "Dana Lee" -> "DL", "bob" -> "B". */
fun initials(name: String): String {
    val words = name.trim().split(' ').filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * A gradient circle (rounded square for groups) with initials. Online shows a green dot;
 * [ring] draws the brand-gradient "story" ring around it instead (used in Active now).
 */
@Composable
fun Avatar(
    name: String,
    seed: String,
    size: Dp = 48.dp,
    group: Boolean = false,
    online: Boolean = false,
    @Suppress("UNUSED_PARAMETER") showOffline: Boolean = false,
    ring: Boolean = false,
) {
    val shape = if (group) RoundedCornerShape(size * 0.3f) else CircleShape
    Box {
        Box(
            Modifier.size(size)
                .then(
                    if (ring) Modifier.border(2.5.dp, MeshColors.Gradient, shape).padding(4.5.dp)
                    else Modifier
                )
                .background(MeshColors.avatarBrush(seed), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials(name),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * (if (ring) 0.32f else 0.38f)).sp,
                letterSpacing = 0.sp,
            )
        }
        if (online && !ring) {
            val dot = (size * 0.27f).coerceAtLeast(10.dp)
            Box(
                Modifier.size(dot).align(Alignment.BottomEnd)
                    .background(MeshColors.Bg, CircleShape)
                    .padding(dot * 0.18f)
                    .background(MeshColors.Online, CircleShape)
            )
        }
    }
}

/** Unread count: brand-gradient pill. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .background(MeshColors.GradientH, CircleShape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

/** Centered icon, title, explanation and an optional action. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).background(MeshColors.Gradient, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(38.dp), tint = Color.White)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.height(24.dp))
        action()
    }
}

/** Quiet label above a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** "14:05" today, "Yesterday", weekday this week, otherwise a short date. */
fun friendlyTime(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val days = daysBetween(then, now)
    return when {
        days == 0 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
        days == 1 -> "Yesterday"
        days in 2..6 -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(ms))
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    }
}

/** "Today", "Yesterday", or a full date, for separators in a chat. */
fun dayLabel(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    return when (daysBetween(then, now)) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }
}

fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun daysBetween(then: Calendar, now: Calendar): Int {
    fun midnight(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return ((midnight(now) - midnight(then)) / 86_400_000L).toInt()
}
