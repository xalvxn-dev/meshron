package com.xalvxnlab.meshron.reels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Base64
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes reels small with the phone's own video encoder (nothing extra in the app):
 * cuts to at most 60 s, scales to 720p, re-encodes H.264 at a bitrate that still looks sharp,
 * and copies the sound as it is (no quality loss), or takes the sound from another reel.
 */
object Transcoder {
    const val MAX_US = 60_000_000L
    private const val LONG_SIDE = 1280
    private const val BITRATE = 2_200_000
    /** Small enough already: posted as it is, untouched. */
    private const val KEEP_ORIGINAL = 6L * 1024 * 1024

    data class Info(val durationUs: Long, val width: Int, val height: Int, val rotation: Int, val size: Long, val mime: String)

    fun info(context: Context, uri: Uri): Info {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            return Info(dur, w, h, rot, size, mime)
        } finally {
            r.release()
        }
    }

    /** A small JPEG of the frame at [atUs], as base64 (≤ ~38 KB). Width/height as shown. */
    fun thumbnail(context: Context, uri: Uri, atUs: Long): Triple<String, Int, Int> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val frame = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return Triple("", 0, 0)
            val w = 270
            val h = min(480, (w * frame.height.toFloat() / frame.width).roundToInt())
            val small = Bitmap.createScaledBitmap(frame, w, max(1, h), true)
            var q = 74
            var bytes: ByteArray
            do {
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, q, out)
                bytes = out.toByteArray()
                q -= 12
            } while (bytes.size > 28_000 && q > 25)
            return Triple(Base64.encodeToString(bytes, Base64.NO_WRAP), frame.width, frame.height)
        } finally {
            r.release()
        }
    }

    /**
     * Writes the reel to [out]. Returns false if the original was copied as it is.
     * [sound]: another reel's video whose sound replaces this one's.
     */
    fun transcode(context: Context, uri: Uri, startUs: Long, lengthUs: Long, sound: File?, out: File, onProgress: (Float) -> Unit): Boolean {
        val info = info(context, uri)
        if (sound == null && startUs == 0L && info.durationUs <= MAX_US + 500_000 && info.size in 1..KEEP_ORIGINAL && info.mime == "video/mp4") {
            context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
            onProgress(1f)
            return false
        }
        val endUs = startUs + lengthUs
        val vEx = MediaExtractor().apply { setDataSource(context, uri, null) }
        val vTrack = (0 until vEx.trackCount).firstOrNull { vEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw Exception("That file has no video")
        vEx.selectTrack(vTrack)
        val inFormat = vEx.getTrackFormat(vTrack)
        val srcW = inFormat.getInteger(MediaFormat.KEY_WIDTH)
        val srcH = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val scale = min(1f, LONG_SIDE.toFloat() / max(srcW, srcH))
        val outW = ((srcW * scale) / 2).roundToInt() * 2
        val outH = ((srcH * scale) / 2).roundToInt() * 2
        val fps = runCatching { inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30).coerceIn(15, 60)

        // Sound: copied sample by sample from this video (or the chosen reel).
        val aEx = MediaExtractor()
        var aTrack = -1
        runCatching {
            if (sound != null) aEx.setDataSource(sound.path) else aEx.setDataSource(context, uri, null)
            aTrack = (0 until aEx.trackCount).firstOrNull {
                aEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: -1
        }
        if (sound != null && aTrack < 0) throw Exception("That reel has no sound to use")
        val audioFrom = if (sound != null) 0L else startUs
        // AAC is copied as it is; anything else (e.g. Opus from a browser) becomes AAC first.
        val aac = aTrack >= 0 && aEx.getTrackFormat(aTrack).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
        val encodedAudio = if (aTrack >= 0 && !aac) runCatching { encodeAudio(aEx, aTrack, audioFrom, lengthUs) }.getOrNull() else null

        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(info.rotation)
        val muxAudio = when {
            aac -> { aEx.selectTrack(aTrack); muxer.addTrack(aEx.getTrackFormat(aTrack)) }
            encodedAudio != null -> muxer.addTrack(encodedAudio.first)
            else -> -1
        }

        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, min(BITRATE, (BITRATE * (outW * outH) / (720f * 1280)).roundToInt().coerceAtLeast(600_000)))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val gl = Gl(encoder.createInputSurface())
        encoder.start()
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, gl.decoderSurface, null, 0)
        decoder.start()

        var muxVideo = -1
        var muxing = false
        val info2 = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        var encodeDone = false
        vEx.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        try {
            while (!encodeDone) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = vEx.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0 || vEx.sampleTime > endUs) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, vEx.sampleTime, 0)
                            vEx.advance()
                        }
                    }
                }
                if (!decodeDone) {
                    val o = decoder.dequeueOutputBuffer(info2, 10_000)
                    if (o >= 0) {
                        val eos = info2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val t = info2.presentationTimeUs
                        val keep = info2.size > 0 && t >= startUs && t <= endUs
                        decoder.releaseOutputBuffer(o, keep)
                        if (keep) {
                            gl.drawFrame()
                            gl.present((t - startUs) * 1000)
                            onProgress(((t - startUs).toFloat() / lengthUs).coerceIn(0f, 1f) * 0.95f)
                        }
                        if (eos) { encoder.signalEndOfInputStream(); decodeDone = true }
                    }
                }
                // Drain the encoder.
                while (true) {
                    val o = encoder.dequeueOutputBuffer(info2, if (decodeDone) 10_000 else 0)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxVideo = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxing = true
                        continue
                    }
                    if (o < 0) continue
                    val buf = encoder.getOutputBuffer(o)!!
                    if (info2.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info2.size = 0
                    if (info2.size > 0 && muxing) {
                        buf.position(info2.offset).limit(info2.offset + info2.size)
                        muxer.writeSampleData(muxVideo, buf, info2)
                    }
                    encoder.releaseOutputBuffer(o, false)
                    if (info2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encodeDone = true; break }
                }
            }
            // Sound for the same stretch of time.
            if (muxAudio >= 0 && muxing && encodedAudio != null) {
                for ((bytes, bi) in encodedAudio.second) muxer.writeSampleData(muxAudio, ByteBuffer.wrap(bytes), bi)
            } else if (muxAudio >= 0 && muxing) {
                val from = audioFrom
                aEx.seekTo(from, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val buf = ByteBuffer.allocate(256 * 1024)
                val ai = MediaCodec.BufferInfo()
                while (true) {
                    val n = aEx.readSampleData(buf, 0)
                    if (n < 0) break
                    val t = aEx.sampleTime - from
                    if (t > lengthUs) break
                    if (t >= 0) {
                        ai.set(0, n, t, if (aEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(muxAudio, buf, ai)
                    }
                    aEx.advance()
                }
            }
            onProgress(1f)
        } finally {
            runCatching { decoder.stop() }; decoder.release()
            runCatching { encoder.stop() }; encoder.release()
            gl.release()
            runCatching { if (muxing) muxer.stop() }; muxer.release()
            vEx.release(); aEx.release()
        }
        if (!muxing) throw Exception("Couldn't convert that video")
        return true
    }

    /**
     * Some cameras (and old files) store the sound as AMR or other formats the editor can't
     * use. Rewrites [uri] to [out] with the picture copied untouched and the sound as AAC.
     * Returns false (and writes nothing) when the sound is already AAC or there is none.
     */
    fun normalizeAudio(context: Context, uri: Uri, out: File): Boolean {
        val probe = MediaExtractor().apply { setDataSource(context, uri, null) }
        val tracks = (0 until probe.trackCount).map { probe.getTrackFormat(it) }
        probe.release()
        val audio = tracks.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        if (audio < 0 || tracks[audio].getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) return false
        val video = tracks.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        if (video < 0) return false
        val aEx = MediaExtractor().apply { setDataSource(context, uri, null) }
        val (aFormat, aSamples) = encodeAudio(aEx, audio, 0, Long.MAX_VALUE / 4)
        aEx.release()
        val vEx = MediaExtractor().apply { setDataSource(context, uri, null); selectTrack(video) }
        val rotation = runCatching { info(context, uri).rotation }.getOrDefault(0)
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(rotation)
            val v = muxer.addTrack(tracks[video])
            val a = muxer.addTrack(aFormat)
            muxer.start()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            while (true) {
                val n = vEx.readSampleData(buf, 0)
                if (n < 0) break
                bi.set(0, n, vEx.sampleTime, if (vEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(v, buf, bi)
                vEx.advance()
            }
            for ((bytes, info) in aSamples) muxer.writeSampleData(a, ByteBuffer.wrap(bytes), info)
            muxer.stop()
        } finally {
            muxer.release(); vEx.release()
        }
        return true
    }

    /** Decodes [track] from [fromUs] for [lengthUs] and re-encodes it as AAC, in memory (≤ ~1 MB a minute). */
    private fun encodeAudio(ex: MediaExtractor, track: Int, fromUs: Long, lengthUs: Long): Pair<MediaFormat, List<Pair<ByteArray, MediaCodec.BufferInfo>>> {
        val inF = ex.getTrackFormat(track)
        ex.selectTrack(track)
        ex.seekTo(fromUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val rate = inF.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = inF.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
        val dec = MediaCodec.createDecoderByType(inF.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(inF, null, null, 0)
        dec.start()
        val encF = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(encF, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val out = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        val bi = MediaCodec.BufferInfo()
        var inDone = false
        var decDone = false
        var encDone = false
        val pcm = java.util.ArrayDeque<Pair<ByteArray, Long>>()
        var pcmEosQueued = false
        try {
            while (!encDone) {
                if (!inDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime - fromUs > lengthUs) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                if (!decDone) {
                    val o = dec.dequeueOutputBuffer(bi, 5_000)
                    if (o >= 0) {
                        if (bi.size > 0 && bi.presentationTimeUs >= fromUs) {
                            val b = ByteArray(bi.size)
                            dec.getOutputBuffer(o)!!.apply { position(bi.offset); get(b) }
                            pcm.add(b to (bi.presentationTimeUs - fromUs))
                        }
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decDone = true
                        dec.releaseOutputBuffer(o, false)
                    }
                }
                // Feed PCM to the encoder.
                while (pcm.isNotEmpty() || (decDone && !pcmEosQueued)) {
                    val i = enc.dequeueInputBuffer(0)
                    if (i < 0) break
                    val buf = enc.getInputBuffer(i)!!
                    if (pcm.isEmpty()) { enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); pcmEosQueued = true; break }
                    val (b, t) = pcm.poll()!!
                    val n = minOf(b.size, buf.capacity())
                    buf.clear(); buf.put(b, 0, n)
                    enc.queueInputBuffer(i, 0, n, t, 0)
                }
                while (true) {
                    val o = enc.dequeueOutputBuffer(bi, 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { outFormat = enc.outputFormat; continue }
                    if (o < 0) break
                    if (bi.size > 0 && bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val b = ByteArray(bi.size)
                        enc.getOutputBuffer(o)!!.apply { position(bi.offset); get(b) }
                        out.add(b to MediaCodec.BufferInfo().apply { set(0, b.size, bi.presentationTimeUs, bi.flags) })
                    }
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encDone = true
                    enc.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            runCatching { dec.stop() }; dec.release()
            runCatching { enc.stop() }; enc.release()
            ex.unselectTrack(track)
        }
        return (outFormat ?: throw Exception("No sound")) to out
    }

    /** Decoder -> texture -> encoder surface, scaled by drawing (EGL + one shader). */
    private class Gl(encoderSurface: Surface) : SurfaceTexture.OnFrameAvailableListener {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        private val texture: Int
        private val st: SurfaceTexture
        val decoderSurface: Surface
        private val program: Int
        private val lock = Object()
        private var available = false
        private val matrix = FloatArray(16)
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
        }

        init {
            val v = IntArray(2)
            EGL14.eglInitialize(display, v, 0, v, 1)
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, context)
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texture = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            st = SurfaceTexture(texture)
            st.setOnFrameAvailableListener(this, android.os.Handler(android.os.HandlerThread("reel-gl").apply { start() }.looper))
            decoderSurface = Surface(st)
            program = link(
                "uniform mat4 m;attribute vec4 p;attribute vec4 c;varying vec2 v;void main(){gl_Position=p;v=(m*c).xy;}",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 v;uniform samplerExternalOES s;void main(){gl_FragColor=texture2D(s,v);}",
            )
        }

        override fun onFrameAvailable(t: SurfaceTexture) {
            synchronized(lock) { available = true; lock.notifyAll() }
        }

        fun drawFrame() {
            synchronized(lock) {
                val until = System.currentTimeMillis() + 2500
                while (!available) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) throw Exception("The video decoder stopped")
                    lock.wait(left)
                }
                available = false
            }
            st.updateTexImage()
            st.getTransformMatrix(matrix)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "m"), 1, false, matrix, 0)
            val p = GLES20.glGetAttribLocation(program, "p")
            val c = GLES20.glGetAttribLocation(program, "c")
            quad.position(0)
            GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(p)
            quad.position(2)
            GLES20.glVertexAttribPointer(c, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(c)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun present(ns: Long) {
            EGLExt.eglPresentationTimeANDROID(display, surface, ns)
            EGL14.eglSwapBuffers(display, surface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            decoderSurface.release()
            st.release()
        }

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(it)
            }
        }
    }
}
