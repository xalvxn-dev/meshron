// Kokoro runs here, on a background thread: generating speech takes the processor (or graphics
// card) for a moment per sentence, and on the orb's own thread that froze the orb (no clicks, no
// dragging) while it spoke.
import { KokoroTTS, env } from './vendor/kokoro.web.js'

// The speech engine ships with the app (see prepare-asst.js), so nothing is fetched from a CDN.
env.wasmPaths = new URL('./vendor/', import.meta.url).href

let tts = null
let loading = null
function load () {
  if (tts) return Promise.resolve(tts)
  if (!loading) {
    const progress_callback = (p) => { if (p && p.status === 'progress' && p.total > 1e6) postMessage({ type: 'progress', pct: Math.round((p.loaded / p.total) * 100) }) }
    loading = (async () => {
      // On the graphics card (WebGPU) it keeps up with speech; on the processor it's about half speed.
      const gpu = !!(self.navigator && navigator.gpu && (await navigator.gpu.requestAdapter().catch(() => null)))
      let t = gpu ? await KokoroTTS.from_pretrained('onnx-community/Kokoro-82M-v1.0-ONNX', { dtype: 'fp32', device: 'webgpu', progress_callback }).catch(() => null) : null
      if (!t) t = await KokoroTTS.from_pretrained('onnx-community/Kokoro-82M-v1.0-ONNX', { dtype: 'q8', device: 'wasm', progress_callback })
      // Warm up once (the GPU compiles its programs on the first run), so the first reply is quick.
      await t.generate('Ready.', { voice: 'af_heart' }).catch(() => {})
      tts = t
      return t
    })().catch((e) => { loading = null; throw e })
  }
  return loading
}

self.onmessage = async (e) => {
  const m = e.data || {}
  try {
    if (m.type === 'load') { await load(); postMessage({ type: 'ready', id: m.id }) }
    if (m.type === 'gen') {
      const t = await load()
      const audio = await t.generate(m.text, { voice: m.voice })
      postMessage({ type: 'audio', id: m.id, blob: audio.toBlob() })
    }
  } catch (err) { postMessage({ type: 'error', id: m.id, error: String((err && err.message) || err) }) }
}
