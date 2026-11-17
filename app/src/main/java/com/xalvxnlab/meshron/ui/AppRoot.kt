package com.xalvxnlab.meshron.ui

import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import com.xalvxnlab.meshron.calls.CallState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xalvxnlab.meshron.MeshronApp
import com.xalvxnlab.meshron.local.LocalTransports
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.xalvxnlab.meshron.identity.Identity
import com.xalvxnlab.meshron.ui.call.CallScreen
import com.xalvxnlab.meshron.ui.call.CallMini
import androidx.compose.runtime.setValue
import com.xalvxnlab.meshron.ui.home.HomeScreen
import com.xalvxnlab.meshron.ui.onboarding.OnboardingFlow
import kotlinx.coroutines.flow.map

private sealed interface RootState {
    data object Loading : RootState
    data object NoIdentity : RootState
    class Ready(val identity: Identity) : RootState
}

@Composable
fun AppRoot(app: MeshronApp) {
    val repo = app.identityRepository
    val state by remember(repo) {
        repo.identity.map { if (it == null) RootState.NoIdentity else RootState.Ready(it) }
    }.collectAsState(initial = RootState.Loading)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Age and the Terms / Privacy Policy / Community Guidelines: agreed once (again when they change).
        val accepted by app.settings.legalAccepted.collectAsState(initial = -1)
        if (accepted in 0 until com.xalvxnlab.meshron.ui.legal.LEGAL_VERSION) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            com.xalvxnlab.meshron.ui.legal.LegalGate(onAccept = { scope.launch { app.settings.acceptLegal(com.xalvxnlab.meshron.ui.legal.LEGAL_VERSION) } })
            return@Surface
        }
        if (accepted < 0) return@Surface
        when (val s = state) {
            RootState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            // Anonymous mode makes its identity by itself: a random nickname, nothing about you.
            RootState.NoIdentity -> if (com.xalvxnlab.meshron.identity.Mode.anon) {
                LaunchedEffect(Unit) { repo.create("Ghost " + (1000..9999).random()) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                // Public: sign up or sign in. Without internet, the old setup (recovery key) still works.
                var offline by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                if (offline) OnboardingFlow(repo) else com.xalvxnlab.meshron.ui.account.AuthFlow(offline = { offline = true })
            }
            is RootState.Ready -> {
                // App lock (a PIN set in Me): nothing shows until it's entered.
                val pin by app.settings.pinHash.collectAsState(initial = null)
                val isLocked by app.locked.collectAsState()
                if (pin != null && isLocked) {
                    com.xalvxnlab.meshron.ui.components.PinPad(
                        title = if (com.xalvxnlab.meshron.identity.Mode.anon) "Anonymous" else "Meshron",
                        subtitle = "Enter your PIN",
                        check = { app.settings.pinMatches(pin!!, it) },
                        onDone = { app.locked.value = false },
                    )
                    return@Surface
                }
                // Ask once for "Nearby devices" so the offline transports (same Wi-Fi, Bluetooth,
                // Wi-Fi Direct) can run. The app works without it, over the internet only.
                val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    app.localTransports.permissionsChanged()
                }
                LaunchedEffect(Unit) {
                    val ctx = app
                    if (!LocalTransports.hasNearbyPermissions(ctx) || !LocalTransports.hasLanPermission(ctx)) {
                        askLocal.launch(LocalTransports.allPermissions())
                    }
                }
                // Notifications while minimised or closed: allow notifications, and ask once to
                // keep Meshron out of battery saving (otherwise Android pauses it after a while).
                val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    askBatteryOnce(app)
                }
                LaunchedEffect(Unit) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                            app, android.Manifest.permission.POST_NOTIFICATIONS,
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else askBatteryOnce(app)
                }
                // Join the swarm once an identity exists (idempotent; also starts the network).
                LaunchedEffect(Unit) {
                    app.startMessaging()
                    app.applyBackgroundSetting()
                }
                // Profile photos: contacts' (as they share them) and my own, for every Avatar.
                val peerProfiles by app.profiles.peers.collectAsState()
                val myProfile by app.profiles.me.collectAsState()
                val myHex = remember(s.identity) { s.identity.publicKey.joinToString("") { "%02x".format(it) } }
                val photos: (String) -> androidx.compose.ui.graphics.ImageBitmap? = { key ->
                    if (key == myHex || key == "me") myProfile.photo else peerProfiles[key]?.photo
                }
                androidx.compose.runtime.CompositionLocalProvider(com.xalvxnlab.meshron.ui.components.LocalPhotos provides photos) {
                    HomeScreen(s.identity, repo, app.contactsRepository, app.networkService, app.messagingService, app.fileService)
                }
                // The call screen fades and slides in over everything. It can be minimised to a
                // floating window, so chats stay usable during a call.
                val callState by app.calls.state.collectAsState()
                var minimized by remember(callState.id) { androidx.compose.runtime.mutableStateOf(false) }
                val inCall = callState.phase != CallState.Phase.IDLE
                // Keep the screen awake for the whole call (video or voice), minimised or not.
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.DisposableEffect(inCall) {
                    view.keepScreenOn = inCall
                    onDispose { view.keepScreenOn = false }
                }
                val full = inCall && (!minimized || callState.phase == CallState.Phase.INCOMING)
                AnimatedVisibility(
                    visible = full,
                    enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 6 },
                    exit = fadeOut(tween(250)),
                ) {
                    CallScreen(
                        app.calls, app.networkService,
                        onMinimize = { minimized = true },
                        onChat = { key -> minimized = true; app.openChatRequests.tryEmit(key) },
                    )
                }
                if (inCall && !full) CallMini(app.calls, onExpand = { minimized = false })
            }
        }
    }
}

/** Asks Android (once) to let Meshron keep running, so messages arrive while it's minimised. */
@android.annotation.SuppressLint("BatteryLife")
private fun askBatteryOnce(app: MeshronApp) {
    val prefs = app.getSharedPreferences("meshron-ui", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean("askedBattery", false)) return
    val pm = app.getSystemService(android.os.PowerManager::class.java) ?: return
    if (pm.isIgnoringBatteryOptimizations(app.packageName)) return
    prefs.edit().putBoolean("askedBattery", true).apply()
    runCatching {
        app.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:" + app.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
