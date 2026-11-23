// Meshron game mode (Windows and Android), drawn as a hologram: a HUD grid, scanlines and glowing
// wireframe panels. Your camera follows your hands and face (track.mjs, on the device only); by
// default the camera picture is NOT shown, only the hologram of your hands. Showing it is a choice.
// Pick from the menu by pointing (a fingertip reticle) and pinching or holding still; mouse and
// touch work too. Games:
//   Hand School   learn every gesture (the training game)
//   Block Drop    the falling-block puzzle: point to move, two fingers to turn, fist to drop faster
//   Block Builder stack blocks on a 3D grid: pinch to place, two fingers to remove
//   Orb Pop       pop rising orbs with your fingertips
//   Face Dash     steer with your head, open your mouth for a shield
//   Air Keys      a keyboard in the air: one finger moves, two fingers type
//   Mood Mirror   a face that copies your mood
//   Fingerspell   American Sign Language letters (a static subset)
//   Hand Control  move the mouse, click and scroll by hand (Windows)
//   Game Pad      play PC games with head and hands (Windows)
import { createTracker } from './track.mjs'

const A = window.Assistant
const ANDROID = A.platform === 'android'
const $ = (id) => document.getElementById(id)
const cv = $('c'); const g = cv.getContext('2d'); const video = $('v')
const C = { cyan: '#22D3C5', blue: '#6E7BFF', lime: '#B6F36A', amber: '#FFB020', red: '#FF5A6A', pink: '#FF6BD6', text: '#E6F7FF', dim: '#7FA3B8', faint: 'rgba(34,211,197,.35)' }
const FONT = '"Segoe UI Variable Display", "Segoe UI", Roboto, sans-serif'
const MONO = '"Cascadia Mono", Consolas, "Roboto Mono", monospace'
let W = 0; let H = 0; let S = 1
let tracker = null
let R = null
let scene = null
let cursor = null
let pinching = false; let pinchEdge = false
let dwell = { id: null, t: 0 }
let ui = []
const lastGesture = {}
const store = { get (k, d) { try { const v = localStorage.getItem('game.' + k); return v == null ? d : JSON.parse(v) } catch { return d } }, set (k, v) { try { localStorage.setItem('game.' + k, JSON.stringify(v)) } catch {} } }
let showCamera = store.get('showCamera', false)

