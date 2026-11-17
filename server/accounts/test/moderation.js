// Moderation end to end. Run the local server with a test admin:
//   npx wrangler dev --local --port 8799 --env test
// (.dev.vars: DEV_CODES=1, AI_URL -> test/mock-ai.js).

const sodium = require('sodium-universal')
const assert = require('assert')
const crypto = require('crypto')
const URL = process.argv[2] || 'http://127.0.0.1:8799'

const call = async (path, body) => {
  const r = await fetch(URL + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  return { status: r.status, ...(await r.json()) }
}
function person () {
  const seed = Buffer.alloc(32); sodium.randombytes_buf(seed)
  const pk = Buffer.alloc(32); const sk = Buffer.alloc(64)
  sodium.crypto_sign_seed_keypair(pk, sk, seed)
  return { id: pk.toString('hex'), sk }
}
/** A request signed with a Meshron identity, as the app sends it. */
function signed (who, path, payload) {
  const p = JSON.stringify(payload)
  const ts = Date.now()
  const msg = 'meshron-mod-v1\n' + path + '\n' + ts + '\n' + crypto.createHash('sha256').update(p).digest('hex')
  const sig = Buffer.alloc(64); sodium.crypto_sign_detached(sig, Buffer.from(msg), who.sk)
  return call(path, { id: who.id, ts, sig: sig.toString('hex'), p })
}
function derive (username, password) {
  const h = Buffer.alloc(32)
  sodium.crypto_hash_sha256(h, Buffer.from('meshron-account-v1:' + username))
  const master = Buffer.alloc(64)
  sodium.crypto_pwhash(master, Buffer.from(password), h.subarray(0, 16), 3, 64 * 1024 * 1024, sodium.crypto_pwhash_ALG_ARGON2ID13)
  return master.subarray(0, 32).toString('hex')
}

;(async () => {
  const reporter = person(); const owner = person(); const bad = person()

  // A forged signature is refused.
  const forged = await call('/v1/report', { id: reporter.id, ts: Date.now(), sig: '00'.repeat(64), p: '{}' })
  assert.strictEqual(forged.status, 401)

  // A threatening reel: the AI moderator hides it, both sides are told, moderators get an email.
  const r1 = await signed(reporter, '/v1/report', {
    kind: 'reel', target: 'ch1:reelA', owner: owner.id, name: '@owner', reason: 'Violence or dangerous acts',
    details: 'scary', content: 'Caption: I will kill you', page: 'Discover, For You', screenshot: Buffer.from('fakejpeg').toString('base64')
  })
  assert.strictEqual(r1.action, 'hide', JSON.stringify(r1))
  assert.ok(r1.devEmail && r1.devEmail.to.includes('xalvxn@gmail.com') && r1.devEmail.shot, 'emailed with screenshot')
  let st = await call('/v1/mod/status', { items: ['reel:ch1:reelA', 'reel:ch1:other'], people: [owner.id] })
  assert.deepStrictEqual(st.hidden.map((h) => h.key), ['reel:ch1:reelA'])
  const ownerBox = await signed(owner, '/v1/mod/inbox', {})
  assert.ok(ownerBox.notices.some((n) => n.action === 'hide' && n.target === 'ch1:reelA'), 'owner told')
  assert.ok((await signed(reporter, '/v1/mod/inbox', {})).notices.some((n) => n.action === 'report'), 'reporter thanked')
  console.log('AI hid the reel; notices + email ok')

  // A harmless report changes nothing.
  const r2 = await signed(reporter, '/v1/report', { kind: 'comment', target: 'ch1:reelA:c1', owner: owner.id, reason: 'Spam', content: 'nice video' })
  assert.strictEqual(r2.action, 'none')
  st = await call('/v1/mod/status', { items: ['comment:ch1:reelA:c1'] })
  assert.strictEqual(st.hidden.length, 0)

  // Only the owner can appeal.
  assert.strictEqual((await signed(reporter, '/v1/mod/appeal', { kind: 'reel', target: 'ch1:reelA', text: 'mine!' })).status, 403)
  assert.ok((await signed(owner, '/v1/mod/appeal', { kind: 'reel', target: 'ch1:reelA', text: 'It was a movie quote' })).ok)

  // Moderators: a non-moderator is refused; modtester (in ADMINS) sees the queue and restores it.
  const user = 'modtester'
  const authKey = derive(user, 'moderator pass 123')
  if ((await call('/v1/check', { username: user })).available) {
    const email = 'modtester@example.com'
    const code = (await call('/v1/signup/code', { email })).devCode
    const me = person()
    const sig = Buffer.alloc(64); sodium.crypto_sign_detached(sig, Buffer.from('meshron-signup-v1:' + user), me.sk)
    const s = await call('/v1/signup', { username: user, email, code, authKey, blob: Buffer.alloc(60).toString('base64'), publicKey: me.id, sig: sig.toString('hex') })
    assert.ok(s.ok, JSON.stringify(s))
  }
  assert.strictEqual((await call('/v1/mod/me', { username: user, authKey })).role, 'admin')
  const q = await call('/v1/mod/queue', { username: user, authKey })
  assert.ok(q.reports.some((r) => r.id === r1.id), 'report queued for humans')
  assert.ok(q.appeals.some((a) => a.target === 'ch1:reelA'), 'appeal queued')
  assert.ok((await call('/v1/mod/act', { username: user, authKey, action: 'restore', kind: 'reel', target: 'ch1:reelA', report: r1.id })).ok)
  st = await call('/v1/mod/status', { items: ['reel:ch1:reelA'] })
  assert.strictEqual(st.hidden.length, 0, 'restored')
  assert.ok((await signed(owner, '/v1/mod/inbox', {})).notices.some((n) => n.action === 'restore' && /won your appeal/.test(n.text)), 'owner told it is back')
  console.log('appeal won, restored')

  // Severe abuse: banned; an admin lifts it.
  const r3 = await signed(reporter, '/v1/report', { kind: 'person', target: bad.id, owner: bad.id, reason: 'Violence or dangerous acts', content: 'ban me now' })
  assert.strictEqual(r3.action, 'ban')
  st = await call('/v1/mod/status', { people: [bad.id] })
  assert.deepStrictEqual(st.banned, [bad.id])
  assert.ok((await signed(bad, '/v1/mod/inbox', {})).banned, 'they see the suspension')
  assert.ok((await call('/v1/mod/act', { username: user, authKey, action: 'unban', who: bad.id })).ok)
  assert.strictEqual((await call('/v1/mod/status', { people: [bad.id] })).banned.length, 0)
  // Verification badge.
  assert.ok((await call('/v1/mod/act', { username: user, authKey, action: 'verify', who: owner.id })).ok)
  assert.deepStrictEqual((await call('/v1/mod/status', { people: [owner.id] })).verified, [owner.id])
  console.log('ban, unban, verify ok')

  // Badges: the verified owner, the moderator (modtester), and nobody else.
  const modPk = (await call('/v1/resolve', { username: user })).publicKey
  const b = (await call('/v1/badges', { people: [owner.id, modPk, reporter.id] })).badges
  assert.ok(b[owner.id] && b[owner.id].verified && !b[owner.id].mod, 'owner verified')
  assert.ok(b[modPk] && b[modPk].mod && b[modPk].verified, 'moderator badge + check')
  assert.ok(!b[reporter.id], 'no badge for others')
  // Asking for verification: queued for moderators, then approved.
  assert.ok((await signed(reporter, '/v1/verify/request', { name: 'Rep Orter', username: 'reporter', category: 'Creator', reason: 'I make videos', links: 'https://example.com' })).ok)
  assert.ok((await call('/v1/mod/queue', { username: user, authKey })).verifications.some((v) => v.id === reporter.id), 'request queued')
  assert.ok((await call('/v1/mod/act', { username: user, authKey, action: 'verify', who: reporter.id })).ok)

  // Applying to moderate: needs an account; the CEOs (admins) approve; moderators can't change roles.
  assert.strictEqual((await signed(reporter, '/v1/mod/apply', { name: 'Rep', why: 'I want to help' })).status, 400, 'no account, no application')
  const hopeful = 'modhopeful' + Date.now().toString(36).slice(-4)
  const hopefulKey = derive(hopeful, 'hopeful pass 123')
  const hp = person()
  {
    const email = hopeful + '@example.com'
    const code = (await call('/v1/signup/code', { email })).devCode
    const sig = Buffer.alloc(64); sodium.crypto_sign_detached(sig, Buffer.from('meshron-signup-v1:' + hopeful), hp.sk)
    const s = await call('/v1/signup', { username: hopeful, email, code, authKey: hopefulKey, blob: Buffer.alloc(60).toString('base64'), publicKey: hp.id, sig: sig.toString('hex') })
    assert.ok(s.ok, JSON.stringify(s))
  }
  assert.ok((await signed(hp, '/v1/mod/apply', { name: 'Hope Ful', why: 'I care about safety', hours: '10', languages: 'English, Twi' })).ok)
  const q2 = await call('/v1/mod/queue', { username: user, authKey })
  assert.ok(q2.applications.some((a) => a.id === hp.id && a.username === hopeful), 'CEO sees the application')
  assert.ok((await call('/v1/mod/act', { username: user, authKey, action: 'approve-mod', who: hp.id })).ok)
  assert.strictEqual((await call('/v1/mod/me', { username: hopeful, authKey: hopefulKey })).role, 'mod')
  const q3 = await call('/v1/mod/queue', { username: hopeful, authKey: hopefulKey })
  assert.ok(!q3.applications && Array.isArray(q3.team), 'moderators see the team but not applications')
  assert.strictEqual((await call('/v1/mod/act', { username: hopeful, authKey: hopefulKey, action: 'role', who: user, role: '' })).status, 403, 'a moderator cannot remove anyone')
  assert.strictEqual((await call('/v1/mod/act', { username: hopeful, authKey: hopefulKey, action: 'approve-mod', who: reporter.id })).status, 403)
  assert.ok((await call('/v1/mod/act', { username: user, authKey, action: 'role', who: hopeful, role: '' })).ok, 'CEO removes a moderator')
  assert.strictEqual((await call('/v1/mod/me', { username: hopeful, authKey: hopefulKey })).role, null)
  console.log('moderator applications ok')
  assert.ok((await call('/v1/badges', { people: [reporter.id] })).badges[reporter.id].verified, 'now verified')
  // The panel page is served.
  const page = await (await fetch(URL + '/panel')).text()
  assert.ok(page.includes('Meshron Moderation'), 'panel served')
  console.log('badges, verification requests, panel ok')
  console.log('ALL PASSED')
})().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
