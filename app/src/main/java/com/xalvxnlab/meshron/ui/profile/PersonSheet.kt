package com.xalvxnlab.meshron.ui.profile

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.theme.MeshColors

/**
 * Someone's profile: photo, name, @username, bio (as they share it), ID and quick actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSheet(
    contact: Contact,
    online: Boolean,
    onDismiss: () -> Unit,
    onMessage: (() -> Unit)?,
    onCall: (() -> Unit)?,
    onVideo: (() -> Unit)?,
    onVerify: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MeshronApp
    val profiles by app.profiles.peers.collectAsState()
    val bio = profiles[contact.keyHex]?.bio.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MeshColors.Surface1) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(contact.displayName, contact.keyHex, 112.dp, online = online)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contact.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                if (contact.verified) {
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.Rounded.Verified, "Verified", Modifier.size(20.dp), tint = Color(0xFF60A5FA))
                }
            }
            Text(
                listOfNotNull(contact.handle.takeIf { it.isNotEmpty() }?.let { "@$it" }, if (online) "online" else "offline").joinToString(" · "),
                color = if (online) MeshColors.Online else MeshColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (bio.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(bio, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                onMessage?.let { SheetAction(Icons.AutoMirrored.Rounded.Chat, "Message", it) }
                onCall?.let { SheetAction(Icons.Rounded.Call, "Call", it) }
                onVideo?.let { SheetAction(Icons.Rounded.Videocam, "Video", it) }
                SheetAction(Icons.Rounded.VerifiedUser, if (contact.verified) "Verified" else "Verify", onVerify)
            }
            Spacer(Modifier.height(20.dp))
            Text("MESHRON ID", style = MaterialTheme.typography.labelMedium, color = MeshColors.TextTertiary, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                contact.id,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MeshColors.TextSecondary,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MeshColors.Surface2)
                    .clickable {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Meshron ID", contact.id))
                        com.xalvxnlab.meshron.ui.components.Toasts.show(context, "ID copied")
                    }
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(MeshColors.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(22.dp), tint = MeshColors.TextPrimary)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MeshColors.TextSecondary)
    }
}
