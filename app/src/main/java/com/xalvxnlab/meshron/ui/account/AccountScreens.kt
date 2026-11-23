package com.xalvxnlab.meshron.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.account.AccountCrypto
import com.xalvxnlab.meshron.account.AccountService
import com.xalvxnlab.meshron.ui.components.MeshronLogo
import com.xalvxnlab.meshron.ui.components.SectionHeader
import com.xalvxnlab.meshron.ui.components.SettingsDivider
import com.xalvxnlab.meshron.ui.components.SettingsGroup
import com.xalvxnlab.meshron.ui.components.SettingsRow
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Step { Welcome, Name, Email, Code, Password, Login, LoginCode }

/**
 * First run (public): sign up with a name, @username, email (code) and password, or sign in.
 * [offline] keeps the old no-account setup (recovery key) for phones without internet.
 */
@Composable
fun AuthFlow(offline: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val acc = app.accounts
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    var name by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var userFree by remember { mutableStateOf<Boolean?>(null) }
    var userReserved by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf("") }
    var emailHint by remember { mutableStateOf("") }

    fun go(s: Step) { error = null; step = s }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch { try { block() } catch (e: Exception) { error = e.message ?: "Something went wrong" } finally { busy = false } }
    }
    val back = when (step) {
        Step.Welcome -> null
        Step.Name, Step.Login -> Step.Welcome
        Step.Email -> Step.Name
        Step.Code -> Step.Email
        Step.Password -> Step.Code
        Step.LoginCode -> Step.Login
    }
    BackHandler(enabled = back != null) { back?.let { go(it) } }

    // Username availability, as you type.
    val cleanUser = user.trim().removePrefix("@").lowercase()
    LaunchedEffect(cleanUser) {
        userFree = null
        if (!Regex("^[a-z0-9_]{3,24}$").matches(cleanUser) || step != Step.Name) return@LaunchedEffect
        delay(450)
        userFree = runCatching { acc.available(cleanUser) }.getOrNull()
        userReserved = userFree == false && runCatching { acc.reserved(cleanUser) }.getOrDefault(false)
    }

    Box(Modifier.fillMaxSize().background(MeshColors.Bg)) {
        // Soft aurora glow at the top.
        Box(Modifier.fillMaxWidth().height(260.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(MeshColors.GradStart.copy(alpha = 0.22f), Color.Transparent))))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (back != null) IconButton(onClick = { go(back) }, modifier = Modifier.padding(6.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            else Spacer(Modifier.height(60.dp))
            AnimatedContent(step, transitionSpec = { (slideInHorizontally { it / 6 } + fadeIn()) togetherWith fadeOut() }, label = "auth", modifier = Modifier.weight(1f)) { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    when (s) {
                        Step.Welcome -> {
                            Spacer(Modifier.height(24.dp))
                            MeshronLogo(64.dp)
                            Spacer(Modifier.height(24.dp))
                            Text("Your people.\nNo middleman.", style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.height(12.dp))
                            Text("Chat, call and share reels, phone to phone. Messages are end-to-end encrypted and never stored on a server.", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(40.dp))
                            Primary("Create account", true) { go(Step.Name) }
                            Spacer(Modifier.height(10.dp))
                            Secondary("I have an account") { go(Step.Login) }
                            Spacer(Modifier.height(18.dp))
                            Text(
                                "No internet? Set up without an account", color = MeshColors.TextSecondary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(8.dp)).clickable(onClick = offline).padding(8.dp),
                            )
                        }
                        Step.Name -> {
                            Title("Who are you?", "Your name is what friends see. Your @username is how they find you.")
                            Field(name, { name = it.take(32) }, "Your name")
                            Spacer(Modifier.height(12.dp))
                            Field(user, { user = it.take(25) }, "@username", KeyboardType.Ascii, trailing = {
                                when {
                                    cleanUser.length >= 3 && userFree == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    userFree == true -> Icon(Icons.Rounded.CheckCircle, "Available", tint = MeshColors.Online)
                                    else -> Unit
                                }
                            })
                            Hint(
                                when {
                                    cleanUser.isNotEmpty() && !Regex("^[a-z0-9_]{3,24}$").matches(cleanUser) -> "3–24 letters, numbers or _"
                                    userReserved -> "@$cleanUser is kept for someone: enter your invite code"
                                    userFree == false -> "@$cleanUser is taken"
                                    userFree == true -> "@$cleanUser is yours to take"
                                    else -> "Letters, numbers and _ only"
                                },
                                userFree == false && !userReserved,
                            )
                            if (userReserved) { Spacer(Modifier.height(12.dp)); Field(invite, { invite = it.trim().take(64) }, "Invite code") }
                            Spacer(Modifier.height(24.dp))
                            Primary("Continue", name.isNotBlank() && (userFree == true || (userReserved && invite.length >= 4))) { go(Step.Email) }
                        }
                        Step.Email -> {
                            Title("Your email", "Only for codes that keep your account safe. Nobody else sees it.")
                            Field(email, { email = it.trim() }, "you@example.com", KeyboardType.Email)
                            Spacer(Modifier.height(24.dp))
                            Primary("Send code", email.contains('@') && email.contains('.')) {
                                run { acc.sendSignupCode(email); code = ""; go(Step.Code) }
                            }
                        }
                        Step.Code -> {
                            Title("Check your email", "We sent a 6-digit code to $email.")
                            CodeField(code) { code = it }
                            Text(
                                "Send it again", color = MeshColors.Accent, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).clickable { run { acc.sendSignupCode(email) } }.padding(6.dp),
                            )
                            Spacer(Modifier.height(24.dp))
                            Primary("Continue", code.length == 6) { go(Step.Password) }
                        }
                        Step.Password -> {
                            Title("Make a password", "It never leaves this phone. It unlocks your account on new devices, so pick one only you know.")
                            PasswordField(password) { password = it }
                            val (score, label) = AccountCrypto.strength(password)
                            StrengthBar(score, label)
                            Spacer(Modifier.height(24.dp))
                            Primary("Create account", score >= 2) {
                                run { acc.signUp(name, cleanUser, email, password, code, invite = if (userReserved) invite else ""); password = "" }
                            }
                        }
                        Step.Login -> {
                            Title("Welcome back", "Sign in with your @username and password.")
                            Field(user, { user = it.take(25) }, "@username", KeyboardType.Ascii)
                            Spacer(Modifier.height(12.dp))
                            PasswordField(password) { password = it }
                            Spacer(Modifier.height(24.dp))
                            Primary("Sign in", cleanUser.length >= 3 && password.isNotEmpty()) {
                                run {
                                    when (val r = acc.logIn(cleanUser, password)) {
                                        is AccountService.Login.NeedCode -> { emailHint = r.email; code = ""; go(Step.LoginCode) }
                                        AccountService.Login.Done -> Unit
                                    }
                                    password = ""
                                }
                            }
                        }
                        Step.LoginCode -> {
                            Title("2-step verification", "Enter the code we sent to $emailHint.")
                            CodeField(code) { code = it }
                            Spacer(Modifier.height(24.dp))
                            Primary("Verify", code.length == 6) { run { acc.logInCode(code) } }
                        }
                    }
                    error?.let {
                        Spacer(Modifier.height(16.dp))
                        Text(it, color = MeshColors.Danger, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (busy) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally)) }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable private fun Title(t: String, sub: String) {
    Text(t, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(8.dp))
    Text(sub, color = MeshColors.TextSecondary)
    Spacer(Modifier.height(24.dp))
}

