// Desktop test peer: runs the app's real worklet (app/src/main/js/app.js) under Node.js,
// standing in for a third phone in tests. It plays the Kotlin side with simple rules:
//   - stores nothing except its seed; acks every DM / group entry it receives
//   - accepts group invites from anyone in --trust and joins the group
//   - posts "<name> joined" in each group it joins, and replies "pong from <name>" to "ping"
//   - answers calls after 2 s and sends the caller's audio and video straight back (an echo);
//     --call <hex> rings that contact instead
//
// Usage: node tools/test-peer.js --dir <folder> --name Carol [--trust <hexKey>]...
// Prints its ID on start. Not part of the app.

const path = require('path')
const fs = require('fs')
const { EventEmitter } = require('events')
const crypto = require('crypto')

const args = process.argv.slice(2)
const opt = (n) => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : null }
const trust = args.flatMap((a, i) => (a === '--trust' ? [args[i + 1]] : []))
const dir = path.resolve(opt('dir') || 'test-peer-data')
const name = opt('name') || 'Carol'
fs.mkdirSync(dir, { recursive: true })

const seedFile = path.join(dir, 'seed')
if (!fs.existsSync(seedFile)) fs.writeFileSync(seedFile, crypto.randomBytes(32).toString('hex'))
const seed = fs.readFileSync(seedFile, 'utf8').trim()

// --- Minimal Bare shim ---------------------------------------------------------
const IPC = new EventEmitter()
let outBuf = ''
IPC.write = (buf) => {
  outBuf += Buffer.from(buf).toString()
  let i
  while ((i = outBuf.indexOf('\n')) !== -1) {
    const line = outBuf.slice(0, i)
    outBuf = outBuf.slice(i + 1)
    if (line) onEvent(JSON.parse(line)).catch((e) => console.error('harness error', e))
  }
  return true
}
// Libraries read Bare.platform etc. when they see a Bare global, so report the real host.
global.Bare = { IPC, on: (ev, fn) => process.on(ev, fn), platform: process.platform, arch: process.arch, version: process.version }
require(path.join(__dirname, '..', 'app', 'src', 'main', 'js', 'app.js'))

const cmd = (o) => IPC.emit('data', Buffer.from(JSON.stringify(o) + '\n'))
const rid = () => crypto.randomBytes(16).toString('hex')
const t = () => new Date().toISOString().slice(11, 19)
let me = null
let call = null // { key, id, packets }
const dmPeers = new Set()
// Remembered across runs (the app keeps this in Room): joined groups and members' log keys.
const stateFile = path.join(dir, 'state.json')
const state = Object.assign({ groups: {}, anns: {}, carried: [] }, fs.existsSync(stateFile) ? JSON.parse(fs.readFileSync(stateFile, 'utf8')) : {})
const save = () => fs.writeFileSync(stateFile, JSON.stringify(state, null, 1))
const groups = new Map(Object.entries(state.groups))
const openGroup = (inv) =>
  cmd({ cmd: 'group-open', id: inv.id, secret: inv.secret, members: inv.members, cursors: {}, anns: state.anns[inv.id] || {} })

// Sends a 300 KB random test file (5 blocks) into a chat: 'dm:<peer>' or 'g:<id>'.
const pendingSends = new Map()
function sendTestFile(conv) {
  const xfer = rid()
  const data = crypto.randomBytes(300 * 1024)
  pendingSends.set(xfer, { conv, info: { name: 'dana-test.bin', mime: 'application/octet-stream', size: data.length, sha256: crypto.createHash('sha256').update(data).digest('hex') } })
  cmd({ cmd: 'file-begin', xfer, conv })
  for (let i = 0; i < data.length; i += 48 * 1024) cmd({ cmd: 'file-chunk', xfer, data: data.subarray(i, i + 48 * 1024).toString('base64') })
  cmd({ cmd: 'file-end', xfer })
  console.log(`[${t()}] sending test file sha256=${pendingSends.get(xfer).info.sha256}`)
}

