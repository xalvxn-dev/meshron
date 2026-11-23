// Meshron Assistant for Windows: game mode. Hand and face tracking (MediaPipe, in the game window)
// for a game menu you pick from with your hand, games played with hands, fingers and face, a
// virtual keyboard, mood from your face, controlling the PC by gesture, and a "game controller"
// that turns punches and head turns into mouse and keys for games like Minecraft.
//
// The tracking models come from Google the first time game mode opens, after the person agrees,
// and are kept in the app's data folder. Input to other apps goes through one long-running
// PowerShell process (SendInput), so moves and clicks arrive without delay.

const { BrowserWindow, screen, app, globalShortcut } = require('electron')
const { spawn } = require('child_process')
const https = require('https')
const fs = require('fs')
const path = require('path')

const MODELS = {
  hand: { file: 'hand_landmarker.task', url: 'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task', size: 7.8e6 },
  face: { file: 'face_landmarker.task', url: 'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task', size: 3.7e6 }
}
const modelDir = () => path.join(app.getPath('userData'), 'models')
const modelPath = (k) => path.join(modelDir(), MODELS[k].file)
const haveModels = () => Object.keys(MODELS).every((k) => { try { return fs.statSync(modelPath(k)).size > 1e6 } catch { return false } })

/** Downloads both models (following redirects), reporting progress 0..1. */
async function downloadModels (progress) {
  fs.mkdirSync(modelDir(), { recursive: true })
  const keys = Object.keys(MODELS).filter((k) => { try { return fs.statSync(modelPath(k)).size < 1e6 } catch { return true } })
  const total = keys.reduce((n, k) => n + MODELS[k].size, 0)
  let done = 0
  for (const k of keys) {
    const tmp = modelPath(k) + '.part'
    await new Promise((resolve, reject) => {
      const get = (url, hops) => https.get(url, { headers: { 'user-agent': 'MeshronAssistant' } }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && hops < 5) { res.resume(); return get(new URL(res.headers.location, url).toString(), hops + 1) }
        if (res.statusCode !== 200) { res.resume(); return reject(new Error('Download failed (' + res.statusCode + ')')) }
        const f = fs.createWriteStream(tmp)
        res.on('data', (b) => { done += b.length; progress(Math.min(0.99, done / total)) })
        res.pipe(f)
        f.on('finish', () => f.close(() => { fs.renameSync(tmp, modelPath(k)); resolve() }))
        f.on('error', reject)
      }).on('error', reject)
      get(MODELS[k].url, 0)
    })
  }
  progress(1)
  return true
}

// ---- Input to other apps (SendInput through one PowerShell process) ----
const HELPER = `
$src = @'
using System; using System.Runtime.InteropServices;
public static class MI {
  [StructLayout(LayoutKind.Sequential)] struct MOUSEINPUT { public int dx, dy; public uint data, flags, time; public IntPtr extra; }
  [StructLayout(LayoutKind.Sequential)] struct KEYBDINPUT { public ushort vk, scan; public uint flags, time; public IntPtr extra; }
  [StructLayout(LayoutKind.Explicit)] struct U { [FieldOffset(0)] public MOUSEINPUT m; [FieldOffset(0)] public KEYBDINPUT k; }
  [StructLayout(LayoutKind.Sequential)] struct INPUT { public uint type; public U u; }
  [DllImport("user32.dll")] static extern uint SendInput(uint n, INPUT[] i, int size);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  static void Send(INPUT i) { SendInput(1, new[] { i }, Marshal.SizeOf(typeof(INPUT))); }
  public static void Move(int dx, int dy) { var i = new INPUT(); i.type = 0; i.u.m.dx = dx; i.u.m.dy = dy; i.u.m.flags = 1; Send(i); }
  public static void Btn(string b, bool down) { var i = new INPUT(); i.type = 0; i.u.m.flags = b == "r" ? (down ? 8u : 16u) : (down ? 2u : 4u); Send(i); }
  public static void Wheel(int d) { var i = new INPUT(); i.type = 0; i.u.m.flags = 0x800; i.u.m.data = (uint)d; Send(i); }
  public static void Key(ushort vk, bool down) { var i = new INPUT(); i.type = 1; i.u.k.vk = vk; i.u.k.flags = down ? 0u : 2u; Send(i); }
  public static void Text(string s) { foreach (char c in s) { var i = new INPUT(); i.type = 1; i.u.k.scan = c; i.u.k.flags = 4; Send(i); i.u.k.flags = 6; Send(i); } }
}
'@
Add-Type -TypeDefinition $src
[Console]::Out.WriteLine('READY')
while ($true) {
  $l = [Console]::In.ReadLine(); if ($l -eq $null) { break }
  $p = $l.Split(' ', 2)
  try {
    switch ($p[0]) {
      'm' { $a = $p[1].Split(' '); [MI]::Move([int]$a[0], [int]$a[1]) }
      'p' { $a = $p[1].Split(' '); [MI]::SetCursorPos([int]$a[0], [int]$a[1]) | Out-Null }
      'b' { $a = $p[1].Split(' '); [MI]::Btn($a[0], $a[1] -eq 'd') }
      'w' { [MI]::Wheel([int]$p[1]) }
      'k' { $a = $p[1].Split(' '); [MI]::Key([uint16]$a[0], $a[1] -eq 'd') }
      't' { [MI]::Text([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[1]))) }
    }
  } catch {}
}`
let helper = null
const held = { keys: new Set(), btns: new Set() }
function input (cmd) {
  if (!helper) {
    // The script runs from a file, so standard input carries only commands.
    const file = path.join(app.getPath('userData'), 'game-input.ps1')
    fs.writeFileSync(file, HELPER.replace(/\r?\n/g, '\r\n'))
    helper = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', file], { windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'] })
    helper.on('exit', () => { helper = null })
    helper.stdin.on('error', () => {})
  }
  const line = ({
    move: () => 'm ' + Math.round(cmd.dx) + ' ' + Math.round(cmd.dy),
    pos: () => 'p ' + Math.round(cmd.x) + ' ' + Math.round(cmd.y),
    button: () => { (cmd.down ? held.btns.add(cmd.b) : held.btns.delete(cmd.b)); return 'b ' + (cmd.b === 'r' ? 'r' : 'l') + ' ' + (cmd.down ? 'd' : 'u') },
    wheel: () => 'w ' + Math.round(cmd.delta),
    key: () => { const vk = Math.max(1, Math.min(254, cmd.vk | 0)); cmd.down ? held.keys.add(vk) : held.keys.delete(vk); return 'k ' + vk + ' ' + (cmd.down ? 'd' : 'u') },
    text: () => 't ' + Buffer.from(String(cmd.text || '').slice(0, 200), 'utf8').toString('base64')
  }[cmd.type] || (() => ''))()
  if (line && helper) helper.stdin.write(line + '\n')
  return true
}
/** Lets go of every key and button it is holding (when a control mode stops or the window closes). */
function releaseAll () {
  if (!helper) return
  for (const vk of held.keys) helper.stdin.write('k ' + vk + ' u\n')
  for (const b of held.btns) helper.stdin.write('b ' + b + ' u\n')
  held.keys.clear(); held.btns.clear()
}

