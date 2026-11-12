// Meshron for Windows: Electron shell around the shared host (cross/core) and screens (cross/ui).
//
// - The host and networking worklet run here in the main process (Node), like on a phone's
//   background service. Closing the window keeps Meshron running in the tray, so messages
//   still arrive; "Quit" in the tray menu stops it.
// - Secrets (identity seed, storage key) are encrypted with Windows DPAPI via safeStorage.
// - meshron://id/… links open the Add contact screen.

const { app, BrowserWindow, ipcMain, safeStorage, shell, Notification, Tray, Menu, nativeImage, powerSaveBlocker } = require('electron')
const path = require('path')
const fs = require('fs')

// Testing only: a separate profile folder, and a screenshot of the window after a delay.
if (process.env.MESHRON_USER_DATA) app.setPath('userData', process.env.MESHRON_USER_DATA)
if (!app.requestSingleInstanceLock()) {
  app.quit()
  process.exit(0)
}
app.setAppUserModelId('com.xalvxnlab.meshron.desktop')
if (process.defaultApp) app.setAsDefaultProtocolClient('meshron', process.execPath, [path.resolve(process.argv[1])])
else app.setAsDefaultProtocolClient('meshron')

const bundle = path.join(__dirname, 'bundle')
const dataDir = path.join(app.getPath('userData'), 'data')
fs.mkdirSync(dataDir, { recursive: true })
const iconPath = path.join(bundle, 'icon.png')

let win = null
let tray = null
let quitting = false
let pendingLink = null

// ---- Secrets (DPAPI) --------------------------------------------------------------

const secretsFile = path.join(dataDir, 'secrets.json')
function readSecrets() {
  try { return JSON.parse(fs.readFileSync(secretsFile, 'utf8')) } catch { return {} }
}
const secrets = {
  async get(name) {
    const v = readSecrets()[name]
    if (!v) return null
    return safeStorage.decryptString(Buffer.from(v, 'base64'))
  },
  async set(name, value) {
    if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows secure storage is not available')
    const all = readSecrets()
    all[name] = safeStorage.encryptString(value).toString('base64')
    const tmp = secretsFile + '.tmp'
    fs.writeFileSync(tmp, JSON.stringify(all))
    fs.renameSync(tmp, secretsFile)
  }
}

// ---- Host ------------------------------------------------------------------------

const send = (ev, data) => {
  if (win && !win.isDestroyed()) win.webContents.send('host-event', ev, data)
}
let unread = 0
function emit(ev, data) {
  if (ev === 'summaries') {
    unread = data.reduce((n, s) => n + s.unread, 0)
    updateTray()
  }
  send(ev, data)
}

const { startNodeHost } = require(path.join(bundle, 'core', 'boot-node.js'))
const host = startNodeHost({ dataDir, secrets, emit, platform: 'windows', lan: true, workletPath: path.join(bundle, 'worklet', 'app.js') })

ipcMain.handle('host-call', async (_e, method, args) => {
  try {
    const result = await host.call(method, args)
    return result === undefined ? null : result
  } catch (err) {
    // Re-thrown errors reach the page as "Error invoking remote method…"; keep the message clean.
    throw new Error((err && err.message) || String(err))
  }
})

let awakeId = null
ipcMain.handle('desk', async (_e, what, arg) => {
  if (what === 'openPath') {
    const err = await shell.openPath(String(arg))
    if (err) throw new Error(err)
  } else if (what === 'showInFolder') {
    shell.showItemInFolder(String(arg))
  } else if (what === 'badge') {
    if (win && !win.isDestroyed() && !win.isFocused() && Number(arg) > 0) win.flashFrame(true)
  } else if (what === 'awake') {
    // Calls: keep the display on (and the PC awake) until the call ends.
    if (arg && awakeId == null) awakeId = powerSaveBlocker.start('prevent-display-sleep')
    else if (!arg && awakeId != null) { powerSaveBlocker.stop(awakeId); awakeId = null }
  } else if (what === 'notify') {
    if (!Notification.isSupported()) return
    const n = new Notification({ title: String(arg.title || 'Meshron'), body: String(arg.body || '').slice(0, 200), icon: iconPath, silent: true })
    n.on('click', () => {
      showWindow()
      if (arg.conv) send('open-chat', { conv: arg.conv })
    })
    n.show()
  }
})

// ---- Window and tray -------------------------------------------------------------------

function createWindow() {
  win = new BrowserWindow({
    width: 1180,
    height: 780,
    minWidth: 380,
    minHeight: 560,
    backgroundColor: '#0a0a0f',
    title: 'Meshron',
    icon: iconPath,
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: true
    }
  })
  win.removeMenu()
  win.loadFile(path.join(bundle, 'ui', 'index.html'))
  win.once('ready-to-show', () => win.show())
  if (process.env.MESHRON_SNAPSHOT) {
    const shoot = async (n) => {
      const img = await win.webContents.capturePage()
      fs.writeFileSync(process.env.MESHRON_SNAPSHOT.replace('.png', `-${n}.png`), img.toPNG())
    }
    setTimeout(() => shoot(1), 6000)
    setTimeout(() => shoot(2), 25000)
  }
  win.webContents.on('did-finish-load', () => {
    if (pendingLink) {
      send('open-link', { text: pendingLink })
      pendingLink = null
    }
  })
  // Links in messages open in the real browser; the app itself never navigates away.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file://')) {
      e.preventDefault()
      if (/^https?:\/\//.test(url)) shell.openExternal(url)
    }
  })
  win.on('focus', () => win.flashFrame(false))
  win.on('close', (e) => {
    if (quitting) return
    e.preventDefault()
    win.hide()
    if (!app.__toldTray && Notification.isSupported()) {
      app.__toldTray = true
      new Notification({ title: 'Meshron is still running', body: 'You stay reachable from the tray. Right-click the tray icon to quit.', icon: iconPath, silent: true }).show()
    }
  })
}

function showWindow() {
  if (!win || win.isDestroyed()) createWindow()
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
}

function updateTray() {
  if (!tray) return
  tray.setToolTip(unread ? `Meshron: ${unread} unread` : 'Meshron')
}

function createTray() {
  const img = nativeImage.createFromPath(iconPath).resize({ width: 16, height: 16 })
  tray = new Tray(img)
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Meshron', click: showWindow },
    { type: 'separator' },
    { label: 'Quit Meshron', click: () => { quitting = true; app.quit() } }
  ]))
  tray.on('click', showWindow)
  updateTray()
}

function linkFrom(argv) {
  return (argv || []).find((a) => typeof a === 'string' && a.startsWith('meshron://')) || null
}

app.on('second-instance', (_e, argv) => {
  showWindow()
  const link = linkFrom(argv)
  if (link) send('open-link', { text: link })
})

app.whenReady().then(() => {
  if (process.env.MESHRON_TEST_NAME) {
    host.call('init').then((s) => s.identity || host.call('identity.create', { name: process.env.MESHRON_TEST_NAME, handle: '' })).catch((e) => console.error(e))
  }
  pendingLink = linkFrom(process.argv)
  createWindow()
  createTray()
})

app.on('before-quit', () => {
  quitting = true
  try { host.flush() } catch {}
  try { host.stopLan && host.stopLan() } catch {}
})
app.on('window-all-closed', () => {})
