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
const game = require('./asst-game')
const pal = require('./asst-pal')

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
const FACES = ['buddy', 'cosmo', 'pixel', 'kitty', 'island', 'pulse'] // faces and island orbs sit at the top centre (island)
const PALS = ['strider', 'wisp', 'drift', 'bolt'] // companions: they live on the screen instead of the orb
const isPal = () => PALS.includes(settings().skin)
/** The orb shows unless it's turned off or a companion has taken its place (the orb window still owns the mic and speaker). */
const orbShown = () => settings().orb && !isPal()
/** Companion on or off, with the orb hidden while it's on. */
function applyCompanion () {
  if (isPal() && settings().orb) { pal.open(!!settings().hidden); if (orb && !orb.isDestroyed()) orb.hide() } else { pal.close(); if (orb && !orb.isDestroyed() && settings().orb) orb.showInactive() }
}
const DEFAULTS = { remote: false, remoteTarget: '', skin: 'reactor', theme: 'midnight', hidden: false, name: 'Meshron', studio: true, studioVoice: 'george', speak: true, always: false, hotkey: 'Ctrl+Alt+Space', orb: true, startup: true }
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

// ---- The person's other devices (remote control, same account) ----
let remoteCtx = null // set while running a command sent from another device
const remoteQueue = []
const rc = require('./asst-remote')({
  post, data, save, settings,
  signedIn: () => !!account(),
  changed: (list) => { for (const w of [panel]) if (w && !w.isDestroyed()) w.webContents.send('a-event', 'devices', list) },
  runCommand: (m) => {
    new Notification({ title: 'Meshron Assistant', body: 'From ' + (m.fromName || 'your other device') + ': ' + m.text, icon }).show()
    remoteQueue.push(m); runRemoteQueue()
  },
  showReply: (m, p) => {
    const who = (p && p.name) || m.fromName || 'Other device'
    line({ who: 'me', text: '[' + who + '] ' + m.text })
    strip(who.toUpperCase(), m.text, !settings().speak)
    if (settings().speak) say(m.text)
  },
  approveFor: (m) => approve((m.fromName || 'Other device') + ': ' + m.title, m.detail)
})
async function runRemoteQueue () {
  if (remoteCtx || !remoteQueue.length) return
  while (busy) await new Promise((r) => setTimeout(r, 300))
  const m = remoteQueue.shift(); remoteCtx = m
  try { await ask(m.text) } finally {
    if (remoteCtx && !remoteCtx.answered) rc.send(m.from, { kind: 'reply', id: m.id, text: 'Done.', done: true }).catch(() => {})
    remoteCtx = null; runRemoteQueue()
  }
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
  if (FACES.includes(settings().skin) && !(data.orb && data.orb.moved)) {
    const d = data.orb ? screen.getDisplayNearestPoint({ x: data.orb.x, y: data.orb.y }) : screen.getPrimaryDisplay()
    const wa = d.workArea
    return { x: Math.round(wa.x + (wa.width - ORB_W) / 2), y: wa.y + 2, width: ORB_W, height: ORB_H, right: false, island: true }
  }
  const s = data.orb
  const d = s ? screen.getDisplayNearestPoint({ x: s.x, y: s.y }) : screen.getPrimaryDisplay()
  const wa = d.workArea
  const right = s ? s.right : true
  // Top or bottom edge: anywhere along it (the strip opens toward the middle of the screen).
  if (s && (s.edge === 'top' || s.edge === 'bottom')) {
    const cx = Math.max(wa.x + 44, Math.min(wa.x + wa.width - 44, s.x))
    const x = Math.round(right ? cx - (ORB_W - 12 - ORB_SIZE / 2) : cx - (12 + ORB_SIZE / 2))
    const cy = s.edge === 'top' ? wa.y + 6 + ORB_SIZE / 2 : wa.y + wa.height - 6 - ORB_SIZE / 2
    return { x: Math.max(wa.x - (right ? 0 : 0), Math.min(wa.x + wa.width - ORB_W, x)), y: Math.round(cy - ORB_H / 2), width: ORB_W, height: ORB_H, right, edge: s.edge }
  }
  // Anywhere on the screen, where it was dropped.
  if (s && s.edge === 'free') {
    const x = Math.round(right ? s.x - (ORB_W - 12 - ORB_SIZE / 2) : s.x - (12 + ORB_SIZE / 2))
    return { x: Math.max(wa.x, Math.min(wa.x + wa.width - ORB_W, x)), y: Math.round(Math.max(wa.y, Math.min(wa.y + wa.height - ORB_H, s.y - ORB_H / 2))), width: ORB_W, height: ORB_H, right, edge: 'free' }
  }
  const y = Math.max(wa.y + 12, Math.min(wa.y + wa.height - ORB_H - 12, s ? s.y : wa.y + wa.height * 0.55))
  return { x: right ? wa.x + wa.width - ORB_W - 6 : wa.x + 6, y, width: ORB_W, height: ORB_H, right }
}
// ---- The orb takes the mouse only over itself (the rest of its window lets clicks through) ----
// Checked here from the cursor's position, so it keeps working when other see-through windows
// (companions, showcases, the board) sit on top of it; it comes to the front when the mouse is on it.
let orbRects = []; let orbHold = false; let orbTaking = null
function orbHitTick () {
  if (!orb || orb.isDestroyed() || !orb.isVisible()) return
  const p = screen.getCursorScreenPoint(); const [wx, wy] = orb.getPosition()
  const inside = orbRects.some((r) => p.x >= wx + r.x - 2 && p.x <= wx + r.x + r.w + 2 && p.y >= wy + r.y - 2 && p.y <= wy + r.y + r.h + 2)
  const take = orbHold || inside
  if (take === orbTaking) return
  orbTaking = take
  orb.setIgnoreMouseEvents(!take, { forward: true })
  if (take) { orb.setAlwaysOnTop(true, 'screen-saver', 2); orb.moveTop() }
}
setInterval(orbHitTick, 40)
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
  orb.loadFile(path.join(dir, 'orb.html'), { hash: b.island ? 'center' : b.right ? 'right' : 'left' })
  orb.once('ready-to-show', () => { if (orbShown()) orb.showInactive() })
  orb.on('closed', () => { orb = null; orbTaking = null })
}

