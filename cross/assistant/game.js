// Meshron game mode (Windows). Your camera follows your hands and face (track.mjs); you pick
// from the menu by pointing (a fingertip cursor) and pinching or holding still. Games:
//   Hand School   learn every gesture, one lesson at a time (the training game)
//   Orb Pop       pop rising orbs with your fingertips
//   Face Dash     steer with your head, open your mouth for a shield
//   Air Keys      a keyboard in the air: one finger moves, two fingers type; send it to Meshron
//   Mood Mirror   a face that copies your mood, and can tell Meshron how you feel
//   Fingerspell   American Sign Language letters (a static subset), learn and spell words
//   Hand Control  move the mouse, click, scroll and go back and forward with your hand
//   Game Pad      play PC games (like Minecraft): turn your head to look, punch to hit, palm to walk
import { createTracker } from './track.mjs'

const A = window.Assistant
const $ = (id) => document.getElementById(id)
const cv = $('c'); const g = cv.getContext('2d'); const video = $('v')
const C = { cyan: '#22D3C5', blue: '#6E7BFF', lime: '#B6F36A', amber: '#FFB020', red: '#FF5A6A', pink: '#FF6BD6', text: '#E6F7FF', dim: '#8FA6BC' }
const FONT = '"Segoe UI Variable Text", "Segoe UI", sans-serif'
const EMOJI = '"Segoe UI Emoji", "Segoe UI Symbol", sans-serif'
let W = 0; let H = 0
let tracker = null
let R = null // the latest tracking result
let scene = null // the running game, or the menu
let cursor = null // { x, y } smoothed fingertip
let pinching = false; let pinchEdge = false
let dwell = { id: null, t: 0 }
let ui = [] // pickable areas drawn this frame
let lastGesture = {}
const store = { get (k, d) { try { const v = localStorage.getItem('game.' + k); return v == null ? d : JSON.parse(v) } catch { return d } }, set (k, v) { try { localStorage.setItem('game.' + k, JSON.stringify(v)) } catch {} } }

// ---- Drawing helpers ----
function size () {
  const dpr = window.devicePixelRatio || 1
  W = cv.clientWidth; H = cv.clientHeight
  if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr) }
  g.setTransform(dpr, 0, 0, dpr, 0, 0)
}
function rr (x, y, w, h, r) { r = Math.min(r, w / 2, h / 2); g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath() }
function text (s, x, y, o = {}) {
  g.font = (o.weight || 400) + ' ' + (o.size || 16) + 'px ' + (o.emoji ? EMOJI : FONT)
  g.textAlign = o.align || 'left'; g.textBaseline = o.base || 'alphabetic'; g.fillStyle = o.color || C.text
  g.fillText(s, x, y, o.max || 4000)
}
function cameraBackground (alpha = 0.35) {
  if (video.readyState < 2) return
  const vw = video.videoWidth; const vh = video.videoHeight; const k = Math.max(W / vw, H / vh)
  g.save(); g.globalAlpha = alpha; g.translate(W, 0); g.scale(-1, 1)
  g.drawImage(video, (W - vw * k) / 2, (H - vh * k) / 2, vw * k, vh * k); g.restore()
  const v = g.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.2, W / 2, H / 2, Math.max(W, H) * 0.75)
  v.addColorStop(0, 'rgba(5,7,13,.25)'); v.addColorStop(1, 'rgba(5,7,13,.85)')
  g.fillStyle = v; g.fillRect(0, 0, W, H)
}
const BONES = [[0, 1], [1, 2], [2, 3], [3, 4], [0, 5], [5, 6], [6, 7], [7, 8], [5, 9], [9, 10], [10, 11], [11, 12], [9, 13], [13, 14], [14, 15], [15, 16], [13, 17], [17, 18], [18, 19], [19, 20], [0, 17]]
function drawHands (faint) {
  if (!R) return
  for (const h of R.hands) {
    const col = h.handed === 'right' ? C.cyan : C.pink
    g.strokeStyle = faint ? 'rgba(255,255,255,.18)' : col; g.lineWidth = faint ? 2 : 3; g.lineCap = 'round'
    g.globalAlpha = faint ? 1 : 0.75
    for (const [a, b] of BONES) { g.beginPath(); g.moveTo(h.P[a].x, h.P[a].y); g.lineTo(h.P[b].x, h.P[b].y); g.stroke() }
    g.globalAlpha = 1
    g.fillStyle = '#fff'
    for (const i of [4, 8, 12, 16, 20]) { g.beginPath(); g.arc(h.P[i].x, h.P[i].y, faint ? 3 : 5, 0, Math.PI * 2); g.fill() }
    if (!faint) text(label(h.gesture), h.P[0].x, h.P[0].y + 28, { align: 'center', size: 13, color: C.dim })
  }
}
const label = (gs) => ({ point: '☝ point', pinch: '🤏 pinch', palm: '✋ palm', fist: '✊ fist', two: '✌ two', three: 'three', thumbs: '👍 thumbs up', rock: '🤘', call: '🤙', L: 'L', ily: '🤟', ok: '👌', four: 'four', pinky: 'pinky' }[gs] || '')
function drawCursor () {
  if (!cursor) return
  const d = dwell.id ? Math.min(1, (performance.now() - dwell.t) / 1100) : 0
  g.strokeStyle = pinching ? C.lime : C.cyan; g.lineWidth = 3
  g.beginPath(); g.arc(cursor.x, cursor.y, pinching ? 10 : 14, 0, Math.PI * 2); g.stroke()
  if (d > 0) { g.strokeStyle = C.lime; g.lineWidth = 4; g.beginPath(); g.arc(cursor.x, cursor.y, 22, -Math.PI / 2, -Math.PI / 2 + d * Math.PI * 2); g.stroke() }
  g.fillStyle = '#fff'; g.beginPath(); g.arc(cursor.x, cursor.y, 3, 0, Math.PI * 2); g.fill()
}

