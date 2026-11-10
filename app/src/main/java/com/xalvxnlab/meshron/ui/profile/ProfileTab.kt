package com.xalvxnlab.meshron.ui.profile

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import com.xalvxnlab.meshron.ui.components.SectionHeader
import com.xalvxnlab.meshron.ui.components.SettingsDivider
import com.xalvxnlab.meshron.ui.components.SettingsGroup
import com.xalvxnlab.meshron.ui.components.SettingsRow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import com.xalvxnlab.meshron.ui.components.RecoveryKeyDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.crypto.toHex
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.local.LocalTransports
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.NetworkState
import com.xalvxnlab.meshron.network.PeerInfo
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.components.GlowButton
import com.xalvxnlab.meshron.ui.components.QrCode
import com.xalvxnlab.meshron.ui.components.SectionLabel
import com.xalvxnlab.meshron.ui.home.shareIdentity
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun ProfileTab(identity: Identity, repo: IdentityRepository, network: NetworkService) {
    val context = LocalContext.current
    val net by network.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingHandle by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showQr by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var selfTest by remember { mutableStateOf<Boolean?>(null) }
    val app = context.applicationContext as MeshronApp
    val stayConnected by app.settings.stayConnected.collectAsState(initial = false)
    val local by app.localTransports.status.collectAsState()
    val useInternet by app.settings.useInternet.collectAsState(initial = true)
    val sameWifi by app.settings.sameWifi.collectAsState(initial = true)
    val nearby by app.settings.nearby.collectAsState(initial = true)
    val carry by app.settings.carryForOthers.collectAsState(initial = true)
    // Notifications are optional: the switch works either way.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.localTransports.permissionsChanged()
    }
    val missingPermission = (sameWifi && local.lanMissingPermission) || (nearby && local.nearbyMissingPermission)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // ---- Profile header ----
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Gradient cover with the avatar overlapping its lower edge.
            Box(Modifier.fillMaxWidth().height(164.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MeshColors.Gradient)
                )
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .background(MeshColors.Bg, CircleShape)
                        .padding(5.dp)
                ) {
                    Avatar(identity.username, identity.publicKey.toHex(), 96.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(identity.username, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(identity.handle.takeIf { it.isNotEmpty() }?.let { "@$it" }, "ID " + identity.shortId).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MeshColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderAction(Icons.Rounded.Share, "Share", Modifier.weight(1f)) { shareIdentity(context, identity) }
                HeaderAction(Icons.Rounded.QrCode2, "QR code", Modifier.weight(1f)) { showQr = true }
                HeaderAction(Icons.Rounded.ContentCopy, "Copy ID", Modifier.weight(1f)) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Meshron ID", identity.id))
                    Toast.makeText(context, "ID copied", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ---- Account ----
        SectionHeader("Account")
        SettingsGroup {
            SettingsRow(Icons.Rounded.Person, MeshColors.TileBlue, "Name", value = identity.username) { editing = true }
            SettingsDivider()
            SettingsRow(Icons.Rounded.AlternateEmail, MeshColors.TileIndigo, "Username",
                value = identity.handle.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "Not set") { editingHandle = true }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Key, MeshColors.TileOrange, "Recovery key", subtitle = "Restore your ID on a new phone") { showBackup = true }
        }

        // ---- Connections ----
        SectionHeader("Connections")
        if (missingPermission) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(16.dp)).background(MeshColors.Waiting.copy(alpha = 0.10f))
                    .clickable { askLocal.launch(LocalTransports.allPermissions()) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WarningAmber, null, tint = MeshColors.Waiting)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Allow nearby devices", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Needed to find phones without internet. Meshron never reads your location.",
                        style = MaterialTheme.typography.bodySmall, color = MeshColors.TextSecondary,
                    )
                }
            }
        }
        SettingsGroup {
            SettingsRow(Icons.Rounded.Language, MeshColors.TileBlue, "Internet", subtitle = "Reach contacts anywhere", checked = useInternet) {
                scope.launch { app.settings.setUseInternet(!useInternet) }
            }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Wifi, MeshColors.TileTeal, "Same Wi-Fi",
                subtitle = "Phones on your network or hotspot" + count(sameWifi, local.lanLinks), checked = sameWifi) {
                if (!sameWifi) askLocal.launch(LocalTransports.allPermissions())
                scope.launch { app.settings.setSameWifi(!sameWifi) }
            }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Bluetooth, MeshColors.TileIndigo, "Bluetooth & Wi-Fi Direct",
                subtitle = if (nearby && local.nearbyError != null) "Not available on this phone" else "Phones close by" + count(nearby, local.nearbyLinks),
                checked = nearby) {
                if (!nearby) askLocal.launch(LocalTransports.allPermissions())
                scope.launch { app.settings.setNearby(!nearby) }
            }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Hub, MeshColors.TileGreen, "Pass messages on",
                subtitle = "Carry others' locked messages", checked = carry) {
                scope.launch { app.settings.setCarryForOthers(!carry) }
            }
        }
        Text(
            "Meshron uses every way that's switched on. Messages and calls are end-to-end encrypted on all of them.",
            style = MaterialTheme.typography.bodySmall,
            color = MeshColors.TextTertiary,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )

        // ---- Notifications ----
        SectionHeader("Notifications")
        SettingsGroup {
            SettingsRow(Icons.Rounded.NotificationsActive, MeshColors.TileRose, "Stay connected",
                subtitle = "Messages and calls when the app is closed", checked = stayConnected) {
                val on = !stayConnected
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                scope.launch {
                    app.settings.setStayConnected(on)
                    app.applyBackgroundSetting()
                }
            }
        }

        // ---- About ----
        SectionHeader("About")
        SettingsGroup {
            SettingsRow(Icons.Rounded.Info, MeshColors.TileGrey, "About Meshron", subtitle = "Version, privacy policy, licenses") { showAbout = true }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Tune, MeshColors.TileGrey, "Connection details", subtitle = "For troubleshooting") { showDetails = true }
        }
    }

    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("Connection details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val dht = net.dht
                InfoRow(
                    "Network", when (net.status) {
                        NetworkState.Status.STOPPED -> "Not started"
                        NetworkState.Status.STARTING -> "Starting…"
                        NetworkState.Status.ONLINE -> when {
                            dht?.internet == false -> "Nearby only"
                            dht?.bootstrapped == true -> "Online"
                            else -> "Joining DHT…"
                        }
                        NetworkState.Status.FAILED -> "Failed"
                    }
                )
                InfoRow("Connected now", net.peers.values.count { it.state == PeerInfo.State.CONNECTED }.toString())
                InfoRow("Public address", dht?.host?.let { "$it:${dht.port}" } ?: "–")
                InfoRow("NAT", dht?.natLabel ?: "–")
                InfoRow("Open connections", (dht?.connections ?: 0).toString())
                InfoRow("Relay", "None")
                InfoRow("Key matches ID", when (net.keyMatches) { true -> "Yes"; false -> "NO"; null -> "–" })
                InfoRow("Key type", "Ed25519 · Android Keystore")
                InfoRow("Messages", "SQLCipher database")
                InfoRow("Identity created", DateFormat.getDateInstance().format(Date(identity.createdAt)))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { selfTest = repo.selfTest() } }) { Text("Run key check") }
                    when (selfTest) {
                        true -> Text("Passed", color = MeshColors.Online)
                        false -> Text("Failed", color = MaterialTheme.colorScheme.error)
                        null -> {}
                    }
                }
                net.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                Text(
                    net.log.takeLast(15).joinToString("\n").ifEmpty { "–" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MeshColors.TextSecondary,
                )
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } },
    )

    if (showBackup) RecoveryKeyDialog(load = { repo.recoveryKey() }, onDismiss = { showBackup = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    if (editingHandle) HandleDialog(
        initial = identity.handle,
        myKey = identity.publicKey.toHex(),
        check = { network.findHandle(it) },
        onDismiss = { editingHandle = false },
        onSave = { h -> scope.launch { repo.setHandle(h); editingHandle = false } },
    )

    if (editing) RenameDialog(
        initial = identity.username,
        onDismiss = { editing = false },
        onSave = { name -> scope.launch { repo.rename(name); editing = false } },
    )

    if (showQr) AlertDialog(
        onDismissRequest = { showQr = false },
        title = { Text("Scan to add ${identity.username}") },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(identity.shareLink) } },
        confirmButton = { TextButton(onClick = { showQr = false }) { Text("Done") } },
    )
}

