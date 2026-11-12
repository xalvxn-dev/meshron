/* global Bare */
// Meshron worklet: Hyperswarm + Hypercore. Runs inside Bare Kit on the phone, started by
// NetworkService.kt.
//
// Sync model: every 1:1 chat has two append-only Hypercore logs, one written by each side
// ("outboxes"). Sending = appending a signed entry to my outbox for that peer. Whenever two
// devices are connected (in any way), Corestore replicates the logs, so anything written
// while the other side was offline arrives automatically. Logs are block-encrypted with keys
// only the participants can derive, so other phones can store and forward them blindly.
//
// Ways to connect (all end up in onconnection(), authenticated by the identity key pair):
//   - 'internet': Hyperswarm / HyperDHT (can be switched off)
//   - 'lan':      same Wi-Fi or hotspot, no internet needed (socket owned by Kotlin)
//   - 'nearby':   Bluetooth / Wi-Fi Direct via Nearby Connections (owned by Kotlin)
// For 'lan' and 'nearby' Kotlin hands us raw bytes ('link-*' commands); we run the same
// Noise handshake (NoiseSecretStream) over them as Hyperswarm does over UDP.
//
// Mesh: phones gossip "records" { core key, tag } for the logs they hold. A tag is a hash
// only the two people of a chat (or members of a group) can compute, so a carrier learns
// nothing about who talks to whom. If a record isn't for us, we carry the (encrypted) log
// for others and pass it on to whoever we meet next.
//
// Kotlin <-> JS over Bare.IPC, newline-delimited JSON:
//   Kotlin -> JS: { cmd: 'start' | 'trust' | 'connect' | 'disconnect' | 'append' | 'ack' | 'name' |
//                        'group-open' | 'group-append' | 'group-ack' | 'file-begin' | 'file-chunk' |
//                        'file-end' | 'file-get' | 'network-change' | 'suspend' | 'resume' |
//                        'internet' | 'carry' | 'link-open' | 'link-data' | 'link-close' |
//                        'call-send' | 'call-audio' | 'call-video', ... }
//   JS -> Kotlin: { ev: 'ready' | 'dht' | 'peer' | 'hello' | 'msg' | 'appended' | 'delivered' |
//                       'gmsg' | 'gappended' | 'gdelivered' | 'gann' | 'file-stored' | 'file-data' |
//                       'file-done' | 'file-error' | 'carry' | 'link-data' | 'link-close' |
//                       'call-msg' | 'call-audio' | 'call-video' | 'log' | 'error', ... }
// Peer <-> peer: Protomux channel 'meshron/dm/1' (JSON messages) alongside Hypercore replication:
//   { t: 'hello', v, name, handle, outbox }   { t: 'ack', length }   { t: 'mesh', recs: [[core, tag]] }
//   { t: 'typing' } (live only)
//   { t: 'ghello', group, anns: { member: { outbox, sig } } }   { t: 'gack', group, length }
// Voice calls: Protomux channel 'meshron/call/1' on the same stream, only with added contacts:
//   message 0 (JSON): { t: 'offer' | 'answer' | 'reject' | 'end', id, reason?, video? }
//                     { t: 'video', id, on }  camera switched on/off
//                     { t: 'vack', id, seq }  video frame received (sender's congestion control)
//                     { t: 'keyreq', id }     receiver needs a key frame
//   message 1 (bytes): one audio packet (Opus frames, made and played by Kotlin)
//   message 2 (bytes): one video frame (H.264, made and shown by Kotlin)
// Calls need a live connection (internet, same Wi-Fi or Bluetooth / Wi-Fi Direct); they are
// not stored or carried through the mesh.

const b4a = require('b4a')

// Android: Bare.IPC to Kotlin. iPhone/Windows: the shared host (cross/core/host.js) runs this
// file in-process and hands it an in-memory channel instead.
const IPC = (typeof global !== 'undefined' && global.MESHRON_IPC) || Bare.IPC
const PROTOCOL_VERSION = 3
const MAX_BODY = 4000
// story: a 24-hour post fanned out to each contact's 1:1 log; seen: a view receipt for one.
// edit {id, text} / unsend {id} change the author's own earlier message; react {id, e} sets
// (or with e = '' clears) the sender's reaction to any message. Replies are plain text that
// starts with '↩ <quote>' on its own line, so older versions still show them.
const DM_KINDS = ['text', 'invite', 'file', 'story', 'seen', 'edit', 'unsend', 'react']
const GROUP_KINDS = ['text', 'members', 'file', 'edit', 'unsend', 'react']
const MAX_FILE = 25 * 1024 * 1024
const HEX64 = /^[0-9a-f]{64}$/

// Mesh limits: records per gossip message, logs carried for others, entries per carried log.
const MESH_SEND_MAX = 300
const CARRY_MAX = 200 // other people's sealed logs this phone carries on (store-and-forward hops)
const CARRY_MAX_BLOCKS = 5000
// Urgent messages (and SOS): every phone that meets them carries them on, even with
// "pass messages" off and past the usual limit, for this long.
const URGENT_MS = 48 * 60 * 60 * 1000
const URGENT_MAX = 100

let swarm = null // only while internet is on
let store = null
let keyPair = null
let mySkCurve = null // X25519 secret derived from the identity key (for chat keys)
let myName = ''
let myHandle = '' // @username, announced on the DHT so people can find us by it
const HANDLE_RE = /^[a-z0-9_]{3,20}$/
let identitySecret = null // 32-byte seed, kept in worklet memory only
let started = false
let carryEnabled = true

const peers = new Map() // peerHex -> { conns: Set<stream>, current: stream }
const dialled = new Set() // contacts we look for on the DHT
const outboxes = new Map() // peerHex -> Promise<Hypercore> (mine, writable)
const inboxes = new Map() // peerHex -> { core, pumping }
const cursors = new Map() // peerHex -> next inbox seq to hand to Kotlin
const confirmed = new Map() // peerHex -> inbox length Kotlin has stored

// Who may write to this device, set by Kotlin with { cmd: 'trust' }: contacts you added get
// everything; pending requests at most REQUEST_LIMIT entries until accepted; anyone else's
// log isn't read at all (their hello is kept until they become a request).
const REQUEST_LIMIT = 20
let trusted = new Set()
let requested = new Set()
const pendingHellos = new Map() // peerHex -> hello from someone not (yet) allowed
const inboxLimit = (peerHex) => (trusted.has(peerHex) ? Infinity : requested.has(peerHex) ? REQUEST_LIMIT : 0)

function send(msg) {
  IPC.write(b4a.from(JSON.stringify(msg) + '\n'))
}

function describe(err) {
  let out = (err && err.stack) || String(err)
  for (let c = err && err.cause, depth = 0; c && depth < 5; c = c.cause, depth++) {
    out += '\nCaused by: ' + ((c && c.stack) || String(c))
  }
  return out
}

