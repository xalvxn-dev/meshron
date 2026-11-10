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
const HANDLE = /^[a-z0-9_]{3,20}$/
const LINK = /meshron:\/\/id\/([a-z0-9]+)(?:\?name=(\S*))?/
const BARE_ID = /^[ybndrfg8ejkmcpqxot1uwisza345h769]{52}$/

const PENDING = 0 // saved, not yet in my log
const SENT = 1 // appended to my log; replicates when peers are online
const DELIVERED = 2 // 1:1 only: peer stored it and acknowledged
const RECEIVED = 3 // incoming
const GROUP = 'g:'

const MAX_BODY = 4000
const MAX_NAME = 32
const MAX_FILE = 25 * 1024 * 1024
const MAX_REQUESTS = 30
const FILE_CHUNK = 48 * 1024

const DEFAULT_SETTINGS = { useInternet: true, carry: true, notifications: true, sounds: true, enterSends: true, previews: true }

function createHost({ fs, path, dataDir, secrets, loadWorklet, emit, platform }) {
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
          carried: [], settings: { ...DEFAULT_SETTINGS }, pinned: [], muted: [], drafts: {}
        }), box)
        const s = state.value
        s.settings = { ...DEFAULT_SETTINGS, ...(s.settings || {}) }
        for (const k of ['pinned', 'muted', 'carried', 'contacts', 'groups']) if (!Array.isArray(s[k])) s[k] = []
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
    c.file.save()
    changed(row.peerKey, [row])
    return true
  }

  // Tell the screens about changed rows; the chat list is recomputed (debounced).
  let summaryTimer = null
  function changed(conv, rows) {
    if (rows && rows.length) emit('msgs', { conv, rows })
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
      for (const r of c.rows) if (!r.outgoing && !r.read) unread++
      out.push({ conv, last: c.rows[c.rows.length - 1], unread })
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

  async function startNetwork() {
    if (workletStarted || !S().identity) return
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
        cmd: 'start', seed, name: i.username, handle: i.handle || '', storage: path.join(dataDir, 'corestore'),
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
      cmd({ cmd: 'append', key: row.peerKey, id: row.id, body: row.body, ts: row.sentAt, kind: row.kind })
    }
  }

  async function onEvent(e) {
    switch (e.ev) {
      case 'ready': {
        net.keyMatches = e.publicKey === myKey
        if (!net.keyMatches) return fail('Worklet derived a different key, refusing to continue')
        net.status = 'online'
        log('Network ready')
        syncContacts()
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
        if (!S().carried.some((r) => r.k === e.k)) {
          S().carried.push({ k: e.k, t: e.t })
          saveState()
        }
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
      if (!known && via === 'internet') {
        // Strangers can make endless new IDs, so cap how many requests we keep.
        if (list.filter((c) => c.status === 'REQUEST').length >= MAX_REQUESTS) {
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
      signature: e.sig, read: false, seq: e.seq, senderKey: null, kind: e.kind || 'text', localPath: null
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
      localTime: now(), status: RECEIVED, signature: e.sig, read: false, seq: e.seq, senderKey: e.from,
      kind: e.kind || 'text', localPath: null
    }
    if (insertRow(row)) afterIncoming(row, g.name)
    cmd({ cmd: 'group-ack', group: e.group, member: e.from, seq: e.seq })
  }

  function afterIncoming(row, title) {
    if (row.kind === 'file') download(row)
    if (!S().muted.includes(row.peerKey)) emit('incoming', { conv: row.peerKey, row, title })
  }

  function parseInvite(json) {
    try {
      const o = JSON.parse(json)
      const members = Array.isArray(o.members) ? o.members : null
      if (!HEX32.test(o.id) || !HEX64.test(o.secret) || !HEX64.test(o.creator) || !members) return null
      if (members.some((m) => !HEX64.test(m)) || !members.includes(o.creator) || members.length > 64) return null
      return {
        id: o.id, name: String(o.name || '').slice(0, 48).trim() || 'Group', secret: o.secret, creatorKey: o.creator,
        members: [...new Set(members)], createdAt: now()
      }
    } catch {
      return null
    }
  }

  function acceptInvite(from, json) {
    const g = parseInvite(json)
    if (!g || !g.members.includes(from) || !g.members.includes(myKey)) return false
    if (!S().groups.some((x) => x.id === g.id)) {
      S().groups.push(g)
      saveState()
      emit('groups', groupsView())
      openGroup(g)
    }
    return true
  }

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
    const info = parseFileInfo(row.body)
    if (!info) return setFileStatus(row, 'Invalid file')
    if (info.size > MAX_FILE) return setFileStatus(row, 'Too large')
    if (!mayDownload(row)) return setFileStatus(row, 'Accept this contact to download')
    const req = randomHex(8)
    downloads.set(req, { row, info, chunks: [], bytes: 0 })
    setFileStatus(row, 'Downloading…')
    cmd({ cmd: 'file-get', req, conv: wireConv(row.peerKey), from: row.senderKey || row.peerKey, blobs: info.blobs, blob: info.blob })
  }

  function retryDownloads() {
    for (const r of allRows()) if (r.kind === 'file' && !r.outgoing && !r.localPath) download(r)
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
  }

  /** Stores bytes in my blob log, then sends the file message. */
  async function sendFile(conv, name, mime, data) {
    if (net.status !== 'online') throw new Error('The network is still starting. Try again in a moment.')
    if (data.byteLength > MAX_FILE) throw new Error('Files can be up to 25 MB')
    if (!data.byteLength) throw new Error("That file is empty")
    const id = randomHex(16)
    const local = path.join(sentDir, id + '-' + safeName(name))
    fs.writeFileSync(local, data)
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
      const body = JSON.stringify({ name, mime: mime || 'application/octet-stream', size: data.byteLength, sha256: sha256(data), blobs: stored.blobs, blob: stored.blob })
      sendRow(conv, body, 'file', local)
    } catch (err) {
      try { fs.unlinkSync(local) } catch {}
      throw err
    } finally {
      uploads.delete(id)
    }
  }

  // ---- Sending ------------------------------------------------------------------------

  function sendRow(conv, body, kind, localPath) {
    const t = now()
    const wid = randomHex(16)
    const group = isGroup(conv)
    const row = {
      peerKey: conv, id: group ? groupRowId(myKey, wid) : wid, outgoing: true, body, sentAt: t, localTime: t,
      status: PENDING, signature: null, read: true, seq: null, senderKey: group ? myKey : null, kind, localPath: localPath || null
    }
    insertRow(row)
    if (net.status === 'online') appendRow(row)
    return row
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
    const h = cleanHandle(handle)
    if (h && !HANDLE.test(h)) throw new Error('Usernames are 3–20 letters, numbers or _')
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

  const methods = {
    async init() {
      await load()
      startNetwork()
      return {
        platform, identity: identityView(), contacts: contactsView(), groups: groupsView(), summaries: summaries(),
        net, prefs: prefsView(), fileStatus, limits: { MAX_BODY, MAX_NAME, MAX_FILE }
      }
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
      emit('identity', identityView())
    },
    async 'identity.setHandle'({ handle }) {
      const h = cleanHandle(handle)
      if (h && !HANDLE.test(h)) throw new Error('Usernames are 3–20 letters, numbers or _')
      S().identity.handle = h
      saveState()
      cmd({ cmd: 'handle', handle: h })
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

    async 'chat.open'({ conv }) {
      return chat(conv).rows
    },
    /** Text messages containing [q], newest first, across every chat. */
    async 'chat.search'({ q, limit = 40 }) {
      const needle = String(q || '').trim().toLowerCase()
      if (needle.length < 2) return []
      const hits = []
      for (const r of allRows()) if (r.kind === 'text' && r.body.toLowerCase().includes(needle)) hits.push(r)
      return hits.sort((a, b) => b.localTime - a.localTime).slice(0, limit)
    },
    async 'chat.send'({ conv, text }) {
      const body = String(text || '').trim().slice(0, MAX_BODY)
      if (!body) return
      sendRow(conv, body, 'text')
    },
    async 'chat.markRead'({ conv }) {
      const c = chat(conv)
      const rows = c.rows.filter((r) => !r.outgoing && !r.read)
      if (!rows.length) return
      for (const r of rows) r.read = true
      c.file.save()
      changed(conv, rows)
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

    async 'group.create'({ name, members }) {
      const keys = [...new Set((members || []).filter((k) => HEX64.test(k) && k !== myKey))]
      const g = { id: randomHex(16), name: String(name || '').trim().slice(0, 48) || 'Group', secret: randomHex(32), creatorKey: myKey, members: [myKey, ...keys], createdAt: now() }
      S().groups.push(g)
      saveState()
      emit('groups', groupsView())
      if (net.status === 'online') openGroup(g)
      const invite = JSON.stringify({ id: g.id, name: g.name, secret: g.secret, creator: g.creatorKey, members: g.members })
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
