// The orb (same design family as the Android app), in skins. The classic "arc reactor" is a glowing
// core in rings of light: idle it breathes, listening it swells with your voice, thinking it spins
// fast, speaking it pulses. Other skins redraw the same moods: a nebula, embers, an aurora, a clean
// mono ring, and faces (Buddy, Cosmo, Pixel, Kitty) that blink, look around, talk and show feelings.
//
// window.Reactor(canvas) -> r: set r.mode ('idle' | 'listening' | 'thinking' | 'speaking'),
// r.level (0-1, the voice while listening), r.skin (or window.ReactorSkin for every orb on the
// page) and r.feel(mood, ms) for a moment's expression ('happy' | 'sad' | 'surprised' | 'love').
;(function () {
  'use strict'
  const C = { cyan: '#22D3C5', blue: '#6E7BFF', lime: '#B6F36A' }
  const rgba = (hex, a) => { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})` }
  const TAU = Math.PI * 2

  /** The skins people can pick: id, name, whether it has a face (faces sit at the top of the screen). */
  const SKINS = [
    { id: 'reactor', name: 'Reactor', face: false, colors: [C.cyan, C.blue, C.lime] },
    { id: 'nebula', name: 'Nebula', face: false, colors: ['#B57BFF', '#FF6BD6', '#6E7BFF'] },
    { id: 'ember', name: 'Ember', face: false, colors: ['#FF8A3D', '#FFB020', '#FF4D5E'] },
    { id: 'aurora', name: 'Aurora', face: false, colors: ['#3DFFB0', '#22D3C5', '#8E7BFF'] },
    { id: 'mono', name: 'Mono', face: false, colors: ['#F2F5FA', '#9BA3B4', '#F2F5FA'] },
    { id: 'gold', name: 'Gold', face: false, colors: ['#FFD86B', '#FFB020', '#FFF1C4'] },
    { id: 'buddy', name: 'Buddy', face: true, colors: [C.cyan, C.blue, C.lime] },
    { id: 'cosmo', name: 'Cosmo', face: true, colors: ['#8E7BFF', '#22D3C5', '#FF6BD6'] },
    { id: 'pixel', name: 'Pixel', face: true, colors: ['#3DFF8A', '#22D3C5', '#B6F36A'] },
    { id: 'kitty', name: 'Kitty', face: true, colors: ['#FF9BD2', '#B57BFF', '#FFD1E8'] }
  ]
  const byId = (id) => SKINS.find((s) => s.id === id) || SKINS[0]

  function Reactor (canvas) {
    const g = canvas.getContext('2d')
    const r = { mode: 'idle', level: 0, shown: 0, skin: null, mood: null, moodUntil: 0 }
    r.feel = (mood, ms = 2200) => { r.mood = mood; r.moodUntil = performance.now() + ms }
    const t0 = performance.now()
    // Faces: where the eyes look, when they blink, how open the mouth is.
    const face = { lx: 0, ly: 0, tx: 0, ty: 0, nextLook: 0, blink: 0, nextBlink: 1.5, mouth: 0, talk: 0 }
    const stars = Array.from({ length: 70 }, () => ({ a: Math.random() * TAU, d: Math.random(), s: Math.random() * 1.4 + 0.3, z: Math.random() }))
    const sparks = Array.from({ length: 26 }, () => ({ a: Math.random() * TAU, d: Math.random(), v: 0.2 + Math.random() * 0.6, life: Math.random() }))

    function frame (now) {
      const dpr = window.devicePixelRatio || 1
      const w = canvas.clientWidth; const h = canvas.clientHeight
      if (!w || !h) return requestAnimationFrame(frame)
      if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr) }
      g.setTransform(dpr, 0, 0, dpr, 0, 0)
      g.clearRect(0, 0, w, h)
      const t = (now - t0) / 1000
      const skin = byId(r.skin || window.ReactorSkin)
      if (r.mood && now > r.moodUntil) r.mood = null
      r.shown += ((r.mode === 'listening' ? r.level : 0) - r.shown) * 0.25
      const S = { g, t, w, h, cx: w / 2, cy: h / 2, R: Math.min(w, h) / 2, mode: r.mode, shown: r.shown, mood: r.mood, skin, face, stars, sparks, dt: 1 / 60 }
      S.breathe = (Math.sin(t * (r.mode === 'speaking' ? 14 : 2.4)) + 1) / 2
      ;(DRAW[skin.id] || DRAW.reactor)(S)
      requestAnimationFrame(frame)
    }
    requestAnimationFrame(frame)
    return r
  }

  // ---- Ring skins ----
  function ringSkin (S, opt) {
    const { g, t, cx, cy, R, mode, shown, breathe, skin } = S
    const [c1, c2, c3] = skin.colors
    const fast = mode === 'thinking'
    const accent = mode === 'thinking' ? c2 : mode === 'speaking' ? c3 : c1
    const swell = 1 + shown * 0.18 + breathe * (mode === 'speaking' ? 0.06 : 0.025)
    const glow = g.createRadialGradient(cx, cy, 0, cx, cy, R * 1.02 * swell)
    glow.addColorStop(0, rgba(accent, 0.34)); glow.addColorStop(1, rgba(accent, 0))
    g.fillStyle = glow; g.beginPath(); g.arc(cx, cy, R * 1.02 * swell, 0, TAU); g.fill()
    if (opt.ticks) {
      const spinA = -t * (fast ? 150 : 26) * Math.PI / 180
      for (let i = 0; i < 72; i++) {
        const a = spinA + i * 5 * Math.PI / 180; const long = i % 6 === 0
        const r1 = R * 0.94; const r2 = R * (long ? 0.86 : 0.9)
        g.strokeStyle = rgba(accent, long ? 0.7 : 0.25); g.lineWidth = long ? 1.6 : 1
        g.beginPath(); g.moveTo(cx + r1 * Math.cos(a), cy + r1 * Math.sin(a)); g.lineTo(cx + r2 * Math.cos(a), cy + r2 * Math.sin(a)); g.stroke()
      }
    }
    const spin = t * (fast ? 225 : 40) * Math.PI / 180
    const arcR = R * 0.76 * swell
    g.lineCap = 'round'; g.lineWidth = Math.max(1.5, R * (opt.thin ? 0.02 : 0.035))
    ;[[0, c1], [95, c2], [190, c1], [280, c3]].forEach(([s, col]) => {
      g.strokeStyle = rgba(col, 0.9); g.beginPath(); g.arc(cx, cy, arcR, spin + s * Math.PI / 180, spin + (s + (opt.thin ? 80 : 62)) * Math.PI / 180); g.stroke()
    })
    const inR = R * 0.56 * swell; const counter = -t * (fast ? 230 : 39) * Math.PI / 180
    g.strokeStyle = rgba(accent, 0.55); g.lineWidth = Math.max(1, R * 0.012)
    g.beginPath(); g.arc(cx, cy, inR, counter + 0.35, counter + 0.35 + 280 * Math.PI / 180); g.stroke()
    if (opt.coils) {
      g.strokeStyle = rgba(accent, 0.45); g.lineWidth = Math.max(1.2, R * 0.03)
      for (let i = 0; i < 10; i++) {
        const a = counter + i * 36 * Math.PI / 180; const r1 = inR * 0.72; const r2 = inR * 0.92
        g.beginPath(); g.moveTo(cx + r1 * Math.cos(a), cy + r1 * Math.sin(a)); g.lineTo(cx + r2 * Math.cos(a), cy + r2 * Math.sin(a)); g.stroke()
      }
    }
    if (opt.extra) opt.extra(S, accent, swell)
    const coreR = R * (0.26 + breathe * 0.02 + shown * 0.1)
    const core = g.createRadialGradient(cx, cy, 0, cx, cy, coreR * 1.6)
    core.addColorStop(0, '#ffffff'); core.addColorStop(0.45, rgba(accent, 0.95)); core.addColorStop(1, rgba(accent, 0))
    g.fillStyle = core; g.beginPath(); g.arc(cx, cy, coreR * 1.6, 0, TAU); g.fill()
    g.fillStyle = '#FFFFFF'; g.beginPath(); g.arc(cx, cy, coreR * 0.55, 0, TAU); g.fill()
  }

  const DRAW = {
    reactor: (S) => ringSkin(S, { ticks: true, coils: true }),
    mono: (S) => ringSkin(S, { ticks: true, thin: true }),
    gold: (S) => ringSkin(S, {
      ticks: true,
      coils: true,
      extra ({ g, t, cx, cy, R }, accent) {
        // A slow hexagon frame.
        g.strokeStyle = rgba('#FFD86B', 0.5); g.lineWidth = Math.max(1, R * 0.015)
        g.beginPath()
        for (let i = 0; i <= 6; i++) { const a = t * 0.3 + i * TAU / 6; const x = cx + R * 0.98 * Math.cos(a); const y = cy + R * 0.98 * Math.sin(a); i ? g.lineTo(x, y) : g.moveTo(x, y) }
        g.stroke()
      }
    }),
    nebula (S) {
      // A swirl of glowing dust around a bright heart; it spins faster while thinking and flares when speaking.
      const { g, t, cx, cy, R, mode, shown, breathe, skin, stars } = S
      const [c1, c2, c3] = skin.colors
      const speed = mode === 'thinking' ? 2.4 : mode === 'speaking' ? 1.2 : 0.4
      const swell = 1 + shown * 0.22 + breathe * (mode === 'speaking' ? 0.08 : 0.03)
      const bg = g.createRadialGradient(cx, cy, 0, cx, cy, R * swell)
      bg.addColorStop(0, rgba(c2, 0.45)); bg.addColorStop(0.5, rgba(c1, 0.22)); bg.addColorStop(1, rgba(c3, 0))
      g.fillStyle = bg; g.beginPath(); g.arc(cx, cy, R * swell, 0, TAU); g.fill()
      for (const s of stars) {
        const a = s.a + t * speed * (0.4 + (1 - s.d)) ; const d = R * (0.2 + s.d * 0.75) * swell
        const x = cx + Math.cos(a) * d; const y = cy + Math.sin(a) * d * 0.82
        g.fillStyle = rgba(s.z > 0.66 ? c1 : s.z > 0.33 ? c2 : '#FFFFFF', 0.4 + 0.6 * s.z)
        g.beginPath(); g.arc(x, y, s.s * (R / 40), 0, TAU); g.fill()
      }
      // Two spiral arms.
      g.lineCap = 'round'
      for (let arm = 0; arm < 2; arm++) {
        g.strokeStyle = rgba(arm ? c1 : c2, 0.55); g.lineWidth = Math.max(1, R * 0.03)
        g.beginPath()
        for (let i = 0; i < 40; i++) { const k = i / 40; const a = t * speed + arm * Math.PI + k * 4; const d = R * (0.1 + k * 0.7) * swell; const x = cx + Math.cos(a) * d; const y = cy + Math.sin(a) * d * 0.82; i ? g.lineTo(x, y) : g.moveTo(x, y) }
        g.stroke()
      }
      const core = g.createRadialGradient(cx, cy, 0, cx, cy, R * 0.32 * swell)
      core.addColorStop(0, '#FFFFFF'); core.addColorStop(0.4, rgba(c2, 0.9)); core.addColorStop(1, rgba(c2, 0))
      g.fillStyle = core; g.beginPath(); g.arc(cx, cy, R * 0.32 * swell, 0, TAU); g.fill()
    },
    ember (S) {
      // A fiery core with sparks rising off it.
      const { g, t, cx, cy, R, mode, shown, breathe, skin, sparks } = S
      const [c1, c2, c3] = skin.colors
      const heat = mode === 'thinking' ? 1.8 : mode === 'speaking' ? 1.4 : 0.8
      const swell = 1 + shown * 0.2 + breathe * (mode === 'speaking' ? 0.07 : 0.03)
      const glow = g.createRadialGradient(cx, cy, 0, cx, cy, R * swell)
      glow.addColorStop(0, rgba(c2, 0.55)); glow.addColorStop(0.55, rgba(c1, 0.22)); glow.addColorStop(1, rgba(c3, 0))
      g.fillStyle = glow; g.beginPath(); g.arc(cx, cy, R * swell, 0, TAU); g.fill()
      // Flame petals.
      for (let i = 0; i < 9; i++) {
        const a = i * TAU / 9 + Math.sin(t * 2 + i) * 0.12 + t * 0.3
        const len = R * (0.48 + 0.12 * Math.sin(t * 6 * heat + i * 1.7)) * swell
        g.fillStyle = rgba(i % 2 ? c1 : c3, 0.55)
        g.beginPath(); g.moveTo(cx + Math.cos(a - 0.35) * R * 0.2, cy + Math.sin(a - 0.35) * R * 0.2)
        g.quadraticCurveTo(cx + Math.cos(a) * len * 1.2, cy + Math.sin(a) * len * 1.2, cx + Math.cos(a + 0.35) * R * 0.2, cy + Math.sin(a + 0.35) * R * 0.2); g.fill()
      }
      for (const s of sparks) {
        s.life += S.dt * s.v * heat; if (s.life > 1) { s.life = 0; s.a = Math.random() * TAU }
        const d = R * (0.25 + s.life * 0.7); const x = cx + Math.cos(s.a) * d; const y = cy + Math.sin(s.a) * d - s.life * R * 0.2
        g.fillStyle = rgba(c2, 1 - s.life); g.beginPath(); g.arc(x, y, Math.max(0.6, R * 0.025 * (1 - s.life)), 0, TAU); g.fill()
      }
      const core = g.createRadialGradient(cx, cy, 0, cx, cy, R * 0.34 * swell)
      core.addColorStop(0, '#FFF6E0'); core.addColorStop(0.5, rgba(c2, 0.95)); core.addColorStop(1, rgba(c1, 0))
      g.fillStyle = core; g.beginPath(); g.arc(cx, cy, R * 0.34 * swell, 0, TAU); g.fill()
    },
    aurora (S) {
      // Ribbons of light flowing around a calm core.
      const { g, t, cx, cy, R, mode, shown, breathe, skin } = S
      const [c1, c2, c3] = skin.colors
      const speed = mode === 'thinking' ? 3 : mode === 'speaking' ? 1.8 : 0.7
      const swell = 1 + shown * 0.2 + breathe * (mode === 'speaking' ? 0.07 : 0.03)
      const glow = g.createRadialGradient(cx, cy, 0, cx, cy, R * swell)
      glow.addColorStop(0, rgba(c2, 0.3)); glow.addColorStop(1, rgba(c2, 0))
      g.fillStyle = glow; g.beginPath(); g.arc(cx, cy, R * swell, 0, TAU); g.fill()
      ;[c1, c2, c3].forEach((col, k) => {
        g.strokeStyle = rgba(col, 0.7); g.lineWidth = Math.max(1.5, R * 0.05); g.lineCap = 'round'
        g.beginPath()
        for (let i = 0; i <= 60; i++) {
          const a = (i / 60) * TAU; const wob = Math.sin(a * 3 + t * speed + k * 2) * 0.09 + Math.sin(a * 5 - t * speed * 0.7) * 0.04
          const d = R * (0.5 + k * 0.09 + wob) * swell; const x = cx + Math.cos(a) * d; const y = cy + Math.sin(a) * d
          i ? g.lineTo(x, y) : g.moveTo(x, y)
        }
        g.stroke()
      })
      const core = g.createRadialGradient(cx, cy, 0, cx, cy, R * 0.36 * swell)
      core.addColorStop(0, '#FFFFFF'); core.addColorStop(0.45, rgba(c1, 0.9)); core.addColorStop(1, rgba(c1, 0))
      g.fillStyle = core; g.beginPath(); g.arc(cx, cy, R * 0.36 * swell, 0, TAU); g.fill()
    },
    buddy: (S) => faceSkin(S, 'buddy'),
    cosmo: (S) => faceSkin(S, 'cosmo'),
    pixel: (S) => faceSkin(S, 'pixel'),
    kitty: (S) => faceSkin(S, 'kitty')
  }

  // ---- Faces ----
  /** What the face feels right now: a moment's mood wins, else the mode. */
  function expression (S) {
    if (S.mood) return S.mood
    return { listening: 'listening', thinking: 'thinking', speaking: 'talking' }[S.mode] || 'calm'
  }

  function faceSkin (S, kind) {
    const { g, t, cx, cy, R, mode, shown, breathe, skin, face, stars } = S
    const [c1, c2, c3] = skin.colors
    const ex = expression(S)
    const bob = Math.sin(t * 2) * R * 0.02 + (kind === 'kitty' ? R * 0.1 : 0)
    const swell = 1 + shown * 0.12 + (mode === 'speaking' ? breathe * 0.03 : 0)
    const fr = R * (kind === 'kitty' ? 0.66 : 0.8) * swell // face radius (Kitty leaves room for her ears)

    // Eyes wander; thinking looks up and to the side; listening looks at you.
    if (t > face.nextLook) { face.tx = (Math.random() - 0.5) * 0.5; face.ty = (Math.random() - 0.5) * 0.3; face.nextLook = t + 1.2 + Math.random() * 2.5 }
    let tx = face.tx; let ty = face.ty
    if (ex === 'thinking') { tx = 0.35 * Math.cos(t * 1.5); ty = -0.35 }
    if (ex === 'listening' || ex === 'surprised') { tx = 0; ty = 0 }
    face.lx += (tx - face.lx) * 0.12; face.ly += (ty - face.ly) * 0.12
    // Blinks (none while surprised).
    if (t > face.nextBlink) { face.blink = 1; face.nextBlink = t + 2 + Math.random() * 3.5 }
    face.blink = Math.max(0, face.blink - 0.16)
    const lid = ex === 'surprised' ? 0 : face.blink > 0 ? Math.sin(face.blink * Math.PI) : 0
    // The mouth moves with speech (syllable-like), and with your voice while listening.
    if (ex === 'talking') { face.talk += 0.35 + Math.random() * 0.25; face.mouth = 0.25 + 0.75 * Math.abs(Math.sin(face.talk) * Math.sin(face.talk * 0.37 + 1)) } else face.mouth += ((ex === 'listening' ? shown * 0.6 : 0) - face.mouth) * 0.3

    g.save(); g.translate(cx, cy + bob)

    // Head.
    const glow = g.createRadialGradient(0, 0, 0, 0, 0, R * 1.05)
    glow.addColorStop(0, rgba(c1, 0.35)); glow.addColorStop(1, rgba(c1, 0))
    g.fillStyle = glow; g.beginPath(); g.arc(0, 0, R * 1.05, 0, TAU); g.fill()
    if (kind === 'kitty') {
      // Ears that perk up when listening.
      const perk = ex === 'listening' || ex === 'surprised' ? 0.12 : 0
      for (const s of [-1, 1]) {
        g.fillStyle = '#1A1220'; g.strokeStyle = rgba(c1, 0.9); g.lineWidth = Math.max(1, R * 0.04)
        g.beginPath(); g.moveTo(s * fr * 0.85, -fr * 0.35); g.lineTo(s * fr * (0.7 - perk * 0.4), -fr * (1.15 + perk)); g.lineTo(s * fr * 0.25, -fr * 0.78); g.closePath(); g.fill(); g.stroke()
        g.fillStyle = rgba(c1, 0.6); g.beginPath(); g.moveTo(s * fr * 0.72, -fr * 0.5); g.lineTo(s * fr * (0.66 - perk * 0.4), -fr * (0.98 + perk)); g.lineTo(s * fr * 0.38, -fr * 0.74); g.closePath(); g.fill()
      }
    }
    if (kind === 'pixel') {
      // A rounded screen.
      const s = fr * 1.7
      g.fillStyle = '#050A08'; g.strokeStyle = rgba(c1, 0.55); g.lineWidth = Math.max(1, R * 0.04)
      round(g, -s / 2, -s / 2.3, s, s / 1.15, s * 0.22); g.fill(); g.stroke()
    } else {
      const head = g.createRadialGradient(-fr * 0.3, -fr * 0.35, fr * 0.1, 0, 0, fr)
      if (kind === 'cosmo') { head.addColorStop(0, '#2A1F5C'); head.addColorStop(1, '#07061A') } else if (kind === 'kitty') { head.addColorStop(0, '#3A2440'); head.addColorStop(1, '#140C18') } else { head.addColorStop(0, '#16324A'); head.addColorStop(1, '#060B14') }
      g.fillStyle = head; g.beginPath(); g.arc(0, 0, fr, 0, TAU); g.fill()
      g.strokeStyle = rgba(c1, 0.6); g.lineWidth = Math.max(1, R * 0.035); g.stroke()
    }
    if (kind === 'cosmo') {
      // Space inside the head: stars drift, faster while it speaks or thinks.
      g.save(); g.beginPath(); g.arc(0, 0, fr * 0.96, 0, TAU); g.clip()
      const v = mode === 'speaking' ? 0.9 + face.mouth : mode === 'thinking' ? 1.6 : 0.25
      for (const s of stars) {
        s.a += 0.004 * v * (0.5 + s.z)
        const d = fr * s.d; const x = Math.cos(s.a) * d; const y = Math.sin(s.a) * d
        g.fillStyle = rgba(s.z > 0.7 ? c3 : s.z > 0.4 ? c2 : '#FFFFFF', 0.35 + 0.65 * s.z)
        g.beginPath(); g.arc(x, y, s.s * (R / 50), 0, TAU); g.fill()
      }
      // A ringed planet passing behind the eyes.
      const px = Math.cos(t * 0.25) * fr * 0.55; const py = fr * 0.45
      g.fillStyle = rgba(c2, 0.5); g.beginPath(); g.arc(px, py, fr * 0.12, 0, TAU); g.fill()
      g.strokeStyle = rgba(c3, 0.5); g.lineWidth = Math.max(1, R * 0.02); g.beginPath(); g.ellipse(px, py, fr * 0.22, fr * 0.06, -0.3, 0, TAU); g.stroke()
      g.restore()
    }

    // Eyes.
    const eyeY = -fr * 0.12; const gap = fr * 0.38
    const ew = fr * (kind === 'pixel' ? 0.26 : 0.2); let eh = fr * (ex === 'surprised' ? 0.36 : ex === 'listening' ? 0.32 : 0.28)
    eh *= 1 - lid * 0.92
    const look = { x: face.lx * fr * 0.18, y: face.ly * fr * 0.18 }
    for (const s of [-1, 1]) {
      const x = s * gap + look.x; const y = eyeY + look.y
      g.fillStyle = kind === 'pixel' ? c1 : '#F4FBFF'
      g.shadowColor = c1; g.shadowBlur = R * 0.25
      if (ex === 'happy' || ex === 'love') {
        // Happy: upturned arcs (love: little hearts).
        g.shadowBlur = R * 0.2
        if (ex === 'love') heart(g, x, y, ew * 1.1, c3)
        else { g.strokeStyle = kind === 'pixel' ? c1 : '#F4FBFF'; g.lineWidth = Math.max(1.5, ew * 0.45); g.lineCap = 'round'; g.beginPath(); g.arc(x, y + ew * 0.35, ew * 0.8, Math.PI * 1.15, Math.PI * 1.85); g.stroke() }
      } else if (kind === 'pixel') {
        const px = ew / 3
        for (let i = 0; i < 3; i++) for (let j = 0; j < Math.max(1, Math.round(eh / px)); j++) g.fillRect(x - ew / 2 + i * px + 0.5, y - eh / 2 + j * px + 0.5, px - 1, px - 1)
      } else {
        round(g, x - ew / 2, y - eh / 2, ew, Math.max(1.5, eh), ew / 2); g.fill()
        if (ex === 'sad') { g.shadowBlur = 0; g.fillStyle = kind === 'cosmo' ? '#07061A' : '#060B14'; g.beginPath(); g.moveTo(x - ew, y - eh / 2 - 1); g.lineTo(x + ew, y - eh / 2 - 1); g.lineTo(x + s * ew, y - eh * 0.05); g.closePath(); g.fill() }
        // A glint.
        if (eh > ew * 0.6) { g.shadowBlur = 0; g.fillStyle = 'rgba(255,255,255,.9)'; g.beginPath(); g.arc(x - ew * 0.18, y - eh * 0.22, ew * 0.14, 0, TAU); g.fill() }
      }
      g.shadowBlur = 0
    }
    // Cheeks when happy or in love (and always a little on Kitty).
    if (ex === 'happy' || ex === 'love' || kind === 'kitty') {
      g.fillStyle = rgba('#FF6B9A', ex === 'happy' || ex === 'love' ? 0.4 : 0.22)
      for (const s of [-1, 1]) { g.beginPath(); g.ellipse(s * gap * 1.35, eyeY + fr * 0.3, fr * 0.13, fr * 0.08, 0, 0, TAU); g.fill() }
    }

    // Mouth.
    const my = fr * 0.38; const mw = fr * (kind === 'pixel' ? 0.42 : 0.34)
    g.strokeStyle = kind === 'pixel' ? c1 : '#F4FBFF'; g.fillStyle = kind === 'pixel' ? c1 : '#F4FBFF'; g.lineCap = 'round'; g.lineWidth = Math.max(1.5, fr * 0.07)
    if (kind === 'pixel') {
      const px = mw / 6; const open = ex === 'talking' || ex === 'listening' ? face.mouth : 0
      for (let i = 0; i < 6; i++) {
        const curve = ex === 'sad' ? -Math.abs(i - 2.5) * 0.45 : ex === 'happy' || ex === 'calm' || ex === 'love' ? Math.abs(i - 2.5) * 0.45 : 0
        const hgt = Math.max(1, Math.round(1 + open * 3 * (1 - Math.abs(i - 2.5) / 3)))
        for (let j = 0; j < hgt; j++) g.fillRect(-mw / 2 + i * px + 0.5, my - curve * px + j * px - px, px - 1, px - 1)
      }
    } else if (ex === 'talking' || (ex === 'listening' && face.mouth > 0.05)) {
      const oh = fr * 0.05 + face.mouth * fr * 0.24
      g.fillStyle = '#1A0E14'; round(g, -mw * 0.45, my - oh / 2, mw * 0.9, oh, Math.min(oh, mw) / 2); g.fill()
      g.strokeStyle = '#F4FBFF'; g.lineWidth = Math.max(1, fr * 0.04); g.stroke()
      if (face.mouth > 0.45) { g.fillStyle = '#FF7A9A'; g.beginPath(); g.ellipse(0, my + oh * 0.25, mw * 0.22, oh * 0.18, 0, 0, TAU); g.fill() }
    } else if (ex === 'surprised') {
      g.beginPath(); g.ellipse(0, my, fr * 0.08, fr * 0.11, 0, 0, TAU); g.stroke()
    } else if (ex === 'thinking') {
      g.beginPath(); g.moveTo(-mw * 0.3, my + fr * 0.02); g.lineTo(mw * 0.35, my - fr * 0.03); g.stroke()
    } else if (ex === 'sad') {
      g.beginPath(); g.arc(0, my + mw * 0.55, mw * 0.5, Math.PI * 1.2, Math.PI * 1.8); g.stroke()
    } else {
      const k = ex === 'happy' || ex === 'love' ? 1.25 : 1
      if (kind === 'kitty') {
        // A little "w" mouth.
        g.lineWidth = Math.max(1.2, fr * 0.05)
        g.beginPath(); g.arc(-mw * 0.2, my - fr * 0.02, mw * 0.2 * k, 0.1, Math.PI - 0.1); g.arc(mw * 0.2, my - fr * 0.02, mw * 0.2 * k, 0.1, Math.PI - 0.1); g.stroke()
      } else { g.beginPath(); g.arc(0, my - mw * 0.45, mw * 0.6 * k, Math.PI * 0.25, Math.PI * 0.75); g.stroke() }
    }
    if (kind === 'kitty') {
      // Whiskers.
      g.strokeStyle = rgba('#FFD1E8', 0.55); g.lineWidth = Math.max(1, fr * 0.02)
      for (const s of [-1, 1]) for (const d of [-0.06, 0.06]) { g.beginPath(); g.moveTo(s * fr * 0.48, my - fr * 0.08 + d * fr); g.lineTo(s * fr * 0.95, my - fr * 0.14 + d * fr * 2.2); g.stroke() }
    }
    // Thinking: three dots orbiting above.
    if (ex === 'thinking') for (let i = 0; i < 3; i++) { const a = t * 3 + i * 0.6; g.fillStyle = rgba(c2, 0.5 + 0.5 * Math.sin(a * 2)); g.beginPath(); g.arc(fr * (0.55 + i * 0.22), -fr * (0.95 + 0.06 * Math.sin(a)), fr * 0.06, 0, TAU); g.fill() }
    g.restore()
  }

  function round (g, x, y, w, h, r) { r = Math.min(r, w / 2, h / 2); g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath() }
  function heart (g, x, y, s, col) { g.fillStyle = col; g.beginPath(); g.moveTo(x, y + s * 0.5); g.bezierCurveTo(x - s, y - s * 0.1, x - s * 0.5, y - s * 0.8, x, y - s * 0.25); g.bezierCurveTo(x + s * 0.5, y - s * 0.8, x + s, y - s * 0.1, x, y + s * 0.5); g.fill() }

  window.Reactor = Reactor
  window.Reactor.SKINS = SKINS
})()