/** A pickable button: picked by pinching on it, or by holding the cursor on it for a second. */
function button (id, x, y, w, h, draw, onPick) {
  const hover = cursor && cursor.x >= x && cursor.x <= x + w && cursor.y >= y && cursor.y <= y + h
  ui.push({ id, x, y, w, h, onPick, hover })
  draw(hover)
}
function pickLoop () {
  const hit = ui.find((u) => u.hover)
  if (!hit) { dwell = { id: null, t: 0 }; return }
  if (dwell.id !== hit.id) dwell = { id: hit.id, t: performance.now() }
  if (pinchEdge || (!usingMouse && performance.now() - dwell.t > 1100)) { dwell = { id: null, t: performance.now() + 600 }; hit.onPick(); chirp(880) }
}
function backButton () {
  button('back', 20, 18, 120, 44, (hv) => { rr(20, 18, 120, 44, 22); g.fillStyle = hv ? 'rgba(34,211,197,.25)' : 'rgba(255,255,255,.08)'; g.fill(); text('←  Menu', 80, 46, { align: 'center', weight: 600 }) }, () => go(null))
}
function chirp (f = 660, len = 0.08, vol = 0.07) {
  try {
    const ctx = chirp.ctx || (chirp.ctx = new AudioContext())
    const o = ctx.createOscillator(); const gn = ctx.createGain(); o.type = 'sine'; o.frequency.value = f
    gn.gain.setValueAtTime(vol, ctx.currentTime); gn.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + len)
    o.connect(gn).connect(ctx.destination); o.start(); o.stop(ctx.currentTime + len + 0.02)
  } catch {}
}
const primary = () => (R && R.hands.length ? (R.hands.find((h) => h.handed === 'right') || R.hands[0]) : null)
/** True on the frame a hand's gesture becomes [name] (not while it is held). */
function became (h, name, slot = 'p') { const was = lastGesture[slot]; return h && h.gesture === name && was !== name }
const held = {}
/** How long (s) a condition has been true, by key. */
function holdFor (key, on) { const now = performance.now(); if (!on) { delete held[key]; return 0 } if (!held[key]) held[key] = now; return (now - held[key]) / 1000 }

// ---- The games ----
const GAMES = []
const game = (o) => GAMES.push(o)

// Hand School: the training game.
const LESSONS = [
  { title: 'Point to move', hint: 'Hold up one finger and touch the 4 targets.', icon: '☝', kind: 'targets', gesture: null },
  { title: 'Pinch to pick', hint: 'Touch thumb and index together on each target.', icon: '🤏', kind: 'targets', gesture: 'pinch' },
  { title: 'Open palm', hint: 'Show your open hand and hold it for a second.', icon: '✋', kind: 'hold', gesture: 'palm' },
  { title: 'Make a fist', hint: 'Close your hand and hold it.', icon: '✊', kind: 'hold', gesture: 'fist' },
  { title: 'Two fingers', hint: 'Index and middle up, like a V. (It types on Air Keys.)', icon: '✌', kind: 'hold', gesture: 'two' },
  { title: 'Thumbs up', hint: 'Thumb up, other fingers closed.', icon: '👍', kind: 'hold', gesture: 'thumbs' },
  { title: 'Swipe left', hint: 'Sweep your hand quickly to the left.', icon: '👈', kind: 'swipe', gesture: 'left' },
  { title: 'Swipe right', hint: 'Now sweep it quickly to the right.', icon: '👉', kind: 'swipe', gesture: 'right' },
  { title: 'Punch', hint: 'Make a fist and punch toward the camera, twice.', icon: '👊', kind: 'punch', need: 2 },
  { title: 'Scroll', hint: 'Two fingers up, move them down slowly, then up.', icon: '↕', kind: 'scroll' }
]
game({
  id: 'school', name: 'Hand School', emoji: '🎓', blurb: 'Learn every move', face: false,
  enter () { this.i = 0; this.targets = null; this.count = 0; this.t0 = performance.now(); this.done = false; this.ok = 0; this.scrollMin = null; this.scrollMax = null },
  update () {
    if (this.done) return
    const L = LESSONS[this.i]; const h = primary()
    if (this.ok) { if (performance.now() - this.ok > 900) { this.ok = 0; this.i++; this.targets = null; this.count = 0; this.scrollMin = this.scrollMax = null; if (this.i >= LESSONS.length) { this.done = true; this.time = (performance.now() - this.t0) / 1000; const best = store.get('schoolBest', 0); if (!best || this.time < best) store.set('schoolBest', this.time) } } return }
    const pass = () => { this.ok = performance.now(); chirp(1046, 0.16, 0.09) }
    if (L.kind === 'targets') {
      if (!this.targets) {
        // Four targets, never on top of each other.
        this.targets = []
        for (let n = 0; this.targets.length < 4 && n < 400; n++) {
          const t = { x: W * (0.2 + 0.6 * Math.random()), y: H * (0.4 + 0.4 * Math.random()), hit: false, k: this.targets.length }
          if (this.targets.every((o) => Math.hypot(o.x - t.x, o.y - t.y) > 150)) this.targets.push(t)
        }
      }
      for (const t of this.targets) if (!t.hit && cursor && Math.hypot(cursor.x - t.x, cursor.y - t.y) < 46 && (!L.gesture || (h && h.gesture === L.gesture))) { t.hit = true; chirp(700 + t.k * 120) }
      if (this.targets.every((t) => t.hit)) pass()
    } else if (L.kind === 'hold') {
      if (holdFor('school', h && h.gesture === L.gesture) > 0.9) { delete held.school; pass() }
    } else if (L.kind === 'swipe') {
      if (R && R.hands.some((x) => x.swipe === L.gesture)) pass()
    } else if (L.kind === 'punch') {
      if (R && R.hands.some((x) => x.punch)) { this.count++; chirp(300, 0.12, 0.12); if (this.count >= L.need) pass() }
    } else if (L.kind === 'scroll' && h && h.gesture === 'two') {
      const y = h.tip.y
      this.scrollMin = Math.min(this.scrollMin == null ? y : this.scrollMin, y); this.scrollMax = Math.max(this.scrollMax == null ? y : this.scrollMax, y)
      if (this.scrollMax - this.scrollMin > H * 0.3 && y < this.scrollMax - H * 0.2) pass()
    }
  },
  draw () {
    if (this.done) {
      text('🎓', W / 2, H * 0.36, { align: 'center', size: 90, emoji: true })
      text('You know every move!', W / 2, H * 0.5, { align: 'center', size: 40, weight: 700 })
      text('Finished in ' + this.time.toFixed(1) + ' s · best ' + store.get('schoolBest', this.time).toFixed(1) + ' s', W / 2, H * 0.57, { align: 'center', size: 18, color: C.dim })
      button('again', W / 2 - 110, H * 0.64, 220, 54, (hv) => { rr(W / 2 - 110, H * 0.64, 220, 54, 27); g.fillStyle = hv ? C.cyan : 'rgba(255,255,255,.1)'; g.fill(); text('Again', W / 2, H * 0.64 + 35, { align: 'center', weight: 700, color: hv ? '#00110F' : C.text }) }, () => this.enter())
      return
    }
    const L = LESSONS[this.i]
    // Progress dots.
    LESSONS.forEach((_, k) => { g.fillStyle = k < this.i ? C.lime : k === this.i ? C.cyan : 'rgba(255,255,255,.15)'; g.beginPath(); g.arc(W / 2 - (LESSONS.length - 1) * 11 + k * 22, 40, 6, 0, Math.PI * 2); g.fill() })
    text(L.icon, W / 2, H * 0.2, { align: 'center', size: 64, emoji: true })
    text((this.i + 1) + '. ' + L.title, W / 2, H * 0.27, { align: 'center', size: 34, weight: 700 })
    text(L.hint, W / 2, H * 0.31, { align: 'center', size: 18, color: C.dim })
    if (L.kind === 'targets' && this.targets) for (const t of this.targets) {
      g.strokeStyle = t.hit ? C.lime : C.cyan; g.lineWidth = 4; g.beginPath(); g.arc(t.x, t.y, 36 + (t.hit ? 0 : Math.sin(performance.now() / 200 + t.k) * 4), 0, Math.PI * 2); g.stroke()
      if (t.hit) text('✓', t.x, t.y + 12, { align: 'center', size: 32, weight: 700, color: C.lime })
    }
    if (L.kind === 'hold') { const p = Math.min(1, (held.school ? (performance.now() - held.school) / 900 : 0)); g.strokeStyle = 'rgba(255,255,255,.12)'; g.lineWidth = 10; g.beginPath(); g.arc(W / 2, H * 0.6, 70, 0, Math.PI * 2); g.stroke(); g.strokeStyle = C.lime; g.beginPath(); g.arc(W / 2, H * 0.6, 70, -Math.PI / 2, -Math.PI / 2 + p * Math.PI * 2); g.stroke() }
    if (L.kind === 'punch') text(this.count + ' / ' + L.need, W / 2, H * 0.62, { align: 'center', size: 48, weight: 700, color: C.amber })
    if (this.ok) text('Nice!', W / 2, H * 0.82, { align: 'center', size: 44, weight: 800, color: C.lime })
    const h = primary(); text(h ? 'I see: ' + (label(h.gesture) || '…') : 'Show me your hand', W / 2, H - 30, { align: 'center', size: 16, color: C.dim })
  }
})

