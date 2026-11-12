// The only bridge between the screens and the rest of the app. The page has no Node access;
// it can call host methods and a few desktop actions, and receive host events.

const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('meshronNative', {
  invoke: (method, args) => ipcRenderer.invoke('host-call', method, args || {}),
  onEvent: (fn) => ipcRenderer.on('host-event', (_e, ev, data) => fn(ev, data)),
  openPath: (path) => ipcRenderer.invoke('desk', 'openPath', path),
  showInFolder: (path) => ipcRenderer.invoke('desk', 'showInFolder', path),
  setBadge: (count) => ipcRenderer.invoke('desk', 'badge', count),
  notify: (n) => ipcRenderer.invoke('desk', 'notify', n),
  keepAwake: (on) => ipcRenderer.invoke('desk', 'awake', !!on)
})
