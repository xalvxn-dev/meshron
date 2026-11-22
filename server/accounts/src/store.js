// Storage on D1 (Cloudflare's SQL database) behind the same small API the code used with KV:
// get(key), put(key, value, { expirationTtl }), delete(key), list({ prefix, cursor, limit }).
//
// Why: KV's free plan allows only 1,000 writes and 1,000 lists a day. Once they ran out, every
// route failed until midnight UTC. D1's free plan allows 100,000 writes and 5 million reads a
// day, which Meshron is far from.
//
// Tables:
//   kv(k TEXT PRIMARY KEY, v TEXT, exp INTEGER)  exp = Unix seconds, null = keeps
//   mig(k TEXT PRIMARY KEY)                      KV keys still to copy over
//
// Moving from KV: until every old key is copied (migrateStep, run by the cron trigger), a key
// missing in D1 is read from the old KV and copied. Deleting leaves a tombstone so the old copy
// can't come back.

const TOMB = '\u0000gone'
const SCHEMA = [
  'CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL, exp INTEGER)',
  'CREATE TABLE IF NOT EXISTS mig (k TEXT PRIMARY KEY)'
]

let ready = null
let lastSweep = 0
let migrated = false
const misses = new Map() // key -> until (ms): not in the old KV either

const nowS = () => Math.floor(Date.now() / 1000)

function init (db) {
  ready = ready || db.batch(SCHEMA.map((q) => db.prepare(q))).catch((e) => { ready = null; throw e })
  return ready
}

async function isMigrated (db) {
  if (migrated) return true
  const r = await db.prepare('SELECT v FROM kv WHERE k = ?').bind('cfg:migrated').first()
  migrated = !!(r && r.v === '1')
  return migrated
}

/** Old KV keys whose expiry we know from the key alone (KV's get doesn't say). */
function guessExp (key) {
  if (key.startsWith('c:')) return nowS() + 600
  if (key.startsWith('rl:')) return nowS() + 3600
  return null
}

export function d1Store (db, oldKv) {
  async function sweep () {
    if (Date.now() - lastSweep < 10 * 60 * 1000) return
    lastSweep = Date.now()
    try { await db.prepare('DELETE FROM kv WHERE exp IS NOT NULL AND exp <= ?').bind(nowS()).run() } catch {}
  }

  return {
    async get (key) {
      key = String(key)
      await init(db)
      const row = await db.prepare('SELECT v, exp FROM kv WHERE k = ?').bind(key).first()
      if (row) return row.v === TOMB || (row.exp != null && row.exp <= nowS()) ? null : row.v
      if (!oldKv || (await isMigrated(db))) return null
      const miss = misses.get(key)
      if (miss && miss > Date.now()) return null
      let v = null
      try { v = await oldKv.get(key) } catch {}
      if (v == null) {
        misses.set(key, Date.now() + 10 * 60 * 1000)
        if (misses.size > 50000) misses.clear()
        return null
      }
      try { await db.prepare('INSERT OR IGNORE INTO kv (k, v, exp) VALUES (?, ?, ?)').bind(key, v, guessExp(key)).run() } catch {}
      return v
    },
    async put (key, value, opts) {
      await init(db)
      const ttl = opts && opts.expirationTtl ? Math.max(60, Math.floor(opts.expirationTtl)) : null
      const exp = opts && opts.expiration ? Math.floor(opts.expiration) : ttl ? nowS() + ttl : null
      misses.delete(String(key))
      await db.prepare('INSERT INTO kv (k, v, exp) VALUES (?, ?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v, exp = excluded.exp')
        .bind(String(key), String(value), exp).run()
      sweep()
    },
    async delete (key) {
      await init(db)
      if (oldKv && !(await isMigrated(db))) {
        // Keep a tombstone until the move is done, so the old KV copy isn't read back.
        await db.prepare('INSERT INTO kv (k, v, exp) VALUES (?, ?, NULL) ON CONFLICT(k) DO UPDATE SET v = excluded.v, exp = NULL').bind(String(key), TOMB).run()
      } else {
        await db.prepare('DELETE FROM kv WHERE k = ?').bind(String(key)).run()
      }
    },
    /** Keys in order, like KV: { keys: [{ name }], list_complete, cursor } (the cursor is the last key). */
    async list ({ prefix = '', cursor, limit = 1000 } = {}) {
      await init(db)
      const n = Math.min(Math.max(1, limit | 0), 1000)
      // Prefix range without LIKE (keys may contain % or _): prefix <= k < prefix + U+FFFF.
      const rows = await db.prepare('SELECT k FROM kv WHERE k >= ? AND k < ? AND k > ? AND v != ? AND (exp IS NULL OR exp > ?) ORDER BY k LIMIT ?')
        .bind(prefix, prefix + '￿', cursor || '', TOMB, nowS(), n + 1).all()
      const res = rows.results || []
      const keys = res.slice(0, n).map((r) => ({ name: r.k }))
      const more = res.length > n
      return { keys, list_complete: !more, cursor: more ? keys[keys.length - 1].name : undefined }
    }
  }
}

/**
 * One step of copying the old KV into D1 (run every minute by the cron trigger until done).
 * Each step lists at most one page of KV keys (KV allows 1,000 lists a day) and copies up to
 * 200 values, never overwriting anything D1 already has (newer data or a tombstone).
 */
export async function migrateStep (db, oldKv) {
  await init(db)
  if (!oldKv || (await isMigrated(db))) return 'done'
  const q = await db.prepare('SELECT k FROM mig ORDER BY k LIMIT 200').all()
  let keys = (q.results || []).map((r) => r.k)
  if (!keys.length) {
    const cur = await db.prepare('SELECT v FROM kv WHERE k = ?').bind('cfg:migcursor').first()
    if (cur && cur.v === 'end') {
      await db.batch([
        db.prepare("INSERT INTO kv (k, v) VALUES ('cfg:migrated', '1') ON CONFLICT(k) DO UPDATE SET v = '1'"),
        db.prepare('DELETE FROM kv WHERE v = ?').bind(TOMB)
      ])
      migrated = true
      return 'done'
    }
    const page = await oldKv.list({ limit: 1000, cursor: cur && cur.v ? cur.v : undefined })
    // An empty first page means KV didn't really answer (its daily list quota ran out): try later,
    // never mark the move as done on it (that once left most accounts behind).
    if (!(cur && cur.v) && !page.keys.length) return 'empty first page; will retry'
    const names = page.keys.map((k) => k.name).filter((n) => !n.startsWith('rl:') && !n.startsWith('cfg:mig'))
    await db.batch([
      db.prepare('INSERT OR IGNORE INTO mig (k) SELECT value FROM json_each(?)').bind(JSON.stringify(names)),
      db.prepare("INSERT INTO kv (k, v) VALUES ('cfg:migcursor', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").bind(page.list_complete ? 'end' : page.cursor)
    ])
    return 'listed ' + names.length
  }
  const rows = []
  for (const k of keys) {
    const v = await oldKv.get(k)
    if (v != null) rows.push({ k, v, e: guessExp(k) })
  }
  await db.batch([
    db.prepare("INSERT OR IGNORE INTO kv (k, v, exp) SELECT json_extract(value, '$.k'), json_extract(value, '$.v'), json_extract(value, '$.e') FROM json_each(?)").bind(JSON.stringify(rows)),
    db.prepare('DELETE FROM mig WHERE k IN (SELECT value FROM json_each(?))').bind(JSON.stringify(keys))
  ])
  return 'copied ' + rows.length
}
