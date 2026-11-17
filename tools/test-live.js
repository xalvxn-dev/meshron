// Live across local dev servers: Winnie (8787) goes live, Sam (8788) and Tess (8789) watch.
const assert = require('assert')
const http = require('http')
const call = async (port, method, args = {}) => {
  const r = await fetch('http://127.0.0.1:' + port + '/call', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ method, args }) })
  const o = await r.json()
  if (o.error) throw new Error(port + ' ' + method + ': ' + o.error)
  return o.result
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms))
async function until (what, fn, ms = 60000) {
  const end = Date.now() + ms
  while (Date.now() < end) { const v = await fn().catch(() => null); if (v) return v; await wait(1000) }
  throw new Error('timed out: ' + what)
}
/** Collects a dev server's events (Server-Sent Events). */
function listen (port) {
  const got = []
  http.get('http://127.0.0.1:' + port + '/events', (res) => {
    let buf = ''
    res.on('data', (d) => {
      buf += d
      let i
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i); buf = buf.slice(i + 2)
        const ev = /^event: (.*)$/m.exec(block); const data = /^data: (.*)$/m.exec(block)
        if (data) { try { const o = JSON.parse(data[1]); got.push({ ev: ev ? ev[1] : o.ev || o.type, ...(o.data || o) }) } catch {} }
      }
    })
  })
  return got
}

;(async () => {
  const W = 8787; const S = 8788; const T = 8789
  for (const p of [W, S, T]) await call(p, 'init')
  await until('Reels running', async () => (await Promise.all([W, S, T].map((p) => call(p, 'reels.status')))).every((x) => x.ready))
  const winnie = (await call(W, 'init')).identity.publicKey
  const ws = listen(W); const ss = listen(S); const ts = listen(T)
  await wait(500)

  await call(W, 'live.start', { id: 'live1', title: 'Testing live' })
  // Sam finds it in "who's live" (he knows Winnie's channel) and joins.
  const seen = await until('Sam sees Winnie live', async () => (await call(S, 'reels.lives')).find((l) => l.id === winnie))
  assert.strictEqual(seen.title, 'Testing live')
  await call(S, 'live.watch', { host: winnie, id: seen.lid })
  await call(T, 'live.watch', { host: winnie, id: 'live1' })
  await until('Sam welcomed', async () => ss.find((e) => e.ev === 'live-welcome'))
  await until('Tess welcomed', async () => ts.find((e) => e.ev === 'live-welcome'))
  await until('Winnie counts 2 viewers', async () => ws.find((e) => e.ev === 'live-viewers' && e.n === 2))
  console.log('2 viewers joined')

  // Frames fan out.
  for (let i = 0; i < 5; i++) await call(W, 'live.frame', { kind: 'v', data: Buffer.from('frame' + i).toString('base64') })
  await call(W, 'live.frame', { kind: 'a', data: Buffer.from('audio').toString('base64') })
  await until('Sam got video', async () => ss.filter((e) => e.ev === 'live-video').length >= 5)
  await until('Tess got audio', async () => ts.find((e) => e.ev === 'live-audio' && Buffer.from(e.data, 'base64').toString() === 'audio'))
  console.log('video + audio fanned out')

  // Chat and hearts go through the host to everyone.
  await call(S, 'live.chat', { host: winnie, text: 'hi from Sam' })
  await until('Tess sees Sam chat', async () => ts.find((e) => e.ev === 'live-chat' && e.text === 'hi from Sam'))
  await until('Winnie sees Sam chat', async () => ws.find((e) => e.ev === 'live-chat' && e.text === 'hi from Sam'))
  await call(T, 'live.heart', { host: winnie }); await call(T, 'live.heart', { host: winnie })
  await until('Sam sees hearts', async () => ss.find((e) => e.ev === 'live-hearts' && e.n >= 1))
  await call(W, 'live.chat', { text: 'thanks for watching' })
  await until('Sam sees host chat', async () => ss.find((e) => e.ev === 'live-chat' && e.text === 'thanks for watching'))
  console.log('chat + hearts ok')

  // Tess leaves; Winnie ends.
  await call(T, 'live.leave', { host: winnie })
  await until('Winnie counts 1 viewer', async () => ws.slice().reverse().find((e) => e.ev === 'live-viewers')?.n === 1)
  await call(W, 'live.stop', { id: 'live1' })
  await until('Sam told it ended', async () => ss.find((e) => e.ev === 'live-ended'))
  await until('Gone from lives', async () => !(await call(S, 'reels.lives')).some((l) => l.id === winnie))
  console.log('ALL PASSED')
  process.exit(0)
})().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
