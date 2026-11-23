package com.xalvxnlab.meshron.assistant

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The floating assistant: a small reactor orb over every app. Tap it to talk (or, with "Always
 * listening" on, just say "Hey Meshron…"); a slim strip slides out beside it with what you said and
 * the answer, then tucks away. Drag it anywhere; it settles on the nearest edge. Long-press opens
 * the full app.
 */
class FloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner, Host {
    companion object {
        @Volatile var running = false
        @Volatile var instance: FloatingService? = null
        /** Runs for the orb (if it may draw over apps) and for always listening (if the microphone is allowed). */
        fun start(c: Context) {
            val orb = Memory.floating(c) && Settings.canDrawOverlays(c)
            val ears = Memory.always(c) && Device.granted(c, Manifest.permission.RECORD_AUDIO)
            if (orb || ears) runCatching { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, FloatingService::class.java)) }
        }
        fun wanted(c: Context) = (Memory.floating(c) && Settings.canDrawOverlays(c)) || (Memory.always(c) && Device.granted(c, Manifest.permission.RECORD_AUDIO))
        fun stop(c: Context) { c.stopService(Intent(c, FloatingService::class.java)) }
        /** Restarts it so a new listening mode takes effect. */
        fun restart(c: Context) { if (running) { stop(c); Handler(Looper.getMainLooper()).postDelayed({ start(c) }, 400) } }
        private const val STOP = "stop"
    }

    // ---- Lifecycle for Compose inside a service window ----
    private val life = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = life
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override val ctx: Context get() = this
    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var view: android.view.View? = null
    private lateinit var brain: Brain
    private var listener: Listener? = null

    // What the orb shows.
    private var mode by mutableStateOf(Mode.Idle)
    private var level by mutableFloatStateOf(0f)
    private var strip by mutableStateOf<String?>(null) // the slim text strip; null = tucked away
    private var tag by mutableStateOf("")
    private var asking by mutableStateOf<Triple<String, String, CompletableDeferred<Boolean>>?>(null)
    private var onRight by mutableStateOf(true)
    /** A face skin: the orb is an island at the top centre of the screen. */
    var island by mutableStateOf(false)

    private var always = false
    private var busy = false
    private var wakeErrors = 0
    private var wakeOnDevice = true
    private var voiceTurn = false
    private var hideJob: Job? = null
    private val speakingHook: (Boolean) -> Unit = { on -> main.post { onSpeaking(on) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        life.currentState = Lifecycle.State.CREATED
        always = Memory.always(this) && Device.granted(this, Manifest.permission.RECORD_AUDIO)
        foreground()
        running = true; instance = this
        startShake()
        brain = Brain(Api(this), this)
        brain.onReply = { reply -> show(Memory.name(this).uppercase(), plain(reply), autoHide = !Memory.speak(this)); if (Memory.speak(this)) Speaker.say(this, reply) }
        Speaker.listeners += speakingHook

        wm = getSystemService(WindowManager::class.java)
        Look.load(this)
        island = Look.isFace(this)
        val (right, y) = Memory.orbSpot(this)
        onRight = right
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or if (island) Gravity.CENTER_HORIZONTAL else if (right) Gravity.END else Gravity.START
            x = 0
            this.y = if (island) (statusBar() * 0.15f).roundToInt() else (resources.displayMetrics.heightPixels * y).roundToInt().coerceIn(maxTop(), maxBottom())
        }
        val compose = ComposeView(this).apply { setContent { Bubble() } }
        view = DragFrame(this, compose).apply {
            setViewTreeLifecycleOwner(this@FloatingService)
            setViewTreeSavedStateRegistryOwner(this@FloatingService)
        }
        // The orb shows only if it is turned on; otherwise this just listens for the name.
        if (Memory.floating(this) && Settings.canDrawOverlays(this)) wm.addView(view, params) else view = null
        life.currentState = Lifecycle.State.RESUMED
        if (always) main.postDelayed({ listen(wake = true) }, 800)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { Memory.setFloating(this, false); stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false; instance = null
        stopShake()
        listener?.stop(); listener = null
        Speaker.listeners -= speakingHook
        runCatching { view?.let { wm.removeView(it) } }
        view = null
        life.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        super.onDestroy()
    }

    private fun foreground() {
        Memory.channel(this, "floating", "Floating assistant", NotificationManager.IMPORTANCE_MIN)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val hide = PendingIntent.getService(this, 1, Intent(this, FloatingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "floating").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (always) "Listening for \"Hey " + Memory.name(this) + "\"" else Memory.name(this) + " is ready")
            .setContentText(if (always) "Say \"Hey " + Memory.name(this) + "\" then your request." else "Tap the floating orb to talk.")
            .setOngoing(true).setContentIntent(open).addAction(0, "Hide", hide).build()
        val type = if (android.os.Build.VERSION.SDK_INT >= 34)
            (if (always) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else if (android.os.Build.VERSION.SDK_INT >= 30 && always) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, 9, n, type)
    }

    // ---- Talking ----

    /** wake = waiting for "Hey Meshron" quietly; otherwise listening to a request. */
    private fun listen(wake: Boolean) {
        listener?.stop()
        if (!wake) { mode = Mode.Listening; show("LISTENING", "…", autoHide = false) }
        listener = Listener(this,
            onLevel = { if (!wake) level = it },
            onPartial = { if (!wake) show("LISTENING", it, autoHide = false) },
            onDone = { text, err -> main.post { heard(wake, text, err) } },
            onDevice = wake && wakeOnDevice)
        listener?.start()
    }

    private fun heard(wake: Boolean, text: String?, err: String?) {
        level = 0f
        if (wake) {
            if (busy || mode == Mode.Speaking) return
            if (err != null && err != "busy") {
                // The on-device recogniser may lack this language: fall back to the regular one before giving up.
                if (++wakeErrors == 3 && wakeOnDevice) { wakeOnDevice = false; wakeErrors = 0 }
                if (wakeErrors >= 6) { always = false; show("SYSTEM", "Always listening stopped: $err", true); if (Memory.speak(this)) Speaker.say(this, "Always listening stopped. $err"); return }
            }
            else wakeErrors = 0
            val cmd = text?.let { Wake.command(it, Memory.name(this)) }
            when {
                cmd == null -> main.postDelayed({ if (always && !busy) listen(wake = true) }, if (err == "busy") 1500 else 250)
                cmd.isBlank() -> { Speaker.say(this, "Yes?"); main.postDelayed({ listen(wake = false) }, 700) }
                else -> { voiceTurn = true; run(cmd) }
            }
            return
        }
        mode = Mode.Idle
        when {
            !text.isNullOrBlank() -> { voiceTurn = true; run(text) }
            err != null && err != "busy" -> show("SYSTEM", err, true)
            else -> { voiceTurn = false; strip = null; resumeWake() }
        }
    }

    private fun run(text: String) {
        busy = true
        mode = Mode.Thinking
        show("YOU", text, autoHide = false)
        scope.launch {
            brain.ask(text)
            (brain.lines.lastOrNull() as? Line.Oops)?.let { show("SYSTEM", it.text, true) }
            busy = false
            if (mode == Mode.Thinking) mode = Mode.Idle
            if (!Speaker.talking) resumeWake()
        }
    }

    private fun onSpeaking(on: Boolean) {
        if (on) { mode = Mode.Speaking; listener?.stop() }
        else {
            if (mode == Mode.Speaking) mode = if (busy) Mode.Thinking else Mode.Idle
            hideSoon(4000)
            // Conversation: after answering something said out loud, listen again for the reply (no wake word).
            if (voiceTurn && asking == null) main.postDelayed({ if (mode == Mode.Idle && !busy) listen(wake = false) }, 400) else resumeWake()
        }
    }

    private fun resumeWake() { if (always && !busy) main.postDelayed({ if (always && !busy && !Speaker.talking) listen(wake = true) }, 500) }

    private fun tap() {
        when {
            mode == Mode.Listening -> { listener?.stop(); mode = Mode.Idle; strip = null; resumeWake() }
            mode == Mode.Speaking -> Speaker.stop()
            busy -> {}
            !Device.granted(this, Manifest.permission.RECORD_AUDIO) -> { show("SYSTEM", "Open Meshron Assistant once and allow the microphone.", true); openApp() }
            else -> { Speaker.stop(); listen(wake = false) }
        }
    }

    private fun show(t: String, text: String, autoHide: Boolean) {
        tag = t; strip = text
        hideJob?.cancel()
        if (autoHide) hideSoon(6000)
    }

    private fun hideSoon(ms: Long) {
        hideJob?.cancel()
        hideJob = scope.launch { delay(ms); if (asking == null && mode == Mode.Idle) strip = null }
    }

    private fun openApp() = startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Start listening now (from the button combo, the accessibility button or a shake). */
    fun wake() = main.post { if (mode != Mode.Listening && !busy) tap() }

    // ---- Shake to talk (optional) ----
    private var shaker: android.hardware.SensorEventListener? = null
    private fun startShake() {
        if (!Memory.shake(this)) return
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        var last = 0L; var hits = 0; var firstHit = 0L
        shaker = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val g = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / android.hardware.SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                if (g < 2.4f || now - last < 120) return
                last = now
                if (now - firstHit > 900) { firstHit = now; hits = 0 }
                // Two strong shakes within a second: a deliberate shake, not a bump.
                if (++hits >= 2) { hits = 0; firstHit = 0; wake() }
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(shaker, acc, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }
    private fun stopShake() { shaker?.let { getSystemService(android.hardware.SensorManager::class.java).unregisterListener(it) }; shaker = null }

    // ---- Host (the tools ask the orb, not a screen) ----
    override suspend fun approve(title: String, detail: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        asking = Triple(title, detail, d)
        if (Memory.speak(this)) Speaker.say(this, title)
        return d.await().also { asking = null }
    }
    override suspend fun permission(name: String) = Device.granted(this, name) || system(ProxyActivity.Req.Perm(name)) == true
    override suspend fun takePhoto(): Bitmap? = system(ProxyActivity.Req.Photo) as? Bitmap
    override suspend fun pickDocument(): Picked? = (system(ProxyActivity.Req.Doc) as? android.net.Uri)?.let { readDocument(this, it) }

    // ---- The orb and its strip ----
    @Composable
    private fun Bubble() {
        if (island) {
            // Like an island in the status bar: a black pill with the face, growing sideways with the text.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black).padding(3.dp)
            ) {
                Box(Modifier.size(50.dp).pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { openApp() }) }, contentAlignment = Alignment.Center) { Reactor(mode, level, Modifier.size(48.dp)) }
                AnimatedVisibility(strip != null || asking != null, enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start), exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)) { IslandText() }
            }
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
            if (!onRight) Orb()
            AnimatedVisibility(strip != null || asking != null, enter = fadeIn() + expandHorizontally(expandFrom = if (onRight) Alignment.End else Alignment.Start),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = if (onRight) Alignment.End else Alignment.Start)) { Strip() }
            if (onRight) Orb()
        }
    }

    @Composable
    private fun Orb() {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(Color(0xE604060C)).border(1.dp, Hud.line, CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { openApp() }) },
            contentAlignment = Alignment.Center
        ) { Reactor(mode, level, Modifier.size(50.dp)) }
    }

    /** After a drag, the orb settles on the nearest side edge (and remembers where). */
    /** Keep the orb below the status bar and above the gesture bar, where Android owns touches. */
    private fun statusBar(): Int = resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it > 0 }?.let { resources.getDimensionPixelSize(it) } ?: (28 * resources.displayMetrics.density).roundToInt()
    private fun maxTop() = statusBar() + (56 * resources.displayMetrics.density).roundToInt()
    private fun maxBottom() = resources.displayMetrics.heightPixels - (170 * resources.displayMetrics.density).roundToInt()

    val paramsX get() = params.x
    val paramsY get() = params.y

    /** Follows the finger: [dx]/[dy] is how far it travelled on screen since the drag began. */
    fun moveTo(startX: Int, startY: Int, dx: Float, dy: Float) {
        if (island) return
        params.x = (startX + (if (onRight) -dx else dx)).roundToInt().coerceAtLeast(0)
        params.y = (startY + dy).roundToInt().coerceIn(maxTop(), maxBottom())
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun settle() {
        if (island) return
        val w = resources.displayMetrics.widthPixels
        val orbPx = (60 * resources.displayMetrics.density).roundToInt()
        val left = if (onRight) w - params.x - orbPx else params.x
        val right = left + orbPx / 2 > w / 2
        onRight = right
        params.gravity = Gravity.TOP or if (right) Gravity.END else Gravity.START
        params.x = 0
        runCatching { wm.updateViewLayout(view, params) }
        Memory.setOrbSpot(this, right, params.y / resources.displayMetrics.heightPixels.toFloat())
    }

    @Composable
    private fun IslandText() {
        val a = asking
        Column(Modifier.widthIn(max = 240.dp).padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)) {
            Text(if (a != null) "APPROVE?" else tag, color = if (a != null) Hud.amber else Color(Look.of(Look.skin).c1), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text(if (a != null) a.first + "\n" + a.second else strip.orEmpty(), color = Hud.text, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (a != null) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cancel", color = Hud.text, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Hud.line, RoundedCornerShape(50)).clickable { a.third.complete(false) }.padding(horizontal = 12.dp, vertical = 6.dp))
                Text("Approve", color = Color(0xFF1A1000), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Hud.amber).clickable { a.third.complete(true) }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }

    @Composable
    private fun Strip() {
        val a = asking
        Column(
            Modifier.padding(horizontal = 6.dp).widthIn(max = 250.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0xF00A1220)).border(1.dp, if (a != null) Hud.amber.copy(alpha = 0.6f) else Hud.line, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Text(if (a != null) "APPROVE?" else tag, color = if (a != null) Hud.amber else Color(Look.of(Look.skin).c1), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text(if (a != null) a.first + "\n" + a.second else strip.orEmpty(), color = Hud.text, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (a != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cancel", color = Hud.text, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Hud.line, RoundedCornerShape(50)).clickable { a.third.complete(false) }.padding(horizontal = 12.dp, vertical = 6.dp))
                Text("Approve", color = Color(0xFF1A1000), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Hud.amber).clickable { a.third.complete(true) }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

/**
 * Holds the orb's window content and moves the window when it's dragged. Drags are measured in
 * screen coordinates (rawX/rawY): measured inside the window, they'd be skewed by the window moving
 * under the finger. A touch that doesn't travel past the touch slop stays a tap or long-press for
 * the orb itself.
 */
private class DragFrame(private val s: FloatingService, child: android.view.View) : android.widget.FrameLayout(s) {
    private val slop = android.view.ViewConfiguration.get(s).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private var dragging = false

    init { addView(child) }

    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = s.paramsX; startY = s.paramsY; dragging = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!dragging && (dx * dx + dy * dy) > slop * slop) {
                    dragging = true
                    // Let the orb know this isn't a tap after all.
                    super.dispatchTouchEvent(android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL })
                }
                if (dragging) { s.moveTo(startX, startY, dx, dy); return true }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; s.settle(); return true }
        }
        return super.dispatchTouchEvent(e)
    }
}
