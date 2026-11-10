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
    AlertDialog(
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
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text ?: "Private messages and calls, phone to phone. No accounts, nothing stored on servers, works without internet.\n\n" +
                            "Made by xalvxnLab.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
