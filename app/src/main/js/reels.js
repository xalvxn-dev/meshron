// Reels: short public videos, peer to peer (part of the Meshron worklet, see app.js).
//
// Every public user has a "channel": an append-only Hypercore everyone can read, plus a
// Hyperblobs core with the videos. Both are derived from the identity seed, so restoring an
// ID brings the channel back (followers keep copies). Channel entries (JSON):
//   { t: 'me', id, sig, name, handle, bio, photo, following }   who runs it (sig = identity
//                                                                signs the channel key)
//   { t: 'reel', id, ts, cap, dur, w, h, thumb, blobs, blob, sound: { ch, id, title } | null, title }
//   { t: 'del', id }                    reel removed
//   { t: 'l', id, by, on }              like (written by the author when a viewer likes)
//   { t: 'c', reel, cid, by, name, text, ts }   comment (written by the author)
//   { t: 'cdel', cid }                  comment removed
//   { t: 'f', by, on }                  follower
//   { t: 'v', id, n }                   view count
// Viewers never write to someone else's channel. They keep what they want (likes, follows,
// pending comments) in their own private core and send it to the author as a 'sync' when
// they're connected; the author appends whatever changed. So likes and comments arrive even
// if the author was offline when they were made.
//
// Finding channels: everyone with Reels on joins the topic 'meshron/reels/v1'; connected
// peers swap channel keys ('hi'), so each new person learns about many. Reels links never
// show up as contacts or requests.
//
// Peer <-> peer: Protomux channel 'meshron/reels/1' (JSON):
//   { t: 'hi', ch, known: [ch…] }      { t: 'sync', ch, likes: [id], follow, comments: [{ reel, cid, text, ts, name }], uncomment: [cid], views: [id] }
//   { t: 'synced', ch }
// Host <-> worklet commands start with 'reel' (see handle()).

const TOPIC = 'meshron/reels/v1'
const MAX_KNOWN = 400
const MAX_ENTRY = 96 * 1024
const MAX_VIDEO = 25 * 1024 * 1024
const MAX_THUMB = 40 * 1024
const MAX_PHOTO = 60 * 1024
const MAX_COMMENT = 300
// Meshron AI's own account: its answers carry a signature by this key (checked everywhere).
const AI_ID = '1814b421f78a892ee898487af96ec45421cf50f996fae9ab4bf1f9754167824e'
const MAX_AI = 1500
const HEX64 = /^[0-9a-f]{64}$/
const ID = /^[0-9a-f]{16,32}$/

/** A profile website: https only, short. */
function cleanLink (v) {
  if (typeof v !== 'string') return ''
  const t = v.trim().slice(0, 200)
  return /^https:\/\/[^\s/]+\.[^\s]+$/.test(t) ? t : ''
}

const LIVE_MAX = 4 * 60 * 60 * 1000 // a live that never said it ended counts as over after 4 h

