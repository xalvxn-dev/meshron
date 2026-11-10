package com.xalvxnlab.meshron.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.xalvxnlab.meshron.background.CallService
import com.xalvxnlab.meshron.background.Notifier
import com.xalvxnlab.meshron.contacts.Contact
import com.xalvxnlab.meshron.contacts.ContactsRepository
import com.xalvxnlab.meshron.network.NetworkService
import com.xalvxnlab.meshron.network.PeerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.SecureRandom

data class CallState(
    val phase: Phase = Phase.IDLE,
    val peer: String = "",
    val name: String = "",
    val id: String = "",
    /** When the call was answered (ms), for the timer. */
    val startedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    /** Shown briefly after the call ends ("Call ended", "No answer", ...). */
    val message: String? = null,
    /** True while the connection is gone and we wait for it to come back. */
    val reconnecting: Boolean = false,
    /** A video call was asked for (offer had video, or we started one). */
    val videoCall: Boolean = false,
    /** My camera is on. */
    val video: Boolean = false,
    /** Their camera is on. */
    val remoteVideo: Boolean = false,
    /** My video can't get through (link too slow); voice continues. */
    val videoPoor: Boolean = false,
    val frontCamera: Boolean = true,
) {
    enum class Phase { IDLE, OUTGOING, INCOMING, ACTIVE, ENDED }
}

