package com.xalvxnlab.meshron.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.crypto.hexToBytes
import com.xalvxnlab.meshron.ui.theme.MeshColors
import java.security.MessageDigest

/**
 * A 60-digit code both people see the same way: a hash of the two identity keys. If the
 * codes match when compared in person or on a call, nobody is impersonating either side.
 */
fun safetyCode(myKeyHex: String, theirKeyHex: String): List<String> {
    val (a, b) = listOf(myKeyHex, theirKeyHex).sorted()
    val d = MessageDigest.getInstance("SHA-512").digest("meshron-safety-v1".toByteArray() + a.hexToBytes() + b.hexToBytes())
    return List(12) { i ->
        val v = ((d[i * 4].toLong() and 0xff) shl 24) or ((d[i * 4 + 1].toLong() and 0xff) shl 16) or
            ((d[i * 4 + 2].toLong() and 0xff) shl 8) or (d[i * 4 + 3].toLong() and 0xff)
        (v % 100000).toString().padStart(5, '0')
    }
}

@Composable
fun SafetyCodeDialog(
    name: String,
    myKeyHex: String,
    theirKeyHex: String,
    verified: Boolean,
    onSetVerified: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val code = remember(myKeyHex, theirKeyHex) { safetyCode(myKeyHex, theirKeyHex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.VerifiedUser, null, tint = if (verified) MeshColors.Online else MeshColors.PurpleBright) },
        title = { Text("Safety code with $name") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Compare this with the code on $name's phone, in person or on a call. If they match, your chat is with the real $name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    code.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
                if (verified) {
                    Spacer(Modifier.height(12.dp))
                    Text("You marked $name as verified.", color = MeshColors.Online, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            if (verified) TextButton(onClick = { onSetVerified(false); onDismiss() }) { Text("Remove verified") }
            else TextButton(onClick = { onSetVerified(true); onDismiss() }) { Text("They match") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