const downloads = new Map()
function fetchFile(conv, from, info) {
  const req = rid()
  downloads.set(req, { info, chunks: [] })
  cmd({ cmd: 'file-get', req, conv, from, blobs: info.blobs, blob: info.blob })
}

async function onEvent(e) {
  switch (e.ev) {
    case 'ready': {
      me = e.publicKey
      const z32 = require('z32').encode(Buffer.from(me, 'hex'))
      console.log(`[${t()}] READY ${name} hex=${me} id=${z32}`)
      for (const k of trust) dmPeers.add(k)
      cmd({ cmd: 'trust', added: [...dmPeers], requests: [] })
      for (const k of trust) cmd({ cmd: 'connect', key: k })
      for (const inv of groups.values()) openGroup(inv)
      // --spam <hex> [--count N]: act as a stranger who writes N messages to someone (limit test).
      if (opt('spam')) {
        const target = opt('spam')
        dmPeers.add(target)
        cmd({ cmd: 'trust', added: [...dmPeers], requests: [] })
        cmd({ cmd: 'connect', key: target })
        const n = Number(opt('count') || 25)
        for (let i = 1; i <= n; i++) cmd({ cmd: 'append', key: target, id: rid(), ts: Date.now() + i, body: `spam ${i} of ${n}`, kind: 'text' })
        console.log(`[${t()}] wrote ${n} messages for ${target.slice(0, 8)}`)
      }
      break
    }
    case 'link-data': {
      const s = linkSockets.get(e.id)
      if (s) s.write(Buffer.from(e.data, 'base64'))
      break
    }
    case 'link-close': {
      const s = linkSockets.get(e.id)
      linkSockets.delete(e.id)
      if (s) s.destroy()
      break
    }
    case 'carry':
      state.carried.push({ k: e.k, t: e.t })
      save()
      console.log(`[${t()}] carrying log ${e.k.slice(0, 8)} for someone else`)
      break
    case 'peer':
      if (e.state !== 'connecting') console.log(`[${t()}] peer ${e.key.slice(0, 8)} ${e.state}${e.via ? ' via ' + e.via : ''}`)
      // A test peer reads DMs from anyone it meets (invites still need --trust).
      if (e.state === 'connected' && !dmPeers.has(e.key)) {
        dmPeers.add(e.key)
        cmd({ cmd: 'trust', added: [...dmPeers], requests: [] })
      }
      break
    case 'hello':
      console.log(`[${t()}] hello from ${e.key.slice(0, 8)}: ${e.name}`)
      break
    case 'msg':
      cmd({ cmd: 'ack', key: e.key, seq: e.seq })
      console.log(`[${t()}] DM from ${e.key.slice(0, 8)} #${e.seq} (${e.kind}): ${e.body.slice(0, 80)}`)
      if (e.kind === 'file') fetchFile('dm:' + e.key, e.key, JSON.parse(e.body))
      if (e.kind === 'text' && e.body.trim() === 'sendfile') sendTestFile('dm:' + e.key)
      if (e.kind === 'text' && e.body.trim().toLowerCase() === 'ping') {
        cmd({ cmd: 'append', key: e.key, id: rid(), ts: Date.now(), body: 'pong from ' + name, kind: 'text' })
      }
      if (e.kind === 'invite' && trust.includes(e.key)) {
        const inv = JSON.parse(e.body)
        if (groups.has(inv.id)) break
        groups.set(inv.id, inv)
        state.groups[inv.id] = inv
        save()
        openGroup(inv)
        setTimeout(() => cmd({ cmd: 'group-append', group: inv.id, id: rid(), ts: Date.now(), body: `${name} joined`, kind: 'text' }), 500)
      }
      break
    case 'gmsg':
      cmd({ cmd: 'group-ack', group: e.group, member: e.from, seq: e.seq })
      console.log(`[${t()}] GROUP ${e.group.slice(0, 6)} from ${e.from.slice(0, 8)} #${e.seq}: ${e.body.slice(0, 120)}`)
      if (e.kind === 'file') fetchFile('g:' + e.group, e.from, JSON.parse(e.body))
      if (e.kind === 'text' && e.body.trim() === 'sendfile') sendTestFile('g:' + e.group)
      if (e.kind === 'text' && e.body.trim().toLowerCase() === 'ping') {
        cmd({ cmd: 'group-append', group: e.group, id: rid(), ts: Date.now(), body: `pong from ${name}`, kind: 'text' })
      }
      break
    case 'gann':
      ;(state.anns[e.group] = state.anns[e.group] || {})[e.member] = { outbox: e.outbox, sig: e.sig }
      save()
      console.log(`[${t()}] learned log of ${e.member.slice(0, 8)} in group ${e.group.slice(0, 6)}`)
      break
    case 'gappended':
      console.log(`[${t()}] appended to group ${e.group.slice(0, 6)} #${e.seq}`)
      break
    case 'gdelivered':
      console.log(`[${t()}] group ${e.group.slice(0, 6)}: ${e.member.slice(0, 8)} has ${e.length} of my entries`)
      break
    case 'file-stored': {
      const s = pendingSends.get(e.xfer)
      pendingSends.delete(e.xfer)
      const body = JSON.stringify({ ...s.info, blobs: e.blobs, blob: e.blob })
      if (s.conv.startsWith('g:')) cmd({ cmd: 'group-append', group: s.conv.slice(2), id: rid(), ts: Date.now(), body, kind: 'file' })
      else cmd({ cmd: 'append', key: s.conv.slice(3), id: rid(), ts: Date.now(), body, kind: 'file' })
      console.log(`[${t()}] test file stored and sent`)
      break
    }
    case 'file-data':
      downloads.get(e.req).chunks.push(Buffer.from(e.data, 'base64'))
      break
    case 'file-done': {
      const d = downloads.get(e.req)
      downloads.delete(e.req)
      const buf = Buffer.concat(d.chunks)
      const ok = crypto.createHash('sha256').update(buf).digest('hex') === d.info.sha256 && buf.length === d.info.size
      console.log(`[${t()}] FILE ${ok ? 'OK' : 'CORRUPT'} ${d.info.name} ${buf.length} bytes`)
      if (ok) fs.writeFileSync(path.join(dir, 'received-' + d.info.name.replace(/[^\w.-]/g, '_')), buf)
      break
    }
    case 'file-error':
      console.log(`[${t()}] FILE ERROR ${e.msg}`)
      break
    case 'log':
      console.log(`[${t()}] log: ${e.msg}`)
      break
    case 'call-msg': {
      const m = e.msg
      if (m.t !== 'vack') console.log(`[${t()}] call ${m.t}${m.video ? ' (video)' : ''} from ${e.key.slice(0, 8)} via ${e.via}${m.reason ? ' (' + m.reason + ')' : ''}`)
      if (['video', 'vack', 'keyreq'].includes(m.t)) {
        // Echo: the caller sees their own video and gets their own acks back.
        if (call && call.id === m.id) cmd({ cmd: 'call-send', key: e.key, msg: m })
        break
      }
      if (m.t === 'offer') {
        call = { key: e.key, id: m.id, packets: 0 }
        setTimeout(() => { if (call && call.id === m.id) cmd({ cmd: 'call-send', key: e.key, msg: { t: 'answer', id: m.id } }) }, 2000)
      } else if (m.t === 'answer' && call && call.id === m.id) {
        console.log(`[${t()}] call answered`)
      } else if ((m.t === 'end' || m.t === 'reject' || m.t === 'unreachable') && call && call.id === m.id) {
        console.log(`[${t()}] call over, echoed ${call.packets} audio packets`)
        call = null
      }
      break
    }
    case 'call-video':
      if (call && call.key === e.key) {
        call.frames = (call.frames || 0) + 1
        if (call.frames % 100 === 0) console.log(`[${t()}] echoed ${call.frames} video frames`)
        cmd({ cmd: 'call-video', key: e.key, data: e.data })
      }
      break
    case 'call-audio':
      if (call && call.key === e.key) {
        call.packets++
        if (call.packets % 125 === 0) console.log(`[${t()}] echoed ${call.packets} audio packets (~${call.packets / 25} s)`)
        cmd({ cmd: 'call-audio', key: e.key, data: e.data })
      }
      break
    case 'handle-found':
      console.log(`[${t()}] @${opt('find')} found: ${e.keys.length ? e.keys.map((k) => k.slice(0, 8)).join(', ') : 'nobody'}${e.error ? ' (' + e.error + ')' : ''}`)
      break
    case 'error':
      console.log(`[${t()}] ERROR: ${e.msg}`)
      break
  }
}

