// Kokoro (82M, Apache-2.0): a natural voice that runs on this PC, no account and no key.
// The model (about 90 MB) downloads from Hugging Face the first time, then is kept on the PC and
// works offline. The work happens in kokoro-worker.mjs, on a background thread, so the orb keeps
// answering clicks and drags while it speaks.

const worker = new Worker(new URL('./kokoro-worker.mjs', import.meta.url), { type: 'module' })
let seq = 0
const waiting = new Map()
let progress = null
let isReady = false
worker.onmessage = (e) => {
  const m = e.data || {}
  if (m.type === 'progress') { if (progress) progress(m.pct); return }
  const w = waiting.get(m.id); if (!w) return
  waiting.delete(m.id)
  if (m.type === 'error') w.reject(new Error(m.error)); else w.resolve(m)
}
function ask (msg) { const id = ++seq; return new Promise((resolve, reject) => { waiting.set(id, { resolve, reject }); worker.postMessage({ ...msg, id }) }) }

let loading = null
function load (onProgress) {
  if (onProgress) progress = onProgress
  if (!loading) loading = ask({ type: 'load' }).then(() => { isReady = true }).catch((e) => { loading = null; throw e })
  return loading
}

/** Short pieces (sentences), so it starts talking quickly and long replies stay smooth. */
function pieces (text) {
  const out = []
  for (const s of String(text).replace(/[*#_`]/g, '').replace(/\s+/g, ' ').trim().match(/[^.!?]+[.!?]*\s*/g) || []) {
    const t = s.trim()
    if (!t) continue
    // The first piece stays short so it starts talking at once; later ones join up to ~160 characters.
    if (out.length > 1 && (out[out.length - 1].length + t.length < 160)) out[out.length - 1] += ' ' + t
    else out.push(t)
  }
  return out.slice(0, 24)
}

/** Generates the reply piece by piece; each piece is handed to play() as soon as it's ready. */
async function speak (text, voice, play, onProgress) {
  await load(onProgress)
  const list = pieces(text)
  const gen = (t) => ask({ type: 'gen', text: t, voice }).then((m) => m.blob)
  let next = null
  for (let i = 0; i < list.length; i++) {
    const blob = await (next || gen(list[i]))
    next = i + 1 < list.length ? gen(list[i + 1]) : null
    const stop = await play(blob)
    if (stop) return
  }
}

/** True once the model is downloaded and warmed up (until then, other voices speak). */
const ready = () => isReady
window.Kokoro = { speak, load, ready }

// With a Kokoro voice chosen, get the model ready in the background (the first time it downloads).
async function warm () {
  try { const s = await window.Assistant.settings(); if (s.studio && String(s.studioVoice || '').startsWith('kokoro:')) await load() } catch {}
}
warm()
window.Assistant.on((ev, data) => { if (ev === 'settings' && data.studio && String(data.studioVoice || '').startsWith('kokoro:')) load().catch(() => {}) })
