// On Android, game mode runs in a WebView: this gives game.js the same window.Assistant calls it
// uses on Windows, backed by the app (window.MeshronAndroid). On Windows it does nothing.
;(function () {
  'use strict'
  if (window.Assistant || !window.MeshronAndroid) return
  const N = window.MeshronAndroid
  const listeners = []
  let download = null
  window.__gameEvent = (ev, data) => {
    if (ev === 'game-downloaded' && download) { download.resolve(true); download = null }
    if (ev === 'game-download-failed' && download) { download.reject(new Error(data || 'failed')); download = null }
    for (const fn of listeners) { try { fn(ev, data) } catch {} }
  }
  window.Assistant = {
    platform: 'android',
    on: (fn) => listeners.push(fn),
    settings: async () => JSON.parse(N.settings()),
    gameModels: async () => JSON.parse(N.models()),
    gameDownload: () => new Promise((resolve, reject) => { download = { resolve, reject }; N.download() }),
    gameModel: (k) => fetch('/models/' + (k === 'face' ? 'face' : 'hand') + '.task').then((r) => r.arrayBuffer()).then((b) => new Uint8Array(b)),
    ask: async (text) => { N.ask(String(text || '')); return true },
    gameMood: async (m) => { N.mood(String(m || '')); return true },
    gameClose: async () => { N.close(); return true },
    gameCompact: async () => true,
    gameInput: async () => true,
    gameRelease: async () => true,
    gameScreen: async () => ({ w: innerWidth, h: innerHeight })
  }
})()
