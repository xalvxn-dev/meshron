package com.xalvxnlab.meshron.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.ui.theme.MeshColors
import com.xalvxnlab.meshron.ui.components.MeshronLogo

/** Version, privacy policy and open-source licenses (all bundled, works offline). */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var page by remember { mutableIntStateOf(0) }
    val text = remember(page) {
        val file = when (page) { 1 -> "privacy.txt"; 2 -> "licenses.txt"; else -> null }
        file?.let { runCatching { context.assets.open(it).bufferedReader().readText() }.getOrDefault("") }
    }
    com.xalvxnlab.meshron.ui.components.PageDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MeshronLogo(28.dp)
                Spacer(Modifier.width(12.dp))
                Text("Meshron $version")
            }
        },
        text = {
            Column {
                Row {
                    FilterChip(selected = page == 0, onClick = { page = 0 }, label = { Text("About") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = page == 1, onClick = { page = 1 }, label = { Text("Privacy") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = page == 2, onClick = { page = 2 }, label = { Text("Licenses") })
                }
                Spacer(Modifier.height(16.dp))
                when (page) {
                    // The same Privacy Policy as Settings → Your data & legal.
                    1 -> {
                        val d = com.xalvxnlab.meshron.ui.legal.PRIVACY
                        Text("Last updated " + d.updated, color = MeshColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(d.intro, style = MaterialTheme.typography.bodyLarge)
                        for ((h, b) in d.sections) {
                            Spacer(Modifier.height(16.dp))
                            Text(h, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(b, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    2 -> Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MeshColors.TextSecondary)
                    else -> Column {
                        Text("Private chats, calls, moments and videos, device to device.", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Chats and calls are end-to-end encrypted and travel straight between phones, over the internet, the same Wi-Fi or Bluetooth. " +
                                "Meshron's server only handles public accounts (sign-in on new phones), moderation and verification.",
                            color = MeshColors.TextSecondary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Founded by Alvin Nana Kojo Pieterson (CEO) and Clement Kofi Yeboah (co-CEO).", color = MeshColors.TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text("Made by xalvxnLab.", color = MeshColors.TextSecondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