function report(err) {
  send({ ev: 'error', msg: describe(err) })
}

function log(msg) {
  send({ ev: 'log', msg })
}

const hex = (buf) => b4a.toString(buf, 'hex')

// An uncaught error would abort the whole app process, so report instead.
Bare.on('uncaughtException', report)
Bare.on('unhandledRejection', report)

// Loaded after the handlers above so a native addon failure is reported, not fatal.
let Hyperswarm, crypto, Corestore, Protomux, c, Hyperblobs, NoiseSecretStream, Duplex, sodium
try {
  Hyperswarm = require('hyperswarm')
  crypto = require('hypercore-crypto')
  Corestore = require('corestore')
  Protomux = require('protomux')
  c = require('compact-encoding')
  Hyperblobs = require('hyperblobs')
  NoiseSecretStream = require('@hyperswarm/secret-stream')
  Duplex = require('streamx').Duplex
  sodium = require('sodium-universal')
} catch (err) {
  report(err)
}

lineReader(IPC, (line) => {
  let cmd
  try {
    cmd = JSON.parse(line)
  } catch {
    return log('Ignored malformed command')
  }
  handle(cmd).catch(report)
})

async function handle(cmd) {
  switch (cmd.cmd) {
    case 'start':
      return start(cmd)
    case 'connect': {
      if (!keyPair) throw new Error('Not started')
      if (!HEX64.test(cmd.key)) throw new Error('Bad key')
      if (cmd.key === me()) throw new Error('That is your own ID')
      dialled.add(cmd.key)
      if (swarm) swarm.joinPeer(b4a.from(cmd.key, 'hex'))
      if (!peers.has(cmd.key)) send({ ev: 'peer', key: cmd.key, state: 'connecting' })
      return
    }
    case 'disconnect': {
      dialled.delete(cmd.key)
      if (swarm) swarm.leavePeer(b4a.from(cmd.key, 'hex'))
      const p = peers.get(cmd.key)
      if (p) for (const conn of [...p.conns]) conn.destroy()
      return
    }
    case 'trust':
      return setTrust(cmd)
    case 'internet':
      return setInternet(!!cmd.on)
    case 'carry':
      carryEnabled = !!cmd.on
      if (swarm) maybeHostRelay(swarm).catch(report)
      return
    case 'link-open':
      return linkOpen(cmd)
    case 'link-data': {
      const raw = links.get(cmd.id)
      if (raw) raw.push(b4a.from(cmd.data, 'base64'))
      return
    }
    case 'link-close': {
      const raw = links.get(cmd.id)
      if (raw) {
        links.delete(cmd.id)
        raw.destroy()
      }
      return
    }
    case 'typing':
      // Live only: 'is typing…' for a contact who's connected right now. Never stored.
      if (trusted.has(cmd.key)) sendToPeer(cmd.key, { t: 'typing' })
      return
    case 'call-send':
      return callSend(cmd)
    case 'call-audio':
      return callAudio(cmd)
    case 'call-video':
      return callVideo(cmd)
    case 'append':
      return append(cmd)
    case 'group-open':
      return groupOpen(cmd)
    case 'group-append':
      return groupAppend(cmd)
    case 'group-ack': {
      const g = groups.get(cmd.group)
      if (!g) return
      const length = Math.max(g.confirmed.get(cmd.member) || 0, Number(cmd.seq) + 1)
      g.confirmed.set(cmd.member, length)
      sendToPeer(cmd.member, { t: 'gack', group: g.id, length })
      return
    }
    case 'ack': {
      // Kotlin stored inbox entry `seq` on disk; tell the sender it is delivered.
      const length = Math.max(confirmed.get(cmd.key) || 0, Number(cmd.seq) + 1)
      confirmed.set(cmd.key, length)
      sendToPeer(cmd.key, { t: 'ack', length })
      return
    }
    case 'file-begin':
      uploads.set(cmd.xfer, { conv: cmd.conv, chunks: [], size: 0 })
      return
    case 'file-chunk': {
      const u = uploads.get(cmd.xfer)
      if (!u) return
      const chunk = b4a.from(cmd.data, 'base64')
      u.size += chunk.byteLength
      if (u.size > MAX_FILE) {
        uploads.delete(cmd.xfer)
        return send({ ev: 'file-error', xfer: cmd.xfer, msg: 'File too large' })
      }
      u.chunks.push(chunk)
      return
    }
    case 'file-end':
      return fileEnd(cmd.xfer)
    case 'file-get':
      return fileGet(cmd)
    case 'network-change':
      // Android switched networks (e.g. Wi-Fi -> mobile data): rebind sockets and re-announce.
      if (swarm && swarm.suspend && swarm.resume) {
        await swarm.suspend()
        await swarm.resume()
        log('Network changed, reconnecting')
      }
      return
    case 'handle':
      return setHandle(cmd.handle)
    case 'find-handle':
      return findHandle(cmd)
    case 'name':
      myName = String(cmd.name || '')
      for (const peer of peers.keys()) sendHello(peer).catch(report)
      return
    case 'suspend':
      if (swarm && swarm.suspend) await swarm.suspend()
      return
    case 'resume':
      if (swarm && swarm.resume) await swarm.resume()
      return
  }
}

async function start({ seed, name, handle, storage, cursors: initial, internet, carry, carried, relays: pinned, forceRelay: force }) {
  pinnedRelays = (Array.isArray(pinned) ? pinned : []).filter((k) => HEX64.test(k))
  forceRelay = !!force
  if (started) return
  if (!Hyperswarm || !sodium) throw new Error('Networking modules failed to load (see earlier error)')
  started = true
  myName = String(name || '')
  myHandle = HANDLE_RE.test(handle) ? handle : ''
  identitySecret = b4a.from(seed, 'hex')
  keyPair = crypto.keyPair(identitySecret)
  mySkCurve = b4a.alloc(32)
  sodium.crypto_sign_ed25519_sk_to_curve25519(mySkCurve, keyPair.secretKey)
  carryEnabled = carry !== false
  for (const [peer, next] of Object.entries(initial || {})) {
    cursors.set(peer, next)
    confirmed.set(peer, next)
  }

  store = new Corestore(storage)
  await store.ready()
  log('Opened local log storage')
  for (const r of Array.isArray(carried) ? carried : []) {
    if (r && HEX64.test(r.k) && HEX64.test(r.t)) await carryLog(r.k, r.t, false, (Number(r.u) || 0) > Date.now())
  }

  // Ready before going online: nearby links and local sending work without internet.
  send({ ev: 'ready', publicKey: me() })
  setInterval(sendStats, 3000)
  // Hyperswarm backs off further and further while a contact is offline, so someone who
  // comes online later could wait minutes. Re-ask regularly for contacts we can't reach.
  // Relays come and go; refresh the list and re-check whether we can relay ourselves.
  setInterval(() => {
    if (!swarm || !swarm.dht.bootstrapped) return
    findRelays(swarm).catch(report)
    maybeHostRelay(swarm).catch(report)
  }, 5 * 60 * 1000)
  setInterval(() => {
    if (!swarm) return
    for (const k of dialled) {
      if (peers.has(k)) continue
      const key = b4a.from(k, 'hex')
      swarm.leavePeer(key)
      swarm.joinPeer(key)
    }
  }, 30000)
  await setInternet(internet !== false)
}

