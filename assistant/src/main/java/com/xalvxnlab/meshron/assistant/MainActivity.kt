package com.xalvxnlab.meshron.assistant

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), Host {
    override val ctx: android.content.Context get() = this
    private lateinit var api: Api
    private lateinit var brain: Brain

    // Results from the system screens (permission, camera, file picker) come back here.
    private var permWait: CompletableDeferred<Boolean>? = null
    private var photoWait: CompletableDeferred<Bitmap?>? = null
    private var docWait: CompletableDeferred<Uri?>? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permWait?.complete(it) }
    private val photoLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { photoWait?.complete(it) }
    private val docLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { docWait?.complete(it) }

    // The approval panel.
    private val approval = mutableStateOf<Triple<String, String, CompletableDeferred<Boolean>>?>(null)
    private val wakeListening = mutableStateOf(0)
    /** A request from the home-screen widget, asked as soon as the screen is up. */
    private val pendingAsk = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        Look.load(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        api = Api(this)
        brain = Brain(api, this)
        brain.onReply = { if (Memory.speak(this)) Speaker.say(this, it) }
        if (isAssist(intent)) wakeListening.value++
        intent?.getStringExtra("ask")?.let { pendingAsk.value = it }
        setContent { App() }
    }

    // Coming back from "Display over other apps": turn the orb on if it was just allowed.
    private val overlayAllowed = mutableStateOf(false)
    override fun onResume() {
        super.onResume()
        overlayAllowed.value = Settings.canDrawOverlays(this)
        if (api.signedIn && FloatingService.wanted(this)) FloatingService.start(this)
        // Answers and approval questions for commands sent from this screen to the person's other devices.
        if (api.signedIn) { Remote.approver = { t, d -> approve(t, d) }; Remote.start(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isAssist(intent)) wakeListening.value++
        intent.getStringExtra("ask")?.let { pendingAsk.value = it }
    }

    private fun isAssist(i: Intent?) = i != null && (i.getBooleanExtra("assist", false) || i.action == Intent.ACTION_ASSIST || i.action == Intent.ACTION_VOICE_COMMAND)

    // ---- Host ----
    override suspend fun approve(title: String, detail: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        approval.value = Triple(title, detail, d)
        if (Memory.speak(this)) Speaker.say(this, title)
        return d.await().also { approval.value = null }
    }
    override suspend fun permission(name: String): Boolean {
        if (Device.granted(this, name)) return true
        val d = CompletableDeferred<Boolean>(); permWait = d
        permLauncher.launch(name)
        return d.await()
    }
    override suspend fun takePhoto(): Bitmap? { val d = CompletableDeferred<Bitmap?>(); photoWait = d; photoLauncher.launch(null); return d.await() }
    override suspend fun pickDocument(): Picked? {
        val d = CompletableDeferred<Uri?>(); docWait = d
        docLauncher.launch(arrayOf("image/*", "application/pdf", "text/*"))
        val uri = d.await() ?: return null
        return withContext(Dispatchers.IO) { readDoc(uri) }
    }

    private fun readDoc(uri: Uri): Picked? = runCatching {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "document"
        val type = contentResolver.getType(uri).orEmpty()
        when {
            type.startsWith("image/") -> Picked(name, null, ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE })
            type == "application/pdf" -> contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                PdfRenderer(fd).use { pdf ->
                    pdf.openPage(0).use { p ->
                        val k = 1400f / maxOf(p.width, p.height)
                        val bmp = Bitmap.createBitmap((p.width * k).toInt(), (p.height * k).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        Picked(name + if (pdf.pageCount > 1) " (page 1 of ${pdf.pageCount})" else "", null, bmp)
                    }
                }
            }
            else -> Picked(name, contentResolver.openInputStream(uri)!!.use { it.readBytes().take(60_000).toByteArray().toString(Charsets.UTF_8) }, null)
        }
    }.getOrNull()

    // ---- Screens ----
    @Composable
    private fun App() {
        var signedIn by remember { mutableStateOf(api.signedIn) }
        var settings by remember { mutableStateOf(false) }
        var help by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize().background(Hud.bg)) {
            HudGrid()
            when {
                !signedIn -> SignIn { signedIn = true }
                help -> HelpScreen(Memory.name(this@MainActivity), onBack = { help = false }, onTry = { t -> help = false; settings = false; scope.launch { brain.ask(t) } })
                settings -> SettingsScreen(onBack = { settings = false }, onSignOut = { api.signOut(); brain.reset(); signedIn = false; settings = false }, onHelp = { help = true })
                else -> Home(onSettings = { settings = true })
            }
        }
    }

    @Composable
    private fun SignIn(onDone: () -> Unit) {
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Reactor(if (busy) Mode.Thinking else Mode.Idle, 0f, Modifier.size(180.dp))
            Spacer(Modifier.height(24.dp))
            Text("MESHRON ASSISTANT", color = Hud.cyan, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Sign in with your Meshron account", color = Hud.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
            HudField(user, { user = it.trim() }, "@username")
            Spacer(Modifier.height(12.dp))
            HudField(pass, { pass = it }, "Password", secret = true)
            error?.let { Text(it, color = Hud.red, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(22.dp))
            HudButton(if (busy) "CONNECTING…" else "SIGN IN", enabled = !busy && user.isNotBlank() && pass.isNotEmpty()) {
                busy = true; error = null
                scope.launch {
                    try { api.signIn(user, pass); pass = ""; onDone() } catch (e: ApiError) { error = e.message } finally { busy = false }
                }
            }
            Text("No account? Make one in the Meshron app. Your password never leaves this phone.", color = Hud.dim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        }
    }

    @Composable
    private fun Home(onSettings: () -> Unit) {
        val scope = rememberCoroutineScope()
        var input by remember { mutableStateOf("") }
        var listening by remember { mutableStateOf(false) }
        var partial by remember { mutableStateOf("") }
        var level by remember { mutableFloatStateOf(0f) }
        var speaking by remember { mutableStateOf(false) }
        val thinking by brain.thinking
        val listState = rememberLazyListState()
        androidx.compose.runtime.DisposableEffect(Unit) { val f: (Boolean) -> Unit = { speaking = it }; Speaker.listeners += f; onDispose { Speaker.listeners -= f } }

        fun send(text: String, photo: Bitmap? = null) { if (text.isBlank() && photo == null) return; Speaker.stop(); scope.launch { brain.ask(text, photo) } }
        val listener = remember {
            Listener(this@MainActivity, onLevel = { level = it }, onPartial = { partial = it }, onDone = { text, err ->
                listening = false; level = 0f; partial = ""
                if (err != null) brain.lines += Line.Oops(err) else if (!text.isNullOrBlank()) send(text)
            })
        }
        fun listen() {
            if (listening) { listener.stop(); listening = false; return }
            Speaker.stop()
            scope.launch { if (permission(Manifest.permission.RECORD_AUDIO)) { listening = true; partial = ""; listener.start() } }
        }
        LaunchedEffect(wakeListening.value) { if (wakeListening.value > 0) listen() }
        LaunchedEffect(pendingAsk.value) { pendingAsk.value?.let { pendingAsk.value = null; send(it) } }
        LaunchedEffect(brain.lines.size) { if (brain.lines.isNotEmpty()) listState.animateScrollToItem(brain.lines.size - 1) }

        val mode = when { listening -> Mode.Listening; thinking -> Mode.Thinking; speaking -> Mode.Speaking; else -> Mode.Idle }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Memory.name(this@MainActivity).uppercase(), color = Hud.cyan, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(" // ASSISTANT", color = Hud.dim, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (brain.lines.isNotEmpty()) IconTap(Icons.Rounded.Refresh, "New conversation") { brain.reset() }
                IconTap(Icons.Rounded.Tune, "Settings", onSettings)
            }
            // The reactor and what it's doing
            Box(Modifier.fillMaxWidth().height(if (brain.lines.isEmpty()) 300.dp else 190.dp), contentAlignment = Alignment.Center) {
                Reactor(mode, level, Modifier.size(if (brain.lines.isEmpty()) 230.dp else 150.dp).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { listen() })
            }
            Text(
                when (mode) { Mode.Listening -> partial.ifBlank { "LISTENING" }; Mode.Thinking -> "PROCESSING"; Mode.Speaking -> "SPEAKING"; Mode.Idle -> if (brain.lines.isEmpty()) (if (Memory.always(this@MainActivity)) "SAY \"HEY " + Memory.name(this@MainActivity).uppercase() + "\" OR TAP THE CORE" else "TAP THE CORE OR SAY SOMETHING") else "STANDING BY" },
                color = if (mode == Mode.Listening && partial.isNotBlank()) Hud.text else Hud.cyan.copy(alpha = 0.8f),
                fontFamily = if (mode == Mode.Listening && partial.isNotBlank()) FontFamily.Default else FontFamily.Monospace,
                letterSpacing = if (mode == Mode.Listening && partial.isNotBlank()) 0.sp else 3.sp, fontSize = if (mode == Mode.Listening && partial.isNotBlank()) 17.sp else 11.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
            // Conversation, or ideas to start with
            if (brain.lines.isEmpty()) {
                Spacer(Modifier.weight(1f))
                LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Plan my day", "What's on my screen?", "Triage my notifications", "What am I looking at?", "Remind me to drink water in 1 hour", "Flashlight on", "Latest news")) { s ->
                        Text(s, color = Hud.text, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Hud.line, RoundedCornerShape(50)).clickable { send(s) }.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(brain.lines) { LineView(it) }
                }
            }
            // Input
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTap(Icons.Rounded.CameraAlt, "Look with the camera") { scope.launch { takePhoto()?.let { send(input.ifBlank { "What am I looking at?" }, it); input = "" } } }
                IconTap(Icons.Rounded.Description, "Read a document or photo") {
                    scope.launch {
                        val q = input.ifBlank { "Explain this simply, with the key points." }
                        val p = pickDocument() ?: return@launch
                        input = ""; Speaker.stop()
                        if (p.image != null) brain.ask(q, p.image, shown = "📄 ${p.name} — $q")
                        else brain.ask(q + "\n\nDocument \"" + p.name + "\":\n" + p.text.orEmpty().take(12_000), shown = "📄 ${p.name} — $q")
                    }
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).border(1.dp, Hud.line, RoundedCornerShape(24.dp)).background(Hud.panel).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (input.isEmpty()) Text("Ask or command…", color = Hud.dim, fontSize = 15.sp)
                    BasicTextField(input, { input = it }, textStyle = TextStyle(color = Hud.text, fontSize = 15.sp), cursorBrush = SolidColor(Hud.cyan), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(input); input = "" }), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                val hasText = input.isNotBlank()
                Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Hud.blue, Hud.cyan))).clickable { if (hasText) { send(input); input = "" } else listen() }, contentAlignment = Alignment.Center) {
                    Icon(if (hasText) Icons.AutoMirrored.Rounded.Send else if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, if (hasText) "Send" else "Speak", tint = Color(0xFF02060C))
                }
            }
        }
        // Approval before anything that sends, calls or replies.
        val ask = approval.value
        AnimatedVisibility(ask != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}, contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Hud.panel).border(1.dp, Hud.amber.copy(alpha = 0.6f), RoundedCornerShape(24.dp)).padding(22.dp)) {
                    Text("APPROVAL NEEDED", color = Hud.amber, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(ask?.first.orEmpty(), color = Hud.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text(ask?.second.orEmpty(), color = Hud.dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(50)).border(1.dp, Hud.line, RoundedCornerShape(50)).clickable { ask?.third?.complete(false) }, contentAlignment = Alignment.Center) { Text("Cancel", color = Hud.text, fontWeight = FontWeight.SemiBold) }
                        Box(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(50)).background(Hud.amber).clickable { ask?.third?.complete(true) }, contentAlignment = Alignment.Center) { Text("Approve", color = Color(0xFF1A1000), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        BackHandler(ask != null) { ask?.third?.complete(false) }

        // Once: may it download the on-phone (Kokoro) voices? Then it fetches them in the background.
        var offerKokoro by remember { mutableStateOf(Kokoro.ENABLED && !Memory.kokoroAsked(this) && !Kokoro.ready(this)) }
        var kokoroPct by remember { mutableStateOf(Kokoro.progress) }
        LaunchedEffect(Unit) { while (true) { kokoroPct = Kokoro.progress; kotlinx.coroutines.delay(700) } }
        if (kokoroPct >= 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Text("DOWNLOADING VOICES · $kokoroPct%", color = Hud.cyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.statusBarsPadding().padding(top = 46.dp))
        }
        if (offerKokoro && ask == null) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Hud.panel).border(1.dp, Hud.line, RoundedCornerShape(24.dp)).padding(22.dp)) {
                    Text("ON-DEVICE VOICES", color = Hud.cyan, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Download natural voices to this phone?", color = Hud.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text("Kylie, Alvin, Clement and more, that speak offline with no account. About 130 MB, once. Best on Wi-Fi.", color = Hud.dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(50)).border(1.dp, Hud.line, RoundedCornerShape(50))
                            .clickable { Memory.setKokoroAsked(this@MainActivity); offerKokoro = false }, contentAlignment = Alignment.Center) { Text("Not now", color = Hud.text, fontWeight = FontWeight.SemiBold) }
                        Box(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(50)).background(Hud.cyan)
                            .clickable { Memory.setKokoroAsked(this@MainActivity); offerKokoro = false; Kokoro.downloadInBackground(this@MainActivity) }, contentAlignment = Alignment.Center) { Text("Download", color = Color(0xFF02060C), fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    @Composable
    private fun LineView(l: Line) {
        when (l) {
            is Line.You -> Text(l.text, color = Hud.dim, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(start = 48.dp))
            is Line.Me -> Column(Modifier.fillMaxWidth().padding(end = 24.dp)) {
                Text(Memory.name(this@MainActivity).uppercase() + " ›", color = Hud.cyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp)
                Text(pretty(l.text), color = Hud.text, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 3.dp))
            }
            is Line.Act -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("▸ " + l.label, color = if (l.ok) Hud.green else Hud.amber, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("  " + (if (l.ok) "✓ " else "! ") + l.result, color = Hud.dim, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2)
            }
            is Line.Oops -> Text("⚠ " + l.text, color = Hud.red, fontSize = 14.sp)
            is Line.Pic -> Column(Modifier.fillMaxWidth().padding(end = 48.dp)) {
                l.thumb?.let { b ->
                    androidx.compose.foundation.Image(b.asImageBitmap(), l.caption.ifEmpty { "Picture" },
                        modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { Pictures.open(this@MainActivity, l.uri) },
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                } ?: Text("▸ picture saved to Pictures/Meshron", color = Hud.green, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.clickable { Pictures.open(this@MainActivity, l.uri) })
                if (l.caption.isNotEmpty()) Text(l.caption, color = Hud.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    @Composable
    private fun SettingsScreen(onBack: () -> Unit, onSignOut: () -> Unit, onHelp: () -> Unit) {
        BackHandler(onBack = onBack)
        var speak by remember { mutableStateOf(Memory.speak(this)) }
        var sentry by remember { mutableStateOf(Memory.sentry(this)) }
        var phrase by remember { mutableStateOf(Memory.lockPhrase(this)) }
        val scope = rememberCoroutineScope()
        val admin = ComponentName(this, LockAdmin::class.java)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconTap(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                Text("SYSTEMS", color = Hud.cyan, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Section("Help")
            SetRow("Help and commands", "How to use it, every command (tap one to try it) and the hand gestures.") { LinkText("Open", onHelp) }
            Section("Signed in as @" + api.username)
            var name by remember { mutableStateOf(Memory.name(this@MainActivity)) }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Name", color = Hud.text, fontSize = 16.sp)
                Text("What it's called, and what you say to wake it (\"Hey $name\"). Short, clear names like Nova, Jarvis or Atlas are heard best.", color = Hud.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
                HudField(name, { name = it.take(24); Memory.setName(this@MainActivity, name) }, "Meshron")
            }
            SetRow("Spoken replies", "Read answers and alerts out loud") { Switch(speak, { speak = it; Memory.setSpeak(this@MainActivity, it) }, colors = hudSwitch()) }
            VoicePicker()
            var studio by remember { mutableStateOf(Memory.studio(this@MainActivity)) }
            SetRow("Studio voice", "A lifelike AI voice (needs the internet). Off: the phone's own voice below.") { Switch(studio, { studio = it; Memory.setStudio(this@MainActivity, it) }, colors = hudSwitch()) }
            if (studio) {
                var sv by remember { mutableStateOf(Memory.studioVoice(this@MainActivity)) }
                var kokoroNote by remember { mutableStateOf<String?>(null) }
                val online = listOf("george" to "George", "daniel" to "Daniel", "brian" to "Brian", "adam" to "Adam", "sarah" to "Sarah", "charlotte" to "Charlotte", "lily" to "Lily", "rachel" to "Rachel", "ms:ava" to "Ava (Microsoft)", "ms:andrew" to "Andrew (Microsoft)", "ms:emma" to "Emma (Microsoft)", "ms:brian" to "Brian (Microsoft)", "ms:ryan" to "Ryan (Microsoft)", "ms:sonia" to "Sonia (Microsoft)", "ms:jenny" to "Jenny (Microsoft)", "ms:guy" to "Guy (Microsoft)")
                // Kokoro: on the phone, offline after a one-time download of about 130 MB.
                val onPhone = listOf("kokoro:af_heart" to "Kylie", "kokoro:af_bella" to "Glauxnycti", "kokoro:am_michael" to "Alvin", "kokoro:am_fenrir" to "Clement", "kokoro:am_puck" to "Xalvxn", "kokoro:bm_george" to "Kadmiel", "kokoro:bm_fable" to "Dhorpas")
                fun pick(id: String, label: String) {
                    sv = id; Memory.setStudioVoice(this@MainActivity, id); Speaker.stop()
                    val hello = "Hi, I'm " + label.substringBefore(" (") + ". This is how I'll sound."
                    if (!id.startsWith("kokoro:") || Kokoro.ready(this@MainActivity)) { kokoroNote = null; Speaker.say(this@MainActivity, hello); return }
                    scope.launch {
                        kokoroNote = "Downloading $label's voice (about 130 MB, once)…"
                        val ok = Kokoro.download(this@MainActivity) { p -> kokoroNote = "Downloading $label's voice… $p%" }
                        kokoroNote = if (ok) "$label is on this phone now and works offline." else "Couldn't download the voice: " + (Kokoro.lastError ?: "try again on Wi-Fi") + ". Until then it uses an online voice."
                        if (ok) Speaker.say(this@MainActivity, hello)
                    }
                }
                @Composable fun chips(list: List<Pair<String, String>>) = LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list) { (id, label) ->
                        Text(label, color = if (sv == id) Color(0xFF02060C) else Hud.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sv == id) Hud.cyan else Color.Transparent).border(1.dp, Hud.line, RoundedCornerShape(50))
                                .clickable { pick(id, label) }
                                .padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                Text("ONLINE", color = Hud.dim, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp))
                chips(online)
                if (Kokoro.ENABLED) Text("ON THIS PHONE · KOKORO" + if (Kokoro.ready(this@MainActivity)) " · DOWNLOADED" else " · ~130 MB ONCE", color = Hud.dim, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
                if (Kokoro.ENABLED) chips(onPhone)
                kokoroNote?.let { Text(it, color = Hud.cyan, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            }

            Section("Hands")
            SetRow("Act on screen", "Lets it tap, type, scroll, go home or back, and close apps when you ask, like \"open Instagram and scroll\" or \"text Sam on WhatsApp\". Turn on Meshron Assistant under Accessibility.") {
                LinkText(if (ControlService.on) "On" else "Turn on") { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            SetRow("Files it can organise", if (Memory.folder(this@MainActivity).isEmpty()) "Pick a folder (like Download or Documents) it may sort, make folders in and clean up." else "A folder is chosen. Pick another to change it.") {
                LinkText("Choose") { scope.launch { Device(this@MainActivity).run("choose_folder", org.json.JSONObject()) } }
            }

            Section("Ways to start it")
            var combo by remember { mutableStateOf(Memory.combo(this@MainActivity)) }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Button combo", color = Hud.text, fontSize = 16.sp)
                Text("Starts it from anywhere, even inside other apps. Needs \"Act on screen\" on." + if (!ControlService.on && combo != "off") " (It's off right now.)" else "", color = Hud.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
                for ((v, label) in listOf("off" to "Off", "down2" to "Volume down twice", "up2" to "Volume up twice", "updown" to "Volume up, then down", "both" to "Both volume keys together")) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { combo = v; Memory.setCombo(this@MainActivity, v) }.padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (combo == v) Hud.cyan else Hud.line))
                        Text(label, color = if (combo == v) Hud.text else Hud.dim, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            var shake by remember { mutableStateOf(Memory.shake(this@MainActivity)) }
            SetRow("Shake to talk", "Shake the phone twice, firmly. Works while the floating orb is on.") {
                Switch(shake, { shake = it; Memory.setShake(this@MainActivity, it); FloatingService.restart(this@MainActivity) }, colors = hudSwitch())
            }
            SetRow("Quick Settings tile", "Pull down the shade, tap the pencil, and drag \"Meshron\" into your tiles.") { Text("", color = Hud.dim) }
            SetRow("Home-screen widget", "Long-press your home screen, tap Widgets, and add \"Meshron Assistant\".") { Text("", color = Hud.dim) }

            Section("Your other devices")
            var remoteOn by remember { mutableStateOf(Remote.accept(this@MainActivity)) }
            var devs by remember { mutableStateOf(Remote.devices) }
            var target by remember { mutableStateOf(Remote.target(this@MainActivity)) }
            LaunchedEffect(Unit) { runCatching { devs = Remote.register(this@MainActivity) } }
            SetRow("Control from my other devices", "Your PC (signed in to the same account) can give this phone commands, like \"on my phone, set an alarm for 7\". Anything risky asks on the device that sent it.") {
                Switch(remoteOn, { on -> remoteOn = on; Remote.setAccept(this@MainActivity, on); scope.launch { runCatching { devs = Remote.register(this@MainActivity) } }; if (on) FloatingService.start(this@MainActivity) else FloatingService.restart(this@MainActivity) }, colors = hudSwitch())
            }
            val others = devs.filter { !it.me }
            Text(if (others.isEmpty()) "No other devices yet. Sign in to Meshron Assistant on your PC with the same account." else "Talk to: (or just say \"on my PC, …\")", color = Hud.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            if (others.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(Remote.Dev("", "This phone", "android", true, true, true)) + others).forEach { d ->
                    val on = (target.isEmpty() && d.id.isEmpty()) || target == d.id
                    Text(d.name + if (d.id.isEmpty()) "" else if (!d.online) " · offline" else if (!d.accept) " · not allowed" else "", color = if (on) Color(0xFF02060C) else Hud.text, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Hud.cyan else Hud.panel).border(1.dp, Hud.line, RoundedCornerShape(50)).clickable { target = d.id; Remote.setTarget(this@MainActivity, d.id) }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }

            Section("Game mode")
            SetRow("Game mode", "Games played with your hands and face: Hand School, Block Drop, Block Builder, Orb Pop, Face Dash, Air Keys, Mood Mirror and sign-language letters. Your camera picture stays hidden unless you turn it on.") {
                Text("Open", color = Hud.cyan, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Hud.cyan, RoundedCornerShape(50)).clickable { startActivity(Intent(this@MainActivity, GameActivity::class.java)) }.padding(horizontal = 14.dp, vertical = 8.dp))
            }

            Section("Look")
            Text("Orb skin. Skins with a face show feelings, and the floating one sits at the top of the screen like an island.", color = Hud.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Look.SKINS.size) { i ->
                    val k = Look.SKINS[i]
                    val on = Look.skin == k.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Hud.cyan.copy(alpha = 0.12f) else Hud.panel)
                            .border(1.dp, if (on) Hud.cyan else Hud.line, RoundedCornerShape(14.dp))
                            .clickable { Look.save(this@MainActivity, k.id); if (k.face) Look.feel("happy", 1600); FloatingService.restart(this@MainActivity) }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        SkinOrb(if (on) Mode.Speaking else Mode.Idle, 0f, Modifier.size(58.dp), skinId = k.id)
                        Text(k.name, color = Hud.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        if (k.pal != null) Text("COMPANION", color = Hud.lime, fontSize = 9.sp, letterSpacing = 1.sp) else if (k.island) Text("ISLAND", color = Hud.cyan, fontSize = 9.sp, letterSpacing = 1.sp) else if (k.face) Text("FACE", color = Hud.dim, fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                }
            }

            Section("Floating assistant")
            var floating by remember { mutableStateOf(Memory.floating(this@MainActivity) && overlayAllowed.value) }
            var always by remember { mutableStateOf(Memory.always(this@MainActivity)) }
            SetRow("Floating orb", "A small orb over every app. Tap it to talk, drag it anywhere, long-press to open this app.") {
                Switch(floating, { on ->
                    Memory.setFloating(this@MainActivity, on)
                    if (!on) { floating = false; FloatingService.restart(this@MainActivity) }
                    else if (!Settings.canDrawOverlays(this@MainActivity)) {
                        // Android asks for "Display over other apps" on its own screen; the orb starts on return.
                        openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } else { floating = true; scope.launch { permission(Manifest.permission.POST_NOTIFICATIONS); FloatingService.start(this@MainActivity) } }
                }, colors = hudSwitch())
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Listening", color = Hud.text, fontSize = 16.sp)
                Text(if (always) "Say \"Hey Meshron\" and your request, any time the orb is on. Uses more battery; a notification shows while it listens."
                    else "Tap the orb (or the core in this app) to talk.", color = Hud.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).border(1.dp, Hud.line, RoundedCornerShape(50)).padding(4.dp)) {
                    for ((on, label) in listOf(false to "Push to talk", true to "Always listening")) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (always == on) Hud.cyan else Color.Transparent)
                            .clickable {
                                if (always == on) return@clickable
                                scope.launch {
                                    if (on && !permission(Manifest.permission.RECORD_AUDIO)) return@launch
                                    always = on; Memory.setAlways(this@MainActivity, on); if (FloatingService.running) FloatingService.restart(this@MainActivity) else FloatingService.start(this@MainActivity)
                                }
                            }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(label, color = if (always == on) Color(0xFF02060C) else Hud.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            }
            SetRow("Make it the phone's assistant", "Long-press home or power opens Meshron Assistant, and it can see your screen. Pick it under Digital assistant app.") {
                LinkText("Open") { openSettings(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")) }
            }
            SetRow("Notification access", "Lets it read and reply to messages and emails in your notifications") {
                LinkText("Open") { openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            SetRow("Lock the phone", "\"Lock my phone\" locks it at once. Turn on as a device admin (lock only).") {
                LinkText(if (getSystemService(DevicePolicyManager::class.java).isAdminActive(admin)) "On" else "Turn on") {
                    startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Meshron Assistant only uses this to lock the screen when you ask."))
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Lockdown phrase", color = Hud.text, fontSize = 16.sp)
                Text("Say it (or type it) and the phone locks at once, even offline.", color = Hud.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                HudField(phrase, { phrase = it; Memory.setLockPhrase(this@MainActivity, it) }, "e.g. protocol zero")
            }
            SetRow("Sentry mode", "Speaks up about the charger, low battery, full charge and heat. Keeps a small notification.") {
                Switch(sentry, { on ->
                    sentry = on; Memory.setSentry(this@MainActivity, on)
                    scope.launch {
                        if (on) { permission(Manifest.permission.POST_NOTIFICATIONS); ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, SentryService::class.java)) }
                        else stopService(Intent(this@MainActivity, SentryService::class.java))
                    }
                }, colors = hudSwitch())
            }
            if (android.os.Build.VERSION.SDK_INT >= 31 && !getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()) {
                SetRow("On-time reminders", "Allow exact alarms so reminders ring on the minute") {
                    LinkText("Allow") { openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
                }
            }
            Section("What it can do")
            Text("Open apps, alarms, timers, reminders, tasks, your calendar, notifications (read and reply), texts and calls (you approve), the flashlight, volume, battery, what's on your screen, what's in front of the camera, documents and photos, live web lookups, and locking the phone. Opening apps, the flashlight, alarms, timers, the camera, battery and lock work offline.",
                color = Hud.dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(24.dp))
            Box(Modifier.padding(horizontal = 20.dp)) { HudButton("SIGN OUT", danger = true, onClick = onSignOut) }
            Spacer(Modifier.height(32.dp))
        }
    }

    /** The voice: the most natural ones for your language first; tap one to hear it and use it. */
    @Composable
    private fun VoicePicker() {
        var open by remember { mutableStateOf(false) }
        var list by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
        var chosen by remember { mutableStateOf(Memory.voice(this)) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { list = Speaker.voices(this@MainActivity); if (chosen.isEmpty()) chosen = list.firstOrNull()?.name.orEmpty() }
        SetRow("Voice", list.firstOrNull { it.name == chosen }?.let { Speaker.label(it) } ?: "The phone's default voice") {
            LinkText(if (open) "Close" else "Change") { open = !open }
        }
        if (open) Column(Modifier.padding(horizontal = 12.dp)) {
            if (list.isEmpty()) Text("No voices found. Install \"Speech Services by Google\" from Play for natural voices.", color = Hud.dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            list.take(16).forEach { v ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    chosen = v.name
                    scope.launch { Speaker.useVoice(this@MainActivity, v); Speaker.stop(); Speaker.say(this@MainActivity, "Hello. This is how I'll sound from now on.") }
                }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (v.name == chosen) Hud.cyan else Hud.line))
                    Text(Speaker.label(v), color = if (v.name == chosen) Hud.text else Hud.dim, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }

    private fun openSettings(vararg intents: Intent) {
        for (i in intents) if (runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    // ---- Small pieces ----
    @Composable private fun Section(t: String) = Text(t.uppercase(), color = Hud.cyan.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, fontSize = 11.sp, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))

    @Composable
    private fun SetRow(title: String, sub: String, end: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = Hud.text, fontSize = 16.sp)
                Text(sub, color = Hud.dim, fontSize = 13.sp, lineHeight = 18.sp)
            }
            end()
        }
    }

    @Composable private fun LinkText(t: String, onClick: () -> Unit) = Text(t, color = Hud.cyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, Hud.line, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))

    @Composable
    private fun IconTap(icon: ImageVector, label: String, onClick: () -> Unit) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Hud.dim) }
    }

    @Composable
    private fun HudField(value: String, onChange: (String) -> Unit, hint: String, secret: Boolean = false) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Hud.line, RoundedCornerShape(16.dp)).background(Hud.panel).padding(horizontal = 16.dp, vertical = 15.dp)) {
            if (value.isEmpty()) Text(hint, color = Hud.dim, fontSize = 16.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = Hud.text, fontSize = 16.sp), cursorBrush = SolidColor(Hud.cyan),
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable
    private fun HudButton(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(50))
            .background(if (danger) Brush.linearGradient(listOf(Hud.panel, Hud.panel)) else Brush.linearGradient(listOf(Hud.blue, Hud.cyan)), alpha = if (enabled) 1f else 0.4f)
            .border(1.dp, if (danger) Hud.red.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            Text(text, color = if (danger) Hud.red else Color(0xFF02060C), fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable private fun hudSwitch() = SwitchDefaults.colors(checkedTrackColor = Hud.cyan, checkedThumbColor = Color(0xFF02060C), uncheckedTrackColor = Hud.panel, uncheckedThumbColor = Hud.dim, uncheckedBorderColor = Hud.line)
}
