// z-base-32, bit-for-bit the same as crypto/ZBase32.kt on Android (and the `z32` npm package).
// Used for Meshron IDs (public keys) and recovery keys (seeds): 32 bytes -> 52 characters.

const ALPHABET = 'ybndrfg8ejkmcpqxot1uwisza345h769'

function encode(bytes) {
  let out = ''
  let buffer = 0
  let bits = 0
  for (const b of bytes) {
    buffer = ((buffer << 8) | b) & 0xffff
    bits += 8
    while (bits >= 5) {
      bits -= 5
      out += ALPHABET[(buffer >> bits) & 0x1f]
    }
  }
  if (bits > 0) out += ALPHABET[(buffer << (5 - bits)) & 0x1f]
  return out
}

/** Throws on characters outside the alphabet. */
function decode(text) {
  const out = new Uint8Array(Math.floor((text.length * 5) / 8))
  let buffer = 0
  let bits = 0
  let i = 0
  for (const c of text) {
    const v = ALPHABET.indexOf(c)
    if (v < 0) throw new Error('Invalid z-base-32 character: ' + c)
    buffer = ((buffer << 5) | v) & 0xffff
    bits += 5
    if (bits >= 8) {
      bits -= 8
      out[i++] = (buffer >> bits) & 0xff
    }
  }
  return out
}

module.exports = { encode, decode, ALPHABET }