// Internet (Hyperswarm) can be switched on and off at runtime; nearby links are unaffected.
async function setInternet(on) {
  if (on && !swarm) {
    // When a direct (hole-punched) connection fails, Hyperswarm retries through a relay.
    const s = new Hyperswarm({ keyPair, relayThrough: (force) => pickRelay(force, s) })
    // Test mode (desktop test peer only): refuse direct hole-punching so links must relay.
    if (forceRelay) {
      s.server._neverPunch = true
      s.server.holepunch = () => false
      const connect = s.dht.connect.bind(s.dht)
      s.dht.connect = (pk, o = {}) =>
        relayKeys().includes(b4a.toString(pk, 'hex'))
          ? connect(pk, o)
          : connect(pk, { ...o, localConnection: false, fastOpen: false, holepunch: () => false })
    }
    swarm = s
    s.on('connection', (conn, info) => {
      // A carrier that found the recipient through an urgent log's topic (we dialled them).
      conn.carrierLink = !!(info && info.topics && info.topics.some((t) => urgentTopics.has(hex(t))))
      if (conn.carrierLink) log('Found the recipient of an urgent message I carry; handing it over')
      onconnection(conn, 'internet')
    })
    for (const k of urgentTopics.keys()) s.join(b4a.from(k, 'hex'), { server: false, client: true })
    listenForUrgent(s)
    for (const k of dialled) s.joinPeer(b4a.from(k, 'hex'))
    for (const g of groups.values()) s.join(g.topic, { server: true, client: true })
    if (myHandle) s.join(handleTopic(myHandle), { server: true, client: false })
    log('Joining the DHT…')
    // Announce our own public key on the DHT so contacts can find us by ID.
    await s.listen()
    if (swarm !== s) return
    log('Listening on the DHT under your ID')
    const afterBoot = () => {
      if (swarm !== s) return
      log('DHT bootstrapped')
      sendStats()
      findRelays(s).catch(report)
      maybeHostRelay(s).catch(report)
    }
    if (s.dht.fullyBootstrapped) s.dht.fullyBootstrapped().then(afterBoot, report)
    else s.dht.ready().then(afterBoot, report)
  } else if (!on && swarm) {
    const s = swarm
    swarm = null
    await stopHostingRelay()
    await s.destroy()
    log('Internet off: nearby connections only')
  }
  sendStats()
}

// ---- Relays -----------------------------------------------------------------
//
// Two phones behind strict NATs (typical on mobile data) often can't hole-punch a direct
// connection. Hyperswarm then goes through a blind relay: the relay pairs two raw UDX
// streams and forwards bytes. The Noise handshake still runs end to end between the two
// phones, so a relay sees only encrypted traffic and IP addresses.
//
// Relays announce themselves on RELAY_TOPIC. That covers both kinds:
//   - phones whose connection is open (not firewalled) and that have "Pass messages on"
//     switched on relay for others (maybeHostRelay below);
//   - an always-on relay someone runs with tools/relay-node.js.
// Keys passed in `relays` at start are pinned and always tried.

let BlindRelay = null
let relayTopic = null // BLAKE2b('meshron/relay/v1'), made once crypto is loaded
const foundRelays = new Map() // hex -> last seen (ms)
let pinnedRelays = []
let forceRelay = false
let relayHost = null // { server, relay, keyPair, timer, sessions }
const MAX_RELAY_SESSIONS = 16

function relayKeys() {
  const own = relayHost ? b4a.toString(relayHost.keyPair.publicKey, 'hex') : null
  const keys = new Set(pinnedRelays)
  for (const k of foundRelays.keys()) keys.add(k)
  if (own) keys.delete(own)
  return [...keys]
}

// Called by Hyperswarm for each connection attempt. `force` is set after a direct attempt
// failed in a way a relay can fix; behind a randomizing NAT we relay straight away.
function pickRelay(force, s) {
  if (!force && !forceRelay && !(s && s.dht.randomized)) return null
  const keys = relayKeys()
  if (keys.length === 0) return null
  return b4a.from(keys[Math.floor(Math.random() * keys.length)], 'hex')
}

function getRelayTopic() {
  if (!relayTopic) relayTopic = crypto.hash(b4a.from('meshron/relay/v1'))
  return relayTopic
}

async function findRelays(s) {
  if (swarm !== s) return
  const now = Date.now()
  const before = relayKeys().length
  for await (const data of s.dht.lookup(getRelayTopic())) {
    for (const p of data.peers || []) foundRelays.set(b4a.toString(p.publicKey, 'hex'), now)
  }
  for (const [k, t] of foundRelays) if (now - t > 60 * 60 * 1000) foundRelays.delete(k)
  // First relays found: retry contacts we couldn't reach directly, now with a relay to try.
  if (before === 0 && relayKeys().length > 0 && swarm === s) {
    log('Found ' + relayKeys().length + ' relay(s) for hard-to-reach contacts')
    for (const k of dialled) {
      if (peers.has(k)) continue
      const key = b4a.from(k, 'hex')
      s.leavePeer(key)
      s.joinPeer(key)
    }
  }
  sendStats()
}

async function maybeHostRelay(s) {
  const want = carryEnabled && swarm === s && !!s.dht.bootstrapped && !s.dht.firewalled
  if (want && !relayHost) {
    if (!BlindRelay) BlindRelay = require('blind-relay')
    const relay = new BlindRelay.Server({
      createStream: (opts) => s.dht.createRawStream({ ...opts, framed: true })
    })
    const host = { relay, server: null, keyPair: crypto.keyPair(), timer: null, sessions: 0 }
    relayHost = host
    host.server = s.dht.createServer({ firewall: () => host.sessions >= MAX_RELAY_SESSIONS }, (socket) => {
      host.sessions++
      socket.on('error', () => {})
      socket.on('close', () => { host.sessions-- })
      const session = relay.accept(socket, { id: socket.remotePublicKey })
      session.on('error', () => {})
    })
    await host.server.listen(host.keyPair)
    const announce = () => s.dht.announce(getRelayTopic(), host.keyPair).finished().catch(() => {})
    announce()
    host.timer = setInterval(announce, 10 * 60 * 1000)
    log('Your connection is open: relaying encrypted traffic for others')
    sendStats()
  } else if (!want && relayHost) {
    await stopHostingRelay()
  }
}

async function stopHostingRelay() {
  const host = relayHost
  if (!host) return
  relayHost = null
  clearInterval(host.timer)
  try { await host.server.close() } catch {}
  try { host.relay.close() } catch {}
  log('Stopped relaying for others')
}

