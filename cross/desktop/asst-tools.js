// Meshron Assistant for Windows: the tools the AI can use on the PC.
// Apps (Start menu + processes), keyboard and mouse (user32 via PowerShell), clicking by name
// (Windows UI Automation), files (Node fs; deletes go to the Recycle Bin), and the rest.
// Anything that closes apps or deletes asks the person first (approve()).

const { execFile } = require('child_process')
const fs = require('fs')
const path = require('path')
const os = require('os')
const { app, shell, clipboard, desktopCapturer, screen } = require('electron')

/** Runs a PowerShell script (no profile, hidden) and returns its output. */
function ps (script, timeout = 20000) {
  return new Promise((resolve, reject) => {
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script], { windowsHide: true, timeout, maxBuffer: 8 * 1024 * 1024 }, (err, out, errOut) => {
      if (err && !out) return reject(new Error(String(errOut || err.message).trim().split('\n')[0].slice(0, 200)))
      resolve(String(out).trim())
    })
  })
}
const q = (s) => "'" + String(s).replace(/'/g, "''") + "'" // a PowerShell single-quoted string

// user32 helpers: keys, mouse, windows.
const USER32 = `
if (-not ('MA.U' -as [type])) { Add-Type -Namespace MA -Name U -MemberDefinition @'
[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
[DllImport("user32.dll")] public static extern void mouse_event(uint flags, int dx, int dy, int data, UIntPtr extra);
[DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
'@ }`

const VK = {
  ctrl: 0x11, control: 0x11, shift: 0x10, alt: 0x12, win: 0x5B, windows: 0x5B, enter: 0x0D, return: 0x0D, tab: 0x09, esc: 0x1B, escape: 0x1B,
  space: 0x20, backspace: 0x08, delete: 0x2E, del: 0x2E, insert: 0x2D, home: 0x24, end: 0x23, pageup: 0x21, pagedown: 0x22,
  up: 0x26, down: 0x28, left: 0x25, right: 0x27, printscreen: 0x2C, capslock: 0x14,
  volumeup: 0xAF, volumedown: 0xAE, mute: 0xAD, playpause: 0xB3, next: 0xB0, previous: 0xB1, stop: 0xB2
}
for (let i = 1; i <= 12; i++) VK['f' + i] = 0x6F + i
function vkOf (k) {
  k = k.toLowerCase().trim()
  if (VK[k] != null) return VK[k]
  if (/^[a-z0-9]$/.test(k)) return k.toUpperCase().charCodeAt(0)
  const sym = { '-': 0xBD, '=': 0xBB, ',': 0xBC, '.': 0xBE, '/': 0xBF, ';': 0xBA, "'": 0xDE, '[': 0xDB, ']': 0xDD, '\\': 0xDC, '`': 0xC0 }
  return sym[k] != null ? sym[k] : null
}
/** Presses a combo like "ctrl+shift+t", "win+d", "alt+f4", "enter". */
async function keys (combo) {
  const parts = String(combo).split('+').map((x) => x.trim()).filter(Boolean)
  const codes = parts.map(vkOf)
  if (!codes.length || codes.some((c) => c == null)) throw new Error('Unknown key in "' + combo + '"')
  const down = codes.map((c) => `[MA.U]::keybd_event(${c},0,0,[UIntPtr]::Zero)`).join(';')
  const up = codes.slice().reverse().map((c) => `[MA.U]::keybd_event(${c},0,2,[UIntPtr]::Zero)`).join(';')
  await ps(USER32 + ';' + down + '; Start-Sleep -Milliseconds 40;' + up)
}

// ---- Apps ----
let startApps = null
async function apps () {
  if (startApps && Date.now() - startApps.at < 5 * 60_000) return startApps.list
  const out = await ps('Get-StartApps | ForEach-Object { $_.Name + "`t" + $_.AppID }')
  const list = out.split(/\r?\n/).map((l) => l.split('\t')).filter((x) => x[0] && x[1]).map(([name, id]) => ({ name, id }))
  startApps = { at: Date.now(), list }
  return list
}
function best (list, query, key = 'name') {
  const qq = query.toLowerCase().trim()
  return list.find((x) => x[key].toLowerCase() === qq) || list.find((x) => x[key].toLowerCase().startsWith(qq)) || list.find((x) => x[key].toLowerCase().includes(qq))
}
async function windows () {
  const out = await ps('Get-Process | Where-Object { $_.MainWindowTitle } | ForEach-Object { $_.Id.ToString() + "`t" + $_.ProcessName + "`t" + $_.MainWindowTitle }')
  return out.split(/\r?\n/).map((l) => l.split('\t')).filter((x) => x.length === 3).map(([id, proc, title]) => ({ id: +id, proc, title }))
    .filter((w) => !/^(Meshron Assistant|electron)$/i.test(w.title))
}
async function findWindow (name) {
  const ws = await windows()
  return best(ws, name, 'title') || best(ws, name, 'proc')
}

// ---- Folders ----
const KNOWN = () => ({
  downloads: app.getPath('downloads'), documents: app.getPath('documents'), desktop: app.getPath('desktop'),
  pictures: app.getPath('pictures'), music: app.getPath('music'), videos: app.getPath('videos'), home: os.homedir()
})
/** "Downloads\\Invoices", "documents/x" or a full path. */
function resolvePath (p) {
  p = String(p || '').trim().replace(/^["']|["']$/g, '')
  if (!p) return app.getPath('downloads')
  if (path.isAbsolute(p)) return path.normalize(p)
  const [head, ...rest] = p.split(/[\\/]/)
  const base = KNOWN()[head.toLowerCase()]
  return base ? path.join(base, ...rest) : path.join(os.homedir(), p)
}
function matcher (x) {
  const names = Array.isArray(x.names) ? x.names.map((n) => String(n).toLowerCase()) : []
  const pat = String(x.pattern || '').trim().toLowerCase()
  if (names.length) return (n) => names.includes(n.toLowerCase())
  if (pat) { const re = new RegExp('^' + pat.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + '$', 'i'); return (n) => re.test(n) }
  return () => false
}
async function moveOne (from, to) {
  try { await fs.promises.rename(from, to) } catch (e) {
    if (e.code !== 'EXDEV') throw e
    await fs.promises.cp(from, to, { recursive: true }); await fs.promises.rm(from, { recursive: true })
  }
}

// ---- Volume (Core Audio) ----
const AUDIO = `
if (-not ('MA.Vol' -as [type])) { Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
namespace MA {
[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioEndpointVolume { int f(); int g(); int h(); int i(); int SetMasterVolumeLevelScalar(float l, System.Guid c); int j(); int GetMasterVolumeLevelScalar(out float l); }
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice { int Activate(ref System.Guid id, int ctx, int p, [MarshalAs(UnmanagedType.IUnknown)] out object o); }
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator { int f(); int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice d); }
[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class En { }
public class Vol { public static void Set(float l) { var e = (IMMDeviceEnumerator)(new En()); IMMDevice d; e.GetDefaultAudioEndpoint(0, 1, out d); var id = typeof(IAudioEndpointVolume).GUID; object o; d.Activate(ref id, 23, 0, out o); ((IAudioEndpointVolume)o).SetMasterVolumeLevelScalar(l, System.Guid.Empty); } }
}
'@ }`

/**
 * Runs one tool. ctx: { approve(title, detail), notify(text), schedule(text, atMs), tasks(), saveTasks(list), pickFile() }.
 * Returns { text, image?, question? }.
 */
async function run (name, x, ctx) {
  const ok = (text, extra) => Object.assign({ text }, extra || {})
  switch (name) {
    case 'open_app': {
      const n = String(x.name || '').trim()
      if (!n) return ok('Which app?')
      const a = best(await apps(), n.replace(/ app$/i, ''))
      if (a) { await ps('Start-Process ' + q('shell:AppsFolder\\' + a.id)); return ok('Opened ' + a.name) }
      const exe = n.toLowerCase().replace(/\s+/g, '') // e.g. "notepad", "calc"
      try { await ps('Start-Process ' + q(exe)); return ok('Opened ' + n) } catch { return ok("I couldn't find an app called " + n) }
    }
    case 'close_app': {
      const w = await findWindow(String(x.name || ''))
      if (!w) return ok("I can't see an open window called " + x.name)
      if (!(await ctx.approve('Close ' + w.title + '?', 'Unsaved work in it may be lost if it doesn\'t ask to save.'))) return ok('The person didn\'t approve closing it')
      await ps('(Get-Process -Id ' + w.id + ').CloseMainWindow() | Out-Null')
      return ok('Asked ' + w.title + ' to close')
    }
    case 'switch_to': {
      const w = await findWindow(String(x.name || ''))
      if (!w) return ok("I can't see an open window called " + x.name)
      await ps(USER32 + '; $h=(Get-Process -Id ' + w.id + ').MainWindowHandle; if ([MA.U]::IsIconic($h)) { [MA.U]::ShowWindow($h, 9) | Out-Null }; [MA.U]::keybd_event(0x12,0,0,[UIntPtr]::Zero); [MA.U]::SetForegroundWindow($h) | Out-Null; [MA.U]::keybd_event(0x12,0,2,[UIntPtr]::Zero)')
      return ok('Switched to ' + w.title)
    }
    case 'list_windows': {
      const ws = await windows()
      return ok(ws.length ? ws.map((w) => '- ' + w.title + ' (' + w.proc + ')').join('\n') : 'No app windows are open')
    }
    case 'show_desktop': await ps('(New-Object -ComObject Shell.Application).MinimizeAll()'); return ok('Showing the desktop')
    case 'open_url': {
      const u = String(x.url || '')
      if (!/^https?:\/\//.test(u)) return ok('Only web links can be opened')
      await shell.openExternal(u); return ok('Opened ' + u)
    }
    case 'open_path': {
      const p = resolvePath(x.path)
      const err = await shell.openPath(p)
      return ok(err ? 'Couldn\'t open ' + p + ': ' + err : 'Opened ' + p)
    }
    case 'type_text': {
      // Through the clipboard (handles every language and emoji), then put the clipboard back.
      const old = clipboard.readText()
      clipboard.writeText(String(x.text || ''))
      await keys('ctrl+v')
      setTimeout(() => clipboard.writeText(old), 600)
      return ok('Typed it')
    }
    case 'press_keys': await keys(String(x.keys || '')); return ok('Pressed ' + x.keys)
    case 'click': {
      const target = String(x.target || '').trim()
      if (!target) return ok('Click what?')
      const out = await ps(USER32 + `
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$fg = Add-Type -Name FG -Namespace MA2 -PassThru -MemberDefinition '[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();'
$root = [System.Windows.Automation.AutomationElement]::FromHandle($fg::GetForegroundWindow())
$want = ${q(target.toLowerCase())}
$all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
$hit = $null; $part = $null
foreach ($e in $all) { $n = $e.Current.Name; if (-not $n) { continue }; $l = $n.ToLower(); if ($l -eq $want) { $hit = $e; break }; if (-not $part -and $l.Contains($want)) { $part = $e } }
if (-not $hit) { $hit = $part }
if (-not $hit) { 'NOTFOUND'; exit }
$p = $null
if ($hit.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$p)) { $p.Invoke(); 'OK ' + $hit.Current.Name; exit }
if ($hit.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$p)) { $p.Select(); 'OK ' + $hit.Current.Name; exit }
if ($hit.TryGetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern, [ref]$p)) { $p.Toggle(); 'OK ' + $hit.Current.Name; exit }
if ($hit.TryGetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern, [ref]$p)) { $p.Expand(); 'OK ' + $hit.Current.Name; exit }
$r = $hit.Current.BoundingRectangle
[MA.U]::SetCursorPos([int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2)) | Out-Null
[MA.U]::mouse_event(2,0,0,0,[UIntPtr]::Zero); [MA.U]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
'OK ' + $hit.Current.Name`, 30000)
      return ok(out.startsWith('OK') ? 'Clicked ' + out.slice(3) : 'I can\'t find "' + target + '" in the window in front. Try read_screen to see it.')
    }
    case 'scroll': {
      const n = Math.max(1, Math.min(30, Number(x.amount) || 5))
      const delta = (String(x.direction) === 'up' ? 120 : -120) * n
      await ps(USER32 + `;[MA.U]::mouse_event(0x0800,0,0,${delta},[UIntPtr]::Zero)`)
      return ok('Scrolled ' + x.direction)
    }
    case 'read_screen': {
      const d = screen.getPrimaryDisplay()
      const s = await ctx.unseen(() => desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: Math.min(1600, d.size.width), height: Math.round(Math.min(1600, d.size.width) * d.size.height / d.size.width) } }))
      if (!s.length) return ok('Couldn\'t see the screen')
      const img = 'data:image/jpeg;base64,' + s[0].thumbnail.toJPEG(80).toString('base64')
      return ok('A screenshot of the screen is attached.', { image: img, question: String(x.question || 'What is on my screen?') })
    }
    case 'list_files': {
      const p = resolvePath(x.path)
      const list = await fs.promises.readdir(p, { withFileTypes: true }).catch(() => null)
      if (!list) return ok('No such folder: ' + p)
      return ok(p + '\n' + (list.length ? list.slice(0, 200).map((e) => (e.isDirectory() ? '[folder] ' : '') + e.name).join('\n') : '(empty)'))
    }
    case 'make_folder': { const p = resolvePath(x.path); await fs.promises.mkdir(p, { recursive: true }); return ok('Folder ready: ' + p) }
    case 'move_files': {
      const from = resolvePath(x.from); const to = resolvePath(x.to)
      const m = matcher(x)
      const files = (await fs.promises.readdir(from, { withFileTypes: true }).catch(() => [])).filter((e) => e.isFile() && m(e.name))
      if (!files.length) return ok('No files matched in ' + from)
      await fs.promises.mkdir(to, { recursive: true })
      let n = 0
      for (const f of files) {
        let dest = path.join(to, f.name)
        for (let i = 2; fs.existsSync(dest); i++) { const e = path.extname(f.name); dest = path.join(to, path.basename(f.name, e) + ' (' + i + ')' + e) }
        try { await moveOne(path.join(from, f.name), dest); n++ } catch {}
      }
      return ok('Moved ' + n + ' of ' + files.length + ' file(s) to ' + to)
    }
    case 'delete_files': {
      const p = resolvePath(x.path); const m = matcher(x)
      const items = (await fs.promises.readdir(p).catch(() => [])).filter(m)
      if (!items.length) return ok('No files matched in ' + p)
      if (!(await ctx.approve('Move ' + items.length + ' item(s) to the Recycle Bin?', items.slice(0, 6).join(', ') + (items.length > 6 ? '…' : '')))) return ok('The person didn\'t approve deleting')
      let n = 0
      for (const it of items) { try { await shell.trashItem(path.join(p, it)); n++ } catch {} }
      return ok('Moved ' + n + ' item(s) to the Recycle Bin')
    }
    case 'set_timer': {
      const s = Math.round(Number(x.seconds) || 0)
      if (s < 1 || s > 86400) return ok('That length isn\'t valid')
      ctx.schedule('Timer' + (x.label ? ': ' + x.label : '') + ' is done', Date.now() + s * 1000)
      return ok('Timer started for ' + Math.floor(s / 60) + ' min ' + (s % 60) + ' s')
    }
    case 'add_reminder': {
      const at = new Date(String(x.at || '').slice(0, 16))
      if (isNaN(at) || at < new Date()) return ok('That time isn\'t valid or has passed')
      ctx.schedule('Reminder: ' + String(x.text || ''), at.getTime())
      return ok('Reminder set for ' + at.toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }))
    }
    case 'add_task': { const t = ctx.tasks(); t.push({ text: String(x.text || '').slice(0, 200), due: x.due || '', done: false, at: Date.now() }); ctx.saveTasks(t.slice(-100)); return ok('Added to tasks') }
    case 'list_tasks': { const open = ctx.tasks().filter((t) => !t.done); return ok(open.length ? open.map((t) => '- ' + t.text + (t.due ? ' (due ' + t.due + ')' : '')).join('\n') : 'No open tasks') }
    case 'complete_task': {
      const t = ctx.tasks(); const hit = t.find((k) => !k.done && k.text.toLowerCase().includes(String(x.text || '').toLowerCase()))
      if (!hit) return ok('No open task matches that')
      hit.done = true; ctx.saveTasks(t); return ok('Done: ' + hit.text)
    }
    case 'set_volume': {
      const l = Math.max(0, Math.min(100, Number(x.level) || 0))
      await ps(AUDIO + `;[MA.Vol]::Set(${(l / 100).toFixed(2)})`)
      return ok('Volume set to ' + l + '%')
    }
    case 'media': {
      const k = { play_pause: 'playpause', next: 'next', previous: 'previous', mute: 'mute' }[String(x.action)]
      if (!k) return ok('Unknown media action')
      await keys(k); return ok('Done: ' + x.action)
    }
    case 'battery_status': {
      const out = await ps('$b = Get-CimInstance Win32_Battery; if ($b) { "$($b.EstimatedChargeRemaining)`t$($b.BatteryStatus)" } else { "none" }')
      if (out === 'none' || !out) return ok('This PC has no battery (it runs on mains power)')
      const [pct, st] = out.split('\t')
      return ok('Battery ' + pct + '%, ' + (st === '2' || st === '6' || st === '7' || st === '8' || st === '9' ? 'charging' : 'on battery'))
    }
    case 'lock_pc': execFile('rundll32.exe', ['user32.dll,LockWorkStation']); return ok('PC locked')
    case 'read_document': {
      const f = await ctx.pickFile()
      if (!f) return ok('No document was picked')
      const ext = path.extname(f).toLowerCase()
      if (['.png', '.jpg', '.jpeg', '.webp'].includes(ext)) {
        const { nativeImage } = require('electron')
        const img = nativeImage.createFromPath(f)
        const s = img.getSize(); const k = Math.min(1, 1400 / Math.max(s.width, s.height))
        return ok('The picture "' + path.basename(f) + '" is attached.', { image: 'data:image/jpeg;base64,' + img.resize({ width: Math.round(s.width * k) }).toJPEG(82).toString('base64'), question: String(x.question || 'Explain this.') })
      }
      const text = (await fs.promises.readFile(f)).subarray(0, 60000).toString('utf8')
      return ok('Document "' + path.basename(f) + '":\n' + text.slice(0, 12000))
    }
    case 'showcase': return ok(await ctx.showcase(String(x.kind || '')))
    case 'point_at': {
      // Teaching: circle a control by its name (UI Automation, window in front first), or a spot
      // given as fractions of the screen (from what read_screen showed).
      const note = String(x.note || '').slice(0, 120)
      const target = String(x.target || '').trim()
      if (!target && x.x != null && x.y != null) {
        const d = screen.getPrimaryDisplay(); const k = d.scaleFactor || 1
        const fx = Math.max(0, Math.min(1, +x.x)); const fy = Math.max(0, Math.min(1, +x.y))
        const w = 90 * k; const h = 60 * k
        ctx.point({ x: (d.bounds.x + fx * d.bounds.width) * k - w / 2, y: (d.bounds.y + fy * d.bounds.height) * k - h / 2, width: w, height: h }, note)
        return ok('Pointing at that spot' + (note ? ': ' + note : ''))
      }
      if (!target) return ok('Point at what?')
      const out = await ps(`
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$fg = Add-Type -Name FG3 -Namespace MA3 -PassThru -MemberDefinition '[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();'
$want = ${q(target.toLowerCase())}
$roots = @([System.Windows.Automation.AutomationElement]::FromHandle($fg::GetForegroundWindow()), [System.Windows.Automation.AutomationElement]::RootElement)
foreach ($root in $roots) {
  $scope = if ($root -eq [System.Windows.Automation.AutomationElement]::RootElement) { [System.Windows.Automation.TreeScope]::Children } else { [System.Windows.Automation.TreeScope]::Descendants }
  $all = $root.FindAll($scope, [System.Windows.Automation.Condition]::TrueCondition)
  $hit = $null; $part = $null
  foreach ($e in $all) { $n = $e.Current.Name; if (-not $n) { continue }; $r = $e.Current.BoundingRectangle; if ($r.IsEmpty -or $r.Width -lt 2) { continue }; $l = $n.ToLower(); if ($l -eq $want) { $hit = $e; break }; if (-not $part -and $l.Contains($want)) { $part = $e } }
  if (-not $hit) { $hit = $part }
  if ($hit) { $r = $hit.Current.BoundingRectangle; 'OK|' + [int]$r.X + '|' + [int]$r.Y + '|' + [int]$r.Width + '|' + [int]$r.Height + '|' + $hit.Current.Name; exit }
}
'NOTFOUND'`, 30000)
      if (!out.startsWith('OK|')) return ok('I can\'t find "' + target + '" on screen. Try read_screen and point by position.')
      const [, rx, ry, rw, rh, name] = out.trim().split('|')
      ctx.point({ x: +rx, y: +ry, width: +rw, height: +rh }, note || name)
      return ok('Pointing at ' + name + ' on screen')
    }
    default: return ok('Unknown tool ' + name)
  }
}

module.exports = { run, keys, apps, ps }