// Orb Pop.
game({
  id: 'pop', name: 'Orb Pop', emoji: '🫧', blurb: 'Pop orbs with your fingers', face: false,
  enter () { this.orbs = []; this.score = 0; this.combo = 0; this.end = performance.now() + 45000; this.next = 0; this.bursts = []; this.over = false },
  update (dt) {
    const now = performance.now()
    if (this.over) return
    if (now > this.end) { this.over = true; const b = store.get('popBest', 0); if (this.score > b) store.set('popBest', this.score); chirp(523, 0.3, 0.1); return }
    if (now > this.next) {
      const kind = Math.random() < 0.08 ? 'gold' : Math.random() < 0.12 ? 'bomb' : 'orb'
      this.orbs.push({ x: W * (0.1 + 0.8 * Math.random()), y: H + 40, r: kind === 'gold' ? 26 : 34 + Math.random() * 14, v: 90 + Math.random() * 120 + (45000 - (this.end - now)) / 300, kind, hue: Math.random() })
      this.next = now + 420 - Math.min(250, (45000 - (this.end - now)) / 200)
    }
    const tips = (R ? R.hands : []).flatMap((h) => [h.P[8], h.P[12]])
    for (const o of this.orbs) {
      o.y -= o.v * dt; o.x += Math.sin(o.y / 60 + o.hue * 6) * 0.6
      if (!o.dead && tips.some((t) => Math.hypot(t.x - o.x, t.y - o.y) < o.r + 8)) {
        o.dead = true
        if (o.kind === 'bomb') { this.score = Math.max(0, this.score - 5); this.combo = 0; chirp(160, 0.25, 0.12) } else { this.combo++; this.score += (o.kind === 'gold' ? 5 : 1) * Math.min(4, 1 + Math.floor(this.combo / 5)); chirp(600 + Math.min(12, this.combo) * 40) }
        this.bursts.push({ x: o.x, y: o.y, t: now, col: o.kind === 'bomb' ? C.red : o.kind === 'gold' ? C.amber : C.cyan })
      }
      if (o.y < -60 && !o.dead && o.kind !== 'bomb') this.combo = 0
    }
    this.orbs = this.orbs.filter((o) => !o.dead && o.y > -80)
    this.bursts = this.bursts.filter((b) => now - b.t < 500)
  },
  draw () {
    const now = performance.now()
    for (const o of this.orbs) {
      const col = o.kind === 'gold' ? C.amber : o.kind === 'bomb' ? C.red : [C.cyan, C.blue, C.lime, C.pink][Math.floor(o.hue * 4)]
      const gr = g.createRadialGradient(o.x - o.r * 0.3, o.y - o.r * 0.3, 2, o.x, o.y, o.r)
      gr.addColorStop(0, '#fff'); gr.addColorStop(0.35, col); gr.addColorStop(1, 'rgba(0,0,0,.1)')
      g.fillStyle = gr; g.beginPath(); g.arc(o.x, o.y, o.r, 0, Math.PI * 2); g.fill()
      if (o.kind === 'bomb') text('✖', o.x, o.y + 8, { align: 'center', size: 22, weight: 700 })
    }
    for (const b of this.bursts) { const k = (now - b.t) / 500; g.strokeStyle = b.col; g.globalAlpha = 1 - k; g.lineWidth = 4; g.beginPath(); g.arc(b.x, b.y, 20 + k * 50, 0, Math.PI * 2); g.stroke(); g.globalAlpha = 1 }
    text(String(this.score), W - 40, 60, { align: 'right', size: 44, weight: 800 })
    if (this.combo >= 5) text('combo ×' + Math.min(4, 1 + Math.floor(this.combo / 5)), W - 40, 88, { align: 'right', size: 16, color: C.amber })
    text(Math.max(0, Math.ceil((this.end - now) / 1000)) + ' s', W / 2, 52, { align: 'center', size: 28, weight: 700, color: C.dim })
    if (this.over) {
      g.fillStyle = 'rgba(5,7,13,.7)'; g.fillRect(0, 0, W, H)
      text('Score ' + this.score, W / 2, H * 0.45, { align: 'center', size: 56, weight: 800 })
      text('Best ' + store.get('popBest', this.score), W / 2, H * 0.52, { align: 'center', size: 20, color: C.dim })
      button('again', W / 2 - 110, H * 0.6, 220, 54, (hv) => { rr(W / 2 - 110, H * 0.6, 220, 54, 27); g.fillStyle = hv ? C.cyan : 'rgba(255,255,255,.1)'; g.fill(); text('Play again', W / 2, H * 0.6 + 35, { align: 'center', weight: 700, color: hv ? '#00110F' : C.text }) }, () => this.enter())
    }
  }
})

