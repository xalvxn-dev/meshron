package com.xalvxnlab.meshron.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** How a video stream must be drawn: buffer size, clockwise rotation, mirrored or not. */
data class VideoFormat(val width: Int, val height: Int, val rotation: Int, val mirror: Boolean = false)

/**
 * Camera -> H.264 (Android's built-in encoder) -> [send], and received frames -> decoder ->
 * the remote video view.
 *
 * Frame: [seq u32][flags u8 (1 = key frame)][rotation/90 u8][width u16][height u16] + H.264 bytes.
 * Key frames carry the codec header (SPS/PPS) so a receiver can start from any key frame.
 *
 * The links underneath deliver everything in order, so a slow link would make video lag more
 * and more. Instead, the receiver acks every frame and the sender keeps at most a few frames
 * in flight: when the link can't keep up, frames are skipped, the bitrate goes down, and the
 * next frame sent is a key frame. If nothing gets through for a while, [poor] turns true
 * (the UI shows "video paused"). Voice is unaffected.
 */
class VideoEngine(
    private val context: Context,
    private val send: (ByteArray) -> Unit,
    private val sendCtl: (type: String, extra: Map<String, Any>) -> Unit,
    private val lowBandwidth: Boolean,
) {
    private val camThread = HandlerThread("call-camera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val camExecutor = Executor { camHandler.post(it) }

    // ---- Sending ------------------------------------------------------------------------
    @Volatile private var sending = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var drainThread: Thread? = null
    private var size = Size(640, 480)
    @Volatile private var sensorRotation = 0
    @Volatile var front = true
        private set

    private val _local = MutableStateFlow<VideoFormat?>(null)
    val local: StateFlow<VideoFormat?> = _local.asStateFlow()

    // Congestion control
    @Volatile private var seq = 0L
    @Volatile private var acked = -1L
    @Volatile private var needKey = true
    @Volatile private var lastAckAt = 0L
    @Volatile private var lastDropAt = 0L
    @Volatile private var bitrate = if (lowBandwidth) 250_000 else 800_000
    private val maxBitrate = if (lowBandwidth) 400_000 else 1_500_000
    private val _poor = MutableStateFlow(false)
    val poor: StateFlow<Boolean> = _poor.asStateFlow()

    // ---- Receiving ----------------------------------------------------------------------
    @Volatile private var receiving = true
    private val incoming = LinkedBlockingDeque<ByteArray>()
    private var decoder: MediaCodec? = null
    @Volatile private var remoteSurface: Surface? = null
    @Volatile private var remoteSurfaceChanged = false
    private var decodeThread: Thread? = null
    private var lastSeq = -1L
    private var broken = true
    private var lastKeyReq = 0L
    private var decoderSize: Size? = null
    private var decoderSps: ByteArray? = null
    private val _remote = MutableStateFlow<VideoFormat?>(null)
    val remote: StateFlow<VideoFormat?> = _remote.asStateFlow()

    // Stats for the log
    @Volatile private var sentFrames = 0
    @Volatile private var skippedFrames = 0
    @Volatile private var receivedFrames = 0
    @Volatile private var shownFrames = 0
    private var statsAt = System.currentTimeMillis()

    init {
        decodeThread = thread(name = "call-video-decode", isDaemon = true) {
            runCatching { decodeLoop() }.onFailure { Log.e(TAG, "decode", it) }
        }
    }

    // ---- Camera / encoder ---------------------------------------------------------------

    /** Starts the camera and encoder (needs CAMERA permission, checked by CallManager). */
    fun startCamera(useFront: Boolean = front) = camHandler.post {
        if (sending) return@post
        front = useFront
        runCatching { openCamera() }.onFailure {
            Log.e(TAG, "camera", it)
            stopCameraNow()
        }
    }

    fun stopCamera() = camHandler.post { stopCameraNow() }

    fun switchCamera() = camHandler.post {
        if (!sending) return@post
        val next = !front
        stopCameraNow()
        front = next
        runCatching { openCamera() }.onFailure { Log.e(TAG, "camera", it); stopCameraNow() }
    }

    /** The local preview (or null when its view goes away). */
    fun setPreview(texture: SurfaceTexture?) = camHandler.post {
        if (texture === previewTexture) return@post
        previewTexture = texture
        previewSurface?.release()
        previewSurface = texture?.let {
            it.setDefaultBufferSize(size.width, size.height)
            Surface(it)
        }
        if (sending) camera?.let { createSession(it) }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(CameraManager::class.java)
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: manager.cameraIdList.firstOrNull() ?: error("No camera")
        val chars = manager.getCameraCharacteristics(id)
        front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val target = if (lowBandwidth) Size(320, 240) else Size(640, 480)
        val sizes = map.getOutputSizes(MediaCodec::class.java).orEmpty().toList()
        size = sizes.firstOrNull { it == target }
            ?: sizes.filter { it.width <= target.width && it.width * 3 == it.height * 4 }.maxByOrNull { it.width }
            ?: sizes.filter { it.width <= 1280 }.maxByOrNull { it.width * it.height }
            ?: target
        val fpsRange = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .filter { it.upper <= 30 }.minByOrNull { kotlin.math.abs(it.upper - FPS) + kotlin.math.abs(it.lower - 10) }

        // Set before the encoder thread starts: it runs only while this is true.
        sending = true
        startEncoder()
        previewTexture?.setDefaultBufferSize(size.width, size.height)
        // The preview texture is already turned upright (and mirrored for the front camera) by
        // the camera; only its shape changes. The encoded frames are not: the receiver rotates them.
        _local.value = if (sensorRotation % 180 == 0) VideoFormat(size.width, size.height, 0)
        else VideoFormat(size.height, size.width, 0)
        manager.openCamera(id, camExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.i(TAG, "Camera opened")
                if (!sending) return device.close()
                camera = device
                createSession(device, fpsRange)
            }
            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera disconnected (in use by another app?)")
                device.close()
                if (camera === device) camera = null
            }
            override fun onError(device: CameraDevice, error: Int) {
                Log.w(TAG, "Camera error $error")
                device.close()
                if (camera === device) camera = null
            }
        })
        Log.i(TAG, "Camera on: ${if (front) "front" else "back"} ${size.width}x${size.height}, ${bitrate / 1000} kbit/s")
    }

    private var lastFps: Range<Int>? = null

    private fun createSession(device: CameraDevice, fps: Range<Int>? = lastFps) {
        lastFps = fps
        runCatching { session?.close() }
        session = null
        val targets = listOfNotNull(encoderSurface, previewSurface)
        if (targets.isEmpty()) return
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            targets.map { OutputConfiguration(it) },
            camExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera !== device) return s.close()
                    session = s
                    Log.i(TAG, "Camera streaming to ${targets.size} surface(s)")
                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        targets.forEach { addTarget(it) }
                        fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    }.build()
                    runCatching { s.setRepeatingRequest(req, null, camHandler) }.onFailure { Log.w(TAG, "repeat", it) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { Log.w(TAG, "Camera session failed") }
            },
        )
        runCatching { device.createCaptureSession(config) }.onFailure { Log.w(TAG, "session", it) }
    }

    private fun startEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        Log.i(TAG, "Encoder ${enc.name}")
        encoderSurface = enc.createInputSurface()
        enc.start()
        encoder = enc
        needKey = true
        lastAckAt = System.currentTimeMillis()
        drainThread = thread(name = "call-video-encode", isDaemon = true) {
            runCatching { drain(enc) }.onFailure { Log.w(TAG, "encode failed", it) }
        }
    }

    private fun stopCameraNow() {
        sending = false
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        drainThread?.join(500)
        drainThread = null
        encoder?.let { runCatching { it.stop() }; it.release() }
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
        _local.value = null
        _poor.value = false
    }

    private fun drain(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var config: ByteArray? = null
        while (sending) {
            val idx = try { enc.dequeueOutputBuffer(info, 20_000) } catch (_: IllegalStateException) { break }
            if (idx < 0) { checkStall(); continue }
            val buf = enc.getOutputBuffer(idx)!!
            buf.position(info.offset)
            val bytes = ByteArray(info.size).also { buf.get(it) }
            enc.releaseOutputBuffer(idx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                config = bytes
                continue
            }
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (sentFrames == 0 && skippedFrames < 3) Log.i(TAG, "Encoder output: ${bytes.size} bytes, key=$key, acked=$acked, seq=$seq")
            val inFlight = seq - 1 - acked
            if (inFlight >= MAX_IN_FLIGHT) {
                // The link is behind: skip, slow down, and restart from a key frame later.
                skippedFrames++
                logStats()
                if (!needKey) lowerBitrate(enc)
                needKey = true
                checkStall()
                continue
            }
            if (needKey && !key) {
                if (inFlight <= 1) requestKeyFrame(enc)
                skippedFrames++
                logStats()
                checkStall()
                continue
            }
            needKey = false
            if (sentFrames == 0) Log.i(TAG, "First video frame sent (${bytes.size} bytes, key=$key)")
            val payload = if (key && config != null) config + bytes else bytes
            send(pack(seq++, key, sensorRotation / 90, size, payload))
            sentFrames++
            maybeRaiseBitrate(enc)
            logStats()
        }
    }

    private var lastKeyRequest = 0L
    private fun requestKeyFrame(enc: MediaCodec) {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequest < 300) return
        lastKeyRequest = now
        runCatching { enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun lowerBitrate(enc: MediaCodec) {
        lastDropAt = System.currentTimeMillis()
        bitrate = maxOf(MIN_BITRATE, (bitrate * 0.7).toInt())
        runCatching { enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) }) }
    }

    private var lastRaise = 0L
    private fun maybeRaiseBitrate(enc: MediaCodec) {
        val now = System.currentTimeMillis()
        if (bitrate >= maxBitrate || now - lastDropAt < 5000 || now - lastRaise < 5000) return
        lastRaise = now
        bitrate = minOf(maxBitrate, (bitrate * 1.15).toInt())
        runCatching { enc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) }) }
    }

    /** Nothing acknowledged for a while: the link is too slow (or gone) for video. */
    private fun checkStall() {
        val stalled = System.currentTimeMillis() - lastAckAt > STALL_MS && seq > 0
        if (_poor.value != stalled) _poor.value = stalled
    }

    /** The peer received frame [ackSeq]. */
    fun onAck(ackSeq: Long) {
        if (ackSeq > acked) acked = ackSeq
        lastAckAt = System.currentTimeMillis()
    }

    /** The peer can't decode until it gets a key frame. */
    fun onKeyRequest() {
        needKey = true
    }

    // ---- Receiving ----------------------------------------------------------------------

    fun receive(frame: ByteArray) {
        if (!receiving) return
        receivedFrames++
        incoming.offer(frame)
        // Never let a backlog build up: a fresh key frame is better than old frames.
        while (incoming.size > 10) incoming.poll()
    }

    /** The view that shows the other person (or null when it goes away). */
    fun setRemoteSurface(surface: Surface?) {
        remoteSurface = surface
        remoteSurfaceChanged = true
    }

    /** The other side switched their camera off. */
    fun remoteStopped() {
        _remote.value = null
        broken = true
    }

    private fun decodeLoop() {
        val info = MediaCodec.BufferInfo()
        while (receiving) {
            val frame = incoming.poll(100, TimeUnit.MILLISECONDS)
            if (remoteSurfaceChanged) {
                remoteSurfaceChanged = false
                releaseDecoder()
                broken = true
            }
            if (frame == null) continue
            if (frame.size < HEADER) continue
            val b = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            val s = b.int.toLong() and 0xffffffffL
            val key = b.get().toInt() and 1 == 1
            val rot = (b.get().toInt() and 3) * 90
            val w = b.short.toInt() and 0xffff
            val h = b.short.toInt() and 0xffff
            sendCtl("vack", mapOf("seq" to s))

            if (lastSeq >= 0 && s != lastSeq + 1 && !key) broken = true
            lastSeq = s
            if (broken && !key) {
                askForKey()
                continue
            }
            val surface = remoteSurface ?: continue
            if (w == 0 || h == 0 || w > 1920 || h > 1920) continue
            // Key frames start with the codec header (SPS, PPS). Decoders want that header in
            // their format (csd-0 / csd-1), and only the picture itself as input.
            var data = frame.copyOfRange(HEADER, frame.size)
            if (key) {
                val nals = splitNals(data)
                val sps = nals.firstOrNull { nalType(it) == 7 }
                val pps = nals.firstOrNull { nalType(it) == 8 }
                data = nals.filter { nalType(it) != 7 && nalType(it) != 8 }.fold(ByteArray(0)) { acc, n -> acc + n }
                if (decoder == null || decoderSize != Size(w, h) || sps != null && !sps.contentEquals(decoderSps)) {
                    if (sps == null || pps == null) { askForKey(); continue }
                    releaseDecoder()
                    val dec = runCatching {
                        createDecoder().apply {
                            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                            f.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                            f.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME)
                            configure(f, surface, null, 0)
                            start()
                        }
                    }.getOrElse { Log.w(TAG, "decoder", it); continue }
                    Log.i(TAG, "Decoder ${dec.name} for ${w}x$h")
                    decoder = dec
                    decoderSize = Size(w, h)
                    decoderSps = sps
                }
            }
            val dec = decoder ?: run { askForKey(); null } ?: continue
            broken = false
            if (_remote.value != VideoFormat(w, h, rot)) _remote.value = VideoFormat(w, h, rot)
            if (data.isEmpty()) continue
            try {
                val inIdx = dec.dequeueInputBuffer(30_000)
                if (inIdx < 0) { broken = true; continue }
                val input = dec.getInputBuffer(inIdx)!!
                if (input.capacity() < data.size) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    Log.w(TAG, "Frame too big for decoder: ${data.size}")
                    broken = true
                    continue
                }
                input.apply { clear(); put(data) }
                dec.queueInputBuffer(inIdx, 0, data.size, s * 1_000_000 / FPS, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                while (true) {
                    val outIdx = dec.dequeueOutputBuffer(info, 5_000)
                    if (outIdx >= 0) {
                        dec.releaseOutputBuffer(outIdx, true)
                        shownFrames++
                    } else if (outIdx != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break
                }
            } catch (t: Exception) {
                val info2 = (t as? MediaCodec.CodecException)?.let { " (${it.diagnosticInfo}, code ${it.errorCode})" } ?: ""
                Log.w(TAG, "decode failed with ${dec.name}$info2: ${t.message}")
                // Some phones' (and emulators') own decoders reject our stream: use Android's
                // standard software decoder for the rest of the call.
                if (!useSoftware) { useSoftware = true; Log.i(TAG, "Switching to the software decoder") }
                releaseDecoder()
                broken = true
            }
            logStats()
        }
        releaseDecoder()
    }

    @Volatile private var useSoftware = false

    private fun createDecoder(): MediaCodec {
        if (useSoftware) {
            val name = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { c ->
                !c.isEncoder && c.isSoftwareOnly && c.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }?.name
            if (name != null) return MediaCodec.createByCodecName(name)
        }
        return MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }

    private fun askForKey() {
        val now = System.currentTimeMillis()
        if (now - lastKeyReq < 500) return
        lastKeyReq = now
        sendCtl("keyreq", emptyMap())
    }

    private fun releaseDecoder() {
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        decoder = null
        decoderSize = null
        decoderSps = null
    }

    private fun logStats() {
        val now = System.currentTimeMillis()
        if (now - statsAt < 5000) return
        statsAt = now
        Log.i(TAG, "Video: sent $sentFrames, skipped $skippedFrames, received $receivedFrames, shown $shownFrames, ${bitrate / 1000} kbit/s")
    }

    fun release() {
        receiving = false
        camHandler.post { stopCameraNow() }
        camHandler.post { previewSurface?.release(); previewSurface = null; camThread.quitSafely() }
        decodeThread?.join(1000)
        Log.i(TAG, "Video stopped: sent $sentFrames, skipped $skippedFrames, received $receivedFrames, shown $shownFrames")
    }

    companion object {
        private const val TAG = "VideoEngine"
        const val FPS = 20
        private const val HEADER = 10
        private const val MAX_IN_FLIGHT = 6 // frames (~300 ms) not yet acknowledged
        private const val MIN_BITRATE = 120_000
        private const val MAX_FRAME = 256 * 1024
        private const val STALL_MS = 3000L

        /** Splits H.264 Annex-B data into NAL units, each keeping its start code. */
        fun splitNals(d: ByteArray): List<ByteArray> {
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 <= d.size) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1) { starts += i; i += 3 } else i++
            }
            if (starts.isEmpty()) return listOf(d)
            return starts.mapIndexed { n, st ->
                // Include a 4th leading zero (00 00 00 01) with the unit it belongs to.
                val begin = if (st > 0 && d[st - 1].toInt() == 0) st - 1 else st
                val end = if (n + 1 < starts.size) starts[n + 1].let { if (d[it - 1].toInt() == 0) it - 1 else it } else d.size
                d.copyOfRange(begin, end)
            }
        }

        /** NAL unit type (7 = SPS, 8 = PPS, 5 = key picture). */
        fun nalType(nal: ByteArray): Int {
            val i = nal.indexOfFirst { it.toInt() == 1 }
            return if (i < 0 || i + 1 >= nal.size) -1 else nal[i + 1].toInt() and 0x1f
        }

        fun pack(seq: Long, key: Boolean, rot: Int, size: Size, data: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER + data.size).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(seq.toInt())
                put(if (key) 1 else 0)
                put(rot.toByte())
                putShort(size.width.toShort())
                putShort(size.height.toShort())
                put(data)
            }.array()

        /**
         * Matrix for a TextureView of [viewW]x[viewH] showing [f]: rotated upright, mirrored if
         * needed, and scaled to fill the view ([crop]) or fit inside it.
         */
        fun transform(viewW: Int, viewH: Int, f: VideoFormat, crop: Boolean): Matrix {
            val m = Matrix()
            if (viewW == 0 || viewH == 0) return m
            val cx = viewW / 2f
            val cy = viewH / 2f
            val (cw, ch) = if (f.rotation % 180 == 0) f.width to f.height else f.height to f.width
            val scale = if (crop) maxOf(viewW / cw.toFloat(), viewH / ch.toFloat()) else minOf(viewW / cw.toFloat(), viewH / ch.toFloat())
            m.setScale(f.width / viewW.toFloat(), f.height / viewH.toFloat(), cx, cy)
            m.postRotate(f.rotation.toFloat(), cx, cy)
            m.postScale(scale, scale, cx, cy)
            if (f.mirror) m.postScale(-1f, 1f, cx, cy)
            return m
        }
    }
}