function sendStats() {
  if (!swarm) {
    return send({ ev: 'dht', internet: false, bootstrapped: false, connections: peers.size })
  }
  const dht = swarm.dht
  send({
    ev: 'dht',
    internet: true,
    bootstrapped: !!dht.bootstrapped,
    host: dht.host || null,
    port: dht.port || 0,
    firewalled: !!dht.firewalled,
    randomized: !!dht.randomized,
    relays: relayKeys().length,
    relaying: !!relayHost,
    connections: peers.size,
    connecting: swarm.connecting
  })
}

// ---- Chat keys --------------------------------------------------------------
//
// Both people of a 1:1 chat can compute the same X25519 shared secret from their identity
// keys (libsodium: Ed25519 -> X25519, then scalar multiplication). Log encryption keys and
// mesh tags are BLAKE2b hashes of it, so they work even if the two never met online.

const shared = new Map() // peerHex -> 32-byte shared secret

function sharedWith(peerHex) {
  let s = shared.get(peerHex)
  if (!s) {
    const pk = b4a.alloc(32)
    sodium.crypto_sign_ed25519_pk_to_curve25519(pk, b4a.from(peerHex, 'hex'))
    s = b4a.alloc(32)
    sodium.crypto_scalarmult(s, mySkCurve, pk)
    shared.set(peerHex, s)
  }
  return s
}

// Encryption key of the log written by `fromHex` for `toHex` (one of them is me).
function dmEncKey(fromHex, toHex) {
  const other = fromHex === me() ? toHex : fromHex
  return crypto.hash([b4a.from('meshron/dm-enc/v2'), sharedWith(other), b4a.from(fromHex, 'hex')])
}

function dmTag(fromHex, toHex) {
  const other = fromHex === me() ? toHex : fromHex
  return crypto.hash([b4a.from('meshron/mesh-dm/v1'), sharedWith(other), b4a.from(fromHex, 'hex')])
}

function groupTag(g, memberHex) {
  return crypto.hash([b4a.from('meshron/mesh-g/v1'), g.secret, b4a.from(memberHex, 'hex')])
}

// ---- Logs -------------------------------------------------------------------

function outbox(peerHex) {
  let p = outboxes.get(peerHex)
  if (!p) {
    const core = store.get({ name: 'dm2/' + peerHex, encryption: { key: dmEncKey(me(), peerHex) } })
    p = core.ready().then(() => {
      addRecord(core.key, dmTag(me(), peerHex), true)
      return core
    })
    outboxes.set(peerHex, p)
  }
  return p
}

async function append({ key: peerHex, id, ts, body, kind, urgent }) {
  body = String(body).slice(0, MAX_BODY)
  ts = Number(ts) || Date.now()
  kind = DM_KINDS.includes(kind) ? kind : 'text'
  const sig = crypto.sign(signable(id, ts, me(), peerHex, body, kind), keyPair.secretKey)
  const core = await outbox(peerHex)
  const entry = { v: 1, id, ts, body, sig: hex(sig) }
  if (kind !== 'text') entry.kind = kind
  const { length } = await core.append(b4a.from(JSON.stringify(entry)))
  send({ ev: 'appended', key: peerHex, id, seq: length - 1 })
  if (urgent) markUrgent(hex(core.key))
}

// Urgent: gossip this log with a flag so every phone that meets it carries it on.
function markUrgent(k) {
  const r = records.get(k)
  if (!r) return
  r.urgentUntil = Date.now() + URGENT_MS
  meshChanged()
}
const isUrgent = (r) => (r.urgentUntil || 0) > Date.now()

// ---- Files ------------------------------------------------------------------
//
// Each chat has a "files" Hypercore per sender, stored with Hyperblobs in 64 KB blocks and
// block-encrypted with the same key as that sender's chat log. A file message (signed like
// any other) carries the blob log key and the blob's position, so only chat members can
// find and decrypt it, and any member that already has the blocks can serve them.

const uploads = new Map() // xfer -> { conv, chunks, size }

function convEncKey(conv, senderHex) {
  if (conv.startsWith('g:')) {
    const g = groups.get(conv.slice(2))
    if (!g) throw new Error('Unknown group')
    return g.enc
  }
  const peer = conv.slice(3) // 'dm:<peer>'
  return senderHex === me() ? dmEncKey(me(), peer) : dmEncKey(peer, me())
}

async function fileEnd(xfer) {
  const u = uploads.get(xfer)
  uploads.delete(xfer)
  if (!u) return
  try {
    const name = u.conv.startsWith('g:') ? 'files/group/' + u.conv.slice(2) : 'files2/dm/' + u.conv.slice(3)
    const core = store.get({ name, encryption: { key: convEncKey(u.conv, me()) } })
    await core.ready()
    const blobs = new Hyperblobs(core)
    const id = await blobs.put(b4a.concat(u.chunks))
    send({ ev: 'file-stored', xfer, blobs: hex(core.key), blob: id })
  } catch (err) {
    send({ ev: 'file-error', xfer, msg: err.message })
  }
}

async function fileGet({ req, conv, from, blobs: keyHex, blob }) {
  try {
    if (!HEX64.test(keyHex) || !blob || !Number.isInteger(blob.blockLength)) throw new Error('Bad file reference')
    if (blob.byteLength > MAX_FILE) throw new Error('File too large')
    const core = store.get({ key: b4a.from(keyHex, 'hex'), encryption: { key: convEncKey(conv, from) } })
    await core.ready()
    for (let i = 0; i < blob.blockLength; i++) {
      // Waits until a peer that has the block is connected (up to 60 s per block).
      const block = await core.get(blob.blockOffset + i, { timeout: 60000 })
      send({ ev: 'file-data', req, data: b4a.toString(block, 'base64') })
    }
    send({ ev: 'file-done', req })
  } catch (err) {
    send({ ev: 'file-error', req, msg: err.message || String(err) })
  }
}

async function openInbox(peerHex, keyHex) {
  const existing = inboxes.get(peerHex)
  if (existing && hex(existing.core.key) === keyHex) return existing
  if (existing) await existing.core.close()

  const core = store.get({ key: b4a.from(keyHex, 'hex'), encryption: { key: dmEncKey(peerHex, me()) } })
  await core.ready()
  const inbox = { core, pumping: false }
  inboxes.set(peerHex, inbox)
  addRecord(core.key, dmTag(peerHex, me()), false) // known, so we don't carry it as a stranger's
  core.on('append', () => pump(peerHex, inbox).catch(report))
  core.update({ wait: true }).then(() => pump(peerHex, inbox), report)
  pump(peerHex, inbox).catch(report)
  return inbox
}

