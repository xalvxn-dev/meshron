// Meshron Assistant for Windows: bigger powers.
//   create_shortcut  a desktop (or Start menu) shortcut to an app, file, folder or website
//   memory_usage     what's using the RAM (and CPU), with a visual
//   draw_in_paint    opens Paint and draws lines, circles and boxes with the mouse
//   show_how         teaches on screen: a hologram mouse pointer moves, points, circles,
//                    highlights, clicks, drags and drops, step by step (and can really do it)
// Real mouse input goes through game mode's SendInput helper (one long-running PowerShell).

const { screen, shell } = require('electron')
const fs = require('fs')
const path = require('path')
const os = require('os')

const wait = (ms) => new Promise((r) => setTimeout(r, ms))

module.exports = function power ({ ps, q, apps, best, input, show, hidden }) {
  // ---- Finding things on screen ----
  /** A control by its name (window in front first, then top-level windows); physical pixels. */
  async function locate (target) {
    const out = await ps(`
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$fg = Add-Type -Name FG4 -Namespace MA4 -PassThru -MemberDefinition '[DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();'
$want = ${q(String(target).toLowerCase())}
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
    if (!out.startsWith('OK|')) return null
    const [, x, y, w, h, name] = out.trim().split('|')
    return { x: +x, y: +y, width: +w, height: +h, name }
  }
  /** A spot given as fractions of the screen (0-1), as a small physical-pixel box. */
  function spot (fx, fy) {
    const d = screen.getPrimaryDisplay(); const k = d.scaleFactor || 1
    const x = (d.bounds.x + Math.max(0, Math.min(1, +fx)) * d.bounds.width) * k; const y = (d.bounds.y + Math.max(0, Math.min(1, +fy)) * d.bounds.height) * k
    return { x: x - 30 * k, y: y - 20 * k, width: 60 * k, height: 40 * k, name: '' }
  }
  const where = async (o) => (o && o.target ? locate(o.target) : o && o.x != null && o.y != null ? spot(o.x, o.y) : null)
  const centre = (r) => ({ x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) })

  // ---- Shortcuts ----
  async function createShortcut (x) {
    const target = String(x.target || '').trim()
    if (!target) return 'Shortcut to what?'
    const where2 = String(x.where || 'desktop').toLowerCase().includes('start') ? path.join(process.env.APPDATA, 'Microsoft', 'Windows', 'Start Menu', 'Programs') : path.join(os.homedir(), 'Desktop')
    const safe = (s) => String(s).replace(/[\\/:*?"<>|]/g, '').trim().slice(0, 60) || 'Shortcut'
    if (/^https?:\/\//i.test(target)) {
      const name = safe(x.name || new URL(target).hostname.replace(/^www\./, ''))
      fs.writeFileSync(path.join(where2, name + '.url'), '[InternetShortcut]\r\nURL=' + target + '\r\n')
      return 'Made a shortcut "' + name + '" to ' + target + (where2.includes('Desktop') ? ' on your desktop' : ' in the Start menu')
    }
    // A file or folder that exists, or else an installed app by name.
    const p = target.replace(/^~/, os.homedir())
    let file; let args = ''; let label
    if (fs.existsSync(p)) { file = p; label = path.basename(p).replace(/\.[^.]+$/, '') } else {
      const a = best(await apps(), target.replace(/ app$/i, ''))
      if (!a) return 'I can\'t find an app, file or folder called "' + target + '"'
      label = a.name
      if (/^[A-Za-z]:\\/.test(a.id) && fs.existsSync(a.id)) file = a.id
      else { file = path.join(process.env.WINDIR || 'C:\\Windows', 'explorer.exe'); args = 'shell:AppsFolder\\' + a.id }
    }
    const name = safe(x.name || label)
    const lnk = path.join(where2, name + '.lnk')
    const out = await ps(`$s = (New-Object -ComObject WScript.Shell).CreateShortcut(${q(lnk)}); $s.TargetPath = ${q(file)}; $s.Arguments = ${q(args)}; $s.WorkingDirectory = ${q(path.dirname(file))}; $s.Save(); 'OK'`)
    return out.includes('OK') ? 'Made a shortcut "' + name + '"' + (where2.includes('Desktop') ? ' on your desktop' : ' in the Start menu') : 'Couldn\'t make the shortcut'
  }

  // ---- What's using the memory ----
  async function memory () {
    const out = await ps(`$t = Get-CimInstance Win32_OperatingSystem; "TOTAL|" + $t.TotalVisibleMemorySize + "|" + $t.FreePhysicalMemory
Get-Process | Group-Object ProcessName | ForEach-Object { $ws = ($_.Group | Measure-Object WorkingSet64 -Sum).Sum; $cpu = ($_.Group | Measure-Object CPU -Sum).Sum; $d = ($_.Group | Where-Object { $_.MainWindowTitle } | Select-Object -First 1).Description; if (-not $d) { $d = ($_.Group | Select-Object -First 1).Description }; [pscustomobject]@{ n = $_.Name; c = $_.Count; ws = $ws; cpu = $cpu; d = $d } } | Sort-Object ws -Descending | Select-Object -First 10 | ForEach-Object { "P|" + $_.n + "|" + $_.c + "|" + $_.ws + "|" + [int]$_.cpu + "|" + $_.d }`, 25000)
    const lines = out.split(/\r?\n/)
    const t = (lines.find((l) => l.startsWith('TOTAL|')) || 'TOTAL|0|0').split('|')
    const total = +t[1] * 1024; const free = +t[2] * 1024
    const procs = lines.filter((l) => l.startsWith('P|')).map((l) => { const [, n, c, ws, cpu, ...d] = l.split('|'); return { name: (d.join('|') || n).trim() || n, proc: n, count: +c, bytes: +ws, cpu: +cpu } })
    return { total, used: total - free, procs }
  }
  const gb = (b) => (b / 1073741824).toFixed(1) + ' GB'
  const mb = (b) => (b >= 1073741824 ? gb(b) : Math.round(b / 1048576) + ' MB')

  // ---- Drawing in Paint ----
  async function drawInPaint (x) {
    const items = Array.isArray(x.shapes) ? x.shapes.slice(0, 60) : []
    if (!items.length) return 'Tell me what to draw (lines, circles or boxes).'
    await ps('Start-Process mspaint')
    // Wait for Paint's window, bring it forward, maximised.
    let rect = null
    for (let i = 0; i < 20 && !rect; i++) {
      await wait(500)
      const out = await ps(`Add-Type -Name W5 -Namespace MA5 -MemberDefinition '[DllImport("user32.dll")] public static extern bool ShowWindow(System.IntPtr h, int c); [DllImport("user32.dll")] public static extern bool SetForegroundWindow(System.IntPtr h); [DllImport("user32.dll")] public static extern bool GetWindowRect(System.IntPtr h, out RECT r); public struct RECT { public int L, T, R, B; }'
$p = Get-Process mspaint -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
if ($p) { $h = $p.MainWindowHandle; [MA5.W5]::ShowWindow($h, 3) | Out-Null; [MA5.W5]::SetForegroundWindow($h) | Out-Null; Start-Sleep -Milliseconds 400; $r = New-Object MA5.W5+RECT; [MA5.W5]::GetWindowRect($h, [ref]$r) | Out-Null; "R|" + $r.L + "|" + $r.T + "|" + $r.R + "|" + $r.B }`)
      const m = /R\|(-?\d+)\|(-?\d+)\|(-?\d+)\|(-?\d+)/.exec(out)
      if (m) rect = { L: +m[1], T: +m[2], R: +m[3], B: +m[4] }
    }
    if (!rect) return 'Paint didn\'t open'
    await wait(600)
    // The drawing area: the middle of Paint's window (below its ribbon), square-ish.
    const w = rect.R - rect.L; const h = rect.B - rect.T
    const top = rect.T + h * 0.3; const bottom = rect.B - h * 0.1
    const size = Math.min(w * 0.7, bottom - top)
    const ox = rect.L + (w - size) / 2; const oy = top + (bottom - top - size) / 2
    const P = (px, py) => ({ x: Math.round(ox + Math.max(0, Math.min(1, +px)) * size), y: Math.round(oy + Math.max(0, Math.min(1, +py)) * size) })
    const toLines = (it) => {
      if (Array.isArray(it.line)) return [it.line]
      if (it.circle) { const { x: cx, y: cy, r } = it.circle; return [Array.from({ length: 41 }, (_, k) => [cx + Math.cos(k / 40 * Math.PI * 2) * r, cy + Math.sin(k / 40 * Math.PI * 2) * r])] }
      if (it.rect) { const { x: rx, y: ry, w: rw, h: rh } = it.rect; return [[[rx, ry], [rx + rw, ry], [rx + rw, ry + rh], [rx, ry + rh], [rx, ry]]] }
      return []
    }
    let strokes = 0
    for (const it of items) for (const ln of toLines(it)) {
      const pts = ln.filter((p) => Array.isArray(p) && p.length >= 2).map(([a, b]) => P(a, b))
      if (pts.length < 2) continue
      input({ type: 'pos', ...pts[0] }); await wait(40)
      input({ type: 'button', b: 'l', down: true }); await wait(30)
      for (let k = 1; k < pts.length; k++) {
        // Small steps so Paint follows the line smoothly.
        const a = pts[k - 1]; const b = pts[k]; const n = Math.max(1, Math.round(Math.hypot(b.x - a.x, b.y - a.y) / 12))
        for (let j = 1; j <= n; j++) { input({ type: 'pos', x: Math.round(a.x + (b.x - a.x) * j / n), y: Math.round(a.y + (b.y - a.y) * j / n) }); await wait(8) }
      }
      input({ type: 'button', b: 'l', down: false }); await wait(40)
      strokes++
    }
    return 'Drew ' + strokes + ' stroke' + (strokes === 1 ? '' : 's') + ' in Paint'
  }

  // ---- Teaching with the hologram pointer ----
  async function showHow (x) {
    const steps = Array.isArray(x.steps) ? x.steps.slice(0, 12) : []
    if (!steps.length) return 'Which steps should I show?'
    const plan = []
    for (const s of steps) {
      const r = await where(s)
      if (!r && s.action !== 'type') { plan.push({ action: 'say', note: (s.note || '') + (s.target ? ' (I can\'t see "' + s.target + '" on screen)' : '') }); continue }
      const to = s.action === 'drag' ? await where(s.to || {}) : null
      plan.push({ action: s.action || 'point', rect: r, to, note: String(s.note || '').slice(0, 120), text: s.text || '', perform: !!(x.perform || s.perform) })
    }
    // The overlay plays every step; real clicks and drags happen at the end of their step.
    const timeline = show.demo(plan, hidden())
    let done = 0
    for (let i = 0; i < plan.length; i++) {
      await wait(timeline[i].at + timeline[i].len - (i ? timeline[i - 1].at + timeline[i - 1].len : 0))
      const p = plan[i]
      if (!p.perform || !p.rect) continue
      const c = centre(p.rect)
      if (p.action === 'click') { input({ type: 'pos', ...c }); await wait(60); input({ type: 'button', b: 'l', down: true }); await wait(60); input({ type: 'button', b: 'l', down: false }); done++ }
      if (p.action === 'drag' && p.to) {
        const e = centre(p.to)
        input({ type: 'pos', ...c }); await wait(60); input({ type: 'button', b: 'l', down: true }); await wait(120)
        for (let j = 1; j <= 20; j++) { input({ type: 'pos', x: Math.round(c.x + (e.x - c.x) * j / 20), y: Math.round(c.y + (e.y - c.y) * j / 20) }); await wait(15) }
        await wait(80); input({ type: 'button', b: 'l', down: false }); done++
      }
      if (p.action === 'type' && p.text) { input({ type: 'text', text: p.text }); done++ }
    }
    const missing = plan.filter((p) => p.action === 'say').length
    return 'Showed ' + plan.length + ' step' + (plan.length === 1 ? '' : 's') + (done ? ', did ' + done + ' of them for real' : '') + (missing ? '; ' + missing + ' couldn\'t be found on screen' : '')
  }

  return { locate, spot, createShortcut, memory, mb, drawInPaint, showHow }
}