module.exports = function createReels (x) {
  const { b4a, crypto, sodium, Hyperblobs, send, log, report } = x
  /** True when [sig] is Meshron AI's signature on this answer ([ref] = "<channel>:<reel>"). */
  function aiOk (ref, re, text, sig) {
    if (typeof sig !== 'string' || !/^[0-9a-f]{128}$/.test(sig) || typeof re !== 'string' || typeof text !== 'string') return false
    try { return crypto.verify(b4a.from('meshron-ai-v1\n' + ref + '\n' + re + '\n' + text), b4a.from(sig, 'hex'), b4a.from(AI_ID, 'hex')) } catch { return false }
  }
  const hex = (b) => b4a.toString(b, 'hex')
  const now = () => Date.now()
  const rid = () => { const b = b4a.alloc(12); sodium.randombytes_buf(b); return hex(b) }
  const hash = (s) => { const out = b4a.alloc(32); sodium.crypto_generichash(out, b4a.from(s)); return out }

  let on = false
  // Nearby suggestions: a rough area cell (geohash, ~5 km). Only name and @username are swapped.
  let nearCell = null
  let nearTopic = null
  let me = { name: '', handle: '' }
  let mine = null // { ch, chKey, blobsCore, blobs, priv }
  const chans = new Map() // chHex -> channel state
  const byId = new Map() // identity hex -> chHex
  const conns = new Map() // identity hex -> Set<{ conn, message }>
  const dialing = new Set() // identity keys we dialled for Reels only
  // Feed settings: hidden creators, muted words, "not interested", and how For You ranks.
  const DEFAULT_WEIGHTS = { fresh: 5, popular: 5, following: 5, interests: 5, explore: 5 }
  const prefs = { weights: { ...DEFAULT_WEIGHTS }, words: [], hiddenCh: new Set(), notInt: new Set(), notIntTags: new Map() }
  const tagsOf = (text) => (String(text || '').toLowerCase().match(/#[\p{L}\p{N}_]+/gu) || [])
  const privates = new Map() // id -> reel entry kept only for me (encrypted on this phone)
  const reposts = new Map() // 'ch:id' -> { ts, cid, quote } (my reposts)
  const invited = new Set() // 'id:identityHex' (collaborators I invited to my reels)
  const state = { likes: new Set(), favs: new Map(), follows: new Set(), known: new Set(), pending: new Map(), uncomment: new Map(), seen: new Set() }
  const views = new Map() // 'ch:id' -> I watched it (sent with the next sync)
  const xfers = new Map()
  let seenBatch = []
  let changedTimer = null
  const changedChans = new Set()

  // ---- My channel --------------------------------------------------------------------

  async function open () {
    const seed = x.seed()
    const store = x.store()
    const kp = crypto.keyPair(hash('meshron-reels-channel:' + hex(seed)).subarray(0, 32))
    const bkp = crypto.keyPair(hash('meshron-reels-blobs:' + hex(seed)).subarray(0, 32))
    const ch = store.get({ keyPair: kp })
    const blobsCore = store.get({ keyPair: bkp })
    const priv = store.get({ name: 'reels-private', encryption: { key: hash('meshron-reels-private:' + hex(seed)) } })
    // Private videos: never shared (not in the channel, not announced, encrypted at rest).
    const privBlobsCore = store.get({ name: 'reels-private-blobs', encryption: { key: hash('meshron-reels-private-blobs:' + hex(seed)) } })
    await Promise.all([ch.ready(), blobsCore.ready(), priv.ready(), privBlobsCore.ready()])
    mine = { ch, chKey: hex(ch.key), blobsCore, blobs: new Hyperblobs(blobsCore), priv, privBlobsCore, privBlobs: new Hyperblobs(privBlobsCore) }
    // What I like / follow / favourite, as I left it.
    for (let i = 0; i < priv.length; i++) {
      const e = json(await priv.get(i))
      if (e) applyPrivate(e)
    }
    await watch(mine.chKey, ch)
  }

  function applyPrivate (e) {
    const k = e.ch + ':' + e.id
    if (e.t === 'like') e.on ? state.likes.add(k) : state.likes.delete(k)
    else if (e.t === 'fav') e.on ? state.favs.set(k, e.ts || 0) : state.favs.delete(k)
    else if (e.t === 'follow') e.on ? state.follows.add(e.ch) : state.follows.delete(e.ch)
    else if (e.t === 'known') state.known.add(e.ch)
    else if (e.t === 'pc') { const m = state.pending.get(e.ch) || new Map(); m.set(e.cid, e); state.pending.set(e.ch, m) }
    else if (e.t === 'pcd') { const s = state.uncomment.get(e.ch) || new Set(); s.add(e.cid); state.uncomment.set(e.ch, s) }
    else if (e.t === 'seen' && Array.isArray(e.ids)) for (const s of e.ids) state.seen.add(s)
    else if (e.t === 'pv' && e.reel) privates.set(e.reel.id, e.reel)
    else if (e.t === 'rpst') e.on ? reposts.set(e.ch + ':' + e.id, { ts: e.ts || 0, cid: e.cid || null, quote: e.quote || '' }) : reposts.delete(e.ch + ':' + e.id)
    else if (e.t === 'inv') invited.add(e.id + ':' + e.who)
    else if (e.t === 'cmfrom' && ['everyone', 'followers', 'friends', 'off'].includes(e.v)) prefs.cmFrom = e.v
    else if (e.t === 'prefs') {
      if (e.weights && typeof e.weights === 'object') for (const k of Object.keys(DEFAULT_WEIGHTS)) if (Number.isFinite(e.weights[k])) prefs.weights[k] = Math.max(0, Math.min(10, e.weights[k]))
      if (Array.isArray(e.words)) prefs.words = e.words.map((w) => String(w).toLowerCase().trim()).filter(Boolean).slice(0, 100)
    } else if (e.t === 'hidech') e.on ? prefs.hiddenCh.add(e.ch) : prefs.hiddenCh.delete(e.ch)
    else if (e.t === 'notint') {
      prefs.notInt.add(e.ch + ':' + e.id)
      for (const t of e.tags || []) prefs.notIntTags.set(t, (prefs.notIntTags.get(t) || 0) + 1)
    }
    else if (e.t === 'pvd') privates.delete(e.id)
  }
  async function savePrivate (e) {
    applyPrivate(e)
    await mine.priv.append(b4a.from(JSON.stringify(e)))
  }

  async function publishMe ({ name, handle, bio, photo, link, force } = {}) {
    if (name != null) me.name = String(name).slice(0, 32)
    if (handle != null) me.handle = String(handle)
    if (!mine) return
    const kp = x.keyPair()
    const cur = (chans.get(mine.chKey) || {}).profile || {}
    const next = {
      t: 'me', id: hex(kp.publicKey),
      name: String(name != null ? name : cur.name || '').slice(0, 32),
      handle: String(handle != null ? handle : cur.handle || '').slice(0, 24),
      bio: String(bio != null ? bio : cur.bio || '').slice(0, 150),
      photo: typeof photo === 'string' && photo.length <= MAX_PHOTO ? photo : (photo === undefined ? cur.photo || '' : ''),
      following: state.follows.size,
      cm: prefs.cmFrom || 'everyone', // who may comment (shown to viewers; enforced by me)
      link: typeof link === 'string' ? cleanLink(link) : cur.link || ''
    }
    if (!force && cur.sig && ['id', 'name', 'handle', 'bio', 'photo', 'following', 'cm', 'link'].every((k) => cur[k] === next[k])) return
    next.sig = hex(crypto.sign(b4a.concat([b4a.from('meshron-reels-v1'), mine.ch.key]), kp.secretKey))
    await mine.ch.append(b4a.from(JSON.stringify(next)))
  }

  // ---- Reading channels --------------------------------------------------------------

  function json (block) {
    if (!block || block.byteLength > MAX_ENTRY) return null
    try { const e = JSON.parse(b4a.toString(block)); return e && typeof e === 'object' ? e : null } catch { return null }
  }

  async function watch (chHex, core) {
    let c = chans.get(chHex)
    if (c) return c
    if (!core) {
      core = x.store().get({ key: b4a.from(chHex, 'hex') })
      await core.ready()
    }
    c = { key: chHex, core, seq: 0, profile: null, reels: new Map(), likes: new Map(), comments: new Map(), followers: new Set(), views: new Map(), reposts: new Map(), collabs: new Map(), reposted: new Map(), claims: new Set(), reading: false }
    chans.set(chHex, c)
    core.on('append', () => read(c).catch(report))
    read(c).catch(report)
    if (core !== (mine && mine.ch)) core.update({ wait: true }).then(() => read(c), () => {})
    return c
  }

  async function read (c) {
    if (c.reading) return
    c.reading = true
    try {
      let any = false
      while (c.seq < c.core.length) {
        const block = await c.core.get(c.seq, { timeout: 20000 }).catch(() => null)
        if (!block) break
        apply(c, json(block))
        c.seq++
        any = true
      }
      if (any) changed(c.key)
    } finally {
      c.reading = false
    }
  }

  function apply (c, e) {
    if (!e || typeof e.t !== 'string') return
    switch (e.t) {
      case 'me': {
        if (!HEX64.test(e.id) || typeof e.sig !== 'string') return
        const ok = crypto.verify(b4a.concat([b4a.from('meshron-reels-v1'), c.core.key]), b4a.from(e.sig, 'hex'), b4a.from(e.id, 'hex'))
        if (!ok) return
        c.profile = { id: e.id, sig: e.sig, name: str(e.name, 32), handle: str(e.handle, 24), bio: str(e.bio, 150), photo: str(e.photo, MAX_PHOTO), following: e.following | 0, cm: ['followers', 'friends', 'off'].includes(e.cm) ? e.cm : 'everyone', link: cleanLink(e.link) }
        byId.set(e.id, c.key)
        return
      }
      case 'reel':
        if (!ID.test(e.id) || !HEX64.test(e.blobs) || !e.blob || !Number.isInteger(e.blob.blockLength)) return
        c.reels.set(e.id, {
          id: e.id, ts: +e.ts || 0, cap: str(e.cap, 300), dur: +e.dur || 0, w: e.w | 0, h: e.h | 0,
          thumb: str(e.thumb, MAX_THUMB), blobs: e.blobs, blob: e.blob, title: str(e.title, 60),
          sound: e.sound && HEX64.test(e.sound.ch) && ID.test(e.sound.id) ? { ch: e.sound.ch, id: e.sound.id, title: str(e.sound.title, 60) } : null
        })
        return
      case 'del':
        c.reels.delete(e.id)
        return
      case 'l': {
        if (!HEX64.test(e.by)) return
        const s = c.likes.get(e.id) || new Set()
        e.on ? s.add(e.by) : s.delete(e.by)
        c.likes.set(e.id, s)
        return
      }
      case 'c': {
        if (!HEX64.test(e.by) || typeof e.cid !== 'string') return
        if (e.by === AI_ID && !aiOk(c.key + ':' + e.reel, e.re, e.text, e.ai)) return
        const m = c.comments.get(e.reel) || new Map()
        m.set(e.cid, { cid: e.cid, by: e.by, name: e.by === AI_ID ? 'Meshron AI' : str(e.name, 32), text: str(e.text, e.by === AI_ID ? MAX_AI : MAX_COMMENT), ts: +e.ts || 0, re: typeof e.re === 'string' && ID.test(e.re) ? e.re : null, ai: e.by === AI_ID ? e.ai : undefined })
        c.comments.set(e.reel, m)
        return
      }
      case 'cdel':
        for (const m of c.comments.values()) m.delete(e.cid)
        return
      case 'f':
        if (HEX64.test(e.by)) e.on ? c.followers.add(e.by) : c.followers.delete(e.by)
        return
      case 'v':
        c.views.set(e.id, Math.max(c.views.get(e.id) || 0, e.n | 0))
        return
      case 'live':
        // The author went live (or stopped). Old ones expire on their own.
        c.live = e.on && typeof e.id === 'string' ? { id: e.id.slice(0, 40), title: str(e.title, 100), ts: +e.ts || 0, screen: e.screen === true } : null
        return
      case 'rp': {
        // Someone reposted one of this channel's reels.
        if (!HEX64.test(e.by)) return
        const s2 = c.reposts.get(e.id) || new Set()
        e.on ? s2.add(e.by) : s2.delete(e.by)
        c.reposts.set(e.id, s2)
        return
      }
      case 'repost':
        // This channel reposted someone's reel (optionally quoting a comment).
        if (!HEX64.test(e.rch) || !ID.test(e.rid)) return
        if (e.on === false) c.reposted.delete(e.rch + ':' + e.rid)
        else c.reposted.set(e.rch + ':' + e.rid, { ts: +e.ts || 0, cid: typeof e.cid === 'string' ? e.cid : null, quote: str(e.quote, MAX_COMMENT) })
        return
      case 'co': {
        // The author confirmed a collaborator on a reel.
        if (!HEX64.test(e.by)) return
        const s3 = c.collabs.get(e.id) || new Set()
        e.on === false ? s3.delete(e.by) : s3.add(e.by)
        c.collabs.set(e.id, s3)
        return
      }
      case 'collab':
        // This channel accepted to be a collaborator on someone's reel.
        if (HEX64.test(e.rch) && ID.test(e.rid)) c.claims.add(e.rch + ':' + e.rid)
    }
  }
  const str = (v, n) => (typeof v === 'string' ? v.slice(0, n) : '')

  function changed (chHex) {
    changedChans.add(chHex)
    if (changedTimer) return
    changedTimer = setTimeout(() => {
      changedTimer = null
      const list = [...changedChans]
      changedChans.clear()
      send({ ev: 'reels-changed', chans: list })
      // Viewers whose likes / comments this author hasn't written yet.
      for (const ch of list) if (ch !== (mine && mine.chKey)) maybeSync(ch)
    }, 400)
  }

  async function learn (chHex) {
    if (!HEX64.test(chHex) || chans.has(chHex) || chHex === (mine && mine.chKey)) return
    if (state.known.size >= MAX_KNOWN && !state.follows.has(chHex)) return
    if (!state.known.has(chHex)) await savePrivate({ t: 'known', ch: chHex })
    await watch(chHex)
  }

  // ---- Peers -------------------------------------------------------------------------

  function topic () { return hash(TOPIC) }

  /** Every stream gets a Reels channel; for strangers it is the only one used. */
  function attach (conn, key, mux) {
    const channel = mux.createChannel({
      protocol: 'meshron/reels/1',
      onopen () {
        if (!on) return
        sendHi(msg)
      },
      onclose () {
        const set = conns.get(key)
        if (set) { set.delete(entry); if (!set.size) conns.delete(key) }
      }
    })
    if (!channel) return
    const msg = channel.addMessage({
      encoding: x.c.json,
      onmessage (m) { onMessage(key, conn, m).catch(report) }
    })
    const entry = { conn, message: msg }
    let set = conns.get(key)
    if (!set) conns.set(key, (set = new Set()))
    set.add(entry)
    channel.open()
  }

  function sendHi (msg) {
    if (!mine) return
    if (nearCell) try { msg.send({ t: 'near', cell: nearCell, name: me.name, handle: me.handle }) } catch {}
    const pool = [...state.follows, ...state.known].filter((k) => k !== mine.chKey)
    const known = []
    while (pool.length && known.length < 60) known.push(pool.splice(Math.floor(Math.random() * pool.length), 1)[0])
    try { msg.send({ t: 'hi', ch: mine.chKey, known, live: liveChannels() }) } catch {}
  }

  /** Channels live right now (mine too): passed along so lives reach everyone, not only followers. */
  function liveChannels () {
    const out = []
    for (const c of chans.values()) if (c.live && now() - c.live.ts < LIVE_MAX) out.push(c.key)
    return out.slice(0, 30)
  }

  /** Re-sends my "hi" to every Reels peer (when I go live, so they hear at once). */
  function hiAll () { for (const set of conns.values()) for (const e of set) sendHi(e.message) }

  async function onMessage (key, conn, m) {
    if (!on || !m || typeof m !== 'object') return
    if (m.t === 'hi') {
      if (!x.isKnown(key)) x.quiet(conn) // a Reels-only link: never a contact request
      if (HEX64.test(m.ch)) { await learn(m.ch); byId.set(key, byId.get(key) || m.ch) }
      if (Array.isArray(m.known)) for (const k of m.known.slice(0, 60)) learn(k).catch(() => {})
      // Lives: fetch those channels now (their latest entry says live + title).
      if (Array.isArray(m.live)) for (const k of m.live.slice(0, 30)) if (HEX64.test(k)) learn(k).then(() => maybeSync(k)).catch(() => {})
      const ch = byId.get(key)
      if (ch) maybeSync(ch)
      return
    }
    if (m.t === 'near') {
      if (!x.isKnown(key)) x.quiet(conn)
      if (nearCell && m.cell === nearCell && key !== hex(x.keyPair().publicKey)) {
        send({ ev: 'reels-near-person', key, name: str(m.name, 32), handle: /^[a-z0-9_]{3,24}$/.test(m.handle) ? m.handle : '' })
      }
      return
    }
    if (m.t === 'sync' && m.ch === mine.chKey) return onSync(key, m)
    if (m.t === 'synced') {
      dialing.delete(key)
      if (!x.isKnown(key) && x.swarm()) x.swarm().leavePeer(b4a.from(key, 'hex'))
    }
  }

  function sendTo (idHex, m) {
    const set = conns.get(idHex)
    if (!set || !set.size) return false
    for (const e of set) { try { e.message.send(m); return true } catch {} }
    return false
  }

  // What I want the author of `ch` to have written, compared with what their channel says.
  function wanted (chHex) {
    const c = chans.get(chHex)
    if (!c || !c.profile || !mine) return null
    const me = hex(x.keyPair().publicKey)
    const likes = []
    let likesDiffer = false
    for (const id of c.reels.keys()) {
      const want = state.likes.has(chHex + ':' + id)
      if (want) likes.push(id)
      if (want !== !!(c.likes.get(id) && c.likes.get(id).has(me))) likesDiffer = true
    }
    const follow = state.follows.has(chHex)
    const comments = []
    const pend = state.pending.get(chHex)
    if (pend) {
      for (const p of pend.values()) {
        const there = c.comments.get(p.reel)
        if ((!there || !there.has(p.cid)) && !(state.uncomment.get(chHex) || new Set()).has(p.cid) && c.reels.has(p.reel)) comments.push({ reel: p.reel, cid: p.cid, text: p.text, ts: p.ts, name: x.name(), re: p.re || null, ...(p.ai ? { ai: p.ai } : {}) })
      }
    }
    const uncomment = []
    for (const cid of state.uncomment.get(chHex) || []) {
      for (const m of c.comments.values()) if (m.has(cid) && m.get(cid).by === me) uncomment.push(cid)
    }
    const watched = [...views.keys()].filter((k) => k.startsWith(chHex + ':')).map((k) => k.slice(65))
    const rps = []
    let rpDiffer = false
    for (const id of c.reels.keys()) {
      const want = reposts.has(chHex + ':' + id)
      if (want) rps.push(id)
      if (want !== !!(c.reposts.get(id) && c.reposts.get(id).has(me))) rpDiffer = true
    }
    const mineCh = chans.get(mine.chKey)
    const collab = [...c.reels.keys()].filter((id) => mineCh && mineCh.claims.has(chHex + ':' + id) && !(c.collabs.get(id) && c.collabs.get(id).has(me)))
    const dirty = likesDiffer || rpDiffer || collab.length || follow !== c.followers.has(me) || comments.length || uncomment.length || watched.length
    return dirty ? { t: 'sync', ch: chHex, likes, follow, comments, uncomment, views: watched, reposts: rps, collab } : null
  }

  function maybeSync (chHex) {
    const m = wanted(chHex)
    if (!m) return
    const c = chans.get(chHex)
    const author = c.profile.id
    if (sendTo(author, m)) {
      for (const id of m.views) views.delete(chHex + ':' + id)
      return
    }
    // Not connected: look the author up on the DHT (as a Reels link, not a contact).
    const s = x.swarm()
    if (s && !dialing.has(author)) {
      dialing.add(author)
      s.joinPeer(b4a.from(author, 'hex'))
      setTimeout(() => { if (dialing.delete(author) && !x.isKnown(author) && x.swarm()) x.swarm().leavePeer(b4a.from(author, 'hex')) }, 90000)
    }
  }

  // An author receiving a viewer's likes / follow / comments.
  const viewCounts = new Map()
  let viewTimer = null
  async function onSync (by, m) {
    const c = chans.get(mine.chKey)
    const out = []
    const want = new Set(Array.isArray(m.likes) ? m.likes.filter((id) => typeof id === 'string') : [])
    for (const id of c.reels.keys()) {
      const has = !!(c.likes.get(id) && c.likes.get(id).has(by))
      if (want.has(id) !== has) out.push({ t: 'l', id, by, on: !has })
    }
    if (typeof m.follow === 'boolean' && m.follow !== c.followers.has(by)) out.push({ t: 'f', by, on: m.follow })
    if (Array.isArray(m.reposts)) {
      const want2 = new Set(m.reposts.filter((id) => typeof id === 'string'))
      for (const id of c.reels.keys()) {
        const has = !!(c.reposts.get(id) && c.reposts.get(id).has(by))
        if (want2.has(id) !== has) out.push({ t: 'rp', id, by, on: !has })
      }
    }
    // Collaborators: only people I invited to that reel.
    for (const id of (Array.isArray(m.collab) ? m.collab : []).slice(0, 20)) {
      if (c.reels.has(id) && invited.has(id + ':' + by) && !(c.collabs.get(id) && c.collabs.get(id).has(by))) out.push({ t: 'co', id, by })
    }
    // Comments only from people my setting allows (everyone / followers / friends / nobody).
    const mayComment = by === hex(x.keyPair().publicKey) || (prefs.cmFrom || 'everyone') === 'everyone' ||
      (prefs.cmFrom === 'followers' && c.followers.has(by)) || (prefs.cmFrom === 'friends' && c.followers.has(by) && iFollow(by))
    for (const cm of (mayComment && Array.isArray(m.comments) ? m.comments : []).slice(0, 20)) {
      if (!cm || !c.reels.has(cm.reel) || typeof cm.cid !== 'string' || !ID.test(cm.cid)) continue
      if (cm.ai) {
        // Meshron AI's answer to a question, carried by the person who asked.
        if (typeof cm.text !== 'string' || cm.text.length > MAX_AI || !aiOk(c.key + ':' + cm.reel, cm.re, cm.text, cm.ai)) continue
        if (c.comments.get(cm.reel) && c.comments.get(cm.reel).has(cm.cid)) continue
        out.push({ t: 'c', reel: cm.reel, cid: cm.cid, by: AI_ID, name: 'Meshron AI', text: cm.text, ts: Math.min(+cm.ts || now(), now()), re: cm.re, ai: cm.ai })
        continue
      }
      const text = str(cm.text, MAX_COMMENT).trim()
      if (!text || (c.comments.get(cm.reel) && c.comments.get(cm.reel).has(cm.cid))) continue
      const re = typeof cm.re === 'string' && c.comments.get(cm.reel) && c.comments.get(cm.reel).has(cm.re) ? cm.re : null
      out.push({ t: 'c', reel: cm.reel, cid: cm.cid, by, name: str(cm.name, 32), text, ts: Math.min(+cm.ts || now(), now()), re })
    }
    for (const cid of (Array.isArray(m.uncomment) ? m.uncomment : []).slice(0, 50)) {
      for (const cm of c.comments.values()) if (cm.has(cid) && cm.get(cid).by === by) out.push({ t: 'cdel', cid })
    }
    for (const id of (Array.isArray(m.views) ? m.views : []).slice(0, 200)) {
      if (!c.reels.has(id)) continue
      viewCounts.set(id, (viewCounts.get(id) || c.views.get(id) || 0) + 1)
    }
    if (viewCounts.size && !viewTimer) {
      viewTimer = setTimeout(async () => {
        viewTimer = null
        const batch = [...viewCounts].map(([id, n]) => ({ t: 'v', id, n }))
        viewCounts.clear()
        for (const e of batch) await mine.ch.append(b4a.from(JSON.stringify(e)))
      }, 60000)
    }
    for (const e of out) await mine.ch.append(b4a.from(JSON.stringify(e)))
    sendTo(by, { t: 'synced', ch: mine.chKey })
  }

  // ---- Views for the screens ---------------------------------------------------------

  function item (c, r) {
    const me = hex(x.keyPair().publicKey)
    const k = c.key + ':' + r.id
    const likes = c.likes.get(r.id)
    const liked = state.likes.has(k)
    const likedThere = !!(likes && likes.has(me))
    let likeCount = likes ? likes.size : 0
    if (liked && !likedThere) likeCount++
    if (!liked && likedThere) likeCount--
    const comments = c.comments.get(r.id)
    const pend = [...(state.pending.get(c.key) || new Map()).values()].filter((p) => p.reel === r.id && !(comments && comments.has(p.cid)))
    const sound = r.sound || { ch: c.key, id: r.id, title: r.title || 'Original sound · ' + ((c.profile && c.profile.name) || '') }
    return {
      ch: c.key, id: r.id, ts: r.ts, cap: r.cap, dur: r.dur, w: r.w, h: r.h, thumb: r.thumb, size: r.blob.byteLength | 0,
      sound, original: !r.sound,
      author: author(c),
      likes: Math.max(0, likeCount), liked, comments: (comments ? comments.size : 0) + pend.length,
      views: c.views.get(r.id) || 0, faved: state.favs.has(k), seen: state.seen.has(k),
      reposts: (c.reposts.get(r.id) ? c.reposts.get(r.id).size : 0) + (reposts.has(k) && !(c.reposts.get(r.id) && c.reposts.get(r.id).has(me)) ? 1 : 0),
      reposted: reposts.has(k),
      collabs: [...(c.collabs.get(r.id) || [])].map((id) => byId.get(id)).filter((ch) => ch && chans.get(ch)).map((ch) => author(chans.get(ch))),
      mine: c.key === (mine && mine.chKey)
    }
  }
  /** Does this identity follow me (are they in my channel's followers)? */
  /** True when I follow the channel of identity [id]. Friends = people who follow each other. */
  function iFollow (id) {
    if (!id) return false
    for (const ch of state.follows) { const c = chans.get(ch); if (c && c.profile && c.profile.id === id) return true }
    return false
  }

  function followsMe (id) {
    const m = mine && chans.get(mine.chKey)
    return !!(m && id && m.followers.has(id))
  }

  function author (c) {
    const p = c.profile || {}
    const lv = c.live && now() - c.live.ts < LIVE_MAX ? c.live : null
    return { ch: c.key, id: p.id || '', name: p.name || 'Someone', handle: p.handle || '', photo: p.photo || '', following: state.follows.has(c.key), mine: c.key === (mine && mine.chKey), live: lv, cm: ['followers', 'friends', 'off'].includes(p.cm) ? p.cm : 'everyone', followsMe: followsMe(p.id) }
  }

  function allItems () {
    const out = []
    for (const c of chans.values()) if (c.profile) for (const r of c.reels.values()) out.push(item(c, r))
    return out
  }

  /** Reels reposted by [chs] (each with who reposted, when, and any quoted comment). */
  function repostItems (chs) {
    const out = []
    for (const rch of chs) {
      const rc = chans.get(rch)
      if (!rc || !rc.profile) continue
      for (const [k, rp] of rc.reposted) {
        const [ch2, id] = [k.slice(0, 64), k.slice(65)]
        const c = chans.get(ch2)
        const r = c && c.reels.get(id)
        if (!r || !c.profile) continue
        out.push({ ...item(c, r), reposter: author(rc), repostTs: rp.ts, quote: rp.quote || '', quoteCid: rp.cid })
      }
    }
    return visible(out)
  }

  /** Drops creators you hid, videos you're not interested in, and muted words. */
  function visible (items) {
    return items.filter((i) => {
      if (prefs.hiddenCh.has(i.ch) || prefs.notInt.has(i.ch + ':' + i.id)) return false
      if (!prefs.words.length) return true
      const hay = (i.cap + ' ' + i.sound.title + ' ' + i.author.name + ' ' + i.author.handle).toLowerCase()
      return !prefs.words.some((wd) => hay.includes(wd))
    })
  }

  function feed ({ kind, ch, offset = 0, limit = 12, sound }) {
    let list
    if (kind === 'following') {
      // Posts and reposts from people I follow, newest first.
      const posts = visible(allItems()).filter((i) => state.follows.has(i.ch))
      list = posts.concat(repostItems([...state.follows])).sort((a, b) => (b.repostTs || b.ts) - (a.repostTs || a.ts))
    } else if (kind === 'reposts') {
      list = repostItems([ch]).sort((a, b) => b.repostTs - a.repostTs)
    } else if (kind === 'user') {
      // Their reels, and reels they're a confirmed collaborator on.
      const c = chans.get(ch)
      const id = c && c.profile && c.profile.id
      list = allItems().filter((i) => i.ch === ch || (id && i.collabs.some((a) => a.ch === ch))).sort((a, b) => b.ts - a.ts)
    } else if (kind === 'private') {
      const c = chans.get(mine.chKey)
      list = [...privates.values()].map((r) => ({ ...item(c, r), private: true })).sort((a, b) => b.ts - a.ts)
    } else if (kind === 'favourites') {
      list = allItems().filter((i) => i.faved).sort((a, b) => (state.favs.get(b.ch + ':' + b.id) || 0) - (state.favs.get(a.ch + ':' + a.id) || 0))
    } else if (kind === 'liked') {
      list = allItems().filter((i) => i.liked).sort((a, b) => b.ts - a.ts)
    } else if (kind === 'sound') {
      list = allItems().filter((i) => i.sound.ch === sound.ch && i.sound.id === sound.id).sort((a, b) => b.likes - a.likes || b.ts - a.ts)
    } else {
      // For You, ranked by your own settings (0-10 each): fresh, popular, following,
      // interests (tags you liked or saved), explore (randomness). Unseen before seen,
      // never your own, and never what you filtered out.
      const t = now()
      const w = prefs.weights
      const likedTags = new Map()
      for (const i of allItems()) if (i.liked || i.faved) for (const tag of tagsOf(i.cap)) likedTags.set(tag, (likedTags.get(tag) || 0) + 1)
      const score = (i) => {
        const tags = tagsOf(i.cap)
        const interest = tags.reduce((n, tag) => n + Math.min(3, likedTags.get(tag) || 0) - 2 * (prefs.notIntTags.get(tag) || 0), 0)
        return (i.seen ? -50 : 0) +
          w.popular * 0.9 * Math.log2(2 + i.likes * 2 + i.comments * 3 + i.views / 10) +
          w.following * (i.author.following ? 1.2 : 0) +
          w.interests * 0.8 * interest -
          w.fresh * 0.25 * ((t - i.ts) / 3600000 / 6) +
          w.explore * 0.6 * Math.random()
      }
      list = visible(allItems()).filter((i) => !i.mine).map((i) => [score(i), i]).sort((a, b) => b[0] - a[0]).map((p) => p[1])
    }
    return { items: list.slice(offset, offset + limit), more: offset + limit < list.length, total: list.length }
  }

  function profile (chHex) {
    const c = chans.get(chHex)
    if (!c) return null
    let likes = 0
    for (const s of c.likes.values()) likes += s.size
    return {
      ...author(c), bio: (c.profile || {}).bio || '', link: (c.profile || {}).link || '', followers: c.followers.size, followingCount: (c.profile || {}).following || 0,
      likes, reels: feed({ kind: 'user', ch: chHex, limit: 200 }).items
    }
  }

  function comments (chHex, id) {
    const c = chans.get(chHex)
    if (!c) return []
    const me = hex(x.keyPair().publicKey)
    const gone = state.uncomment.get(chHex) || new Set()
    const list = [...(c.comments.get(id) || new Map()).values()].filter((cm) => !gone.has(cm.cid)).map((cm) => ({ ...cm, mine: cm.by === me, sent: true }))
    for (const p of (state.pending.get(chHex) || new Map()).values()) {
      if (p.reel === id && !gone.has(p.cid) && !list.some((cm) => cm.cid === p.cid)) list.push(p.ai ? { cid: p.cid, by: AI_ID, name: 'Meshron AI', text: p.text, ts: p.ts, mine: false, sent: false, re: p.re || null }
        : { cid: p.cid, by: me, name: x.name(), text: p.text, ts: p.ts, mine: true, sent: false, re: p.re || null })
    }
    const isAuthor = chHex === (mine && mine.chKey)
    return list.sort((a, b) => a.ts - b.ts).map((cm) => ({ ...cm, canDelete: cm.mine || isAuthor, photo: photoOf(cm.by) }))
  }
  function photoOf (idHex) {
    const ch = byId.get(idHex)
    const c = ch && chans.get(ch)
    return (c && c.profile && c.profile.photo) || ''
  }

  function search (q) {
    const s = String(q || '').toLowerCase().replace(/^@/, '').trim()
    if (!s) return []
    const out = []
    for (const c of chans.values()) {
      if (!c.profile) continue
      if (c.profile.name.toLowerCase().includes(s) || c.profile.handle.toLowerCase().includes(s)) out.push({ ...author(c), followers: c.followers.size, reels: c.reels.size })
    }
    return out.sort((a, b) => b.followers - a.followers).slice(0, 30)
  }

  /** @username not met yet: find them on the DHT, ask for their channel. */
  async function findHandle (handle) {
    const s = x.swarm()
    if (!s || !/^[a-z0-9_]{3,24}$/.test(handle)) return []
    const keys = await x.lookupHandle(handle)
    for (const k of keys) {
      if (byId.has(k)) continue
      dialing.add(k)
      s.joinPeer(b4a.from(k, 'hex'))
      setTimeout(() => { if (dialing.delete(k) && !x.isKnown(k) && x.swarm()) x.swarm().leavePeer(b4a.from(k, 'hex')) }, 30000)
    }
    // Wait (up to 12 s) for their 'hi' and their channel's profile.
    for (let i = 0; i < 24; i++) {
      const found = keys.map((k) => byId.get(k)).filter((ch) => ch && chans.get(ch) && chans.get(ch).profile)
      if (found.length) return found.map((ch) => ({ ...author(chans.get(ch)), followers: chans.get(ch).followers.size, reels: chans.get(ch).reels.size }))
      await new Promise((resolve) => setTimeout(resolve, 500))
    }
    return []
  }

  // ---- Videos ------------------------------------------------------------------------

  async function post (xf, { cap, dur, w, h, thumb, sound, title }) {
    const data = b4a.concat(xf.chunks)
    const blob = await mine.blobs.put(data)
    const id = rid()
    let snd = null
    if (sound && HEX64.test(sound.ch) && ID.test(sound.id)) {
      const c = chans.get(sound.ch)
      const r = c && c.reels.get(sound.id)
      const orig = r && r.sound
      snd = orig || { ch: sound.ch, id: sound.id, title: str((r && r.title) || sound.title || 'Original sound · ' + ((c && c.profile && c.profile.name) || ''), 60) }
    }
    const e = {
      t: 'reel', id, ts: now(), cap: str(cap, 300), dur: Math.min(60, +dur || 0), w: w | 0, h: h | 0,
      thumb: str(thumb, MAX_THUMB), blobs: hex(mine.blobsCore.key), blob, sound: snd,
      title: snd ? '' : str(title, 60) || 'Original sound · ' + x.name()
    }
    await mine.ch.append(b4a.from(JSON.stringify(e)))
    return id
  }

  async function get ({ req, ch, id }) {
    try {
      const c = chans.get(ch)
      const priv = ch === mine.chKey ? privates.get(id) : null
      const r = priv || (c && c.reels.get(id))
      if (!r) throw new Error('That video is gone')
      if (priv) {
        const data = await mine.privBlobs.get(r.blob)
        for (let o = 0; o < data.byteLength; o += 64 * 1024) send({ ev: 'reel-data', req, data: b4a.toString(data.subarray(o, o + 64 * 1024), 'base64') })
        return send({ ev: 'reel-done', req })
      }
      if (r.blob.byteLength > MAX_VIDEO) throw new Error('Video too large')
      const core = r.blobs === hex(mine.blobsCore.key) ? mine.blobsCore : x.store().get({ key: b4a.from(r.blobs, 'hex') })
      await core.ready()
      // Fetch everything in parallel (fast start), then hand it over in order.
      const range = core.download({ start: r.blob.blockOffset, end: r.blob.blockOffset + r.blob.blockLength })
      for (let i = 0; i < r.blob.blockLength; i++) {
        const block = await core.get(r.blob.blockOffset + i, { timeout: 45000 })
        send({ ev: 'reel-data', req, data: b4a.toString(block, 'base64') })
      }
      range.destroy && range.destroy()
      send({ ev: 'reel-done', req })
      const k = ch + ':' + id
      if (!state.seen.has(k)) {
        state.seen.add(k)
        seenBatch.push(k)
        if (c.key !== mine.chKey) { views.set(k, true); maybeSync(ch) }
        if (seenBatch.length >= 10) flushSeen()
      }
    } catch (err) {
      send({ ev: 'reel-error', req, msg: err.message || String(err) })
    }
  }
  function flushSeen () {
    if (!seenBatch.length || !mine) return
    const ids = seenBatch
    seenBatch = []
    mine.priv.append(b4a.from(JSON.stringify({ t: 'seen', ids }))).catch(report)
  }

  // Followed channels: keep their newest videos ready (and help share them).
  async function prefetch () {
    for (const ch of state.follows) {
      const c = chans.get(ch)
      if (!c) continue
      const newest = [...c.reels.values()].sort((a, b) => b.ts - a.ts).slice(0, 3)
      for (const r of newest) {
        try {
          const core = x.store().get({ key: b4a.from(r.blobs, 'hex') })
          await core.ready()
          core.download({ start: r.blob.blockOffset, end: r.blob.blockOffset + r.blob.blockLength })
        } catch {}
      }
    }
  }

  // ---- Commands from the host ----------------------------------------------------------

  async function handle (cmd) {
    const reply = (ev, data) => send({ ev, req: cmd.req, ...data })
    try {
      switch (cmd.cmd) {
        case 'reels-start': {
          if (on) return publishMe(cmd)
          on = true
          await open()
          await publishMe(cmd)
          for (const ch of state.follows) watch(ch).catch(() => {})
          for (const ch of state.known) watch(ch).catch(() => {})
          x.onTopic(topic())
          setInterval(() => prefetch().catch(() => {}), 10 * 60 * 1000)
          setInterval(flushSeen, 60 * 1000)
          setTimeout(() => prefetch().catch(() => {}), 20000)
          log('Reels on: channel ' + mine.chKey.slice(0, 8) + '…')
          return reply('reels-ready', { ch: mine.chKey })
        }
        case 'reels-me':
          return publishMe(cmd)
        case 'reels-people': {
          // Following, followers, and friends (people who follow each other).
          const following = [...state.follows].map((k) => chans.get(k)).filter((c) => c && c.profile).map(author)
          const mc = chans.get(mine.chKey)
          const followers = (mc ? [...mc.followers] : []).map((id) => {
            const c = chans.get(byId.get(id))
            return c && c.profile ? author(c) : { ch: '', id, name: 'Someone', handle: '', photo: '', following: false, mine: false, followsMe: true }
          })
          const friends = following.filter((a) => a.followsMe)
          const list = cmd.kind === 'following' ? following : cmd.kind === 'followers' ? followers : friends
          return reply('reels-people', { list, counts: { following: following.length, followers: followers.length, friends: friends.length } })
        }
        case 'reel-cmfrom':
          if (!['everyone', 'followers', 'friends', 'off'].includes(cmd.v)) throw new Error('Bad setting')
          await savePrivate({ t: 'cmfrom', v: cmd.v })
          prefs.cmFrom = cmd.v
          await publishMe({})
          return reply('reel-ok', {})
        case 'reels-prefs':
          return reply('reels-prefs', { weights: prefs.weights, words: prefs.words, hidden: [...prefs.hiddenCh].map((k) => chans.get(k) ? author(chans.get(k)) : { ch: k, name: 'Someone', handle: '' }) })
        case 'reels-prefs-set':
          await savePrivate({ t: 'prefs', weights: cmd.weights || prefs.weights, words: Array.isArray(cmd.words) ? cmd.words : prefs.words })
          return reply('reel-ok', {})
        case 'reel-notint': {
          const c = chans.get(cmd.ch)
          const r = c && c.reels.get(cmd.id)
          await savePrivate({ t: 'notint', ch: cmd.ch, id: cmd.id, tags: r ? tagsOf(r.cap) : [] })
          return reply('reel-ok', {})
        }
        case 'reel-hidech':
          await savePrivate({ t: 'hidech', ch: cmd.ch, on: !!cmd.on })
          if (cmd.on && state.follows.has(cmd.ch)) await savePrivate({ t: 'follow', ch: cmd.ch, on: false })
          changed(cmd.ch)
          return reply('reel-ok', {})
        case 'reels-near': {
          // Join (or leave) the topic for my rough area.
          const s = x.swarm()
          const cell = typeof cmd.cell === 'string' && /^[0-9b-hjkmnp-z]{5}$/.test(cmd.cell) ? cmd.cell : null
          if (cell === nearCell) return
          if (nearTopic && s) await s.leave(nearTopic).catch(() => {})
          nearCell = cell
          nearTopic = cell ? hash('meshron/near/v1:' + cell) : null
          if (nearTopic && s) s.join(nearTopic, { server: true, client: true })
          // Tell people already connected.
          for (const set of conns.values()) for (const e of set) if (nearCell) try { e.message.send({ t: 'near', cell: nearCell, name: me.name, handle: me.handle }) } catch {}
          return
        }
        case 'reels-feed':
          return reply('reels-feed', feed(cmd))
        case 'reels-ch-of': {
          // The Reels channel of an identity (for opening someone's page from a comment or a group).
          const id = String(cmd.id || '')
          let ch = ''
          if (mine && id === hex(x.keyPair().publicKey)) ch = mine.chKey
          if (!ch) for (const c of chans.values()) if (c.profile && c.profile.id === id) { ch = c.key; break }
          if (!ch && byId.has(id)) ch = byId.get(id)
          return reply('reels-ch-of', { ch })
        }
        case 'reels-profile':
          if (HEX64.test(cmd.ch) && !chans.has(cmd.ch)) await learn(cmd.ch)
          return reply('reels-profile', { profile: profile(cmd.ch) })
        case 'reels-sounds': {
          // Sounds people have posted with, most used first (optionally matching a search).
          const q = String(cmd.q || '').toLowerCase().trim()
          const byKey = new Map()
          for (const i of allItems()) {
            const k = i.sound.ch + ':' + i.sound.id
            const s = byKey.get(k) || { sound: i.sound, uses: 0, thumb: '', likes: 0 }
            s.uses++
            s.likes += i.likes
            if (i.ch === i.sound.ch && i.id === i.sound.id) s.thumb = i.thumb
            if (!s.thumb) s.thumb = i.thumb
            byKey.set(k, s)
          }
          const list = [...byKey.values()].filter((s) => !q || s.sound.title.toLowerCase().includes(q))
            .sort((a, b) => b.uses - a.uses || b.likes - a.likes).slice(0, 80)
          return reply('reels-sounds', { list })
        }
        case 'reels-videos': {
          const q = String(cmd.q || '').toLowerCase().trim()
          if (!q) return reply('reels-feed', { items: [], more: false })
          const words = q.split(/s+/).filter(Boolean)
          const hits = allItems().filter((i) => {
            const hay = (i.cap + ' ' + i.sound.title + ' @' + i.author.handle + ' ' + i.author.name).toLowerCase()
            return words.every((w) => hay.includes(w))
          }).sort((a, b) => (b.likes + b.views / 10) - (a.likes + a.views / 10) || b.ts - a.ts)
          return reply('reels-feed', { items: hits.slice(0, 60), more: false })
        }
        case 'reels-search': {
          let users = search(cmd.q)
          const h = String(cmd.q || '').toLowerCase().replace(/^@/, '').trim()
          if (!users.some((u) => u.handle === h) && /^[a-z0-9_]{3,24}$/.test(h)) users = users.concat(await findHandle(h))
          return reply('reels-search', { users })
        }
        case 'reel-comments':
          return reply('reel-comments', { list: comments(cmd.ch, cmd.id) })
        case 'reel-like': {
          const k = cmd.ch + ':' + cmd.id
          if (!!cmd.on !== state.likes.has(k)) await savePrivate({ t: 'like', ch: cmd.ch, id: cmd.id, on: !!cmd.on })
          maybeSync(cmd.ch)
          changed(cmd.ch)
          return reply('reel-ok', {})
        }
        case 'reel-fav':
          await savePrivate({ t: 'fav', ch: cmd.ch, id: cmd.id, on: !!cmd.on, ts: now() })
          changed(cmd.ch)
          return reply('reel-ok', {})
        case 'reel-follow':
          if (cmd.ch === mine.chKey) return reply('reel-ok', {})
          if (!!cmd.on !== state.follows.has(cmd.ch)) await savePrivate({ t: 'follow', ch: cmd.ch, on: !!cmd.on })
          await learn(cmd.ch)
          maybeSync(cmd.ch)
          await publishMe({})
          changed(cmd.ch)
          return reply('reel-ok', {})
        case 'reel-comment': {
          if (cmd.ai) {
            // Meshron AI's answer (signed by the server), posted under the question.
            if (typeof cmd.text !== 'string' || cmd.text.length > MAX_AI || !aiOk(cmd.ch + ':' + cmd.id, cmd.re, cmd.text, cmd.ai)) throw new Error('That answer could not be checked')
            const aid = rid()
            if (cmd.ch === mine.chKey) await mine.ch.append(b4a.from(JSON.stringify({ t: 'c', reel: cmd.id, cid: aid, by: AI_ID, name: 'Meshron AI', text: cmd.text, ts: now(), re: cmd.re, ai: cmd.ai })))
            else { await savePrivate({ t: 'pc', ch: cmd.ch, reel: cmd.id, cid: aid, text: cmd.text, ts: now(), re: cmd.re, ai: cmd.ai }); maybeSync(cmd.ch) }
            changed(cmd.ch)
            return reply('reel-ok', { cid: aid })
          }
          const text = str(cmd.text, MAX_COMMENT).trim()
          if (!text) throw new Error('Write something first')
          const cid = rid()
          if (cmd.ch === mine.chKey) {
            const c = chans.get(mine.chKey)
            if (!c.reels.has(cmd.id)) throw new Error('That video is gone')
            await mine.ch.append(b4a.from(JSON.stringify({ t: 'c', reel: cmd.id, cid, by: hex(x.keyPair().publicKey), name: x.name(), text, ts: now(), re: ID.test(cmd.re || '') ? cmd.re : null })))
          } else {
            await savePrivate({ t: 'pc', ch: cmd.ch, reel: cmd.id, cid, text, ts: now(), re: ID.test(cmd.re || '') ? cmd.re : null })
            maybeSync(cmd.ch)
          }
          changed(cmd.ch)
          return reply('reel-ok', { cid })
        }
        case 'reel-uncomment':
          if (cmd.ch === mine.chKey) await mine.ch.append(b4a.from(JSON.stringify({ t: 'cdel', cid: cmd.cid })))
          else { await savePrivate({ t: 'pcd', ch: cmd.ch, cid: cmd.cid }); maybeSync(cmd.ch) }
          changed(cmd.ch)
          return reply('reel-ok', {})
        case 'reel-private': {
          // Make one of my videos private (only me) or public again.
          const c = chans.get(mine.chKey)
          if (cmd.on) {
            const r = c.reels.get(cmd.id)
            if (!r) throw new Error('That video is gone')
            const data = await mine.blobs.get(r.blob)
            const blob = await mine.privBlobs.put(data)
            await savePrivate({ t: 'pv', reel: { ...r, blobs: hex(mine.privBlobsCore.key), blob } })
            await mine.ch.append(b4a.from(JSON.stringify({ t: 'del', id: cmd.id })))
            // Wipe the public copy's bytes too, so nobody can fetch them any more.
            await mine.blobsCore.clear(r.blob.blockOffset, r.blob.blockOffset + r.blob.blockLength).catch(() => {})
            return reply('reel-ok', { id: cmd.id })
          }
          const r = privates.get(cmd.id)
          if (!r) throw new Error('That video is gone')
          const data = await mine.privBlobs.get(r.blob)
          const id = await post({ chunks: [data] }, { cap: r.cap, dur: r.dur, w: r.w, h: r.h, thumb: r.thumb, sound: r.sound, title: r.title })
          await savePrivate({ t: 'pvd', id: cmd.id })
          await mine.privBlobsCore.clear(r.blob.blockOffset, r.blob.blockOffset + r.blob.blockLength).catch(() => {})
          return reply('reel-ok', { id })
        }
        case 'reel-repost': {
          // Repost (or undo). A comment can be quoted with it.
          const k = cmd.ch + ':' + cmd.id
          const on = !!cmd.on
          if (on === reposts.has(k) && !cmd.cid) return reply('reel-ok', {})
          const quote = str(cmd.quote, MAX_COMMENT)
          await savePrivate({ t: 'rpst', ch: cmd.ch, id: cmd.id, on, ts: now(), cid: cmd.cid || null, quote })
          await mine.ch.append(b4a.from(JSON.stringify({ t: 'repost', rch: cmd.ch, rid: cmd.id, on, ts: now(), cid: cmd.cid || null, quote })))
          maybeSync(cmd.ch)
          changed(cmd.ch)
          return reply('reel-ok', {})
        }
        case 'reel-invite':
          // Invite a collaborator (an identity key) to one of my reels.
          if (!HEX64.test(cmd.who) || !chans.get(mine.chKey).reels.has(cmd.id)) throw new Error('That video is gone')
          await savePrivate({ t: 'inv', id: cmd.id, who: cmd.who })
          return reply('reel-ok', {})
        case 'reel-collab-accept':
          // Accept: claim it on my channel; the author confirms it when we meet.
          if (!HEX64.test(cmd.ch) || !ID.test(cmd.id)) throw new Error('Bad invite')
          await mine.ch.append(b4a.from(JSON.stringify({ t: 'collab', rch: cmd.ch, rid: cmd.id })))
          await learn(cmd.ch)
          maybeSync(cmd.ch)
          return reply('reel-ok', {})
        case 'reel-live':
          // Going live / stopping: written to my channel so followers see the LIVE ring.
          await mine.ch.append(b4a.from(JSON.stringify({ t: 'live', on: !!cmd.on, id: String(cmd.id || '').slice(0, 40), title: str(cmd.title, 100), ts: now(), screen: !!cmd.screen })))
          changed(mine.chKey)
          hiAll()
          return reply('reel-ok', {})
        case 'reels-lives': {
          // Who is live now: people I follow first, then everyone else I know.
          const list = []
          for (const c of chans.values()) {
            if (!c.profile || c.key === mine.chKey || !c.live || now() - c.live.ts > LIVE_MAX) continue
            if (prefs.hiddenCh.has(c.key)) continue
            list.push({ ...author(c), lid: c.live.id, title: c.live.title, ts: c.live.ts, screen: !!c.live.screen })
          }
          list.sort((a, b) => (b.following - a.following) || (b.ts - a.ts))
          return reply('reels-lives', { list: list.slice(0, 50) })
        }
        case 'reel-delete':
          if (privates.has(cmd.id)) {
            const r = privates.get(cmd.id)
            await savePrivate({ t: 'pvd', id: cmd.id })
            await mine.privBlobsCore.clear(r.blob.blockOffset, r.blob.blockOffset + r.blob.blockLength).catch(() => {})
            return reply('reel-ok', {})
          }
          await mine.ch.append(b4a.from(JSON.stringify({ t: 'del', id: cmd.id })))
          return reply('reel-ok', {})
        case 'reel-begin':
          xfers.set(cmd.xfer, { chunks: [], size: 0 })
          return
        case 'reel-chunk': {
          const xf = xfers.get(cmd.xfer)
          if (!xf) return
          const chunk = b4a.from(cmd.data, 'base64')
          xf.size += chunk.byteLength
          if (xf.size > MAX_VIDEO) { xfers.delete(cmd.xfer); return send({ ev: 'reel-error', req: cmd.xfer, msg: 'Video too large (25 MB max)' }) }
          xf.chunks.push(chunk)
          return
        }
        case 'reel-end': {
          const xf = xfers.get(cmd.xfer)
          xfers.delete(cmd.xfer)
          if (!xf) return
          const id = await post(xf, cmd)
          return send({ ev: 'reel-posted', req: cmd.xfer, id, ch: mine.chKey })
        }
        case 'reel-get':
          return get(cmd)
      }
    } catch (err) {
      send({ ev: 'reel-error', req: cmd.req || cmd.xfer, msg: err.message || String(err) })
    }
  }

  return {
    handle,
    attach,
    topic,
    /** Is this one of the Reels topics (feed or nearby)? */
    isTopic: (t) => b4a.equals(t, topic()) || (nearTopic != null && b4a.equals(t, nearTopic)),
    nearTopic: () => nearTopic,
    get on () { return on },
    /** Did we dial this key only for Reels? */
    dialled: (key) => dialing.has(key)
  }
}
