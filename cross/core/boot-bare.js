/* global Bare */
// iPhone entry: runs the shared host inside the Bare Kit worklet started by the Swift app
// (cross/ios/App/Bridge.swift). Bare.argv[0] is the app's private data folder.
//
// Swift <-> here over Bare.IPC, newline-delimited JSON:
//   Swift -> JS  { id, method, args }        a call from the screens (relayed by Swift)
//                { sid, value }               answer to a secret request
//                { lifecycle: 'suspend' | 'resume' }
//   JS -> Swift  { id, result } | { id, error }
//                { ev, data }                 host event for the screens (and notifications)
//                { sid, secret: 'get' | 'set', name, value? }   Keychain access

const fs = require('bare-fs')
const path = require('bare-path')
const b4a = require('b4a')
const { createHost } = require('./host')

const IPC = Bare.IPC
const dataDir = Bare.argv[0] || '.'

const send = (o) => IPC.write(b4a.from(JSON.stringify(o) + '\n'))

// Keychain lives in Swift; ask for it and wait for the answer.
const secretWaits = new Map()
let nextSid = 1
function askSecret(o) {
  return new Promise((resolve, reject) => {
    const sid = nextSid++
    const timer = setTimeout(() => {
      secretWaits.delete(sid)
      reject(new Error('Keychain did not answer'))
    }, 10000)
    secretWaits.set(sid, (v) => { clearTimeout(timer); resolve(v) })
    send({ sid, ...o })
  })
}
const secrets = {
  get: (name) => askSecret({ secret: 'get', name }),
  set: (name, value) => askSecret({ secret: 'set', name, value })
}

const host = createHost({
  fs, path, dataDir, secrets, platform: 'ios',
  emit: (ev, data) => send({ ev, data }),
  loadWorklet: () => require('../../app/src/main/js/app.js')
})

async function onMessage(m) {
  if (m.sid != null) {
    const done = secretWaits.get(m.sid)
    if (done) {
      secretWaits.delete(m.sid)
      done(m.value == null ? null : m.value)
    }
    return
  }
  if (m.lifecycle === 'suspend') return host.suspend()
  if (m.lifecycle === 'resume') return host.resume()
  if (m.id == null) return
  try {
    const result = await host.call(m.method, m.args)
    send({ id: m.id, result: result === undefined ? null : result })
  } catch (err) {
    send({ id: m.id, error: (err && err.message) || String(err) })
  }
}

let pending = b4a.alloc(0)
IPC.on('data', (data) => {
  pending = pending.byteLength ? b4a.concat([pending, data]) : data
  let i
  while ((i = pending.indexOf(10)) !== -1) {
    const line = b4a.toString(pending.subarray(0, i))
    pending = pending.subarray(i + 1)
    if (!line) continue
    let m
    try { m = JSON.parse(line) } catch { continue }
    onMessage(m)
  }
})

Bare.on('suspend', () => host.flush())