async function setTrust({ added, requests }) {
  const set = (list) => new Set((Array.isArray(list) ? list : []).filter((k) => HEX64.test(k) && k !== me()))
  trusted = set(added)
  requested = set(requests)
  recomputeTags()
  // My outbox for every contact, so it can be offered to carriers even before we meet.
  for (const peer of trusted) outbox(peer).catch(report)
  for (const [peer, hello] of pendingHellos) {
    if (inboxLimit(peer) > 0) {
      pendingHellos.delete(peer)
      await onHello(peer, hello)
    }
  }
  // Someone accepted may have more entries waiting beyond the request limit.
  for (const [peer, inbox] of inboxes) pump(peer, inbox).catch(report)
}

// Hands every new inbox entry to Kotlin, in order. Kotlin acks after storing it.
async function pump(peerHex, inbox) {
  if (inbox.pumping) return
  inbox.pumping = true
  try {
    const peerKey = b4a.from(peerHex, 'hex')
    let seq = cursors.get(peerHex) || 0
    while (seq < Math.min(inbox.core.length, inboxLimit(peerHex))) {
      const block = await inbox.core.get(seq, { timeout: 30000 })
      const entry = parseEntry(block, DM_KINDS)
      if (!entry) {
        log('Skipped malformed log entry ' + seq + ' from ' + peerHex.slice(0, 8))
      } else if (
        !crypto.verify(signable(entry.id, entry.ts, peerHex, me(), entry.body, entry.kind), b4a.from(entry.sig, 'hex'), peerKey)
      ) {
        log('Skipped entry ' + seq + ' with bad signature from ' + peerHex.slice(0, 8))
      } else {
        send({ ev: 'msg', key: peerHex, seq, id: entry.id, ts: entry.ts, body: entry.body, kind: entry.kind, sig: entry.sig })
      }
      seq++
      cursors.set(peerHex, seq)
    }
  } finally {
    inbox.pumping = false
  }
}

function parseEntry(block, kinds) {
  let e
  try {
    e = JSON.parse(b4a.toString(block))
  } catch {
    return null
  }
  const ok =
    e && typeof e.id === 'string' && /^[0-9a-f]{32}$/.test(e.id) &&
    typeof e.body === 'string' && e.body.length <= MAX_BODY &&
    Number.isFinite(e.ts) &&
    typeof e.sig === 'string' && /^[0-9a-f]{128}$/.test(e.sig)
  if (!ok) return null
  e.kind = e.kind === undefined ? 'text' : e.kind
  return kinds.includes(e.kind) ? e : null
}

// ---- Mesh -------------------------------------------------------------------

const records = new Map() // coreHex -> { k, t, gossip, carried }
const myTags = new Map() // tagHex -> { dm: peerHex } | { group: id, member: hex }
let carriedCount = 0
let meshTimer = null

function recomputeTags() {
  myTags.clear()
  for (const peer of trusted) {
    try {
      myTags.set(hex(dmTag(peer, me())), { dm: peer })
    } catch {} // not a valid curve point: can't be a real ID
  }
  for (const g of groups.values()) {
    for (const m of g.members) if (m !== me()) myTags.set(hex(groupTag(g, m)), { group: g.id, member: m })
  }
  if (swarm) listenForUrgent(swarm)
}

// Urgent hand-over over the internet: a stranger carrying a contact's urgent message for me
// looks up a topic made from that chat's tag (only the two of us and carriers know the tag,
// and carriers can't tell who it belongs to). I listen on one topic per contact.
function urgentTopic(tagHex) {
  return crypto.hash([b4a.from('meshron/urgent/v1'), b4a.from(tagHex, 'hex')])
}
const urgentListening = new Set()
function listenForUrgent(s) {
  if (s.__urgent !== urgentListening) { urgentListening.clear(); s.__urgent = urgentListening }
  for (const [tag, v] of myTags) {
    if (!v.dm || urgentListening.has(tag)) continue
    urgentListening.add(tag)
    s.join(urgentTopic(tag), { server: true, client: false })
  }
}

// A log I hold as part of my own chats. `gossip`: offer it to others (my outboxes, group logs).
function addRecord(coreKey, tag, gossip) {
  const k = hex(coreKey)
  const r = records.get(k)
  if (r && !r.carried) return
  if (r && r.carried) carriedCount--
  records.set(k, { k, t: hex(tag), gossip, carried: false })
  if (gossip) meshChanged()
}

function meshChanged() {
  if (meshTimer) return
  meshTimer = setTimeout(() => {
    meshTimer = null
    for (const p of peers.values()) if (p.current) sendMesh(p.current)
  }, 500)
}

function sendMesh(conn) {
  if (!conn.chan || conn.chan.channel.closed) return
  const recs = []
  // Urgent logs first (mine or carried), then my own, then the ones I carry for others.
  for (const r of records.values()) {
    if (recs.length >= MESH_SEND_MAX) break
    if ((r.gossip || r.carried) && isUrgent(r)) recs.push([r.k, r.t, 1])
  }
  for (const pass of [false, true]) {
    for (const r of records.values()) {
      if (recs.length >= MESH_SEND_MAX) break
      if ((r.gossip || r.carried) && r.carried === pass && !isUrgent(r)) recs.push([r.k, r.t])
    }
  }
  if (recs.length) conn.chan.message.send({ t: 'mesh', recs })
}

async function onMesh(recs) {
  if (!Array.isArray(recs)) return
  for (const r of recs.slice(0, MESH_SEND_MAX)) {
    if (!Array.isArray(r) || !HEX64.test(r[0]) || !HEX64.test(r[1])) continue
    const [k, t] = r
    const urgent = r[2] === 1
    if (records.has(k)) {
      // Already known: an urgent flag still spreads, so it keeps hopping.
      const known = records.get(k)
      if (urgent && known.carried && !isUrgent(known)) {
        known.urgentUntil = Date.now() + URGENT_MS
        log('Carrying an urgent message for someone else')
        seekRecipient(t, URGENT_MS)
        send({ ev: 'carry', k, t, u: known.urgentUntil })
        meshChanged()
      }
      continue
    }
    const mine = myTags.get(t)
    if (mine && mine.dm) {
      // A contact's log for me, brought by whoever we're connected to.
      if (inboxLimit(mine.dm) > 0) await openInbox(mine.dm, k)
    } else if (mine && mine.group) {
      const g = groups.get(mine.group)
      if (g && !g.logs.has(mine.member)) {
        await openMemberLog(g, mine.member, k)
        send({ ev: 'gann', group: g.id, member: mine.member, outbox: k, sig: '' })
      }
    } else if (urgent) {
      // Someone's urgent message: carry it even if "pass messages" is off.
      await carryLog(k, t, true, true)
    } else if (carryEnabled) {
      await carryLog(k, t, true)
    }
  }
}

