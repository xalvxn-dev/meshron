package com.xalvxnlab.meshron.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kokoro voices that run on the phone (sherpa-onnx, Apache-2.0). The voice model (about 130 MB) is
 * downloaded once, only when the person picks a Kokoro voice, and kept in the app's own storage;
 * after that it speaks offline. Audio plays while it's still being generated, so it starts quickly.
 */
object Kokoro {
    /** On-phone Kokoro voices are paused (not offered) until they are finished and tested. */
    const val ENABLED = false
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"

    /** The named voices and their speaker numbers in kokoro-multi-lang-v1_0. */
    val VOICES = linkedMapOf(
        "af_heart" to 3, "af_bella" to 2, "am_michael" to 16, "am_fenrir" to 14,
        "am_puck" to 18, "bm_george" to 26, "bm_fable" to 25
    )

    private fun dir(c: Context) = File(c.filesDir, "kokoro")
    fun ready(c: Context) = File(dir(c), "ready").exists()

    /** 0..100 while downloading, -1 otherwise. */
    @Volatile var progress = -1; private set
    @Volatile var lastError: String? = null; private set
    private val downloading = Mutex()

    /** Downloads and unpacks the model (once). Returns true when it's ready. */
    suspend fun download(c: Context, onProgress: (Int) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (ready(c)) return@withContext true
        downloading.withLock {
            if (ready(c)) return@withLock true
            val tmp = File(c.filesDir, "kokoro-tmp").apply { deleteRecursively(); mkdirs() }
            try {
                lastError = null
                progress = 0; onProgress(0)
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true; conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: 132_000_000L
                var read = 0L
                val counting = object : java.io.FilterInputStream(conn.inputStream.buffered(1 shl 16)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                        if (n > 0) { read += n; val p = (read * 100 / total).toInt().coerceIn(0, 99); if (p != progress) { progress = p; onProgress(p) } }
                    }
                }
                TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        // Drop the archive's top folder ("kokoro-int8-multi-lang-v1_0/…").
                        val rel = e.name.substringAfter('/', "").trimStart('/')
                        if (rel.isEmpty() || rel.contains("..")) continue
                        val out = File(tmp, rel)
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                    }
                }
                if (tmp.listFiles().orEmpty().none { it.name.endsWith(".onnx") }) error("The voice download looks incomplete")
                val d = dir(c); d.deleteRecursively()
                if (!tmp.renameTo(d)) error("Couldn't save the voice")
                File(d, "ready").writeText("1")
                progress = -1; onProgress(100)
                true
            } catch (e: Exception) {
                lastError = e.message ?: "Download failed"
                android.util.Log.w("Kokoro", "voice download failed", e)
                tmp.deleteRecursively(); progress = -1
                false
            }
        }
    }

    fun delete(c: Context) { release(); dir(c).deleteRecursively() }

    /** Downloads in the background (after the person allowed it); the screens show [progress]. */
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    fun downloadInBackground(c: Context) {
        val app = c.applicationContext
        if (ready(app) || progress >= 0) return
        background.launch { download(app) }
    }

    // ---- Speaking ----
    private var tts: OfflineTts? = null
    private val speaking = Mutex()
    @Volatile private var stopFlag = false

    private fun engine(c: Context): OfflineTts {
        tts?.let { return it }
        val d = dir(c)
        val model = d.listFiles().orEmpty().first { it.name.endsWith(".onnx") }
        val lex = listOf("lexicon-us-en.txt", "lexicon-zh.txt").map { File(d, it) }.filter { it.exists() }.joinToString(",") { it.absolutePath }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.absolutePath,
                    voices = File(d, "voices.bin").absolutePath,
                    tokens = File(d, "tokens.txt").absolutePath,
                    dataDir = File(d, "espeak-ng-data").absolutePath,
                    lexicon = lex,
                    dictDir = File(d, "dict").takeIf { it.exists() }?.absolutePath.orEmpty(),
                ),
                numThreads = maxOf(2, Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(4),
            ),
        )
        return OfflineTts(config = config).also { tts = it }
    }

    fun release() { runCatching { tts?.release() }; tts = null }
    fun stop() { stopFlag = true }

    /** Speaks [text] with a voice from [VOICES]; returns false if Kokoro isn't ready or fails. */
    suspend fun speak(c: Context, text: String, voice: String): Boolean = withContext(Dispatchers.Default) {
        if (!ready(c)) return@withContext false
        speaking.withLock {
            stopFlag = false
            try {
                val t = engine(c)
                val rate = t.sampleRate()
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(min, rate * 4 / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track.play()
                var written = 0
                // Each piece is played as soon as it's made; returning 0 stops generating.
                t.generateWithCallback(text, VOICES[voice] ?: 3, 1.0f) { samples ->
                    if (stopFlag) return@generateWithCallback 0
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    written += samples.size
                    1
                }
                // Let the end of the speech play out, then close.
                val end = System.currentTimeMillis() + 3000
                while (!stopFlag && track.playbackHeadPosition < written && System.currentTimeMillis() < end) Thread.sleep(40)
                track.stop(); track.release()
                true
            } catch (e: Throwable) { lastError = e.message; false }
        }
    }
}
