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
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.max
import kotlin.math.hypot
import kotlin.random.Random
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.ui.layout.onSizeChanged

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
            if (orb || ears || Remote.accept(c)) runCatching { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, FloatingService::class.java)) }
        }
        fun wanted(c: Context) = (Memory.floating(c) && Settings.canDrawOverlays(c)) || (Memory.always(c) && Device.granted(c, Manifest.permission.RECORD_AUDIO)) || Remote.accept(c)
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
        brain.onReply = { reply ->
            remoteCmd?.let { Remote.answer(this, it, plain(reply)); remoteAnswered = true }
            show(Memory.name(this).uppercase(), plain(reply), autoHide = !Memory.speak(this)); if (Memory.speak(this)) Speaker.say(this, reply)
        }
        // Commands from the person's other devices (when allowed), one at a time.
        Remote.onCommand = { m -> scope.launch { runRemote(m) } }
        Remote.approver = { t, d -> approve(t, d) }
        Remote.start(this)
        Speaker.listeners += speakingHook

        wm = getSystemService(WindowManager::class.java)
        Look.load(this)
        island = Look.isFace(this)
        palSkin = Look.of(Look.skin).takeIf { it.pal != null }
        val (right, y) = Memory.orbSpot(this)
        onRight = right
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or if (palSkin != null) Gravity.START else if (island) Gravity.CENTER_HORIZONTAL else if (right) Gravity.END else Gravity.START
            x = 0
            this.y = if (island) (statusBar() * 0.15f).roundToInt() else (resources.displayMetrics.heightPixels * y).roundToInt().coerceIn(maxTop(), maxBottom())
            // Left on the top or bottom edge last time: put it back where it was along that edge.
            val prefs = getSharedPreferences("memory", MODE_PRIVATE)
            if (!island && palSkin == null && prefs.getString("orbEdge", "") in setOf("top", "bottom")) {
                val w = resources.displayMetrics.widthPixels; val cx = (prefs.getFloat("orbX", 0.5f) * w).roundToInt(); val orbPx = (60 * resources.displayMetrics.density).roundToInt()
                x = if (right) (w - cx - orbPx / 2).coerceAtLeast(0) else (cx - orbPx / 2).coerceAtLeast(0)
            }
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
        if (palSkin != null && view != null) startPal()
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
        palJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun foreground() {
        Memory.channel(this, "floating", "Floating assistant", NotificationManager.IMPORTANCE_MIN)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val hide = PendingIntent.getService(this, 1, Intent(this, FloatingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "floating").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (always) "Listening for \"Hey " + Memory.name(this) + "\"" else if (Remote.accept(this) && !Memory.floating(this)) Memory.name(this) + ": ready for your other devices" else Memory.name(this) + " is ready")
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
    private var remoteCmd: org.json.JSONObject? = null
    private var remoteAnswered = false
    private val remoteLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun runRemote(m: org.json.JSONObject) = remoteLock.withLock {
        while (busy) delay(300)
        remoteCmd = m; remoteAnswered = false; brain.remoteRun = true
        show("FROM " + m.optString("fromName", "OTHER DEVICE").uppercase(), m.optString("text"), autoHide = false)
        try { brain.ask(m.optString("text")) } finally {
            if (!remoteAnswered) Remote.answer(this, m, brain.lines.lastOrNull()?.let { (it as? Line.Oops)?.text ?: (it as? Line.Act)?.let { a -> a.label + ": " + a.result } } ?: "Done.")
            brain.remoteRun = false; remoteCmd = null
        }
    }

    override suspend fun approve(title: String, detail: String): Boolean {
        // Running another device's command: that device approves (nobody may be at this phone).
        remoteCmd?.let { return Remote.askApproval(this, it, title, detail) }
        val d = CompletableDeferred<Boolean>()
        asking = Triple(title, detail, d)
        if (Memory.speak(this)) Speaker.say(this, title)
        return d.await().also { asking = null }
    }
    override suspend fun permission(name: String) = Device.granted(this, name) || system(ProxyActivity.Req.Perm(name)) == true
    override suspend fun takePhoto(): Bitmap? = system(ProxyActivity.Req.Photo) as? Bitmap
    override suspend fun pickDocument(): Picked? = (system(ProxyActivity.Req.Doc) as? android.net.Uri)?.let { readDocument(this, it) }

    // ---- The orb and its strip ----
    // ---- Companions (Strider walks, Wisp flies, Drift floats, Bolt walks and jet-flies) ----
    // It lives on the screen: walks along the bottom, flies up and sits on the top edge over your
    // apps, naps when left alone, stops to listen and talk. Tap: talk. Drag: carry or throw it.
    var palSkin: Skin? = null
    private var palPose by mutableStateOf(PalPose())
    private var px = 0f; private var py = 0f; private var vx = 0f; private var vy = 0f
    private var palMode = "fall"; private var palTarget: Pair<Float, Float>? = null
    private var palIdle = 0f; private var palPhase = 0f; private var palFacing = 1; private var palSleep = false; private var palDance = 0f; private var palHidden = false; private var palPerch = false
    private var palWinW = 0; private var palWinH = 0
    private var palJob: Job? = null
    private val dpx get() = resources.displayMetrics.density
    private fun floorY() = resources.displayMetrics.heightPixels - 6 * dpx
    private fun topY() = statusBar() + 4 * dpx

    private fun startPal() {
        val w = resources.displayMetrics.widthPixels.toFloat()
        px = w * (0.3f + Random.nextFloat() * 0.4f); py = topY() + 80 * dpx; palMode = if (palSkin?.pal == "fly") "fly" else "fall"
        palJob = scope.launch {
            var last = System.nanoTime()
            while (true) {
                delay(16)
                val now = System.nanoTime(); val dt = min(0.05f, (now - last) / 1e9f); last = now
                palStep(dt)
                placePal()
            }
        }
    }
    private fun placePal() {
        val v = view ?: return
        if (palWinW == 0) return
        params.x = (px - palWinW / 2f).roundToInt().coerceIn(0, max(0, resources.displayMetrics.widthPixels - palWinW))
        params.y = (py - palWinH).roundToInt().coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, params) }
    }
    private fun palTargetNew() {
        val w = resources.displayMetrics.widthPixels.toFloat(); val k = palSkin?.pal
        val top = (k == "fly" || k == "jet") && Random.nextFloat() < 0.4f
        palTarget = (w * (0.1f + Random.nextFloat() * 0.8f)) to (if (top) topY() else floorY())
    }
    private fun palStep(dt: Float) {
        val k = palSkin?.pal ?: return
        if (palMode == "held" || palHidden) return
        val talking = mode != Mode.Idle || asking != null
        if (talking) { palSleep = false; palIdle = 0f }
        if (palDance > 0) palDance -= dt
        val w = resources.displayMetrics.widthPixels.toFloat()
        val air: Boolean
        when {
            k == "float" -> {
                // Drift: floats and bobs, wandering slowly through the middle of the screen.
                val tg = palTarget
                if (tg == null || hypot(tg.first - px, tg.second - py) < 30 * dpx || Random.nextFloat() < 0.002f) palTarget = (w * (0.15f + Random.nextFloat() * 0.7f)) to (topY() + 160 * dpx + Random.nextFloat() * (floorY() - topY() - 320 * dpx))
                palTarget?.let { (tx, ty) -> if (!talking) { vx += (tx - px) * 0.6f * dt; vy += (ty - py) * 0.6f * dt } }
                vx *= 0.97f; vy *= 0.97f; px += vx * dt; py += vy * dt + sin(System.nanoTime() / 6e8f) * 0.4f
                py = py.coerceIn(topY() + 130 * dpx, floorY() - 20 * dpx); palFacing = if (vx >= 0) 1 else -1; air = true
            }
            palMode == "fly" -> {
                val tg = palTarget ?: run { palTargetNew(); palTarget!! }
                val dx = tg.first - px; val dy = tg.second - py; val dist = hypot(dx, dy)
                val sp = 300 * dpx
                vx += ((dx / max(1f, dist)) * sp - vx) * min(1f, dt * 2.5f); vy += ((dy / max(1f, dist)) * sp - vy) * min(1f, dt * 2.5f)
                if (talking) { vx *= 0.9f; vy *= 0.9f }
                px += vx * dt; py += vy * dt
                if (kotlin.math.abs(dx) > 2) palFacing = if (dx > 0) 1 else -1
                if (dist < 12 * dpx) { py = tg.second; palMode = "stand"; palPerch = tg.second < floorY() - 10; palTarget = null; vx = 0f; vy = 0f; palIdle = 0f }
                air = true
            }
            palMode == "fall" -> {
                vy += 2400 * dt; px += vx * dt; py += vy * dt
                if (py >= floorY()) { py = floorY(); vx = 0f; vy = 0f; palMode = "stand"; palPerch = false }
                air = true
            }
            else -> {
                air = false
                palPhase += if (palPose.walking) dt * 9 else 0f
                if (talking || palDance > 0) { palPose = palPose.copy(walking = false); placeDone(); return }
                palIdle += dt
                if (palSleep) { if (palIdle > 40) { palSleep = false; palIdle = 0f } } else if (palIdle > 28 && Random.nextFloat() < 0.01f) palSleep = true
                else {
                    if (palTarget == null && palIdle > 2.5f + Random.nextFloat() * 4) { palTargetNew(); palIdle = 0f }
                    palTarget?.let { (tx, ty) ->
                        if (kotlin.math.abs(ty - py) > 8) {
                            // Another level: fly (Wisp, Bolt) or fall off the top edge (a walker on top).
                            if (k == "fly" || k == "jet") { palMode = "fly"; return } else if (ty > py) { palMode = "fall"; return } else palTarget = tx to py
                        }
                        val dx = tx - px
                        if (kotlin.math.abs(dx) < 4) { palTarget = null; palIdle = 0f } else { palFacing = if (dx > 0) 1 else -1; px += palFacing * min(kotlin.math.abs(dx), 90 * dpx * dt) }
                    }
                }
            }
        }
        px = px.coerceIn(30 * dpx, w - 30 * dpx)
        palPose = PalPose(walking = palMode == "stand" && palTarget != null && !palSleep, phase = palPhase, air = air && !(k == "fly" && palMode == "stand"), perch = palPerch && palMode == "stand", facing = palFacing, sleep = palSleep)
    }
    private fun placeDone() {}

    /** Companion commands: come, sit, sleep, wake, dance, fly, left, right, jump, hide, show. */
    fun palCommand(action: String): String {
        if (palSkin == null) return "Pick a companion first (Look: Strider, Wisp, Drift or Bolt)."
        main.post {
            palSleep = false; palIdle = 0f
            val w = resources.displayMetrics.widthPixels.toFloat(); val k = palSkin?.pal
            when (action) {
                "come" -> { palTarget = (w / 2) to floorY(); if (k == "fly" || k == "jet") palMode = "fly" }
                "sit" -> { palTarget = (w / 2) to topY(); if (k == "fly" || k == "jet") palMode = "fly" else { palMode = "fall"; vy = -2200f } }
                "sleep" -> { palSleep = true; palTarget = null }
                "dance" -> { palDance = 4f; Look.feel("happy", 4000) }
                "fly" -> if (k == "fly" || k == "jet") { palMode = "fly"; palTarget = (w * Random.nextFloat()) to (topY() + 200 * dpx) } else { palMode = "fall"; vy = -1600f }
                "left" -> palTarget = 40 * dpx to py
                "right" -> palTarget = (w - 40 * dpx) to py
                "jump" -> { palMode = "fall"; vy = -1500f; vx = palFacing * 300f }
                "hide" -> { palHidden = true; view?.visibility = android.view.View.INVISIBLE }
                "show" -> { palHidden = false; view?.visibility = android.view.View.VISIBLE }
            }
        }
        return mapOf("come" to "Coming!", "sit" to "Sitting on top of your apps.", "sleep" to "Taking a nap.", "wake" to "I'm awake!", "dance" to "Dancing!", "fly" to "Up we go!", "left" to "Heading left.", "right" to "Heading right.", "jump" to "Jump!", "hide" to "Hiding. Say \"show yourself\" to bring me back.", "show" to "Here I am!")[action] ?: "Okay."
    }

    @Composable
    private fun PalView() {
        val skin = palSkin ?: return
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.onSizeChanged { palWinW = it.width; palWinH = it.height }) {
            AnimatedVisibility(strip != null || asking != null, enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom), exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)) { Strip() }
            Box(Modifier.size(112.dp, 124.dp).pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { openApp() }) }) {
                SkinOrb(mode, level, Modifier.size(112.dp, 124.dp), skinId = skin.id, pose = palPose)
            }
        }
    }

    @Composable
    private fun Bubble() {
        if (palSkin != null) { PalView(); return }
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
        if (palSkin != null) { palMode = "held"; px = startX + palWinW / 2f + dx; py = startY + palWinH + dy; placePal(); return }
        if (island) return
        params.x = (startX + (if (onRight) -dx else dx)).roundToInt().coerceAtLeast(0)
        params.y = (startY + dy).roundToInt().coerceIn(maxTop(), maxBottom())
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun settle() {
        if (palSkin == null && !island) {
            // Nearest edge: left, right, top or bottom (top and bottom keep where along it you left it).
            val w = resources.displayMetrics.widthPixels; val h = resources.displayMetrics.heightPixels
            val orbPx = (60 * resources.displayMetrics.density).roundToInt()
            val left = if (onRight) w - params.x - orbPx else params.x
            val cx = left + orbPx / 2; val cy = params.y + orbPx / 2
            val d = mapOf("left" to cx, "right" to w - cx, "top" to cy - maxTop(), "bottom" to maxBottom() + orbPx - cy)
            val edge = d.minByOrNull { it.value }!!.key
            if (edge == "top" || edge == "bottom") {
                onRight = cx > w / 2
                params.gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
                params.x = if (onRight) (w - cx - orbPx / 2).coerceAtLeast(0) else (cx - orbPx / 2).coerceAtLeast(0)
                params.y = if (edge == "top") maxTop() else maxBottom()
                runCatching { wm.updateViewLayout(view, params) }
                getSharedPreferences("memory", MODE_PRIVATE).edit().putString("orbEdge", edge).putFloat("orbX", cx / w.toFloat()).apply()
                Memory.setOrbSpot(this, onRight, params.y / h.toFloat())
                return
            }
            getSharedPreferences("memory", MODE_PRIVATE).edit().putString("orbEdge", edge).apply()
        }
        if (palSkin != null) { palMode = if (palSkin?.pal == "fly") "fly" else if (palSkin?.pal == "float") "stand" else "fall"; vy = 0f; vx = 0f; palTarget = null; Look.feel("surprised", 900); return }
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