// Face Dash.
game({
  id: 'dash', name: 'Face Dash', emoji: '🙂', blurb: 'Steer with your head', face: true,
  enter () { tracker && tracker.recenter(); this.x = 0.5; this.rocks = []; this.stars = []; this.score = 0; this.lives = 3; this.next = 0; this.speed = 260; this.over = false; this.shield = 0; this.hurt = 0 },
  update (dt) {
    if (this.over) return
    const now = performance.now(); const f = R && R.face
    if (f) this.x += ((0.5 + Math.max(-1, Math.min(1, f.yaw * 2.4)) * 0.45) - this.x) * Math.min(1, dt * 8)
    this.shield = f && f.jaw > 0.45 ? 1 : Math.max(0, this.shield - dt * 3)
    this.speed += dt * 6
    if (now > this.next) { this.next = now + Math.max(260, 900 - this.speed); if (Math.random() < 0.7) this.rocks.push({ x: Math.random(), y: -40, r: 20 + Math.random() * 18 }); else this.stars.push({ x: Math.random(), y: -30 }) }
    const px = W * 0.1 + this.x * W * 0.8; const py = H * 0.84
    for (const r of this.rocks) { r.y += this.speed * dt; if (!r.hit && Math.hypot(W * 0.1 + r.x * W * 0.8 - px, r.y - py) < r.r + 26) { r.hit = true; if (this.shield > 0.5) { this.score += 2; chirp(900) } else { this.lives--; this.hurt = now; chirp(150, 0.3, 0.12); if (this.lives <= 0) { this.over = true; const b = store.get('dashBest', 0); if (this.score > b) store.set('dashBest', this.score) } } } }
    for (const s of this.stars) { s.y += this.speed * dt; if (!s.got && Math.hypot(W * 0.1 + s.x * W * 0.8 - px, s.y - py) < 40) { s.got = true; this.score += 5; chirp(1200) } }
    this.rocks = this.rocks.filter((r) => r.y < H + 50 && !r.hit); this.stars = this.stars.filter((s) => s.y < H + 50 && !s.got)
    this.score += dt * 2
  },
  draw () {
    const px = W * 0.1 + this.x * W * 0.8; const py = H * 0.84
    for (const r of this.rocks) { g.fillStyle = '#3A4458'; g.beginPath(); g.arc(W * 0.1 + r.x * W * 0.8, r.y, r.r, 0, Math.PI * 2); g.fill(); g.strokeStyle = C.red; g.lineWidth = 2; g.stroke() }
    for (const s of this.stars) text('⭐', W * 0.1 + s.x * W * 0.8, s.y + 12, { align: 'center', size: 30, emoji: true })
    const hurt = performance.now() - this.hurt < 400
    g.fillStyle = hurt ? C.red : C.cyan; g.shadowColor = g.fillStyle; g.shadowBlur = 30
    g.beginPath(); g.arc(px, py, 24, 0, Math.PI * 2); g.fill(); g.shadowBlur = 0
    if (this.shield > 0.1) { g.strokeStyle = 'rgba(182,243,106,' + this.shield + ')'; g.lineWidth = 5; g.beginPath(); g.arc(px, py, 40, 0, Math.PI * 2); g.stroke() }
    text(String(Math.floor(this.score)), W - 40, 60, { align: 'right', size: 44, weight: 800 })
    text('❤'.repeat(Math.max(0, this.lives)), 160, 48, { size: 26, color: C.red, emoji: true })
    text('Turn your head to steer · open your mouth for a shield', W / 2, H - 20, { align: 'center', size: 14, color: C.dim })
    if (!(R && R.face)) text('Look at the camera', W / 2, H / 2, { align: 'center', size: 26, color: C.amber })
    if (this.over) {
      g.fillStyle = 'rgba(5,7,13,.7)'; g.fillRect(0, 0, W, H)
      text('Score ' + Math.floor(this.score), W / 2, H * 0.45, { align: 'center', size: 56, weight: 800 })
      text('Best ' + Math.floor(store.get('dashBest', this.score)), W / 2, H * 0.52, { align: 'center', size: 20, color: C.dim })
      button('again', W / 2 - 110, H * 0.6, 220, 54, (hv) => { rr(W / 2 - 110, H * 0.6, 220, 54, 27); g.fillStyle = hv ? C.cyan : 'rgba(255,255,255,.1)'; g.fill(); text('Play again', W / 2, H * 0.6 + 35, { align: 'center', weight: 700, color: hv ? '#00110F' : C.text }) }, () => this.enter())
    }
  }
})

