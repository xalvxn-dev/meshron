package com.xalvxnlab.meshron.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import org.json.JSONObject

/**
 * The board on the phone: the whole screen becomes a hologram whiteboard Meshron writes and draws
 * on while it explains (the same board as on Windows, bundled in the app). It floats over every
 * app (it needs "display over other apps", like the floating orb), lets touches through, and has a
 * small ✕ to clear it. "Clear the board" works too.
 */
object Board {
    private const val HOST = "appassets.androidplatform.net"
    private var web: WebView? = null
    private var closeBtn: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private var pending: String? = null
    private var ready = false

    fun canShow(c: Context) = Settings.canDrawOverlays(c)

    /** Writes and draws: spec is { items, title?, fresh?, dim? }. */
    fun show(c: Context, spec: JSONObject) {
        main.post {
            val js = "window.__gameEvent && window.__gameEvent('board', " + spec.toString() + ")"
            val w = web
            if (w != null) { if (ready) w.evaluateJavascript(js, null) else pending = js; return@post }
            open(c.applicationContext); pending = js
        }
    }

    fun clear() { main.post { close() } }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(c: Context) {
        val wm = c.getSystemService(WindowManager::class.java)
        ready = false
        val v = WebView(c).apply {
            setBackgroundColor(0)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            addJavascriptInterface(object {
                @JavascriptInterface fun settings() = "{}"
                @JavascriptInterface fun models() = "{\"ready\":false}"
                @JavascriptInterface fun download() {}
                @JavascriptInterface fun ask(t: String) {}
                @JavascriptInterface fun mood(m: String) {}
                @JavascriptInterface fun close() { clear() }
                @JavascriptInterface fun boardClose() { clear() }
            }, "MeshronAndroid")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    if (req.url.host != HOST) return null
                    val path = req.url.path.orEmpty().removePrefix("/")
                    val type = when (path.substringAfterLast('.')) { "html" -> "text/html"; "js" -> "text/javascript"; else -> "application/octet-stream" }
                    return runCatching { WebResourceResponse(type, "utf-8", c.assets.open(path)) }.getOrNull()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    ready = true
                    // The phone's own ✕ button is used (this window lets touches through).
                    view.evaluateJavascript("document.getElementById('x').style.display='none'", null)
                    pending?.let { view.evaluateJavascript(it, null) }; pending = null
                }
            }
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        runCatching { wm.addView(v, lp) }.onFailure { return }
        web = v
        // A small ✕ in the top corner that does take touches.
        val dp = c.resources.displayMetrics.density
        val x = TextView(c).apply {
            text = "✕"; textSize = 18f; setTextColor(0xFFE6F7FF.toInt()); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xE6040810.toInt()); setStroke((1 * dp).toInt(), 0x9922D3C5.toInt()) }
            setOnClickListener { clear() }
        }
        val xl = WindowManager.LayoutParams((44 * dp).toInt(), (44 * dp).toInt(), type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; this.x = (14 * dp).toInt(); y = (40 * dp).toInt()
        }
        runCatching { wm.addView(x, xl) }
        closeBtn = x
        v.loadUrl("https://$HOST/game/board.html")
    }

    private fun close() {
        val c = web?.context ?: return
        val wm = c.getSystemService(WindowManager::class.java)
        runCatching { web?.let { wm.removeView(it); it.destroy() } }
        runCatching { closeBtn?.let { wm.removeView(it) } }
        web = null; closeBtn = null; ready = false
    }
}
