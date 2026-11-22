package com.xalvxnlab.meshron.assistant

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Hands for the assistant (only after the person turns it on under Accessibility):
 * - acts on screen like a person would: reads what's shown, taps, types, scrolls, swipes, goes
 *   home or back, opens recent apps and closes them;
 * - starts the assistant from the person's own button combo (volume keys) or the accessibility button.
 */
class ControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: ControlService? = null
        val on get() = instance != null
        /** Opens the assistant listening: the floating orb if it's running, else the app. */
        fun activate(c: Context) {
            FloatingService.instance?.let { it.wake(); return }
            c.startActivity(Intent(c, MainActivity::class.java).putExtra("assist", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastKey = 0; private var lastAt = 0L
    private val held = mutableSetOf<Int>()

    override fun onServiceConnected() {
        instance = this
        runCatching {
            accessibilityButtonController.registerAccessibilityButtonCallback(object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) = activate(this@ControlService)
            })
        }
    }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- The person's own button combo ----
    override fun onKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k != KeyEvent.KEYCODE_VOLUME_UP && k != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val combo = Memory.combo(this)
        if (combo == "off") return false
        if (e.action == KeyEvent.ACTION_UP) { held.remove(k); return false }
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return false
        val now = e.eventTime
        val fire = when (combo) {
            "down2" -> k == KeyEvent.KEYCODE_VOLUME_DOWN && lastKey == k && now - lastAt < 450
            "up2" -> k == KeyEvent.KEYCODE_VOLUME_UP && lastKey == k && now - lastAt < 450
            "both" -> held.isNotEmpty() && !held.contains(k)
            "updown" -> k == KeyEvent.KEYCODE_VOLUME_DOWN && lastKey == KeyEvent.KEYCODE_VOLUME_UP && now - lastAt < 600
            else -> false
        }
        held.add(k); lastKey = k; lastAt = now
        if (fire) { lastKey = 0; held.clear(); main.post { activate(this) }; return true }
        return false
    }

    // ---- Seeing the screen ----

    /** What's on screen, numbered, so the model can say "tap 4". */
    data class Item(val n: Int, val node: AccessibilityNodeInfo, val label: String, val kind: String)

    private fun items(): List<Item> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<Item>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || out.size >= 80) return
            if (n.isVisibleToUser) {
                val label = listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString()).firstOrNull { it.isNotBlank() }?.trim()?.take(80)
                val kind = when { n.isEditable -> "field"; n.isCheckable -> "switch"; n.isClickable -> "button"; n.isScrollable -> "list"; else -> "text" }
                if (label != null || n.isEditable || (n.isScrollable && depth < 12)) out += Item(out.size + 1, n, label ?: "", kind)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    fun read(): String {
        val app = rootInActiveWindow?.packageName?.toString().orEmpty()
        val list = items()
        if (list.isEmpty()) return "Nothing readable on screen (app: $app)."
        return "App: $app\n" + list.joinToString("\n") { "${it.n}. [${it.kind}] ${it.label}" + if (it.node.isChecked) " (on)" else "" }
    }

    private fun find(target: String): Item? {
        val list = items()
        target.trim().toIntOrNull()?.let { n -> return list.firstOrNull { it.n == n } }
        val q = target.trim().lowercase()
        return list.firstOrNull { it.label.lowercase() == q && it.kind != "text" } ?: list.firstOrNull { it.label.lowercase() == q }
            ?: list.firstOrNull { it.label.lowercase().contains(q) && it.kind != "text" } ?: list.firstOrNull { it.label.lowercase().contains(q) }
    }

    // ---- Acting ----

    suspend fun tap(target: String, long: Boolean = false): String {
        val it = find(target) ?: return "I can't see \"$target\" on screen"
        var n: AccessibilityNodeInfo? = it.node
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (n != null) {
            if ((if (long) n.isLongClickable else n.isClickable) && n.performAction(action)) return (if (long) "Long-pressed " else "Tapped ") + it.label.ifBlank { "item ${it.n}" }
            n = n.parent
        }
        val r = Rect(); it.node.getBoundsInScreen(r)
        return if (gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), if (long) 700 else 60)) "Tapped " + it.label else "Couldn't tap that"
    }

    fun type(text: String, target: String?): String {
        val field = target?.let { find(it)?.node } ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: items().firstOrNull { it.kind == "field" }?.node
            ?: return "There's no text box on screen"
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
        return if (ok) "Typed it" else "That box won't take text"
    }

    suspend fun scroll(direction: String): String {
        val back = direction == "up" || direction == "left"
        val list = items().filter { it.node.isScrollable }.maxByOrNull { val r = Rect(); it.node.getBoundsInScreen(r); r.width() * r.height() }
        if (list != null && list.node.performAction(if (back) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return "Scrolled $direction"
        return swipe(if (back) "down" else "up").let { "Scrolled $direction" }
    }

    /** A finger swipe across the middle of the screen ("up" moves the content up, like flicking to the next video). */
    suspend fun swipe(direction: String): String {
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f; val cy = m.heightPixels / 2f
        val dy = m.heightPixels * 0.3f; val dx = m.widthPixels * 0.35f
        val ok = when (direction) {
            "up" -> gesture(cx, cy + dy, cx, cy - dy, 250)
            "down" -> gesture(cx, cy - dy, cx, cy + dy, 250)
            "left" -> gesture(cx + dx, cy, cx - dx, cy, 220)
            "right" -> gesture(cx - dx, cy, cx + dx, cy, 220)
            else -> false
        }
        return if (ok) "Swiped $direction" else "Couldn't swipe"
    }

    fun global(name: String): String {
        val a = when (name) {
            "home" -> GLOBAL_ACTION_HOME; "back" -> GLOBAL_ACTION_BACK; "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN; "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return "Unknown action $name"
        }
        return if (performGlobalAction(a)) "Done: $name" else "Couldn't do $name"
    }

    /** Closes the app in front: opens recent apps and flicks its card away, then goes home. */
    suspend fun closeApp(): String {
        val app = rootInActiveWindow?.packageName?.toString()
        if (app == null || app == packageName) return "There's no app open to close"
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        delay(700)
        val m = resources.displayMetrics
        gesture(m.widthPixels / 2f, m.heightPixels * 0.5f, m.widthPixels / 2f, m.heightPixels * 0.05f, 200)
        delay(400)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return "Closed the app"
    }

    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean = suspendCancellableCoroutine { cont ->
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }
}

/** Quick Settings tile: one tap from the notification shade starts the assistant. */
class TalkTile : android.service.quicksettings.TileService() {
    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).putExtra("assist", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 3, i, android.app.PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}

/** Home-screen widget: a reactor to talk, and three one-tap requests. */
class AssistWidget : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(c: Context, m: android.appwidget.AppWidgetManager, ids: IntArray) {
        fun ask(code: Int, text: String?) = android.app.PendingIntent.getActivity(c, code,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (text == null) putExtra("assist", true) else putExtra("ask", text) },
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        for (id in ids) {
            val v = android.widget.RemoteViews(c.packageName, R.layout.widget)
            v.setOnClickPendingIntent(R.id.w_orb, ask(10, null))
            v.setOnClickPendingIntent(R.id.w_talk, ask(10, null))
            v.setOnClickPendingIntent(R.id.w_day, ask(11, "Plan my day"))
            v.setOnClickPendingIntent(R.id.w_inbox, ask(12, "Triage my notifications"))
            v.setOnClickPendingIntent(R.id.w_torch, ask(13, "Flashlight on"))
            m.updateAppWidget(id, v)
        }
    }
}
