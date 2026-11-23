// Meshron Assistant for Windows: a floating orb over every app, a Windows 11 panel, a tray icon
// and a global shortcut. It listens (push to talk, or always for "Hey Meshron"), thinks with
// Meshron AI's models through the Meshron server, speaks back (the studio voice or Windows'),
// and acts on the PC with the tools in asst-tools.js.

const { app, BrowserWindow, ipcMain, safeStorage, shell, Tray, Menu, nativeImage, globalShortcut, screen, Notification, dialog, nativeTheme } = require('electron')
const path = require('path')
const fs = require('fs')
const { spawn } = require('child_process')
const tools = require('./asst-tools')
const showcase = require('./asst-show')

if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0) }
app.setAppUserModelId('com.xalvxnlab.meshron.assistant.desktop')

const dir = path.join(__dirname, 'asst-bundle')
const BASE = (process.env.MESHRON_ACCOUNTS_URL || 'https://meshron-accounts.meshron.workers.dev').replace(/\/$/, '')
const icon = path.join(dir, 'icons', 'icon.png')
const startHidden = process.argv.includes('--hidden')

// ---- Storage: settings, tasks, reminders; the sign-in key encrypted with Windows (safeStorage) ----
const dataFile = () => path.join(app.getPath('userData'), 'assistant.json')
let data = { settings: {}, tasks: [], reminders: [], orb: null }
function load () { try { data = Object.assign(data, JSON.parse(fs.readFileSync(dataFile(), 'utf8'))) } catch {} }
function save () { fs.mkdirSync(path.dirname(dataFile()), { recursive: true }); fs.writeFileSync(dataFile(), JSON.stringify(data)) }
const DEFAULTS = { hidden: false, name: 'Meshron', studio: true, studioVoice: 'george', speak: true, always: false, hotkey: 'Ctrl+Alt+Space', orb: true, startup: true }
const settings = () => Object.assign({}, DEFAULTS, data.settings)
function account () {
  try { return data.account ? JSON.parse(safeStorage.decryptString(Buffer.from(data.account, 'base64'))) : null } catch { return null }
}
function setAccount (a) { data.account = a ? safeStorage.encryptString(JSON.stringify(a)).toString('base64') : null; save() }

async function post (p, body) {
  const a = account()
  let r
  try { r = await fetch(BASE + p, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(Object.assign({}, body, a ? { username: a.u, authKey: a.a } : {})) }) } catch { throw new Error('No connection. Check the internet and try again.') }
  const o = await r.json().catch(() => ({}))
  if (!r.ok) throw new Error(r.status === 401 ? 'Wrong username or password' : (o.error || 'Something went wrong (' + r.status + ')'))
  return o
}

// ---- Windows ----
let panel = null; let orb = null; let tray = null
const ORB_W = 380; const ORB_H = 150; const ORB_SIZE = 64

function openPanel (page) {
  if (panel && !panel.isDestroyed()) { if (panel.isMinimized()) panel.restore(); panel.show(); panel.focus(); if (page) panel.webContents.send('a-event', 'page', page); return }
  panel = new BrowserWindow({
    width: 460, height: 720, minWidth: 380, minHeight: 520, title: 'Meshron Assistant', icon, show: false,
    backgroundColor: '#00000000', backgroundMaterial: 'mica', // Windows 11 Mica
    titleBarStyle: 'hidden', titleBarOverlay: { color: '#00000000', symbolColor: '#E6EDF5', height: 40 },
    webPreferences: { preload: path.join(__dirname, 'asst-preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false }
  })
  panel.setContentProtection(!!settings().hidden) // "Hide from screenshots" (Settings)
  panel.loadFile(path.join(dir, 'index.html'), page ? { hash: page } : undefined)
  panel.once('ready-to-show', () => panel.show())
  panel.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: 'deny' } })
  panel.webContents.on('will-navigate', (e) => e.preventDefault())
  panel.on('closed', () => { panel = null })
}

