// Chat requests between strangers who already share a Reels link: Tess (8789) adds Sam (8788).
// With requests off, Sam gets nothing; with requests on, Sam gets a request.
const assert = require('assert')
const call = async (port, method, args = {}) => {
  const r = await fetch('http://127.0.0.1:' + port + '/call', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ method, args }) })
  const o = await r.json()
  if (o.error) throw new Error(port + ' ' + method + ': ' + o.error)
  return o.result
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
async function until (what, fn, ms = 90000) {
  const end = Date.now() + ms
  while (Date.now() < end) { const v = await fn().catch(() => null); if (v) return v; await wait(1500) }
  throw new Error('timed out: ' + what)
}

;(async () => {
  const S = 8788; const T = 8789
  const sam = (await call(S, 'init')).identity.publicKey
  const tess = (await call(T, 'init')).identity.publicKey
  await until('Reels running', async () => (await call(S, 'reels.status')).ready && (await call(T, 'reels.status')).ready)
  // Start clean: they aren't contacts.
  await call(T, 'contacts.remove', { key: sam }).catch(() => {})
  await call(S, 'contacts.remove', { key: tess }).catch(() => {})
  try {
    await call(S, 'prefs.set', { key: 'allowRequests', value: false })
    await call(T, 'contacts.add', { key: sam, name: 'Sam' })
    await wait(15000)
    assert.ok(!(await call(S, 'init')).contacts.some((c) => c.keyHex === tess), 'no request while off')
    console.log('off: no request')
    await call(S, 'prefs.set', { key: 'allowRequests', value: true })
    // Tess adds again (a new attempt).
    await call(T, 'contacts.remove', { key: sam })
    await wait(2000)
    await call(T, 'contacts.add', { key: sam, name: 'Sam' })
    const c = await until('request arrives', async () => (await call(S, 'init')).contacts.find((x) => x.keyHex === tess && x.status === 'REQUEST'), 120000)
    console.log('on: request from', c.name || c.keyHex.slice(0, 8))
  } finally {
    await call(S, 'prefs.set', { key: 'allowRequests', value: true }).catch(() => {})
    await call(T, 'contacts.remove', { key: sam }).catch(() => {})
    await call(S, 'contacts.remove', { key: tess }).catch(() => {})
  }
  console.log('ALL PASSED')
  process.exit(0)
})().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
