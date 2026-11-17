package com.xalvxnlab.meshron.reels

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import com.xalvxnlab.meshron.calls.VideoEngine
import com.xalvxnlab.meshron.calls.VideoFormat
import com.xalvxnlab.meshron.calls.VoiceEngine
import com.xalvxnlab.meshron.network.NetworkService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.nio.ByteBuffer

/**
 * Going live and watching lives. The worklet (live.js) carries frames, chat and hearts; this
 * class runs the camera/mic (host) or the decoder/speaker (viewer) with the call engines.
 * One live at a time: hosting and watching don't mix.
 */
class LiveService(private val context: Context, private val net: NetworkService, private val reels: ReelsService) {
    data class Chat(val from: String, val name: String, val text: String, val joined: Boolean = false)

    /** What's on screen: hosting my live, or watching [host]'s. */
    data class Session(val hosting: Boolean, val host: String, val id: String, val title: String, val ended: Boolean = false, val full: Boolean = false, val screen: Boolean = false)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _viewers = MutableStateFlow(0)
    val viewers: StateFlow<Int> = _viewers.asStateFlow()
    private val _chat = MutableStateFlow<List<Chat>>(emptyList())
    val chat: StateFlow<List<Chat>> = _chat.asStateFlow()
    private val _hearts = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val hearts: SharedFlow<Int> = _hearts.asSharedFlow()

    private var video: VideoEngine? = null
    private var voice: VoiceEngine? = null
    val localFormat: StateFlow<VideoFormat?>? get() = video?.local
    val remoteFormat: StateFlow<VideoFormat?>? get() = video?.remote

    init { net.onLiveEvent = ::onEvent }

    // ---- Hosting ----

    /** Starts my live (needs camera + microphone permission). */
    suspend fun start(title: String) {
        stopAll()
        val id = java.util.UUID.randomUUID().toString().take(12)
        _chat.value = emptyList(); _viewers.value = 0
        _session.value = Session(true, "", id, title.trim().take(100))
        lateinit var v: VideoEngine
        // Fan-out has no single receiver to ack frames: frames count as delivered when handed on
        // (the worklet skips video for viewers who fall behind).
        v = VideoEngine(context, send = { f -> v.onAck(ByteBuffer.wrap(f, 0, 4).int.toLong()); net.liveFrame("v", f) }, sendCtl = { _, _ -> }, lowBandwidth = false)
        video = v
        voice = VoiceEngine(send = { net.liveFrame("a", it) }).also { it.start() }
        v.startCamera()
        net.liveSend(JSONObject().put("cmd", "live-start").put("id", id).put("title", title))
        runCatching { reels.setLive(true, id, title) }
    }

    /** Starts my live from the screen (games, apps); [p] comes from ScreenLiveService. */
    suspend fun startScreen(title: String, p: android.media.projection.MediaProjection) {
        stopAll()
        val id = java.util.UUID.randomUUID().toString().take(12)
        _chat.value = emptyList(); _viewers.value = 0
        _session.value = Session(true, "", id, title.trim().take(100), screen = true)
        lateinit var v: VideoEngine
        v = VideoEngine(context, send = { f -> v.onAck(ByteBuffer.wrap(f, 0, 4).int.toLong()); net.liveFrame("v", f) }, sendCtl = { _, _ -> }, lowBandwidth = false)
        video = v
        voice = VoiceEngine({ net.liveFrame("a", it) }, gameAudio = p, bitrate = VoiceEngine.LIVE_BITRATE).also { it.start() }
        val m = context.resources.displayMetrics
        v.startScreen(p, m.widthPixels, m.heightPixels, m.densityDpi)
        net.liveSend(JSONObject().put("cmd", "live-start").put("id", id).put("title", title))
        runCatching { reels.setLive(true, id, title, screen = true) }
    }

    /** Called when the screen rotates during a screen live. */
    fun screenRotated() {
        if (_session.value?.screen != true) return
        val m = context.resources.displayMetrics
        video?.resizeScreen(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    /** Game sound volume in a screen live (0..1). */
    fun setGameVolume(v: Float) { voice?.gameVolume = v.coerceIn(0f, 1f) }

    fun setPreview(texture: SurfaceTexture?) { video?.setPreview(texture) }
    fun switchCamera() { video?.switchCamera() }
    fun setMuted(m: Boolean) { voice?.muted = m }

    suspend fun stop() {
        val s = _session.value ?: return
        if (s.hosting) {
            net.liveSend(JSONObject().put("cmd", "live-stop"))
            runCatching { reels.setLive(false, s.id, s.title) }
            if (s.screen) com.xalvxnlab.meshron.background.ScreenLiveService.stop(context)
        } else net.liveSend(JSONObject().put("cmd", "live-leave").put("host", s.host))
        stopAll()
        _session.value = null
    }

    // ---- Watching ----

    fun watch(host: String, id: String, title: String, myName: String) {
        stopAll()
        _chat.value = emptyList(); _viewers.value = 0
        _session.value = Session(false, host, id, title)
        video = VideoEngine(context, send = {}, sendCtl = { type, _ ->
            if (type == "keyreq") net.liveSend(JSONObject().put("cmd", "live-keyreq").put("host", host))
        }, lowBandwidth = false)
        voice = VoiceEngine(send = {}).also { it.startListening() }
        net.liveSend(JSONObject().put("cmd", "live-watch").put("host", host).put("id", id).put("name", myName))
    }

    fun setRemoteSurface(s: Surface?) { video?.setRemoteSurface(s) }

    // ---- Both ----

    fun sendChat(text: String) {
        val s = _session.value ?: return
        val t = text.trim().take(200)
        if (t.isEmpty()) return
        net.liveSend(JSONObject().put("cmd", "live-chat").put("host", if (s.hosting) "" else s.host).put("text", t))
        if (!s.hosting) _chat.update { (it + Chat("", "You", t)).takeLast(200) }
    }

    fun heart() {
        val s = _session.value ?: return
        net.liveSend(JSONObject().put("cmd", "live-heart").put("host", if (s.hosting) "" else s.host))
        _hearts.tryEmit(1)
    }

    private fun stopAll() {
        voice?.stop(); voice = null
        video?.release(); video = null
    }

    private fun onEvent(e: JSONObject) {
        val s = _session.value ?: return
        val host = e.optString("host")
        // Viewer events must be about the live I'm watching.
        if (!s.hosting && host.isNotEmpty() && host != s.host) return
        when (e.optString("ev")) {
            "live-viewers" -> _viewers.value = e.optInt("n")
            "live-count" -> _viewers.value = e.optInt("viewers")
            "live-key" -> video?.onKeyRequest()
            "live-welcome" -> { _viewers.value = e.optInt("viewers"); _session.update { it?.copy(title = e.optString("title").ifEmpty { it.title }) } }
            "live-video" -> video?.receive(android.util.Base64.decode(e.optString("data"), android.util.Base64.DEFAULT))
            "live-audio" -> voice?.receive(android.util.Base64.decode(e.optString("data"), android.util.Base64.DEFAULT))
            "live-chat" -> {
                // My own messages come back from the host too; I already show them.
                if (!s.hosting && e.optString("from") == net.myKeyHex) return
                val c = Chat(e.optString("from"), e.optString("name").ifEmpty { "Someone" }, e.optString("text"), e.optBoolean("joined"))
                _chat.update { (it + c).takeLast(200) }
            }
            "live-hearts" -> _hearts.tryEmit(e.optInt("n").coerceIn(1, 20))
            "live-ended" -> { _session.update { it?.copy(ended = true, full = e.optBoolean("full")) }; stopAll() }
        }
    }
}
