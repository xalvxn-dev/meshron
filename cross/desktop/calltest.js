// Test only: opens the dev server's screens with fake camera/microphone devices, starts a
// call to the given contact, and saves screenshots. Run with: npx electron calltest.js <key> <video 0|1> <out.png>

const { app, BrowserWindow } = require('electron')
app.commandLine.appendSwitch('use-fake-device-for-media-stream')
app.commandLine.appendSwitch('use-fake-ui-for-media-stream')
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')
const [key, video, out] = process.argv.slice(2).filter((a) => !a.startsWith('--') && !a.endsWith('calltest.js') && a !== '.')

app.whenReady().then(async () => {
  const win = new BrowserWindow({ width: 1100, height: 760, show: true })
  win.webContents.on('console-message', (_e, level, msg) => { if (level >= 2) console.log('console:', msg) })
  await win.loadURL('http://127.0.0.1:8787/')
  await new Promise((r) => setTimeout(r, 6000))
  const fs = require('fs')
  await win.webContents.executeJavaScript(`
    (async () => {
      let b = null
      for (let i = 0; i < 20 && !b; i++) {
        const r = [...document.querySelectorAll('.row')].find((x) => x.querySelector('.name') && x.querySelector('.name').textContent === 'Dana')
        if (r) r.dispatchEvent(new MouseEvent('click', { bubbles: true }))
        await new Promise((r) => setTimeout(r, 500))
        b = document.querySelector('button[title="${video === '1' ? 'Video call' : 'Voice call'}"]')
      }
      if (b) b.click()
      return !!b
    })()`).then((ok) => console.log('call started', ok))
  for (const [i, ms] of [[1, 2500], [2, 9000], [3, 9000]]) {
    await new Promise((r) => setTimeout(r, ms))
    const img = await win.webContents.capturePage().catch(() => null)
    if (img)
    fs.writeFileSync(out.replace('.png', `-${i}.png`), img.toPNG())
    console.log('shot', i, await win.webContents.executeJavaScript(`(document.querySelector('.call-status')||{}).textContent`))
  }
  await win.webContents.executeJavaScript(`(document.querySelector('.cbtn.red button')||{click(){}}).click()`)
  await new Promise((r) => setTimeout(r, 1500))
  app.quit()
})
