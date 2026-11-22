// The only bridge between the screens and the rest of the app. The page has no Node access;
// it can call host methods and a few desktop actions, and receive host events.

const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('meshronNative', {
  // Electron wraps errors as "Error invoking remote method 'host-call': Error: …"; pass on only
  // the message itself, and say timeouts from peers in plain words.
  invoke: (method, args) => ipcRenderer.invoke('host-call', method, args || {}).catch((err) => {
    let msg = String((err && err.message) || err).replace(/^Error invoking remote method '[^']*': /, '').replace(/^Error: /, '')
    if (/REQUEST_TIMEOUT|timed out/i.test(msg)) msg = "Couldn't reach anyone who has this right now. Try again in a moment."
    throw new Error(msg)
  }),
  onEvent: (fn) => ipcRenderer.on('host-event', (_e, ev, data) => fn(ev, data)),
  openPath: (path) => ipcRenderer.invoke('desk', 'openPath', path),
  showInFolder: (path) => ipcRenderer.invoke('desk', 'showInFolder', path),
  setBadge: (count) => ipcRenderer.invoke('desk', 'badge', count),
  notify: (n) => ipcRenderer.invoke('desk', 'notify', n),
  keepAwake: (on) => ipcRenderer.invoke('desk', 'awake', !!on),
  openWifi: () => ipcRenderer.invoke('desk', 'wifi'),
  // Going live: list screens and apps, pick one, keep Meshron's window out of the picture.
  captureSources: () => ipcRenderer.invoke('desk', 'sources'),
  pickSource: (id, sound) => ipcRenderer.invoke('desk', 'pickSource', { id, sound }),
  hideFromCapture: (on) => ipcRenderer.invoke('desk', 'hideFromCapture', !!on)
})