@Composable private fun Hint(t: String, bad: Boolean = false) {
    Text(t, color = if (bad) MeshColors.Danger else MeshColors.TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Text, trailing: (@Composable () -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, placeholder = { Text(label) }, singleLine = true, trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshColors.Accent, unfocusedBorderColor = MeshColors.Hairline, focusedContainerColor = MeshColors.Surface1, unfocusedContainerColor = MeshColors.Surface1),
    )
}

@Composable
fun PasswordField(value: String, label: String = "Password", onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, placeholder = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide" else "Show") } },
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshColors.Accent, unfocusedBorderColor = MeshColors.Hairline, focusedContainerColor = MeshColors.Surface1, unfocusedContainerColor = MeshColors.Surface1),
    )
}

@Composable
private fun StrengthBar(score: Int, label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(4) { i ->
            val c = when { i >= score -> MeshColors.Surface3; score <= 1 -> MeshColors.Danger; score == 2 -> MeshColors.Waiting; else -> MeshColors.Online }
            Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c))
        }
    }
    Hint(label, score <= 1)
}

/** Six boxes for a code (one field underneath, so paste and autofill work). */
@Composable
fun CodeField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(6)) }, singleLine = true,
        placeholder = { Text("000000") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, letterSpacing = MaterialTheme.typography.headlineMedium.fontSize * 0.4f),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MeshColors.Accent, unfocusedBorderColor = MeshColors.Hairline, focusedContainerColor = MeshColors.Surface1, unfocusedContainerColor = MeshColors.Surface1),
    )
}

@Composable
fun Primary(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(if (enabled) MeshColors.TextPrimary else MeshColors.Surface3)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) MeshColors.Bg else MeshColors.TextTertiary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
}

