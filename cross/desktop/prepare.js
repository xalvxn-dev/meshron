// Copies the shared core, the screens and the networking worklet into ./bundle, so the
// Windows app ships one self-contained copy (run before `electron .` and before packaging).

const fs = require('fs')
const path = require('path')

const here = __dirname
const out = path.join(here, 'bundle')
fs.rmSync(out, { recursive: true, force: true })

function copyDir(from, to, skip = () => false) {
  fs.mkdirSync(to, { recursive: true })
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    const a = path.join(from, e.name)
    const b = path.join(to, e.name)
    if (skip(e.name)) continue
    if (e.isDirectory()) copyDir(a, b, skip)
    else fs.copyFileSync(a, b)
  }
}

copyDir(path.join(here, '..', 'core'), path.join(out, 'core'), (n) => n === 'dev-server.js' || n === 'boot-bare.js')
copyDir(path.join(here, '..', 'ui'), path.join(out, 'ui'))
fs.mkdirSync(path.join(out, 'worklet'), { recursive: true })
// Every worklet file (app.js requires ./reels and ./live).
for (const f of fs.readdirSync(path.join(here, '..', '..', 'app', 'src', 'main', 'js'))) if (f.endsWith('.js')) fs.copyFileSync(path.join(here, '..', '..', 'app', 'src', 'main', 'js', f), path.join(out, 'worklet', f))

const icon = path.join(here, 'build', 'icon.png')
if (!fs.existsSync(icon)) {
  fs.mkdirSync(path.dirname(icon), { recursive: true })
  fs.copyFileSync(path.join(here, '..', '..', 'store', 'icon-512.png'), icon)
}
fs.copyFileSync(icon, path.join(out, 'icon.png'))
console.log('Prepared', out)
