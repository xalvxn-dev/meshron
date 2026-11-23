// Meshron Assistant for Windows: the showcase. For a few commands (recommended voices, storage,
// system load, battery) the orb flies to the middle of the screen and shows it around itself,
// then flies back to its edge. It plays in its own see-through window above everything.

const { BrowserWindow, screen } = require('electron')
const { execFile } = require('child_process')
const os = require('os')
const path = require('path')

const run = (file, args, timeout = 8000) => new Promise((resolve) => {
  execFile(file, args, { windowsHide: true, timeout }, (err, out) => resolve(err ? '' : String(out)))
})
const ps = (script) => run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script])

// ---- What it shows ----

async function storage () {
  const out = await ps("Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3 or DriveType=2' | ForEach-Object { $_.DeviceID + '|' + $_.Size + '|' + $_.FreeSpace + '|' + $_.VolumeName }")
  return out.split(/\r?\n/).map((l) => l.trim().split('|')).filter((p) => p.length >= 3 && +p[1] > 0)
    .map(([id, size, free, name]) => ({ id, name: name || '', size: +size, free: +free }))
}

let lastCpu = os.cpus()
function cpu () {
  const now = os.cpus()
  let idle = 0; let total = 0
  now.forEach((c, i) => {
    const p = lastCpu[i] || c
    const t = (k) => c.times[k] - p.times[k]
    idle += t('idle'); total += t('user') + t('nice') + t('sys') + t('idle') + t('irq')
  })
  lastCpu = now
  return total > 0 ? Math.round(100 * (1 - idle / total)) : 0
}

let hasNvidia = null
async function gpu () {
  if (hasNvidia !== false) {
    const out = await run('nvidia-smi', ['--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu', '--format=csv,noheader,nounits'], 4000)
    const p = out.split('\n')[0].split(',').map((x) => x.trim())
    if (p.length >= 5 && p[1] !== '') { hasNvidia = true; return { name: p[0], load: +p[1], memUsed: +p[2] * 1048576, memTotal: +p[3] * 1048576, temp: +p[4] } }
    hasNvidia = false
  }
  // Any graphics card: Windows' own counter (3D engine use).
  const out = await ps("(Get-Counter '\\GPU Engine(*engtype_3D)\\Utilization Percentage' -ErrorAction SilentlyContinue).CounterSamples | Measure-Object CookedValue -Sum | ForEach-Object { [math]::Round($_.Sum) }")
  return { name: 'Graphics', load: Math.min(100, +out.trim() || 0) }
}

async function system () {
  const [g] = await Promise.all([gpu()])
  return { cpu: cpu(), cpuName: (os.cpus()[0] || {}).model || 'Processor', gpu: g, ramUsed: os.totalmem() - os.freemem(), ramTotal: os.totalmem() }
}

async function battery () {
  const out = await ps('$b = Get-CimInstance Win32_Battery; if ($b) { "$($b.EstimatedChargeRemaining)|$($b.BatteryStatus)|$($b.EstimatedRunTime)" }')
  const [pct, st, mins] = out.trim().split('|')
  if (!pct) return { none: true }
  return { pct: +pct, charging: ['2', '6', '7', '8', '9'].includes(st), mins: +mins > 0 && +mins < 71582788 ? +mins : null }
}

/** The voices it recommends: lifelike online ones and the ones that run on this PC. */
const VOICES = [
  { id: 'george', name: 'George', tag: 'Warm · British' },
  { id: 'sarah', name: 'Sarah', tag: 'Soft · American' },
  { id: 'daniel', name: 'Daniel', tag: 'Newsreader' },
  { id: 'lily', name: 'Lily', tag: 'British' },
  { id: 'kokoro:af_heart', name: 'Kylie', tag: 'On this PC' },
  { id: 'kokoro:am_michael', name: 'Alvin', tag: 'On this PC' },
  { id: 'kokoro:am_fenrir', name: 'Clement', tag: 'Deep · on this PC' },
  { id: 'kokoro:bm_george', name: 'Kadmiel', tag: 'British · on this PC' }
]

// ---- The stage ----

let stage = null
let ticker = null
const KINDS = ['voices', 'storage', 'system', 'battery', 'tour']

/**
 * Shows one kind. from: the orb's centre on screen (it flies in from there). hooks: { hideOrb,
 * showOrb, current } — current() is the chosen voice.
 */