// Air Keys: a virtual keyboard.
const ROWS = ['QWERTYUIOP', 'ASDFGHJKL\'', 'ZXCVBNM,.?']
game({
  id: 'keys', name: 'Air Keys', emoji: '⌨️', blurb: 'One finger moves, two type', face: false,
  enter () { this.text = ''; this.flash = null },
  keys () {
    const kw = Math.min(86, (W - 120) / 10.5); const kh = kw * 0.9; const x0 = (W - kw * 10.4) / 2; const y0 = H * 0.42
    const out = []
    ROWS.forEach((row, r) => [...row].forEach((ch, c) => out.push({ ch, x: x0 + (c + r * 0.25) * kw * 1.04, y: y0 + r * kh * 1.08, w: kw, h: kh })))
    const y3 = y0 + 3 * kh * 1.08
    out.push({ ch: '⌫', x: x0, y: y3, w: kw * 1.6, h: kh }, { ch: ' ', x: x0 + kw * 1.7, y: y3, w: kw * 5.9, h: kh, label: 'space' }, { ch: '⏎', x: x0 + kw * 7.7, y: y3, w: kw * 2.7, h: kh, label: 'Ask Meshron' })
    return out
  },
  update () {
    const h = primary(); if (!h || !cursor) return
    // Typing: two fingers (or a pinch) while the index fingertip is over a key.
    if (became(h, 'two') || pinchEdge) {
      const k = this.keys().find((k) => cursor.x >= k.x && cursor.x <= k.x + k.w && cursor.y >= k.y && cursor.y <= k.y + k.h)
      if (!k) return
      this.flash = { k: k.ch, t: performance.now() }; chirp(1200, 0.04, 0.05)
      if (k.ch === '⌫') this.text = this.text.slice(0, -1)
      else if (k.ch === '⏎') { if (this.text.trim()) { A.ask(this.text.trim()); this.sent = performance.now(); this.text = '' } } else if (this.text.length < 120) this.text += this.text.length ? k.ch.toLowerCase() : k.ch
    }
  },
  draw () {
    rr(W * 0.12, H * 0.16, W * 0.76, 90, 18); g.fillStyle = 'rgba(12,18,30,.9)'; g.fill(); g.strokeStyle = 'rgba(34,211,197,.35)'; g.lineWidth = 1.5; g.stroke()
    const caret = Math.floor(performance.now() / 500) % 2 ? '|' : ''
    text((this.text || '') + caret, W * 0.14, H * 0.16 + 56, { size: 30, max: W * 0.72, color: this.text ? C.text : C.dim })
    if (!this.text) text('Type something to ask Meshron…', W * 0.14 + 14, H * 0.16 + 56, { size: 24, color: 'rgba(143,166,188,.6)' })
    if (this.sent && performance.now() - this.sent < 2000) text('Sent to Meshron ✓', W / 2, H * 0.16 + 120, { align: 'center', size: 16, color: C.lime })
    for (const k of this.keys()) {
      const over = cursor && cursor.x >= k.x && cursor.x <= k.x + k.w && cursor.y >= k.y && cursor.y <= k.y + k.h
      const hit = this.flash && this.flash.k === k.ch && performance.now() - this.flash.t < 180
      rr(k.x, k.y, k.w, k.h, 12); g.fillStyle = hit ? C.lime : over ? 'rgba(34,211,197,.35)' : 'rgba(255,255,255,.08)'; g.fill()
      text(k.label || k.ch, k.x + k.w / 2, k.y + k.h / 2 + 8, { align: 'center', size: k.label ? 16 : 24, weight: 600, color: hit ? '#00110F' : C.text })
    }
    text('☝ one finger moves · ✌ two fingers (or a pinch) types', W / 2, H - 24, { align: 'center', size: 15, color: C.dim })
  }
})

// Mood Mirror.
const moodOrb = document.createElement('canvas')
moodOrb.style.cssText = 'position:absolute;width:300px;height:300px;left:50%;top:44%;transform:translate(-50%,-50%);display:none;pointer-events:none'
document.body.appendChild(moodOrb)
const moodR = window.Reactor(moodOrb)
game({
  id: 'mood', name: 'Mood Mirror', emoji: '🎭', blurb: 'A face that copies you', face: true,
  enter () { moodOrb.style.display = 'block'; this.share = store.get('moodShare', false); this.last = null; this.since = 0; A.settings().then((s) => { const face = window.Reactor.SKINS.find((k) => k.id === s.skin && k.face); moodR.skin = face ? face.id : 'buddy' }) },
  exit () { moodOrb.style.display = 'none' },
  update () {
    const f = R && R.face
    if (!f) { moodR.mode = 'idle'; return }
    const m = { happy: 'happy', surprised: 'surprised', sad: 'sad', angry: 'sad', sleepy: null, neutral: null }[f.mood]
    moodR.mood = m; moodR.moodUntil = performance.now() + 400
    moodR.mode = f.jaw > 0.25 ? 'speaking' : 'idle'
    if (f.mood !== this.last) { this.last = f.mood; this.since = performance.now() }
    // Tell Meshron once a mood has lasted a moment (its orb shows it).
    if (this.share && f.mood !== 'neutral' && performance.now() - this.since > 1200 && this.told !== f.mood) { this.told = f.mood; A.gameMood && A.gameMood(m || 'happy') }
  },
  draw () {
    const f = R && R.face
    const NAMES = { happy: 'Happy 😄', surprised: 'Surprised 😮', sad: 'Sad 😢', angry: 'Angry 😠', sleepy: 'Sleepy 😴', neutral: 'Calm 🙂' }
    text(f ? NAMES[f.mood] : 'Look at the camera', W / 2, H * 0.74, { align: 'center', size: 40, weight: 700, emoji: true })
    if (f) {
      const bars = [['Smile', f.smile, C.lime], ['Mouth open', f.jaw, C.cyan], ['Frown', f.frown, C.blue], ['Brows down', f.browDown, C.red], ['Eyes wide', f.wide, C.amber]]
      bars.forEach(([n, v, col], i) => { const x = W * 0.08; const y = H * 0.3 + i * 40; text(n, x, y, { size: 14, color: C.dim }); rr(x, y + 8, 200, 10, 5); g.fillStyle = 'rgba(255,255,255,.08)'; g.fill(); rr(x, y + 8, Math.max(10, 200 * v), 10, 5); g.fillStyle = col; g.fill() })
      text('Head ' + (f.yaw > 0.12 ? 'turned right' : f.yaw < -0.12 ? 'turned left' : 'straight') + (f.pitch > 0.12 ? ', down' : f.pitch < -0.12 ? ', up' : ''), W * 0.92, H * 0.3, { align: 'right', size: 16, color: C.dim })
    }
    button('share', W / 2 - 160, H * 0.8, 320, 50, (hv) => { rr(W / 2 - 160, H * 0.8, 320, 50, 25); g.fillStyle = this.share ? 'rgba(182,243,106,.25)' : hv ? 'rgba(34,211,197,.25)' : 'rgba(255,255,255,.08)'; g.fill(); text(this.share ? '✓ Meshron sees your mood' : 'Let Meshron see my mood', W / 2, H * 0.8 + 32, { align: 'center', weight: 600 }) }, () => { this.share = !this.share; store.set('moodShare', this.share) })
  }
})

