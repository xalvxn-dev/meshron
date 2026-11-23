package com.xalvxnlab.meshron.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Game mode on the phone: the same hologram game screens as on Windows (bundled in the APK),
 * in a full-screen WebView. The front camera follows your hands and face on the phone only; the
 * camera picture is not shown unless the person turns it on. Tracking models are downloaded on
 * first use, after the person agrees, and kept in the app's files.
 */
class GameActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pendingCamera: PermissionRequest? = null
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        pendingCamera?.let { if (ok) it.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else it.deny() }
        pendingCamera = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        web = WebView(this).apply {
            setBackgroundColor(0xFF03060B.toInt())
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "MeshronAndroid")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = serve(req.url.host, req.url.path.orEmpty())
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = req.url.host != HOST
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    // Only the camera, only for our own page.
                    if (request.origin.host != HOST || !request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) { request.deny(); return }
                    runOnUiThread {
                        if (ContextCompat.checkSelfPermission(this@GameActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        else { pendingCamera = request; askCamera.launch(Manifest.permission.CAMERA) }
                    }
                }
            }
        }
        setContentView(web)
        web.loadUrl("https://$HOST/game/game.html")
    }

    /** The page's files come from the APK's assets; the models from the app's own files. */
    private fun serve(host: String?, path: String): WebResourceResponse? {
        if (host != HOST) return null
        val type = when (path.substringAfterLast('.')) { "html" -> "text/html"; "js", "mjs" -> "text/javascript"; "wasm" -> "application/wasm"; "css" -> "text/css"; else -> "application/octet-stream" }
        val headers = mapOf("Access-Control-Allow-Origin" to "https://$HOST", "Cache-Control" to "no-cache")
        return runCatching {
            when {
                path.startsWith("/game/") -> WebResourceResponse(type, "utf-8", 200, "OK", headers, assets.open(path.removePrefix("/")))
                path == "/models/hand.task" -> WebResourceResponse(type, null, 200, "OK", headers, handModel(this).inputStream())
                path == "/models/face.task" -> WebResourceResponse(type, null, 200, "OK", headers, faceModel(this).inputStream())
                else -> null
            }
        }.getOrNull() ?: WebResourceResponse("text/plain", "utf-8", 404, "Not found", headers, "".byteInputStream())
    }

    private fun event(ev: String, data: Any?) {
        val d = when (data) { null -> "null"; is String -> JSONObject.quote(data); else -> data.toString() }
        runOnUiThread { web.evaluateJavascript("window.__gameEvent && window.__gameEvent(${JSONObject.quote(ev)}, $d)", null) }
    }

    inner class Bridge {
        @JavascriptInterface fun settings(): String = JSONObject().put("skin", Look.skin).put("theme", "midnight").toString()
        @JavascriptInterface fun models(): String = JSONObject().put("ready", modelsReady(this@GameActivity)).put("mb", 11.5).toString()
        @JavascriptInterface fun download() {
            thread {
                try { downloadModels(this@GameActivity) { p -> event("game-progress", p) }; event("game-downloaded", null) }
                catch (e: Exception) { event("game-download-failed", e.message ?: "failed") }
            }
        }
        @JavascriptInterface fun ask(text: String) { runOnUiThread { if (text.isNotBlank()) { startActivity(Intent(this@GameActivity, MainActivity::class.java).putExtra("ask", text).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() } } }
        @JavascriptInterface fun mood(m: String) { if (m.isNotBlank()) Look.feel(m, 3000) }
        @JavascriptInterface fun close() { runOnUiThread { finish() } }
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val HAND = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        private const val FACE = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
        private fun dir(c: android.content.Context) = File(c.filesDir, "models").apply { mkdirs() }
        fun handModel(c: android.content.Context) = File(dir(c), "hand_landmarker.task")
        fun faceModel(c: android.content.Context) = File(dir(c), "face_landmarker.task")
        fun modelsReady(c: android.content.Context) = handModel(c).length() > 1_000_000 && faceModel(c).length() > 1_000_000

        /** Downloads whichever model is missing (following redirects); [progress] goes 0..1. */
        fun downloadModels(c: android.content.Context, progress: (Float) -> Unit) {
            val todo = listOf(HAND to handModel(c), FACE to faceModel(c)).filter { it.second.length() < 1_000_000 }
            val total = 11_500_000f; var done = 0L; var last = 0L
            for ((url, file) in todo) {
                var u = URL(url); var hops = 0
                var conn: HttpURLConnection
                while (true) {
                    conn = u.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false; conn.connectTimeout = 15000; conn.readTimeout = 30000
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) { u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    if (code != 200) throw Exception("Download failed ($code)")
                    break
                }
                val tmp = File(file.path + ".part")
                conn.inputStream.use { input -> tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; val now = System.currentTimeMillis(); if (now - last > 120) { last = now; progress(minOf(0.99f, done / total)) } }
                } }
                if (!tmp.renameTo(file)) throw Exception("Couldn't save the model")
            }
            progress(1f)
        }
    }
}
