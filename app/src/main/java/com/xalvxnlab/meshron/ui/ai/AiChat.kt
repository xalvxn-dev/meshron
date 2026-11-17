package com.xalvxnlab.meshron.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.account.AiService
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch

/** Session-only history: not saved anywhere. */
private val history = mutableStateListOf<AiService.Turn>()

/** Meshron AI: a private helper chat. */
@Composable
fun AiChat(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val app = LocalContext.current.applicationContext as MeshronApp
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(history.size) { if (history.isNotEmpty()) list.animateScrollToItem(history.size) }
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        val before = history.toList()
        history += AiService.Turn(true, t)
        draft = ""; busy = true; error = null
        scope.launch {
            runCatching { app.ai.helper(t, before) }
                .onSuccess { history += AiService.Turn(false, it) }
                .onFailure { error = it.message }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().background(MeshColors.Bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Box(Modifier.size(38.dp).clip(CircleShape).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("Meshron AI", style = MaterialTheme.typography.titleMedium)
                Text("Not end-to-end encrypted · not saved", style = MaterialTheme.typography.bodySmall, color = MeshColors.TextSecondary)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ask anything", style = MaterialTheme.typography.headlineSmall)
                    Text("What you type here goes to the AI service, so don't share secrets.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    if (history.isEmpty()) Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Plan a fun weekend with friends", "Help me write a birthday message", "Explain how Meshron keeps chats private").forEach { s ->
                            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(MeshColors.Surface2).clickable { send(s) }.padding(horizontal = 14.dp, vertical = 10.dp)) { Text(s) }
                        }
                    }
                }
            }
            items(history) { t ->
                if (t.mine) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        t.text, color = Color.White,
                        modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                            .background(MeshColors.BubbleOwn).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else AiReply(t.text)
            }
            if (busy) item { AiTyping() }
            error?.let { item { Text(it, color = MeshColors.Danger) } }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp).clip(RoundedCornerShape(28.dp)).background(MeshColors.Surface1).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = draft, onValueChange = { draft = it.take(2000) }, placeholder = { Text("Message Meshron AI") }, maxLines = 5,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            Box(Modifier.size(46.dp).clip(CircleShape).background(MeshColors.Gradient).clickable { send(draft) }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = Color.White)
            }
        }
    }
}

/** An AI answer: small avatar, then clean text (stray Markdown removed, lists as bullets). Long-press copies. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AiReply(raw: String) {
    val context = LocalContext.current
    val text = remember(raw) { cleanAi(raw) }
    Row(Modifier.fillMaxWidth().padding(end = 28.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Text(
            text, color = MeshColors.TextPrimary, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
            modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp)).background(MeshColors.Surface1)
                .combinedClickable(onClick = {}, onLongClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Meshron AI", text.text))
                    com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Copied")
                }).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Three dots while Meshron AI is thinking. */
@Composable
private fun AiTyping() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "p")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
        Row(Modifier.padding(start = 10.dp).clip(RoundedCornerShape(20.dp)).background(MeshColors.Surface1).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i -> Box(Modifier.size(7.dp).clip(CircleShape).background(MeshColors.TextSecondary.copy(alpha = if (p.toInt() == i) 1f else 0.35f))) }
        }
    }
}

/** Turns Markdown-ish AI text into tidy text: **bold** stays bold, lists become "•", headings and stray symbols go. */
internal fun cleanAi(raw: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val lines = raw.replace("\r", "").replace(Regex("```[a-zA-Z]*"), "").lines()
        .map { it.trimEnd() }
        .map { l ->
            var x = l.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")           // headings
            x = x.replace(Regex("^\\s*[-*+]\\s+"), "• ")                     // bullets
            x = x.replace(Regex("^\\s*(\\d+)[.)]\\s+"), "$1. ")            // numbered
            if (Regex("^\\s*([-*_]\\s*){3,}$").matches(x)) "" else x         // rules
        }
    val text = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    // **bold** / __bold__ become bold; single * _ ` are dropped.
    val bold = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")
    var i = 0
    for (m in bold.findAll(text)) {
        append(text.substring(i, m.range.first).replace(Regex("[*`]"), ""))
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold))
        append((m.groups[1] ?: m.groups[2])!!.value.replace(Regex("[*`]"), ""))
        pop()
        i = m.range.last + 1
    }
    append(text.substring(i).replace(Regex("[*`]"), ""))
}

/** "Ask Meshron AI" card for the chats screen. */
@Composable
fun AiEntry(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.AccentContainer).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MeshColors.Gradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Meshron AI", fontWeight = FontWeight.Bold, color = MeshColors.OnAccentContainer)
            Text("Ask anything, plan, write, translate", style = MaterialTheme.typography.bodySmall, color = MeshColors.OnAccentContainer.copy(alpha = 0.75f))
        }
        Spacer(Modifier.size(4.dp))
    }
}
