package com.xalvxnlab.meshron.calls

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Microphone -> Opus -> [send], and received packets -> Opus decoder -> speaker.
 * Uses Android's built-in Opus codec (Android 10+), 16 kHz mono, 24 kbit/s.
 *
 * Packet: [seq u16][frame count u8] then per frame [length u16][Opus bytes]. Two 20 ms
 * frames per packet, so 25 packets a second (~7 KB/s), small enough for Bluetooth.
 * The links underneath are reliable, so a slow link means late audio, not lost audio; the
 * player drops packets when it falls too far behind to keep the delay short.
 */
class VoiceEngine(private val send: (ByteArray) -> Unit) {
    @Volatile private var running = false
    @Volatile var muted = false
    private val incoming = LinkedBlockingDeque<ByteArray>()
    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    // Counters for the log, to check audio flows both ways.
    @Volatile private var sentPackets = 0
    @Volatile private var receivedPackets = 0
    @Volatile private var playedFrames = 0
    @Volatile private var droppedPackets = 0

    fun start() {
        if (running) return
        running = true
        recordThread = thread(name = "call-mic", isDaemon = true) { runCatching { record() }.onFailure { Log.e(TAG, "mic", it) } }
        playThread = thread(name = "call-play", isDaemon = true) { runCatching { play() }.onFailure { Log.e(TAG, "play", it) } }
    }

    fun stop() {
        running = false
        recordThread?.join(1000)
        playThread?.join(1000)
        recordThread = null
        playThread = null
        incoming.clear()
        Log.i(TAG, "Call audio stopped: sent $sentPackets, received $receivedPackets, played $playedFrames frames, dropped $droppedPackets")
    }

    fun receive(packet: ByteArray) {
        if (!running) return
        receivedPackets++
        incoming.offer(packet)
    }

    @SuppressLint("MissingPermission") // CallManager checks RECORD_AUDIO before starting
    private fun record() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 8),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Microphone unavailable")
            rec.release()
            return
        }
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId) else null,
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId) else null,
        ).onEach { runCatching { it.enabled = true } }

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 2)
        }
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        rec.startRecording()
        val pcm = ByteArray(FRAME_BYTES)
        val info = MediaCodec.BufferInfo()
        val frames = ArrayList<ByteArray>(FRAMES_PER_PACKET)
        var seq = 0
        var samples = 0L
        var lastLog = System.currentTimeMillis()
        try {
            while (running) {
                var got = 0
                while (got < FRAME_BYTES && running) {
                    val n = rec.read(pcm, got, FRAME_BYTES - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME_BYTES) continue
                if (muted) pcm.fill(0) // keep timing, send silence
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    enc.getInputBuffer(inIdx)!!.apply { clear(); put(pcm, 0, got) }
                    enc.queueInputBuffer(inIdx, 0, got, samples * 1_000_000 / RATE, 0)
                    samples += got / 2
                }
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(info, 0)
                    if (outIdx < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buf = enc.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        frames += ByteArray(info.size).also { buf.get(it) }
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (frames.size == FRAMES_PER_PACKET) {
                        send(pack(seq++, frames))
                        sentPackets++
                        frames.clear()
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastLog > 5000) {
                    lastLog = now
                    Log.i(TAG, "Call audio: sent $sentPackets, received $receivedPackets, played $playedFrames frames, dropped $droppedPackets")
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            effects.forEach { runCatching { it.release() } }
            runCatching { enc.stop() }
            enc.release()
        }
    }

    private fun play() {
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
            setByteBuffer("csd-0", opusHead())
            setByteBuffer("csd-1", longLe(0)) // codec delay (ns)
            setByteBuffer("csd-2", longLe(80_000_000)) // seek pre-roll (ns)
        }
        dec.configure(fmt, null, null, 0)
        dec.start()
        var track: AudioTrack? = null
        val info = MediaCodec.BufferInfo()
        val out = ByteArrayOutputStream()
        var pts = 0L
        try {
            // Wait for a little audio before starting, to smooth out jitter.
            while (running && incoming.size < 2) Thread.sleep(10)
            while (running) {
                val packet = incoming.poll(100, TimeUnit.MILLISECONDS) ?: continue
                // Too far behind (e.g. after a slow patch on Bluetooth): skip ahead.
                while (incoming.size > MAX_QUEUE) {
                    incoming.poll()
                    droppedPackets++
                }
                for (frame in unpack(packet)) {
                    val inIdx = dec.dequeueInputBuffer(20_000)
                    if (inIdx < 0) continue
                    dec.getInputBuffer(inIdx)!!.apply { clear(); put(frame) }
                    dec.queueInputBuffer(inIdx, 0, frame.size, pts, 0)
                    pts += FRAME_MS * 1000L
                    out.reset()
                    var tries = 0
                    while (tries < 3) {
                        val outIdx = dec.dequeueOutputBuffer(info, 5_000)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f = dec.outputFormat
                                track?.release()
                                track = newTrack(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                            }
                            outIdx >= 0 -> {
                                val buf = dec.getOutputBuffer(outIdx)!!
                                buf.position(info.offset)
                                val bytes = ByteArray(info.size).also { buf.get(it) }
                                dec.releaseOutputBuffer(outIdx, false)
                                out.write(bytes)
                                if (bytes.isNotEmpty()) break
                            }
                            else -> tries++
                        }
                    }
                    if (out.size() > 0) {
                        val t = track ?: newTrack(48_000, 1).also { track = it }
                        t.write(out.toByteArray(), 0, out.size()) // blocking: paces this thread
                        playedFrames++
                    }
                }
            }
        } finally {
            track?.let { runCatching { it.stop() }; it.release() }
            runCatching { dec.stop() }
            dec.release()
        }
    }

    private fun newTrack(rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(maxOf(min, rate / 10 * 2 * channels)) // ~100 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    companion object {
        private const val TAG = "VoiceEngine"
        private const val RATE = 16_000
        private const val BITRATE = 24_000
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = RATE / 1000 * FRAME_MS * 2 // 16-bit mono
        private const val FRAMES_PER_PACKET = 2
        private const val MAX_QUEUE = 6 // packets (~240 ms) before we skip ahead

        /** True if this phone has an Opus encoder and decoder. */
        fun supported(): Boolean {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1)
            return list.findEncoderForFormat(f) != null && list.findDecoderForFormat(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1)
            ) != null
        }

        fun pack(seq: Int, frames: List<ByteArray>): ByteArray {
            val size = 3 + frames.sumOf { 2 + it.size }
            val b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
            b.putShort(seq.toShort())
            b.put(frames.size.toByte())
            for (f in frames) {
                b.putShort(f.size.toShort())
                b.put(f)
            }
            return b.array()
        }

        /** Never throws on bad input: a malformed packet just yields fewer frames. */
        fun unpack(packet: ByteArray): List<ByteArray> {
            if (packet.size < 3) return emptyList()
            val b = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            b.short
            val count = b.get().toInt() and 0xff
            val frames = ArrayList<ByteArray>(count)
            repeat(minOf(count, 10)) {
                if (b.remaining() < 2) return frames
                val len = b.short.toInt() and 0xffff
                if (len == 0 || len > b.remaining()) return frames
                frames += ByteArray(len).also { b.get(it) }
            }
            return frames
        }

        private fun opusHead(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray())
            put(1) // version
            put(1) // channels
            putShort(0) // pre-skip
            putInt(RATE) // original input rate
            putShort(0) // output gain
            put(0) // mapping family
            flip()
        }

        private fun longLe(v: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }
    }
}
