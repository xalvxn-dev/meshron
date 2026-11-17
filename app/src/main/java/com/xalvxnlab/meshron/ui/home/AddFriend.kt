package com.xalvxnlab.meshron.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.ContactParser
import com.xalvxnlab.meshron.contacts.ParsedContact
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.MenuTile
import com.xalvxnlab.meshron.ui.components.QrCode
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.delay

/** Someone found by @username (or a link), ready to add. */
data class Found(val key: String, val handle: String, val name: String)

/**
 * Add friends only three ways: their @username, their link, or their QR code.
 * [onAdd] gets the key (and a name hint).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFriendSheet(me: Identity, onScan: () -> Unit, onAdd: (Found) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Found>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var showCode by remember { mutableStateOf(false) }
    val myKey = remember(me) { me.publicKey.joinToString("") { "%02x".format(it) } }

    // A pasted link is understood straight away; a @username is looked up as you type.
    LaunchedEffect(q) {
        found = null; note = null
        val text = q.trim()
        if (text.isEmpty()) return@LaunchedEffect
        if (text.contains("meshron://") || text.length > 40) {
            when (val p = ContactParser.parse(text)) {
                is ParsedContact.Ok -> found = if (p.keyHex == myKey) { note = "That's your own link"; emptyList() } else listOf(Found(p.keyHex, "", p.name))
                is ParsedContact.Invalid -> note = "That link isn't a Meshron link"
            }
            return@LaunchedEffect
        }
        val h = text.removePrefix("@").lowercase()
        if (!Regex("^[a-z0-9_]{3,24}$").matches(h)) { note = "Usernames are 3–24 letters, numbers or _"; return@LaunchedEffect }
        delay(450)
        busy = true
        // Accounts first (exact, instant), then people announcing that @username on the network.
        val keys = linkedSetOf<String>()
        app.accounts.resolve(h)?.let { keys += it }
        if (keys.isEmpty()) runCatching { app.networkService.findHandle(h) }.getOrNull()?.keys?.let { keys += it }
        keys.remove(myKey)
        busy = false
        found = keys.map { Found(it, h, "") }
        if (keys.isEmpty()) note = "Nobody found with @$h"
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Add friends", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            TextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("@username or paste a link") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
            note?.let { Text(it, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
            found?.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(f.name.ifBlank { f.handle }, f.key, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name.ifBlank { "@" + f.handle }, style = MaterialTheme.typography.titleMedium)
                        Text(if (f.handle.isNotEmpty()) "@${f.handle}" else "From a link", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).background(MeshColors.TextPrimary).clickable { onAdd(f) }.padding(horizontal = 18.dp, vertical = 9.dp)) {
                        Text("Add", color = MeshColors.Bg, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            MenuTile(Icons.Rounded.QrCodeScanner, MeshColors.TileTeal, "Scan a QR code", "Point at a friend's Meshron code", onScan)
            MenuTile(Icons.Rounded.Link, MeshColors.TileIndigo, if (showCode) "Hide my code" else "My QR code & link", "Let a friend scan or open it") { showCode = !showCode }
            if (showCode) Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.White).padding(16.dp)) { QrCode(me.shareLink) }
                Spacer(Modifier.height(8.dp))
                if (me.handle.isNotEmpty()) Text("@" + me.handle, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(Icons.Rounded.Share, "Share link") { shareIdentity(context, me) }
                    Pill(Icons.Rounded.ContentCopy, "Copy link") {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Meshron link", me.shareLink))
                        com.xalvxnlab.meshron.ui.components.Toasts.show(context, "Link copied")
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(MeshColors.Surface2).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
