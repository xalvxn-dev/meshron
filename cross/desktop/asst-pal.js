// Meshron Assistant for Windows: companions (Strider, Wisp, Drift, Bolt) that live on the screen.
// One see-through, click-through window covers the work area; the companion draws itself there.
// A small long-running PowerShell helper reports where app windows are (a few times a second) so
// the companion can stand on their top edges and sit on the one you're using.

const { BrowserWindow, screen, app } = require('electron')
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')

const HELPER = `
$src = @'
using System; using System.Text; using System.Collections.Generic; using System.Runtime.InteropServices;
public static class PW {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr p);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] static extern int GetWindowTextLength(IntPtr h);
  [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int i);
  [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int a, out RECT r, int s);
  [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int a, out int v, int s);
  public static string List() {
    var fg = GetForegroundWindow(); var sb = new StringBuilder("["); int n = 0;
    EnumWindows((h, p) => {
      if (n >= 14 || !IsWindowVisible(h) || IsIconic(h) || GetWindowTextLength(h) == 0) return true;
      if ((GetWindowLong(h, -20) & 0x80) != 0) return true; // tool windows (ours, popups)
      int cloaked; if (DwmGetWindowAttribute(h, 14, out cloaked, 4) == 0 && cloaked != 0) return true;
      RECT r; if (DwmGetWindowAttribute(h, 9, out r, 16) != 0) return true; // the visible frame
      if (r.R - r.L < 120 || r.B - r.T < 80) return true;
      if (n > 0) sb.Append(",");
      sb.Append("{\\"id\\":" + h.ToInt64() + ",\\"x\\":" + r.L + ",\\"y\\":" + r.T + ",\\"w\\":" + (r.R - r.L) + ",\\"h\\":" + (r.B - r.T) + ",\\"fg\\":" + (h == fg ? "true" : "false") + "}");
      n++; return true;
    }, IntPtr.Zero);
    return sb.Append("]").ToString();
  }
}
'@
Add-Type -TypeDefinition $src
while ($true) { [Console]::Out.WriteLine([PW]::List()); [Console]::Out.Flush(); Start-Sleep -Milliseconds 350 }`

let win = null
let helper = null
let lastJson = ''

function startHelper () {
  if (helper) return
  const file = path.join(app.getPath('userData'), 'pal-windows.ps1')
  fs.writeFileSync(file, HELPER.replace(/\r?\n/g, '\r\n'))
  helper = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', file], { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] })
  let buf = ''
  helper.stdout.on('data', (d) => {
    buf += d
    let i
    while ((i = buf.indexOf('\n')) >= 0) {
      const lineText = buf.slice(0, i).trim(); buf = buf.slice(i + 1)
      if (!lineText.startsWith('[') || lineText === lastJson || !win || win.isDestroyed()) continue
      lastJson = lineText
      try {
        // Physical pixels to this window's coordinates.
        const d2 = screen.getPrimaryDisplay(); const k = d2.scaleFactor || 1; const wa = d2.workArea
        const list = JSON.parse(lineText).map((w) => ({ id: w.id, fg: w.fg, x: w.x / k - wa.x, y: w.y / k - wa.y, w: w.w / k, h: w.h / k }))
        win.webContents.send('a-event', 'pal-windows', list)
      } catch {}
    }
  })
  helper.on('exit', () => { helper = null })
}
function stopHelper () { if (helper) { helper.kill(); helper = null } }

/** Shows the companion layer (hidden: leave it out of screenshots). */
function open (hidden) {
  if (win && !win.isDestroyed()) { win.showInactive(); return }
  const wa = screen.getPrimaryDisplay().workArea
  win = new BrowserWindow({
    x: wa.x, y: wa.y, width: wa.width, height: wa.height, frame: false, transparent: true, resizable: false, movable: false, focusable: false,
    alwaysOnTop: true, skipTaskbar: true, hasShadow: false, show: false, backgroundColor: '#00000000', title: 'Meshron companion',
    webPreferences: { preload: path.join(__dirname, 'asst-preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, backgroundThrottling: false }
  })
  win.setAlwaysOnTop(true, 'screen-saver')
  win.setIgnoreMouseEvents(true, { forward: true })
  win.setContentProtection(!!hidden)
  win.loadFile(path.join(__dirname, 'asst-bundle', 'pal.html'))
  win.once('ready-to-show', () => win.showInactive())
  win.on('closed', () => { win = null; stopHelper() })
  startHelper()
  // Follow changes to the screen (taskbar moved, resolution changed).
  screen.on('display-metrics-changed', () => { if (win && !win.isDestroyed()) win.setBounds(screen.getPrimaryDisplay().workArea) })
}
function close () { if (win && !win.isDestroyed()) win.close(); stopHelper() }
const send = (ev, d) => { if (win && !win.isDestroyed()) win.webContents.send('a-event', ev, d) }

module.exports = { open, close, send, isOpen: () => !!(win && !win.isDestroyed()), window: () => win, mouse: (on) => { if (win && !win.isDestroyed()) win.setIgnoreMouseEvents(!on, { forward: true }) } }
