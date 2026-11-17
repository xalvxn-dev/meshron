package com.xalvxnlab.meshron.ui.reels

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.xalvxnlab.meshron.reels.Reel
import com.xalvxnlab.meshron.reels.ReelSound
import com.xalvxnlab.meshron.reels.ReelsService
import com.xalvxnlab.meshron.reels.VideoEditor
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val MAX_MS = 60_000L

/**
 * Film a reel: one or more clips (tap to start and stop), up to 60 s in all.
 * A chosen sound plays while you film, so you can move to it.
 */
@Composable
fun CameraScreen(reels: ReelsService, sound: ReelSound?, soundItem: Reel?, onClose: () -> Unit, onDone: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r[Manifest.permission.CAMERA] == true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onDone(uri) }

    var front by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var timer by remember { mutableIntStateOf(0) } // 0, 3, 10 seconds
    var countdown by remember { mutableIntStateOf(0) }
    val clips = remember { mutableStateListOf<Pair<File, Long>>() } // file, duration ms
    var recording by remember { mutableStateOf<Recording?>(null) }
    var currentMs by remember { mutableFloatStateOf(0f) }
    var joining by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val usedMs = clips.sumOf { it.second } + currentMs.toLong()
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val capture = remember {
        VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build(),
        )
    }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    // A chosen sound plays while filming (and its own audio replaces the mic later).
    var soundFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(soundItem) { if (soundItem != null) soundFile = runCatching { reels.load(soundItem.ch, soundItem.id) }.getOrNull() }
    val player = remember { MediaPlayer() }
    var playerReady by remember { mutableStateOf(false) }
    LaunchedEffect(soundFile) {
        val f = soundFile ?: return@LaunchedEffect
        runCatching { player.setDataSource(f.path); player.prepare(); playerReady = true }
    }
    DisposableEffect(Unit) { onDispose { runCatching { recording?.stop() }; player.release() } }

    LaunchedEffect(granted, front) {
        if (!granted) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        runCatching {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycle, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            camera?.cameraControl?.enableTorch(torch && !front)
        }.onFailure { error = "Camera unavailable: ${it.message}" }
    }
    LaunchedEffect(torch) { camera?.cameraControl?.enableTorch(torch && !front) }

    fun stop() { recording?.stop(); recording = null }
    fun start() {
        if (usedMs >= MAX_MS) return
        val file = File(context.cacheDir, "clip-${System.currentTimeMillis()}.mp4")
        val withMic = sound == null && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (playerReady) { player.seekTo((clips.sumOf { it.second }).toInt()); player.start() }
        recording = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .apply { if (withMic) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(context)) { ev ->
                when (ev) {
                    is VideoRecordEvent.Status -> {
                        currentMs = ev.recordingStats.recordedDurationNanos / 1e6f
                        if (clips.sumOf { it.second } + currentMs >= MAX_MS) stop()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (playerReady && player.isPlaying) player.pause()
                        val ms = ev.recordingStats.recordedDurationNanos / 1_000_000
                        currentMs = 0f
                        if (!ev.hasError() && ms > 300) clips += file to ms else file.delete()
                    }
                }
            }
    }
    fun finish() {
        if (clips.isEmpty() || joining) return
        joining = true
        scope.launch {
            runCatching {
                val out = File(context.cacheDir, "recorded-${System.currentTimeMillis()}.mp4")
                VideoEditor.join(context, clips.map { it.first }, out) {}
                onDone(Uri.fromFile(out))
            }.onFailure { error = it.message; joining = false }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView({ previewView }, Modifier.fillMaxSize())
        else Text("Meshron needs the camera to film.", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))

        // Progress across all clips, with a tick where each one ends.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth((usedMs.toFloat() / MAX_MS).coerceIn(0f, 1f)).height(5.dp).background(MeshColors.GradientH))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Round(Icons.Rounded.Close, "Close") { onClose() }
                Spacer(Modifier.weight(1f))
                if (sound != null) Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(" " + sound.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(170.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("%d:%02d".format(usedMs / 60000, (usedMs / 1000) % 60), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        // Side tools.
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Round(Icons.Rounded.FlipCameraAndroid, "Flip camera") { if (recording == null) front = !front }
            Round(if (torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Flash") { torch = !torch }
            Box { Round(Icons.Rounded.Timer, "Timer") { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
                if (timer > 0) Text("${timer}s", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.BottomCenter).background(MeshColors.AccentStrong, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp)) }
        }
        if (countdown > 0) Text("$countdown", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center))
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(14.dp)) }

        // Bottom: gallery / undo, the record button, done.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (clips.isEmpty()) Round(Icons.Rounded.PhotoLibrary, "Upload from gallery") {
                pickVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            } else Round(Icons.AutoMirrored.Rounded.Undo, "Remove last clip") { if (recording == null) clips.removeAt(clips.lastIndex).first.delete() }
            Spacer(Modifier.weight(1f))
            val on = recording != null
            val inner by animateFloatAsState(if (on) 0.42f else 0.78f, label = "rec")
            Box(
                Modifier.size(84.dp).border(5.dp, Color.White, CircleShape).padding(8.dp)
                    .semantics { contentDescription = if (on) "Stop" else "Record" }
                    .clickable(enabled = granted && countdown == 0 && !joining) {
                    if (on) stop()
                    else if (timer > 0) scope.launch { for (i in timer downTo 1) { countdown = i; delay(1000) }; countdown = 0; start() }
                    else start()
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize(inner).clip(RoundedCornerShape(if (on) 8.dp else 40.dp)).background(Color(0xFFFF3B5C)))
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (clips.isNotEmpty() && recording == null) Color.White else Color.White.copy(alpha = 0.2f))
                    .clickable(enabled = clips.isNotEmpty() && recording == null) { finish() },
                contentAlignment = Alignment.Center,
            ) {
                if (joining) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.Black)
                else Icon(Icons.Rounded.Check, "Next", tint = Color.Black)
            }
        }
    }
}

@Composable
private fun Round(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White)
    }
}
