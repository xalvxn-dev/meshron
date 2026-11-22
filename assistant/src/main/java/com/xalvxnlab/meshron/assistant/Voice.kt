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
import java.util.Locale

/** Speaking (text to speech), shared by the screen, reminders and Sentry mode. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = mutableListOf<String>()
    var onSpeaking: ((Boolean) -> Unit)? = null

    fun say(c: Context, text: String) {
        val clean = text.replace(Regex("[*#_`]"), "").replace("•", ",").trim()
        if (clean.isEmpty()) return
        if (tts == null) {
            tts = TextToSpeech(c.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                tts?.setPitch(0.95f); tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { onSpeaking?.invoke(true) }
                    override fun onDone(id: String?) { onSpeaking?.invoke(false) }
                    @Deprecated("old API") override fun onError(id: String?) { onSpeaking?.invoke(false) }
                })
                synchronized(queued) { queued.forEach { speakNow(it) }; queued.clear() }
            }
        }
        if (ready) speakNow(clean) else synchronized(queued) { queued.add(clean) }
    }

    private fun speakNow(t: String) { tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime()) }
    fun stop() { tts?.stop(); onSpeaking?.invoke(false) }
}

/**
 * Listening (speech to text) with the phone's own recogniser. Once Meshron Assistant is the default
 * assistant, Android points "the default recogniser" at Meshron Assistant itself, so Google's (or
 * the on-device one) is asked for by name.
 */
class Listener(private val context: Context, private val onLevel: (Float) -> Unit, private val onPartial: (String) -> Unit, private val onDone: (String?, String?) -> Unit) {
    private var sr: SpeechRecognizer? = null

    private fun make(): SpeechRecognizer? {
        val google = ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        val hasGoogle = runCatching { context.packageManager.getServiceInfo(google, 0); true }.getOrDefault(false)
        return when {
            hasGoogle -> SpeechRecognizer.createSpeechRecognizer(context, google)
            android.os.Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
    }

    fun start() {
        stop()
        val r = make() ?: return onDone(null, "Speech recognition isn't available on this phone. Type instead.")
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