@Composable
fun Secondary(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(MeshColors.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
}

// ---- Settings: account & security ------------------------------------------------------

private enum class Need { None, TwoOff, Password, Delete }

@Composable
fun SecuritySection() {
    val app = LocalContext.current.applicationContext as MeshronApp
    val acc = app.accounts
    val state by acc.state.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var need by remember { mutableStateOf(Need.None) }
    var linking by rememberSaveable { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var loggingOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SectionHeader("Account & security")
    val s = state
    SettingsGroup {
        if (s == null) {
            SettingsRow(Icons.Rounded.AlternateEmail, MeshColors.TileIndigo, "Create an account", subtitle = "Username, password and email, so you can sign in on new phones") { linking = true }
        } else {
            SettingsRow(Icons.Rounded.AlternateEmail, MeshColors.TileIndigo, "Username", value = "@" + s.username) {}
            SettingsDivider()
            SettingsRow(Icons.Rounded.MarkEmailRead, MeshColors.TileBlue, "Email", value = s.email) {}
            SettingsDivider()
            SettingsRow(Icons.Rounded.Lock, MeshColors.TileGreen, "2-step verification", subtitle = if (s.twoFactor) "On · new sign-ins need an email code" else "Off · turn on for extra safety", checked = s.twoFactor) {
                if (s.twoFactor) need = Need.TwoOff
                else scope.launch { runCatching { acc.setTwoFactor(true) }.onSuccess { msg = "2-step verification is on" }.onFailure { msg = it.message } }
            }
            SettingsDivider()
            SettingsRow(Icons.Rounded.Key, MeshColors.TileOrange, "Change password") { need = Need.Password }
            SettingsDivider()
            SettingsRow(Icons.Rounded.DeleteForever, MeshColors.Danger, "Delete account", subtitle = "Frees your @username. Chats on this phone stay.") { need = Need.Delete }
            SettingsDivider()
            SettingsRow(Icons.AutoMirrored.Rounded.Logout, MeshColors.TileGrey, "Log out · switch account", subtitle = "Sign in as @" + s.username + " or another account") { loggingOut = true }
        }
    }
    if (loggingOut && s != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = { loggingOut = false },
        title = { Text("Log out of @" + s.username + "?") },
        text = {
            Text("Meshron keeps chats on your phone, not on a server. Logging out clears this phone: chats, files and settings here are removed. " +
                "Your account, profile and videos stay: sign in again (or with another account) on the next screen.")
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            loggingOut = false
            // Clears every file, database and key of the app, then Android restarts it at sign-in.
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            if (am?.clearApplicationUserData() != true) msg = "Couldn't log out"
        }) { Text("Log out", color = MeshColors.Danger) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { loggingOut = false }) { Text("Cancel") } },
    )
    msg?.let { Text(it, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) }
    if (need != Need.None) CodeDialog(need, onClose = { need = Need.None }, onDone = { msg = it; need = Need.None })
    if (linking) LinkAccountDialog(onClose = { linking = false })
}

@Composable
private fun CodeDialog(need: Need, onClose: () -> Unit, onDone: (String) -> Unit) {
    val acc = (LocalContext.current.applicationContext as MeshronApp).accounts
    val scope = rememberCoroutineScope()
    var sentTo by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { acc.sendSecurityCode() }.onSuccess { sentTo = it }.onFailure { error = it.message } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(when (need) { Need.TwoOff -> "Turn off 2-step?"; Need.Password -> "New password"; else -> "Delete account?" }) },
        text = {
            Column {
                Text(sentTo?.let { "Enter the code we sent to $it." } ?: "Sending a code to your email…", color = MeshColors.TextSecondary)
                Spacer(Modifier.height(12.dp))
                CodeField(code) { code = it }
                if (need == Need.Password) {
                    Spacer(Modifier.height(12.dp))
                    PasswordField(pw, "New password") { pw = it }
                    StrengthBar(AccountCrypto.strength(pw).first, AccountCrypto.strength(pw).second)
                }
                error?.let { Text(it, color = MeshColors.Danger, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && code.length == 6 && (need != Need.Password || AccountCrypto.strength(pw).first >= 2), onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        when (need) {
                            Need.TwoOff -> { acc.setTwoFactor(false, code); "2-step verification is off" }
                            Need.Password -> { acc.changePassword(pw, code); "Password changed" }
                            else -> { acc.deleteAccount(code); "Account deleted" }
                        }
                    }.onSuccess(onDone).onFailure { error = it.message }
                    busy = false
                }
            }) { Text(if (need == Need.Delete) "Delete" else "Confirm", color = if (need == Need.Delete) MeshColors.Danger else MeshColors.Accent) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Gives the ID already on this phone a username, email and password. */
@Composable
private fun LinkAccountDialog(onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as MeshronApp
    val acc = app.accounts
    val me by app.identityRepository.identity.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(me?.handle.orEmpty()) }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    com.xalvxnlab.meshron.ui.components.PageDialog(
        onDismissRequest = onClose,
        title = { Text("Create an account") },
        text = {
            Column {
                Text("Keeps your ID, chats and contacts. Adds sign-in with a password on new phones.", color = MeshColors.TextSecondary)
                Spacer(Modifier.height(12.dp))
                Field(user, { user = it.take(25) }, "@username", KeyboardType.Ascii)
                Spacer(Modifier.height(8.dp))
                Field(email, { email = it.trim() }, "Email", KeyboardType.Email)
                if (sent) {
                    Spacer(Modifier.height(8.dp)); CodeField(code) { code = it }
                    Spacer(Modifier.height(8.dp)); PasswordField(pw) { pw = it }
                    StrengthBar(AccountCrypto.strength(pw).first, AccountCrypto.strength(pw).second)
                }
                error?.let { Text(it, color = MeshColors.Danger, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching {
                        if (!sent) { acc.sendSignupCode(email); sent = true }
                        else { acc.signUp(me?.username.orEmpty(), user, email, pw, code, existing = true); onClose() }
                    }.onFailure { error = it.message }
                    busy = false
                }
            }) { Text(if (sent) "Create" else "Send code") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
