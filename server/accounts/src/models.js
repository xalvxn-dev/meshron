// The AI models, strongest first, across Groq and Google AI Studio (Gemini).
// Every request goes to the strongest model that is available right now. A model that hits its
// limit, goes down or is retired sits out until it should be back, so weaker models only answer
// while the stronger ones are all used up. A model whose key isn't set is skipped.
//
// Override with AI_LADDER / AI_VISION_LADDER: comma-separated "provider:model", e.g.
// "groq:openai/gpt-oss-120b,gemini:gemini-2.5-flash".

const TEXT_LADDER = [
  'groq:openai/gpt-oss-120b',
  'gemini:gemini-2.5-flash',
  'groq:moonshotai/kimi-k2-instruct-0905',
  'groq:qwen/qwen3-32b',
  'groq:llama-3.3-70b-versatile',
  'gemini:gemini-2.5-flash-lite',
  'groq:openai/gpt-oss-20b',
  'groq:meta-llama/llama-4-scout-17b-16e-instruct',
  'groq:llama-3.1-8b-instant'
]
const VISION_LADDER = [
  'gemini:gemini-2.5-flash',
  'groq:meta-llama/llama-4-maverick-17b-128e-instruct',
  'groq:meta-llama/llama-4-scout-17b-16e-instruct',
  'gemini:gemini-2.5-flash-lite'
]
const IMAGE_MODELS = ['gemini-2.5-flash-image', 'gemini-2.0-flash-preview-image-generation']
const GEMINI = 'https://generativelanguage.googleapis.com/v1beta'

// Models sitting out, and until when (per server instance; it is only a shortcut, so a fresh
// instance simply tries again and learns it in one request).
const resting = new Map()

const list = (s) => String(s || '').split(',').map((x) => x.trim()).filter(Boolean).map((x) => x.includes(':') ? x : 'groq:' + x)

export function ladder (env, vision) {
  if (vision) return list(env.AI_VISION_LADDER).length ? list(env.AI_VISION_LADDER) : (env.AI_VISION_MODEL ? ['groq:' + env.AI_VISION_MODEL, ...VISION_LADDER] : VISION_LADDER)
  if (list(env.AI_LADDER).length) return list(env.AI_LADDER)
  const main = env.AI_MODEL && env.AI_MODEL !== 'openai/gpt-oss-120b' ? ['groq:' + env.AI_MODEL] : []
  return [...new Set([...main, ...TEXT_LADDER])]
}

function endpoint (env, provider) {
  if (provider === 'gemini') return env.GEMINI_API_KEY ? { url: GEMINI + '/openai/chat/completions', key: env.GEMINI_API_KEY } : null
  return env.AI_URL && env.AI_KEY ? { url: env.AI_URL.replace(/\/$/, '') + '/chat/completions', key: env.AI_KEY } : null
}

/** How long a model should sit out after this answer, in ms (0: it's the request, not the model). */
function restFor (r, body) {
  if (r.status === 429) {
    const m = /try again in (?:(\d+)h)?(?:(\d+)m)?(?:([\d.]+)s)?/i.exec(body || '')
    const ms = m ? ((+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0)) * 1000 : (+r.headers.get('retry-after') || 0) * 1000
    return Math.min(Math.max(ms || 60_000, 5_000), 6 * 3600_000)
  }
  if (r.status === 404 || (r.status === 400 && /decommission|not found|does not exist|no longer supported/i.test(body || ''))) return 24 * 3600_000
  if (r.status >= 500 || r.status === 408) return 30_000
  if (r.status === 400 && /model|tool_use_failed|not supported|unsupported/i.test(body || '')) return 1 // this request only
  return 0
}

/**
 * One chat completion (OpenAI format) from the strongest available model.
 * opts: { vision, only: 'groq:model' (a special model with no stand-ins) }
 */
