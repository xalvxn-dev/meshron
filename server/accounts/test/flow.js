// End-to-end test of the account server (run against `wrangler dev`, which has DEV_CODES=1).
// Also the reference for the app's account crypto:
//   salt     = first 16 bytes of SHA-256("meshron-account-v1:" + username)
//   master   = Argon2id(password, salt, ops 3, mem 64 MiB, 64 bytes)
//   authKey  = master[0..32]  (sent; the server stores SHA-256 of its hex)
//   lockKey  = master[32..64] (never sent)
//   blob     = base64(nonce12 || ChaCha20-Poly1305-IETF(lockKey, nonce, seed, ad="meshron-blob-v1"))
const sodium = require('sodium-universal')
const assert = require('assert')
const URL = process.argv[2] || 'http://127.0.0.1:8799'

const call = async (path, body) => {
  const r = await fetch(URL + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  const o = await r.json()
  return { status: r.status, ...o }
}
function derive (username, password) {
  const h = Buffer.alloc(32)
  sodium.crypto_hash_sha256(h, Buffer.from('meshron-account-v1:' + username))
  const master = Buffer.alloc(64)
  sodium.crypto_pwhash(master, Buffer.from(password), h.subarray(0, 16), 3, 64 * 1024 * 1024, sodium.crypto_pwhash_ALG_ARGON2ID13)
  return { authKey: master.subarray(0, 32).toString('hex'), lockKey: master.subarray(32) }
}
function lock (lockKey, seed) {
  const nonce = Buffer.alloc(12); sodium.randombytes_buf(nonce)
  const out = Buffer.alloc(seed.length + 16)
  sodium.crypto_aead_chacha20poly1305_ietf_encrypt(out, seed, Buffer.from('meshron-blob-v1'), null, nonce, lockKey)
  return Buffer.concat([nonce, out]).toString('base64')
}
function unlock (lockKey, blob) {
  const b = Buffer.from(blob, 'base64')
  const out = Buffer.alloc(b.length - 12 - 16)
  sodium.crypto_aead_chacha20poly1305_ietf_decrypt(out, null, b.subarray(12), Buffer.from('meshron-blob-v1'), b.subarray(0, 12), lockKey)
  return out
}

;(async () => {
  const user = 'tester_' + Math.floor(Math.random() * 1e6)
  const email = user + '@example.com'
  const seed = Buffer.alloc(32); sodium.randombytes_buf(seed)
  const pk = Buffer.alloc(32); const sk = Buffer.alloc(64)
  sodium.crypto_sign_seed_keypair(pk, sk, seed)
  const sig = Buffer.alloc(64); sodium.crypto_sign_detached(sig, Buffer.from('meshron-signup-v1:' + user), sk)
  const t0 = Date.now()
  const { authKey, lockKey } = derive(user, 'correct horse battery')
  console.log('argon2id ms', Date.now() - t0)

  assert.equal((await call('/v1/check', { username: user })).available, true)
  const c1 = await call('/v1/signup/code', { email })
  assert.ok(c1.devCode, JSON.stringify(c1))
  assert.equal((await call('/v1/signup', { username: user, email, code: '000000x', authKey, blob: lock(lockKey, seed), publicKey: pk.toString('hex'), sig: sig.toString('hex') })).error, 'Wrong code')
  const s = await call('/v1/signup', { username: user, email, code: c1.devCode, authKey, blob: lock(lockKey, seed), publicKey: pk.toString('hex'), sig: sig.toString('hex') })
  assert.equal(s.ok, true, JSON.stringify(s))
  assert.equal((await call('/v1/check', { username: user })).available, false)
  assert.equal((await call('/v1/resolve', { username: '@' + user.toUpperCase() })).publicKey, pk.toString('hex'))

  // Wrong password is refused; right one returns a blob that unlocks to the same seed.
  assert.equal((await call('/v1/login', { username: user, authKey: derive(user, 'wrong').authKey })).status, 401)
  const l = await call('/v1/login', { username: user, authKey })
  assert.ok(unlock(lockKey, l.blob).equals(seed))

  // Turn 2-step on: login now needs a code.
  assert.equal((await call('/v1/account', { username: user, authKey, twoFactor: true })).twoFactor, true)
  const l2 = await call('/v1/login', { username: user, authKey })
  assert.equal(l2.needCode, true); assert.equal(l2.blob, undefined)
  const l3 = await call('/v1/login/code', { username: user, authKey, code: l2.devCode })
  assert.ok(unlock(lockKey, l3.blob).equals(seed))
  // Turning it off needs a code too.
  assert.ok((await call('/v1/account', { username: user, authKey, twoFactor: false })).error)
  const cc = await call('/v1/account/code', { username: user, authKey })
  assert.equal((await call('/v1/account', { username: user, authKey, twoFactor: false, code: cc.devCode })).twoFactor, false)

  // AI (against test/mock-ai.js): needs the account, answers each task.
  assert.equal((await call('/v1/ai', { username: user, authKey: derive(user, 'wrong').authKey, task: 'helper', text: 'hi' })).status, 401)
  assert.ok((await call('/v1/ai', { username: user, authKey, task: 'helper', text: 'hello' })).text.includes('hello'))
  const cap = await call('/v1/ai', { username: user, authKey, task: 'caption', text: 'beach at sunset' })
  assert.deepEqual(cap.hashtags, ['#sunset', '#chill', '#meshron'])
  assert.equal((await call('/v1/ai', { username: user, authKey, task: 'replies', history: [{ mine: false, text: 'dinner at 6?' }] })).replies.length, 3)
  assert.equal((await call('/v1/ai', { username: user, authKey, task: 'translate', text: 'Hi, how are you?', lang: 'Spanish' })).text, 'Hola, ¿cómo estás?')
  assert.equal((await call('/v1/ai', { username: user, authKey, task: 'moderate', text: 'you idiot' })).ok, false)
  assert.equal((await call('/v1/ai', { username: user, authKey, task: 'moderate', text: 'nice video' })).ok, true)

  // Reserved usernames: not available, can't be taken without the invite code.
  assert.equal((await call('/v1/check', { username: 'alvinnanakojopieterson' })).available, false)
  // Rename: needs new keys (they depend on the username); old name is freed.
  const newName = user + 'x'
  const k2 = derive(newName, 'correct horse battery')
  assert.equal((await call('/v1/rename', { username: user, authKey, newUsername: 'clementkofiyeboah', newAuthKey: k2.authKey, newBlob: lock(k2.lockKey, seed) })).error, 'That username is reserved')
  assert.equal((await call('/v1/rename', { username: user, authKey, newUsername: newName, newAuthKey: k2.authKey, newBlob: lock(k2.lockKey, seed) })).username, newName)
  // A second change within 30 days is refused.
  const again = await call('/v1/rename', { username: newName, authKey: k2.authKey, newUsername: newName + 'x', newAuthKey: k2.authKey, newBlob: lock(k2.lockKey, seed) })
  assert.match(String(again.error), /again on/)
  assert.equal((await call('/v1/check', { username: user })).available, true)
  assert.ok(unlock(k2.lockKey, (await call('/v1/login', { username: newName, authKey: k2.authKey })).blob).equals(seed))
  assert.equal((await call('/v1/resolve', { username: newName })).publicKey, pk.toString('hex'))

  // Duplicate username / email refused.
  assert.ok((await call('/v1/signup/code', { email })).error) // email still taken after rename
  console.log('ALL PASSED for', user)
})().catch((e) => { console.error('FAILED', e); process.exit(1) })
