// Reposts + collaborators across three local dev servers (Winnie 8787, Sam 8788, Tess 8789).
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
  for (const p of [W, S, T]) await call(p, 'init') // starts each test peer's network
  await until('Reels running everywhere', async () => (await Promise.all([W, S, T].map((p) => call(p, 'reels.status')))).every((x) => x.ready))
  const wch = (await call(W, 'reels.status')).ch
  const tch = (await call(T, 'reels.status')).ch
  const sam = (await call(S, 'init')).identity.publicKey
  const reel = (await call(W, 'reels.feed', { kind: 'user', ch: wch, limit: 50 })).items.find((i) => i.cap.includes('#1'))
  assert.ok(reel, 'Winnie has a reel')

  // Tess reposts it, quoting a comment.
  await until('Tess sees the reel', async () => (await call(T, 'reels.feed', { kind: 'user', ch: wch, limit: 50 })).items.some((i) => i.id === reel.id))
  await call(T, 'reels.repost', { ch: wch, id: reel.id, on: true, quote: 'This one is great' })
  const mine = (await call(T, 'reels.feed', { kind: 'reposts', ch: tch })).items
  assert.ok(mine.some((i) => i.id === reel.id && i.quote === 'This one is great'), 'shows on Tess\'s reposts')

  // Sam follows Tess: the repost shows up in Following, marked as reposted by Tess.
  await until('Sam knows Tess', async () => (await call(S, 'reels.profile', { ch: tch })))
  await call(S, 'reels.follow', { ch: tch, on: true })
  const seen = await until('repost in Sam\'s Following', async () =>
    (await call(S, 'reels.feed', { kind: 'following' })).items.find((i) => i.id === reel.id && i.reposter && i.reposter.ch === tch))
  console.log('Sam sees repost by', seen.reposter.name)

  // Winnie's count goes up once Tess and Winnie sync.
  await until('Winnie counts the repost', async () => (await call(W, 'reels.feed', { kind: 'user', ch: wch, limit: 50 })).items.find((i) => i.id === reel.id).reposts >= 1)
  console.log('Winnie repost count ok')

  // Collab: Winnie invites Sam, Sam accepts, Winnie confirms.
  await call(W, 'reels.invite', { id: reel.id, who: sam })
  await call(S, 'reels.collabAccept', { ch: wch, id: reel.id })
  await until('Winnie confirms Sam', async () =>
    (await call(W, 'reels.feed', { kind: 'user', ch: wch, limit: 50 })).items.find((i) => i.id === reel.id).collabs.some((a) => a.id === sam))
  console.log('collab confirmed')
  // An uninvited claim is ignored: Tess claims too, but stays off.
  await call(T, 'reels.collabAccept', { ch: wch, id: reel.id })
  await wait(8000)
  const after = (await call(W, 'reels.feed', { kind: 'user', ch: wch, limit: 50 })).items.find((i) => i.id === reel.id)
  assert.ok(!after.collabs.some((a) => a.ch === tch), 'uninvited claim ignored')
  // Replies to comments: Sam comments, Tess replies to it; Winnie's channel keeps the thread.
  const cid = await call(S, 'reels.comment', { ch: wch, id: reel.id, text: 'Sam here' })
  await until('Sam comment lands', async () => (await call(W, 'reels.comments', { ch: wch, id: reel.id })).some((c) => c.cid === cid))
  await until('Tess sees it', async () => (await call(T, 'reels.comments', { ch: wch, id: reel.id })).some((c) => c.cid === cid))
  const rid = await call(T, 'reels.comment', { ch: wch, id: reel.id, text: 'reply from Tess', re: cid })
  const reply = await until('reply lands threaded', async () => (await call(W, 'reels.comments', { ch: wch, id: reel.id })).find((c) => c.cid === rid && c.re === cid))
  console.log('reply threaded under', reply.re.slice(0, 6))
  console.log('ALL PASSED')
})().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
