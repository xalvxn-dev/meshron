package com.xalvxnlab.meshron.reels

import android.content.Context
import android.graphics.ColorMatrix
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Meshron's own looks. Each is one colour matrix, used for the preview and the export. */
enum class Look(val label: String) {
    None("Original"), Aurora("Aurora"), Ember("Ember"), Mint("Mint"), Dusk("Dusk"),
    Vivid("Vivid"), Fade("Fade"), Mono("Mono"), Noir("Noir"),
}

class TextLayer(val text: String, val x: Float, val y: Float, val size: Float = 1f, val color: Int = 0xFFFFFFFF.toInt(), val pill: Boolean = true)

class MusicTrack(val file: File, val title: String, val startMs: Long = 0, val sound: ReelSound? = null)

/** Everything the editor can change. Times are in the source video (before speed). */
data class Edit(
    val trimStartMs: Long = 0,
    val trimEndMs: Long = Long.MAX_VALUE,
    val speed: Float = 1f,
    val look: Look = Look.None,
    val brightness: Float = 0f, // -1..1
    val contrast: Float = 0f, // -1..1
    val saturation: Float = 0f, // -1..1
    val texts: List<TextLayer> = emptyList(),
    val rotation: Int = 0, // 0, 90, 180, 270
    val aspect: String = "original", // original, 9:16, 1:1, 4:5
    val originalVolume: Float = 1f,
    val music: MusicTrack? = null,
    val musicVolume: Float = 0.8f,
    val coverMs: Long = 500,
) {
    val lengthMs get() = ((trimEndMs - trimStartMs) / speed).toLong()
    val changed get() = this != Edit(trimEndMs = trimEndMs)
}