// ---- State shared with the windows ----
const state = { mode: 'idle', strip: null, tag: '', asking: null, lines: [], signedIn: false }
function broadcast () {
  for (const w of [panel, orb, pal.window()]) if (w && !w.isDestroyed()) w.webContents.send('a-event', 'state', state)
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
  // Running a command from another device: that device approves (nobody may be at this PC).
  if (remoteCtx) return rc.askApproval(remoteCtx, title, detail)
  return new Promise((resolve) => {
    approval = resolve
    state.asking = { title, detail }; broadcast()
    if (settings().speak) say(title)
    if (!panel || !panel.isVisible()) { if (isPal()) openPanel(); else if (orb) orb.showInactive() }
  }).then((v) => { approval = null; state.asking = null; broadcast(); return v })
}

function context () {
  return { now: new Date().toLocaleString('sv-SE').slice(0, 16).replace(' ', 'T') + ', ' + new Date().toLocaleDateString('en', { weekday: 'long' }), timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, name: (account() || {}).u, language: app.getLocale(), platform: 'windows', assistantName: settings().name, pictures: true }
}

// ---- Scheduled tasks: "at 3 PM join this Zoom link in Edge", "lock my PC at 10" ----
// Kept with the settings; checked every 15 s. At its time the task runs as if the person said it.
const localIso = (d) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
const jobs = {
  add (at, task, repeat) {
    data.jobs = data.jobs || []
    const j = { id: Date.now().toString(36), at: localIso(at), task, repeat }
    data.jobs.push(j); save(); return j
  },
  list: () => (data.jobs || []).slice().sort((a, b) => new Date(a.at) - new Date(b.at)),
  cancel (which) {
    const before = (data.jobs || []).length
    const w = which.toLowerCase().trim()
    data.jobs = w === 'all' ? [] : (data.jobs || []).filter((j) => !j.task.toLowerCase().includes(w) && j.id !== w)
    save(); return before - data.jobs.length
  }
}
function nextRun (j) {
  const t = new Date(j.at)
  do { t.setDate(t.getDate() + 1) } while (j.repeat === 'weekdays' && (t.getDay() === 0 || t.getDay() === 6))
  return localIso(t)
}
function jobsTick () {
  const now = Date.now()
  for (const j of jobs.list()) {
    const at = new Date(j.at).getTime()
    if (at > now) break
    if (busy) return // runs on the next check
    if (j.repeat === 'once') data.jobs = data.jobs.filter((x) => x !== j); else j.at = nextRun(j)
    save()
    // The PC was off or asleep at the time: say so rather than doing it hours late.
    if (now - at > 30 * 60000) { line({ who: 'act', text: 'scheduled · missed (the PC was off): ' + j.task.slice(0, 70), ok: false }); continue }
    line({ who: 'act', text: 'scheduled · ' + j.task.slice(0, 80), ok: true })
    ask(j.task)
    return
  }
}
const toolCtx = {
  jobs,
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
  hidden: () => !!settings().hidden,
  board: (spec) => { showcase.board(spec, !!settings().hidden); return 'On the board' },
  game: () => { game.open(!!settings().hidden); return 'Opened game mode' },
  companion: (action) => companion(action),
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
    showOrb: () => { if (orb && !orb.isDestroyed() && orbShown()) orb.showInactive() },
    current: () => settings().studioVoice,
    name: () => settings().name || 'Meshron',
    hidden: () => !!settings().hidden,
    memory: () => tools.P(toolCtx).memory()
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

/** Tells the companion (Strider, Wisp, Drift or Bolt) to do something on the screen. */
function companion (action) {
  if (!isPal()) return 'Pick a companion first (Settings > Look: Strider, Wisp, Drift or Bolt).'
  const c = { action }
  if (action === 'come') { const p = screen.getCursorScreenPoint(); const wa = screen.getPrimaryDisplay().workArea; c.at = { x: p.x - wa.x, y: p.y - wa.y } }
  pal.send('pal', c)
  return { come: 'Coming!', sit: 'Sitting on your window.', sleep: 'Taking a nap.', wake: 'I\'m awake!', dance: 'Dancing!', fly: 'Up we go!', left: 'Heading left.', right: 'Heading right.', hide: 'Hiding. Say "show yourself" to bring me back.', show: 'Here I am!', jump: 'Jump!' }[action] || 'Okay.'
}

/** Commands that work at once, even offline. */
async function local (t) {
  const s = t.toLowerCase().trim().replace(/[.!?]+$/, '')
  let m
  if (/^(?:clear|wipe|close|erase)(?: the| your)? (?:board|screen drawing|drawings?)$|^clear the screen$/.test(s)) { showcase.boardClose(); return 'Board cleared.' }
  // The companion (when one is chosen) moves around on request.
  if (isPal()) {
    const act = /\b(come here|come to me|come over)\b/.test(s) ? 'come' : /\b(sit (down|on)|perch|get on (my|the) window)\b/.test(s) ? 'sit' : /\b(go to sleep|take a nap|nap time)\b/.test(s) ? 'sleep' : /\bwake up\b/.test(s) ? 'wake'
      : /\bdance\b/.test(s) ? 'dance' : /^(fly|fly around|take off)$/.test(s) ? 'fly' : /\b(walk|go|move) left\b/.test(s) ? 'left' : /\b(walk|go|move) right\b/.test(s) ? 'right' : /\bhide yourself\b|^hide$/.test(s) ? 'hide' : /\bshow yourself\b|come back/.test(s) ? 'show' : /^jump$/.test(s) ? 'jump' : null
    if (act) return companion(act)
  }
  // Showcases: the orb takes centre stage for these.
  if (/\b(game ?mode|play a game|open (the )?games?|hand tracking|face tracking|let'?s play)\b/.test(s)) { game.open(!!settings().hidden); return 'Game mode is open. Point at a game and pinch to start.' }
  if (/what (?:can|could|else can) (?:you|u) do|^what can (?:you|u)|(?:your|ur) (?:features|abilities|skills)|^(?:help|show me what you can do|give me a tour)$|show me everything|everything you can do|feature tour/.test(s)) return stageShow('tour')
  if (/\b(recommend|suggest|show|change|pick|choose)\b.*\bvoices?\b|^voices$/.test(s)) return stageShow('voices')
  if (/\b(ram|memory)\b.*\b(using|taking|hog|eating|used)\b|\b(using|taking|eating) (up )?(all )?(my )?(ram|memory)\b|^(?:ram|memory)( usage)?$/.test(s)) return stageShow('memory')
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
  if (/\b(thank(s| you)|love (you|u)|you'?re (the best|amazing|awesome)|good job|well done)\b/i.test(text)) mood('love', 2600)
  setMode('thinking'); strip('YOU', text, false)
  try {
    // For another of the person's devices ("on my phone, …" or the device picked in the panel)?
    const rt = remoteCtx ? null : rc.route(text)
    if (rt && rt.local) text = rt.local
    else if (rt && rt.missing) { reply('I can\'t see your ' + rt.missing + '. Sign in to Meshron Assistant there with the same account and turn on "Control from my other devices".'); return }
    else if (rt && rt.device) {
      line({ who: 'act', text: 'sent to ' + rt.device.name + ' · ' + rt.text.slice(0, 60), ok: true })
      try { await rc.command(rt.device, rt.text); strip(rt.device.name.toUpperCase(), 'Sent. Waiting for ' + rt.device.name + '…', true) } catch (e) { reply(e.message) }
      setMode('idle'); return
    }
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
      if (Array.isArray(r.images)) for (const p of r.images) showPicture(p)
      if (r.text != null) { messages.push({ role: 'assistant', content: r.text }); reply(r.text); break }
      messages.push(r.message)
      for (const d of r.done || []) { messages.push(d); line({ who: 'act', text: 'web search', ok: true }) }

      let photo = null
      for (const c of r.calls || []) {
        let out
        try { out = await tools.run(c.name, c.args || {}, toolCtx) } catch (e) { out = { text: 'Failed: ' + e.message } }
        illustrate(c.name, c.args || {}, out)
        mood(/^(Failed|The person didn't|I can't|Couldn't)/.test(out.text) ? 'sad' : 'happy', 1800)
        line({ who: 'act', text: c.name.replace(/_/g, ' ') + ' · ' + out.text.split('\n')[0].slice(0, 80), ok: !/^(Failed|The person didn't|I can't|Couldn't)/.test(out.text) })
        messages.push({ role: 'tool', tool_call_id: c.id, content: out.text })
        if (out.image) photo = out
      }
      if (photo) { messages.push({ role: 'user', content: photo.question || 'What is this?' }); img = photo.image }
    }
    if (messages.length > 40) messages = messages.slice(-24)
  } catch (e) {
    line({ who: 'oops', text: e.message }); strip('SYSTEM', e.message, true); setMode('idle')
    if (remoteCtx) { remoteCtx.answered = true; rc.send(remoteCtx.from, { kind: 'reply', id: remoteCtx.id, text: 'Problem: ' + e.message, done: true }).catch(() => {}) }
  } finally { busy = false; if (state.mode === 'thinking') setMode('idle') }
}
// A picture the AI made: saved to Pictures\Meshron, shown in the panel (or opened, if the panel is closed).
function showPicture (p) {
  try {
    const m = /^data:image\/(png|jpeg|webp);base64,([A-Za-z0-9+/=]+)$/.exec(p && p.image || '')
    if (!m) return
    const dir = path.join(app.getPath('pictures'), 'Meshron'); fs.mkdirSync(dir, { recursive: true })
    const file = path.join(dir, 'meshron-' + new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19) + '.' + (m[1] === 'jpeg' ? 'jpg' : m[1]))
    fs.writeFileSync(file, Buffer.from(m[2], 'base64'))
    line({ who: 'pic', text: String(p.caption || '').slice(0, 120), src: require('url').pathToFileURL(file).href, file })
    if (!panel || panel.isDestroyed() || !panel.isVisible()) shell.openPath(file)
  } catch (e) { line({ who: 'oops', text: 'Could not save the picture: ' + e.message }) }
}
function reply (text) {
  if (remoteCtx) { remoteCtx.answered = true; rc.send(remoteCtx.from, { kind: 'reply', id: remoteCtx.id, text, done: true }).catch(() => {}) }
  line({ who: 'me', text })
  strip('MESHRON', text, !settings().speak)
  setMode('idle')
  if (settings().speak) say(text)
}
/** A moment's expression on face skins: happy, sad, surprised or love. */
function mood (m, ms) { for (const w of [orb, pal.window()]) if (w && !w.isDestroyed()) w.webContents.send('a-event', 'mood', { mood: m, ms }) }
function say (text) { if (orb && !orb.isDestroyed()) orb.webContents.send('a-event', 'say', text) }

/** Starts listening in the orb window (it owns the microphone and the speaker). */
function listen () {
  if (busy) return
  if (!orb || orb.isDestroyed()) openOrb()
  if (!isPal()) orb.showInactive()
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
$g = New-Object System.Speech.Recognition.Grammar((New-Object System.Speech.Recognition.GrammarBuilder($c))); $g.Name = 'wake'; $r.LoadGrammar($g)
# Ordinary speech goes to a dictation grammar, so talking near the PC isn't forced into the wake phrase.
try { $d = New-Object System.Speech.Recognition.DictationGrammar; $d.Name = 'other'; $r.LoadGrammar($d) } catch { }
# Listen in a plain loop: PowerShell can't run event handlers on the engine's own thread
# (they were silently dropped), so each phrase is read here, one at a time.
[Console]::Out.WriteLine('READY'); [Console]::Out.Flush()
while ($true) {
  try { $res = $r.Recognize([TimeSpan]::FromSeconds(10)) } catch { Start-Sleep -Milliseconds 500; continue }
  # Report everything it hears with how sure it is; the app decides (and logs it for checking).
  if ($res) { [Console]::Out.WriteLine('HEARD ' + [math]::Round($res.Confidence, 2) + ' ' + $res.Grammar.Name + ' ' + $res.Text); [Console]::Out.Flush() }
  [Console]::Out.WriteLine('TICK'); [Console]::Out.Flush()
}`
  wake = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script], { windowsHide: true })
  wake.stdout.on('data', (b) => {
    for (const l of String(b).split(/\r?\n/)) {
      const m = /^HEARD ([\d.]+) (\S+) (.*)$/.exec(l)
      if (m) {
        const conf = +m[1]; const said = m[3]
        // Only the wake phrase wakes it, and only when it's sure: "hey / ok / hi" + name at 70%,
        // the bare name (easily confused with other words) at 88%.
        const need = /^(hey|ok|okay|hi) /.test(said) ? 0.7 : 0.88
        const isWake = m[2] === 'wake'
        const woke = isWake && conf >= need && !busy && state.mode === 'idle'
        if (isWake) wakeLog(said + ' (' + Math.round(conf * 100) + '% sure) ' + (woke ? 'woke up' : conf < need ? 'not sure enough, ignored' : 'busy, ignored'))
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
    { label: 'Game mode', click: () => game.open(!!settings().hidden) },
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
  if ('orb' in patch && orb) { if (orbShown()) orb.showInactive(); else orb.hide() }
  if ('orb' in patch || 'skin' in patch) applyCompanion()
  if ('remote' in patch) { rc.register(); rc.kick() }
  if ('hidden' in patch && pal.window()) pal.window().setContentProtection(!!settings().hidden)
  if ('skin' in patch && orb && !orb.isDestroyed()) { const b = orbBounds(); orb.setBounds({ x: b.x, y: b.y, width: b.width, height: b.height }); orb.webContents.send('a-event', 'side', b.island ? 'center' : b.right ? 'right' : 'left') }
  for (const w of [panel, orb, pal.window()]) if (w && !w.isDestroyed()) w.webContents.send('a-event', 'settings', settings())
  return settings()
}

// ---- Messages from the windows ----
ipcMain.handle('a', async (e, what, arg) => {
  // Game mode's own calls.
  if (what === 'openGame') { game.open(!!settings().hidden); return true }
  if (what === 'gameMood') { mood(String(arg || 'happy'), 3000); return true }
  if (String(what).startsWith('game')) { const r = await game.handle(what, arg, (ev, d) => { const w = game.window(); if (w && !w.isDestroyed()) w.webContents.send('a-event', ev, d) }); if (r !== undefined) return r }
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
    case 'stt': return post('/v1/stt', { audio: arg.audio, mime: arg.mime, language: app.getLocale().slice(0, 2), hint: settings().name })
    case 'tts': return post('/v1/tts', { text: arg, voice: settings().studioVoice })
    case 'openPanel': openPanel(arg); return true
    case 'testHotkey': return registerHotkey()
    // The orb: let clicks through its empty area; drag it and let it settle on a side.
    case 'orbMouse': orbHold = !!arg; orbHitTick(); return true
    case 'orbRects': orbRects = Array.isArray(arg) ? arg.slice(0, 4) : []; return true
    case 'palMouse': pal.mouse(!!arg); return true
    case 'devices': return rc.register()
    case 'deviceId': return rc.id()
    case 'boardMouse': showcase.boardMouse(!!arg); return true
    case 'boardClose': showcase.boardClose(); return true
    case 'orbMove': {
      if (!orb) return true
      const [x, y] = orb.getPosition()
      orb.setPosition(Math.round(x + arg.dx), Math.round(y + arg.dy))
      return true
    }
    case 'orbDrop': {
      if (!orb) return true
      const [x, y] = orb.getPosition()
      const orbCenter = arg.cx != null ? { x: arg.cx, y: arg.cy } : { x: x + (arg.right ? ORB_W - ORB_SIZE / 2 - 12 : ORB_SIZE / 2 + 12), y: y + ORB_H / 2 }
      const d = screen.getDisplayNearestPoint(orbCenter); const wa = d.workArea
      // It stays where it is dropped. Dropped close to an edge, it docks on that edge.
      const dist = { left: orbCenter.x - wa.x, right: wa.x + wa.width - orbCenter.x, top: orbCenter.y - wa.y, bottom: wa.y + wa.height - orbCenter.y }
      const near = Object.keys(dist).sort((a, b) => dist[a] - dist[b])[0]
      const edge = dist[near] < 70 ? near : 'free'
      // The strip opens toward the middle of the screen.
      const right = edge === 'right' || (edge !== 'left' && orbCenter.x > wa.x + wa.width / 2)
      data.orb = edge === 'left' || edge === 'right' ? { x: orbCenter.x, y: orbCenter.y - ORB_H / 2, right, edge, moved: true } : { x: orbCenter.x, y: orbCenter.y, right, edge, moved: true }; save()
      const b = orbBounds()
      orb.setBounds(b)
      orb.webContents.send('a-event', 'side', right ? 'right' : 'left')
      return true
    }
    case 'openPic': { // only pictures this app saved
      const dir = path.join(app.getPath('pictures'), 'Meshron')
      const f = path.resolve(String(arg || ''))
      if (f.startsWith(dir + path.sep) && fs.existsSync(f)) shell.openPath(f)
      return true
    }
    case 'orbHome': { // back to the default spot (island orbs: the top centre)
      if (data.orb) delete data.orb.moved
      save(); const b = orbBounds(); if (orb) { orb.setBounds({ x: b.x, y: b.y, width: b.width, height: b.height }); orb.webContents.send('a-event', 'side', b.island ? 'center' : b.right ? 'right' : 'left') }
      return true
    }
  }
})

app.whenReady().then(() => {
  setInterval(jobsTick, 15000)
  load()
  nativeTheme.themeSource = 'dark'
  makeTray()
  openOrb()
  applyCompanion()
  rc.start()
  registerHotkey()
  startWake()
  applyStartup()
  for (const r of data.reminders) arm(r)
  if (!startHidden || !account()) openPanel()
})
app.on('second-instance', () => openPanel())
app.on('window-all-closed', (e) => e.preventDefault && e.preventDefault()) // keep running in the tray
app.on('will-quit', () => { globalShortcut.unregisterAll(); stopWake() })
