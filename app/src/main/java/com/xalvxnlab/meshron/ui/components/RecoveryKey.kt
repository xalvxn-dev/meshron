package com.xalvxnlab.meshron.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xalvxnlab.meshron.ui.theme.MeshColors

/** Groups a 52-character key as "abcd efgh …" so it's easier to write down. */
fun groupKey(key: String): String = key.chunked(4).joinToString(" ")

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Shows the recovery key, after a warning. Screenshots are blocked while it's open. */
@Composable
fun RecoveryKeyDialog(load: suspend () -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf(false) }
    if (reveal) {
        LaunchedEffect(Unit) { key = load() }
        DisposableEffect(Unit) {
            val window = context.activity()?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Key, null, tint = MeshColors.PurpleBright) },
        title = { Text(if (reveal) "Your recovery key" else "Back up your account") },
        text = {
            Column {
                if (!reveal) {
                    Text(
                        "If you lose this phone, your recovery key brings your Meshron ID back on a new one, so friends " +
                            "still reach you. Write it down and keep it somewhere safe.\n\n" +
                            "Anyone with this key can pretend to be you. Never send it to anyone, and Meshron will never ask for it.\n\n" +
                            "It restores your ID only, not your chats or contacts.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        groupKey(key ?: "…"),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        lineHeight = 30.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Write these 52 letters and numbers on paper. Spaces don't matter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (!reveal) TextButton(onClick = { reveal = true }) { Text("Show my key") }
            else TextButton(onClick = onDismiss) { Text("I wrote it down") }
        },
        dismissButton = {
            if (reveal && key != null) {
                TextButton(onClick = {
                    val clip = ClipData.newPlainText("Meshron recovery key", key)
                    // Android 13+: keep it out of clipboard previews.
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                    Toast.makeText(context, "Copied. Paste it somewhere private, then clear your clipboard.", Toast.LENGTH_LONG).show()
                }) { Text("Copy") }
            } else {
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        },
    )
}
