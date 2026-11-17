// Copies the shared moderator screens (cross/mod) into ./mod-bundle for the Windows Mod app,
// without the local-testing server address.
const fs = require('fs')
const path = require('path')
const from = path.join(__dirname, '..', 'mod')
const out = path.join(__dirname, 'mod-bundle')
fs.rmSync(out, { recursive: true, force: true })
fs.cpSync(from, out, { recursive: true })
const html = path.join(out, 'index.html')
fs.writeFileSync(html, fs.readFileSync(html, 'utf8').replace(' http://127.0.0.1:8799', ''))
console.log('mod-bundle ready')
