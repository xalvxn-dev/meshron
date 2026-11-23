// Meshron Assistant for Windows: controlling the person's other devices, and being controlled by
// them (same Meshron account only). Off by default: a device only takes commands when its owner
// turned on "Control from my other devices" on it. Commands run there with that device's own
// tools; anything that would normally ask for approval asks on the device that sent the command.
// Everything goes through the Meshron server: a list of the account's devices, and one small
// mailbox per device that it checks every few seconds (every 30 s when nothing is going on).

const os = require('os')
const crypto = require('crypto')

module.exports = function remote (hooks) {
  const { post, data, save, settings } = hooks
  const id = () => { if (!data.deviceId) { data.deviceId = 'pc-' + crypto.randomBytes(10).toString('hex'); save() } return data.deviceId }
  const myName = () => (os.hostname() || 'PC').replace(/-/g, ' ').slice(0, 40)
  let devices = []
  const pending = new Map() // command id -> { to, name, at }
  const decisions = new Map() // approval id -> resolve
  let timer = null
  let lastRegister = 0

  async function register () {
    if (!hooks.signedIn()) return devices
    try { const r = await post('/v1/remote/devices', { device: { id: id(), name: myName(), platform: 'windows' }, accept: !!settings().remote }); devices = r.devices || []; lastRegister = Date.now() } catch {}
    hooks.changed(devices)
    return devices
  }
  const send = (to, msg) => post('/v1/remote/send', { from: id(), to, msg })

  /** Which device a request is for: "on my phone, …" picks one by type; else the chosen target. */
  function route (text) {
    const m = /^(?:on|with|using) (?:my )?(phone|mobile|android|pc|computer|laptop|desktop|tablet)[,:]?\s+(.+)$/i.exec(String(text).trim())
    const others = devices.filter((d) => !d.me)
    if (m) {
      const want = /phone|mobile|android|tablet/i.test(m[1]) ? 'android' : 'windows'
      const d = others.find((x) => x.platform === want && x.online) || others.find((x) => x.platform === want)
      if (want === 'windows' && !d) return { local: m[2] } // "on my PC" while on this PC
      return d ? { device: d, text: m[2] } : { missing: m[1] }
    }
    const t = settings().remoteTarget
    if (t && t !== id()) { const d = others.find((x) => x.id === t); if (d) return { device: d, text } }
    return null
  }

  /** Sends a command; the answer comes back through the mailbox. */
  async function command (device, text) {
    const cid = crypto.randomBytes(8).toString('hex')
    await send(device.id, { kind: 'cmd', id: cid, text })
    pending.set(cid, { to: device.id, name: device.name, at: Date.now() })
    schedule(500)
    return cid
  }

  /** While running a command from another device: approval questions go to that device. */
  function askApproval (ctx, title, detail) {
    const aid = crypto.randomBytes(6).toString('hex')
    return new Promise((resolve) => {
      decisions.set(aid, resolve)
      send(ctx.from, { kind: 'ask', id: ctx.id, aid, title, detail }).catch(() => resolve(false))
      setTimeout(() => { if (decisions.has(aid)) { decisions.delete(aid); resolve(false) } }, 120000)
    })
  }

  async function poll () {
    timer = null
    if (!hooks.signedIn()) return schedule(30000)
    if (Date.now() - lastRegister > 60000) await register()
    try {
      const r = await post('/v1/remote/inbox', { device: id() })
      for (const m of r.messages || []) {
        if (m.kind === 'cmd') hooks.runCommand(m)
        if (m.kind === 'reply') { const p = pending.get(m.id); if (m.done) pending.delete(m.id); hooks.showReply(m, p) }
        if (m.kind === 'ask') hooks.approveFor(m).then((ok) => send(m.from, { kind: 'decide', id: m.id, aid: m.aid, ok }).catch(() => {}))
        if (m.kind === 'decide') { const r2 = decisions.get(m.aid); if (r2) { decisions.delete(m.aid); r2(!!m.ok) } }
      }
    } catch {}
    for (const [k, p] of pending) if (Date.now() - p.at > 180000) pending.delete(k)
    schedule(settings().remote || pending.size || decisions.size ? 3000 : 30000)
  }
  function schedule (ms) { clearTimeout(timer); timer = setTimeout(poll, ms) }

  return { id, register, route, command, askApproval, send, start: () => schedule(1500), devices: () => devices, kick: () => schedule(300) }
}