/**
 * One voice call at a time, with added contacts only, over whatever live connection the
 * worklet has to them (internet, same Wi-Fi, Bluetooth / Wi-Fi Direct). Signalling and audio
 * travel inside the same encrypted connection as messages; nothing goes through a server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManager(
    private val context: Context,
    private val network: NetworkService,
    private val contacts: ContactsRepository,
    private val notifier: Notifier,
    private val inForeground: () -> Boolean,
) {
    // All state changes run on one thread, so signals and button taps never race.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val audio = context.getSystemService(AudioManager::class.java)
    private var engine: VoiceEngine? = null
    /** Present during an answered call; null otherwise. The call screen draws from it. */
    private val _videoEngine = MutableStateFlow<VideoEngine?>(null)
    val videoEngineFlow: StateFlow<VideoEngine?> = _videoEngine.asStateFlow()
    private var videoEngine: VideoEngine?
        get() = _videoEngine.value
        set(v) { _videoEngine.value = v }
    private var poorJob: Job? = null
    private var timeout: Job? = null
    private var watchdog: Job? = null
    private var ringtone: Ringtone? = null
    private var ringback: ToneGenerator? = null
    private var ringbackJob: Job? = null
    private var focus: AudioFocusRequest? = null
    private var proximity: PowerManager.WakeLock? = null

    fun start() {
        network.onCallSignal = { key, msg ->
            val type = msg.optString("t")
            val id = msg.optString("id")
            // Frame acks are frequent and need no state change: handle them right here.
            when {
                type == "vack" && id == _state.value.id && key == _state.value.peer -> videoEngine?.onAck(msg.optLong("seq", -1))
                type == "keyreq" && id == _state.value.id && key == _state.value.peer -> videoEngine?.onKeyRequest()
                else -> scope.launch { onSignal(key, type, id, msg.optString("reason").ifEmpty { null }, msg) }
            }
        }
        network.onCallVideo = { key, data ->
            val s = _state.value
            if (s.phase == CallState.Phase.ACTIVE && s.peer == key) videoEngine?.receive(data)
        }
        network.onCallAudio = { key, data ->
            val s = _state.value
            if (s.phase == CallState.Phase.ACTIVE && s.peer == key) engine?.receive(data)
        }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun call(peer: String, video: Boolean = false) = scope.launch {
        if (_state.value.phase != CallState.Phase.IDLE) return@launch
        val name = nameOf(peer)
        when {
            !VoiceEngine.supported() -> return@launch end(peer, name, "This phone can't make calls (no Opus codec)")
            !hasMicPermission() -> return@launch end(peer, name, "Meshron needs the microphone for calls")
            !network.isConnected(peer) -> return@launch end(peer, name, "Not connected. Calls need you both online or nearby.")
        }
        val id = newId()
        val withVideo = video && hasCameraPermission()
        _state.value = CallState(CallState.Phase.OUTGOING, peer, name, id, videoCall = withVideo, video = withVideo)
        network.callSend(peer, "offer", id, extra = mapOf("video" to withVideo))
        startRingback()
        timeout = scope.launch {
            delay(RING_MS)
            if (_state.value.id == id && _state.value.phase == CallState.Phase.OUTGOING) {
                network.callSend(peer, "end", id)
                finish("No answer")
            }
        }
        watch(peer, id)
    }

    /** [video]: answer with my camera on. */
    fun accept(video: Boolean = _state.value.videoCall) = scope.launch {
        val s = _state.value
        if (s.phase != CallState.Phase.INCOMING) return@launch
        if (!hasMicPermission()) return@launch
        _state.value = s.copy(video = video && hasCameraPermission())
        network.callSend(s.peer, "answer", s.id)
        begin()
    }

    /** Camera on/off during a call (or before it's answered). Needs CAMERA permission to turn on. */
    fun toggleVideo() = scope.launch {
        val s = _state.value
        if (s.phase != CallState.Phase.ACTIVE && s.phase != CallState.Phase.OUTGOING) return@launch
        val on = !s.video
        if (on && !hasCameraPermission()) return@launch
        _state.value = s.copy(video = on, videoPoor = false)
        if (s.phase == CallState.Phase.ACTIVE) applyVideo()
    }

    fun switchCamera() = scope.launch {
        val v = videoEngine ?: return@launch
        v.switchCamera()
        _state.value = _state.value.copy(frontCamera = !_state.value.frontCamera)
    }

    /** Starts or stops my camera to match the state, and tells the other side. */
    private fun applyVideo() {
        val s = _state.value
        val v = videoEngine ?: return
        if (s.video) {
            v.startCamera(s.frontCamera)
            // Video calls are held in front of you, not at the ear.
            if (!s.speaker) {
                setSpeaker(true)
                proximity?.takeIf { it.isHeld }?.release()
                _state.value = _state.value.copy(speaker = true)
            }
            // Keep the camera allowed while the app is in the background.
            runCatching { CallService.start(context) }
        } else {
            v.stopCamera()
        }
        network.callSend(s.peer, "video", s.id, extra = mapOf("on" to s.video))
    }

    fun decline() = scope.launch {
        val s = _state.value
        if (s.phase != CallState.Phase.INCOMING) return@launch
        network.callSend(s.peer, "reject", s.id, "declined")
        finish("Declined")
    }

    fun hangUp() = scope.launch {
        val s = _state.value
        if (s.phase == CallState.Phase.OUTGOING || s.phase == CallState.Phase.ACTIVE) {
            network.callSend(s.peer, "end", s.id)
            finish("Call ended")
        } else if (s.phase == CallState.Phase.INCOMING) {
            network.callSend(s.peer, "reject", s.id, "declined")
            finish("Declined")
        }
    }

    fun toggleMute() = scope.launch {
        val m = !_state.value.muted
        engine?.muted = m
        _state.value = _state.value.copy(muted = m)
    }

    fun toggleSpeaker() = scope.launch {
        val on = !_state.value.speaker
        setSpeaker(on)
        _state.value = _state.value.copy(speaker = on)
        if (on) proximity?.takeIf { it.isHeld }?.release() else holdProximity()
    }

    private suspend fun onSignal(key: String, type: String, id: String, reason: String?, msg: org.json.JSONObject) {
        val s = _state.value
        when (type) {
            "offer" -> {
                if (s.phase != CallState.Phase.IDLE && s.phase != CallState.Phase.ENDED) {
                    if (s.id != id) network.callSend(key, "reject", id, "busy")
                    return
                }
                val contact = contacts.contacts.first().find { it.keyHex == key }
                if (contact?.status != Contact.Status.ADDED) return
                val name = nameOf(key)
                val videoCall = msg.optBoolean("video", false)
                _state.value = CallState(CallState.Phase.INCOMING, key, name, id, videoCall = videoCall, remoteVideo = videoCall)
                startRinging()
                if (!inForeground()) notifier.incomingCall(name, videoCall)
                timeout = scope.launch {
                    delay(RING_MS)
                    if (_state.value.id == id && _state.value.phase == CallState.Phase.INCOMING) finish("Missed call")
                }
                watch(key, id)
            }
            "answer" -> if (s.phase == CallState.Phase.OUTGOING && s.id == id && s.peer == key) begin()
            "video" -> if (s.id == id && s.peer == key && s.phase == CallState.Phase.ACTIVE) {
                val on = msg.optBoolean("on", false)
                if (!on) videoEngine?.remoteStopped()
                _state.value = _state.value.copy(remoteVideo = on)
            }
            "reject" -> if (s.id == id && s.peer == key && s.phase == CallState.Phase.OUTGOING) {
                finish(if (reason == "busy") "${s.name} is on another call" else "Declined")
            }
            "end" -> if (s.id == id && s.peer == key && s.phase != CallState.Phase.ENDED && s.phase != CallState.Phase.IDLE) {
                finish(if (s.phase == CallState.Phase.INCOMING) "Missed call" else "Call ended")
            }
            "unreachable" -> if (s.id == id && s.phase != CallState.Phase.IDLE && s.phase != CallState.Phase.ENDED) {
                finish("Couldn't reach ${s.name}")
            }
        }
    }

    /** The call is answered: start audio on both sides. */
    private fun begin() {
        timeout?.cancel()
        stopRinging()
        notifier.cancelCall()
        _state.value = _state.value.copy(phase = CallState.Phase.ACTIVE, startedAt = System.currentTimeMillis())
        val peer = _state.value.peer
        runCatching { CallService.start(context) }.onFailure { Log.w(TAG, "Call service: ${it.message}") }
        audioOn()
        engine = VoiceEngine { packet -> network.callAudio(peer, packet) }.also { it.start() }
        val id = _state.value.id
        val lowBandwidth = network.state.value.peers[peer]?.via == "nearby"
        val v = VideoEngine(
            context,
            send = { frame -> network.callVideo(peer, frame) },
            sendCtl = { type, extra -> network.callSend(peer, type, id, extra = extra) },
            lowBandwidth = lowBandwidth,
        )
        videoEngine = v
        poorJob = scope.launch { v.poor.collect { p -> _state.value = _state.value.copy(videoPoor = p) } }
        // The caller's offer said whether they send video; the answerer tells them now.
        applyVideo()
        Log.i(TAG, "Call with ${peer.take(8)}… started")
    }

    /** Ends the call if the connection is gone for too long (it may come back another way). */
    private fun watch(peer: String, id: String) {
        watchdog?.cancel()
        watchdog = scope.launch {
            var lostSince = 0L
            while (_state.value.id == id && _state.value.phase != CallState.Phase.ENDED && _state.value.phase != CallState.Phase.IDLE) {
                val up = network.state.value.peers[peer]?.state == PeerInfo.State.CONNECTED
                val now = System.currentTimeMillis()
                if (up) {
                    lostSince = 0
                    if (_state.value.reconnecting) _state.value = _state.value.copy(reconnecting = false)
                } else {
                    if (lostSince == 0L) lostSince = now
                    val limit = if (_state.value.phase == CallState.Phase.ACTIVE) RECONNECT_MS else 3_000L
                    if (now - lostSince > limit) return@launch finish("Connection lost")
                    if (!_state.value.reconnecting) _state.value = _state.value.copy(reconnecting = true)
                }
                delay(500)
            }
        }
    }

    private fun end(peer: String, name: String, message: String) {
        _state.value = CallState(CallState.Phase.ENDED, peer, name, message = message)
        scope.launch { delay(ENDED_MS); if (_state.value.phase == CallState.Phase.ENDED) _state.value = CallState() }
    }

    private fun finish(message: String) {
        timeout?.cancel()
        watchdog?.cancel()
        stopRinging()
        notifier.cancelCall()
        engine?.stop()
        engine = null
        poorJob?.cancel()
        poorJob = null
        videoEngine?.release()
        videoEngine = null
        audioOff()
        runCatching { CallService.stop(context) }
        val s = _state.value
        if (message == "Missed call" && !inForeground()) notifier.missedCall(s.name)
        Log.i(TAG, "Call with ${s.peer.take(8)}… ended: $message")
        end(s.peer, s.name, message)
    }

    // ---- Audio routing, ringing ----------------------------------------------------

    private fun audioOn() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            ).build()
        focus = req
        audio.requestAudioFocus(req)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(false)
        holdProximity()
    }

    private fun audioOff() {
        setSpeaker(false)
        audio.mode = AudioManager.MODE_NORMAL
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        proximity?.takeIf { it.isHeld }?.release()
        proximity = null
    }

    private fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audio.setCommunicationDevice(it) }
            } else audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = on
        }
    }

    /** Turns the screen off while the phone is held to the ear (no cheek taps). */
    private fun holdProximity() {
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        val lock = proximity ?: pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "meshron:call").also { proximity = it }
        if (!lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
    }

    private fun startRinging() {
        runCatching {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                isLooping = true
                play()
            }
        }
    }

    /** The caller hears a ring tone while waiting. */
    private fun startRingback() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
            ringback = tone
            ringbackJob = scope.launch {
                while (true) {
                    tone.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1500)
                    delay(4000)
                }
            }
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        ringbackJob?.cancel()
        ringbackJob = null
        ringback?.release()
        ringback = null
    }

    private suspend fun nameOf(key: String): String =
        contacts.contacts.first().find { it.keyHex == key }?.displayName
            ?: network.state.value.peers[key]?.announcedName?.ifBlank { null }
            ?: (key.take(8) + "…")

    private fun newId() = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "CallManager"
        const val RING_MS = 45_000L
        const val RECONNECT_MS = 15_000L
        const val ENDED_MS = 2_500L
    }
}
