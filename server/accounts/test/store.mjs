// Tests src/store.js (D1 store + the move from KV) with Node's built-in SQLite standing in for D1.
//   node test/store.mjs
import { DatabaseSync } from 'node:sqlite'
import assert from 'node:assert'
import { d1Store, migrateStep } from '../src/store.js'

function fakeD1 () {
  const sql = new DatabaseSync(':memory:')
  const stmt = (q, args = []) => ({
    bind: (...a) => stmt(q, a),
    first: async () => sql.prepare(q).get(...args) ?? null,
    all: async () => ({ results: sql.prepare(q).all(...args) }),
    run: async () => sql.prepare(q).run(...args)
  })
  return { prepare: (q) => stmt(q), batch: async (list) => { for (const s of list) await s.run() } }
}

function fakeKv (data) {
  const m = new Map(Object.entries(data))
  return {
    gets: 0,
    async get (k) { this.gets++; return m.has(k) ? m.get(k) : null },
    async list ({ cursor, limit = 1000 } = {}) {
      const all = [...m.keys()].sort()
      const start = cursor ? all.indexOf(cursor) + 1 : 0
      const keys = all.slice(start, start + limit).map((name) => ({ name }))
      const done = start + limit >= all.length
      return { keys, list_complete: done, cursor: done ? undefined : keys[keys.length - 1].name }
    }
  }
}

const db = fakeD1()
const old = { 'u:ann': '{"a":1}', 'v:1111': '{"by":"x"}', 'h:reel:aa:bb': '{"h":1}', 'role:zed': 'mod', 'rl:ip:x': '5', 'b:gone': '{}' }
for (let i = 0; i < 450; i++) old['ml:' + String(i).padStart(5, '0')] = '{"i":' + i + '}'
const kv = fakeKv(old)
const s = d1Store(db, kv)

// Read-through: a key only in the old KV is read and copied.
assert.equal(await s.get('v:1111'), '{"by":"x"}')
assert.equal(await s.get('nope'), null)
// Newer data in D1 wins over the old copy.
await s.put('role:zed', 'admin')
// Deleting during the move leaves a tombstone: the old copy doesn't come back.
await s.delete('b:gone')
assert.equal(await s.get('b:gone'), null)
// Expiry.
await s.put('c:tmp', 'x', { expirationTtl: 60 })
assert.equal(await s.get('c:tmp'), 'x')

let steps = 0
while ((await migrateStep(db, kv)) !== 'done') if (++steps > 50) throw new Error('migration never finished')
assert.equal(await s.get('u:ann'), '{"a":1}')
assert.equal(await s.get('h:reel:aa:bb'), '{"h":1}')
assert.equal(await s.get('role:zed'), 'admin', 'kept the newer value')
assert.equal(await s.get('b:gone'), null, 'deleted stays deleted')
assert.equal(await s.get('rl:ip:x'), null, 'rate counters not copied')
assert.equal(await s.get('ml:00449'), '{"i":449}')

// list: prefix, order, paging.
const page1 = await s.list({ prefix: 'ml:', limit: 200 })
assert.equal(page1.keys.length, 200)
assert.equal(page1.list_complete, false)
const page3 = await s.list({ prefix: 'ml:', limit: 200, cursor: (await s.list({ prefix: 'ml:', limit: 200, cursor: page1.cursor })).cursor })
assert.equal(page3.keys.length, 50)
assert.equal(page3.list_complete, true)
assert.deepEqual((await s.list({ prefix: 'h:' })).keys.map((k) => k.name), ['h:reel:aa:bb'])

// After the move, the old KV isn't read any more.
const before = kv.gets
assert.equal(await s.get('missing-after'), null)
assert.equal(kv.gets, before)
console.log('store ok (' + steps + ' steps)')