// With internet, look for the recipient of an urgent log right away instead of waiting to
// meet them. The recipient listens on a topic made from the chat's private tag (urgentTopic).
let urgentCount = 0
const urgentTopics = new Map() // topic hex -> until (urgent logs I carry)
function seekRecipient(t, ms) {
  const topicBuf = urgentTopic(t)
  const topic = hex(topicBuf)
  if (urgentTopics.has(topic)) return
  urgentTopics.set(topic, Date.now() + ms)
  if (swarm) swarm.join(topicBuf, { server: false, client: true })
  const timer = setTimeout(() => {
    urgentTopics.delete(topic)
    if (swarm) swarm.leave(topicBuf).catch(() => {})
  }, ms)
  if (timer.unref) timer.unref()
}

// Stores someone else's (encrypted) log so it can be handed on. We can't read it.
async function carryLog(k, t, persist, urgent = false) {
  if (records.has(k)) return
  if (urgent ? urgentCount >= URGENT_MAX : carriedCount >= CARRY_MAX) return
  records.set(k, { k, t, gossip: false, carried: true, urgentUntil: urgent ? Date.now() + URGENT_MS : 0 })
  if (urgent) urgentCount++
  else carriedCount++
  const core = store.get({ key: b4a.from(k, 'hex') })
  await core.ready()
  if (urgent) {
    log('Carrying an urgent message for someone else')
    seekRecipient(t, URGENT_MS)
  }
  const fetch = () => {
    const end = Math.min(core.length, CARRY_MAX_BLOCKS)
    if (end > 0) core.download({ start: 0, end })
  }
  core.on('append', fetch)
  core.update({ wait: true }).then(fetch, () => {})
  fetch()
  if (persist) send({ ev: 'carry', k, t, u: urgent ? Date.now() + URGENT_MS : 0 })
  meshChanged()
}

// ---- Connections ------------------------------------------------------------

const links = new Map() // id -> raw Duplex fed by Kotlin

// A byte pipe owned by Kotlin (TCP on the same Wi-Fi, or Nearby Connections).
function linkOpen({ id, initiator, via }) {
  if (!keyPair || typeof id !== 'string' || links.has(id)) return
  const raw = new Duplex({
    write(data, cb) {
      send({ ev: 'link-data', id, data: b4a.toString(data, 'base64') })
      cb(null)
    }
  })
  links.set(id, raw)
  const conn = new NoiseSecretStream(!!initiator, raw, { keyPair })
  conn.on('error', () => {})
  conn.on('close', () => {
    if (links.delete(id)) send({ ev: 'link-close', id })
  })
  conn.opened.then((ok) => {
    if (!ok) return
    if (b4a.equals(conn.remotePublicKey, keyPair.publicKey)) return conn.destroy()
    onconnection(conn, via === 'nearby' ? 'nearby' : 'lan')
  }, () => {})
}

function onconnection(conn, via) {
  const key = hex(conn.remotePublicKey)
  let p = peers.get(key)
  if (!p) peers.set(key, (p = { conns: new Set(), current: null }))
  p.conns.add(conn)
  p.current = conn
  conn.via = via
  // A stranger over the internet: wait for their hello to learn whether they're a carrier
  // (then they're not shown as a contact request).
  const known = trusted.has(key) || requested.has(key)
  if (via !== 'internet' || known || conn.carrierLink) announcePeer(key, conn)
  else conn.pendingAnnounce = setTimeout(() => { conn.pendingAnnounce = null; announcePeer(key, conn) }, 4000)

  conn.on('error', () => {})
  conn.on('close', () => {
    if (conn.pendingAnnounce) clearTimeout(conn.pendingAnnounce)
    p.conns.delete(conn)
    if (p.current !== conn) return
    // Still connected another way (e.g. internet and Bluetooth at once)?
    const next = [...p.conns].pop()
    p.current = next || null
    if (next) return announcePeer(key, next)
    peers.delete(key)
    pendingHellos.delete(key)
    send({ ev: 'peer', key, state: 'disconnected' })
  })

  // Hypercore replication and our own channel share one Protomux on the stream.
  store.replicate(conn)
  const mux = Protomux.from(conn)
  const channel = mux.createChannel({
    protocol: 'meshron/dm/1',
    onopen() {
      sendHello(key).catch(report)
      for (const g of groups.values()) if (g.members.has(key)) sendGroupHello(key, g)
      sendMesh(conn)
    }
  })
  if (!channel) return // duplicate channel on this stream
  const message = channel.addMessage({
    encoding: c.json,
    onmessage(msg) {
      onpeermessage(key, msg).catch(report)
    }
  })
  conn.chan = { channel, message }
  channel.open()

  const callChannel = mux.createChannel({
    protocol: 'meshron/call/1',
    onopen() {
      conn.call.open = true
    },
    onclose() {
      conn.call.open = false
    }
  })
  if (!callChannel) return
  const ctl = callChannel.addMessage({
    encoding: c.json,
    onmessage(msg) {
      onCallMessage(key, conn, msg)
    }
  })
  const audio = callChannel.addMessage({
    encoding: c.buffer,
    onmessage(buf) {
      if (!trusted.has(key) || !buf || buf.byteLength > MAX_AUDIO) return
      send({ ev: 'call-audio', key, data: b4a.toString(buf, 'base64') })
    }
  })
  const video = callChannel.addMessage({
    encoding: c.buffer,
    onmessage(buf) {
      if (!trusted.has(key) || !buf || buf.byteLength > MAX_VIDEO) return
      send({ ev: 'call-video', key, data: b4a.toString(buf, 'base64') })
    }
  })
  conn.call = { channel: callChannel, ctl, audio, video, open: false }
  callChannel.open()
}

// ---- Voice calls ------------------------------------------------------------

const MAX_AUDIO = 1500
const MAX_VIDEO = 256 * 1024
const CALL_TYPES = ['offer', 'answer', 'reject', 'end', 'video', 'vack', 'keyreq']

// Only known fields, with checked types, pass between Kotlin and the peer.
function cleanCallMsg(msg) {
  const out = { t: msg.t, id: String(msg.id).slice(0, 64) }
  if (typeof msg.reason === 'string') out.reason = msg.reason.slice(0, 32)
  if (typeof msg.video === 'boolean') out.video = msg.video
  if (typeof msg.on === 'boolean') out.on = msg.on
  if (Number.isInteger(msg.seq) && msg.seq >= 0) out.seq = msg.seq
  return out
}

// The best connection to a peer that has the call channel open (newest first).
function callConn(peerHex) {
  const p = peers.get(peerHex)
  if (!p) return null
  if (p.current && p.current.call && p.current.call.open) return p.current
  return [...p.conns].reverse().find((s) => s.call && s.call.open) || null
}

function onCallMessage(peerHex, conn, msg) {
  if (!trusted.has(peerHex)) return // only added contacts can ring this phone
  if (!msg || !CALL_TYPES.includes(msg.t) || typeof msg.id !== 'string' || msg.id.length > 64) return
  send({ ev: 'call-msg', key: peerHex, msg: cleanCallMsg(msg), via: conn.via })
}

