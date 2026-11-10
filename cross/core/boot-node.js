// Starts the shared host under Node (Windows/Electron, and the dev server).
// The networking worklet (app.js) expects a `Bare` global; under Node we provide the few
// members it uses. Its IPC is replaced by the in-memory channel the host sets up.

const fs = require('fs')
const path = require('path')
const { createHost } = require('./host')

function startNodeHost({ dataDir, secrets, emit, workletPath, platform }) {
  if (!global.Bare) {
    global.Bare = {
      on: (ev, fn) => process.on(ev, fn),
      platform: process.platform,
      arch: process.arch,
      version: process.version
    }
  }
  return createHost({
    fs, path, dataDir, secrets, emit, platform,
    loadWorklet: () => require(workletPath)
  })
}

module.exports = { startNodeHost }
