// The AI models, strongest first, across Groq and Google AI Studio (Gemini).
// Every request goes to the strongest model that is available right now. A model that hits its
// limit, goes down or is retired sits out until it should be back, so weaker models only answer
// while the stronger ones are all used up. A model whose key isn't set is skipped.
//
// The list comes from the providers themselves (see discover). Override with AI_LADDER /
// AI_VISION_LADDER: comma-separated "provider:model", e.g. "groq:openai/gpt-oss-120b,gemini:gemini-2.5-flash".

// Used only when the providers can't be asked which models they have.
const TEXT_LADDER = ['groq:openai/gpt-oss-120b', 'groq:openai/gpt-oss-20b']
const VISION_LADDER = []
const IMAGE_MODELS = ['gemini-2.5-flash-image']
const GEMINI = 'https://generativelanguage.googleapis.com/v1beta'

// Models sitting out, and until when (per server instance; it is only a shortcut, so a fresh
// instance simply tries again and learns it in one request).
const resting = new Map()
const REST_KEY = 'https://meshron-internal/ai-resting'
let restSync = 0
/** Every server instance shares what it learned (a model out of quota, down…), so none re-tries it. */
async function loadResting () {
  if (Date.now() - restSync < 15000 || typeof caches === 'undefined') return
  restSync = Date.now()
  try {
    const r = await caches.default.match(REST_KEY)
    if (r) for (const [k, v] of Object.entries(await r.json())) if (v > Date.now() && !(resting.get(k) > v)) resting.set(k, v)
  } catch {}
}
async function saveResting () {
  if (typeof caches === 'undefined') return
  try {
    const now = Date.now(); const o = {}
    for (const [k, v] of resting) if (v > now) o[k] = v
    await caches.default.put(REST_KEY, new Response(JSON.stringify(o), { headers: { 'content-type': 'application/json', 'cache-control': 'max-age=21600' } }))
  } catch {}
}

const list = (s) => String(s || '').split(',').map((x) => x.trim()).filter(Boolean).map((x) => x.includes(':') ? x : 'groq:' + x)

// ---- Which models the keys can use right now (asked every few hours) ----
// Providers retire and rename models often, so the list isn't written down: both are asked, and
// what they offer is ranked by how capable each model is.
let known = null // { at, text: [...], vision: [...], image: [...] }
const NOT_CHAT = /whisper|tts|orpheus|playai|guard|embed|embedding|aqa|imagen|veo|live|audio|native-audio|compound|computer-use|robotics|learnlm|gemma|tuning|distil|transcribe|lyria|nano|deep-research|thinking-exp/i

/** How capable a model is, roughly (higher is better): newer and bigger first, "lite" and small last. */
export function score (name) {
  const id = name.toLowerCase()
  if (id.startsWith('gemini:')) {
    const v = parseFloat((/gemini-(\d+(?:\.\d+)?)/.exec(id) || [])[1] || '1')
    const tier = /pro/.test(id) ? 3 : /flash-lite|lite/.test(id) ? 0.5 : /flash/.test(id) ? 2.2 : 1
    return v * 10 + tier * 3 - (/preview|exp/.test(id) ? 0.5 : 0)
  }
  const size = Math.max(...[...id.matchAll(/(\d+(?:\.\d+)?)b\b/g)].map((m) => +m[1]), 0)
  const famous = /gpt-oss|kimi|qwen|llama-4|deepseek|llama-3\.3|mistral|claude|gpt-5|gpt-4/i.test(id) ? 4 : 0
  // Groq answers in a second or two: a strong Groq model goes ahead of Gemini, which can take much longer.
  return Math.min(36, 8 + Math.log2(Math.max(size, 1)) * 3.6) + famous + 10 - (/instant|mini|small|tiny/.test(id) ? 4 : 0)
}
const vision = (n) => /^gemini:/.test(n) || /llama-4|vision|maverick|scout|-vl\b|pixtral|qwen.*vl/i.test(n)

