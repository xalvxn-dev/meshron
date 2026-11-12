// Same Wi-Fi / hotspot links for Windows, compatible with Android's LanTransport:
// each device advertises "_meshron._tcp" named "meshron-<session>" (random per run) and the
// one whose session ID is smaller dials, so two devices make one connection, not two.
// The raw TCP bytes go to the networking worklet, which runs the usual encrypted handshake
// over them, so nobody on the Wi-Fi can read or fake messages.

const net = require('net')
const crypto = require('crypto')

const PREFIX = 'meshron-'

function startLan({ call, log = () => {} }) {
  const { Bonjour } = require('bonjour-service')
  const sessionId = crypto.randomBytes(8).toString('hex')
  const sockets = new Map() // link id -> socket
  const dialled = new Map() // session ID -> socket we dialled
  let bonjour = null
  let server = null
  let browser = null
  let stopped = false

  async function attach(sock, initiator) {
    sock.setNoDelay(true)
    sock.setKeepAlive(true, 15000)
    const early = []
    let id = null
    sock.on('data', (d) => {
      if (id) call('link.data', { id, data: d.toString('base64') }).catch(() => {})
      else early.push(d)
    })
    sock.on('error', () => {})
    sock.on('close', () => {
      if (id && sockets.delete(id)) call('link.close', { id }).catch(() => {})
    })
    try {
      id = await call('link.open', { via: 'lan', initiator })
    } catch (e) {
      sock.destroy()
      return
    }
    if (sock.destroyed) return call('link.close', { id }).catch(() => {})
    sockets.set(id, sock)
    for (const d of early.splice(0)) call('link.data', { id, data: d.toString('base64') }).catch(() => {})
  }

  function dial(sid, service) {
    if (stopped || dialled.has(sid)) return
    const addr = (service.addresses || []).find((a) => net.isIPv4(a)) || (service.referer && service.referer.address)
    if (!addr || !service.port) return
    const sock = net.connect({ host: addr, port: service.port, timeout: 8000 })
    dialled.set(sid, sock)
    sock.once('timeout', () => sock.destroy())
    sock.once('close', () => { if (dialled.get(sid) === sock) dialled.delete(sid) })
    sock.once('connect', () => { sock.setTimeout(0); attach(sock, true) })
    sock.on('error', (e) => log('lan dial ' + e.message))
  }

  server = net.createServer((sock) => attach(sock, false))
  server.on('error', (e) => log('lan server ' + e.message))
  server.listen(0, () => {
    if (stopped) return
    try {
      bonjour = new Bonjour({}, (e) => log('lan mdns ' + (e && e.message)))
      bonjour.publish({ name: PREFIX + sessionId, type: 'meshron', protocol: 'tcp', port: server.address().port })
      const seen = (service) => {
        if (!service.name || !service.name.startsWith(PREFIX)) return
        const sid = service.name.slice(PREFIX.length)
        if (sid !== sessionId && sid > sessionId) dial(sid, service)
      }
      browser = bonjour.find({ type: 'meshron', protocol: 'tcp' }, seen)
      // Phones come and go, and a dial can fail while the app is still starting: retry.
      let n = 0
      const t = setInterval(() => {
        if (!browser) return
        for (const s of browser.services) seen(s)
        if (++n % 3 === 0) browser.update()
      }, 10000)
      t.unref && t.unref()
    } catch (e) {
      log('lan mdns ' + e.message)
    }
  })

  return {
    /** Worklet events for links this transport owns. */
    onLink(e) {
      const sock = sockets.get(e.id)
      if (!sock) return
      if (e.ev === 'link-data') sock.write(Buffer.from(e.data, 'base64'))
      else if (e.ev === 'link-close') {
        sockets.delete(e.id)
        sock.destroy()
      }
    },
    /** Network changed (new Wi-Fi): look again. */
    refresh() {
      if (browser) browser.update()
    },
    stop() {
      stopped = true
      for (const s of sockets.values()) s.destroy()
      for (const s of dialled.values()) s.destroy()
      try { if (browser) browser.stop() } catch {}
      try { if (bonjour) bonjour.unpublishAll(() => bonjour.destroy()) } catch {}
      try { server.close() } catch {}
    }
  }
}

module.exports = { startLan }
