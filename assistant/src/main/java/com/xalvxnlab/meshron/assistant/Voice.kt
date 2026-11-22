package com.xalvxnlab.meshron.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Speaking. Uses Google's speech engine when it's there (its neural voices sound like a person),
 * the voice the person picked, or else the most natural voice for their language.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private const val GOOGLE = "com.google.android.tts"
    /** Everyone who wants to know when it starts or stops talking (the app screen, the floating orb). */
    val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private fun tell(on: Boolean) = listeners.forEach { it(on) }
    @Volatile var talking = false; private set

    private fun init(c: Context): CompletableDeferred<Boolean> {
        ready?.let { return it }
        val d = CompletableDeferred<Boolean>()
        ready = d
        val app = c.applicationContext
        val hasGoogle = runCatching { app.packageManager.getPackageInfo(GOOGLE, 0); true }.getOrDefault(false)
        val onInit = TextToSpeech.OnInitListener { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts?.setPitch(1.0f); tts?.setSpeechRate(1.0f)
                pickVoice(app)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { talking = true; tell(true) }
                    override fun onDone(id: String?) { talking = false; tell(false) }
                    @Deprecated("old API") override fun onError(id: String?) { talking = false; tell(false) }
                    override fun onStop(id: String?, interrupted: Boolean) { talking = false; tell(false) }
                })
            }
            d.complete(ok)
        }
        tts = if (hasGoogle) TextToSpeech(app, onInit, GOOGLE) else TextToSpeech(app, onInit)
        return d
    }

    /** The voices for the person's language, most natural first. */
    suspend fun voices(c: Context): List<Voice> {
        if (!init(c).await()) return emptyList()
        val lang = Locale.getDefault()
        return runCatching { tts!!.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == lang.language && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.locale.country == lang.country }.thenByDescending { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    }

    private fun pickVoice(c: Context) {
        val t = tts ?: return
        val saved = Memory.voice(c)
        val lang = Locale.getDefault()
        val all = runCatching { t.voices }.getOrNull().orEmpty().filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val v = all.firstOrNull { it.name == saved }
            ?: all.filter { it.locale.language == lang.language }
                .sortedWith(compareByDescending<Voice> { it.locale.country == lang.country }.thenByDescending { it.quality }.thenBy { it.isNetworkConnectionRequired })
                .firstOrNull()
        if (v != null) t.voice = v
    }

    suspend fun useVoice(c: Context, v: Voice) { if (init(c).await()) { Memory.setVoice(c, v.name); tts?.voice = v } }

    /** "English (United States) · voice IOB · HD, online" */
    fun label(v: Voice): String {
        val code = Regex("-x-([a-z0-9]+)-").find(v.name)?.groupValues?.get(1)?.uppercase() ?: v.name.substringAfterLast('-').take(8)
        val q = when { v.quality >= Voice.QUALITY_VERY_HIGH -> "HD"; v.quality >= Voice.QUALITY_HIGH -> "High"; else -> "Basic" }
        return v.locale.displayCountry.ifBlank { v.locale.displayLanguage } + " · voice " + code + " · " + q + if (v.isNetworkConnectionRequired) ", online" else ", on device"
    }

    fun say(c: Context, text: String) {
        val clean = text.replace(Regex("[*#_`]"), "").replace("•", ",").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        if (Memory.studio(c) && System.currentTimeMillis() > studioOffUntil) {
            studioJob = scope.launch { studioLock.lock(); try { if (!studio(c, clean)) device(c, clean) } finally { studioLock.unlock() } }
            return
        }
        device(c, clean)
    }

    private fun device(c: Context, clean: String) {
        val d = init(c)
        if (d.isCompleted) speakNow(clean) else scope.launch { if (d.await()) speakNow(clean) }
    }

    // ---- The studio voice (lifelike, from the server) ----
    private var studioOffUntil = 0L
    private var studioJob: kotlinx.coroutines.Job? = null
    private val studioLock = kotlinx.coroutines.sync.Mutex()
    private var player: android.media.MediaPlayer? = null

    /** Fetches the reply as audio and plays it; false (and the phone's voice for 10 minutes) if it can't. */
    private suspend fun studio(c: Context, text: String): Boolean {
        val api = Api(c)
        if (!api.signedIn) return false
        val clips = runCatching { api.tts(text, Memory.studioVoice(c)).getJSONArray("audio") }.getOrElse {
            studioOffUntil = System.currentTimeMillis() + 10 * 60_000
            return false
        }
        talking = true; tell(true)
        try {
            for (i in 0 until clips.length()) {
                val f = java.io.File(c.cacheDir, "say$i.wav")
                f.writeBytes(android.util.Base64.decode(clips.getString(i), android.util.Base64.DEFAULT))
                playFile(f)
            }
        } finally { talking = false; tell(false) }
        return true
    }

    private suspend fun playFile(f: java.io.File) = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        val mp = android.media.MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { it.release(); if (player === it) player = null; if (cont.isActive) cont.resume(Unit) {} }
            mp.setOnErrorListener { p, _, _ -> p.release(); if (player === p) player = null; if (cont.isActive) cont.resume(Unit) {}; true }
            mp.prepare(); mp.start()
        }.onFailure { mp.release(); if (cont.isActive) cont.resume(Unit) {} }
        cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private fun speakNow(t: String) { tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime()) }
    fun stop() { studioJob?.cancel(); runCatching { player?.stop(); player?.release() }; player = null; tts?.stop(); talking = false; tell(false) }
}

/**
 * Listening (speech to text) with the phone's own recogniser. Once Meshron Assistant is the default
 * assistant, Android points "the default recogniser" at Meshron Assistant itself, so Google's (or
 * the on-device one) is asked for by name. [onDevice] prefers the on-device recogniser (quiet, no
 * internet), used for always-listening.
 */
class Listener(
    private val context: Context,
    private val onLevel: (Float) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onDone: (String?, String?) -> Unit,
    private val onDevice: Boolean = false
) {
    private var sr: SpeechRecognizer? = null

    private fun make(): SpeechRecognizer? {
        val google = ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        val hasGoogle = runCatching { context.packageManager.getServiceInfo(google, 0); true }.getOrDefault(false)
        val local = android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return when {
            onDevice && local -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            hasGoogle -> SpeechRecognizer.createSpeechRecognizer(context, google)
            local -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
    }

    fun start() {
        stop()
        val r = runCatching { make() }.getOrNull() ?: return onDone(null, "Speech recognition isn't available on this phone. Type instead.")
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onLevel(0f) }
            override fun onError(error: Int) {
                onLevel(0f)
                onDone(null, when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null // heard nothing: just stop
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need the microphone permission to listen."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Listening needs the internet on this phone. Type instead."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                    else -> "I couldn't hear that. Try again."
                })
            }
            override fun onResults(results: Bundle?) { onDone(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(), null) }
            override fun onPartialResults(partial: Bundle?) { partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }

    fun stop() { runCatching { sr?.destroy() }; sr = null }
}