function orbBounds () {
  const s = data.orb
  const d = s ? screen.getDisplayNearestPoint({ x: s.x, y: s.y }) : screen.getPrimaryDisplay()
  const wa = d.workArea
  const right = s ? s.right : true
  const y = Math.max(wa.y + 12, Math.min(wa.y + wa.height - ORB_H - 12, s ? s.y : wa.y + wa.height * 0.55))
  return { x: right ? wa.x + wa.width - ORB_W - 6 : wa.x + 6, y, width: ORB_W, height: ORB_H, right }
}
function openOrb () {
  if (orb && !orb.isDestroyed()) return
  const b = orbBounds()
  orb = new BrowserWindow({
    x: b.x, y: b.y, width: ORB_W, height: ORB_H, frame: false, transparent: true, resizable: false, movable: false,
    alwaysOnTop: true, skipTaskbar: true, focusable: false, hasShadow: false, show: false, backgroundColor: '#00000000',
    webPreferences: { preload: path.join(__dirname, 'asst-preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, backgroundThrottling: false }
  })
  orb.setAlwaysOnTop(true, 'screen-saver')
  orb.setContentProtection(!!settings().hidden)
  orb.setIgnoreMouseEvents(true, { forward: true }) // only the orb and its strip take clicks (orb.js switches this)
  orb.loadFile(path.join(dir, 'orb.html'), { hash: b.right ? 'right' : 'left' })
  orb.once('ready-to-show', () => { if (settings().orb) orb.showInactive() })
  orb.on('closed', () => { orb = null })
}

// ---- State shared with the windows ----
const state = { mode: 'idle', strip: null, tag: '', asking: null, lines: [], signedIn: false }
function broadcast () {
  for (const w of [panel, orb]) if (w && !w.isDestroyed()) w.webContents.send('a-event', 'state', state)
}
function setMode (m) { state.mode = m; broadcast() }
let hideTimer = null
function strip (tag, text, autoHide) {
  state.tag = tag; state.strip = text; broadcast()
  clearTimeout(hideTimer)
  if (autoHide) hideTimer = setTimeout(() => { if (state.mode === 'idle' && !state.asking) { state.strip = null; broadcast() } }, 7000)
}
const line = (l) => { l.at = Date.now(); state.lines.push(l); if (state.lines.length > 200) state.lines.shift(); broadcast() }

// ---- The conversation ----
let messages = []
let busy = false
let approval = null

function approve (title, detail) {
  return new Promise((resolve) => {
    approval = resolve
    state.asking = { title, detail }; broadcast()
    if (settings().speak) say(title)
    if (!panel || !panel.isVisible()) { if (orb) orb.showInactive() }
  }).then((v) => { approval = null; state.asking = null; broadcast(); return v })
}

function context () {
  return { now: new Date().toLocaleString('sv-SE').slice(0, 16).replace(' ', 'T') + ', ' + new Date().toLocaleDateString('en', { weekday: 'long' }), timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, name: (account() || {}).u, language: app.getLocale(), platform: 'windows', assistantName: settings().name }
}

const toolCtx = {
  approve,
  schedule (text, at) {
    data.reminders.push({ text, at }); save()
    arm({ text, at })
  },
  tasks: () => data.tasks,
  saveTasks (t) { data.tasks = t; save() },
  /** Its own screen reading never sees itself: its windows are hidden from the capture just for that moment. */
  async unseen (fn) {
    const ws = [panel, orb].filter((w) => w && !w.isDestroyed())
    for (const w of ws) w.setContentProtection(true)
    try { await new Promise((r) => setTimeout(r, 120)); return await fn() } finally { for (const w of ws) w.setContentProtection(!!settings().hidden) }
  },
  showcase: (kind) => stageShow(kind),
  point: (rect, note) => showcase.point(rect, note, !!settings().hidden),
  async pickFile () {
    const r = await dialog.showOpenDialog(panel || undefined, { properties: ['openFile'], filters: [{ name: 'Documents and pictures', extensions: ['txt', 'md', 'csv', 'json', 'log', 'png', 'jpg', 'jpeg', 'webp'] }] })
    return r.canceled ? null : r.filePaths[0]
  }
}
function arm (r) {
  const ms = r.at - Date.now()
  if (ms < 0 || ms > 2 ** 31 - 1) return
  setTimeout(() => {
    data.reminders = data.reminders.filter((x) => x !== r && !(x.at === r.at && x.text === r.text)); save()
    new Notification({ title: 'Meshron Assistant', body: r.text, icon }).show()
    say(r.text)
  }, ms)
}

/** Plays a showcase (voices, storage, system, battery) and says what it shows. */
async function stageShow (kind) {
  let from = null
  if (orb && !orb.isDestroyed() && orb.isVisible()) {
    const b = orb.getBounds(); const right = (data.orb || {}).right !== false
    from = { x: b.x + (right ? b.width - 44 : 44), y: b.y + b.height / 2 }
  }
  const shown = await showcase.show(kind, from, {
    hideOrb: () => { if (orb && !orb.isDestroyed()) orb.hide() },
    showOrb: () => { if (orb && !orb.isDestroyed() && settings().orb) orb.showInactive() },
    current: () => settings().studioVoice,
    name: () => settings().name || 'Meshron',
    hidden: () => !!settings().hidden
  })
  return shown ? showcase.describe(kind, shown) : 'I can\'t show that.'
}

/** A small picture beside the orb for what a tool just did (volume, timer, alarm, app…). */
function illustrate (name, x, out) {
  if (!out || /^(Failed|The person didn't|I can't|Couldn't|No |Unknown|That )/.test(out.text || '')) return
  const pic = {
    set_volume: ['volume', { level: +x.level || 0 }],
    set_timer: ['timer', { seconds: +x.seconds || 0, text: x.label || '' }],
    add_reminder: [/alarm|wake/i.test(String(x.text || '')) ? 'alarm' : 'reminder', { text: String(x.text || ''), when: out.text.replace(/^Reminder set for /, '') }],
    media: ['media', { action: String(x.action || '') }],
    lock_pc: ['lock', {}],
    open_app: ['app', { name: String(x.name || '') }],
    switch_to: ['app', { name: String(x.name || '') }],
    move_files: ['files', { text: out.text }],
    make_folder: ['files', { text: out.text }],
    delete_files: ['files', { text: out.text }]
  }[name]
  if (pic) toastNow(pic[0], pic[1])
}
function toastNow (pic, d) {
  try {
    const visible = orb && !orb.isDestroyed() && orb.isVisible()
    showcase.toast(pic, d, visible ? Object.assign(orb.getBounds(), { right: (data.orb || {}).right !== false }) : null, !!settings().hidden)
  } catch {}
}

/** Commands that work at once, even offline. */
async function local (t) {
  const s = t.toLowerCase().trim().replace(/[.!?]+$/, '')
  let m
  // Showcases: the orb takes centre stage for these.
  if (/what (?:can|could|else can) (?:you|u) do|^what can (?:you|u)|(?:your|ur) (?:features|abilities|skills)|^(?:help|show me what you can do|give me a tour)$/.test(s)) return stageShow('tour')
  if (/\b(recommend|suggest|show|change|pick|choose)\b.*\bvoices?\b|^voices$/.test(s)) return stageShow('voices')
  if (/\b(storage|disk space|drive space|how much space|free space|my drives?)\b/.test(s)) return stageShow('storage')
  if (/\b(cpu|gpu|processor|graphics card|ram|memory usage|system usage|performance|how'?s my (pc|computer|laptop))\b/.test(s)) return stageShow('system')
  if (/^(?:show |what'?s |how'?s )?(?:my |the )?battery(?: level| status)?$/.test(s)) return stageShow('battery')
  const run = async (n, x) => { const o = await tools.run(n, x, toolCtx); illustrate(n, x, o); return o.text }
  if ((m = /^(?:please )?(?:open|launch|start)(?: the)? (.+?)(?: app)?$/.exec(s))) return run('open_app', { name: m[1] })
  if (/^(?:show (?:the )?desktop|go home|minimi[sz]e (?:everything|all))$/.test(s)) return (await tools.run('show_desktop', {}, toolCtx)).text
  if (/^lock(?: the| my)? (?:pc|computer|screen)$/.test(s)) return run('lock_pc', {})
  if ((m = /^(?:set )?(?:the )?volume (?:to )?(\d{1,3})(?: ?%| percent)?$/.exec(s))) return run('set_volume', { level: +m[1] })
  if (/^(?:mute|unmute)$/.test(s)) return run('media', { action: 'mute' })
  if (/^(?:pause|play|resume)(?: (?:the )?(?:music|video|song))?$/.test(s)) return run('media', { action: 'play_pause' })
  if (/^(?:next|skip)(?: (?:song|track))?$/.test(s)) return run('media', { action: 'next' })
  if ((m = /^(?:set |start )?(?:a )?timer (?:for )?(\d+) ?(seconds?|secs?|minutes?|mins?|hours?)$/.exec(s))) {
    const n = +m[1]; const sec = m[2].startsWith('h') ? n * 3600 : m[2].startsWith('m') ? n * 60 : n
    return run('set_timer', { seconds: sec })
  }
  return null
}

async function ask (text, image) {
  text = String(text || '').trim()
  if (!text && !image) return
  if (busy) return
  busy = true
  line({ who: 'you', text: image ? '📷 ' + text : text })
  setMode('thinking'); strip('YOU', text, false)
  try {
    const l = await local(text)
    if (l) { reply(l); return }
    if (!account()) { reply('Sign in first: open Meshron Assistant from the tray.'); return }
    messages.push({ role: 'user', content: text || 'What is this?' })
    let img = image || null
    for (let round = 0; round < 16; round++) {
      const r = await post('/v1/assistant', { messages, context: context(), image: img || undefined })
      img = null
      // It searched the web: a keyboard types the query beside the orb.
      if (Array.isArray(r.searched) && r.searched[0]) toastNow('search', { query: String(r.searched[0]).slice(0, 60) })
      if (r.text != null) { messages.push({ role: 'assistant', content: r.text }); reply(r.text); break }
      messages.push(r.message)
      for (const d of r.done || []) { messages.push(d); line({ who: 'act', text: 'web search', ok: true }) }

      let photo = null
      for (const c of r.calls || []) {
        let out
        try { out = await tools.run(c.name, c.args || {}, toolCtx) } catch (e) { out = { text: 'Failed: ' + e.message } }
        illustrate(c.name, c.args || {}, out)
        line({ who: 'act', text: c.name.replace(/_/g, ' ') + ' · ' + out.text.split('\n')[0].slice(0, 80), ok: !/^(Failed|The person didn't|I can't|Couldn't)/.test(out.text) })
        messages.push({ role: 'tool', tool_call_id: c.id, content: out.text })
        if (out.image) photo = out
      }
      if (photo) { messages.push({ role: 'user', content: photo.question || 'What is this?' }); img = photo.image }
    }
    if (messages.length > 40) messages = messages.slice(-24)
  } catch (e) {
    line({ who: 'oops', text: e.message }); strip('SYSTEM', e.message, true); setMode('idle')
  } finally { busy = false; if (state.mode === 'thinking') setMode('idle') }
}
function reply (text) {
  line({ who: 'me', text })
  strip('MESHRON', text, !settings().speak)
  setMode('idle')
  if (settings().speak) say(text)
}
function say (text) { if (orb && !orb.isDestroyed()) orb.webContents.send('a-event', 'say', text) }

/** Starts listening in the orb window (it owns the microphone and the speaker). */
function listen () {
  if (busy) return
  if (!orb || orb.isDestroyed()) openOrb()
  orb.showInactive()
  orb.webContents.send('a-event', 'listen')
}

// ---- "Hey Meshron": Windows' own offline speech engine listens for the wake phrase only ----
let wake = null
/** The phrases Windows listens for: the name alone, or with hey / ok / hi; for the default name also how it's often heard. */
function wakePhrases () {
  const n = String(settings().name || 'Meshron').toLowerCase().replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim() || 'meshron'
  const names = n === 'meshron' ? ['meshron', 'mesh ron', 'mesh run'] : [n]
  const out = []
  for (const x of names) for (const p of ['', 'hey ', 'ok ', 'okay ', 'hi ']) out.push("'" + (p + x).replace(/'/g, "''") + "'")
  return out.join(', ')
}

// Windows' speech engine can lose its microphone stream when the app's own recording (your
// request) starts and stops, and then hears nothing more. So the listener is restarted after
// every conversation, and whenever it goes quiet (no heartbeat for 25 s).
let lastTick = Date.now()
let wakeRestart = null
function refreshWake (why) {
  if (!settings().always) return
  clearTimeout(wakeRestart)
  wakeRestart = setTimeout(() => {
    if (busy || state.mode !== 'idle') return refreshWake(why) // wait until the conversation is over
    wakeLog('restarting the listener (' + why + ')')
    startWake()
  }, 600)
}
setInterval(() => { if (settings().always && wake && Date.now() - lastTick > 25000) { lastTick = Date.now(); refreshWake('it went quiet') } }, 10000)

/** What the wake listener heard, for checking (wake.log next to the settings, and the Settings page). */
let lastHeard = ''
function wakeLog (text) {
  lastHeard = new Date().toLocaleTimeString() + '  ' + text
  try { fs.appendFileSync(path.join(app.getPath('userData'), 'wake.log'), lastHeard + '\n') } catch {}
  if (panel && !panel.isDestroyed()) panel.webContents.send('a-event', 'heard', lastHeard)
}

function startWake () {
  stopWake()
  if (!settings().always) return
  const script = `
Add-Type -AssemblyName System.Speech
# Prefer an English recogniser (the wake phrases are English); else whichever Windows has.
$ri = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers() | Sort-Object { if ($_.Culture.Name -like 'en-*') { 0 } else { 1 } } | Select-Object -First 1
if (-not $ri) { [Console]::Out.WriteLine('ERR Windows has no speech recogniser installed (Settings, Time and language, Speech)'); exit }
try { $r = New-Object System.Speech.Recognition.SpeechRecognitionEngine($ri) } catch { [Console]::Out.WriteLine('ERR no speech recogniser'); exit }
try { $r.SetInputToDefaultAudioDevice() } catch { [Console]::Out.WriteLine('ERR no microphone'); exit }
$c = New-Object System.Speech.Recognition.Choices
$c.Add([string[]]@(${wakePhrases()}))
$r.LoadGrammar((New-Object System.Speech.Recognition.Grammar((New-Object System.Speech.Recognition.GrammarBuilder($c)))))
# Listen in a plain loop: PowerShell can't run event handlers on the engine's own thread
# (they were silently dropped), so each phrase is read here, one at a time.
[Console]::Out.WriteLine('READY'); [Console]::Out.Flush()
while ($true) {
  try { $res = $r.Recognize([TimeSpan]::FromSeconds(10)) } catch { Start-Sleep -Milliseconds 500; continue }
  # Report everything it hears with how sure it is; the app decides (and logs it for checking).
  if ($res) { [Console]::Out.WriteLine('HEARD ' + [math]::Round($res.Confidence, 2) + ' ' + $res.Text); [Console]::Out.Flush() }
  [Console]::Out.WriteLine('TICK'); [Console]::Out.Flush()
}`
  wake = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script], { windowsHide: true })
  wake.stdout.on('data', (b) => {
    for (const l of String(b).split(/\r?\n/)) {
      const m = /^HEARD ([\d.]+) (.*)$/.exec(l)
      if (m) {
        const conf = +m[1]
        const woke = conf >= 0.3 && !busy && state.mode === 'idle'
        wakeLog(m[2] + ' (' + Math.round(conf * 100) + '% sure) ' + (woke ? 'woke up' : conf < 0.3 ? 'too unsure' : 'busy, ignored'))
        if (woke) { if (orb && !orb.isDestroyed()) orb.webContents.send('a-event', 'chime'); setTimeout(listen, 250) }
      }
      if (l === 'READY' || l === 'TICK') lastTick = Date.now()
      if (l === 'READY') wakeLog('listening for "' + settings().name + '"')
      if (l.startsWith('ERR')) { wakeLog('error: ' + l.slice(4)); strip('SYSTEM', '"Hey ' + settings().name + '" isn\'t available: ' + l.slice(4) + '. Use the shortcut or the orb.', true) }
    }
  })
  const proc = wake
  proc.on('exit', () => { if (wake === proc) wake = null })
}
function stopWake () { if (wake) { try { wake.kill() } catch {} wake = null } }

// ---- Shortcut, tray, startup ----
function registerHotkey () {
  globalShortcut.unregisterAll()
  const k = settings().hotkey
  if (!k) return true
  try { return globalShortcut.register(k, () => (state.mode === 'listening' ? orb && orb.webContents.send('a-event', 'stop') : listen())) } catch { return false }
}
function applyStartup () { if (app.isPackaged) app.setLoginItemSettings({ openAtLogin: !!settings().startup, args: ['--hidden'] }) }
function makeTray () {
  tray = new Tray(nativeImage.createFromPath(icon).resize({ width: 16, height: 16 }))
  tray.setToolTip('Meshron Assistant')
  const menu = () => Menu.buildFromTemplate([
    { label: 'Talk  (' + (settings().hotkey || 'no shortcut') + ')', click: listen },
    { label: 'Open Meshron Assistant', click: () => openPanel() },
    { label: settings().orb ? 'Hide the orb' : 'Show the orb', click: () => { setSettings({ orb: !settings().orb }); tray.setContextMenu(menu()) } },
    { type: 'separator' },
    { label: 'Settings', click: () => openPanel('settings') },
    { label: 'Quit', click: () => { app.isQuitting = true; app.quit() } }
  ])
  tray.setContextMenu(menu())
  tray.on('click', () => openPanel())
}
function setSettings (patch) {
  data.settings = Object.assign({}, data.settings, patch); save()
  if ('hotkey' in patch) registerHotkey()
  if ('always' in patch || 'name' in patch) startWake()
  if ('startup' in patch) applyStartup()
  if ('hidden' in patch) for (const w of [panel, orb]) if (w && !w.isDestroyed()) w.setContentProtection(!!settings().hidden)
  if ('orb' in patch && orb) { if (settings().orb) orb.showInactive(); else orb.hide() }
  for (const w of [panel, orb]) if (w && !w.isDestroyed()) w.webContents.send('a-event', 'settings', settings())
  return settings()
}

// ---- Messages from the windows ----
ipcMain.handle('a', async (e, what, arg) => {
  switch (what) {
    case 'state': return Object.assign({}, state, { signedIn: !!account(), user: (account() || {}).u || '' })
    case 'settings': return settings()
    case 'lastHeard': return lastHeard
    case 'setSettings': return setSettings(arg || {})
    case 'signIn': {
      const u = String(arg.u || '').trim().replace(/^@/, '').toLowerCase(); const a = String(arg.a || '')
      setAccount({ u, a })
      try { await post('/v1/assistant', { messages: [{ role: 'user', content: 'Say hi in three words.' }], context: context() }) } catch (err) { setAccount(null); throw err }
      broadcast(); return true
    }
    case 'signOut': setAccount(null); messages = []; state.lines = []; broadcast(); return true
    case 'ask': ask(arg); return true
    case 'listen': listen(); return true
    case 'heard': { // the orb's recording, as words
      refreshWake('after listening')
      if (!arg) { setMode('idle'); state.strip = null; broadcast(); return true }
      ask(arg); return true
    }
    case 'mode': setMode(arg); if (arg === 'listening') strip('LISTENING', '…', false); return true
    case 'partial': strip('LISTENING', arg, false); return true
    case 'approve': if (approval) approval(!!arg); return true
    case 'pickVoice': { // a bubble in the voices showcase
      setSettings({ studio: true, studioVoice: String(arg.id || '') })
      say('Hi, I\'m ' + String(arg.name || 'your new voice') + '. This is how I\'ll sound from now on.')
      return true
    }
    case 'stageClose': showcase.close(true); return true
    case 'reset': messages = []; state.lines = []; broadcast(); return true
    case 'stt': return post('/v1/stt', { audio: arg.audio, mime: arg.mime, language: app.getLocale().slice(0, 2) })
    case 'tts': return post('/v1/tts', { text: arg, voice: settings().studioVoice })
    case 'openPanel': openPanel(arg); return true
    case 'testHotkey': return registerHotkey()
    // The orb: let clicks through its empty area; drag it and let it settle on a side.
    case 'orbMouse': if (orb) orb.setIgnoreMouseEvents(!arg, { forward: true }); return true
    case 'orbMove': {
      if (!orb) return true
      const [x, y] = orb.getPosition()
      orb.setPosition(Math.round(x + arg.dx), Math.round(y + arg.dy))
      return true
    }
    case 'orbDrop': {
      if (!orb) return true
      const [x, y] = orb.getPosition()
      const orbCenter = { x: x + (arg.right ? ORB_W - ORB_SIZE / 2 - 12 : ORB_SIZE / 2 + 12), y: y + ORB_H / 2 }
      const d = screen.getDisplayNearestPoint(orbCenter); const wa = d.workArea
      const right = orbCenter.x > wa.x + wa.width / 2
      data.orb = { x: orbCenter.x, y, right }; save()
      const b = orbBounds()
      orb.setBounds(b)
      orb.webContents.send('a-event', 'side', right ? 'right' : 'left')
      return true
    }
  }
})

app.whenReady().then(() => {
  load()
  nativeTheme.themeSource = 'dark'
  makeTray()
  openOrb()
  registerHotkey()
  startWake()
  applyStartup()
  for (const r of data.reminders) arm(r)
  if (!startHidden || !account()) openPanel()
})
app.on('second-instance', () => openPanel())
app.on('window-all-closed', (e) => e.preventDefault && e.preventDefault()) // keep running in the tray
app.on('will-quit', () => { globalShortcut.unregisterAll(); stopWake() })
