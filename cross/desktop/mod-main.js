// Meshron Mod for Windows: a window around the shared moderator screens (cross/mod).
// The sign-in key is kept with Windows' own encryption (safeStorage), like Android's keystore.

const { app, BrowserWindow, ipcMain, safeStorage, shell } = require('electron')
const path = require('path')
const fs = require('fs')

if (!app.requestSingleInstanceLock()) app.quit()

const dir = path.join(__dirname, 'mod-bundle')
const storeFile = () => path.join(app.getPath('userData'), 'mod-store.json')
function readStore () { try { return JSON.parse(fs.readFileSync(storeFile(), 'utf8')) } catch { return {} } }

ipcMain.handle('mod-store-get', (_e, k) => {
  const v = readStore()[k]
  if (!v) return null
  try { return safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(Buffer.from(v, 'base64')) : null } catch { return null }
})
ipcMain.handle('mod-store-set', (_e, k, v) => {
  const s = readStore()
  if (v == null) delete s[k]
  else if (safeStorage.isEncryptionAvailable()) s[k] = safeStorage.encryptString(String(v)).toString('base64')
  else return false
  fs.mkdirSync(path.dirname(storeFile()), { recursive: true })
  fs.writeFileSync(storeFile(), JSON.stringify(s))
  return true
})

let win = null
function open () {
  win = new BrowserWindow({
    width: 1100, height: 780, minWidth: 380, minHeight: 560, backgroundColor: '#0B0D14', title: 'Meshron Mod',
    icon: path.join(dir, 'icons', 'icon.png'), autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'mod-preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false }
  })
  win.loadFile(path.join(dir, 'index.html'))
  // Links open in the browser, never inside the app.
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: 'deny' } })
  win.webContents.on('will-navigate', (e) => e.preventDefault())
}
app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus() } })
app.whenReady().then(open)
app.on('window-all-closed', () => app.quit())
