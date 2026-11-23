// Kokoro (82M, Apache-2.0): a natural voice that runs on this PC, no account and no key.
// The model (about 90 MB) downloads from Hugging Face the first time, then is kept on the PC and
// works offline.
import { KokoroTTS, env } from './vendor/kokoro.web.js'

// The speech engine ships with the app (see prepare-asst.js), so nothing is fetched from a CDN.
env.wasmPaths = new URL('./vendor/', import.meta.url).href

let tts = null
let loading = null

async function load (onProgress) {
  if (tts) return tts
  if (!loading) {
    const progress_callback = (p) => { if (onProgress && p && p.status === 'progress' && p.total > 1e6) onProgress(Math.round((p.loaded / p.total) * 100)) }
    // On the graphics card (WebGPU) it keeps up with speech; on the processor it's about half speed.
    const gpu = !!(navigator.gpu && (await navigator.gpu.requestAdapter().catch(() => null)))
    loading = (gpu
      ? KokoroTTS.from_pretrained('onnx-community/Kokoro-82M-v1.0-ONNX', { dtype: 'fp32', device: 'webgpu', progress_callback }).catch(() => null)
      : Promise.resolve(null))
      .then((t) => t || KokoroTTS.from_pretrained('onnx-community/Kokoro-82M-v1.0-ONNX', { dtype: 'q8', device: 'wasm', progress_callback }))
      .then(async (t) => {
        // Warm up once (the GPU compiles its programs on the first run), so the first reply is quick.
        await t.generate('Ready.', { voice: 'af_heart' }).catch(() => {})
        tts = t; return t
      })
      .catch((e) => { loading = null; throw e })
  }
  return loading
}

/** Short pieces (sentences), so it starts talking quickly and long replies stay smooth. */
function pieces (text) {
  const out = []
  for (const s of String(text).replace(/[*#_`]/g, '').replace(/\s+/g, ' ').trim().match(/[^.!?]+[.!?]*\s*/g) || []) {
    const t = s.trim()
    if (!t) continue
    // The first piece stays short so it starts talking at once; later ones join up to ~160 characters.
    if (out.length > 1 && (out[out.length - 1].length + t.length < 160)) out[out.length - 1] += " " + t
    else out.push(t)
  }
  return out.slice(0, 24)
}

/** Generates the reply piece by piece; each piece is handed to play() as soon as it's ready. */
async function speak (text, voice, play, onProgress) {
  const t = await load(onProgress)
  let next = null
  const list = pieces(text)
  for (let i = 0; i < list.length; i++) {
    const audio = await (next || t.generate(list[i], { voice }))
    next = i + 1 < list.length ? t.generate(list[i + 1], { voice }) : null
    const stop = await play(audio.toBlob())
    if (stop) return
  }
}

/** True once the model is downloaded and warmed up (until then, other voices speak). */
const ready = () => !!tts
window.Kokoro = { speak, load, ready }

// With a Kokoro voice chosen, get the model ready in the background (the first time it downloads).
async function warm () {
  try { const s = await window.Assistant.settings(); if (s.studio && String(s.studioVoice || '').startsWith('kokoro:')) await load() } catch {}
}
warm()
window.Assistant.on((ev, data) => { if (ev === 'settings' && data.studio && String(data.studioVoice || '').startsWith('kokoro:')) load().catch(() => {}) })
