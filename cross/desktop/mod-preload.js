// Gives the Meshron Mod screens a secure place for the sign-in key (Windows safeStorage).
const { contextBridge, ipcRenderer } = require('electron')
contextBridge.exposeInMainWorld('ModStore', {
  get: (k) => ipcRenderer.invoke('mod-store-get', String(k)),
  set: (k, v) => ipcRenderer.invoke('mod-store-set', String(k), v == null ? null : String(v))
})