// Fingerspell: a subset of the American Sign Language manual alphabet.
const SIGNS = {
  A: 'Fist, thumb resting up the side of your index finger', B: 'Four fingers straight up together, thumb folded across the palm', D: 'Index up, other fingertips touch your thumb',
  F: 'Thumb and index touch in a circle, the other three fingers up', I: 'Only your little finger up', L: 'Thumb and index make an L',
  O: 'All fingertips curve to touch your thumb, an O', S: 'Fist with your thumb across the front of your fingers', U: 'Index and middle up, pressed together',
  V: 'Index and middle up, spread apart', W: 'Index, middle and ring up, spread', Y: 'Thumb and little finger out', '🤟': 'I love you: thumb, index and little finger out'
}
function spell (h) {
  if (!h) return null
  const P = h.P; const s = h.size; const dd = (a, b) => Math.hypot(P[a].x - P[b].x, P[a].y - P[b].y)
  if (dd(4, 8) < s * 0.42 && dd(4, 12) < s * 0.5 && dd(4, 16) < s * 0.6 && !h.ext[1] && !h.ext[2]) return 'O'
  switch (h.gesture) {
    case 'ily': return '🤟'
    case 'call': return 'Y'
    case 'pinky': return 'I'
    case 'L': return 'L'
    case 'three': return 'W'
    case 'two': return dd(8, 12) < s * 0.38 ? 'U' : 'V'
    case 'ok': return 'F'
    case 'four': return 'B'
    case 'palm': return dd(4, 9) < s * 0.7 ? 'B' : null
    case 'point': return dd(4, 12) < s * 0.45 ? 'D' : null
    case 'fist': case 'thumbs': return dd(4, 10) < s * 0.38 || dd(4, 14) < s * 0.38 ? 'S' : 'A'
  }
  return null
}
const ORDER = Object.keys(SIGNS)
game({
  id: 'spell', name: 'Fingerspell', emoji: '🤟', blurb: 'Sign language letters', face: false,
  enter () { this.mode = 'learn'; this.i = 0; this.word = ''; this.got = 0; this.cur = null; this.since = 0; this.used = false },
  update () {
    const h = primary(); const L = spell(h); const now = performance.now()
    if (L !== this.cur) { this.cur = L; this.since = now; this.used = false }
    if (!L || this.used) return
    if (this.mode === 'learn') {
      if (L === ORDER[this.i] && now - this.since > 800) { this.used = true; this.got = now; chirp(1046, 0.15, 0.09); setTimeout(() => { this.i = (this.i + 1) % ORDER.length }, 700) }
    } else if (now - this.since > 1000) {
      this.used = true; chirp(880, 0.06)
      this.word += L === '🤟' ? ' I love you ' : L
    }
    if (this.mode === 'spell' && R && R.hands.some((x) => x.swipe === 'left')) this.word = this.word.slice(0, -1)
  },
  draw () {
    button('mode', W - 300, 18, 280, 44, (hv) => { rr(W - 300, 18, 280, 44, 22); g.fillStyle = hv ? 'rgba(34,211,197,.25)' : 'rgba(255,255,255,.08)'; g.fill(); text(this.mode === 'learn' ? 'Switch to: spell words' : 'Switch to: learn letters', W - 160, 46, { align: 'center', weight: 600 }) }, () => { this.mode = this.mode === 'learn' ? 'spell' : 'learn'; this.word = '' })
    const h = primary(); const L = spell(h)
    if (this.mode === 'learn') {
      const T = ORDER[this.i]
      text('Sign', W / 2, H * 0.18, { align: 'center', size: 20, color: C.dim })
      text(T, W / 2, H * 0.36, { align: 'center', size: 130, weight: 800, emoji: T === '🤟', color: this.got && performance.now() - this.got < 700 ? C.lime : C.text })
      text(SIGNS[T], W / 2, H * 0.44, { align: 'center', size: 20, color: C.dim, max: W * 0.8 })
    } else {
      text('Hold each letter for a second · swipe left to delete', W / 2, H * 0.18, { align: 'center', size: 18, color: C.dim })
      rr(W * 0.15, H * 0.24, W * 0.7, 90, 18); g.fillStyle = 'rgba(12,18,30,.9)'; g.fill()
      text(this.word || '…', W / 2, H * 0.24 + 60, { align: 'center', size: 44, weight: 700, max: W * 0.66 })
      if (this.word.trim()) button('send', W / 2 - 130, H * 0.24 + 110, 260, 48, (hv) => { rr(W / 2 - 130, H * 0.24 + 110, 260, 48, 24); g.fillStyle = hv ? C.cyan : 'rgba(255,255,255,.1)'; g.fill(); text('Ask Meshron', W / 2, H * 0.24 + 141, { align: 'center', weight: 700, color: hv ? '#00110F' : C.text }) }, () => { A.ask(this.word.trim()); this.word = '' })
    }
    text(L ? 'I see: ' + L : h ? 'Not a letter I know yet' : 'Show me your hand', W / 2, H * 0.62, { align: 'center', size: 26, weight: 600, emoji: L === '🤟', color: L ? C.cyan : C.dim })
    text('A learning toy: static fingerspelling letters only (A B D F I L O S U V W Y and I-love-you). Full sign language uses motion, both hands and the face.', W / 2, H - 22, { align: 'center', size: 13, color: C.dim, max: W - 40 })
  }
})