async function discover (env) {
  if (known && Date.now() - known.at < 3 * 3600_000) return known
  const found = []
  const jobs = []
  if (env.AI_URL && env.AI_KEY) jobs.push(fetch(env.AI_URL.replace(/\/$/, '') + '/models', { headers: { authorization: 'Bearer ' + env.AI_KEY }, signal: AbortSignal.timeout(8000) })
    .then((r) => r.ok ? r.json() : null).then((o) => { for (const m of (o && o.data) || []) if (m.id && m.active !== false && !NOT_CHAT.test(m.id)) found.push('groq:' + m.id) }).catch(() => {}))
  const images = []
  if (env.GEMINI_API_KEY) jobs.push(fetch(GEMINI + '/models?pageSize=200', { headers: { 'x-goog-api-key': env.GEMINI_API_KEY }, signal: AbortSignal.timeout(8000) })
    .then((r) => r.ok ? r.json() : null).then((o) => {
      for (const m of (o && o.models) || []) {
        const id = String(m.name || '').replace(/^models\//, '')
        if (!/^gemini-/.test(id) || !(m.supportedGenerationMethods || []).includes('generateContent')) continue
        if (/image/.test(id)) { images.push(id); continue }
        if (!NOT_CHAT.test(id) && /^gemini-\d+(\.\d+)?-(pro|flash|flash-lite)(-preview)?(-\d\d-\d\d(\d\d)?)?$/.test(id)) found.push('gemini:' + id)
      }
    }).catch(() => {}))
  await Promise.all(jobs)
  const rank = (xs) => [...new Set(xs)].sort((x, y) => score(y) - score(x))
  known = found.length
    ? { at: Date.now(), text: rank(found), vision: rank(found.filter(vision)), image: images.sort((x, y) => score('gemini:' + y) - score('gemini:' + x)) }
    : { at: Date.now() - 2.5 * 3600_000, text: TEXT_LADDER, vision: VISION_LADDER, image: IMAGE_MODELS } // ask again in 30 minutes
  console.log('models', JSON.stringify({ text: known.text, vision: known.vision, image: known.image }))
  return known
}

/** The models to try, strongest first (AI_LADDER / AI_VISION_LADDER override it). */
export async function ladder (env, vision) {
  if (vision && list(env.AI_VISION_LADDER).length) return list(env.AI_VISION_LADDER)
  if (!vision && list(env.AI_LADDER).length) return list(env.AI_LADDER)
  const k = await discover(env)
  return vision ? k.vision : k.text
}

function endpoint (env, provider) {
  if (provider === 'gemini') return env.GEMINI_API_KEY ? { url: GEMINI + '/openai/chat/completions', key: env.GEMINI_API_KEY } : null
  return env.AI_URL && env.AI_KEY ? { url: env.AI_URL.replace(/\/$/, '') + '/chat/completions', key: env.AI_KEY } : null
}

/** How long a model should sit out after this answer, in ms (0: it's the request, not the model). */
function restFor (r, body) {
  if (r.status === 429) {
    if (/limit: ?0\b|exceeded your current quota|per ?day|PerDay/i.test(body || '')) return 6 * 3600_000
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
  const names = opts.only ? [opts.only] : first ? [first, ...(await ladder(env, opts.vision)).filter((n) => n !== first)] : await ladder(env, opts.vision)
  await loadResting()
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
    else if (provider === 'gemini') b.reasoning_effort = 'low' // newer Gemini models think long by default: keep it short
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
    if (rest > 1) { resting.set(name, Date.now() + rest); await saveResting() }
    if (r.status === 404 && known) { known.text = known.text.filter((n) => n !== name); known.vision = known.vision.filter((n) => n !== name) }
  }
  throw Object.assign(new Error(limited ? 'The AI has reached its limit for now. Try again later.' : 'The AI is busy. Try again in a moment.'), { status: 502 })
}

/** True when the request looks like it asks for a picture to be made. */
export const wantsImage = (t) => /^\s*(please\s+)?(draw|paint|sketch|illustrate|imagine)\b/i.test(t || '') ||
  /\b(generate|create|make|design|draw|paint|render|produce|give me|show me)\b[^.?!\n]{0,40}\b(image|picture|pic|photo|drawing|illustration|logo|artwork|art|wallpaper|poster|icon|avatar|sticker|banner|cartoon)\b/i.test(t || '')

/** Makes a picture with Gemini (Google AI Studio). Returns { image: 'data:image/png;base64,…', text } . */
export async function makeImage (env, prompt) {
  if (!env.GEMINI_API_KEY) throw Object.assign(new Error('Image making is not set up on this server yet.'), { status: 503 })
  const models = env.AI_IMAGE_MODELS ? String(env.AI_IMAGE_MODELS).split(',').map((x) => x.trim()) : (await discover(env)).image
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