private fun count(on: Boolean, n: Int) = if (on && n > 0) " · $n connected" else ""

/** A square-ish action under the profile header: icon over a short label. */
@Composable
private fun HeaderAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MeshColors.Surface1).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).background(MeshColors.Gradient, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MeshColors.TextPrimary)
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun SettingSwitch(icon: ImageVector, title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name") },
        text = {
            Column {
                Text(
                    "This is what your contacts see.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= Identity.MAX_NAME_LENGTH + 8) name = it },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = Identity.isValidName(name)) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Choose the findable @username. Before saving, looks it up: with no central server nobody
 * can own a name, but we warn if someone else already announced it.
 */
@Composable
private fun HandleDialog(
    initial: String,
    myKey: String,
    check: suspend (String) -> NetworkService.HandleResult,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var value by rememberSaveable { mutableStateOf(initial) }
    var checking by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    val h = Identity.cleanHandle(value)
    val problem = if (h.isEmpty()) null else Identity.handleProblem(h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your @username") },
        text = {
            Column {
                Text(
                    "Friends can find you by typing it in \"Add contact\". Letters, numbers and _, 3 to 20 long.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(24); warning = null },
                    prefix = { Text("@") },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = { Text(problem ?: warning ?: "Leave empty to not be findable by name.") },
                )
            }
        },
        confirmButton = {
            if (warning != null) {
                TextButton(onClick = { onSave(h) }) { Text("Use anyway") }
            } else {
                TextButton(
                    enabled = problem == null && !checking,
                    onClick = {
                        if (h.isEmpty() || h == initial) return@TextButton onSave(h)
                        checking = true
                        scope.launch {
                            val r = check(h)
                            checking = false
                            val others = r.keys.filter { it != myKey }
                            when {
                                others.isNotEmpty() -> warning = "Someone else already uses @$h. Pick another, or use it anyway (friends will see two and must pick by ID)."
                                else -> onSave(h)
                            }
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MeshColors.PurpleBright)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