// Control modes: they run over other apps from a small card in the corner.
let inputSize = null
async function screenPx () { if (!inputSize) inputSize = await A.gameScreen(); return inputSize }
const VK = { W: 0x57, SPACE: 0x20, E: 0x45, ALT: 0x12, LEFT: 0x25, RIGHT: 0x27, PLAYPAUSE: 0xB3 }
const tap = (vk, ms = 60) => { A.gameInput({ type: 'key', vk, down: true }); setTimeout(() => A.gameInput({ type: 'key', vk, down: false }), ms) }
const click = (b = 'l') => { A.gameInput({ type: 'button', b, down: true }); setTimeout(() => A.gameInput({ type: 'button', b, down: false }), 70) }

game({
  id: 'mouse', name: 'Hand Control', emoji: '🖱️', blurb: 'Mouse, click and scroll by hand', face: false, compact: true,
  async enter () { this.paused = false; this.down = false; this.lastPos = null; this.lastScroll = null; await screenPx() },
  exit () { if (this.down) A.gameInput({ type: 'button', b: 'l', down: false }); A.gameRelease() },
  update () {
    const h = primary()
    if (!h) { if (this.down) { this.down = false; A.gameInput({ type: 'button', b: 'l', down: false }) } return }
    // A held fist pauses / resumes.
    if (holdFor('pausefist', h.gesture === 'fist') > 0.9) { delete held.pausefist; this.paused = !this.paused; chirp(this.paused ? 440 : 880) }
    if (this.paused || !inputSize) return
    // The middle of the camera's view maps to the whole screen (so you don't reach for the edges).
    const nx = Math.max(0, Math.min(1, (h.P[8].x / W - 0.2) / 0.6)); const ny = Math.max(0, Math.min(1, (h.P[8].y / H - 0.15) / 0.6))
    const x = Math.round(nx * inputSize.w); const y = Math.round(ny * inputSize.h)
    if (h.gesture === 'two') {
      // Two fingers: scroll by moving them up and down.
      if (this.lastScroll != null) { const dy = h.tip.y - this.lastScroll; if (Math.abs(dy) > 2) A.gameInput({ type: 'wheel', delta: -dy * 6 }) }
      this.lastScroll = h.tip.y
    } else {
      this.lastScroll = null
      if (!this.lastPos || Math.abs(x - this.lastPos.x) + Math.abs(y - this.lastPos.y) > 1) { A.gameInput({ type: 'pos', x, y }); this.lastPos = { x, y } }
    }
    // Pinch: press (hold the pinch to drag).
    if (h.pinch && !this.down) { this.down = true; A.gameInput({ type: 'button', b: 'l', down: true }) } else if (!h.pinch && this.down) { this.down = false; A.gameInput({ type: 'button', b: 'l', down: false }) }
    if (h.swipe === 'left' || h.swipe === 'right') { A.gameInput({ type: 'key', vk: VK.ALT, down: true }); tap(h.swipe === 'left' ? VK.LEFT : VK.RIGHT); setTimeout(() => A.gameInput({ type: 'key', vk: VK.ALT, down: false }), 120) }
  },
  legend: () => ['☝ move', '🤏 click / drag', '✌ scroll', '👈👉 back / forward', '✊ hold: pause'],
  status () { return this.paused ? 'Paused (hold a fist)' : 'Controlling your PC' }
})

game({
  id: 'pad', name: 'Game Pad', emoji: '🎮', blurb: 'Play PC games with your body', face: true, compact: true,
  enter () { tracker && tracker.recenter(); this.walk = false; this.paused = false },
  exit () { A.gameRelease() },
  update (dt) {
    const h = primary(); const f = R && R.face
    if (holdFor('padpause', R && R.hands.length === 2 && R.hands.every((x) => x.gesture === 'palm')) > 1) { delete held.padpause; this.paused = !this.paused; A.gameRelease(); this.walk = false; chirp(this.paused ? 440 : 880) }
    if (this.paused) return
    // Look around: turn your head (past a small dead zone) and the view keeps turning.
    if (f) {
      const dz = 0.07; const sp = 1300
      const ax = Math.abs(f.yaw) > dz ? Math.sign(f.yaw) * (Math.abs(f.yaw) - dz) : 0
      const ay = Math.abs(f.pitch) > dz ? Math.sign(f.pitch) * (Math.abs(f.pitch) - dz) : 0
      if (ax || ay) A.gameInput({ type: 'move', dx: ax * sp * dt * 2, dy: ay * sp * dt * 1.5 })
    }
    if (!h) { if (this.walk) { this.walk = false; A.gameInput({ type: 'key', vk: VK.W, down: false }) } return }
    const want = h.gesture === 'palm'
    if (want !== this.walk) { this.walk = want; A.gameInput({ type: 'key', vk: VK.W, down: want }) }
    if (R.hands.some((x) => x.punch)) { click('l'); this.hit = performance.now() }
    if (became(h, 'pinch')) click('r')
    if (became(h, 'two')) tap(VK.SPACE, 120)
    if (became(h, 'thumbs')) tap(VK.E)
    if (h.swipe) A.gameInput({ type: 'wheel', delta: h.swipe === 'left' ? 120 : -120 })
  },
  legend: () => ['🙂 turn head: look', '👊 punch: hit', '✋ palm: walk', '✌ jump', '🤏 use / place', '👍 inventory', '👈👉 hotbar', '✋✋ both palms: pause'],
  status () { return this.paused ? 'Paused (both palms)' : this.walk ? 'Walking' : 'Ready' }
})