async function show (kind, from, hooks) {
  if (!KINDS.includes(kind)) return false
  close(true)
  const d = screen.getDisplayNearestPoint(from || screen.getCursorScreenPoint())
  const wa = d.workArea
  const size = Math.min(760, wa.width - 40, wa.height - 40)
  const x = Math.round(wa.x + (wa.width - size) / 2); const y = Math.round(wa.y + (wa.height - size) / 2)
  const data = kind === 'storage' ? await storage() : kind === 'system' ? await system() : kind === 'battery' ? await battery() : kind === 'tour' ? { name: hooks.name ? hooks.name() : 'Meshron' } : { voices: VOICES, current: hooks.current() }
  stage = new BrowserWindow({
    x, y, width: size, height: size, frame: false, transparent: true, resizable: false, movable: false,
    alwaysOnTop: true, skipTaskbar: true, hasShadow: false, show: false, backgroundColor: '#00000000',
    webPreferences: { preload: path.join(__dirname, 'asst-preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false }
  })
  stage.setAlwaysOnTop(true, 'screen-saver')
  stage.setContentProtection(hooks.hidden ? hooks.hidden() : false)
  const fromLocal = from ? { x: from.x - x - size / 2, y: from.y - y - size / 2 } : { x: 0, y: size }
  stage.loadFile(path.join(__dirname, 'asst-bundle', 'stage.html'))
  stage.webContents.once('did-finish-load', () => {
    if (!stage) return
    stage.webContents.send('a-event', 'show', { kind, data, from: fromLocal })
    stage.showInactive()
    hooks.hideOrb()
  })
  stage.on('closed', () => { stage = null; clearInterval(ticker); ticker = null; hooks.showOrb() })
  // System load: live, every second.
  if (kind === 'system') ticker = setInterval(async () => { if (stage) stage.webContents.send('a-event', 'update', await system()) }, 1000)
  return data
}

/** Ends the show (the page plays the fly-back first unless [now]). */
function close (now) {
  clearInterval(ticker); ticker = null
  if (!stage || stage.isDestroyed()) { stage = null; return }
  if (now) { stage.destroy(); stage = null; return }
  stage.webContents.send('a-event', 'leave')
  const s = stage
  setTimeout(() => { if (!s.isDestroyed()) s.destroy() }, 650)
}

/** What the assistant says while showing it. */
function describe (kind, data) {
  const gb = (b) => Math.round(b / 1e9)
  if (kind === 'storage') return data.length ? data.map((d) => d.id + ' has ' + gb(d.free) + ' GB free of ' + gb(d.size) + ' GB').join('. ') + '.' : 'I couldn\'t read your drives.'
  if (kind === 'system') return 'Processor at ' + data.cpu + '%, graphics at ' + data.gpu.load + '%, and ' + Math.round(100 * data.ramUsed / data.ramTotal) + '% of memory in use.'
  if (kind === 'battery') return data.none ? 'This PC runs on mains power, no battery.' : 'Battery at ' + data.pct + '%, ' + (data.charging ? 'charging.' : 'on battery.')
  if (kind === 'tour') return 'Here is what I can do. Watch the tour, or just ask me.'
  return 'Here are the voices I\'d recommend. Tap one to hear it.'
}

// ---- A small card beside the orb for an action (volume, timer, alarm, search…) ----
let card = null
const plainWin = (o) => new BrowserWindow(Object.assign({
  frame: false, transparent: true, resizable: false, movable: false, focusable: false,
  alwaysOnTop: true, skipTaskbar: true, hasShadow: false, show: false, backgroundColor: '#00000000',
  webPreferences: { preload: path.join(__dirname, 'asst-preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false }
}, o))

/** pic: volume, timer, alarm, reminder, media, lock, app, files or search. orb: { x, y, width, height, right }. */
function toast (pic, data, orb, hidden) {
  if (card && !card.isDestroyed()) card.destroy()
  const size = pic === 'search' ? 380 : 260
  const d = screen.getDisplayNearestPoint(orb ? { x: orb.x, y: orb.y } : screen.getCursorScreenPoint())
  const wa = d.workArea
  let x, y
  if (orb) { x = orb.right ? orb.x - size + 20 : orb.x + orb.width - 20; y = orb.y + orb.height / 2 - size / 2 } else { x = wa.x + wa.width - size - 40; y = wa.y + wa.height - size - 40 }
  x = Math.max(wa.x, Math.min(wa.x + wa.width - size, Math.round(x))); y = Math.max(wa.y, Math.min(wa.y + wa.height - size, Math.round(y)))
  const w = card = plainWin({ x, y, width: size, height: size })
  w.setAlwaysOnTop(true, 'screen-saver'); w.setIgnoreMouseEvents(true); w.setContentProtection(!!hidden)
  w.loadFile(path.join(__dirname, 'asst-bundle', 'stage.html'))
  w.webContents.once('did-finish-load', () => {
    if (w.isDestroyed()) return
    w.webContents.send('a-event', 'show', { kind: 'toast', data: Object.assign({ pic }, data), from: { x: orb && orb.right ? size / 2 : -size / 2, y: 0 } })
    w.showInactive()
  })
  w.on('closed', () => { if (card === w) card = null })
  setTimeout(() => { if (!w.isDestroyed()) w.destroy() }, pic === 'search' ? 2600 + String(data.query || '').length * 95 : 3400)
}

// ---- Pointing at something on screen (teaching) ----
let pointer = null
/** Circles a spot with an arrow and a note. rect: { x, y, width, height } in physical screen pixels. */
function point (rect, note, hidden) {
  if (pointer && !pointer.isDestroyed()) pointer.destroy()
  const phys = { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 }
  const dip = screen.screenToDipPoint ? screen.screenToDipPoint(phys) : phys
  const d = screen.getDisplayNearestPoint(dip)
  const b = d.bounds; const k = d.scaleFactor || 1
  const w = pointer = plainWin({ x: b.x, y: b.y, width: b.width, height: b.height })
  w.setAlwaysOnTop(true, 'screen-saver'); w.setIgnoreMouseEvents(true); w.setContentProtection(!!hidden)
  w.loadFile(path.join(__dirname, 'asst-bundle', 'pointer.html'))
  w.webContents.once('did-finish-load', () => {
    if (w.isDestroyed()) return
    const cw = rect.width / k; const ch = rect.height / k
    w.webContents.send('a-event', 'point', { x: dip.x - b.x - cw / 2, y: dip.y - b.y - ch / 2, width: cw, height: ch, note: note || '' })
    w.showInactive()
  })
  w.on('closed', () => { if (pointer === w) pointer = null })
  setTimeout(() => { if (!w.isDestroyed()) w.destroy() }, 8000)
}

module.exports = { show, close, describe, toast, point, storage, system, battery, KINDS }

