package com.xalvxnlab.meshron.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Records a voice note (AAC in .m4a, mono 64 kbps) into the shareable chat files folder. */
class VoiceRecorder private constructor(private val rec: MediaRecorder, private val file: File, private val started: Long) {
    /** Stops; returns the file, or null if it was too short to keep. */
    fun stop(): File? {
        val ms = System.currentTimeMillis() - started
        runCatching { rec.stop() }
        rec.release()
        if (ms < 700) { file.delete(); return null }
        return file
    }

    companion object {
        fun start(context: Context): VoiceRecorder? = runCatching {
            val dir = File(context.filesDir, com.xalvxnlab.meshron.identity.Mode.name("chatfiles")).apply { mkdirs() }
            val file = File(dir, "Voice note " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date()) + ".m4a")
            val rec = MediaRecorder(context)
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(44_100)
            rec.setAudioEncodingBitRate(64_000)
            rec.setOutputFile(file.path)
            rec.prepare()
            rec.start()
            VoiceRecorder(rec, file, System.currentTimeMillis())
        }.getOrNull()

        fun uriOf(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
}
