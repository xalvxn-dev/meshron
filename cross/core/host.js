// Meshron shared host (iPhone and Windows).
//
// Does what the Android app's Kotlin services do (IdentityRepository, ContactsRepository,
// NetworkService, MessagingService, FileService), in the same formats, so all platforms talk
// to each other. It runs the same networking worklet as Android (app/src/main/js/app.js)
// in-process and exchanges the same JSON commands and events with it.
//
// The screens (cross/ui) call `host.call(method, args)` and listen to `emit(event, data)`.
// Nothing here touches the DOM or a platform API: files, secrets and the worklet are injected.

const sodium = require('sodium-universal')
const b4a = require('b4a')
const z32 = require('./zbase32')
const { JsonFile, ensureDir, makeBox } = require('./store')

const HEX64 = /^[0-9a-f]{64}$/
const HEX32 = /^[0-9a-f]{32}$/
const HANDLE = /^[a-z0-9_]{3,24}$/
const LINK = /meshron:\/\/id\/([a-z0-9]+)(?:\?name=(\S*))?/
const BARE_ID = /^[ybndrfg8ejkmcpqxot1uwisza345h769]{52}$/

const PENDING = 0 // saved, not yet in my log
const SENT = 1 // appended to my log; replicates when peers are online
const DELIVERED = 2 // 1:1 only: peer stored it and acknowledged
const READ = 3 // 1:1 only: peer opened the chat and saw it (read receipts, like Android)
const RECEIVED = 3 // incoming
const GROUP = 'g:'
// Stories, view receipts, edits, unsends and reactions are log entries but not chat bubbles.
const HIDDEN = new Set(['story', 'seen', 'edit', 'unsend', 'react', 'gmeta'])
const STORY_KINDS = new Set(['story', 'seen'])
const UNSENDABLE = new Set(['text', 'file'])
const REPLY = /^↩ (.*)\n([\s\S]+)$/
const bodyText = (body) => { const m = REPLY.exec(body); return m ? m[2] : body }
const STORY_TTL = 24 * 60 * 60 * 1000
const visible = (r) => !HIDDEN.has(r.kind) && !(r.kind === 'text' && /^\{"op":"/.test(r.body || ''))

const MAX_BODY = 4000
const MAX_NAME = 32
const MAX_FILE = 25 * 1024 * 1024
const MAX_REQUESTS = 30
const FILE_CHUNK = 48 * 1024

const DEFAULT_SETTINGS = { useInternet: true, carry: true, notifications: true, sounds: true, enterSends: true, previews: true, hideIp: false, disappearHours: 0, allowRequests: true, legalVersion: 0, noticesSeen: 0, cmFrom: 'everyone', background: false }
// Anonymous mode starts with every privacy option on.
const ANON_SETTINGS = { hideIp: true, disappearHours: 24, previews: false }

function createHost({ fs, path, dataDir, secrets, loadWorklet, emit, platform, onLink }) {
  const hex = (buf) => b4a.toString(buf, 'hex')
  const randomHex = (n) => {
    const b = b4a.alloc(n)
    sodium.randombytes_buf(b)
    return hex(b)
  }
  const now = () => Date.now()
  const cleanName = (raw) => String(raw || '').trim().replace(/\s+/g, ' ')
  const cleanHandle = (raw) => String(raw || '').trim().replace(/^@/, '').toLowerCase()
  const idOf = (keyHex) => z32.encode(b4a.from(keyHex, 'hex'))
  const isGroup = (conv) => conv.startsWith(GROUP)
  const groupRowId = (sender, wireId) => sender.slice(0, 16) + ':' + wireId
  const wireId = (row) => (isGroup(row.peerKey) ? row.id.slice(row.id.indexOf(':') + 1) : row.id)
  const wireConv = (conv) => (isGroup(conv) ? conv : 'dm:' + conv)
  const safeName = (name) => (String(name).replace(/[^A-Za-z0-9._-]/g, '_').slice(-80) || 'file')

  // ---- Public / anonymous ---------------------------------------------------------
  // Anonymous is a second, separate identity: its own folder and its own secrets, so
  // nothing on disk or on the wire links it to the public one. mode.json picks which opens.
  const rootDir = dataDir
  const baseSecrets = secrets
  const modeFile = path.join(rootDir, 'mode.json')
  const wipeFile = path.join(rootDir, '.logout')
  // Log out: the last run left a marker, so clear every file before anything opens them.
  function rmTree (p) {
    let st
    try { st = fs.statSync(p) } catch { return }
    if (st.isDirectory()) { for (const n of fs.readdirSync(p)) rmTree(path.join(p, n)); try { fs.rmdirSync(p) } catch {} } else { try { fs.unlinkSync(p) } catch {} }
  }
  if (fs.existsSync(wipeFile)) { for (const n of fs.readdirSync(rootDir)) rmTree(path.join(rootDir, n)) }
  let anon = false
  try { anon = JSON.parse(fs.readFileSync(modeFile, 'utf8')).anon === true } catch {}
  if (anon) {
    dataDir = path.join(rootDir, 'anon')
    const base = secrets
    secrets = { get: (n) => base.get('anon.' + n), set: (n, v) => base.set('anon.' + n, v) }
  }
  const pinHash = (salt, pin) => {
    let h = b4a.from(salt + ':' + pin)
    for (let i = 0; i < 20000; i++) {
      const out = b4a.alloc(32)
      sodium.crypto_hash_sha256(out, h)
      h = out
    }
    return hex(h)
  }

  let loaded = null // promise of storage being open
  let state = null // JsonFile of everything except messages
  let box = null
  const chats = new Map() // conversation -> { file: JsonFile, rows: [] }
  let myKey = null

  const net = { status: 'stopped', keyMatches: null, dht: null, peers: {}, lastError: null, log: [] }
  let ipc = null
  let workletStarted = false
  let dialled = new Set()
  const handleLookups = new Map() // req -> resolve
  const uploads = new Map() // xfer -> { resolve, reject }
  const downloads = new Map() // req -> { row, info, chunks, bytes }
  const fileStatus = {} // "conv|id" -> text
  const incoming = new Map() // upload id from the UI -> { conv, name, mime, chunks, size }

  const sentDir = path.join(dataDir, 'files', 'sent')
  const receivedDir = path.join(dataDir, 'files', 'received')
  const messagesDir = path.join(dataDir, 'messages')

  // ---- Storage ----------------------------------------------------------------

  function load() {
    if (!loaded) {
      loaded = (async () => {
        for (const d of [dataDir, sentDir, receivedDir, messagesDir]) ensureDir(fs, d)
        let key = await secrets.get('storage-key')
        if (!key || !HEX64.test(key)) {
          key = randomHex(32)
          await secrets.set('storage-key', key)
        }
        box = makeBox(key)
        state = new JsonFile(fs, path.join(dataDir, 'state.bin'), () => ({
          v: 1, identity: null, contacts: [], groups: [], groupAcks: {}, groupLogs: {}, readMarks: {},
          carried: [], settings: { ...DEFAULT_SETTINGS, ...(anon ? ANON_SETTINGS : {}) }, pinned: [], muted: [], drafts: {}, seenStories: [],
          profile: { pv: 0, bio: '', photo: '' }, profiles: {}
        }), box)
        const s = state.value
        s.settings = { ...DEFAULT_SETTINGS, ...(anon ? ANON_SETTINGS : {}), ...(s.settings || {}) }
        for (const k of ['pinned', 'muted', 'carried', 'contacts', 'groups', 'seenStories']) if (!Array.isArray(s[k])) s[k] = []
        for (const k of ['groupAcks', 'groupLogs', 'readMarks', 'drafts']) if (!s[k] || typeof s[k] !== 'object') s[k] = {}
        if (s.identity) myKey = s.identity.publicKey
        for (const name of fs.readdirSync(messagesDir)) {
          if (!name.endsWith('.bin')) continue
          const conv = name.slice(0, -4).replace('_', ':')
          const file = new JsonFile(fs, path.join(messagesDir, name), [], box)
          if (Array.isArray(file.value)) chats.set(conv, { file, rows: file.value })
        }
      })()
      // A failed start (e.g. secure storage not ready yet) may be retried later.
      loaded.catch(() => { loaded = null })
    }
    return loaded
  }

  const S = () => state.value
  const saveState = () => state.save()

  function chat(conv) {
    let c = chats.get(conv)
    if (!c) {
      const file = new JsonFile(fs, path.join(messagesDir, conv.replace(':', '_') + '.bin'), [], box)
      c = { file, rows: file.value }
      chats.set(conv, c)
    }
    return c
  }

  const allRows = function * () {
    for (const c of chats.values()) yield * c.rows
  }

  /** Stores a new row. Returns false if it was already there (duplicate delivery). */
  function insertRow(row) {
    const c = chat(row.peerKey)
    if (c.rows.some((r) => r.id === row.id)) return false
    c.rows.push(row)
    c.rows.sort((a, b) => a.localTime - b.localTime)
    const target = applyChange(c, row)
    c.file.save()
    changed(row.peerKey, target ? [row, target] : [row])
    return true
  }

  /**
   * edit / unsend change the author's own earlier message; react sets the sender's reaction
   * on any message. Returns the changed row (so the screens redraw it).
   */
  function applyChange(c, row) {
    if (row.kind !== 'edit' && row.kind !== 'unsend' && row.kind !== 'react') return null
    let o
    try { o = JSON.parse(row.body) } catch { return null }
    if (!o || typeof o.id !== 'string' || !o.id || o.id.length > 100) return null
    const author = row.outgoing ? myKey : (row.senderKey || row.peerKey)
    if (row.kind === 'react') {
      const target = c.rows.find((r) => r.id === o.id && visible(r))
      if (!target || target.kind === 'deleted') return null
      const e = typeof o.e === 'string' ? o.e.slice(0, 16) : ''
      // Newest reaction per person wins (rows can arrive out of order across people).
      const at = (target.reactedAt = target.reactedAt || {})
      if ((at[author] || 0) > row.sentAt) return null
      at[author] = row.sentAt
      target.reactions = { ...(target.reactions || {}) }
      if (e) target.reactions[author] = e
      else delete target.reactions[author]
      return target
    }
    // Group row IDs carry the author, so nobody can change someone else's message.
    const targetId = isGroup(row.peerKey) ? groupRowId(author, o.id) : o.id
    const target = c.rows.find((r) => r.id === targetId)
    if (!target || target.outgoing !== row.outgoing || (target.senderKey || null) !== (row.senderKey || null)) return null
    if (row.kind === 'edit') {
      const text = typeof o.text === 'string' ? o.text.slice(0, MAX_BODY) : ''
      if (target.kind !== 'text' || !text.trim()) return null
      target.body = text
      target.edited = true
      return target
    }
    if (!UNSENDABLE.has(target.kind)) return null
    if (target.localPath) try { fs.unlinkSync(target.localPath) } catch {}
    target.kind = 'deleted'
    target.body = ''
    target.localPath = null
    target.reactions = {}
    return target
  }

  /** One short line that stands for a message in a reply (same as Android). */
  function quoteOf(r) {
    let text = r.body
    if (r.kind === 'file') {
      const info = parseFileInfo(r.body)
      text = info ? (/^image\//.test(info.mime || '') ? '📷 Photo' : '📎 ' + info.name) : '📎 File'
    } else text = bodyText(r.body)
    const line = String(text).replace(/\s+/g, ' ').trim()
    return line.length > 90 ? line.slice(0, 89) + '…' : line
  }

  // Tell the screens about changed rows; the chat list is recomputed (debounced).
  let summaryTimer = null
  function changed(conv, rows) {
    const shown = (rows || []).filter(visible)
    if (shown.length) emit('msgs', { conv, rows: shown })
    if (rows && rows.some((r) => STORY_KINDS.has(r.kind))) storiesChanged()
    if (!summaryTimer) {
      summaryTimer = setTimeout(() => {
        summaryTimer = null
        emit('summaries', summaries())
      }, 60)
    }
  }

  function summaries() {
    const out = []
    for (const [conv, c] of chats) {
      if (!c.rows.length) continue
      let unread = 0
      let last = null
      for (const r of c.rows) {
        if (!visible(r)) continue
        last = r
        if (!r.outgoing && !r.read) unread++
      }
      // A group with only settings changes so far still belongs in the list.
      if (!last && isGroup(conv)) { const r = c.rows[c.rows.length - 1]; last = { id: r.id, peerKey: conv, kind: 'text', body: '', outgoing: false, read: true, localTime: r.localTime, sentAt: r.sentAt, status: r.status } }
      if (last) out.push({ conv, last, unread })
    }
    return out.sort((a, b) => b.last.localTime - a.last.localTime)
  }

  // ---- What the screens see -----------------------------------------------------

  function identityView() {
    const i = S().identity
    if (!i) return null
    const id = idOf(i.publicKey)
    return {
      publicKey: i.publicKey, username: i.username, handle: i.handle || '', createdAt: i.createdAt,
      id, shortId: id.slice(0, 6) + '…' + id.slice(-4),
      shareLink: 'meshron://id/' + id + '?name=' + encodeURIComponent(i.username).replace(/%20/g, '+')
    }
  }

  function contactView(c) {
    const id = idOf(c.keyHex)
    const shortId = id.slice(0, 6) + '…' + id.slice(-4)
    return { ...c, id, shortId, displayName: c.name || shortId }
  }

  const contactsView = () => S().contacts.map(contactView)
  const groupsView = () => S().groups.map((g) => ({ ...g, conv: GROUP + g.id, acks: S().groupAcks[g.id] || {} }))
  const prefsView = () => ({ settings: S().settings, pinned: S().pinned, muted: S().muted, drafts: S().drafts })

  let netTimer = null
  function netChanged() {
    if (netTimer) return
    netTimer = setTimeout(() => {
      netTimer = null
      emit('net', net)
    }, 150)
  }

  function log(msg) {
    const t = new Date()
    const p = (n) => String(n).padStart(2, '0')
    net.log.push(p(t.getHours()) + ':' + p(t.getMinutes()) + ':' + p(t.getSeconds()) + '  ' + msg)
    if (net.log.length > 60) net.log.shift()
    netChanged()
  }

  function contactsChanged() {
    saveState()
    emit('contacts', contactsView())
    syncContacts()
  }

  function mutateContact(keyHex, fn) {
    const list = S().contacts
    const i = list.findIndex((c) => c.keyHex === keyHex)
    if (i < 0) return false
    const next = fn(list[i])
    if (next === list[i]) return false
    list[i] = next
    contactsChanged()
    return true
  }

  // ---- Networking worklet ------------------------------------------------------------

  function cmd(o) {
    if (ipc) ipc.emit('data', b4a.from(JSON.stringify(o) + '\n'))
  }

  // The network's local storage (RocksDB) can break if the app was killed mid-write: CURRENT
  // names a MANIFEST that isn't there. Then it's set aside and rebuilt from peers; chats, contacts
  // and keys live elsewhere and are not touched.
  const storeDir = () => path.join(dataDir, 'corestore')
  function storeBroken() {
    try {
      const db = path.join(storeDir(), 'db')
      if (!fs.existsSync(path.join(db, 'CURRENT'))) return false
      const manifest = String(fs.readFileSync(path.join(db, 'CURRENT'), 'utf8')).trim()
      return !!manifest && !fs.existsSync(path.join(db, manifest))
    } catch { return false }
  }
  function setStoreAside() {
    try {
      const to = storeDir() + '.broken-' + now()
      fs.renameSync(storeDir(), to)
      log('Network storage was damaged; set aside and starting fresh')
      return true
    } catch (e) { log('Could not set aside the damaged storage: ' + (e && e.message)); return false }
  }

  async function startNetwork() {
    if (workletStarted || !S().identity) return
    if (storeBroken()) setStoreAside()
    workletStarted = true
    net.status = 'starting'
    net.lastError = null
    netChanged()
    try {
      const seed = await secrets.get('seed')
      if (!seed || !HEX64.test(seed)) throw new Error('The private key is missing from this device')
      // The in-memory channel app.js uses instead of Bare.IPC: on('data') in, write() out.
      const handlers = []
      ipc = {
        on(ev, fn) { if (ev === 'data') handlers.push(fn); return ipc },
        emit(ev, data) { if (ev === 'data') for (const fn of handlers) fn(data) }
      }
      let pending = ''
      ipc.write = (buf) => {
        pending += b4a.toString(buf, 'utf8')
        let i
        while ((i = pending.indexOf('\n')) !== -1) {
          const line = pending.slice(0, i)
          pending = pending.slice(i + 1)
          if (!line) continue
          let e
          try { e = JSON.parse(line) } catch { continue }
          Promise.resolve().then(() => onEvent(e)).catch((err) => log('Event failed: ' + (err && err.message)))
        }
        return true
      }
      global.MESHRON_IPC = ipc
      loadWorklet()
      log('Worklet started')
      const i = S().identity
      cmd({
        cmd: 'start', seed, name: i.username, handle: anon ? '' : i.handle || '', profile: anon ? { pv: 0, bio: '', photo: '' } : myProfile(), forceRelay: !!S().settings.hideIp, storage: path.join(dataDir, 'corestore'),
        cursors: inboxCursors(), internet: S().settings.useInternet !== false, carry: S().settings.carry !== false,
        carried: S().carried
      })
    } catch (err) {
      fail('Could not start network: ' + (err && err.message))
    }
  }

  function fail(msg) {
    log(msg)
    net.status = 'failed'
    net.lastError = msg
    netChanged()
  }

  /** Resume point per 1:1 peer: newest stored message, or further if messages were deleted. */
  function inboxCursors() {
    const out = {}
    for (const [conv, c] of chats) {
      if (isGroup(conv)) continue
      let max = -1
      for (const r of c.rows) if (!r.outgoing && r.seq != null && r.seq > max) max = r.seq
      if (max >= 0) out[conv] = max + 1
    }
    for (const [conv, marks] of Object.entries(S().readMarks)) {
      if (isGroup(conv)) continue
      const m = marks[conv]
      if (m != null) out[conv] = Math.max(out[conv] || 0, m)
    }
    return out
  }

  function groupCursors(conv) {
    const out = {}
    for (const r of chat(conv).rows) {
      if (r.outgoing || r.seq == null || !r.senderKey) continue
      out[r.senderKey] = Math.max(out[r.senderKey] || 0, r.seq + 1)
    }
    for (const [sender, next] of Object.entries(S().readMarks[conv] || {})) out[sender] = Math.max(out[sender] || 0, next)
    return out
  }

  /** Keeps dialled peers equal to the ADDED contacts and tells the worklet who may write here. */
  function syncContacts() {
    if (net.status !== 'online') return
    const list = S().contacts
    const added = list.filter((c) => c.status === 'ADDED').map((c) => c.keyHex)
    const requests = list.filter((c) => c.status === 'REQUEST').map((c) => c.keyHex)
    cmd({ cmd: 'trust', added, requests })
    const wanted = new Set(added)
    for (const k of wanted) if (!dialled.has(k)) cmd({ cmd: 'connect', key: k })
    for (const k of dialled) {
      if (wanted.has(k)) continue
      cmd({ cmd: 'disconnect', key: k })
      delete net.peers[k]
    }
    dialled = wanted
    netChanged()
  }

  function openGroup(g) {
    cmd({ cmd: 'group-open', id: g.id, secret: g.secret, members: g.members, cursors: groupCursors(GROUP + g.id), anns: S().groupLogs[g.id] || {} })
  }

  function appendRow(row) {
    if (isGroup(row.peerKey)) {
      cmd({ cmd: 'group-append', group: row.peerKey.slice(GROUP.length), id: wireId(row), body: row.body, ts: row.sentAt, kind: row.kind })
    } else {
      cmd({ cmd: 'append', key: row.peerKey, id: row.id, body: row.body, ts: row.sentAt, kind: row.kind, urgent: !!row.urgent })
    }
  }

  async function onEvent(e) {
    // Reels answers (matched to the request that asked).
    if (e.req != null && reelWaits.has(e.req)) {
      const w = reelWaits.get(e.req)
      if (e.ev === 'reel-data') return w.chunks.push(e.data)
      reelWaits.delete(e.req)
      if (e.ev === 'reel-error') return w.reject(new Error(e.msg || 'Reels failed'))
      return w.resolve(e.ev === 'reel-done' ? w.chunks : e)
    }
    if (e.ev === 'reels-changed') return emit('reels-changed', { chans: e.chans || [] })
    if (typeof e.ev === 'string' && e.ev.startsWith('live-')) return emit(e.ev, e)
    switch (e.ev) {
      case 'ready': {
        net.keyMatches = e.publicKey === myKey
        if (!net.keyMatches) return fail('Worklet derived a different key, refusing to continue')
        net.status = 'online'
        log('Network ready')
        syncContacts()
        if (!anon) startReels()
        // Groups first, so queued group messages have a log to go into.
        for (const g of S().groups) openGroup(g)
        for (const r of [...allRows()].filter((r) => r.outgoing && r.status < DELIVERED && r.seq == null).sort((a, b) => a.localTime - b.localTime)) appendRow(r)
        retryDownloads()
        return
      }
      case 'dht':
        net.dht = {
          bootstrapped: !!e.bootstrapped, host: e.host || null, port: e.port || 0, firewalled: !!e.firewalled,
          randomized: !!e.randomized, connections: e.connections || 0, internet: e.internet !== false,
          relays: e.relays || 0, relaying: !!e.relaying
        }
        return netChanged()
      case 'peer':
        return onPeer(e)
      case 'hello': {
        const p = net.peers[e.key]
        const name = String(e.name || '').trim()
        if (p) {
          p.announcedName = name
          netChanged()
        }
        mutateContact(e.key, (c) => {
          const handle = String(e.handle || '')
          if ((!name || c.name === name) && c.handle === handle) return c
          return { ...c, name: name || c.name, handle }
        })
        // Their bio/photo changed (or I never had it): ask for it.
        if (e.pv > 0 && e.pv !== ((profiles()[e.key] || {}).pv || 0)) cmd({ cmd: 'profile-get', key: e.key })
        return
      }
      case 'profile': {
        if (!HEX64.test(e.key)) return
        profiles()[e.key] = { pv: e.pv | 0, bio: String(e.bio || '').slice(0, 150), photo: String(e.photo || '') }
        saveState()
        emit('profiles', profilesView())
        return
      }
      case 'msg':
        return onDirectMessage(e)
      case 'gmsg':
        return onGroupMessage(e)
      case 'appended':
        return markAppended(e.key, e.id, e.seq)
      case 'gappended':
        return markAppended(GROUP + e.group, groupRowId(myKey, e.id), e.seq)
      case 'delivered': {
        const c = chat(e.key)
        const rows = c.rows.filter((r) => r.outgoing && r.seq != null && r.seq < e.length && r.status < DELIVERED)
        if (!rows.length) return
        for (const r of rows) r.status = DELIVERED
        c.file.save()
        return changed(e.key, rows)
      }
      case 'read': {
        // They saw my messages (Android and Meshron on Windows/iPhone send this).
        const c = chat(e.key)
        const rows = c.rows.filter((r) => r.outgoing && r.seq != null && r.seq < e.length && r.status < READ)
        if (!rows.length) return
        for (const r of rows) r.status = READ
        c.file.save()
        return changed(e.key, rows)
      }
      case 'gdelivered': {
        const acks = (S().groupAcks[e.group] = S().groupAcks[e.group] || {})
        if ((acks[e.member] || 0) >= e.length) return
        acks[e.member] = e.length
        saveState()
        return emit('groups', groupsView())
      }
      case 'gann': {
        const logs = (S().groupLogs[e.group] = S().groupLogs[e.group] || {})
        if (!logs[e.member]) {
          logs[e.member] = { outbox: e.outbox, sig: e.sig }
          saveState()
        }
        return
      }
      case 'file-stored': {
        const u = uploads.get(e.xfer)
        if (u) u.resolve(e)
        return
      }
      case 'file-data': {
        const d = downloads.get(e.req)
        if (!d) return
        const buf = b4a.from(e.data, 'base64')
        d.chunks.push(buf)
        d.bytes += buf.byteLength
        if (d.bytes > d.info.size) return failDownload(e.req, 'Size mismatch')
        if (d.info.size > 0) setFileStatus(d.row, 'Downloading ' + Math.floor((d.bytes * 100) / d.info.size) + '%')
        return
      }
      case 'file-done':
        return finishDownload(e.req)
      case 'file-error': {
        const id = e.xfer || e.req
        const u = uploads.get(id)
        if (u) u.reject(new Error(e.msg || 'Upload failed'))
        if (downloads.has(id)) failDownload(id, e.msg === 'offline' ? 'Waiting for sender to come online' : 'Waiting to retry')
        return
      }
      case 'handle-found': {
        const done = handleLookups.get(e.req)
        if (done) done({ keys: Array.isArray(e.keys) ? e.keys : [], error: e.error || null })
        return
      }
      case 'carry':
        {
          const known = S().carried.find((r) => r.k === e.k)
          const u = Number(e.u) || 0
          if (!known) S().carried.push({ k: e.k, t: e.t, u })
          else if (u > (known.u || 0)) known.u = u
          else return
          saveState()
        }
        return
      // Local links (same Wi-Fi, Bluetooth): bytes for the platform transport that owns the link.
      case 'typing':
        emit('typing', { key: e.key, what: e.what === 'recording' ? 'recording' : 'typing' })
        return
      case 'link-data':
      case 'link-close':
        if (onLink) onLink(e)
        else emit(e.ev, { id: e.id, data: e.data })
        return
      case 'call-msg':
        return emit('call-msg', { key: e.key, msg: e.msg, via: e.via || null })
      case 'call-audio':
        return emit('call-audio', { key: e.key, data: e.data })
      case 'call-video':
        return emit('call-video', { key: e.key, data: e.data })
      case 'log':
        return log(e.msg)
      case 'error': {
        const first = String(e.msg || '').split('\n')[0]
        log('Error: ' + first)
        // Damaged storage: set it aside and start again (once).
        if (/NewSequentialFile|MANIFEST|Corruption/i.test(first) && !startNetwork.recovered) {
          startNetwork.recovered = true
          if (setStoreAside()) { setTimeout(() => emit('restart', { anon }), 300); return }
        }
        net.lastError = first
        return netChanged()
      }
    }
  }

  function onPeer(e) {
    const key = e.key
    const old = net.peers[key]
    const stateName = e.state === 'connected' || e.state === 'connecting' ? e.state : 'disconnected'
    const via = e.via || (old && old.via) || 'internet'
    net.peers[key] = {
      keyHex: key, state: stateName, via,
      host: via !== 'internet' ? null : e.host || (old && old.host) || null,
      port: e.port != null ? e.port : (old && old.port) || 0,
      announcedName: old ? old.announcedName : null
    }
    const short = key.slice(0, 8)
    if (stateName === 'connected') {
      log('Connected to ' + short + '… (' + via + ')')
      const list = S().contacts
      const known = list.some((c) => c.keyHex === key) || S().groups.some((g) => g.members.includes(key))
      // Someone we don't know dialled our ID: keep it as a request for the user.
      // (Carriers handing over someone's urgent message aren't requests.)
      if (!known && via === 'internet' && !e.carrier) {
        // Strangers can make endless new IDs, so cap how many requests we keep; and nobody gets
        // in when chat requests are turned off.
        if (S().settings.allowRequests === false || list.filter((c) => c.status === 'REQUEST').length >= MAX_REQUESTS) {
          log('Too many pending requests, dropped ' + short + '…')
          cmd({ cmd: 'disconnect', key })
          delete net.peers[key]
          return netChanged()
        }
        list.push({ keyHex: key, name: '', status: 'REQUEST', addedAt: now(), handle: '', verified: false })
        contactsChanged()
        emit('request', { key })
      }
      retryDownloads()
    } else if (stateName === 'disconnected') {
      log('Disconnected from ' + short + '…')
    }
    netChanged()
  }

  function markAppended(conv, id, seq) {
    const c = chat(conv)
    const r = c.rows.find((x) => x.id === id && x.outgoing)
    if (!r) return
    r.seq = seq
    r.status = Math.max(r.status, SENT)
    c.file.save()
    changed(conv, [r])
  }

  function onDirectMessage(e) {
    // Only peers in the contact list (added or pending request) may write to disk.
    const contact = S().contacts.find((c) => c.keyHex === e.key)
    if (!contact || contact.status === 'BLOCKED') return
    if (e.kind === 'invite') {
      // Only people you added can put you in a group.
      if (contact.status !== 'ADDED' || !acceptInvite(e.key, e.body)) {
        return cmd({ cmd: 'ack', key: e.key, seq: e.seq }) // consumed, but ignored
      }
    }
    const row = {
      peerKey: e.key, id: e.id, outgoing: false, body: e.body, sentAt: e.ts, localTime: now(), status: RECEIVED,
      signature: e.sig, read: HIDDEN.has(e.kind), seq: e.seq, senderKey: null, kind: e.kind || 'text', localPath: null
    }
    if (insertRow(row)) afterIncoming(row, contactView(contact).displayName)
    // Ack after the row is stored (duplicates are re-acked too).
    cmd({ cmd: 'ack', key: e.key, seq: e.seq })
  }

  function onGroupMessage(e) {
    const g = S().groups.find((x) => x.id === e.group)
    if (!g || !g.members.includes(e.from)) return
    const row = {
      peerKey: GROUP + g.id, id: groupRowId(e.from, e.id), outgoing: false, body: e.body, sentAt: e.ts,
      localTime: now(), status: RECEIVED, signature: e.sig, read: HIDDEN.has(e.kind), seq: e.seq, senderKey: e.from,
      kind: e.kind || 'text', localPath: null
    }
    if (insertRow(row)) {
      if (row.kind === 'gmeta') { try { applyMeta(g.id, e.from, JSON.parse(row.body)) } catch {} }
      afterIncoming(row, g.name)
    }
    cmd({ cmd: 'group-ack', group: e.group, member: e.from, seq: e.seq })
  }

  function afterIncoming(row, title) {
    if (row.kind === 'story') {
      if (fileInfoOf(row)) download(row)
      return
    }
    if (HIDDEN.has(row.kind)) return
    if (row.kind === 'file') download(row)
    if (S().muted.includes(row.peerKey)) return
    if (anon) emit('incoming', { conv: row.peerKey, row: { ...row, kind: 'text', body: 'New message' }, title: 'Meshron' })
    else emit('incoming', { conv: row.peerKey, row, title })
  }

  function parseInvite(json) {
    try {
      const o = JSON.parse(json)
      const members = Array.isArray(o.members) ? o.members : null
      if (!HEX32.test(o.id) || !HEX64.test(o.secret) || !HEX64.test(o.creator) || !members) return null
      if (members.some((m) => !HEX64.test(m)) || members.length > 256) return null
      const owner = HEX64.test(o.owner) && members.includes(o.owner) ? o.owner : o.creator
      const admins = (Array.isArray(o.admins) ? o.admins : []).filter((m) => members.includes(m))
      return {
        id: o.id, name: String(o.name || '').slice(0, 48).trim() || 'Group', secret: o.secret, creatorKey: o.creator,
        members: [...new Set(members)], createdAt: now(), ownerKey: owner, admins, bio: String(o.bio || '').slice(0, 200), icon: String(o.icon || '').slice(0, 24000), adult: o.adult === true
      }
    } catch {
      return null
    }
  }

  function acceptInvite(from, json) {
    const g = parseInvite(json)
    if (!g || !g.members.includes(from) || !g.members.includes(myKey)) return false
    // 18+ groups: someone under 18 (or who hasn't given a birthday) never joins one.
    if (g.adult && !isAdult()) return true
    const old = S().groups.find((x) => x.id === g.id)
    if (!old) {
      S().groups.push(g)
      saveState()
      emit('groups', groupsView())
      openGroup(g)
    } else if (!old.members.includes(myKey) && old.secret === g.secret) {
      // Added back after leaving or being removed.
      Object.assign(old, { ...g, createdAt: old.createdAt })
      saveState()
      emit('groups', groupsView())
      openGroup(old)
    }
    return true
  }

  // ---- Group roles and settings (same rules as Android) -----------------------------
  // Every change is a signed "gmeta" entry in the sender's group log; each member applies it
  // only if the sender may make it (owner > admins > members).

  const ownerOf = (g) => g.ownerKey || g.creatorKey
  const isAdminOf = (g, k) => k === ownerOf(g) || (g.admins || []).includes(k)

  function applyMeta(groupId, from, o) {
    const g = S().groups.find((x) => x.id === groupId)
    if (!g || !g.members.includes(from) || !o || typeof o !== 'object') return
    const who = typeof o.who === 'string' ? o.who : ''
    const members = [...g.members]
    let admins = [...(g.admins || [])]
    let touched = false
    const owner = ownerOf(g)
    switch (o.op) {
      case 'info':
        if (!isAdminOf(g, from)) return
        g.name = String(o.name || '').slice(0, 48).trim() || g.name
        g.bio = String(o.bio || '').slice(0, 200)
        g.icon = String(o.icon || '').slice(0, 24000)
        touched = true
        break
      case 'admin':
        if (from !== owner || !members.includes(who) || who === owner) return
        admins = admins.filter((k) => k !== who)
        if (o.on) admins.push(who)
        g.admins = admins; touched = true
        break
      case 'remove':
        if (!members.includes(who) || who === owner || !(from === owner || (admins.includes(from) && !admins.includes(who)))) return
        g.members = members.filter((k) => k !== who); g.admins = admins.filter((k) => k !== who); touched = true
        break
      case 'add':
        if (!isAdminOf(g, from)) return
        for (const k of (Array.isArray(o.who) ? o.who : []).slice(0, 64)) if (HEX64.test(k) && !members.includes(k)) members.push(k)
        if (members.length > 256) return
        g.members = members; touched = true
        break
      case 'owner':
        if (from !== owner || !members.includes(who) || who === from) return
        admins = admins.filter((k) => k !== who)
        if (!admins.includes(from)) admins.push(from)
        g.ownerKey = who; g.admins = admins; touched = true
        break
      case 'leave':
        if (from === owner) return
        g.members = members.filter((k) => k !== from); g.admins = admins.filter((k) => k !== from); touched = true
        break
      case 'del': {
        if (!isAdminOf(g, from)) return
        const c = chat(GROUP + g.id)
        const target = c.rows.find((r) => r.id === String(o.id || ''))
        // Admins can't delete the owner's messages; the owner can delete anything.
        if (!target || (from !== owner && target.senderKey === owner) || !UNSENDABLE.has(target.kind)) return
        if (target.localPath) try { fs.unlinkSync(target.localPath) } catch {}
        target.kind = 'deleted'; target.body = ''; target.localPath = null; target.reactions = {}
        c.file.save()
        changed(GROUP + g.id, [target])
        return
      }
      default:
        return
    }
    if (touched) {
      saveState()
      emit('groups', groupsView())
      if (net.status === 'online' && g.members.includes(myKey)) openGroup(g)
    }
  }

  function groupMeta(groupId, o) {
    const g = S().groups.find((x) => x.id === groupId)
    if (!g) throw new Error('No such group')
    applyMeta(g.id, myKey, o)
    sendRow(GROUP + g.id, JSON.stringify(o), 'gmeta')
  }

  function inviteJson(g) {
    return JSON.stringify({ id: g.id, name: g.name, secret: g.secret, creator: g.creatorKey, members: g.members, owner: ownerOf(g), admins: g.admins || [], bio: g.bio || '', icon: g.icon || '', adult: !!g.adult })
  }

  // ---- Age (a birthday asked once; it stays on this device) ----
  function ageYears() {
    const b = S().birth
    if (!b) return null
    const d = new Date(b); const t = new Date()
    let y = t.getFullYear() - d.getFullYear()
    if (t.getMonth() < d.getMonth() || (t.getMonth() === d.getMonth() && t.getDate() < d.getDate())) y--
    return y
  }
  const isAdult = () => (ageYears() || 0) >= 18

  // ---- Files ------------------------------------------------------------------------

  const fileKey = (row) => row.peerKey + '|' + row.id

  function setFileStatus(row, text) {
    const k = fileKey(row)
    if (text == null) delete fileStatus[k]
    else fileStatus[k] = text
    emit('file-status', { key: k, text: text == null ? null : text })
  }

  function parseFileInfo(json) {
    try {
      const o = JSON.parse(json)
      if (typeof o.name !== 'string' || typeof o.sha256 !== 'string' || typeof o.blobs !== 'string' || !o.blob) return null
      return { name: o.name.slice(0, 200), mime: o.mime || 'application/octet-stream', size: Number(o.size), sha256: o.sha256, blobs: o.blobs, blob: o.blob }
    } catch {
      return null
    }
  }

  function sha256(buf) {
    const out = b4a.alloc(sodium.crypto_hash_sha256_BYTES)
    sodium.crypto_hash_sha256(out, buf)
    return hex(out)
  }

  /** Group files, and files from contacts you added. Never from pending requests (could be spam). */
  const mayDownload = (row) => isGroup(row.peerKey) || S().contacts.some((c) => c.keyHex === row.peerKey && c.status === 'ADDED')

  function download(row) {
    if (row.localPath || net.status !== 'online') return
    for (const d of downloads.values()) if (fileKey(d.row) === fileKey(row)) return
    const info = fileInfoOf(row)
    if (row.kind === 'story' && storyExpired(row)) return
    if (!info) return setFileStatus(row, 'Invalid file')
    if (info.size > MAX_FILE) return setFileStatus(row, 'Too large')
    if (!mayDownload(row)) return setFileStatus(row, 'Accept this contact to download')
    const req = randomHex(8)
    downloads.set(req, { row, info, chunks: [], bytes: 0 })
    setFileStatus(row, 'Downloading…')
    cmd({ cmd: 'file-get', req, conv: wireConv(row.peerKey), from: row.senderKey || row.peerKey, blobs: info.blobs, blob: info.blob })
  }

  function retryDownloads() {
    for (const r of allRows()) {
      if (r.outgoing || r.localPath) continue
      if (r.kind === 'file' || (r.kind === 'story' && fileInfoOf(r) && !storyExpired(r))) download(r)
    }
  }

  function failDownload(req, message) {
    const d = downloads.get(req)
    if (!d) return
    downloads.delete(req)
    setFileStatus(d.row, message)
  }

  function finishDownload(req) {
    const d = downloads.get(req)
    if (!d) return
    downloads.delete(req)
    const data = b4a.concat(d.chunks)
    if (data.byteLength !== d.info.size || sha256(data) !== d.info.sha256) return setFileStatus(d.row, 'Failed: file was altered')
    const dest = path.join(receivedDir, wireId(d.row).slice(0, 12) + '-' + safeName(d.info.name))
    fs.writeFileSync(dest, data)
    const c = chat(d.row.peerKey)
    const row = c.rows.find((r) => r.id === d.row.id)
    if (row) {
      row.localPath = dest
      c.file.save()
      changed(row.peerKey, [row])
    }
    setFileStatus(d.row, null)
    if (d.row.kind === 'story') storiesChanged()
  }

  /** Stores bytes in my blob log, then sends the file message. */
  /** Stores bytes in my blob log for [conv]; returns the file description a message carries. */
  async function uploadBlob(conv, name, mime, data, local) {
    const id = randomHex(16)
    try {
      const stored = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Storing the file took too long')), 120000)
        uploads.set(id, {
          resolve: (v) => { clearTimeout(timer); resolve(v) },
          reject: (e) => { clearTimeout(timer); reject(e) }
        })
        cmd({ cmd: 'file-begin', xfer: id, conv: wireConv(conv) })
        for (let o = 0; o < data.byteLength; o += FILE_CHUNK) {
          cmd({ cmd: 'file-chunk', xfer: id, data: b4a.toString(data.subarray(o, Math.min(o + FILE_CHUNK, data.byteLength)), 'base64') })
        }
        cmd({ cmd: 'file-end', xfer: id })
      })
      return { name, mime: mime || 'application/octet-stream', size: data.byteLength, sha256: sha256(data), blobs: stored.blobs, blob: stored.blob }
    } finally {
      uploads.delete(id)
    }
  }

  async function sendFile(conv, name, mime, data) {
    if (net.status !== 'online') throw new Error('The network is still starting. Try again in a moment.')
    if (data.byteLength > MAX_FILE) throw new Error('Files can be up to 25 MB')
    if (!data.byteLength) throw new Error("That file is empty")
    const local = path.join(sentDir, randomHex(16) + '-' + safeName(name))
    fs.writeFileSync(local, data)
    try {
      const info = await uploadBlob(conv, name, mime, data, local)
      sendRow(conv, JSON.stringify(info), 'file', local)
    } catch (err) {
      try { fs.unlinkSync(local) } catch {}
      throw err
    }
  }

  // ---- Stories --------------------------------------------------------------------------
  //
  // A story is a 'story' entry fanned out to each added contact's 1:1 log, so it travels like
  // any message (internet, relays, nearby, mesh) and stays end-to-end encrypted. Body:
  //   { sid, type: 'text' | 'photo' | 'delete', text?, bg?, file?: { name, mime, size, sha256, blobs, blob }, exp }
  // Viewers answer with a 'seen' entry { sid }. Photo files are deleted when the story expires.

  function parseStory(row) {
    try {
      const o = JSON.parse(row.body)
      if (!o || typeof o.sid !== 'string' || !/^[0-9a-f]{16,32}$/.test(o.sid)) return null
      return o
    } catch {
      return null
    }
  }
  function fileInfoOf(row) {
    if (row.kind === 'file') return parseFileInfo(row.body)
    if (row.kind !== 'story') return null
    const o = parseStory(row)
    return o && o.file ? parseFileInfo(JSON.stringify(o.file)) : null
  }
  const storyExpired = (row) => row.sentAt + STORY_TTL < now()

  let storiesTimer = null
  function storiesChanged() {
    if (storiesTimer) return
    storiesTimer = setTimeout(() => {
      storiesTimer = null
      emit('stories', storiesView())
    }, 120)
  }

  function storiesView() {
    const cutoff = now() - STORY_TTL
    const deleted = new Set()
    const mine = new Map() // sid -> story
    const viewers = new Map() // sid -> Set(keys)
    const friends = new Map() // key -> [stories]
    for (const [conv, c] of chats) {
      if (isGroup(conv)) continue
      for (const r of c.rows) {
        if (r.kind === 'seen') {
          if (!r.outgoing) {
            const o = parseStory(r)
            if (o) {
              if (!viewers.has(o.sid)) viewers.set(o.sid, new Set())
              viewers.get(o.sid).add(conv)
            }
          }
          continue
        }
        if (r.kind !== 'story') continue
        const o = parseStory(r)
        if (!o) continue
        if (o.type === 'delete') { deleted.add((r.outgoing ? 'me' : conv) + '|' + o.sid); continue }
        if (r.sentAt < cutoff) continue
        const item = { sid: o.sid, type: o.type === 'photo' ? 'photo' : 'text', text: String(o.text || '').slice(0, 700), bg: o.bg | 0, ts: r.sentAt, conv, id: r.id, hasPhoto: !!o.file, photoReady: !!r.localPath, mime: o.file ? o.file.mime : null }
        if (r.outgoing) {
          if (!mine.has(o.sid) || (!mine.get(o.sid).photoReady && item.photoReady)) mine.set(o.sid, item)
        } else {
          const contact = S().contacts.find((x) => x.keyHex === conv)
          if (!contact || contact.status !== 'ADDED') continue
          item.seen = S().seenStories.includes(conv + '|' + o.sid)
          if (!friends.has(conv)) friends.set(conv, [])
          friends.get(conv).push(item)
        }
      }
    }
    const myList = [...mine.values()].filter((x) => !deleted.has('me|' + x.sid)).sort((a, b) => a.ts - b.ts)
    for (const x of myList) x.viewers = [...(viewers.get(x.sid) || [])]
    const friendList = []
    for (const [key, items] of friends) {
      const list = items.filter((x) => !deleted.has(key + '|' + x.sid)).sort((a, b) => a.ts - b.ts)
      if (list.length) friendList.push({ key, items: list, allSeen: list.every((x) => x.seen), latest: list[list.length - 1].ts })
    }
    friendList.sort((a, b) => (a.allSeen === b.allSeen ? b.latest - a.latest : a.allSeen ? 1 : -1))
    return { mine: myList, friends: friendList }
  }

  /** Expired story photos are removed from this device. */
  function sweepStories() {
    for (const c of chats.values()) {
      let dirty = false
      for (const r of c.rows) {
        if (r.kind === 'story' && r.localPath && storyExpired(r)) {
          try { fs.unlinkSync(r.localPath) } catch {}
          r.localPath = null
          dirty = true
        }
      }
      if (dirty) c.file.save()
    }
    const live = new Set()
    for (const r of allRows()) if (r.kind === 'story' && !storyExpired(r)) { const o = parseStory(r); if (o) live.add((r.outgoing ? 'me' : r.peerKey) + '|' + o.sid) }
    const before = S().seenStories.length
    S().seenStories = S().seenStories.filter((k) => live.has(k))
    if (S().seenStories.length !== before) saveState()
  }
  setInterval(() => { if (state) { sweepStories(); storiesChanged() } }, 10 * 60 * 1000)

  /** A newly added contact gets my stories that are still live (like following someone). */
  async function shareLiveStoriesWith(key) {
    const sentTo = new Set()
    let source = new Map() // sid -> a row of mine holding it
    for (const r of chat(key).rows) if (r.outgoing && r.kind === 'story') { const o = parseStory(r); if (o) sentTo.add(o.sid) }
    for (const [conv, c] of chats) {
      if (isGroup(conv)) continue
      for (const r of c.rows) {
        if (!r.outgoing || r.kind !== 'story' || storyExpired(r)) continue
        const o = parseStory(r)
        if (o && o.type !== 'delete' && !sentTo.has(o.sid) && !source.has(o.sid)) source.set(o.sid, r)
      }
    }
    for (const [, r] of source) {
      const o = parseStory(r)
      if (o.type === 'photo') {
        if (!r.localPath || !fs.existsSync(r.localPath)) continue
        o.file = await uploadBlob(key, 'story.jpg', (o.file && o.file.mime) || 'image/jpeg', fs.readFileSync(r.localPath), r.localPath)
      }
      sendRow(key, JSON.stringify(o), 'story', r.localPath || null)
    }
  }

  const storyAudience = () => S().contacts.filter((c) => c.status === 'ADDED').map((c) => c.keyHex)

  // ---- Sending ------------------------------------------------------------------------

  /** [urgent]: every Meshron phone that meets it passes it on (1:1 chats). */
  function sendRow(conv, body, kind, localPath, urgent) {
    const t = now()
    const wid = randomHex(16)
    const group = isGroup(conv)
    const row = {
      peerKey: conv, id: group ? groupRowId(myKey, wid) : wid, outgoing: true, body, sentAt: t, localTime: t,
      status: PENDING, signature: null, read: true, seq: null, senderKey: group ? myKey : null, kind, localPath: localPath || null
    }
    if (urgent && !group) row.urgent = true
    insertRow(row)
    if (net.status === 'online') appendRow(row)
    return row
  }

  /** A note in a chat that only this device keeps (calls: missed, declined, how long). Never sent. */
  function noteRow(conv, text, outgoing) {
    const t = now()
    insertRow({ peerKey: conv, id: 'n' + randomHex(12), outgoing: !!outgoing, body: String(text || '').slice(0, 200), sentAt: t, localTime: t,
      status: SENT, signature: null, read: true, seq: null, senderKey: null, kind: 'call', localPath: null, local: true })
  }

  /** Remembers how far a chat was read, so deleting rows doesn't make them come back. */
  function keepReadPosition(conv) {
    const marks = (S().readMarks[conv] = S().readMarks[conv] || {})
    const cursors = isGroup(conv) ? groupCursors(conv) : (() => {
      let max = -1
      for (const r of chat(conv).rows) if (!r.outgoing && r.seq != null && r.seq > max) max = r.seq
      return max >= 0 ? { [conv]: max + 1 } : {}
    })()
    for (const [sender, next] of Object.entries(cursors)) marks[sender] = Math.max(marks[sender] || 0, next)
    saveState()
  }

  function parseContact(input) {
    const text = String(input || '').trim()
    if (!text) return { error: 'Paste an ID or link' }
    const link = LINK.exec(text)
    let id
    let name = ''
    if (link) {
      id = link[1]
      try { name = decodeURIComponent((link[2] || '').replace(/\+/g, ' ')).trim() } catch {}
    } else if (BARE_ID.test(text)) id = text
    else return { error: "That doesn't look like a Meshron ID" }
    if (id.length !== 52) return { error: 'ID must be 52 characters' }
    let key
    try { key = z32.decode(id) } catch { return { error: 'ID has invalid characters' } }
    if (key.byteLength !== 32 || z32.encode(key) !== id) return { error: 'ID is not valid' }
    return { keyHex: hex(key), name: name.slice(0, 64) }
  }

  function safetyCode(theirKey) {
    const [a, b] = [myKey, theirKey].sort()
    const d = b4a.alloc(sodium.crypto_hash_sha512_BYTES)
    sodium.crypto_hash_sha512(d, b4a.concat([b4a.from('meshron-safety-v1'), b4a.from(a, 'hex'), b4a.from(b, 'hex')]))
    const out = []
    for (let i = 0; i < 12; i++) {
      const v = d[i * 4] * 16777216 + d[i * 4 + 1] * 65536 + d[i * 4 + 2] * 256 + d[i * 4 + 3]
      out.push(String(v % 100000).padStart(5, '0'))
    }
    return out
  }

  async function createIdentity(username, seed, handle) {
    const name = cleanName(username)
    if (name.length < 1 || name.length > MAX_NAME) throw new Error('Name must be 1–' + MAX_NAME + ' characters')
    if (S().identity) throw new Error('This device already has an account')
    const h = anon ? '' : cleanHandle(handle)
    if (h && !HANDLE.test(h)) throw new Error('Usernames are 3–24 letters, numbers or _')
    const pk = b4a.alloc(sodium.crypto_sign_PUBLICKEYBYTES)
    const sk = b4a.alloc(sodium.crypto_sign_SECRETKEYBYTES)
    sodium.crypto_sign_seed_keypair(pk, sk, seed)
    sk.fill(0)
    await secrets.set('seed', hex(seed))
    seed.fill(0)
    S().identity = { publicKey: hex(pk), username: name, createdAt: now(), handle: h }
    myKey = S().identity.publicKey
    state.flush()
    emit('identity', identityView())
    startNetwork()
    return identityView()
  }

  // ---- Calls from the screens -----------------------------------------------------------

  // ---- Profiles (bio + small photo) --------------------------------------------------
  const profiles = () => (S().profiles = S().profiles || {})
  const myProfile = () => (S().profile = S().profile || { pv: 0, bio: '', photo: '' })
  /** For the screens: { me: {bio, photo}, peers: { key: {bio, photo} } }, photos as data URLs. */
  function profilesView() {
    const url = (b64) => (b64 ? 'data:image/jpeg;base64,' + b64 : '')
    const peers = {}
    for (const [k, p] of Object.entries(profiles())) peers[k] = { bio: p.bio, photo: url(p.photo) }
    const me = myProfile()
    return { me: { bio: me.bio, link: me.link || '', photo: url(me.photo) }, peers }
  }

  let sweeping = null
  function startSweep() {
    if (sweeping) return
    const sweep = () => {
      const hours = Number(S().settings.disappearHours) || 0
      if (hours <= 0) return
      const before = now() - hours * 3600 * 1000
      for (const [conv, c] of chats) {
        const old = c.rows.filter((r) => (r.localTime || r.sentAt || 0) < before)
        if (!old.length) continue
        keepReadPosition(conv)
        for (const r of old) if (r.localPath) try { fs.unlinkSync(r.localPath) } catch {}
        const gone = new Set(old)
        c.rows.splice(0, c.rows.length, ...c.rows.filter((r) => !gone.has(r)))
        c.file.save()
        emit('msgs-removed', { conv, ids: old.map((r) => r.id) })
        changed(conv)
      }
    }
    sweep()
    sweeping = setInterval(sweep, 60 * 1000)
  }

  // ---- Reels (short public videos; the worklet's reels.js does the work) -------------

  const reelsDir = path.join(dataDir, 'reels-cache')
  const reelWaits = new Map()
  let reelSeq = 0
  let reelsCh = null
  const REELS_CACHE = 400 * 1024 * 1024
  function reelCmd(c, timeout = 30000, req = 'r' + ++reelSeq) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { reelWaits.delete(req); reject(new Error('Reels took too long. Check your connection.')) }, timeout)
      reelWaits.set(req, {
        chunks: [],
        resolve: (v) => { clearTimeout(timer); resolve(v) },
        reject: (err) => { clearTimeout(timer); reject(err) }
      })
      cmd({ ...c, req })
    })
  }
  function reelsProfile() {
    const i = S().identity || {}
    const p = myProfile()
    return { name: i.username || '', handle: i.handle || '', bio: p.bio || '', link: p.link || '', photo: p.photo && p.photo.length <= 60 * 1024 ? p.photo : '' }
  }
  function startReels() {
    if (anon || reelsCh) return
    reelCmd({ cmd: 'reels-start', ...reelsProfile() }, 60000).then((r) => {
      reelsCh = r.ch
      emit('reels-ready', { ch: r.ch })
    }, (err) => log('Reels: ' + err.message))
  }
  function reelsMe() {
    if (!anon && reelsCh) cmd({ cmd: 'reels-me', ...reelsProfile() })
  }
  const needReels = () => {
    if (anon) throw new Error('Reels are off while you are anonymous')
    if (!reelsCh) throw new Error('Reels are starting. Try again in a moment.')
  }
  const reelFile = (ch, id) => {
    if (!HEX64.test(ch) || !/^[0-9a-f]{16,32}$/.test(id)) throw new Error('Bad video')
    return path.join(reelsDir, ch.slice(0, 16) + '-' + id + '.mp4')
  }
  const reelLoads = new Map()
  function trimReelCache() {
    try {
      const files = fs.readdirSync(reelsDir).map((n) => {
        const p = path.join(reelsDir, n)
        const st = fs.statSync(p)
        return { p, size: st.size, t: st.mtimeMs || 0 }
      }).sort((a, b) => a.t - b.t)
      let total = files.reduce((n, x) => n + x.size, 0)
      for (const x of files) {
        if (total <= REELS_CACHE) break
        try { fs.unlinkSync(x.p); total -= x.size } catch {}
      }
    } catch {}
  }

  // ---- Public accounts (same crypto as Android's AccountCrypto) ---------------------------
  //   salt    = SHA-256("meshron-account-v1:" + username)[0..16]
  //   master  = Argon2id(password, salt, 3 passes, 64 MiB) -> 64 bytes
  //   authKey = master[0..32]  (proves the password; the server keeps only a hash)
  //   lockKey = master[32..64] (locks the identity seed; never leaves the device)
  //   blob    = base64(nonce12 || ChaCha20-Poly1305(lockKey, seed, ad = "meshron-blob-v1"))
  let pendingKeys = null // { u, auth, lock } between typing the password and the server's answer
  function deriveKeys(username, password) {
    const u = String(username || '').trim().replace(/^@/, '').toLowerCase()
    const h = b4a.alloc(32)
    sodium.crypto_hash_sha256(h, b4a.from('meshron-account-v1:' + u))
    const master = b4a.alloc(64)
    sodium.crypto_pwhash(master, b4a.from(String(password)), h.subarray(0, 16), 3, 64 * 1024 * 1024, sodium.crypto_pwhash_ALG_ARGON2ID13)
    const keys = { u, auth: hex(master.subarray(0, 32)), lock: b4a.from(master.subarray(32, 64)) }
    master.fill(0)
    return keys
  }
  const AD = b4a.from('meshron-blob-v1')
  function lockSeed(lock, seed) {
    const nonce = b4a.alloc(12); sodium.randombytes_buf(nonce)
    const out = b4a.alloc(seed.byteLength + 16)
    sodium.crypto_aead_chacha20poly1305_ietf_encrypt(out, seed, AD, null, nonce, lock)
    return b4a.toString(b4a.concat([nonce, out]), 'base64')
  }
  function unlockSeed(lock, blob) {
    const b = b4a.from(String(blob), 'base64')
    const seed = b4a.alloc(b.byteLength - 12 - 16)
    sodium.crypto_aead_chacha20poly1305_ietf_decrypt(seed, null, b.subarray(12), AD, b.subarray(0, 12), lock)
    return seed
  }
  async function saveAccount(a) { await secrets.set('account', JSON.stringify(a)) }
  async function loadAccount() { try { return JSON.parse((await secrets.get('account')) || 'null') } catch { return null } }

  const methods = {
    /** Turns a password into keys (slow on purpose). Returns the authKey to send to the server. */
    async 'account.keys'({ username, password }) {
      pendingKeys = deriveKeys(username, password)
      return { username: pendingKeys.u, authKey: pendingKeys.auth }
    },
    /** After a good sign-in: open the identity with the password's lock key and use it here. */
    async 'account.adopt'({ blob, publicKey, email, twoFactor }) {
      const k = pendingKeys
      if (!k) throw new Error('Start again')
      let seed
      try { seed = unlockSeed(k.lock, blob) } catch { throw new Error("Couldn't open the account") }
      const pk = b4a.alloc(32); const sk = b4a.alloc(64)
      sodium.crypto_sign_seed_keypair(pk, sk, seed); sk.fill(0)
      if (hex(pk) !== publicKey) { seed.fill(0); throw new Error("Account data doesn't match") }
      if (!S().identity) await createIdentity(k.u, seed, k.u)
      else if (S().identity.publicKey !== publicKey) { seed.fill(0); throw new Error('This device already has a different Meshron ID') }
      await saveAccount({ username: k.u, email: email || '', authKey: k.auth, twoFactor: !!twoFactor })
      k.lock.fill(0); pendingKeys = null
      emit('account', { username: k.u, email: email || '', twoFactor: !!twoFactor })
      return { ok: true }
    },
    /** For signing up: this device's identity locked with the new password, and the signature. */
    async 'account.signupData'({ username }) {
      const k = pendingKeys
      if (!k) throw new Error('Start again')
      const seedHex = await secrets.get('seed')
      if (!seedHex) throw new Error('The private key is missing from this device')
      const seed = b4a.from(seedHex, 'hex')
      const pk = b4a.alloc(32); const sk = b4a.alloc(64)
      sodium.crypto_sign_seed_keypair(pk, sk, seed)
      const sig = b4a.alloc(64)
      sodium.crypto_sign_detached(sig, b4a.from('meshron-signup-v1:' + k.u), sk)
      sk.fill(0)
      const blob = lockSeed(k.lock, seed); seed.fill(0)
      return { blob, publicKey: hex(pk), sig: hex(sig), authKey: k.auth }
    },
    async 'account.saved'({ email, twoFactor }) {
      const k = pendingKeys
      if (!k) throw new Error('Start again')
      await saveAccount({ username: k.u, email: email || '', authKey: k.auth, twoFactor: !!twoFactor })
      if (S().identity && !S().identity.handle) { S().identity.handle = k.u; saveState(); cmd({ cmd: 'handle', handle: k.u }); reelsMe(); emit('identity', identityView()) }
      k.lock.fill(0); pendingKeys = null
      emit('account', { username: k.u, email: email || '', twoFactor: !!twoFactor })
      return { ok: true }
    },
    async 'account.state'() { const a = await loadAccount(); return a ? { username: a.username, email: a.email, twoFactor: a.twoFactor } : null },
    /** Credentials for account requests (AI, moderation) made by the screens. */
    async 'account.auth'() { const a = await loadAccount(); if (!a) throw new Error('Create a Meshron account (Settings) to use this'); return { username: a.username, authKey: a.authKey } },
    async 'account.forget'() { await secrets.set('account', ''); emit('account', null); return { ok: true } },

    /** Add-contact link for someone found in Reels. */
    async 'reels.link'({ id, name }) {
      if (!HEX64.test(id)) throw new Error('Unknown person')
      return 'meshron://id/' + idOf(id) + '?name=' + encodeURIComponent(String(name || '').slice(0, 32)).replace(/%20/g, '+')
    },
    async 'reels.status'() {
      return { on: !anon, ready: !!reelsCh, ch: reelsCh }
    },
    async 'reels.feed'({ kind = 'foryou', ch, offset = 0, limit = 12, sound }) {
      needReels()
      const r = await reelCmd({ cmd: 'reels-feed', kind, ch, offset, limit, sound })
      return { items: r.items || [], more: !!r.more }
    },
    async 'reels.profile'({ ch }) {
      needReels()
      return (await reelCmd({ cmd: 'reels-profile', ch }, 20000)).profile
    },
    /** People active on Meshron lately (not me), newest first: for making friends. */
    async 'reels.active'({ limit }) {
      needReels()
      return (await reelCmd({ cmd: 'reels-active', limit: Math.min(Number(limit) || 40, 100) }, 20000)).users || []
    },
    async 'reels.search'({ q }) {
      needReels()
      return (await reelCmd({ cmd: 'reels-search', q: String(q || '').slice(0, 40) }, 30000)).users || []
    },
    async 'reels.comments'({ ch, id }) {
      needReels()
      return (await reelCmd({ cmd: 'reel-comments', ch, id })).list || []
    },
    async 'reels.repost'({ ch, id, on, cid, quote }) { needReels(); await reelCmd({ cmd: 'reel-repost', ch, id, on: !!on, cid: cid || null, quote: quote || '' }) },
    async 'reels.invite'({ id, who }) { needReels(); await reelCmd({ cmd: 'reel-invite', id, who }) },
    async 'reels.collabAccept'({ ch, id }) { needReels(); await reelCmd({ cmd: 'reel-collab-accept', ch, id }) },
    async 'reels.prefs'() { needReels(); return reelCmd({ cmd: 'reels-prefs' }) },
    async 'reels.setPrefs'({ weights, words }) { needReels(); await reelCmd({ cmd: 'reels-prefs-set', weights, words }) },
    async 'reels.notInterested'({ ch, id }) { needReels(); await reelCmd({ cmd: 'reel-notint', ch, id }) },
    async 'reels.hideCreator'({ ch, on }) { needReels(); await reelCmd({ cmd: 'reel-hidech', ch, on: !!on }) },
    async 'reels.setPrivate'({ id, on }) { needReels(); await reelCmd({ cmd: 'reel-private', id, on: !!on }) },
    // Live: going live is written to my Reels channel; video/audio/chat go over the live link.
    async 'reels.lives'() { needReels(); return (await reelCmd({ cmd: 'reels-lives' })).list },
    // Activity: likes, comments, replies, follows and reposts on my things, plus follows going live.
    async 'reels.activity'() { needReels(); return (await reelCmd({ cmd: 'reel-activity' })).list },
    async 'live.start'({ id, title }) { needReels(); await reelCmd({ cmd: 'reel-live', on: true, id, title }); cmd({ cmd: 'live-start', id, title }) },
    async 'live.stop'({ id }) { cmd({ cmd: 'live-stop' }); if (reelsCh) await reelCmd({ cmd: 'reel-live', on: false, id }) },
    async 'live.frame'({ kind, data }) { cmd({ cmd: 'live-frame', kind, data }) },
    async 'live.watch'({ host, id }) { cmd({ cmd: 'live-watch', host, id, name: (S().identity || {}).username || '' }) },
    async 'live.leave'({ host }) { cmd({ cmd: 'live-leave', host }) },
    async 'live.chat'({ host, text }) { cmd({ cmd: 'live-chat', host: host || '', text }) },
    async 'live.heart'({ host }) { cmd({ cmd: 'live-heart', host: host || '' }) },
    async 'live.keyreq'({ host }) { cmd({ cmd: 'live-keyreq', host }) },
    async 'reels.like'({ ch, id, on }) { needReels(); await reelCmd({ cmd: 'reel-like', ch, id, on: !!on }) },
    async 'reels.fav'({ ch, id, on }) { needReels(); await reelCmd({ cmd: 'reel-fav', ch, id, on: !!on }) },
    async 'reels.follow'({ ch, on }) { needReels(); await reelCmd({ cmd: 'reel-follow', ch, on: !!on }) },
    async 'reels.comment'({ ch, id, text, re }) { needReels(); return (await reelCmd({ cmd: 'reel-comment', ch, id, text: String(text || ''), re: re || null })).cid },
    /** Meshron AI's signed answer, posted under a comment (the worklet checks the signature). */
    async 'reels.aiComment'({ ch, id, text, re, sig }) { needReels(); return (await reelCmd({ cmd: 'reel-comment', ch, id, text: String(text || ''), re, ai: sig })).cid },
    /** Is this really Meshron AI's answer? (signed by its key) */
    async 'ai.verify'({ ref, re, text, sig }) {
      try { return sodium.crypto_sign_verify_detached(b4a.from(String(sig), 'hex'), b4a.from('meshron-ai-v1\n' + ref + '\n' + re + '\n' + text), b4a.from('1814b421f78a892ee898487af96ec45421cf50f996fae9ab4bf1f9754167824e', 'hex')) } catch { return false }
    },
    /** Following / followers / friends (people who follow each other). */
    async 'reels.people'({ kind }) { needReels(); return reelCmd({ cmd: 'reels-people', kind }) },
    /** Someone's Reels channel from their Meshron ID (to open their page). */
    async 'reels.chOf'({ id }) { needReels(); return (await reelCmd({ cmd: 'reels-ch-of', id })).ch || null },
    async 'reels.uncomment'({ ch, cid }) { needReels(); await reelCmd({ cmd: 'reel-uncomment', ch, cid }) },
    async 'reels.delete'({ id }) {
      needReels()
      await reelCmd({ cmd: 'reel-delete', id })
      try { fs.unlinkSync(reelFile(reelsCh, id)) } catch {}
    },
    /** Makes sure the video is on disk (downloading it from peers if needed); returns its size. */
    async 'reels.load'({ ch, id }) {
      needReels()
      const file = reelFile(ch, id)
      try { return { size: fs.statSync(file).size } } catch {}
      if (!reelLoads.has(file)) {
        reelLoads.set(file, (async () => {
          const chunks = await reelCmd({ cmd: 'reel-get', ch, id }, 240000)
          ensureDir(fs, reelsDir)
          const tmp = file + '.part'
          fs.writeFileSync(tmp, b4a.concat(chunks.map((d) => b4a.from(d, 'base64'))))
          fs.renameSync(tmp, file)
          trimReelCache()
        })().finally(() => reelLoads.delete(file)))
      }
      await reelLoads.get(file)
      return { size: fs.statSync(file).size }
    },
    async 'reels.read'({ ch, id, offset = 0, length = 1024 * 1024 }) {
      const file = reelFile(ch, id)
      const fd = fs.openSync(file, 'r')
      try {
        const buf = b4a.alloc(Math.min(Math.max(0, length | 0), 2 * 1024 * 1024))
        const n = fs.readSync(fd, buf, 0, buf.byteLength, offset)
        return { data: b4a.toString(buf.subarray(0, n), 'base64'), done: n < buf.byteLength }
      } finally {
        fs.closeSync(fd)
      }
    },
    async 'reels.begin'() {
      needReels()
      const xfer = 'x' + ++reelSeq
      cmd({ cmd: 'reel-begin', xfer })
      return xfer
    },
    async 'reels.chunk'({ xfer, data }) {
      cmd({ cmd: 'reel-chunk', xfer, data })
    },
    async 'reels.end'({ xfer, cap, dur, w, h, thumb, sound, title }) {
      needReels()
      const r = await reelCmd({ cmd: 'reel-end', xfer, cap, dur, w, h, thumb, sound, title }, 120000, xfer)
      return { ch: r.ch, id: r.id }
    },

    async init() {
      await load()
      startNetwork()
      startSweep()
      return {
        platform, anon, locked: !!S().lock, identity: identityView(), contacts: contactsView(), groups: groupsView(), summaries: summaries(),
        net, prefs: prefsView(), fileStatus, limits: { MAX_BODY, MAX_NAME, MAX_FILE }, stories: storiesView(),
        profiles: profilesView()
      }
    },

    /** My bio and/or photo (base64 JPEG; '' removes it; undefined keeps it). Contacts refresh it. */
    async 'profile.set'({ bio, photo, link }) {
      if (anon) throw new Error('Anonymous has no profile')
      const p = myProfile()
      if (typeof bio === 'string') p.bio = bio.trim().slice(0, 150)
      if (typeof link === 'string') {
        // A website on the profile: https only (like Android).
        const t = link.trim()
        const w = t && !/^https?:\/\//.test(t) ? 'https://' + t : t.replace(/^http:\/\//, 'https://')
        if (w && !/^https:\/\/[^\s/]+\.[^\s]+$/.test(w)) throw new Error("That doesn't look like a website")
        p.link = w.slice(0, 200)
      }
      if (typeof photo === 'string') {
        if (photo.length > 120 * 1024 || !/^[A-Za-z0-9+/=]*$/.test(photo)) throw new Error('That photo is too big')
        p.photo = photo
      }
      p.pv = (p.pv || 0) + 1
      saveState()
      cmd({ cmd: 'profile', ...p })
      reelsMe()
      emit('profiles', profilesView())
      return profilesView()
    },

    async 'identity.create'({ name, handle }) {
      const seed = b4a.alloc(32)
      sodium.randombytes_buf(seed)
      return createIdentity(name, seed, handle)
    },
    async 'identity.restore'({ key, name }) {
      const clean = String(key || '').toLowerCase().replace(/[\s-]/g, '')
      let seed = null
      try { seed = z32.decode(clean) } catch {}
      if (!seed || seed.byteLength !== 32 || z32.encode(seed) !== clean) throw new Error("That recovery key isn't valid")
      return createIdentity(name, b4a.from(seed), '')
    },
    async 'identity.rename'({ name }) {
      const n = cleanName(name)
      if (n.length < 1 || n.length > MAX_NAME) throw new Error('Name must be 1–' + MAX_NAME + ' characters')
      S().identity.username = n
      saveState()
      cmd({ cmd: 'name', name: n })
      reelsMe()
      emit('identity', identityView())
    },
    async 'identity.setHandle'({ handle }) {
      if (anon) throw new Error('Anonymous has no username')
      const h = cleanHandle(handle)
      if (h && !HANDLE.test(h)) throw new Error('Usernames are 3–24 letters, numbers or _')
      S().identity.handle = h
      saveState()
      cmd({ cmd: 'handle', handle: h })
      reelsMe()
      emit('identity', identityView())
    },
    async 'identity.recoveryKey'() {
      const seed = await secrets.get('seed')
      if (!seed) throw new Error('The private key is missing from this device')
      return z32.encode(b4a.from(seed, 'hex'))
    },

    async 'handle.find'({ handle }) {
      const h = cleanHandle(handle)
      if (!HANDLE.test(h)) return { keys: [], error: 'invalid' }
      if (net.status !== 'online') return { keys: [], error: 'offline' }
      const req = randomHex(8)
      const res = await new Promise((resolve) => {
        const timer = setTimeout(() => resolve({ keys: [], error: 'timeout' }), 15000)
        handleLookups.set(req, (r) => { clearTimeout(timer); resolve(r) })
        cmd({ cmd: 'find-handle', req, handle: h })
      })
      handleLookups.delete(req)
      return { ...res, keys: res.keys.filter((k) => k !== myKey) }
    },

    async 'contacts.parse'({ text }) {
      const p = parseContact(text)
      if (p.keyHex && p.keyHex === myKey) return { error: "That's your own ID" }
      return p
    },
    async 'contacts.add'({ key, name }) {
      if (!HEX64.test(key)) throw new Error('Bad key')
      if (key === myKey) throw new Error("That's your own ID")
      const list = S().contacts
      const known = net.peers[key] && net.peers[key].announcedName
      const n = cleanName(name).slice(0, 64) || known || ''
      const i = list.findIndex((c) => c.keyHex === key)
      if (i >= 0) list[i] = { ...list[i], status: 'ADDED', name: n || list[i].name }
      else list.push({ keyHex: key, name: n, status: 'ADDED', addedAt: now(), handle: '', verified: false })
      contactsChanged()
      retryDownloads() // accepting a request unlocks files they already sent
      shareLiveStoriesWith(key).catch(() => {})
    },
    async 'contacts.remove'({ key }) {
      S().contacts = S().contacts.filter((c) => c.keyHex !== key)
      contactsChanged()
    },
    async 'contacts.block'({ key }) {
      if (!mutateContact(key, (c) => ({ ...c, status: 'BLOCKED' }))) {
        S().contacts.push({ keyHex: key, name: '', status: 'BLOCKED', addedAt: now(), handle: '', verified: false })
        contactsChanged()
      }
    },
    async 'contacts.unblock'({ key }) {
      mutateContact(key, (c) => (c.status === 'BLOCKED' ? { ...c, status: 'ADDED' } : c))
    },
    async 'contacts.setVerified'({ key, verified }) {
      mutateContact(key, (c) => ({ ...c, verified: !!verified }))
    },
    async 'contacts.safetyCode'({ key }) {
      return safetyCode(key)
    },

    async 'chat.open'({ conv, all }) {
      return all ? chat(conv).rows.slice() : chat(conv).rows.filter(visible)
    },
    /** Text messages containing [q], newest first, across every chat. */
    async 'chat.search'({ q, limit = 40 }) {
      const needle = String(q || '').trim().toLowerCase()
      if (needle.length < 2) return []
      const hits = []
      for (const r of allRows()) if (r.kind === 'text' && r.body.toLowerCase().includes(needle)) hits.push(r)
      return hits.sort((a, b) => b.localTime - a.localTime).slice(0, limit)
    },
    async 'chat.send'({ conv, text, replyTo, urgent }) {
      const clean = String(text || '').trim()
      if (!clean) return
      const quoted = replyTo && chat(conv).rows.find((r) => r.id === replyTo && visible(r) && r.kind !== 'deleted')
      const body = (quoted ? '↩ ' + quoteOf(quoted) + '\n' + clean : clean).slice(0, MAX_BODY)
      const row = sendRow(conv, body, 'text', null, !!urgent)
      return row ? row.id : null
    },
    /** SOS: an urgent message to every added contact. Returns how many. */
    async 'sos.send'({ text }) {
      const body = String(text || '').trim().slice(0, MAX_BODY)
      if (!body) return 0
      const to = S().contacts.filter((c) => c.status === 'ADDED')
      for (const c of to) sendRow(c.keyHex, body, 'text', null, true)
      return to.length
    },
    /** Changes the text of my own message on every device that has it. */
    async 'chat.edit'({ conv, id, text }) {
      const m = chat(conv).rows.find((r) => r.id === id)
      const clean = String(text || '').trim().slice(0, MAX_BODY)
      if (!m || !m.outgoing || m.kind !== 'text' || !clean || clean === bodyText(m.body)) return
      const q = REPLY.exec(m.body)
      const body = (q ? '↩ ' + q[1] + '\n' + clean : clean).slice(0, MAX_BODY)
      sendRow(conv, JSON.stringify({ id: wireId(m), text: body }), 'edit')
    },
    /** Deletes my own message for everyone. */
    async 'chat.unsend'({ conv, id }) {
      const m = chat(conv).rows.find((r) => r.id === id)
      if (!m || !m.outgoing || !UNSENDABLE.has(m.kind)) return
      sendRow(conv, JSON.stringify({ id: wireId(m) }), 'unsend')
    },
    /** Sets (or with e = '', removes) my reaction to a message. */
    async 'chat.react'({ conv, id, e }) {
      const m = chat(conv).rows.find((r) => r.id === id)
      if (!m || m.kind === 'deleted') return
      sendRow(conv, JSON.stringify({ id: m.id, e: String(e || '').slice(0, 16) }), 'react')
    },
    /** "typing…" for the other person in a 1:1 chat (live only). */
    async 'chat.typing'({ conv, what }) {
      if (anon) return
      if (net.status === 'online' && HEX64.test(conv)) cmd({ cmd: 'typing', key: conv, what: what === 'recording' ? 'recording' : 'typing' })
    },
    async 'stories.list'() {
      sweepStories()
      return storiesView()
    },
    /** Posts a text or photo story to every added contact. Photos arrive as base64 pieces via file.begin/chunk. */
    async 'stories.post'({ type, text, bg, upload }) {
      if (net.status !== 'online') throw new Error('The network is still starting. Try again in a moment.')
      const audience = storyAudience()
      if (!audience.length) throw new Error('Add a contact first. Stories are shown to your contacts.')
      const sid = randomHex(16)
      const exp = now() + STORY_TTL
      const clean = String(text || '').trim().slice(0, 700)
      if (type === 'photo') {
        const f = incoming.get(upload)
        if (!f) throw new Error('The photo did not arrive')
        incoming.delete(upload)
        const data = b4a.concat(f.chunks)
        const local = path.join(sentDir, sid + '-' + safeName(f.name))
        fs.writeFileSync(local, data)
        for (const key of audience) {
          const info = await uploadBlob(key, f.name, f.mime, data, local)
          sendRow(key, JSON.stringify({ sid, type: 'photo', text: clean, file: info, exp }), 'story', local)
        }
      } else {
        if (!clean) throw new Error('Write something first')
        for (const key of audience) sendRow(key, JSON.stringify({ sid, type: 'text', text: clean, bg: bg | 0, exp }), 'story')
      }
      storiesChanged()
      return sid
    },
    async 'stories.seen'({ key, sid }) {
      const k = key + '|' + sid
      if (S().seenStories.includes(k)) return
      S().seenStories.push(k)
      saveState()
      sendRow(key, JSON.stringify({ sid }), 'seen')
      storiesChanged()
    },
    async 'stories.delete'({ sid }) {
      for (const key of storyAudience()) sendRow(key, JSON.stringify({ sid, type: 'delete' }), 'story')
      storiesChanged()
    },
    async 'stories.photo'({ conv, id }) {
      const row = chat(conv).rows.find((r) => r.id === id)
      if (!row || !row.localPath) throw new Error('Not downloaded yet')
      return { data: b4a.toString(fs.readFileSync(row.localPath), 'base64') }
    },
    async 'chat.note'({ conv, text, outgoing }) { if (HEX64.test(conv)) noteRow(conv, text, outgoing) },
    /** A call I made, sent as a message so the other person sees it (missed or not) when next online. */
    async 'chat.call'({ conv, video, result, seconds }) {
      if (!HEX64.test(conv)) return
      const r = ['answered', 'declined', 'noanswer'].includes(result) ? result : 'noanswer'
      sendRow(conv, JSON.stringify({ v: !!video, r, d: Math.max(0, Math.round(+seconds || 0)) }), 'call')
    },
    async 'chat.markRead'({ conv }) {
      const c = chat(conv)
      const rows = c.rows.filter((r) => !r.outgoing && !r.read)
      if (!rows.length) return
      for (const r of rows) r.read = true
      c.file.save()
      changed(conv, rows)
      // Read receipt: tell them (1:1, while connected), like Android.
      if (HEX64.test(conv) && net.status === 'online') {
        const top = Math.max(-1, ...c.rows.filter((x) => !x.outgoing && x.seq != null).map((x) => x.seq))
        if (top >= 0) cmd({ cmd: 'read', key: conv, length: top + 1 })
      }
    },
    async 'chat.deleteMessage'({ conv, id }) {
      const c = chat(conv)
      const i = c.rows.findIndex((r) => r.id === id)
      if (i < 0) return
      keepReadPosition(conv)
      const [row] = c.rows.splice(i, 1)
      if (row.localPath) try { fs.unlinkSync(row.localPath) } catch {}
      c.file.save()
      emit('msgs-removed', { conv, ids: [id] })
      changed(conv)
    },
    async 'chat.deleteConversation'({ conv }) {
      const c = chat(conv)
      keepReadPosition(conv)
      for (const r of c.rows) if (r.localPath) try { fs.unlinkSync(r.localPath) } catch {}
      c.rows.length = 0
      c.file.save()
      emit('msgs-removed', { conv, all: true })
      changed(conv)
    },

    // ---- Moderation, badges, verification (the screens call the account server; only
    // the signature is made here, because this is where the identity key lives) ----
    async 'mod.sign'({ path: p, body }) {
      if (anon) throw new Error('Not available in anonymous mode')
      if (typeof p !== 'string' || !p.startsWith('/v1/') || typeof body !== 'string' || body.length > 1200000) throw new Error('Bad request')
      const seed = await secrets.get('seed')
      if (!seed || !HEX64.test(seed)) throw new Error('The private key is missing from this device')
      const pk = b4a.alloc(sodium.crypto_sign_PUBLICKEYBYTES)
      const sk = b4a.alloc(sodium.crypto_sign_SECRETKEYBYTES)
      sodium.crypto_sign_seed_keypair(pk, sk, b4a.from(seed, 'hex'))
      const ts = now()
      const digest = b4a.alloc(sodium.crypto_hash_sha256_BYTES)
      sodium.crypto_hash_sha256(digest, b4a.from(body))
      const sig = b4a.alloc(sodium.crypto_sign_BYTES)
      sodium.crypto_sign_detached(sig, b4a.from('meshron-mod-v1\n' + p + '\n' + ts + '\n' + hex(digest)), sk)
      sk.fill(0)
      return { id: hex(pk), ts, sig: hex(sig), p: body }
    },
    /** Who may comment on my reels: everyone / followers / friends / off. */
    async 'reels.cmFrom'({ v }) { needReels(); await reelCmd({ cmd: 'reel-cmfrom', v }) },

    async 'group.info'({ id, name, bio, icon }) { groupMeta(id, { op: 'info', name: String(name || '').slice(0, 48), bio: String(bio || '').slice(0, 200), icon: String(icon || '').slice(0, 24000) }) },
    async 'group.admin'({ id, who, on }) { groupMeta(id, { op: 'admin', who, on: !!on }) },
    async 'group.remove'({ id, who }) { groupMeta(id, { op: 'remove', who }) },
    async 'group.owner'({ id, who }) { groupMeta(id, { op: 'owner', who }) },
    async 'group.leave'({ id }) {
      const g = S().groups.find((x) => x.id === id)
      if (g && ownerOf(g) === myKey && g.members.length > 1) throw new Error('Make someone else the owner first')
      groupMeta(id, { op: 'leave' })
    },
    async 'group.add'({ id, members }) {
      const g = S().groups.find((x) => x.id === id)
      if (!g) throw new Error('No such group')
      const fresh = [...new Set((members || []).filter((k) => HEX64.test(k) && !g.members.includes(k)))]
      if (!fresh.length) return
      groupMeta(id, { op: 'add', who: fresh })
      const inv = inviteJson(g)
      for (const m of fresh) sendRow(m, inv, 'invite')
    },
    async 'group.deleteForEveryone'({ conv, id }) {
      if (!isGroup(conv)) throw new Error('Not a group')
      groupMeta(conv.slice(GROUP.length), { op: 'del', id })
    },

    async 'group.create'({ name, members, adult }) {
      if (adult && !isAdult()) throw new Error('Only adults can make 18+ groups')
      const keys = [...new Set((members || []).filter((k) => HEX64.test(k) && k !== myKey))]
      const g = { id: randomHex(16), name: String(name || '').trim().slice(0, 48) || 'Group', secret: randomHex(32), creatorKey: myKey, members: [myKey, ...keys], createdAt: now(), adult: !!adult }
      S().groups.push(g)
      saveState()
      emit('groups', groupsView())
      if (net.status === 'online') openGroup(g)
      const invite = inviteJson(g)
      for (const m of keys) sendRow(m, invite, 'invite')
      return GROUP + g.id
    },

    // Files arrive from the screens in base64 pieces, so no single message gets huge.
    async 'file.begin'({ conv, name, mime, size }) {
      if (size > MAX_FILE) throw new Error('Files can be up to 25 MB')
      const id = randomHex(8)
      incoming.set(id, { conv, name: String(name || 'file').slice(0, 200), mime, chunks: [], size: 0 })
      return id
    },
    async 'file.chunk'({ id, data }) {
      const f = incoming.get(id)
      if (!f) throw new Error('Unknown upload')
      const buf = b4a.from(data, 'base64')
      f.size += buf.byteLength
      if (f.size > MAX_FILE) {
        incoming.delete(id)
        throw new Error('Files can be up to 25 MB')
      }
      f.chunks.push(buf)
    },
    async 'file.end'({ id }) {
      const f = incoming.get(id)
      if (!f) throw new Error('Unknown upload')
      incoming.delete(id)
      await sendFile(f.conv, f.name, f.mime, b4a.concat(f.chunks))
    },
    async 'file.cancel'({ id }) {
      incoming.delete(id)
    },
    /** One piece of a stored file (sent or downloaded and verified), for showing or saving it. */
    async 'file.read'({ conv, id, offset = 0, length = 1024 * 1024 }) {
      const row = chat(conv).rows.find((r) => r.id === id)
      if (!row || !row.localPath) throw new Error('That file is not on this device yet')
      const data = fs.readFileSync(row.localPath)
      const end = Math.min(data.byteLength, offset + length)
      return { size: data.byteLength, data: b4a.toString(data.subarray(offset, end), 'base64'), done: end >= data.byteLength }
    },
    async 'file.path'({ conv, id }) {
      const row = chat(conv).rows.find((r) => r.id === id)
      return row && row.localPath ? row.localPath : null
    },
    async 'file.retry'() {
      retryDownloads()
    },

    /** Switches between the public and the anonymous identity; the app then restarts. */
    async 'mode.switch'({ anon: to }) {
      ensureDir(fs, rootDir)
      fs.writeFileSync(modeFile, JSON.stringify({ anon: !!to }))
      if (state) state.flush()
      for (const c of chats.values()) if (c.file.timer) c.file.flush()
      setTimeout(() => emit('restart', { anon: !!to }), 50)
      return true
    },
    /**
     * Log out · switch account: clears this device (chats, files, keys, settings, both identities)
     * and starts again at the welcome screen. The Meshron account itself stays on the server.
     */
    async 'app.logout'() {
      for (const n of ['seed', 'storage-key', 'account']) { await baseSecrets.set(n, ''); await baseSecrets.set('anon.' + n, '') }
      ensureDir(fs, rootDir)
      fs.writeFileSync(wipeFile, '1')
      setTimeout(() => emit('restart', { anon: false, logout: true }), 50)
      return true
    },
    /** App lock: a 6-digit PIN asked for when Meshron opens. '' removes it. */
    /** The birthday (yyyy-mm-dd), asked once; null when not given yet. */
    async 'age.get'() { return { birth: S().birth || null, years: ageYears(), adult: isAdult() } },
    async 'age.set'({ birth }) {
      const d = new Date(String(birth || ''))
      if (isNaN(d) || d > new Date() || d.getFullYear() < new Date().getFullYear() - 120) throw new Error('Check the date')
      S().birth = d.toISOString().slice(0, 10)
      if ((ageYears() || 0) < 13) { delete S().birth; throw new Error('You must be at least 13 to use Meshron') }
      saveState()
      return { birth: S().birth, years: ageYears(), adult: isAdult() }
    },
    async 'lock.set'({ pin }) {
      if (!pin) delete S().lock
      else {
        if (!/^[0-9]{6}$/.test(pin)) throw new Error('The PIN is 6 digits')
        const salt = randomHex(16)
        S().lock = salt + ':' + pinHash(salt, pin)
      }
      state.flush()
      return !!S().lock
    },
    async 'lock.check'({ pin }) {
      const l = S().lock
      if (!l) return true
      const [salt, h] = l.split(':')
      return pinHash(salt, String(pin || '')) === h
    },

    async 'prefs.set'({ key, value }) {
      if (!(key in DEFAULT_SETTINGS)) throw new Error('Unknown setting')
      S().settings[key] = value
      saveState()
      if (key === 'useInternet') cmd({ cmd: 'internet', on: !!value })
      if (key === 'carry') cmd({ cmd: 'carry', on: !!value })
      emit('prefs', prefsView())
    },
    async 'prefs.pin'({ conv, on }) {
      S().pinned = S().pinned.filter((c) => c !== conv)
      if (on) S().pinned.unshift(conv)
      saveState()
      emit('prefs', prefsView())
    },
    async 'prefs.mute'({ conv, on }) {
      S().muted = S().muted.filter((c) => c !== conv)
      if (on) S().muted.push(conv)
      saveState()
      emit('prefs', prefsView())
    },
    async 'prefs.draft'({ conv, text }) {
      if (text) S().drafts[conv] = String(text).slice(0, MAX_BODY)
      else delete S().drafts[conv]
      saveState()
    },

    // Calls: signalling and media packets pass straight through to the worklet.
    async 'call.send'({ key, msg }) {
      cmd({ cmd: 'call-send', key, msg })
    },
    async 'call.audio'({ key, data }) {
      cmd({ cmd: 'call-audio', key, data })
    },
    async 'call.video'({ key, data }) {
      cmd({ cmd: 'call-video', key, data })
    },

    // Local links: a platform transport (same Wi-Fi / Bluetooth) carries raw bytes; the worklet
    // runs the same Noise handshake over them as over the internet, so identity is proven inside.
    async 'link.open'({ via, initiator }) {
      if (!ipc) throw new Error('Not online yet')
      const id = randomHex(8)
      cmd({ cmd: 'link-open', id, via: via === 'nearby' ? 'nearby' : 'lan', initiator: !!initiator })
      return id
    },
    async 'link.data'({ id, data }) {
      cmd({ cmd: 'link-data', id, data })
    },
    async 'link.close'({ id }) {
      cmd({ cmd: 'link-close', id })
    },
    /** How many other people's sealed logs this device carries on (shown in Connection). */
    async 'net.carried'() {
      return S().carried.length
    },
    async 'net.changed'() {
      if (net.status === 'online') cmd({ cmd: 'network-change' })
    }
  }

  return {
    async call(method, args) {
      const fn = methods[method]
      if (!fn) throw new Error('Unknown method ' + method)
      if (method !== 'init') await load()
      return fn(args || {})
    },
    /** App going to the background (iPhone) or closing: write everything out. */
    flush() {
      if (state) state.flush()
      for (const c of chats.values()) if (c.file.timer) c.file.flush()
    },
    suspend() {
      this.flush()
      cmd({ cmd: 'suspend' })
    },
    resume() {
      cmd({ cmd: 'resume' })
    }
  }
}

module.exports = { createHost }
