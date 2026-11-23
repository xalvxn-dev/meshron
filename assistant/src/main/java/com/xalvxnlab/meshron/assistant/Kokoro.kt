package com.xalvxnlab.meshron.assistant

import android.content.Context

/**
 * Kokoro voices on the phone are paused (the person asked to pause them). The engine behind them
 * (sherpa-onnx, ~24 MB per phone type) is left out of the app to keep it small; this stand-in keeps
 * the same calls so nothing else changes. To bring it back, restore the full version of this file
 * and the sherpa-onnx / commons-compress lines in build.gradle.kts from git history
 * (commit 758ed42 or earlier) and set ENABLED = true.
 */
object Kokoro {
    const val ENABLED = false
    fun ready(c: Context) = false
    @Volatile var progress = -1; private set
    @Volatile var lastError: String? = "Voices on this phone are paused"; private set
    suspend fun download(c: Context, onProgress: (Int) -> Unit = {}): Boolean = false
    fun downloadInBackground(c: Context) {}
    fun delete(c: Context) {}
    fun release() {}
    fun stop() {}
    suspend fun speak(c: Context, text: String, voice: String): Boolean = false
}