// --- Local links (stand-in for the app's same-Wi-Fi transport) ---------------------------
// --listen <port> / --dial <host:port>: a plain TCP pipe; the worklet runs Noise over it.
const net = require('net')
const linkSockets = new Map()
function attachLink(socket, initiator) {
  const id = rid()
  linkSockets.set(id, socket)
  cmd({ cmd: 'link-open', id, initiator, via: 'lan' })
  socket.on('data', (d) => cmd({ cmd: 'link-data', id, data: d.toString('base64') }))
  socket.on('error', () => {})
  socket.on('close', () => {
    if (linkSockets.delete(id)) cmd({ cmd: 'link-close', id })
  })
}
function startLinks() {
  if (opt('listen')) net.createServer((s) => attachLink(s, false)).listen(Number(opt('listen')), () => console.log(`[${t()}] link server on ${opt('listen')}`))
  if (opt('dial')) {
    const [host, port] = opt('dial').includes(':') ? opt('dial').split(':') : ['127.0.0.1', opt('dial')]
    const s = net.connect(Number(port), host, () => attachLink(s, true))
    s.on('error', (err) => console.log(`[${t()}] link dial failed: ${err.message}`))
  }
}

// --to <hex> --say <text>: write a DM at start (works offline; delivered when a path appears).
const offline = args.includes('--offline')
// --relay <hex> (repeatable): pin a relay; --force-relay: always connect through one.
const relays = args.flatMap((x, i) => (x === '--relay' ? [args[i + 1]] : []))
cmd({ cmd: 'start', seed, name, handle: opt('handle') || '', storage: path.join(dir, 'corestore'), cursors: {}, internet: !offline, carry: !args.includes('--no-carry'), carried: state.carried, relays, forceRelay: args.includes('--force-relay') })
setTimeout(() => {
  startLinks()
  if (opt('to') && opt('say')) {
    if (!dmPeers.has(opt('to'))) {
      dmPeers.add(opt('to'))
      cmd({ cmd: 'trust', added: [...dmPeers], requests: [] })
    }
    cmd({ cmd: 'connect', key: opt('to') })
    cmd({ cmd: 'append', key: opt('to'), id: rid(), ts: Date.now(), body: opt('say'), kind: 'text' })
    console.log(`[${t()}] wrote "${opt('say')}" for ${opt('to').slice(0, 8)}`)
  }
  // --find <username>: look an @username up on the DHT after 10 s.
  if (opt('find')) setTimeout(() => cmd({ cmd: 'find-handle', req: 'cli', handle: opt('find') }), 10000)
  if (opt('call')) {
    const key = opt('call')
    if (!dmPeers.has(key)) {
      dmPeers.add(key)
      cmd({ cmd: 'trust', added: [...dmPeers], requests: [] })
      cmd({ cmd: 'connect', key })
    }
    const ring = setInterval(() => {
      if (call) return clearInterval(ring)
      call = { key, id: rid().slice(0, 16), packets: 0 }
      console.log(`[${t()}] ringing ${key.slice(0, 8)}`)
      cmd({ cmd: 'call-send', key, msg: { t: 'offer', id: call.id } })
    }, 10000)
  }
}, 1500)