// ---- Menu ----
function menu () {
  text('MESHRON GAME MODE', W / 2, 58, { align: 'center', size: 15, weight: 700, color: C.cyan })
  text('Point to choose · pinch or hold still to pick', W / 2, 84, { align: 'center', size: 15, color: C.dim })
  const cols = 4; const gap = 18; const tw = Math.min(250, (W - 120 - gap * 3) / cols); const th = tw * 0.82
  const x0 = (W - (tw * cols + gap * (cols - 1))) / 2; const y0 = Math.max(120, (H - th * 2 - gap) / 2 + 10)
  GAMES.forEach((gm, i) => {
    const x = x0 + (i % cols) * (tw + gap); const y = y0 + Math.floor(i / cols) * (th + gap)
    button(gm.id, x, y, tw, th, (hv) => {
      const lift = hv ? -4 : 0
      rr(x, y + lift, tw, th, 22); g.fillStyle = hv ? 'rgba(34,211,197,.18)' : 'rgba(12,18,30,.82)'; g.fill()
      g.strokeStyle = hv ? C.cyan : 'rgba(255,255,255,.08)'; g.lineWidth = hv ? 2 : 1; g.stroke()
      text(gm.emoji, x + tw / 2, y + lift + th * 0.45, { align: 'center', size: tw * 0.26, emoji: true })
      text(gm.name, x + tw / 2, y + lift + th * 0.68, { align: 'center', size: 19, weight: 700 })
      text(gm.blurb, x + tw / 2, y + lift + th * 0.82, { align: 'center', size: 13, color: C.dim, max: tw - 16 })
      if (gm.face) text('face', x + tw - 14, y + lift + 24, { align: 'right', size: 11, color: C.amber })
    }, () => go(gm))
  })
  button('close', W - 140, 18, 120, 44, (hv) => { rr(W - 140, 18, 120, 44, 22); g.fillStyle = hv ? 'rgba(255,90,106,.3)' : 'rgba(255,255,255,.08)'; g.fill(); text('Close', W - 80, 46, { align: 'center', weight: 600 }) }, () => A.gameClose())
}

async function go (gm) {
  if (scene && scene.exit) scene.exit()
  if (scene && scene.compact) { await A.gameCompact(false); document.body.classList.remove('small') }
  scene = gm
  for (const k of Object.keys(held)) delete held[k]
  if (tracker) tracker.useFace(!gm || !!gm.face)
  if (gm) { if (gm.compact) { document.body.classList.add('small'); await A.gameCompact(true) } await gm.enter() }
}

// ---- The loop ----
let lastT = performance.now()
function loop (now) {
  const dt = Math.min(0.05, (now - lastT) / 1000); lastT = now
  size()
  g.clearRect(0, 0, W, H)
  if (tracker) {
    const r = tracker.frame(W, H); if (r) R = r
    const h = primary()
    usingMouse = false
    if (h) {
      const target = h.tip
      cursor = cursor ? { x: cursor.x + (target.x - cursor.x) * 0.45, y: cursor.y + (target.y - cursor.y) * 0.45 } : { x: target.x, y: target.y }
      pinchEdge = h.pinch && !pinching; pinching = h.pinch
    } else if (mouse) { cursor = { x: mouse.x, y: mouse.y }; pinchEdge = mouseClick; pinching = false; usingMouse = true } else { cursor = null; pinchEdge = false; pinching = false }
    mouseClick = false
    const small = scene && scene.compact
    cameraBackground(small ? 0.8 : scene ? 0.3 : 0.22)
    ui = []
    if (small) compactCard()
    else if (scene) { scene.update(dt); scene.draw(); backButton() } else menu()
    drawHands(!!scene && !small)
    if (!small) { pickLoop(); drawCursor() }
    lastGesture.p = h ? h.gesture : null
  }
  requestAnimationFrame(loop)
}
function compactCard () {
  scene.update(1 / 60)
  g.fillStyle = 'rgba(5,7,13,.55)'; g.fillRect(0, 0, W, 46)
  text(scene.emoji + '  ' + scene.name, 12, 29, { size: 15, weight: 700, emoji: true })
  text(scene.status ? scene.status() : '', W - 12, 29, { align: 'right', size: 12, color: C.lime })
  const lg = scene.legend ? scene.legend() : []
  g.fillStyle = 'rgba(5,7,13,.6)'; g.fillRect(0, H - 22 - Math.ceil(lg.length / 2) * 18, W, 200)
  lg.forEach((l, i) => text(l, 10 + (i % 2) * (W / 2), H - 12 - (Math.ceil(lg.length / 2) - 1 - Math.floor(i / 2)) * 18, { size: 11.5, color: C.text, emoji: true, max: W / 2 - 14 }))
  text('Ctrl+Alt+G stops · click here for the menu', W / 2, 62, { align: 'center', size: 11, color: C.dim })
}
// The mouse works too (when no hand is up): point and click.
let mouse = null; let mouseClick = false; let usingMouse = false
cv.addEventListener('mousemove', (e) => { mouse = { x: e.clientX, y: e.clientY } })
cv.addEventListener('mouseleave', () => { mouse = null })
cv.addEventListener('click', () => { if (scene && scene.compact) go(null); else mouseClick = true })
window.MeshronGame = { go: (id) => go(GAMES.find((x) => x.id === id) || null) }
window.addEventListener('keydown', (e) => { if (e.key === 'Escape') { if (scene) go(null); else A.gameClose() } })
A.on((ev, d) => {
  if (ev === 'game-stop') go(null)
  if (ev === 'game-progress') { $('bar').hidden = false; $('barfill').style.width = Math.round(d * 100) + '%' }
})

// ---- Start: models (asked once), camera, tracking ----
async function start () {
  const m = await A.gameModels()
  if (!m.ready) {
    $('gate').hidden = false
    window.Reactor($('gateorb')).skin = 'buddy'
    $('dlnote').textContent = 'First time only: download the hand and face tracking models (' + m.mb + ' MB).'
    await new Promise((resolve) => {
      $('later').onclick = () => A.gameClose()
      $('dl').onclick = async () => {
        $('gatebtns').hidden = true
        try { await A.gameDownload(); resolve() } catch (e) { $('dlnote').textContent = 'The download didn\'t work: ' + (e.message || e) + '. Check the internet and try again.'; $('gatebtns').hidden = false }
      }
    })
    $('gate').hidden = true
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ video: { width: 1280, height: 720, facingMode: 'user' }, audio: false })
    video.srcObject = stream; await video.play()
  } catch {
    $('gate').hidden = false; $('gatebtns').hidden = false; $('bar').hidden = true
    $('dlnote').textContent = 'I can\'t use the camera. Check Windows Settings → Privacy & security → Camera, and that no other app is using it.'
    $('dl').textContent = 'Try again'; $('dl').onclick = () => location.reload(); return
  }
  const [hand, face] = await Promise.all([A.gameModel('hand'), A.gameModel('face')])
  tracker = await createTracker(video, { hand, face })
  tracker.useFace(false)
}
requestAnimationFrame(loop)
start()
