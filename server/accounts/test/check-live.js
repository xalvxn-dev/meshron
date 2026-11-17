// Live test of /v1/mod/check with a throwaway identity.
const sodium = require('sodium-native'); const crypto = require('crypto')
const BASE = process.argv[2] || 'https://meshron-accounts.meshron.workers.dev'
const pk = Buffer.alloc(32); const sk = Buffer.alloc(64); sodium.crypto_sign_keypair(pk, sk)
async function check (kind, text, adult) {
  const p = JSON.stringify({ kind, text, adult, lang: 'English' }); const ts = Date.now(); const path = '/v1/mod/check'
  const msg = 'meshron-mod-v1\n' + path + '\n' + ts + '\n' + crypto.createHash('sha256').update(p).digest('hex')
  const sig = Buffer.alloc(64); sodium.crypto_sign_detached(sig, Buffer.from(msg), sk)
  const t0 = Date.now()
  for (let i = 0; i < 3; i++) {
    try {
      const r = await fetch(BASE + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ id: pk.toString('hex'), ts, sig: sig.toString('hex'), p }) })
      console.log(kind, adult ? '18+' : '', JSON.stringify(text).slice(0, 40), r.status, (Date.now() - t0) + 'ms', (await r.text()).slice(0, 240)); return
    } catch (e) { await new Promise((r) => setTimeout(r, 3000)) }
  }
  console.log(kind, 'network failed')
}
;(async () => {
  await check('comment', 'This video is so good, love it!')
  await check('comment', 'you are ugly and stupid, kill yourself')
  await check('group', 'this party was fucking wild lol', false)
  await check('group', 'this party was fucking wild lol', true)
  await check('username', 'meshron_official_support')
  await check('bio', 'Buy followers cheap DM me 100k for 5$ !!!')
})()
