package com.xalvxnlab.meshron.ui

import androidx.compose.foundation.layout.Box
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
        when (val s = state) {
            RootState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            RootState.NoIdentity -> OnboardingFlow(repo)
            is RootState.Ready -> {
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
                // Join the swarm once an identity exists (idempotent; also starts the network).
                LaunchedEffect(Unit) {
                    app.startMessaging()
                    app.applyBackgroundSetting()
                }
                HomeScreen(s.identity, repo, app.contactsRepository, app.networkService, app.messagingService, app.fileService)
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
