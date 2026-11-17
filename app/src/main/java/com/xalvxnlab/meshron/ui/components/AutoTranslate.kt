package com.xalvxnlab.meshron.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.account.AiService

/**
 * Someone else's words in another language, shown in mine (chats, groups, comments).
 * Returns null while loading, when it's already my language, or when translation is off.
 */
@Composable
fun rememberAutoTranslation(id: String, text: String, enabled: Boolean = true): AiService.Translation? {
    val app = LocalContext.current.applicationContext as MeshronApp
    val on by app.settings.autoTranslate.collectAsState(initial = true)
    val account by app.accounts.state.collectAsState(initial = null)
    var tr by remember(id) { mutableStateOf<AiService.Translation?>(null) }
    LaunchedEffect(id, on, account, enabled) {
        tr = if (enabled && on && account != null && !com.xalvxnlab.meshron.identity.Mode.anon)
            runCatching { app.ai.autoTranslate(id, text) }.getOrNull() else null
    }
    return tr
}

/** "Translated from Spanish · See original" (in my language); tap to switch. */
@Composable
fun TranslationNote(tr: AiService.Translation, showingOriginal: Boolean, color: Color, onToggle: () -> Unit) {
    Text(
        if (showingOriginal) "↺ " + tr.note else tr.note + " · " + tr.original,
        color = color.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle),
    )
}
