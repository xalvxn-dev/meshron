// The only bridge between Meshron Assistant's windows and the app (no Node in the pages).
const { contextBridge, ipcRenderer } = require('electron')
const call = (what, arg) => ipcRenderer.invoke('a', what, arg)
contextBridge.exposeInMainWorld('Assistant', {
  on: (fn) => ipcRenderer.on('a-event', (_e, ev, data) => fn(ev, data)),
  state: () => call('state'),
  settings: () => call('settings'),
  lastHeard: () => call('lastHeard'),
  setSettings: (p) => call('setSettings', p),
  signIn: (u, a) => call('signIn', { u, a }),
  signOut: () => call('signOut'),
  ask: (text) => call('ask', text),
  listen: () => call('listen'),
  heard: (text) => call('heard', text),
  mode: (m) => call('mode', m),
  partial: (t) => call('partial', t),
  approve: (ok) => call('approve', ok),
  pickVoice: (id, name) => call('pickVoice', { id, name }),
  stageClose: () => call('stageClose'),
  reset: () => call('reset'),
  stt: (audio, mime) => call('stt', { audio, mime }),
  tts: (text) => call('tts', text),
  openPanel: (page) => call('openPanel', page),
  testHotkey: () => call('testHotkey'),
  orbMouse: (on) => call('orbMouse', on),
  orbMove: (dx, dy) => call('orbMove', { dx, dy }),
  orbDrop: (right) => call('orbDrop', { right })
})