// ---- The hologram look ----
function size () {
  const dpr = window.devicePixelRatio || 1
  W = cv.clientWidth; H = cv.clientHeight; S = Math.max(0.62, Math.min(1, Math.min(W / 1100, H / 700)))
  if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr) }
  g.setTransform(dpr, 0, 0, dpr, 0, 0)
}
function rr (x, y, w, h, r) { r = Math.min(r, w / 2, h / 2); g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath() }
function text (s, x, y, o = {}) {
  g.font = (o.weight || 400) + ' ' + Math.round((o.size || 16) * (o.raw ? 1 : S)) + 'px ' + (o.mono ? MONO : FONT)
  g.letterSpacing = o.mono ? (o.spacing == null ? 3 : o.spacing) + 'px' : '0px'
  g.textAlign = o.align || 'left'; g.textBaseline = o.base || 'alphabetic'; g.fillStyle = o.color || C.text
  if (o.glow) { g.shadowColor = o.glow; g.shadowBlur = 14 }
  g.fillText(o.mono ? String(s).toUpperCase() : s, x, y, o.max || 4000)
  g.shadowBlur = 0; g.letterSpacing = '0px'
}
function hexA (hex, a) { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})` }
function background (t) {
  g.fillStyle = '#03060B'; g.fillRect(0, 0, W, H)
  if (showCamera && video.readyState >= 2) {
    const vw = video.videoWidth; const vh = video.videoHeight; const k = Math.max(W / vw, H / vh)
    g.save(); g.globalAlpha = 0.28; g.filter = 'grayscale(1) contrast(1.2)'; g.translate(W, 0); g.scale(-1, 1)
    g.drawImage(video, (W - vw * k) / 2, (H - vh * k) / 2, vw * k, vh * k); g.restore(); g.filter = 'none'
    g.fillStyle = 'rgba(34,211,197,.08)'; g.fillRect(0, 0, W, H)
  }
  g.strokeStyle = 'rgba(34,211,197,.05)'; g.lineWidth = 1
  for (let x = (W / 2) % 48; x < W; x += 48) { g.beginPath(); g.moveTo(x, 0); g.lineTo(x, H); g.stroke() }
  for (let y = (H / 2) % 48; y < H; y += 48) { g.beginPath(); g.moveTo(0, y); g.lineTo(W, y); g.stroke() }
  const hz = H * 0.72
  g.strokeStyle = 'rgba(34,211,197,.08)'
  for (let i = -12; i <= 12; i++) { g.beginPath(); g.moveTo(W / 2 + i * 30, hz); g.lineTo(W / 2 + i * W * 0.12, H); g.stroke() }
  for (let k = 0; k < 7; k++) { const y = hz + (H - hz) * Math.pow((k + ((t * 0.25) % 1)) / 7, 2); g.beginPath(); g.moveTo(0, y); g.lineTo(W, y); g.stroke() }
}
function overlay (t) {
  g.fillStyle = 'rgba(0,0,0,.16)'
  for (let y = 0; y < H; y += 3) g.fillRect(0, y, W, 1)
  const sy = ((t * 0.12) % 1) * H
  const sw = g.createLinearGradient(0, sy - 60, 0, sy + 4); sw.addColorStop(0, 'rgba(34,211,197,0)'); sw.addColorStop(1, 'rgba(34,211,197,.06)')
  g.fillStyle = sw; g.fillRect(0, sy - 60, W, 64)
  const v = g.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.35, W / 2, H / 2, Math.max(W, H) * 0.75)
  v.addColorStop(0, 'rgba(3,6,11,0)'); v.addColorStop(1, 'rgba(3,6,11,.75)')
  g.fillStyle = v; g.fillRect(0, 0, W, H)
}
/** A hologram panel: a faint fill, a thin cyan line and bright corner brackets; brighter when hovered. */
function panel (x, y, w, h, hover, col = C.cyan) {
  rr(x, y, w, h, 6); g.fillStyle = hover ? hexA(col, 0.12) : 'rgba(8,22,34,.55)'; g.fill()
  g.strokeStyle = hover ? col : 'rgba(34,211,197,.28)'; g.lineWidth = 1; g.stroke()
  const L = Math.min(18, w / 5, h / 5)
  g.strokeStyle = col; g.lineWidth = 2; if (hover) { g.shadowColor = col; g.shadowBlur = 16 }
  for (const [cx, cy, dx, dy] of [[x, y, 1, 1], [x + w, y, -1, 1], [x, y + h, 1, -1], [x + w, y + h, -1, -1]]) { g.beginPath(); g.moveTo(cx, cy + dy * L); g.lineTo(cx, cy); g.lineTo(cx + dx * L, cy); g.stroke() }
  g.shadowBlur = 0
}
function hbutton (id, x, y, w, h, label, onPick, col) {
  button(id, x, y, w, h, (hv) => { panel(x, y, w, h, hv, col); text(label, x + w / 2, y + h / 2 + 5, { align: 'center', mono: true, size: 12, weight: 600, color: hv ? '#fff' : C.text, glow: hv ? (col || C.cyan) : null, raw: true, spacing: 2 }) }, onPick)
}
/** Line-art icons (no emoji), drawn in cyan with a glow. */
function icon (id, cx, cy, s, col = C.cyan) {
  g.save(); g.translate(cx, cy); g.strokeStyle = col; g.fillStyle = col; g.lineWidth = Math.max(1.5, s * 0.05); g.lineCap = 'round'; g.lineJoin = 'round'; g.shadowColor = col; g.shadowBlur = 12
  const line = (pts) => { g.beginPath(); pts.forEach(([x, y], i) => i ? g.lineTo(x * s, y * s) : g.moveTo(x * s, y * s)); g.stroke() }
  const box = (x, y, w, h) => g.strokeRect(x * s, y * s, w * s, h * s)
  switch (id) {
    case 'school':
      line([[-0.3, 0.45], [-0.3, -0.05], [-0.42, -0.25]])
      for (const [x, top] of [[-0.18, -0.42], [-0.04, -0.5], [0.1, -0.46], [0.24, -0.34]]) line([[x, 0], [x, top]])
      line([[-0.3, 0.45], [0.3, 0.45], [0.3, -0.1]]); break
    case 'drop': for (const [x, y] of [[-0.3, -0.45], [-0.1, -0.45], [-0.1, -0.25], [-0.4, 0.25], [-0.2, 0.25], [0, 0.25], [0.2, 0.25], [0.2, 0.05]]) box(x, y, 0.2, 0.2); break
    case 'build': {
      const cube = (ox, oy, k) => { line([[ox, oy - 0.25 * k], [ox + 0.22 * k, oy - 0.12 * k], [ox, oy], [ox - 0.22 * k, oy - 0.12 * k], [ox, oy - 0.25 * k]]); line([[ox - 0.22 * k, oy - 0.12 * k], [ox - 0.22 * k, oy + 0.14 * k], [ox, oy + 0.27 * k], [ox + 0.22 * k, oy + 0.14 * k], [ox + 0.22 * k, oy - 0.12 * k]]); line([[ox, oy], [ox, oy + 0.27 * k]]) }
      cube(0, 0.2, 1.3); cube(0, -0.15, 1); break
    }
    case 'pop': for (const [x, y, r] of [[-0.12, 0.05, 0.28], [0.24, -0.28, 0.14], [0.28, 0.3, 0.09]]) { g.beginPath(); g.arc(x * s, y * s, r * s, 0, Math.PI * 2); g.stroke() } break
    case 'dash': line([[0, -0.4], [0.32, 0.32], [-0.32, 0.32], [0, -0.4]]); line([[-0.55, 0.45], [-0.45, 0.1]]); line([[0.55, 0.45], [0.45, 0.1]]); break
    case 'keys': box(-0.5, -0.3, 1, 0.6); for (let r = 0; r < 3; r++) for (let k = 0; k < 6; k++) g.fillRect((-0.42 + k * 0.15) * s, (-0.2 + r * 0.14) * s, 0.08 * s, 0.07 * s); break
    case 'mood': g.beginPath(); g.arc(0, 0, 0.42 * s, 0, Math.PI * 2); g.stroke(); g.beginPath(); g.arc(0, 0.05 * s, 0.22 * s, 0.2, Math.PI - 0.2); g.stroke(); g.fillRect(-0.18 * s, -0.16 * s, 0.07 * s, 0.12 * s); g.fillRect(0.11 * s, -0.16 * s, 0.07 * s, 0.12 * s); break
    case 'spell': g.font = '700 ' + Math.round(s * 0.42) + 'px ' + MONO; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText('ASL', 0, 0); box(-0.48, -0.32, 0.96, 0.64); break
    case 'mouse': line([[-0.25, -0.45], [-0.25, 0.35], [-0.05, 0.15], [0.1, 0.45], [0.2, 0.4], [0.05, 0.1], [0.3, 0.1], [-0.25, -0.45]]); break
    case 'pad': rr(-0.5 * s, -0.22 * s, s, 0.44 * s, 0.2 * s); g.stroke(); line([[-0.3, 0], [-0.14, 0]]); line([[-0.22, -0.08], [-0.22, 0.08]]); g.beginPath(); g.arc(0.2 * s, -0.04 * s, 0.04 * s, 0, 7); g.fill(); g.beginPath(); g.arc(0.3 * s, 0.06 * s, 0.04 * s, 0, 7); g.fill(); break
  }
  g.restore()
}
const BONES = [[0, 1], [1, 2], [2, 3], [3, 4], [0, 5], [5, 6], [6, 7], [7, 8], [5, 9], [9, 10], [10, 11], [11, 12], [9, 13], [13, 14], [14, 15], [15, 16], [13, 17], [17, 18], [18, 19], [19, 20], [0, 17]]
const GLABEL = { point: 'point', pinch: 'pinch', palm: 'palm', fist: 'fist', two: 'two', three: 'three', thumbs: 'thumbs up', rock: 'rock', call: 'call', L: 'L', ily: 'I love you', ok: 'ok', four: 'four', pinky: 'pinky' }
/** Your hands as a hologram wireframe: all that shows unless the camera picture is turned on. */
function drawHands () {
  if (!R) return
  for (const h of R.hands) {
    const col = h.handed === 'right' ? C.cyan : C.pink
    g.strokeStyle = col; g.lineWidth = 2.5; g.lineCap = 'round'; g.shadowColor = col; g.shadowBlur = 14; g.globalAlpha = 0.85
    for (const [a, b] of BONES) { g.beginPath(); g.moveTo(h.P[a].x, h.P[a].y); g.lineTo(h.P[b].x, h.P[b].y); g.stroke() }
    g.globalAlpha = 1; g.fillStyle = '#fff'
    for (let i = 0; i < 21; i++) { g.beginPath(); g.arc(h.P[i].x, h.P[i].y, [4, 8, 12, 16, 20].includes(i) ? 4 : 2, 0, Math.PI * 2); g.fill() }
    g.shadowBlur = 0
    const gl = GLABEL[h.gesture]; if (gl) text(gl, h.P[0].x, h.P[0].y + 26, { align: 'center', mono: true, size: 11, color: col, spacing: 2, raw: true })
  }
}
function drawCursor () {
  if (!cursor) return
  const d = dwell.id ? Math.min(1, (performance.now() - dwell.t) / 1100) : 0
  const col = pinching ? C.lime : C.cyan
  g.strokeStyle = col; g.lineWidth = 2; g.shadowColor = col; g.shadowBlur = 12
  g.beginPath(); g.arc(cursor.x, cursor.y, pinching ? 9 : 13, 0, Math.PI * 2); g.stroke()
  for (const a of [0, 1, 2, 3]) { const ang = a * Math.PI / 2 + performance.now() / 900; g.beginPath(); g.moveTo(cursor.x + Math.cos(ang) * 17, cursor.y + Math.sin(ang) * 17); g.lineTo(cursor.x + Math.cos(ang) * 23, cursor.y + Math.sin(ang) * 23); g.stroke() }
  if (d > 0) { g.strokeStyle = C.lime; g.lineWidth = 3; g.beginPath(); g.arc(cursor.x, cursor.y, 28, -Math.PI / 2, -Math.PI / 2 + d * Math.PI * 2); g.stroke() }
  g.shadowBlur = 0
}
function button (id, x, y, w, h, draw, onPick) {
  const hover = !!cursor && cursor.x >= x && cursor.x <= x + w && cursor.y >= y && cursor.y <= y + h
  ui.push({ id, x, y, w, h, onPick, hover })
  draw(hover)
}
function pickLoop () {
  const hit = ui.find((u) => u.hover)
  if (!hit) { dwell = { id: null, t: 0 }; return }
  if (dwell.id !== hit.id) dwell = { id: hit.id, t: performance.now() }
  if (pinchEdge || (!usingPointer && performance.now() - dwell.t > 1100)) { dwell = { id: null, t: performance.now() + 600 }; hit.onPick(); chirp(880) }
}
function topBar (title) {
  hbutton('back', 20, 18, 130, 40, '< menu', () => go(null))
  text(title, W / 2, 46, { align: 'center', mono: true, size: 14, weight: 700, color: C.cyan, glow: C.cyan, raw: true })
}
function chirp (f = 660, len = 0.08, vol = 0.06) {
  try {
    const ctx = chirp.ctx || (chirp.ctx = new AudioContext())
    const o = ctx.createOscillator(); const gn = ctx.createGain(); o.type = 'sine'; o.frequency.value = f
    gn.gain.setValueAtTime(vol, ctx.currentTime); gn.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + len)
    o.connect(gn).connect(ctx.destination); o.start(); o.stop(ctx.currentTime + len + 0.02)
  } catch {}
}
const primary = () => (R && R.hands.length ? (R.hands.find((h) => h.handed === 'right') || R.hands[0]) : null)
function became (h, name, slot = 'p') { return !!h && h.gesture === name && lastGesture[slot] !== name }
const held = {}
function holdFor (key, on) { const now = performance.now(); if (!on) { delete held[key]; return 0 } if (!held[key]) held[key] = now; return (now - held[key]) / 1000 }
function bigButton (id, label, y) { const w = 260; hbutton(id, W / 2 - w / 2, y, w, 48, label, () => scene.enter(), C.lime) }
function overScreen (title, sub) {
  g.fillStyle = 'rgba(3,6,11,.72)'; g.fillRect(0, 0, W, H)
  text(title, W / 2, H * 0.44, { align: 'center', size: 54, weight: 800, glow: C.cyan })
  text(sub, W / 2, H * 0.5, { align: 'center', mono: true, size: 13, color: C.dim })
  bigButton('again', 'play again', H * 0.57)
}
const stat = (label, value, x, y, align = 'right') => { text(label, x, y, { align, mono: true, size: 11, color: C.dim, raw: true }); text(String(value), x, y + 34, { align, size: 32, weight: 800, glow: C.cyan, raw: true }) }

// ---- Games ----
const GAMES = []
const game = (o) => GAMES.push(o)

const LESSONS = [
  { title: 'Point to move', hint: 'Hold up one finger and touch the 4 targets.', kind: 'targets', gesture: null },
  { title: 'Pinch to pick', hint: 'Touch thumb and index together on each target.', kind: 'targets', gesture: 'pinch' },
  { title: 'Open palm', hint: 'Show your open hand and hold it for a second.', kind: 'hold', gesture: 'palm' },
  { title: 'Make a fist', hint: 'Close your hand and hold it.', kind: 'hold', gesture: 'fist' },
  { title: 'Two fingers', hint: 'Index and middle up, like a V. It turns blocks and types.', kind: 'hold', gesture: 'two' },
  { title: 'Thumbs up', hint: 'Thumb up, other fingers closed.', kind: 'hold', gesture: 'thumbs' },
  { title: 'Swipe left', hint: 'Sweep your hand quickly to the left.', kind: 'swipe', gesture: 'left' },
  { title: 'Swipe right', hint: 'Now sweep it quickly to the right.', kind: 'swipe', gesture: 'right' },
  { title: 'Punch', hint: 'Make a fist and punch toward the camera, twice.', kind: 'punch', need: 2 },
  { title: 'Scroll', hint: 'Two fingers up: move them down slowly, then up.', kind: 'scroll' }
]
game({
  id: 'school', name: 'Hand School', blurb: 'Learn every move', face: false,
  enter () { this.i = 0; this.targets = null; this.count = 0; this.t0 = performance.now(); this.done = false; this.ok = 0; this.sMin = this.sMax = null },
  update () {
    if (this.done) return
    const L = LESSONS[this.i]; const h = primary()
    if (this.ok) { if (performance.now() - this.ok > 900) { this.ok = 0; this.i++; this.targets = null; this.count = 0; this.sMin = this.sMax = null; if (this.i >= LESSONS.length) { this.done = true; this.time = (performance.now() - this.t0) / 1000; const b = store.get('schoolBest', 0); if (!b || this.time < b) store.set('schoolBest', this.time) } } return }
    const pass = () => { this.ok = performance.now(); chirp(1046, 0.16, 0.08) }
    if (L.kind === 'targets') {
      if (!this.targets) {
        this.targets = []
        for (let n = 0; this.targets.length < 4 && n < 400; n++) { const t = { x: W * (0.2 + 0.6 * Math.random()), y: H * (0.42 + 0.4 * Math.random()), hit: false, k: this.targets.length }; if (this.targets.every((o) => Math.hypot(o.x - t.x, o.y - t.y) > 150 * S)) this.targets.push(t) }
      }
      for (const t of this.targets) if (!t.hit && cursor && Math.hypot(cursor.x - t.x, cursor.y - t.y) < 46 * S && (!L.gesture || (h && h.gesture === L.gesture) || usingPointer)) { t.hit = true; chirp(700 + t.k * 120) }
      if (this.targets.every((t) => t.hit)) pass()
    } else if (L.kind === 'hold') {
      if (holdFor('school', h && h.gesture === L.gesture) > 0.9) { delete held.school; pass() }
    } else if (L.kind === 'swipe') {
      if (R && R.hands.some((x) => x.swipe === L.gesture)) pass()
    } else if (L.kind === 'punch') {
      if (R && R.hands.some((x) => x.punch)) { this.count++; chirp(300, 0.12, 0.1); if (this.count >= L.need) pass() }
    } else if (L.kind === 'scroll' && h && h.gesture === 'two') {
      const y = h.tip.y; this.sMin = Math.min(this.sMin == null ? y : this.sMin, y); this.sMax = Math.max(this.sMax == null ? y : this.sMax, y)
      if (this.sMax - this.sMin > H * 0.3 && y < this.sMax - H * 0.2) pass()
    }
  },
  draw () {
    topBar('hand school')
    if (this.done) {
      icon('school', W / 2, H * 0.32, 110 * S, C.lime)
      text('Every move learned', W / 2, H * 0.5, { align: 'center', size: 40, weight: 800, glow: C.cyan })
      text('time ' + this.time.toFixed(1) + 's   best ' + store.get('schoolBest', this.time).toFixed(1) + 's', W / 2, H * 0.56, { align: 'center', mono: true, size: 13, color: C.dim })
      bigButton('again', 'again', H * 0.63); return
    }
    const L = LESSONS[this.i]
    LESSONS.forEach((_, k) => { g.fillStyle = k < this.i ? C.lime : k === this.i ? C.cyan : 'rgba(34,211,197,.15)'; g.fillRect(W / 2 - LESSONS.length * 14 + k * 28, 70, 20, 4) })
    text('lesson ' + String(this.i + 1).padStart(2, '0') + ' / ' + LESSONS.length, W / 2, H * 0.17, { align: 'center', mono: true, size: 12, color: C.dim })
    text(L.title, W / 2, H * 0.25, { align: 'center', size: 40, weight: 800, glow: C.cyan })
    text(L.hint, W / 2, H * 0.3, { align: 'center', size: 17, color: C.dim })
    if (L.kind === 'targets' && this.targets) for (const t of this.targets) {
      const r = (36 + (t.hit ? 0 : Math.sin(performance.now() / 200 + t.k) * 4)) * S
      g.strokeStyle = t.hit ? C.lime : C.cyan; g.shadowColor = g.strokeStyle; g.shadowBlur = 14; g.lineWidth = 2
      g.beginPath(); g.arc(t.x, t.y, r, 0, Math.PI * 2); g.stroke(); g.beginPath(); g.arc(t.x, t.y, r * 0.45, 0, Math.PI * 2); g.stroke(); g.shadowBlur = 0
    }
    if (L.kind === 'hold') {
      const p = Math.min(1, held.school ? (performance.now() - held.school) / 900 : 0)
      g.strokeStyle = 'rgba(34,211,197,.15)'; g.lineWidth = 8; g.beginPath(); g.arc(W / 2, H * 0.6, 70 * S, 0, Math.PI * 2); g.stroke()
      g.strokeStyle = C.lime; g.shadowColor = C.lime; g.shadowBlur = 14; g.beginPath(); g.arc(W / 2, H * 0.6, 70 * S, -Math.PI / 2, -Math.PI / 2 + p * Math.PI * 2); g.stroke(); g.shadowBlur = 0
      text(L.gesture, W / 2, H * 0.6 + 5, { align: 'center', mono: true, size: 13 })
    }
    if (L.kind === 'punch') text(this.count + ' / ' + L.need, W / 2, H * 0.62, { align: 'center', size: 50, weight: 800, color: C.amber, glow: C.amber })
    if (this.ok) text('complete', W / 2, H * 0.84, { align: 'center', mono: true, size: 22, weight: 700, color: C.lime, glow: C.lime })
    const h = primary(); text(h ? 'detected: ' + (GLABEL[h.gesture] || 'nothing') : 'show me your hand', W / 2, H - 28, { align: 'center', mono: true, size: 11, color: C.dim })
  }
})

// Block Drop: the falling-block puzzle.
const PIECES = [[[1, 1, 1, 1]], [[1, 1], [1, 1]], [[0, 1, 0], [1, 1, 1]], [[1, 0, 0], [1, 1, 1]], [[0, 0, 1], [1, 1, 1]], [[0, 1, 1], [1, 1, 0]], [[1, 1, 0], [0, 1, 1]]]
const PCOL = [C.cyan, C.amber, C.pink, C.blue, '#FF8A3D', C.lime, C.red]
const rot = (m) => m[0].map((_, i) => m.map((r) => r[i]).reverse())
game({
  id: 'drop', name: 'Block Drop', blurb: 'Point to move, two fingers to turn', face: false,
  enter () { this.cols = 10; this.rows = 20; this.grid = Array.from({ length: 20 }, () => Array(10).fill(-1)); this.score = 0; this.lines = 0; this.over = false; this.fall = 0; this.next = Math.floor(Math.random() * 7); this.spawn() },
  spawn () { const k = this.next; this.next = Math.floor(Math.random() * 7); this.p = { m: PIECES[k], k, x: 3, y: 0 }; if (this.hits(this.p.m, this.p.x, this.p.y)) { this.over = true; const b = store.get('dropBest', 0); if (this.score > b) store.set('dropBest', this.score); chirp(200, 0.4, 0.1) } },
  hits (m, x, y) { return m.some((r, j) => r.some((v, i) => v && (x + i < 0 || x + i >= this.cols || y + j >= this.rows || (y + j >= 0 && this.grid[y + j][x + i] >= 0)))) },
  drop () { while (!this.hits(this.p.m, this.p.x, this.p.y + 1)) this.p.y++; this.lock() },
  turn () { const m = rot(this.p.m); for (const kick of [0, -1, 1, -2, 2]) if (!this.hits(m, this.p.x + kick, this.p.y)) { this.p.m = m; this.p.x += kick; chirp(700, 0.04); break } },
  lock () {
    this.p.m.forEach((r, j) => r.forEach((v, i) => { if (v && this.p.y + j >= 0) this.grid[this.p.y + j][this.p.x + i] = this.p.k }))
    const full = this.grid.map((r, j) => (r.every((c) => c >= 0) ? j : -1)).filter((j) => j >= 0)
    if (full.length) { for (const j of full) { this.grid.splice(j, 1); this.grid.unshift(Array(this.cols).fill(-1)) } this.lines += full.length; this.score += [0, 100, 300, 500, 800][full.length]; chirp(1200, 0.2, 0.08) } else chirp(320, 0.05)
    this.spawn()
  },
  geo () { const cell = Math.min((H - 140) / this.rows, (W * 0.5) / this.cols); return { cell, x0: W / 2 - (this.cols * cell) / 2, y0: 90 } },
  update (dt) {
    if (this.over) return
    const h = primary(); const { cell, x0 } = this.geo()
    if (cursor && (h || usingPointer) && !(h && h.gesture === 'fist')) {
      const want = Math.round((cursor.x - x0) / cell - this.p.m[0].length / 2)
      const step = Math.sign(want - this.p.x)
      if (step && !this.hits(this.p.m, this.p.x + step, this.p.y)) this.p.x += step
    }
    if (became(h, 'two') || became(h, 'L') || (usingPointer && pointerClick)) this.turn()
    if (R && R.hands.some((x) => x.punch)) { this.drop(); return }
    const level = Math.floor(this.lines / 8)
    const speed = (h && h.gesture === 'fist') ? 0.04 : Math.max(0.12, 0.7 - level * 0.06)
    this.fall += dt
    if (this.fall > speed) { this.fall = 0; if (this.hits(this.p.m, this.p.x, this.p.y + 1)) this.lock(); else this.p.y++ }
  },
  keys (e) { if (this.over) return; const m = { ArrowLeft: -1, ArrowRight: 1 }[e.key]; if (m && !this.hits(this.p.m, this.p.x + m, this.p.y)) this.p.x += m; if (e.key === 'ArrowUp') this.turn(); if (e.key === ' ') this.drop(); if (e.key === 'ArrowDown' && !this.hits(this.p.m, this.p.x, this.p.y + 1)) this.p.y++ },
  draw () {
    topBar('block drop')
    const { cell, x0, y0 } = this.geo()
    panel(x0 - 8, y0 - 8, this.cols * cell + 16, this.rows * cell + 16, false)
    g.strokeStyle = 'rgba(34,211,197,.06)'; g.lineWidth = 1
    for (let i = 0; i <= this.cols; i++) { g.beginPath(); g.moveTo(x0 + i * cell, y0); g.lineTo(x0 + i * cell, y0 + this.rows * cell); g.stroke() }
    for (let j = 0; j <= this.rows; j++) { g.beginPath(); g.moveTo(x0, y0 + j * cell); g.lineTo(x0 + this.cols * cell, y0 + j * cell); g.stroke() }
    const cellAt = (x, y, col, ghost) => {
      g.fillStyle = hexA(col, ghost ? 0 : 0.22); g.fillRect(x0 + x * cell + 1, y0 + y * cell + 1, cell - 2, cell - 2)
      g.strokeStyle = hexA(col, ghost ? 0.4 : 1); g.lineWidth = ghost ? 1 : 1.5; g.shadowColor = col; g.shadowBlur = ghost ? 0 : 8
      g.strokeRect(x0 + x * cell + 1.5, y0 + y * cell + 1.5, cell - 3, cell - 3); g.shadowBlur = 0
    }
    this.grid.forEach((r, j) => r.forEach((k, i) => { if (k >= 0) cellAt(i, j, PCOL[k]) }))
    if (!this.over) {
      let gy = this.p.y; while (!this.hits(this.p.m, this.p.x, gy + 1)) gy++
      this.p.m.forEach((r, j) => r.forEach((v, i) => { if (v) { cellAt(this.p.x + i, gy + j, PCOL[this.p.k], true); if (this.p.y + j >= 0) cellAt(this.p.x + i, this.p.y + j, PCOL[this.p.k]) } }))
    }
    const sx = x0 + this.cols * cell + 40
    stat('score', this.score, sx, y0 + 20, 'left'); stat('lines', this.lines, sx, y0 + 100, 'left'); stat('best', store.get('dropBest', 0), sx, y0 + 180, 'left')
    text('next', sx, y0 + 270, { mono: true, size: 11, color: C.dim, raw: true })
    PIECES[this.next].forEach((r, j) => r.forEach((v, i) => { if (v) { g.strokeStyle = PCOL[this.next]; g.lineWidth = 1.5; g.strokeRect(sx + i * 20, y0 + 285 + j * 20, 18, 18) } }))
    if (W > 700) for (const [i, l] of ['point: move', 'two fingers: turn', 'fist: faster', 'punch: drop'].entries()) text(l, x0 - 30, y0 + 30 + i * 26, { align: 'right', mono: true, size: 11, color: C.dim, spacing: 2, raw: true })
    if (this.over) overScreen('Score ' + this.score, 'lines ' + this.lines + '   best ' + store.get('dropBest', this.score))
  }
})

// Block Builder: stack blocks on an isometric grid (saved on the device).
const BCOL = [C.cyan, C.lime, C.amber, C.pink, C.blue, '#FFFFFF']
const emptyBuild = () => Array.from({ length: 10 }, () => Array.from({ length: 10 }, () => []))
game({
  id: 'build', name: 'Block Builder', blurb: 'Pinch to place, two fingers to remove', face: false,
  enter () { this.n = 10; this.h = store.get('build', null) || emptyBuild(); this.col = 0; this.sel = null },
  geo () { const t = Math.min(W / 26, H / 17); return { t, ox: W / 2, oy: H * 0.34 } },
  iso (i, j, k) { const { t, ox, oy } = this.geo(); return { x: ox + (i - j) * t, y: oy + (i + j) * t * 0.5 - k * t * 0.9 } },
  pick (pt) {
    const { t, ox, oy } = this.geo()
    const a = (pt.x - ox) / t; const b = (pt.y - oy) / (t * 0.5)
    const i = Math.floor((a + b) / 2); const j = Math.floor((b - a) / 2)
    return i >= 0 && j >= 0 && i < this.n && j < this.n ? { i, j } : null
  },
  update () {
    const h = primary()
    if (h && h.swipe) { this.col = (this.col + (h.swipe === 'right' ? 1 : BCOL.length - 1)) % BCOL.length; chirp(900, 0.04) }
    this.sel = cursor ? this.pick(cursor) : null
    if (!this.sel || ui.some((u) => u.hover)) return
    const stack = this.h[this.sel.i][this.sel.j]
    if (pinchEdge && stack.length < 8) { stack.push(this.col); chirp(500 + stack.length * 80, 0.05); store.set('build', this.h) }
    if (became(h, 'two') && stack.length) { stack.pop(); chirp(260, 0.05); store.set('build', this.h) }
  },
  draw () {
    topBar('block builder')
    const face = (pts, fill, stroke) => { g.beginPath(); pts.forEach((p, i) => (i ? g.lineTo(p.x, p.y) : g.moveTo(p.x, p.y))); g.closePath(); g.fillStyle = fill; g.fill(); g.strokeStyle = stroke; g.lineWidth = 1; g.stroke() }
    for (let i = 0; i < this.n; i++) for (let j = 0; j < this.n; j++) {
      const on = this.sel && this.sel.i === i && this.sel.j === j
      face([this.iso(i, j, 0), this.iso(i + 1, j, 0), this.iso(i + 1, j + 1, 0), this.iso(i, j + 1, 0)], on ? 'rgba(182,243,106,.18)' : 'rgba(34,211,197,.03)', on ? C.lime : 'rgba(34,211,197,.18)')
    }
    for (let s = 0; s < this.n * 2; s++) for (let i = 0; i < this.n; i++) {
      const j = s - i; if (j < 0 || j >= this.n) continue
      this.h[i][j].forEach((ci, k) => {
        const col = BCOL[ci]
        g.shadowColor = col; g.shadowBlur = 6
        face([this.iso(i, j + 1, k + 1), this.iso(i + 1, j + 1, k + 1), this.iso(i + 1, j + 1, k), this.iso(i, j + 1, k)], hexA(col, 0.18), col)
        face([this.iso(i + 1, j, k + 1), this.iso(i + 1, j + 1, k + 1), this.iso(i + 1, j + 1, k), this.iso(i + 1, j, k)], hexA(col, 0.28), col)
        face([this.iso(i, j, k + 1), this.iso(i + 1, j, k + 1), this.iso(i + 1, j + 1, k + 1), this.iso(i, j + 1, k + 1)], hexA(col, 0.42), col)
        g.shadowBlur = 0
      })
    }
    if (this.sel) {
      const k = this.h[this.sel.i][this.sel.j].length; const { i, j } = this.sel
      g.setLineDash([4, 4]); face([this.iso(i, j, k + 1), this.iso(i + 1, j, k + 1), this.iso(i + 1, j + 1, k + 1), this.iso(i, j + 1, k + 1)], 'rgba(0,0,0,0)', BCOL[this.col]); g.setLineDash([])
    }
    BCOL.forEach((col, k) => {
      const x = W - 60; const y = 110 + k * 56
      button('c' + k, x - 22, y - 22, 44, 44, () => { g.strokeStyle = col; g.lineWidth = k === this.col ? 3 : 1.5; g.shadowColor = col; g.shadowBlur = k === this.col ? 16 : 0; g.strokeRect(x - 16, y - 16, 32, 32); g.fillStyle = hexA(col, 0.3); g.fillRect(x - 16, y - 16, 32, 32); g.shadowBlur = 0 }, () => { this.col = k })
    })
    hbutton('clear', W - 130, 110 + BCOL.length * 56, 110, 40, 'clear', () => { this.h = emptyBuild(); store.set('build', this.h) }, C.red)
    for (const [i, l] of ['pinch or click: place', 'two fingers: remove', 'swipe: next colour'].entries()) text(l, 30, H - 80 + i * 22, { mono: true, size: 11, color: C.dim, spacing: 2, raw: true })
  }
})

game({
  id: 'pop', name: 'Orb Pop', blurb: 'Pop orbs with your fingertips', face: false,
  enter () { this.orbs = []; this.score = 0; this.combo = 0; this.end = performance.now() + 45000; this.next = 0; this.bursts = []; this.over = false },
  update (dt) {
    const now = performance.now()
    if (this.over) return
    if (now > this.end) { this.over = true; const b = store.get('popBest', 0); if (this.score > b) store.set('popBest', this.score); chirp(523, 0.3, 0.1); return }
    if (now > this.next) {
      const kind = Math.random() < 0.08 ? 'gold' : Math.random() < 0.12 ? 'bomb' : 'orb'
      this.orbs.push({ x: W * (0.1 + 0.8 * Math.random()), y: H + 40, r: (kind === 'gold' ? 24 : 30 + Math.random() * 14) * S, v: 90 + Math.random() * 120 + (45000 - (this.end - now)) / 300, kind, hue: Math.random() })
      this.next = now + 420 - Math.min(250, (45000 - (this.end - now)) / 200)
    }
    const tips = (R ? R.hands : []).flatMap((h) => [h.P[8], h.P[12]]).concat(usingPointer && cursor ? [cursor] : [])
    for (const o of this.orbs) {
      o.y -= o.v * dt; o.x += Math.sin(o.y / 60 + o.hue * 6) * 0.6
      if (!o.dead && tips.some((t) => Math.hypot(t.x - o.x, t.y - o.y) < o.r + 8)) {
        o.dead = true
        if (o.kind === 'bomb') { this.score = Math.max(0, this.score - 5); this.combo = 0; chirp(160, 0.25, 0.1) } else { this.combo++; this.score += (o.kind === 'gold' ? 5 : 1) * Math.min(4, 1 + Math.floor(this.combo / 5)); chirp(600 + Math.min(12, this.combo) * 40) }
        this.bursts.push({ x: o.x, y: o.y, t: now, col: o.kind === 'bomb' ? C.red : o.kind === 'gold' ? C.amber : C.cyan })
      }
      if (o.y < -60 && !o.dead && o.kind !== 'bomb') this.combo = 0
    }
    this.orbs = this.orbs.filter((o) => !o.dead && o.y > -80); this.bursts = this.bursts.filter((b) => now - b.t < 500)
  },
  draw () {
    topBar('orb pop')
    const now = performance.now()
    for (const o of this.orbs) {
      const col = o.kind === 'gold' ? C.amber : o.kind === 'bomb' ? C.red : [C.cyan, C.blue, C.lime, C.pink][Math.floor(o.hue * 4)]
      g.strokeStyle = col; g.shadowColor = col; g.shadowBlur = 16; g.lineWidth = 2
      g.beginPath(); g.arc(o.x, o.y, o.r, 0, Math.PI * 2); g.stroke(); g.fillStyle = hexA(col, 0.12); g.fill()
      g.beginPath(); g.arc(o.x, o.y, o.r * 0.55, 0, Math.PI * 2); g.stroke(); g.shadowBlur = 0
      if (o.kind === 'bomb') { g.beginPath(); g.moveTo(o.x - 8, o.y - 8); g.lineTo(o.x + 8, o.y + 8); g.moveTo(o.x + 8, o.y - 8); g.lineTo(o.x - 8, o.y + 8); g.stroke() }
    }
    for (const b of this.bursts) { const k = (now - b.t) / 500; g.strokeStyle = b.col; g.globalAlpha = 1 - k; g.lineWidth = 2; for (let a = 0; a < 8; a++) { const an = a * Math.PI / 4; g.beginPath(); g.moveTo(b.x + Math.cos(an) * 14 * (1 + k * 2), b.y + Math.sin(an) * 14 * (1 + k * 2)); g.lineTo(b.x + Math.cos(an) * 24 * (1 + k * 2), b.y + Math.sin(an) * 24 * (1 + k * 2)); g.stroke() } g.globalAlpha = 1 }
    stat('score', this.score, W - 40, 100)
    if (this.combo >= 5) text('combo x' + Math.min(4, 1 + Math.floor(this.combo / 5)), W - 40, 160, { align: 'right', mono: true, size: 12, color: C.amber, raw: true })
    stat('time', Math.max(0, Math.ceil((this.end - now) / 1000)), 40, 100, 'left')
    if (this.over) overScreen('Score ' + this.score, 'best ' + store.get('popBest', this.score))
  }
})

game({
  id: 'dash', name: 'Face Dash', blurb: 'Steer with your head', face: true,
  enter () { if (tracker) tracker.recenter(); this.x = 0.5; this.rocks = []; this.stars = []; this.score = 0; this.lives = 3; this.next = 0; this.speed = 260; this.over = false; this.shield = 0; this.hurt = 0 },
  update (dt) {
    if (this.over) return
    const now = performance.now(); const f = R && R.face
    if (f) this.x += ((0.5 + Math.max(-1, Math.min(1, f.yaw * 2.4)) * 0.45) - this.x) * Math.min(1, dt * 8)
    else if (usingPointer && cursor) this.x += ((cursor.x / W) - this.x) * Math.min(1, dt * 8)
    this.shield = f && f.jaw > 0.45 ? 1 : Math.max(0, this.shield - dt * 3)
    this.speed += dt * 6
    if (now > this.next) { this.next = now + Math.max(260, 900 - this.speed); if (Math.random() < 0.7) this.rocks.push({ x: Math.random(), y: -40, r: (20 + Math.random() * 18) * S }); else this.stars.push({ x: Math.random(), y: -30 }) }
    const px = W * 0.1 + this.x * W * 0.8; const py = H * 0.84
    for (const r of this.rocks) { r.y += this.speed * dt; if (!r.hit && Math.hypot(W * 0.1 + r.x * W * 0.8 - px, r.y - py) < r.r + 26 * S) { r.hit = true; if (this.shield > 0.5) { this.score += 2; chirp(900) } else { this.lives--; this.hurt = now; chirp(150, 0.3, 0.1); if (this.lives <= 0) { this.over = true; const b = store.get('dashBest', 0); if (this.score > b) store.set('dashBest', this.score) } } } }
    for (const s of this.stars) { s.y += this.speed * dt; if (!s.got && Math.hypot(W * 0.1 + s.x * W * 0.8 - px, s.y - py) < 40 * S) { s.got = true; this.score += 5; chirp(1200) } }
    this.rocks = this.rocks.filter((r) => r.y < H + 50 && !r.hit); this.stars = this.stars.filter((s) => s.y < H + 50 && !s.got)
    this.score += dt * 2
  },
  draw () {
    topBar('face dash')
    const px = W * 0.1 + this.x * W * 0.8; const py = H * 0.84
    for (const r of this.rocks) { const x = W * 0.1 + r.x * W * 0.8; g.strokeStyle = C.red; g.shadowColor = C.red; g.shadowBlur = 10; g.lineWidth = 2; g.beginPath(); for (let k = 0; k <= 6; k++) { const an = k * Math.PI / 3 + r.y / 80; const xx = x + Math.cos(an) * r.r; const yy = r.y + Math.sin(an) * r.r; k ? g.lineTo(xx, yy) : g.moveTo(xx, yy) } g.stroke(); g.shadowBlur = 0 }
    for (const s of this.stars) { const x = W * 0.1 + s.x * W * 0.8; g.strokeStyle = C.amber; g.shadowColor = C.amber; g.shadowBlur = 12; g.lineWidth = 2; g.beginPath(); g.moveTo(x, s.y - 14); g.lineTo(x + 10, s.y); g.lineTo(x, s.y + 14); g.lineTo(x - 10, s.y); g.closePath(); g.stroke(); g.shadowBlur = 0 }
    const col = performance.now() - this.hurt < 400 ? C.red : C.cyan
    g.strokeStyle = col; g.shadowColor = col; g.shadowBlur = 24; g.lineWidth = 3
    g.beginPath(); g.moveTo(px, py - 26 * S); g.lineTo(px + 22 * S, py + 18 * S); g.lineTo(px - 22 * S, py + 18 * S); g.closePath(); g.stroke(); g.shadowBlur = 0
    if (this.shield > 0.1) { g.strokeStyle = hexA(C.lime, this.shield); g.lineWidth = 2; g.beginPath(); g.arc(px, py, 42 * S, 0, Math.PI * 2); g.stroke() }
    stat('score', Math.floor(this.score), W - 40, 100); stat('lives', this.lives, 40, 100, 'left')
    text('turn your head to steer   open your mouth for a shield', W / 2, H - 20, { align: 'center', mono: true, size: 11, color: C.dim, spacing: 2 })
    if (!(R && R.face) && !usingPointer) text('look at the camera', W / 2, H / 2, { align: 'center', mono: true, size: 18, color: C.amber })
    if (this.over) overScreen('Score ' + Math.floor(this.score), 'best ' + Math.floor(store.get('dashBest', this.score)))
  }
})

const ROWS = ['QWERTYUIOP', 'ASDFGHJKL\'', 'ZXCVBNM,.?']
game({
  id: 'keys', name: 'Air Keys', blurb: 'One finger moves, two type', face: false,
  enter () { this.text = ''; this.flash = null },
  keyRects () {
    const kw = Math.min(86, (W - 80) / 10.6); const kh = kw * 0.9; const x0 = (W - kw * 10.5) / 2; const y0 = H * 0.42
    const out = []
    ROWS.forEach((row, r) => [...row].forEach((ch, c) => out.push({ ch, x: x0 + (c + r * 0.25) * kw * 1.04, y: y0 + r * kh * 1.08, w: kw, h: kh })))
    const y3 = y0 + 3 * kh * 1.08
    out.push({ ch: 'BKSP', x: x0, y: y3, w: kw * 1.6, h: kh, label: 'del' }, { ch: ' ', x: x0 + kw * 1.7, y: y3, w: kw * 5.9, h: kh, label: 'space' }, { ch: 'SEND', x: x0 + kw * 7.7, y: y3, w: kw * 2.75, h: kh, label: 'ask meshron' })
    return out
  },
  press (k) {
    this.flash = { k: k.ch, t: performance.now() }; chirp(1200, 0.04, 0.05)
    if (k.ch === 'BKSP') this.text = this.text.slice(0, -1)
    else if (k.ch === 'SEND') { if (this.text.trim()) { A.ask(this.text.trim()); this.sent = performance.now(); this.text = '' } } else if (this.text.length < 120) this.text += this.text.length ? k.ch.toLowerCase() : k.ch
  },
  update () {
    const h = primary(); if (!cursor) return
    if (became(h, 'two') || pinchEdge) { const k = this.keyRects().find((k) => cursor.x >= k.x && cursor.x <= k.x + k.w && cursor.y >= k.y && cursor.y <= k.y + k.h); if (k) this.press(k) }
  },
  draw () {
    topBar('air keys')
    panel(W * 0.1, H * 0.16, W * 0.8, 86, false)
    const caret = Math.floor(performance.now() / 500) % 2 ? '_' : ''
    text(this.text ? this.text + caret : 'type something to ask meshron' + caret, W * 0.12, H * 0.16 + 54, { size: this.text ? 30 : 13, mono: !this.text, max: W * 0.76, color: this.text ? C.text : C.dim })
    if (this.sent && performance.now() - this.sent < 2000) text('sent to meshron', W / 2, H * 0.16 + 116, { align: 'center', mono: true, size: 12, color: C.lime })
    for (const k of this.keyRects()) {
      const over = cursor && cursor.x >= k.x && cursor.x <= k.x + k.w && cursor.y >= k.y && cursor.y <= k.y + k.h
      const hit = this.flash && this.flash.k === k.ch && performance.now() - this.flash.t < 180
      panel(k.x, k.y, k.w, k.h, over || hit, hit ? C.lime : C.cyan)
      text(k.label || k.ch, k.x + k.w / 2, k.y + k.h / 2 + 7, { align: 'center', size: k.label ? 12 : 24, mono: !!k.label, weight: 600, color: hit ? C.lime : C.text, glow: over ? C.cyan : null, spacing: 2 })
    }
    text('one finger moves   two fingers or a pinch types', W / 2, H - 24, { align: 'center', mono: true, size: 11, color: C.dim, spacing: 2 })
    if (!ANDROID) hbutton('intoapps', W - 260, 18, 240, 40, 'type into apps', () => go(AIRTYPE), C.lime)
  }
})

// Air Keys over other apps (Windows): the keyboard docks at the bottom of the screen without
// taking focus, so each key goes into whatever app you are typing in.
const AIRTYPE = {
  id: 'airtype', name: 'Air Keys', dock: 'keyboard',
  enter () { this.flash = null },
  rows: ['1234567890', 'QWERTYUIOP', 'ASDFGHJKL-', 'ZXCVBNM,.?'],
  keyRects () {
    const kw = Math.min(70, (W - 30) / 10.6); const kh = Math.min(kw * 0.78, (H - 70) / 5.4); const x0 = (W - kw * 10.5) / 2; const y0 = 40
    const out = []
    this.rows.forEach((row, r) => [...row].forEach((ch, c) => out.push({ ch, x: x0 + (c + (r > 1 ? (r - 1) * 0.25 : 0)) * kw * 1.04, y: y0 + r * kh * 1.08, w: kw, h: kh })))
    const y4 = y0 + 4 * kh * 1.08
    out.push({ ch: 'DONE', x: x0, y: y4, w: kw * 1.6, h: kh, label: 'done' }, { ch: 'SHIFT', x: x0 + kw * 1.7, y: y4, w: kw * 1.5, h: kh, label: this.shift ? 'SHIFT' : 'shift' }, { ch: ' ', x: x0 + kw * 3.3, y: y4, w: kw * 3.9, h: kh, label: 'space' }, { ch: 'BKSP', x: x0 + kw * 7.3, y: y4, w: kw * 1.5, h: kh, label: 'del' }, { ch: 'ENTER', x: x0 + kw * 8.9, y: y4, w: kw * 1.6, h: kh, label: 'enter' })
    return out
  },
  press (k) {
    this.flash = { k: k.ch, t: performance.now() }; chirp(1200, 0.04, 0.05)
    if (k.ch === 'DONE') return go(null)
    if (k.ch === 'SHIFT') { this.shift = !this.shift; return }
    if (k.ch === 'BKSP') return tap(0x08)
    if (k.ch === 'ENTER') return tap(0x0D)
    A.gameInput({ type: 'text', text: this.shift ? k.ch : k.ch.toLowerCase() }); this.shift = false
  },
  update () {
    const h = primary(); if (!cursor) return
    if (became(h, 'two') || pinchEdge) { const k = this.keyRects().find((k) => cursor.x >= k.x && cursor.x <= k.x + k.w && cursor.y >= k.y && cursor.y <= k.y + k.h); if (k) this.press(k) }
  },
  draw () {
    text('air keys: typing into the app in front', 16, 24, { mono: true, size: 11, color: C.lime, raw: true, spacing: 2 })
    text('one finger moves   two fingers or pinch types', W - 16, 24, { align: 'right', mono: true, size: 10, color: C.dim, raw: true, spacing: 1 })
    for (const k of this.keyRects()) {
      const over = cursor && cursor.x >= k.x && cursor.x <= k.x + k.w && cursor.y >= k.y && cursor.y <= k.y + k.h
      const hit = this.flash && this.flash.k === k.ch && performance.now() - this.flash.t < 180
      panel(k.x, k.y, k.w, k.h, over || hit, hit ? C.lime : C.cyan)
      text(k.label || (this.shift ? k.ch : k.ch.toLowerCase()), k.x + k.w / 2, k.y + k.h / 2 + 6, { align: 'center', size: k.label ? 11 : 20, mono: !!k.label, weight: 600, color: hit ? C.lime : C.text, raw: true, spacing: 1 })
    }
  }
}

// Mood Mirror: a face (the person's face skin, or Buddy) copies their mood.
const moodOrb = document.createElement('canvas')
moodOrb.style.cssText = 'position:absolute;width:300px;height:300px;left:50%;top:44%;transform:translate(-50%,-50%);display:none;pointer-events:none'
document.body.appendChild(moodOrb)
const moodR = window.Reactor(moodOrb)
game({
  id: 'mood', name: 'Mood Mirror', blurb: 'A face that copies yours', face: true,
  enter () { moodOrb.style.display = 'block'; this.share = store.get('moodShare', false); this.last = null; this.since = 0; moodR.skin = 'buddy'; A.settings().then((s) => { const face = window.Reactor.SKINS.find((k) => k.id === s.skin && k.face); moodR.skin = face ? face.id : 'buddy' }) },
  exit () { moodOrb.style.display = 'none' },
  update () {
    const f = R && R.face
    if (!f) { moodR.mode = 'idle'; return }
    const m = { happy: 'happy', surprised: 'surprised', sad: 'sad', angry: 'sad' }[f.mood] || null
    moodR.mood = m; moodR.moodUntil = performance.now() + 400
    moodR.mode = f.jaw > 0.25 ? 'speaking' : 'idle'
    if (f.mood !== this.last) { this.last = f.mood; this.since = performance.now() }
    if (this.share && f.mood !== 'neutral' && performance.now() - this.since > 1200 && this.told !== f.mood) { this.told = f.mood; if (A.gameMood) A.gameMood(m || 'happy') }
  },
  draw () {
    topBar('mood mirror')
    const f = R && R.face
    g.strokeStyle = C.faint; g.lineWidth = 1; g.setLineDash([3, 6]); g.beginPath(); g.arc(W / 2, H * 0.44, 190, 0, Math.PI * 2); g.stroke(); g.setLineDash([])
    text(f ? { happy: 'happy', surprised: 'surprised', sad: 'sad', angry: 'angry', sleepy: 'sleepy', neutral: 'calm' }[f.mood] : 'look at the camera', W / 2, H * 0.74, { align: 'center', mono: true, size: 28, weight: 700, glow: C.cyan, spacing: 6 })
    if (f) {
      const bars = [['smile', f.smile, C.lime], ['mouth open', f.jaw, C.cyan], ['frown', f.frown, C.blue], ['brows down', f.browDown, C.red], ['eyes wide', f.wide, C.amber]]
      bars.forEach(([n, v, col], i) => { const x = W * 0.06; const y = H * 0.3 + i * 44; text(n, x, y, { mono: true, size: 11, color: C.dim, raw: true }); g.strokeStyle = 'rgba(34,211,197,.25)'; g.lineWidth = 1; g.strokeRect(x, y + 8, 220, 10); g.fillStyle = col; g.shadowColor = col; g.shadowBlur = 8; g.fillRect(x + 2, y + 10, Math.max(2, 216 * v), 6); g.shadowBlur = 0 })
      text('head ' + (f.yaw > 0.12 ? 'right' : f.yaw < -0.12 ? 'left' : 'centre') + (f.pitch > 0.12 ? ' / down' : f.pitch < -0.12 ? ' / up' : ''), W * 0.94, H * 0.3, { align: 'right', mono: true, size: 12, color: C.dim, raw: true })
    }
    hbutton('share', W / 2 - 170, H * 0.8, 340, 44, this.share ? 'meshron sees your mood: on' : 'let meshron see my mood', () => { this.share = !this.share; store.set('moodShare', this.share) }, this.share ? C.lime : C.cyan)
  }
})

const SIGNS = {
  A: 'Fist, thumb resting up the side of your index finger', B: 'Four fingers straight up together, thumb folded across the palm', D: 'Index up, other fingertips touch your thumb',
  F: 'Thumb and index touch in a circle, the other three fingers up', I: 'Only your little finger up', L: 'Thumb and index make an L',
  O: 'All fingertips curve to touch your thumb', S: 'Fist with your thumb across the front of your fingers', U: 'Index and middle up, pressed together',
  V: 'Index and middle up, spread apart', W: 'Index, middle and ring up, spread', Y: 'Thumb and little finger out', ILY: 'I love you: thumb, index and little finger out'
}
function spell (h) {
  if (!h) return null
  const P = h.P; const s = h.size; const dd = (a, b) => Math.hypot(P[a].x - P[b].x, P[a].y - P[b].y)
  if (dd(4, 8) < s * 0.42 && dd(4, 12) < s * 0.5 && dd(4, 16) < s * 0.6 && !h.ext[1] && !h.ext[2]) return 'O'
  switch (h.gesture) {
    case 'ily': return 'ILY'
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
  id: 'spell', name: 'Fingerspell', blurb: 'Sign language letters', face: false,
  enter () { this.mode = 'learn'; this.i = 0; this.word = ''; this.got = 0; this.cur = null; this.since = 0; this.used = false },
  update () {
    const h = primary(); const L = spell(h); const now = performance.now()
    if (L !== this.cur) { this.cur = L; this.since = now; this.used = false }
    if (this.mode === 'spell' && R && R.hands.some((x) => x.swipe === 'left')) this.word = this.word.slice(0, -1)
    if (!L || this.used) return
    if (this.mode === 'learn') { if (L === ORDER[this.i] && now - this.since > 800) { this.used = true; this.got = now; chirp(1046, 0.15, 0.08); setTimeout(() => { this.i = (this.i + 1) % ORDER.length }, 700) } } else if (now - this.since > 1000) { this.used = true; chirp(880, 0.06); this.word += L === 'ILY' ? ' I love you ' : L }
  },
  draw () {
    topBar('fingerspell')
    hbutton('mode', W - 300, 18, 280, 40, this.mode === 'learn' ? 'switch: spell words' : 'switch: learn letters', () => { this.mode = this.mode === 'learn' ? 'spell' : 'learn'; this.word = '' })
    const h = primary(); const L = spell(h)
    if (this.mode === 'learn') {
      const T = ORDER[this.i]
      text('sign this', W / 2, H * 0.18, { align: 'center', mono: true, size: 12, color: C.dim })
      text(T, W / 2, H * 0.38, { align: 'center', size: T.length > 1 ? 90 : 140, weight: 800, color: this.got && performance.now() - this.got < 700 ? C.lime : C.text, glow: C.cyan })
      text(SIGNS[T], W / 2, H * 0.45, { align: 'center', size: 18, color: C.dim, max: W * 0.8 })
    } else {
      text('hold each letter for a second   swipe left to delete', W / 2, H * 0.18, { align: 'center', mono: true, size: 11, color: C.dim, spacing: 2 })
      panel(W * 0.15, H * 0.23, W * 0.7, 90, false)
      text(this.word || '...', W / 2, H * 0.23 + 60, { align: 'center', size: 44, weight: 700, max: W * 0.66, glow: C.cyan })
      if (this.word.trim()) hbutton('send', W / 2 - 130, H * 0.23 + 110, 260, 44, 'ask meshron', () => { A.ask(this.word.trim()); this.word = '' }, C.lime)
    }
    text(L ? 'detected: ' + L : h ? 'not a letter I know yet' : 'show me your hand', W / 2, H * 0.62, { align: 'center', mono: true, size: 16, weight: 600, color: L ? C.cyan : C.dim })
    text('learning toy: static letters only (a b d f i l o s u v w y, i love you). full sign language uses motion, both hands and the face.', W / 2, H - 22, { align: 'center', mono: true, size: 10, color: C.dim, max: W - 40, spacing: 1, raw: true })
  }
})

// Control modes (Windows only): they run over other apps from a small card in the corner.
let inputSize = null
async function screenPx () { if (!inputSize) inputSize = await A.gameScreen(); return inputSize }
const VK = { W: 0x57, SPACE: 0x20, E: 0x45, ALT: 0x12, LEFT: 0x25, RIGHT: 0x27 }
const tap = (vk, ms = 60) => { A.gameInput({ type: 'key', vk, down: true }); setTimeout(() => A.gameInput({ type: 'key', vk, down: false }), ms) }
const click = (b = 'l') => { A.gameInput({ type: 'button', b, down: true }); setTimeout(() => A.gameInput({ type: 'button', b, down: false }), 70) }
if (!ANDROID) {
  game({
    id: 'mouse', name: 'Hand Control', blurb: 'Mouse, click and scroll by hand', face: false, compact: true,
    async enter () { this.paused = false; this.down = false; this.lastPos = null; this.lastScroll = null; await screenPx() },
    exit () { if (this.down) A.gameInput({ type: 'button', b: 'l', down: false }); A.gameRelease() },
    update () {
      const h = primary()
      if (!h) { if (this.down) { this.down = false; A.gameInput({ type: 'button', b: 'l', down: false }) } return }
      if (holdFor('pausefist', h.gesture === 'fist') > 0.9) { delete held.pausefist; this.paused = !this.paused; chirp(this.paused ? 440 : 880) }
      if (this.paused || !inputSize) return
      const nx = Math.max(0, Math.min(1, (h.P[8].x / W - 0.2) / 0.6)); const ny = Math.max(0, Math.min(1, (h.P[8].y / H - 0.15) / 0.6))
      const x = Math.round(nx * inputSize.w); const y = Math.round(ny * inputSize.h)
      if (h.gesture === 'two') { if (this.lastScroll != null) { const dy = h.tip.y - this.lastScroll; if (Math.abs(dy) > 2) A.gameInput({ type: 'wheel', delta: -dy * 6 }) } this.lastScroll = h.tip.y } else {
        this.lastScroll = null
        if (!this.lastPos || Math.abs(x - this.lastPos.x) + Math.abs(y - this.lastPos.y) > 1) { A.gameInput({ type: 'pos', x, y }); this.lastPos = { x, y } }
      }
      if (h.pinch && !this.down) { this.down = true; A.gameInput({ type: 'button', b: 'l', down: true }) } else if (!h.pinch && this.down) { this.down = false; A.gameInput({ type: 'button', b: 'l', down: false }) }
      if (h.swipe === 'left' || h.swipe === 'right') { A.gameInput({ type: 'key', vk: VK.ALT, down: true }); tap(h.swipe === 'left' ? VK.LEFT : VK.RIGHT); setTimeout(() => A.gameInput({ type: 'key', vk: VK.ALT, down: false }), 120) }
    },
    legend: () => ['point: move', 'pinch: click / drag', 'two: scroll', 'swipe: back / fwd', 'hold fist: pause'],
    status () { return this.paused ? 'paused' : 'active' }
  })
  game({
    id: 'pad', name: 'Game Pad', blurb: 'Play PC games with your body', face: true, compact: true,
    enter () { if (tracker) tracker.recenter(); this.walk = false; this.paused = false },
    exit () { A.gameRelease() },
    update (dt) {
      const h = primary(); const f = R && R.face
      if (holdFor('padpause', R && R.hands.length === 2 && R.hands.every((x) => x.gesture === 'palm')) > 1) { delete held.padpause; this.paused = !this.paused; A.gameRelease(); this.walk = false; chirp(this.paused ? 440 : 880) }
      if (this.paused) return
      if (f) {
        const dz = 0.07; const sp = 1300
        const ax = Math.abs(f.yaw) > dz ? Math.sign(f.yaw) * (Math.abs(f.yaw) - dz) : 0
        const ay = Math.abs(f.pitch) > dz ? Math.sign(f.pitch) * (Math.abs(f.pitch) - dz) : 0
        if (ax || ay) A.gameInput({ type: 'move', dx: ax * sp * dt * 2, dy: ay * sp * dt * 1.5 })
      }
      if (!h) { if (this.walk) { this.walk = false; A.gameInput({ type: 'key', vk: VK.W, down: false }) } return }
      const want = h.gesture === 'palm'
      if (want !== this.walk) { this.walk = want; A.gameInput({ type: 'key', vk: VK.W, down: want }) }
      if (R.hands.some((x) => x.punch)) click('l')
      if (became(h, 'pinch')) click('r')
      if (became(h, 'two')) tap(VK.SPACE, 120)
      if (became(h, 'thumbs')) tap(VK.E)
      if (h.swipe) A.gameInput({ type: 'wheel', delta: h.swipe === 'left' ? 120 : -120 })
    },
    legend: () => ['head: look', 'punch: hit', 'palm: walk', 'two: jump', 'pinch: use', 'thumbs: inventory', 'swipe: hotbar', 'both palms: pause'],
    status () { return this.paused ? 'paused' : this.walk ? 'walking' : 'ready' }
  })
}

// ---- Menu ----
function menu () {
  text('meshron // game mode', W / 2, 56, { align: 'center', mono: true, size: 15, weight: 700, color: C.cyan, glow: C.cyan, spacing: 5, raw: true })
  text(ANDROID ? 'point and pinch, hold still, or tap' : 'point and pinch, hold still, or click', W / 2, 82, { align: 'center', mono: true, size: 11, color: C.dim, spacing: 2, raw: true })
  const portrait = H > W
  const cols = portrait ? 2 : 5; const rows = Math.ceil(GAMES.length / cols); const gap = 14
  const tw = Math.min(230, (W - 60 - gap * (cols - 1)) / cols); const th = Math.min(tw * 0.92, (H - 200 - gap * (rows - 1)) / rows)
  const x0 = (W - (tw * cols + gap * (cols - 1))) / 2; const y0 = 110
  GAMES.forEach((gm, i) => {
    const x = x0 + (i % cols) * (tw + gap); const y = y0 + Math.floor(i / cols) * (th + gap)
    button(gm.id, x, y, tw, th, (hv) => {
      panel(x, y, tw, th, hv)
      icon(gm.id, x + tw / 2, y + th * 0.38, Math.min(tw, th) * 0.4, hv ? '#fff' : C.cyan)
      text(gm.name, x + tw / 2, y + th * 0.74, { align: 'center', size: 17, weight: 700, glow: hv ? C.cyan : null })
      text(gm.blurb, x + tw / 2, y + th * 0.88, { align: 'center', size: 11, color: C.dim, max: tw - 16 })
      if (gm.face) text('face', x + tw - 10, y + 18, { align: 'right', mono: true, size: 9, color: C.amber, raw: true, spacing: 2 })
      if (gm.compact) text('pc', x + 10, y + 18, { mono: true, size: 9, color: C.lime, raw: true, spacing: 2 })
    }, () => go(gm))
  })
  // The camera picture stays off unless the person turns it on.
  const by = Math.min(H - 56, y0 + rows * (th + gap) + 6)
  hbutton('cam', W / 2 - 170, by, 340, 40, showCamera ? 'camera picture: on' : 'camera picture: off', () => { showCamera = !showCamera; store.set('showCamera', showCamera) }, showCamera ? C.amber : C.cyan)
  hbutton('close', W - 140, 18, 120, 40, 'close', () => A.gameClose(), C.red)
  text(R ? 'tracking: ' + (R.hands.length ? R.hands.length + ' hand' + (R.hands.length > 1 ? 's' : '') : 'no hands') : 'starting', 24, H - 20, { mono: true, size: 10, color: C.dim, raw: true, spacing: 2 })
}

async function go (gm) {
  if (scene && scene.exit) scene.exit()
  if (scene && (scene.compact || scene.dock)) { await A.gameCompact(false); document.body.classList.remove('small') }
  scene = gm
  for (const k of Object.keys(held)) delete held[k]
  if (tracker) tracker.useFace(!gm || !!gm.face)
  if (gm) {
    if (gm.compact) {
      // Explain first: this mode works over your other apps, so the window becomes a small card in the corner.
      intro = { name: gm.name, until: performance.now() + 3200 }
      await new Promise((r) => setTimeout(r, 3200)); intro = null
      if (scene !== gm) return
      document.body.classList.add('small'); await A.gameCompact(true)
    } else if (gm.dock) await A.gameCompact(gm.dock)
    await gm.enter()
  }
}

// ---- The loop ----
let lastT = performance.now()
let pointer = null; let pointerClick = false; let usingPointer = false
let intro = null
function loop (now) {
  const dt = Math.min(0.05, (now - lastT) / 1000); lastT = now
  const t = now / 1000
  size()
  background(t)
  if (tracker) { const r = tracker.frame(W, H); if (r) R = r }
  const h = primary()
  usingPointer = false
  if (h) {
    cursor = cursor ? { x: cursor.x + (h.tip.x - cursor.x) * 0.45, y: cursor.y + (h.tip.y - cursor.y) * 0.45 } : { x: h.tip.x, y: h.tip.y }
    pinchEdge = h.pinch && !pinching; pinching = h.pinch
  } else if (pointer) { cursor = { x: pointer.x, y: pointer.y }; pinchEdge = pointerClick; pinching = false; usingPointer = true } else { cursor = null; pinchEdge = false; pinching = false }
  ui = []
  const small = scene && scene.compact
  if (intro) introCard()
  else if (small) compactCard()
  else if (scene) { scene.update(dt); scene.draw() } else menu()
  drawHands()
  if (!small) { pickLoop(); drawCursor() }
  overlay(t)
  pointerClick = false
  lastGesture.p = h ? h.gesture : null
  requestAnimationFrame(loop)
}
function introCard () {
  const left = Math.max(0, Math.ceil((intro.until - performance.now()) / 1000))
  panel(W / 2 - 330, H / 2 - 120, 660, 240, true)
  text(intro.name, W / 2, H / 2 - 60, { align: 'center', mono: true, size: 18, weight: 700, color: C.cyan, glow: C.cyan, raw: true })
  text('This works over your other apps, so this window becomes', W / 2, H / 2 - 16, { align: 'center', size: 18, raw: true })
  text('a small card in the bottom-right corner, staying on top.', W / 2, H / 2 + 12, { align: 'center', size: 18, raw: true })
  text('Ctrl+Alt+G or a click on the card brings you back here.', W / 2, H / 2 + 50, { align: 'center', mono: true, size: 12, color: C.dim, raw: true, spacing: 1 })
  text('starting in ' + left, W / 2, H / 2 + 92, { align: 'center', mono: true, size: 13, color: C.lime, raw: true })
}
function compactCard () {
  scene.update(1 / 60)
  text(scene.name, 12, 26, { mono: true, size: 12, weight: 700, color: C.cyan, raw: true })
  text(scene.status ? scene.status() : '', W - 12, 26, { align: 'right', mono: true, size: 11, color: C.lime, raw: true })
  const lg = scene.legend ? scene.legend() : []
  lg.forEach((l, i) => text(l, 10 + (i % 2) * (W / 2), H - 12 - (Math.ceil(lg.length / 2) - 1 - Math.floor(i / 2)) * 17, { mono: true, size: 9.5, color: C.text, max: W / 2 - 14, raw: true, spacing: 1 }))
  text('ctrl+alt+g stops   click for menu', W / 2, 46, { align: 'center', mono: true, size: 9, color: C.dim, raw: true, spacing: 1 })
}
cv.addEventListener('pointermove', (e) => { pointer = { x: e.clientX, y: e.clientY } })
cv.addEventListener('pointerleave', () => { pointer = null })
cv.addEventListener('pointerdown', (e) => { pointer = { x: e.clientX, y: e.clientY }; if (scene && scene.compact) go(null); else pointerClick = true })
window.addEventListener('keydown', (e) => { if (e.key === 'Escape') { if (scene) go(null); else A.gameClose() } else if (scene && scene.keys) scene.keys(e) })
A.on((ev, d) => {
  if (ev === 'game-stop') go(null)
  if (ev === 'game-progress') { $('bar').hidden = false; $('barfill').style.width = Math.round(d * 100) + '%' }
})
window.MeshronGame = { go: (id) => go(GAMES.find((x) => x.id === id) || null) }

// ---- Start: models (asked once), camera, tracking ----
async function start () {
  const m = await A.gameModels()
  if (!m.ready) {
    $('gate').hidden = false
    window.Reactor($('gateorb')).skin = 'reactor'
    $('dlnote').textContent = 'First time only: download the hand and face tracking models (' + m.mb + ' MB).'
    await new Promise((resolve) => {
      $('later').onclick = () => A.gameClose()
      $('dl').onclick = async () => {
        $('gatebtns').hidden = true
        try { await A.gameDownload(); resolve() } catch (e) { $('dlnote').textContent = 'The download did not work: ' + (e.message || e) + '. Check the internet and try again.'; $('gatebtns').hidden = false }
      }
    })
    $('gate').hidden = true
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ video: { width: 1280, height: 720, facingMode: 'user' }, audio: false })
    video.srcObject = stream; await video.play()
  } catch {
    $('gate').hidden = false; $('gatebtns').hidden = false; $('bar').hidden = true
    $('dlnote').textContent = ANDROID ? 'I can\'t use the camera. Allow it for Meshron Assistant in Settings.' : 'I can\'t use the camera. Check Windows Settings > Privacy & security > Camera, and that no other app is using it.'
    $('dl').textContent = 'Try again'; $('dl').onclick = () => location.reload(); return
  }
  const [hand, face] = await Promise.all([A.gameModel('hand'), A.gameModel('face')])
  tracker = await createTracker(video, { hand, face })
  tracker.useFace(false)
}
requestAnimationFrame(loop)
start()
