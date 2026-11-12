// Starts the shared host under Node (Windows/Electron, and the dev server).
// The networking worklet (app.js) expects a `Bare` global; under Node we provide the few
// members it uses. Its IPC is replaced by the in-memory channel the host sets up.

const fs = require('fs')
const path = require('path')
const { createHost } = require('./host')

function startNodeHost({ dataDir, secrets, emit, workletPath, platform, lan }) {
  if (!global.Bare) {
    global.Bare = {
      on: (ev, fn) => process.on(ev, fn),
      platform: process.platform,
      arch: process.arch,
      version: process.version
    }
  }
  let local = null
  const host = createHost({
    fs, path, dataDir, secrets, emit, platform,
    loadWorklet: () => require(workletPath),
    onLink: (e) => { if (local) local.onLink(e) }
  })
  // Same Wi-Fi / hotspot: find nearby Meshron devices without the internet.
  if (lan) {
    try {
      local = require('./lan-node').startLan({ call: (m, a) => host.call(m, a), log: (m) => console.log(m) })
      host.stopLan = () => local && local.stop()
    } catch (e) {
      console.error('Same Wi-Fi links unavailable:', e.message)
    }
  }
  return host
}

module.exports = { startNodeHost }