export async function chat (env, body, opts = {}) {
  const first = opts.first ? (opts.first.includes(':') ? opts.first : 'groq:' + opts.first) : null
  const names = opts.only ? [opts.only] : first ? [first, ...ladder(env, opts.vision).filter((n) => n !== first)] : ladder(env, opts.vision)
  const now = Date.now()
  const ready = names.filter((n) => endpoint(env, n.split(':')[0]))
  if (!ready.length) throw Object.assign(new Error('AI is not set up on this server yet'), { status: 503 })
  // Resting models go last (in case every model is resting, the one back soonest is tried first).
  const order = [...ready.filter((n) => !(resting.get(n) > now)), ...ready.filter((n) => resting.get(n) > now).sort((a, b) => resting.get(a) - resting.get(b))]
  let limited = false
  for (const name of order) {
    const i = name.indexOf(':'); const provider = name.slice(0, i); const model = name.slice(i + 1)
    const ep = endpoint(env, provider)
    const b = { ...body, model }
    delete b.reasoning_effort; delete b.reasoning_format
    if (/gpt-oss/i.test(model)) b.reasoning_effort = 'low' // think briefly: fast, and fits the token cap
    else if (/qwen3/i.test(model)) b.reasoning_effort = 'none'
    else if (provider === 'gemini' && /2\.5-flash/.test(model)) b.reasoning_effort = 'low'
    let r
    try {
      r = await fetch(ep.url, { method: 'POST', headers: { authorization: 'Bearer ' + ep.key, 'content-type': 'application/json' }, body: JSON.stringify(b), signal: AbortSignal.timeout(45000) })
    } catch (e) { console.log('ai unreachable', name, e.message); resting.set(name, Date.now() + 30_000); continue }
    if (r.ok) {
      const o = await r.json().catch(() => null)
      if (o && o.choices && o.choices[0]) { resting.delete(name); o.model_used = name; return o }
      console.log('ai odd answer', name); continue
    }
    const err = (await r.text().catch(() => '')).slice(0, 300)
    console.log('ai failed', name, r.status, err)
    if (r.status === 429) limited = true
    const rest = restFor(r, err)
    if (!rest) throw Object.assign(new Error('The AI is busy. Try again in a moment.'), { status: 502 })
    if (rest > 1) resting.set(name, Date.now() + rest)
  }
  throw Object.assign(new Error(limited ? 'The AI has reached its limit for now. Try again later.' : 'The AI is busy. Try again in a moment.'), { status: 502 })
}

/** True when the request looks like it asks for a picture to be made. */
export const wantsImage = (t) => /^\s*(please\s+)?(draw|paint|sketch|illustrate|imagine)\b/i.test(t || '') ||
  /\b(generate|create|make|design|draw|paint|render|produce|give me|show me)\b[^.?!\n]{0,40}\b(image|picture|pic|photo|drawing|illustration|logo|artwork|art|wallpaper|poster|icon|avatar|sticker|banner|cartoon)\b/i.test(t || '')

/** Makes a picture with Gemini (Google AI Studio). Returns { image: 'data:image/png;base64,…', text } . */
export async function makeImage (env, prompt) {
  if (!env.GEMINI_API_KEY) throw Object.assign(new Error('Image making is not set up on this server yet.'), { status: 503 })
  const models = list(env.AI_IMAGE_MODELS).length ? String(env.AI_IMAGE_MODELS).split(',').map((x) => x.trim()) : IMAGE_MODELS
  let limited = false
  for (const model of models) {
    if (resting.get('image:' + model) > Date.now()) continue
    let r
    try {
      r = await fetch(GEMINI + '/models/' + model + ':generateContent', {
        method: 'POST',
        headers: { 'x-goog-api-key': env.GEMINI_API_KEY, 'content-type': 'application/json' },
        body: JSON.stringify({ contents: [{ role: 'user', parts: [{ text: String(prompt).slice(0, 1500) }] }], generationConfig: { responseModalities: ['TEXT', 'IMAGE'] } }),
        signal: AbortSignal.timeout(60000)
      })
    } catch (e) { console.log('image unreachable', model, e.message); continue }
    if (!r.ok) {
      const err = (await r.text().catch(() => '')).slice(0, 300)
      console.log('image failed', model, r.status, err)
      if (r.status === 429) limited = true
      const rest = restFor(r, err)
      if (rest > 1) resting.set('image:' + model, Date.now() + rest)
      if (!rest && r.status === 400 && /safety|blocked|policy/i.test(err)) throw Object.assign(new Error('I can\'t make that picture.'), { status: 400 })
      continue
    }
    const o = await r.json()
    const parts = (o.candidates && o.candidates[0] && o.candidates[0].content && o.candidates[0].content.parts) || []
    const pic = parts.find((p) => p.inlineData && p.inlineData.data)
    const text = parts.filter((p) => p.text).map((p) => p.text).join(' ').trim()
    if (pic) return { image: 'data:' + (pic.inlineData.mimeType || 'image/png') + ';base64,' + pic.inlineData.data, text: text.slice(0, 400) }
    if (text) throw Object.assign(new Error(text.slice(0, 200)), { status: 400 })
  }
  throw Object.assign(new Error(limited ? 'Image making has reached its limit for now. Try again later.' : 'Couldn\'t make the picture right now. Try again in a moment.'), { status: 502 })
}