// ---- The window ----
let win = null
let small = false
/** Opens game mode (or brings it forward). hidden: leave it out of screenshots. */
function open (hidden) {
  if (win && !win.isDestroyed()) { win.show(); win.focus(); return }
  const wa = screen.getPrimaryDisplay().workArea
  win = new BrowserWindow({
    width: Math.min(1280, wa.width - 80), height: Math.min(800, wa.height - 60), minWidth: 900, minHeight: 600, center: true,
    backgroundColor: '#05070D', title: 'Meshron Game Mode', autoHideMenuBar: true, show: false,
    webPreferences: { preload: path.join(__dirname, 'asst-preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, backgroundThrottling: false }
  })
  win.setContentProtection(!!hidden)
  win.loadFile(path.join(__dirname, 'asst-bundle', 'game.html'))
  win.once('ready-to-show', () => win.show())
  // Emergency stop for the control modes: Ctrl+Alt+G (also works while another app is in front).
  globalShortcut.register('Ctrl+Alt+G', () => { releaseAll(); if (win && !win.isDestroyed()) win.webContents.send('a-event', 'game-stop') })
  win.on('closed', () => { releaseAll(); globalShortcut.unregister('Ctrl+Alt+G'); win = null; small = false })
}
/**
 * Control modes run over other apps: the window shrinks to a small camera card in a corner that
 * stays on top (and never takes focus from the game), then grows back.
 */
function compact (on) {
  if (!win || win.isDestroyed()) return false
  small = !!on
  const wa = screen.getPrimaryDisplay().workArea
  if (on) {
    win.setAlwaysOnTop(true, 'screen-saver'); win.setFocusable(false)
    win.setBounds({ x: wa.x + wa.width - 340, y: wa.y + wa.height - 290, width: 320, height: 270 })
  } else {
    win.setAlwaysOnTop(false); win.setFocusable(true)
    win.setBounds({ x: wa.x + Math.round((wa.width - 1280) / 2), y: wa.y + Math.round((wa.height - 800) / 2), width: Math.min(1280, wa.width - 80), height: Math.min(800, wa.height - 60) })
    win.focus()
  }
  return true
}
const screenSize = () => { const d = screen.getPrimaryDisplay(); return { w: d.size.width * d.scaleFactor, h: d.size.height * d.scaleFactor } }

/** IPC for the game window. Returns undefined for anything that isn't game mode's. */
async function handle (what, arg, send) {
  switch (what) {
    case 'gameModels': return { ready: haveModels(), mb: Math.round((MODELS.hand.size + MODELS.face.size) / 1e5) / 10 }
    case 'gameDownload': return downloadModels((p) => send('game-progress', p))
    case 'gameModel': return new Uint8Array(fs.readFileSync(modelPath(arg === 'face' ? 'face' : 'hand')))
    case 'gameInput': return input(arg || {})
    case 'gameRelease': releaseAll(); return true
    case 'gameCompact': return compact(arg)
    case 'gameScreen': return screenSize()
    case 'gameClose': if (win && !win.isDestroyed()) win.close(); return true
  }
  return undefined
}

module.exports = { open, handle, releaseAll, isOpen: () => !!(win && !win.isDestroyed()), window: () => win }
