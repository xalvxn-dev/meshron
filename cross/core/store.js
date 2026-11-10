// Tiny encrypted JSON-file storage for the shared host.
//
// Each file is rewritten atomically (write to .tmp, then rename) and writes are coalesced,
// so a burst of changes costs one write. Contents are sealed with XSalsa20-Poly1305
// (libsodium secretbox) under a storage key the platform keeps in its secure store
// (Windows: DPAPI via Electron safeStorage; iPhone: Keychain). File layout: nonce(24) | box.
//
// `fs` is Node's 'fs' (Windows) or 'bare-fs' (iPhone); both have the sync calls used here.

const sodium = require('sodium-universal')
const b4a = require('b4a')

function makeBox(keyHex) {
  const key = b4a.from(keyHex, 'hex')
  return {
    seal(text) {
      const msg = b4a.from(text, 'utf8')
      const nonce = b4a.alloc(sodium.crypto_secretbox_NONCEBYTES)
      sodium.randombytes_buf(nonce)
      const box = b4a.alloc(msg.byteLength + sodium.crypto_secretbox_MACBYTES)
      sodium.crypto_secretbox_easy(box, msg, nonce, key)
      return b4a.concat([nonce, box])
    },
    open(buf) {
      const n = sodium.crypto_secretbox_NONCEBYTES
      if (buf.byteLength < n + sodium.crypto_secretbox_MACBYTES) throw new Error('File too short')
      const msg = b4a.alloc(buf.byteLength - n - sodium.crypto_secretbox_MACBYTES)
      if (!sodium.crypto_secretbox_open_easy(msg, buf.subarray(n), buf.subarray(0, n), key)) {
        throw new Error('Could not decrypt (wrong key or damaged file)')
      }
      return b4a.toString(msg, 'utf8')
    }
  }
}

class JsonFile {
  constructor(fs, file, fallback, box) {
    this.fs = fs
    this.file = file
    this.fallback = fallback
    this.box = box
    this.timer = null
    this.value = this._read()
  }

  _readOne(file) {
    if (!this.fs.existsSync(file)) return undefined
    return JSON.parse(this.box.open(this.fs.readFileSync(file)))
  }

  _read() {
    try {
      const v = this._readOne(this.file)
      if (v !== undefined) return v
    } catch {}
    // A crash mid-rename can leave only the .tmp copy.
    try {
      const v = this._readOne(this.file + '.tmp')
      if (v !== undefined) return v
    } catch {}
    return typeof this.fallback === 'function' ? this.fallback() : JSON.parse(JSON.stringify(this.fallback))
  }

  /** Writes soon (coalesced). Call flush() to write now. */
  save() {
    if (this.timer) return
    this.timer = setTimeout(() => this.flush(), 200)
  }

  flush() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    const data = this.box.seal(JSON.stringify(this.value))
    const tmp = this.file + '.tmp'
    this.fs.writeFileSync(tmp, data)
    try {
      this.fs.renameSync(tmp, this.file)
    } catch {
      // Windows can refuse to rename over an open file; overwrite instead.
      this.fs.writeFileSync(this.file, data)
      try { this.fs.unlinkSync(tmp) } catch {}
    }
  }

  remove() {
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    for (const f of [this.file, this.file + '.tmp']) {
      try { this.fs.unlinkSync(f) } catch {}
    }
  }
}

function ensureDir(fs, dir) {
  try {
    fs.mkdirSync(dir, { recursive: true })
  } catch {}
}

module.exports = { JsonFile, ensureDir, makeBox }
