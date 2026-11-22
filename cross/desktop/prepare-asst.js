// Copies Meshron Assistant's screens (cross/assistant) into ./asst-bundle for the Windows app,
// with the Argon2id library from the Mod screens, and draws the app icon (the reactor) once.
const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')
const from = path.join(__dirname, '..', 'assistant')
const out = path.join(__dirname, 'asst-bundle')
fs.rmSync(out, { recursive: true, force: true })
fs.cpSync(from, out, { recursive: true })
fs.mkdirSync(path.join(out, 'vendor'), { recursive: true })
fs.copyFileSync(path.join(__dirname, '..', 'mod', 'vendor', 'argon2.umd.min.js'), path.join(out, 'vendor', 'argon2.umd.min.js'))
fs.copyFileSync(path.join(__dirname, '..', 'mod', 'vendor', 'hash-wasm-LICENSE'), path.join(out, 'vendor', 'hash-wasm-LICENSE'))

// The icon: a dark disc, broken cyan / blue / lime rings and a bright core (GDI+, 512 px).
const icons = path.join(out, 'icons')
fs.mkdirSync(icons, { recursive: true })
const png = path.join(icons, 'icon.png')
const ps = `
Add-Type -AssemblyName System.Drawing
$s = 512; $b = New-Object System.Drawing.Bitmap $s, $s
$g = [System.Drawing.Graphics]::FromImage($b); $g.SmoothingMode = 'AntiAlias'; $g.Clear([System.Drawing.Color]::Transparent)
$bg = New-Object System.Drawing.Drawing2D.GraphicsPath; $bg.AddEllipse(16, 16, 480, 480)
$br = New-Object System.Drawing.Drawing2D.PathGradientBrush $bg; $br.CenterColor = [System.Drawing.Color]::FromArgb(255, 16, 30, 52); $br.SurroundColors = @([System.Drawing.Color]::FromArgb(255, 3, 5, 10))
$g.FillPath($br, $bg)
function Arc($col, $start) { $p = New-Object System.Drawing.Pen $col, 22; $p.StartCap = 'Round'; $p.EndCap = 'Round'; $g.DrawArc($p, 116, 116, 280, 280, $start, 62) }
Arc ([System.Drawing.Color]::FromArgb(255, 34, 211, 197)) -90
Arc ([System.Drawing.Color]::FromArgb(255, 110, 123, 255)) 0
Arc ([System.Drawing.Color]::FromArgb(255, 34, 211, 197)) 90
Arc ([System.Drawing.Color]::FromArgb(255, 182, 243, 106)) 180
$ring = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(150, 34, 211, 197)), 8; $g.DrawEllipse($ring, 186, 186, 140, 140)
$glow = New-Object System.Drawing.Drawing2D.GraphicsPath; $glow.AddEllipse(196, 196, 120, 120)
$gb = New-Object System.Drawing.Drawing2D.PathGradientBrush $glow; $gb.CenterColor = [System.Drawing.Color]::White; $gb.SurroundColors = @([System.Drawing.Color]::FromArgb(0, 34, 211, 197))
$g.FillPath($gb, $glow)
$g.FillEllipse((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 242, 255, 253))), 228, 228, 56, 56)
$b.Save(${JSON.stringify(png).replace(/"/g, "'")}, [System.Drawing.Imaging.ImageFormat]::Png)`
const r = spawnSync('powershell.exe', ['-NoProfile', '-Command', ps], { windowsHide: true })
if (r.status !== 0 || !fs.existsSync(png)) fs.copyFileSync(path.join(__dirname, '..', '..', 'store', 'icon-512.png'), png)
console.log('asst-bundle ready')
