// Development only: runs the shared host under Node and serves the screens to a normal
// browser on localhost, so the app can be tried and tested without Electron or an iPhone.
//
//   node cross/core/dev-server.js [--dir <data folder>] [--port 8787]
//
// Secrets are kept in a plain file here. The real apps use the OS secure store instead.

const http = require('http')
const fs = require('fs')
const path = require('path')
const { startNodeHost } = require('./boot-node')

const args = process.argv.slice(2)
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : d }
const dataDir = path.resolve(opt('dir', 'cross-dev-data'))
const port = Number(opt('port', 8787))
const uiDir = path.join(__dirname, '..', 'ui')
fs.mkdirSync(dataDir, { recursive: true })

const secretsFile = path.join(dataDir, 'dev-secrets.json')
const readSecrets = () => (fs.existsSync(secretsFile) ? JSON.parse(fs.readFileSync(secretsFile, 'utf8')) : {})
const secrets = {
  get: async (name) => readSecrets()[name] || null,
  set: async (name, value) => fs.writeFileSync(secretsFile, JSON.stringify({ ...readSecrets(), [name]: value }))
}

const clients = new Set()
const emit = (ev, data) => {
  const line = 'data: ' + JSON.stringify({ ev, data }) + '\n\n'
  for (const res of clients) res.write(line)
}

const host = startNodeHost({
  dataDir, secrets, emit, platform: 'web',
  workletPath: path.join(__dirname, '..', '..', 'app', 'src', 'main', 'js', 'app.js')
})

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' }

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  if (url.pathname === '/events') {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' })
    res.write('\n')
    clients.add(res)
    req.on('close', () => clients.delete(res))
    return
  }
  if (url.pathname === '/call' && req.method === 'POST') {
    let body = ''
    for await (const chunk of req) body += chunk
    res.setHeader('content-type', 'application/json')
    try {
      const { method, args } = JSON.parse(body)
      const result = await host.call(method, args)
      res.end(JSON.stringify({ result: result === undefined ? null : result }))
    } catch (err) {
      res.end(JSON.stringify({ error: (err && err.message) || String(err) }))
    }
    return
  }
  const rel = path.normalize(url.pathname === '/' ? 'index.html' : url.pathname.slice(1))
  const file = path.join(uiDir, rel)
  if (!file.startsWith(uiDir) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    res.writeHead(404)
    return res.end('Not found')
  }
  res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' })
  fs.createReadStream(file).pipe(res)
}).listen(port, '127.0.0.1', () => console.log('Meshron dev server: http://127.0.0.1:' + port + '  data: ' + dataDir))

const stop = () => { host.flush(); process.exit(0) }
process.on('SIGINT', stop)
process.on('SIGTERM', stop)
