// Group roles on the shared host (Windows/iPhone): Winnie (8787) and Sam (8788), who are friends.
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
  const W = 8787; const S = 8788
  const w = await call(W, 'init'); const s = await call(S, 'init')
  const sam = s.identity.publicKey; const win = w.identity.publicKey
  const conv = await call(W, 'group.create', { name: 'Role test ' + Date.now() % 1000, members: [sam] })
  const id = conv.slice(2)
  const sg = await until('Sam got the invite', async () => (await call(S, 'init')).groups.find((g) => g.id === id))
  assert.ok(sg.members.includes(sam))
  // Owner makes Sam an admin and renames the group.
  await call(W, 'group.admin', { id, who: sam, on: true })
  await call(W, 'group.info', { id, name: 'Renamed', bio: 'Weekend plans', icon: '' })
  await until('Sam sees admin + new name', async () => { const g = (await call(S, 'init')).groups.find((x) => x.id === id); return g && g.name === 'Renamed' && (g.admins || []).includes(sam) })
  console.log('admin + info synced')
  // An admin can't remove the owner.
  await call(S, 'group.remove', { id, who: win })
  await wait(4000)
  assert.ok((await call(W, 'init')).groups.find((g) => g.id === id).members.includes(win), 'owner stays')
  // Settings entries never show as chat messages.
  const rows = (await call(W, 'chat.open', { conv })).rows || []
  assert.ok(!rows.some((r) => r.kind === 'gmeta'), 'gmeta hidden')
  // Sam leaves.
  await call(S, 'group.leave', { id })
  await until('Winnie sees Sam left', async () => !(await call(W, 'init')).groups.find((g) => g.id === id).members.includes(sam))
  console.log('leave synced')
  console.log('ALL PASSED')
  process.exit(0)
})().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
