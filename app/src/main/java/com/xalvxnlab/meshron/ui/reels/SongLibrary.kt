package com.xalvxnlab.meshron.ui.reels

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xalvxnlab.meshron.BuildConfig
import com.xalvxnlab.meshron.reels.MusicTrack
import com.xalvxnlab.meshron.reels.ReelsService
import com.xalvxnlab.meshron.ui.components.FilterChips
import com.xalvxnlab.meshron.ui.theme.MeshColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private class Song(val id: String, val title: String, val artist: String, val previewUrl: String, val downloadUrl: String, val image: String, val license: String)

/**
 * Pick music for a reel: sounds people posted with, free Creative Commons songs (Jamendo),
 * or a song file on this phone. [onPick] gets a local file ready to mix in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongLibrary(reels: ReelsService, onPick: (MusicTrack) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("reels") }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sounds by remember { mutableStateOf<List<ReelsService.SoundUse>>(emptyList()) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    fun preview(id: String, source: String) {
        runCatching {
            player.reset()
            if (playing == id) { playing = null; return }
            player.setDataSource(source); player.setOnPreparedListener { it.start() }; player.prepareAsync(); playing = id
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = File(context.cacheDir, "music-${System.currentTimeMillis()}")
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()?.substringBeforeLast('.') ?: "My sound"
            onPick(MusicTrack(f, name.take(60)))
        }
    }

    LaunchedEffect(tab, q) {
        error = null
        delay(if (q.isEmpty()) 0 else 400)
        busy = true
        when (tab) {
            "reels" -> sounds = runCatching { reels.sounds(q) }.onFailure { error = it.message }.getOrDefault(emptyList())
            "songs" -> songs = if (BuildConfig.JAMENDO_ID.isEmpty()) { error = "The song library needs a free Jamendo key (see Settings → About)."; emptyList() }
            else runCatching { jamendo(q) }.onFailure { error = "Couldn't load songs. Check your internet." }.getOrDefault(emptyList())
        }
        busy = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MeshColors.Surface1) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Text("Sounds", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, bottom = 10.dp))
            TextField(
                value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Search sounds and songs", maxLines = 1) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(focusedContainerColor = MeshColors.Surface2, unfocusedContainerColor = MeshColors.Surface2, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Spacer(Modifier.padding(5.dp))
            FilterChips(listOf("reels" to "From reels", "songs" to "Song library", "phone" to "On my phone"), tab, { tab = it; if (it == "phone") pickFile.launch(arrayOf("audio/*")) })
            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp)) {
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                LazyColumn(Modifier.fillMaxWidth()) {
                    error?.let { item { Text(it, color = MeshColors.TextSecondary, modifier = Modifier.padding(20.dp)) } }
                    if (tab == "reels") {
                        if (!busy && sounds.isEmpty() && error == null) item { Text("No sounds yet. Sounds from reels people post show up here.", color = MeshColors.TextSecondary, modifier = Modifier.padding(20.dp)) }
                        items(sounds, key = { it.sound.ch + it.sound.id }) { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    // Use it: the reel's video carries the sound.
                                    busy = true
                                    scope.launch {
                                        val item = runCatching { reels.feed("sound", sound = s.sound, limit = 200).first.find { it.ch == s.sound.ch && it.id == s.sound.id } }.getOrNull()
                                        val file = item?.let { runCatching { reels.load(it.ch, it.id) }.getOrNull() }
                                        busy = false
                                        if (file != null) onPick(MusicTrack(file, s.sound.title, sound = s.sound)) else error = "That sound isn't reachable right now."
                                    }
                                }.padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MeshColors.Surface3), contentAlignment = Alignment.Center) {
                                    val img = remember(s.thumb) { reels.image(s.thumb) }
                                    if (img != null) Image(img, null, Modifier.size(52.dp), contentScale = ContentScale.Crop) else Icon(Icons.Rounded.MusicNote, null)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.sound.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${s.uses} reel${if (s.uses == 1) "" else "s"}", color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (tab == "songs") items(songs, key = { it.id }) { s ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { preview(s.id, s.previewUrl) }) {
                                Icon(if (playing == s.id) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Preview")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist + " · " + s.license, color = MeshColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            Box(
                                Modifier.clip(RoundedCornerShape(50)).background(MeshColors.TextPrimary).clickable {
                                    busy = true
                                    scope.launch {
                                        val f = File(context.cacheDir, "song-${s.id}.mp3")
                                        val ok = runCatching { withContext(Dispatchers.IO) { if (!f.exists()) download(s.downloadUrl, f) } }.isSuccess
                                        busy = false
                                        player.reset(); playing = null
                                        if (ok) onPick(MusicTrack(f, "${s.title} – ${s.artist}".take(60))) else error = "Couldn't download that song."
                                    }
                                }.padding(horizontal = 14.dp, vertical = 7.dp),
                            ) { Text("Use", color = MeshColors.Bg, fontWeight = FontWeight.Bold) }
                        }
                    }
                    if (tab == "phone") item {
                        Row(Modifier.fillMaxWidth().clickable { pickFile.launch(arrayOf("audio/*")) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LibraryMusic, null, tint = MeshColors.Accent)
                            Text("  Choose a song file from this phone", style = MaterialTheme.typography.titleSmall)
                        }
                        Text("Only use music you have the right to share.", color = MeshColors.TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
}

/** Free Creative Commons songs from Jamendo (popular, or matching [q]). */
private suspend fun jamendo(q: String): List<Song> = withContext(Dispatchers.IO) {
    val url = "https://api.jamendo.com/v3.0/tracks/?client_id=${BuildConfig.JAMENDO_ID}&format=json&limit=40&audioformat=mp32&include=licenses" +
        (if (q.isBlank()) "&order=popularity_week" else "&search=" + URLEncoder.encode(q, "UTF-8"))
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 12_000; c.readTimeout = 15_000
    val arr = c.inputStream.use { JSONObject(it.readBytes().decodeToString()) }.getJSONArray("results")
    List(arr.length()) { arr.getJSONObject(it) }.filter { it.optBoolean("audiodownload_allowed", true) && it.optString("audiodownload").isNotEmpty() }.map { o ->
        val cc = o.optString("license_ccurl").substringAfter("licenses/").substringBefore('/').uppercase().ifBlank { "CC" }
        Song(o.optString("id"), o.optString("name"), o.optString("artist_name"), o.optString("audio"), o.optString("audiodownload"), o.optString("image"), "CC $cc")
    }
}

private fun download(url: String, out: File) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 12_000; c.readTimeout = 30_000
    c.inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }
}
