// Connects the screens to the shared host on each platform, behind one small API:
//
//   M.call(method, args) -> Promise      ask the host to do something
//   M.on(event, fn)                      listen to host events
//   M.platform                           'windows' | 'ios' | 'web'
//   M.native.*                           things only the platform shell can do
//
// Windows (Electron): the preload script exposes window.meshronNative.
// iPhone: Swift relays JSON between this page and the Bare worklet.
// Browser (development): HTTP + server-sent events to cross/core/dev-server.js.

;(function () {
  const listeners = new Map()
  const fire = (ev, data) => {
    for (const fn of listeners.get(ev) || []) {
      try { fn(data) } catch (err) { console.error(err) }
    }
  }
  const on = (ev, fn) => {
    if (!listeners.has(ev)) listeners.set(ev, new Set())
    listeners.get(ev).add(fn)
  }

  const downloadBlob = (blob, name) => {
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(a.href), 30000)
  }

  const copyText = async (text) => {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      const t = document.createElement('textarea')
      t.value = text
      t.style.position = 'fixed'
      t.style.opacity = '0'
      document.body.appendChild(t)
      t.select()
      let ok = false
      try { ok = document.execCommand('copy') } catch {}
      t.remove()
      return ok
    }
  }

  let M

  if (window.meshronNative) {
    // ---- Windows (Electron) ----
    const n = window.meshronNative
    n.onEvent((ev, data) => fire(ev, data))
    M = {
      platform: 'windows',
      call: (method, args) => n.invoke(method, args),
      native: {
        copy: copyText,
        share: null,
        openFile: ({ path }) => n.openPath(path),
        showFile: ({ path }) => n.showInFolder(path),
        saveFile: async ({ blob, name }) => downloadBlob(blob, name),
        badge: (count) => n.setBadge(count),
        notify: ({ title, body, conv }) => n.notify({ title, body, conv }),
        keepAwake: (on) => n.keepAwake(on),
        openWifi: () => n.openWifi(),
        // Message alerts are shown by the app itself (main.js), even while the window is hidden.
        alertsInBackground: true,
        focusState: () => document.hasFocus()
      }
    }
  } else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.meshron) {
    // ---- iPhone ----
    const post = (o) => window.webkit.messageHandlers.meshron.postMessage(JSON.stringify(o))
    const waiting = new Map()
    let next = 1
    window.__meshronReceive = (m) => {
      if (m.ev) return fire(m.ev, m.data)
      const w = waiting.get(m.id)
      if (!w) return
      waiting.delete(m.id)
      if (m.error) w.reject(new Error(m.error))
      else w.resolve(m.result)
    }
    const call = (method, args) => new Promise((resolve, reject) => {
      const id = next++
      waiting.set(id, { resolve, reject })
      post({ id, method, args: args || {} })
    })
    M = {
      platform: 'ios',
      call,
      native: {
        copy: async (text) => { await call('native.copy', { text }); return true },
        share: ({ text }) => call('native.share', { text }),
        openFile: ({ path }) => call('native.shareFile', { path }),
        showFile: null,
        saveFile: async ({ path }) => call('native.shareFile', { path }),
        badge: (count) => call('native.badge', { count }),
        keepAwake: (on) => call('native.keepAwake', { on }),
        notify: null,
        focusState: () => !document.hidden
      }
    }
  } else {
    // ---- Browser (development) ----
    const es = new EventSource('/events')
    es.onmessage = (e) => {
      const m = JSON.parse(e.data)
      fire(m.ev, m.data)
    }
    M = {
      platform: 'web',
      call: async (method, args) => {
        const res = await fetch('/call', { method: 'POST', body: JSON.stringify({ method, args: args || {} }) })
        const out = await res.json()
        if (out.error) throw new Error(out.error)
        return out.result
      },
      native: {
        copy: copyText,
        share: null,
        openFile: null,
        showFile: null,
        saveFile: async ({ blob, name }) => downloadBlob(blob, name),
        badge: () => {},
        notify: null,
        focusState: () => document.hasFocus()
      }
    }
  }

  M.on = on
  window.M = M
})()