function callSend({ key, msg }) {
  if (!trusted.has(key) || !msg || !CALL_TYPES.includes(msg.t)) return
  const conn = callConn(key)
  if (!conn) {
    send({ ev: 'call-msg', key, msg: { t: 'unreachable', id: msg.id }, via: null })
    return
  }
  conn.call.ctl.send(cleanCallMsg(msg))
}

function callAudio({ key, data }) {
  const conn = callConn(key)
  if (!conn || typeof data !== 'string') return
  const buf = b4a.from(data, 'base64')
  if (buf.byteLength <= MAX_AUDIO) conn.call.audio.send(buf)
}

function callVideo({ key, data }) {
  const conn = callConn(key)
  if (!conn || !conn.call.video || typeof data !== 'string') return
  const buf = b4a.from(data, 'base64')
  if (buf.byteLength <= MAX_VIDEO) conn.call.video.send(buf)
}

function announcePeer(key, conn, carrier = false) {
  const raw = conn.rawStream
  const internet = conn.via === 'internet'
  send({
    ev: 'peer',
    key,
    state: 'connected',
    via: conn.via,
    host: (internet && raw && raw.remoteHost) || null,
    port: (internet && raw && raw.remotePort) || 0,
    initiator: !!conn.isInitiator,
    carrier: carrier || !!conn.carrierLink,
    // Internet links are direct (hole-punched) or go through a blind relay; from here the
    // two look the same, so the app just says "internet".
    relayed: false
  })
}

function sendToPeer(peerHex, msg) {
  const p = peers.get(peerHex)
  const ch = p && p.current && p.current.chan
  if (ch && !ch.channel.closed) ch.message.send(msg)
}

// ---- Groups -----------------------------------------------------------------
//
// A group is a shared 32-byte secret plus a member list (set by its creator). Every member
// writes their own Hypercore log for the group, encrypted with a key derived from the
// secret. Members meet on a Hyperswarm topic derived from the secret, and gossip signed
// "announcements" (member -> log key) so each member can fetch every other member's log,
// also from third parties who already have a copy.

const groups = new Map() // groupId -> group state

function groupAnnSignable(groupId, outboxHex) {
  return b4a.from(JSON.stringify(['meshron-gout-v1', groupId, outboxHex]))
}

function groupSignable(groupId, id, ts, fromHex, body, kind) {
  return b4a.from(JSON.stringify(['meshron-gmsg-v1', groupId, id, ts, fromHex, body, kind]))
}

async function groupOpen({ id, secret, members, cursors: initial, anns: knownAnns }) {
  if (!/^[0-9a-f]{32}$/.test(id) || !HEX64.test(secret)) throw new Error('Bad group')
  let g = groups.get(id)
  if (!g) {
    const s = b4a.from(secret, 'hex')
    g = {
      id,
      secret: s,
      enc: crypto.hash([b4a.from('meshron/group-enc/v1'), s]),
      topic: crypto.hash([b4a.from('meshron/group-topic/v1'), s]),
      members: new Set(),
      anns: new Map(), // memberHex -> { outbox, sig }
      logs: new Map(), // memberHex -> { core, pumping }
      cursors: new Map(),
      confirmed: new Map(),
      outbox: null
    }
    groups.set(id, g)
    // Members must be known before any await: hellos can arrive while the log opens.
    g.members = new Set((members || []).filter((m) => HEX64.test(m)))
    g.members.add(me())
    recomputeTags()
    for (const [m, next] of Object.entries(initial || {})) {
      g.cursors.set(m, next)
      g.confirmed.set(m, next)
    }
    const core = store.get({ name: 'group/' + id, encryption: { key: g.enc } })
    await core.ready()
    g.outbox = core
    const outboxHex = hex(core.key)
    const sig = crypto.sign(groupAnnSignable(id, outboxHex), keyPair.secretKey)
    g.anns.set(me(), { outbox: outboxHex, sig: hex(sig) })
    addRecord(core.key, groupTag(g, me()), true)
    // Members find each other here, even if they are not each other's contacts.
    if (swarm) swarm.join(g.topic, { server: true, client: true })
    log('Joined group ' + id.slice(0, 8))
    // Logs of other members learned in earlier sessions (stored by Kotlin), so we can
    // fetch their entries from anyone who has a copy, even while they are offline.
    await learnAnns(g, knownAnns || {}, false, true)
  }
  g.members = new Set((members || []).filter((m) => HEX64.test(m)))
  g.members.add(me())
  recomputeTags()
  for (const peer of peers.keys()) if (g.members.has(peer)) sendGroupHello(peer, g)
}

async function groupAppend({ group, id, ts, body, kind }) {
  const g = groups.get(group)
  if (!g) throw new Error('Unknown group ' + group)
  body = String(body).slice(0, MAX_BODY)
  ts = Number(ts) || Date.now()
  kind = GROUP_KINDS.includes(kind) ? kind : 'text'
  const sig = crypto.sign(groupSignable(g.id, id, ts, me(), body, kind), keyPair.secretKey)
  const entry = { v: 1, id, ts, body, kind, sig: hex(sig) }
  const { length } = await g.outbox.append(b4a.from(JSON.stringify(entry)))
  send({ ev: 'gappended', group: g.id, id, seq: length - 1 })
}

function sendGroupHello(peerHex, g) {
  sendToPeer(peerHex, { t: 'ghello', group: g.id, anns: Object.fromEntries(g.anns) })
  const length = g.confirmed.get(peerHex) || 0
  if (length > 0) sendToPeer(peerHex, { t: 'gack', group: g.id, length })
}

async function onGroupHello(peerHex, msg) {
  const g = groups.get(msg.group)
  if (!g || !g.members.has(peerHex) || !msg.anns || typeof msg.anns !== 'object') return
  // If they're missing logs we know about (e.g. they joined after our last hello), tell them.
  if ([...g.anns.keys()].some((m) => m !== peerHex && !(m in msg.anns))) sendGroupHello(peerHex, g)
  const learned = await learnAnns(g, msg.anns, true, false)
  // Pass newly learned logs on to the other members we're connected to.
  if (learned) {
    for (const peer of peers.keys()) {
      if (peer !== peerHex && g.members.has(peer)) sendGroupHello(peer, g)
    }
  }
}

// Verifies and opens other members' logs. Returns true if any were new. `local`: from this
// device's own storage, where logs learned through the mesh are kept without a signature.
async function learnAnns(g, anns, persist, local) {
  let learned = false
  for (const [member, ann] of Object.entries(anns)) {
    if (member === me() || !g.members.has(member) || !ann || !HEX64.test(ann.outbox)) continue
    if (local && ann.sig === '') {
      if (!g.logs.has(member)) await openMemberLog(g, member, ann.outbox)
      continue
    }
    if (!/^[0-9a-f]{128}$/.test(ann.sig)) continue
    if (g.anns.has(member)) continue // keep the first valid log for a member
    // Only the member themself can vouch for their log key, even when relayed by others.
    const ok = crypto.verify(groupAnnSignable(g.id, ann.outbox), b4a.from(ann.sig, 'hex'), b4a.from(member, 'hex'))
    if (!ok) continue
    g.anns.set(member, { outbox: ann.outbox, sig: ann.sig })
    if (persist) send({ ev: 'gann', group: g.id, member, outbox: ann.outbox, sig: ann.sig })
    const current = g.logs.get(member)
    if (!current || hex(current.core.key) !== ann.outbox) await openMemberLog(g, member, ann.outbox)
    learned = true
  }
  return learned
}

