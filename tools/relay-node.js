// Meshron relay node: a blind relay for phones that can't connect to each other directly
// (for example two phones on mobile data behind strict NATs).
//
// It only forwards encrypted bytes between two phones. The phones run their Noise handshake
// end to end, so the relay can't read messages, calls or files. It does see IP addresses.
//
// It announces itself on the DHT topic BLAKE2b("meshron/relay/v1"), so Meshron apps find it
// without any configuration. Run it on any always-on machine with a public IP or a normal
// home router (a small free cloud VM is plenty):
//
//   npm install hyperdht blind-relay hypercore-crypto b4a
//   node relay-node.js [--seed-file relay.seed] [--max 200]
//
// The key pair is kept in the seed file so the relay keeps the same public key across
// restarts. Not part of the app.

const fs = require('fs')
const DHT = require('hyperdht')
const { Server: RelayServer } = require('blind-relay')
const crypto = require('hypercore-crypto')
const b4a = require('b4a')

const args = process.argv.slice(2)
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : d }
const seedFile = opt('seed-file', 'relay.seed')
const maxSessions = Number(opt('max', 200))

if (!fs.existsSync(seedFile)) fs.writeFileSync(seedFile, b4a.toString(crypto.randomBytes(32), 'hex'))
const keyPair = crypto.keyPair(b4a.from(fs.readFileSync(seedFile, 'utf8').trim(), 'hex'))
const topic = crypto.hash(b4a.from('meshron/relay/v1'))
const t = () => new Date().toISOString().slice(0, 19).replace('T', ' ')

const dht = new DHT()
const relay = new RelayServer({
  createStream: (opts) => dht.createRawStream({ ...opts, framed: true })
})

let sessions = 0
const server = dht.createServer({ firewall: () => sessions >= maxSessions }, (socket) => {
  sessions++
  socket.on('error', () => {})
  socket.on('close', () => { sessions-- })
  relay.accept(socket, { id: socket.remotePublicKey }).on('error', () => {})
})

async function main() {
  await server.listen(keyPair)
  console.log(`[${t()}] relay listening, public key ${b4a.toString(keyPair.publicKey, 'hex')}`)
  const announce = async () => {
    try {
      await dht.announce(topic, keyPair).finished()
      console.log(`[${t()}] announced on the Meshron relay topic (${dht.host}:${dht.port}, firewalled=${dht.firewalled})`)
    } catch (err) {
      console.log(`[${t()}] announce failed: ${err.message}`)
    }
  }
  await announce()
  setInterval(announce, 10 * 60 * 1000)
  setInterval(() => {
    const s = relay.stats || {}
    console.log(`[${t()}] sessions ${sessions}, pairings active ${s.pairings ? s.pairings.active : '?'}`)
  }, 60 * 1000)
}

main().catch((err) => { console.error(err); process.exit(1) })

process.once('SIGINT', async () => {
  console.log('closing…')
  try { await dht.unannounce(topic, keyPair) } catch {}
  await server.close()
  relay.close()
  await dht.destroy()
  process.exit(0)
})
