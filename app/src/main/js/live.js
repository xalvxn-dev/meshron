// Live video: one person streams, followers watch, everyone can chat and send hearts.
//
// The host is the hub. Viewers open a direct link to the host (found by identity key on the
// DHT, like Reels sync) and the host fans each video/audio frame out to every viewer. Chat and
// hearts go to the host, who passes them on to everyone. Nothing is stored: when the host stops,
// it's gone.
//
// Peer <-> peer: Protomux channel 'meshron/live/1' with two messages:
//   0 (JSON)  { t: 'watch', id, name }        viewer -> host
//             { t: 'leave' }                   viewer -> host
//             { t: 'chat', text } / { t: 'heart' } / { t: 'keyreq' }   viewer -> host
//             { t: 'welcome', id, title, viewers }  host -> viewer
//             { t: 'chat', from, name, text } / { t: 'hearts', n } / { t: 'count', viewers } / { t: 'end' }  host -> viewers
//   1 (bytes) [kind u8: 1 video, 2 audio] + payload   host -> viewers
//
// Kotlin <-> worklet: cmds 'live-start' {id,title} 'live-stop' 'live-frame' {kind,data}
//   'live-watch' {host,id,name} 'live-leave' {host} 'live-chat' {host?,text} 'live-heart' {host} 'live-keyreq' {host}
//   events 'live-viewers' {n} 'live-key' 'live-welcome' {host,title,viewers} 'live-video'/'live-audio' {host,data}
//   'live-chat' {host,from,name,text} 'live-hearts' {host,n} 'live-ended' {host} 'live-count' {host,viewers}

const MAX_VIEWERS = 60
const MAX_BACKLOG = 2 * 1024 * 1024 // a viewer this far behind skips video frames

