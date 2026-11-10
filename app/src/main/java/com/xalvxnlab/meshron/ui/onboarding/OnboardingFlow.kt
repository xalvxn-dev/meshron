package com.xalvxnlab.meshron.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NoAccounts
import androidx.compose.ui.graphics.Color
import com.xalvxnlab.meshron.ui.components.GlowButton
import com.xalvxnlab.meshron.ui.components.IconTile
import com.xalvxnlab.meshron.ui.components.MeshronLogo
import com.xalvxnlab.meshron.ui.theme.MeshColors
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.identity.IdentityRepository
import com.xalvxnlab.meshron.ui.components.Avatar
import kotlinx.coroutines.launch

@Composable
fun OnboardingFlow(repo: IdentityRepository) {
    var step by rememberSaveable { mutableStateOf(0) }
    BackHandler(enabled = step != 0) { step = 0 }
    when (step) {
        0 -> WelcomeScreen(onStart = { step = 1 }, onRestore = { step = 2 })
        1 -> CreateIdentityScreen(repo)
        else -> RestoreScreen(repo)
    }
}

@Composable
private fun WelcomeScreen(onStart: () -> Unit, onRestore: () -> Unit) {
    // Content on top, actions pinned to the bottom where thumbs are.
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(56.dp))
            MeshronLogo(44.dp)
            Spacer(Modifier.height(28.dp))
            Text("Talk freely.\nNo one in between.", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "Meshron sends your messages and calls straight to your friends' phones. Nothing is stored on a server, and there's no account.",
                style = MaterialTheme.typography.bodyLarge,
                color = MeshColors.TextSecondary,
            )
            Spacer(Modifier.height(36.dp))
            Point(Icons.Rounded.NoAccounts, MeshColors.TileIndigo, "No number, no sign-up", "Pick a name and you're in.")
            Point(Icons.Rounded.Lock, MeshColors.TileGreen, "Encrypted end to end", "Only you and your friend can read or hear it.")
            Point(Icons.Rounded.Bluetooth, MeshColors.TileBlue, "Works without internet", "Phones nearby connect over Wi-Fi or Bluetooth.")
        }
        GlowButton(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Spacer(Modifier.weight(1f))
            Text("Get started", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.weight(1f))
        }
        TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("I have a recovery key") }
    }
}

@Composable
private fun Point(icon: ImageVector, tint: Color, title: String, body: String) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tint, 40.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MeshColors.TextSecondary)
        }
    }
}

@Composable
private fun CreateIdentityScreen(repo: IdentityRepository) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // Suggested from the name until the user edits it.
    var handle by rememberSaveable { mutableStateOf("") }
    var handleEdited by rememberSaveable { mutableStateOf(false) }
    val suggested = Identity.cleanName(name).lowercase().replace(Regex("[^a-z0-9_]"), "").take(20)
    val shownHandle = if (handleEdited) handle else suggested
    val handleProblem = if (shownHandle.isEmpty()) null else Identity.handleProblem(shownHandle)
    val valid = Identity.isValidName(name) && handleProblem == null

    fun submit() {
        if (!valid || busy) return
        busy = true
        error = null
        scope.launch {
            // On success the identity flow emits and AppRoot switches to Home.
            runCatching {
                repo.create(name)
                if (shownHandle.isNotEmpty()) repo.setHandle(shownHandle)
            }.onFailure {
                error = it.message ?: "Could not create identity"
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Avatar(Identity.cleanName(name).ifEmpty { "?" }, "me", 88.dp)
        Spacer(Modifier.height(24.dp))
        Text("What should friends call you?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your contacts see this name. You can change it any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= Identity.MAX_NAME_LENGTH + 8) name = it },
            label = { Text("Your name") },
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            supportingText = { Text(error ?: "${Identity.cleanName(name).length}/${Identity.MAX_NAME_LENGTH}") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = shownHandle,
            onValueChange = { handle = it.take(24); handleEdited = true },
            label = { Text("@username (optional)") },
            prefix = { Text("@") },
            singleLine = true,
            enabled = !busy,
            isError = handleProblem != null,
            supportingText = { Text(handleProblem ?: "Friends can find you by it. You can change it later.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        GlowButton(onClick = ::submit, enabled = valid && !busy, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            else Text("Continue", style = MaterialTheme.typography.titleMedium, color = if (valid) Color.White else MeshColors.TextTertiary)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "A private key is made on this phone and locked by Android. It never leaves the device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** New phone: bring an existing Meshron ID back from its recovery key. */
@Composable
private fun RestoreScreen(repo: IdentityRepository) {
    val scope = rememberCoroutineScope()
    var key by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val cleanKey = key.lowercase().filter { !it.isWhitespace() && it != '-' }
    val valid = cleanKey.length == 52 && Identity.isValidName(name)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Restore your account", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Type the 52-character recovery key you wrote down. Your Meshron ID comes back, so friends reach you again. " +
                "Old chats and contacts stay on the old phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.take(80); error = null },
            label = { Text("Recovery key") },
            minLines = 3,
            enabled = !busy,
            isError = error != null,
            supportingText = { Text(error ?: "${cleanKey.length}/52") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= Identity.MAX_NAME_LENGTH + 8) name = it },
            label = { Text("Your name") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true
                scope.launch {
                    runCatching { repo.restore(cleanKey, name) }.onFailure {
                        error = it.message ?: "Couldn't restore"
                        busy = false
                    }
                }
            },
            enabled = valid && !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Restore", style = MaterialTheme.typography.titleMedium)
        }
    }
}
