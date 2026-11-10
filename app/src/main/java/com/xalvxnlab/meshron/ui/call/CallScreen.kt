package com.xalvxnlab.meshron.ui.call

import android.Manifest
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xalvxnlab.meshron.calls.CallManager
import com.xalvxnlab.meshron.calls.CallState
import com.xalvxnlab.meshron.calls.VideoEngine
import com.xalvxnlab.meshron.calls.VideoFormat
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.ui.components.Avatar
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.delay

/** Full-screen call view, shown on top of everything while a call is ringing or running. */
@Composable
fun CallScreen(calls: CallManager, network: NetworkService) {
    val s by calls.state.collectAsState()
    if (s.phase == CallState.Phase.IDLE) return
    val net by network.state.collectAsState()
    val engine by calls.videoEngineFlow.collectAsState()
    BackHandler { /* stay on the call screen; use the buttons */ }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) {
        while (s.phase == CallState.Phase.ACTIVE) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // Answering: the mic is required; the camera only for a video answer.
    val askToAnswer = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true || calls.hasMicPermission()) {
            calls.accept(video = r[Manifest.permission.CAMERA] == true)
        }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) calls.toggleVideo()
    }
    fun answer(video: Boolean) {
        val need = buildList {
            if (!calls.hasMicPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (video && !calls.hasCameraPermission()) add(Manifest.permission.CAMERA)
        }
        if (need.isEmpty()) calls.accept(video) else askToAnswer.launch(need.toTypedArray())
    }

    val via = net.peers[s.peer]?.viaLabel
    val status = when (s.phase) {
        CallState.Phase.OUTGOING -> if (s.videoCall) "Video calling…" else "Calling…"
        CallState.Phase.INCOMING -> if (s.videoCall) "Incoming video call" else "Incoming voice call"
        CallState.Phase.ACTIVE -> if (s.reconnecting) "Reconnecting…" else formatTime((now - s.startedAt).coerceAtLeast(0))
        CallState.Phase.ENDED -> s.message ?: "Call ended"
        CallState.Phase.IDLE -> ""
    }
    val active = s.phase == CallState.Phase.ACTIVE
    val remoteFormat = engine?.remote?.collectAsState()?.value
    val localFormat = engine?.local?.collectAsState()?.value
    val showRemote = active && s.remoteVideo && engine != null
    val showLocal = active && s.video && engine != null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Box(Modifier.fillMaxSize()) {
            // ---- The other person, full screen ----
            if (showRemote) {
                val e = engine!!
                VideoView(
                    format = remoteFormat,
                    crop = true,
                    onTexture = { st -> e.setRemoteSurface(st?.let { Surface(it) }) },
                    modifier = Modifier.fillMaxSize(),
                )
                // Soft shade behind the buttons so they stay readable on bright video.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(360.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                )
                if (remoteFormat == null) {
                    Text(
                        "Waiting for video…",
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showRemote) {
                    // Compact header over the video.
                    Column(
                        Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(
                            status + (via?.takeIf { !s.reconnecting }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                } else {
                    Spacer(Modifier.height(32.dp))
                    RingingAvatar(s.name, s.peer, ringing = s.phase == CallState.Phase.INCOMING || s.phase == CallState.Phase.OUTGOING)
                    Spacer(Modifier.height(24.dp))
                    Text(s.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (s.phase == CallState.Phase.ENDED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (active && via != null && !s.reconnecting) {
                        Spacer(Modifier.height(4.dp))
                        Text("via $via", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.phase != CallState.Phase.ENDED) {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MeshColors.Online)
                            Spacer(Modifier.width(6.dp))
                            Text("End-to-end encrypted", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (active && s.video && s.videoPoor) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Your video is paused: the connection is too slow right now. Voice still works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.background(MeshColors.Waiting, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }

                Spacer(Modifier.weight(1f))
                val onVideo = showRemote
                when (s.phase) {
                    CallState.Phase.INCOMING -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            RoundButton(Icons.Filled.CallEnd, "Decline", MeshColors.Danger, Color.White, onVideo) { calls.decline() }
                            RoundButton(
                                if (s.videoCall) Icons.Filled.Videocam else Icons.Filled.Call, "Accept",
                                MeshColors.Accept, Color.White, onVideo,
                            ) { answer(s.videoCall) }
                        }
                        if (s.videoCall) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { answer(false) }) { Text("Answer without my camera") }
                        }
                    }
                    CallState.Phase.OUTGOING, CallState.Phase.ACTIVE -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (active) {
                            val off = MaterialTheme.colorScheme.surfaceContainerHighest
                            val on = MaterialTheme.colorScheme.onSurface
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                RoundButton(
                                    if (s.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (s.muted) "Unmute" else "Mute",
                                    if (s.muted) on else off, if (s.muted) MaterialTheme.colorScheme.surface else on, onVideo, 60.dp,
                                ) { calls.toggleMute() }
                                RoundButton(
                                    if (s.video) Icons.Filled.Videocam else Icons.Filled.VideocamOff, if (s.video) "Camera" else "Camera off",
                                    if (s.video) on else off, if (s.video) MaterialTheme.colorScheme.surface else on, onVideo, 60.dp,
                                ) { if (s.video || calls.hasCameraPermission()) calls.toggleVideo() else askCamera.launch(Manifest.permission.CAMERA) }
                                if (s.video) {
                                    RoundButton(Icons.Filled.Cameraswitch, "Flip", off, on, onVideo, 60.dp) { calls.switchCamera() }
                                }
                                RoundButton(
                                    Icons.AutoMirrored.Filled.VolumeUp, "Speaker",
                                    if (s.speaker) on else off, if (s.speaker) MaterialTheme.colorScheme.surface else on, onVideo, 60.dp,
                                ) { calls.toggleSpeaker() }
                            }
                            Spacer(Modifier.height(28.dp))
                        }
                        RoundButton(Icons.Filled.CallEnd, "Hang up", MeshColors.Danger, Color.White, onVideo) { calls.hangUp() }
                    }
                    else -> Unit
                }
                Spacer(Modifier.height(24.dp))
            }

            // ---- My camera, small in the corner ----
            if (showLocal) {
                val e = engine!!
                Box(
                    Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = if (showRemote) 96.dp else 16.dp, end = 16.dp)
                        .size(width = 108.dp, height = 152.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .clickable { calls.switchCamera() },
                ) {
                    VideoView(format = localFormat, crop = true, onTexture = { e.setPreview(it) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** A TextureView that reports its surface and keeps the picture upright and filled. */
@Composable
private fun VideoView(format: VideoFormat?, crop: Boolean, onTexture: (SurfaceTexture?) -> Unit, modifier: Modifier) {
    val holder = remember { arrayOfNulls<VideoFormat>(1) }
    holder[0] = format
    DisposableEffect(Unit) { onDispose { onTexture(null) } }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                fun apply() { holder[0]?.let { setTransform(VideoEngine.transform(width, height, it, crop)) } }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { onTexture(st); apply() }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = apply()
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { onTexture(null); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        update = { v -> format?.let { v.setTransform(VideoEngine.transform(v.width, v.height, it, crop)) } },
        modifier = modifier,
    )
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    bg: Color,
    fg: Color,
    overVideo: Boolean,
    size: Dp = 72.dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(size * 0.44f), tint = fg) }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (overVideo) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTime(ms: Long): String {
    val sec = ms / 1000
    return "%d:%02d".format(sec / 60, sec % 60)
}

/** The avatar, with soft purple rings growing out of it while the call rings. */
@Composable
private fun RingingAvatar(name: String, seed: String, ringing: Boolean) {
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        if (ringing) {
            val t = rememberInfiniteTransition(label = "ring")
            repeat(2) { i ->
                val p by t.animateFloat(
                    0f, 1f,
                    infiniteRepeatable(tween(1800, easing = LinearEasing), initialStartOffset = StartOffset(i * 900)),
                    label = "ring$i",
                )
                Box(
                    Modifier.size(120.dp).graphicsLayer {
                        scaleX = 1f + p * 0.65f; scaleY = 1f + p * 0.65f; alpha = (1f - p) * 0.5f
                    }.background(MeshColors.Purple, CircleShape)
                )
            }
        }
        Avatar(name, seed, 120.dp)
    }
}