async function openMemberLog(g, member, outboxHex) {
  const core = store.get({ key: b4a.from(outboxHex, 'hex'), encryption: { key: g.enc } })
  await core.ready()
  const entry = { core, pumping: false }
  g.logs.set(member, entry)
  addRecord(core.key, groupTag(g, member), true)
  core.on('append', () => groupPump(g, member, entry).catch(report))
  core.update({ wait: true }).then(() => groupPump(g, member, entry), report)
  groupPump(g, member, entry).catch(report)
}

async function groupPump(g, member, entry) {
  if (entry.pumping) return
  entry.pumping = true
  try {
    const memberKey = b4a.from(member, 'hex')
    let seq = g.cursors.get(member) || 0
    while (seq < entry.core.length) {
      const block = await entry.core.get(seq, { timeout: 30000 })
      const e = parseEntry(block, GROUP_KINDS)
      if (!e) {
        log('Skipped malformed group entry ' + seq + ' from ' + member.slice(0, 8))
      } else if (
        !crypto.verify(groupSignable(g.id, e.id, e.ts, member, e.body, e.kind), b4a.from(e.sig, 'hex'), memberKey)
      ) {
        log('Skipped group entry ' + seq + ' with bad signature from ' + member.slice(0, 8))
      } else {
        send({ ev: 'gmsg', group: g.id, from: member, seq, id: e.id, ts: e.ts, body: e.body, kind: e.kind, sig: e.sig })
      }
      seq++
      g.cursors.set(member, seq)
    }
  } finally {
    entry.pumping = false
  }
}

async function sendHello(peerHex) {
  const core = await outbox(peerHex)
  const p = peers.get(peerHex)
  const carrier = !!(p && p.current && p.current.carrierLink)
  sendToPeer(peerHex, { t: 'hello', v: PROTOCOL_VERSION, name: myName, handle: myHandle, outbox: hex(core.key), carrier })
}

async function onpeermessage(peerHex, msg) {
  if (!msg || typeof msg !== 'object') return
  if (msg.t === 'hello') {
    const p = peers.get(peerHex)
    const c = p && p.current
    if (c && c.pendingAnnounce) {
      clearTimeout(c.pendingAnnounce)
      c.pendingAnnounce = null
      announcePeer(peerHex, c, msg.carrier === true)
    }
    if (typeof msg.name === 'string') {
      const handle = typeof msg.handle === 'string' && HANDLE_RE.test(msg.handle) ? msg.handle : ''
      send({ ev: 'hello', key: peerHex, name: msg.name.slice(0, 64), handle, v: msg.v | 0 })
    }
    if (inboxLimit(peerHex) > 0) await onHello(peerHex, msg)
    else pendingHellos.set(peerHex, msg)
  } else if (msg.t === 'ack' && Number.isInteger(msg.length) && msg.length >= 0) {
    send({ ev: 'delivered', key: peerHex, length: msg.length })
  } else if (msg.t === 'typing') {
    send({ ev: 'typing', key: peerHex })
  } else if (msg.t === 'mesh') {
    await onMesh(msg.recs)
  } else if (msg.t === 'ghello') {
    await onGroupHello(peerHex, msg)
  } else if (msg.t === 'gack' && groups.has(msg.group) && Number.isInteger(msg.length) && msg.length >= 0) {
    send({ ev: 'gdelivered', group: msg.group, member: peerHex, length: msg.length })
  }
}

async function onHello(peerHex, msg) {
  if (!HEX64.test(msg.outbox)) return
  await openInbox(peerHex, msg.outbox)
  // Re-confirm what we already stored, in case an earlier ack was lost.
  const length = confirmed.get(peerHex) || 0
  if (length > 0) sendToPeer(peerHex, { t: 'ack', length })
}

// ---- @usernames --------------------------------------------------------------
//
// Phones announce their identity key on a DHT topic derived from their @username. Anyone
// can look the topic up and get the keys announced there. There is no authority, so two
// people can claim the same name: the app shows every match with its ID. Looking up does
// not connect to anyone, and needs the internet.

function handleTopic(h) {
  const out = b4a.alloc(32)
  sodium.crypto_generichash(out, b4a.from('meshron/handle/v1:' + h))
  return out
}

async function setHandle(h) {
  const next = HANDLE_RE.test(h) ? h : ''
  if (next === myHandle) return
  const old = myHandle
  myHandle = next
  if (swarm) {
    if (old) await swarm.leave(handleTopic(old))
    if (next) swarm.join(handleTopic(next), { server: true, client: false })
  }
  for (const k of peers.keys()) sendHello(k).catch(() => {})
}

async function findHandle({ req, handle }) {
  if (!HANDLE_RE.test(handle)) return send({ ev: 'handle-found', req, keys: [], error: 'invalid' })
  if (!swarm) return send({ ev: 'handle-found', req, keys: [], error: 'offline' })
  const keys = new Set()
  const stream = swarm.dht.lookup(handleTopic(handle))
  const timer = setTimeout(() => stream.destroy(), 8000)
  try {
    for await (const data of stream) {
      for (const p of data.peers || []) keys.add(hex(p.publicKey))
      if (keys.size >= 10) break
    }
  } catch {}
  clearTimeout(timer)
  stream.destroy()
  send({ ev: 'handle-found', req, keys: [...keys] })
}

// ---- Helpers ----------------------------------------------------------------

function me() {
  return hex(keyPair.publicKey)
}

// Bytes covered by a message signature. Binding sender and recipient keys stops a
// signed message from being replayed into a different conversation.
// Plain text keeps the original format; other kinds also sign the kind.
function signable(id, ts, fromHex, toHex, body, kind = 'text') {
  const fields = ['meshron-msg-v1', id, ts, fromHex, toHex, body]
  if (kind !== 'text') fields.push(kind)
  return b4a.from(JSON.stringify(fields))
}

// Splits a byte stream on '\n' without breaking multi-byte UTF-8 characters.
function lineReader(stream, online) {
  let pending = b4a.alloc(0)
  stream.on('data', (data) => {
    pending = pending.byteLength ? b4a.concat([pending, data]) : data
    let i
    while ((i = pending.indexOf(10)) !== -1) {
      const line = b4a.toString(pending.subarray(0, i))
      pending = pending.subarray(i + 1)
      if (line) online(line)
    }
    if (pending.byteLength > 1024 * 1024) pending = b4a.alloc(0) // drop runaway input
  })
}
