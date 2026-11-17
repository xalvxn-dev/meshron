// "Who can comment" across dev servers: Winnie (8787) limits comments; Tess (8789, not a friend
// or follower) is refused while Sam (8788) gets in under "friends" (Sam and Winnie follow each other).
const assert = require('assert')
const call = async (port, method, args = {}) => {
  const r = await fetch('http://127.0.0.1:' + port + '/call', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ method, args }) })
  const o = await r.json()
  if (o.error) throw new Error(port + ' ' + method + ': ' + o.error)
  return o.result
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
async function until (what, fn, ms = 60000) {
  const end = Date.now() + ms
  while (Date.now() < end) { const v = await fn().catch(() => null); if (v) return v; await wait(1500) }
  throw new Error('timed out: ' + what)
}

;(async () => {
  const W = 8787; const S = 8788; const T = 8789
  for (const p of [W, S, T]) await call(p, 'init')
  await until('Reels running', async () => (await Promise.all([W, S, T].map((p) => call(p, 'reels.status')))).every((x) => x.ready))
  const wch = (await call(W, 'reels.status')).ch
  const reel = (await call(W, 'reels.feed', { kind: 'user', ch: wch, limit: 50 })).items.find((i) => i.cap.includes('#1'))
  const sch = (await call(S, 'reels.status')).ch
  // Friends follow each other; Tess follows nobody.
  await call(S, 'reels.follow', { ch: wch, on: true })
  await call(W, 'reels.follow', { ch: sch, on: true })
  await call(T, 'reels.follow', { ch: wch, on: false })
  try {
    await call(W, 'reels.cmFrom', { v: 'friends' })
    // Viewers learn the rule from Winnie's profile.
    await until('Tess sees the rule', async () => (await call(T, 'reels.profile', { ch: wch })).cm === 'friends')
    const tag = 'rule-' + Date.now()
    const tcid = await call(T, 'reels.comment', { ch: wch, id: reel.id, text: 'Tess ' + tag })
    const scid = await call(S, 'reels.comment', { ch: wch, id: reel.id, text: 'Sam ' + tag })
    await until('Sam (a friend) gets in', async () => (await call(W, 'reels.comments', { ch: wch, id: reel.id })).some((c) => c.cid === scid))
    await wait(8000)
    assert.ok(!(await call(W, 'reels.comments', { ch: wch, id: reel.id })).some((c) => c.cid === tcid), 'stranger refused')
    console.log('friends-only: friend in, stranger refused')
  } finally {
    await call(W, 'reels.cmFrom', { v: 'everyone' })
  }
  console.log('ALL PASSED')
  process.exit(0)
})().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