module.exports = function createLive (x) {
  const { b4a, send } = x
  const HEX64 = /^[0-9a-f]{64}$/
  const links = new Map() // identity hex -> { ctl, bin, conn }
  let hosting = null // { id, title, viewers: Map(key -> name) }
  const watching = new Map() // host hex -> { id, name }
  let pendingHearts = 0
  let heartTimer = null

  function attach (conn, key, mux) {
    const channel = mux.createChannel({
      protocol: 'meshron/live/1',
      onopen () {
        const w = watching.get(key)
        if (w) sendCtl(key, { t: 'watch', id: w.id, name: w.name })
      },
      onclose () {
        if (links.get(key) && links.get(key).conn === conn) links.delete(key)
        if (hosting && hosting.viewers.delete(key)) count()
        if (watching.has(key)) { watching.delete(key); send({ ev: 'live-ended', host: key }) }
      }
    })
    if (!channel) return
    const ctl = channel.addMessage({ encoding: x.c.json, onmessage (m) { onCtl(key, conn, m) } })
    const bin = channel.addMessage({ encoding: x.c.raw, onmessage (b) { onBin(key, b) } })
    links.set(key, { ctl, bin, conn })
    channel.open()
  }

  function sendCtl (key, m) {
    const l = links.get(key)
    if (!l) return false
    try { l.ctl.send(m); return true } catch { return false }
  }

  function backlog (conn) {
    try { return conn.rawStream && conn.rawStream._writableState ? conn.rawStream._writableState.buffered : (conn._writableState ? conn._writableState.buffered : 0) } catch { return 0 }
  }

  function count () {
    if (!hosting) return
    const n = hosting.viewers.size
    send({ ev: 'live-viewers', n })
    for (const k of hosting.viewers.keys()) sendCtl(k, { t: 'count', viewers: n })
  }

  function broadcast (m) { if (hosting) for (const k of hosting.viewers.keys()) sendCtl(k, m) }

  function str (s, n) { return typeof s === 'string' ? s.slice(0, n) : '' }

  function onCtl (key, conn, m) {
    if (!m || typeof m !== 'object') return
    // Host side.
    if (hosting) {
      if (m.t === 'watch' && m.id === hosting.id) {
        if (!hosting.viewers.has(key) && hosting.viewers.size >= MAX_VIEWERS) return sendCtl(key, { t: 'end', full: true })
        if (!x.isKnown(key)) x.quiet(conn) // watching a live is never a contact request
        hosting.viewers.set(key, str(m.name, 32) || 'Someone')
        sendCtl(key, { t: 'welcome', id: hosting.id, title: hosting.title, viewers: hosting.viewers.size })
        send({ ev: 'live-key' })
        send({ ev: 'live-chat', host: '', from: key, name: hosting.viewers.get(key), text: '', joined: true })
        return count()
      }
      if (!hosting.viewers.has(key)) return
      if (m.t === 'leave') { hosting.viewers.delete(key); return count() }
      if (m.t === 'keyreq') return send({ ev: 'live-key' })
      if (m.t === 'chat') {
        const text = str(m.text, 200).trim()
        if (!text) return
        const out = { t: 'chat', from: key, name: hosting.viewers.get(key), text }
        broadcast(out)
        return send({ ev: 'live-chat', host: '', from: key, name: out.name, text })
      }
      if (m.t === 'heart') { pendingHearts++; return flushHeartsSoon() }
      return
    }
    // Viewer side.
    if (!watching.has(key)) return
    if (m.t === 'welcome') send({ ev: 'live-welcome', host: key, title: str(m.title, 100), viewers: m.viewers | 0 })
    else if (m.t === 'chat') send({ ev: 'live-chat', host: key, from: HEX64.test(m.from) ? m.from : '', name: str(m.name, 32), text: str(m.text, 200) })
    else if (m.t === 'hearts') send({ ev: 'live-hearts', host: key, n: Math.min(50, m.n | 0) })
    else if (m.t === 'count') send({ ev: 'live-count', host: key, viewers: m.viewers | 0 })
    else if (m.t === 'end') { watching.delete(key); send({ ev: 'live-ended', host: key, full: !!m.full }) }
  }

  function flushHeartsSoon () {
    if (heartTimer) return
    heartTimer = setTimeout(() => {
      heartTimer = null
      const n = pendingHearts
      pendingHearts = 0
      if (!n) return
      broadcast({ t: 'hearts', n })
      send({ ev: 'live-hearts', host: '', n })
    }, 400)
  }

  function onBin (key, b) {
    if (!watching.has(key) || !b || b.byteLength < 2) return
    const kind = b[0]
    const data = b4a.toString(b.subarray(1), 'base64')
    send({ ev: kind === 1 ? 'live-video' : 'live-audio', host: key, data })
  }

  function handle (cmd) {
    switch (cmd.cmd) {
      case 'live-start':
        hosting = { id: str(cmd.id, 40), title: str(cmd.title, 100), viewers: new Map() }
        return count()
      case 'live-stop':
        broadcast({ t: 'end' })
        hosting = null
        return
      case 'live-frame': {
        if (!hosting || !hosting.viewers.size) return
        const payload = b4a.from(cmd.data || '', 'base64')
        const frame = b4a.concat([b4a.from([cmd.kind === 'a' ? 2 : 1]), payload])
        const video = cmd.kind !== 'a'
        for (const k of hosting.viewers.keys()) {
          const l = links.get(k)
          if (!l) continue
          // Audio always goes; video is skipped for viewers whose link is backed up.
          if (video && backlog(l.conn) > MAX_BACKLOG) continue
          try { l.bin.send(frame) } catch {}
        }
        return
      }
      case 'live-watch': {
        if (!HEX64.test(cmd.host)) return
        watching.set(cmd.host, { id: str(cmd.id, 40), name: str(cmd.name, 32) })
        if (links.has(cmd.host)) sendCtl(cmd.host, { t: 'watch', id: str(cmd.id, 40), name: str(cmd.name, 32) })
        const s = x.swarm()
        if (s) s.joinPeer(b4a.from(cmd.host, 'hex'))
        return
      }
      case 'live-leave':
        if (watching.delete(cmd.host)) {
          sendCtl(cmd.host, { t: 'leave' })
          const s = x.swarm()
          if (s && !x.isKnown(cmd.host)) s.leavePeer(b4a.from(cmd.host, 'hex'))
        }
        return
      case 'live-chat': {
        const text = str(cmd.text, 200).trim()
        if (!text) return
        if (hosting && !cmd.host) {
          const out = { t: 'chat', from: '', name: x.name() || 'Host', text, host: true }
          broadcast(out)
          return send({ ev: 'live-chat', host: '', from: '', name: out.name, text })
        }
        return sendCtl(cmd.host, { t: 'chat', text })
      }
      case 'live-heart':
        if (hosting && !cmd.host) { pendingHearts++; return flushHeartsSoon() }
        return sendCtl(cmd.host, { t: 'heart' })
      case 'live-keyreq':
        return sendCtl(cmd.host, { t: 'keyreq' })
    }
  }

  return { attach, handle, isWatching: (k) => watching.has(k), get hosting () { return !!hosting } }
}