object VideoEditor {
    /** Combined colour matrix (Android 4×5) for look + adjustments. */
    fun colorMatrix(e: Edit): ColorMatrix {
        val m = ColorMatrix()
        fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }
        fun scale(r: Float, g: Float, b: Float, add: Float = 0f) = ColorMatrix(floatArrayOf(r, 0f, 0f, 0f, add, 0f, g, 0f, 0f, add, 0f, 0f, b, 0f, add, 0f, 0f, 0f, 1f, 0f))
        fun contrast(c: Float): ColorMatrix { val t = (1f - c) * 128f; return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)) }
        when (e.look) {
            Look.None -> Unit
            Look.Aurora -> { m.postConcat(scale(0.92f, 1.02f, 1.12f, 4f)); m.postConcat(sat(1.12f)) }
            Look.Ember -> { m.postConcat(scale(1.12f, 1.0f, 0.86f, 6f)); m.postConcat(sat(1.08f)) }
            Look.Mint -> { m.postConcat(scale(0.94f, 1.08f, 1.0f)); m.postConcat(sat(0.95f)) }
            Look.Dusk -> { m.postConcat(scale(1.08f, 0.92f, 1.12f)); m.postConcat(contrast(1.05f)) }
            Look.Vivid -> { m.postConcat(sat(1.35f)); m.postConcat(contrast(1.12f)) }
            Look.Fade -> { m.postConcat(sat(0.75f)); m.postConcat(contrast(0.85f)); m.postConcat(scale(1f, 1f, 1f, 18f)) }
            Look.Mono -> m.postConcat(sat(0f))
            Look.Noir -> { m.postConcat(sat(0f)); m.postConcat(contrast(1.35f)) }
        }
        if (e.saturation != 0f) m.postConcat(sat(1f + e.saturation))
        if (e.contrast != 0f) m.postConcat(contrast(1f + e.contrast * 0.6f))
        if (e.brightness != 0f) m.postConcat(scale(1f, 1f, 1f, e.brightness * 60f))
        return m
    }

    /** The same matrix for the GPU (4×4, column-major, 0..1 colours; offsets dropped into the last column). */
    private class MatrixEffect(cm: ColorMatrix) : RgbMatrix {
        private val m4: FloatArray
        init {
            val a = cm.array // row-major 4x5, offsets in 0..255
            m4 = FloatArray(16)
            for (row in 0 until 4) for (col in 0 until 4) m4[col * 4 + row] = a[row * 5 + col]
            // Offsets: RgbMatrix has no translation, so fold a small brightness lift into the
            // diagonal (good enough for the small offsets our looks use).
            for (row in 0 until 3) m4[row * 4 + row] += a[row * 5 + 4] / 255f
        }
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean) = m4
    }

    private fun volume(v: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        for (ch in 1..2) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(ch, ch).scaleBy(v.coerceIn(0f, 2f)))
    }

    private fun constantSpeed(s: Float) = object : SpeedProvider {
        override fun getSpeed(timeUs: Long) = s
        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    /** Joins recorded clips into one file (no other changes). */
    suspend fun join(context: Context, clips: List<File>, out: File, onProgress: (Float) -> Unit) {
        if (clips.size == 1) { clips[0].copyTo(out, overwrite = true); onProgress(1f); return }
        val seq = EditedMediaItemSequence.Builder(clips.map { EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it))).build() }).build()
        run(context, Composition.Builder(seq).build(), out, onProgress)
    }

    /** Applies [e] to [input] and writes an H.264/AAC MP4 (720p, ~2.5 Mbps) to [out]. */
    suspend fun export(context: Context, input: Uri, e: Edit, videoW: Int, videoH: Int, out: File, onProgress: (Float) -> Unit) {
        val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(e.trimStartMs)
            .apply { if (e.trimEndMs != Long.MAX_VALUE) setEndPositionMs(e.trimEndMs) }.build()
        val video = ArrayList<androidx.media3.common.Effect>()
        // Rotate first, so the crop below works on the picture as you see it.
        if (e.rotation != 0) video += ScaleAndRotateTransformation.Builder().setRotationDegrees(-e.rotation.toFloat()).build()
        when (e.aspect) {
            "9:16", "1:1", "4:5" -> {
                val target = when (e.aspect) { "9:16" -> 9f / 16f; "1:1" -> 1f; else -> 4f / 5f }
                val rotated = e.rotation % 180 != 0
                val w = if (rotated) videoH else videoW
                val h = if (rotated) videoW else videoH
                val ratio = w.toFloat() / h
                // Centre crop in normalised coordinates (-1..1).
                if (ratio > target) { val k = target / ratio; video += Crop(-k, k, -1f, 1f) }
                else if (ratio < target) { val k = ratio / target; video += Crop(-1f, 1f, -k, k) }
            }
        }
        val cm = colorMatrix(e)
        if (e.look != Look.None || e.brightness != 0f || e.contrast != 0f || e.saturation != 0f) video += MatrixEffect(cm)
        if (e.texts.isNotEmpty()) {
            val overlays = e.texts.map { t ->
                val s = SpannableString(t.text)
                s.setSpan(AbsoluteSizeSpan((72 * t.size).toInt()), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(ForegroundColorSpan(t.color), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (t.pill) s.setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                // x, y are 0..1 from the top-left; the overlay anchor is -1..1 from the bottom-left.
                TextOverlay.createStaticTextOverlay(s, StaticOverlaySettings.Builder().setBackgroundFrameAnchor(t.x * 2 - 1, 1 - t.y * 2).build())
            }
            video += OverlayEffect(ImmutableList.copyOf<androidx.media3.effect.TextureOverlay>(overlays))
        }
        // 720p on the short side at most (sharp on phones, small to send).
        video += Presentation.createForShortSide(720)
        val audio = ArrayList<AudioProcessor>()
        if (e.originalVolume != 1f) audio += volume(e.originalVolume)
        val main = EditedMediaItem.Builder(MediaItem.Builder().setUri(input).setClippingConfiguration(clip).build())
            .setEffects(Effects(audio, video))
            .setRemoveAudio(e.originalVolume <= 0.01f && e.music != null)
            .apply { if (e.speed != 1f) setSpeed(constantSpeed(e.speed)) }
            .build()
        val sequences = arrayListOf(EditedMediaItemSequence.Builder(main).build())
        e.music?.let { m ->
            val music = EditedMediaItem.Builder(
                MediaItem.Builder().setUri(Uri.fromFile(m.file)).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(m.startMs).setEndPositionMs(m.startMs + e.lengthMs).build(),
                ).build(),
            ).setRemoveVideo(true).setEffects(Effects(listOf(volume(e.musicVolume)), emptyList())).build()
            sequences += EditedMediaItemSequence.Builder(music).build()
        }
        run(context, Composition.Builder(sequences).build(), out, onProgress)
    }

    private suspend fun run(context: Context, composition: Composition, out: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        out.delete()
        val enc = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(2_500_000).build()).build()
        suspendCancellableCoroutine { cont ->
            // If the phone's hardware decoder refuses (some phones / emulators), use a software one.
            // Emulators' hardware decoders reject some camera files; prefer software ones there.
            val emulator = android.os.Build.HARDWARE.contains("goldfish") || android.os.Build.HARDWARE.contains("ranchu")
            val selector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
                val all = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (emulator) all.sortedBy { if (it.softwareOnly) 0 else 1 } else all
            }
            val decoders = androidx.media3.transformer.DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).setMediaCodecSelector(selector).build()
            val loader = androidx.media3.transformer.DefaultAssetLoaderFactory(context, decoders, androidx.media3.common.util.Clock.DEFAULT, null as android.media.metrics.LogSessionId?)
            val t = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(enc).setAssetLoaderFactory(loader)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, r: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                    override fun onError(c: Composition, r: ExportResult, ex: ExportException) {
                        if (cont.isActive) cont.resumeWithException(Exception("Couldn't make the video (" + ex.errorCodeName + ")"))
                    }
                }).build()
            t.start(composition, out.path)
            cont.invokeOnCancellation { t.cancel() }
            // Progress, while it runs.
            val holder = ProgressHolder()
            kotlinx.coroutines.MainScope().launch {
                while (cont.isActive) {
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    delay(250)
                }
            }
        }
    }
}
