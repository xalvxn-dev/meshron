// The "arc reactor" (same design as the Android app): a glowing core in rings of light.
// Idle it breathes; listening it swells with your voice; thinking it spins fast in blue; speaking it pulses.
;(function () {
  'use strict'
  const C = { cyan: '#22D3C5', blue: '#6E7BFF', lime: '#B6F36A' }
  const rgba = (hex, a) => { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})` }

  function Reactor (canvas) {
    const g = canvas.getContext('2d')
    const r = { mode: 'idle', level: 0, shown: 0 }
    let t0 = performance.now()
    function frame (now) {
      const dpr = window.devicePixelRatio || 1
      const w = canvas.clientWidth; const h = canvas.clientHeight
      if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr) }
      g.setTransform(dpr, 0, 0, dpr, 0, 0)
      g.clearRect(0, 0, w, h)
      const t = (now - t0) / 1000
      const fast = r.mode === 'thinking'
      const accent = r.mode === 'thinking' ? C.blue : r.mode === 'speaking' ? C.lime : C.cyan
      r.shown += ((r.mode === 'listening' ? r.level : 0) - r.shown) * 0.25
      const breathe = (Math.sin(t * (r.mode === 'speaking' ? 14 : 2.4)) + 1) / 2
      const cx = w / 2; const cy = h / 2; const R = Math.min(w, h) / 2
      const swell = 1 + r.shown * 0.18 + breathe * (r.mode === 'speaking' ? 0.06 : 0.025)
      // glow
      const glow = g.createRadialGradient(cx, cy, 0, cx, cy, R * 1.02 * swell)
      glow.addColorStop(0, rgba(accent, 0.34)); glow.addColorStop(1, rgba(accent, 0))
      g.fillStyle = glow; g.beginPath(); g.arc(cx, cy, R * 1.02 * swell, 0, Math.PI * 2); g.fill()
      // tick ring
      const spinA = -t * (fast ? 150 : 26) * Math.PI / 180
      for (let i = 0; i < 72; i++) {
        const a = spinA + i * 5 * Math.PI / 180; const long = i % 6 === 0
        const r1 = R * 0.94; const r2 = R * (long ? 0.86 : 0.9)
        g.strokeStyle = rgba(accent, long ? 0.7 : 0.25); g.lineWidth = long ? 1.6 : 1
        g.beginPath(); g.moveTo(cx + r1 * Math.cos(a), cy + r1 * Math.sin(a)); g.lineTo(cx + r2 * Math.cos(a), cy + r2 * Math.sin(a)); g.stroke()
      }
      // broken arcs
      const spin = t * (fast ? 225 : 40) * Math.PI / 180
      const arcR = R * 0.76 * swell
      g.lineCap = 'round'; g.lineWidth = Math.max(1.5, R * 0.035)
      ;[[0, C.cyan], [95, C.blue], [190, C.cyan], [280, C.lime]].forEach(([s, col]) => {
        g.strokeStyle = rgba(col, 0.9); g.beginPath(); g.arc(cx, cy, arcR, spin + s * Math.PI / 180, spin + (s + 62) * Math.PI / 180); g.stroke()
      })
      // inner ring and coils
      const inR = R * 0.56 * swell; const counter = -t * (fast ? 230 : 39) * Math.PI / 180
      g.strokeStyle = rgba(accent, 0.55); g.lineWidth = Math.max(1, R * 0.012)
      g.beginPath(); g.arc(cx, cy, inR, counter + 0.35, counter + 0.35 + 280 * Math.PI / 180); g.stroke()
      g.strokeStyle = rgba(accent, 0.45); g.lineWidth = Math.max(1.2, R * 0.03)
      for (let i = 0; i < 10; i++) {
        const a = counter + i * 36 * Math.PI / 180; const r1 = inR * 0.72; const r2 = inR * 0.92
        g.beginPath(); g.moveTo(cx + r1 * Math.cos(a), cy + r1 * Math.sin(a)); g.lineTo(cx + r2 * Math.cos(a), cy + r2 * Math.sin(a)); g.stroke()
      }
      // core
      const coreR = R * (0.26 + breathe * 0.02 + r.shown * 0.1)
      const core = g.createRadialGradient(cx, cy, 0, cx, cy, coreR * 1.6)
      core.addColorStop(0, '#ffffff'); core.addColorStop(0.45, rgba(accent, 0.95)); core.addColorStop(1, rgba(accent, 0))
      g.fillStyle = core; g.beginPath(); g.arc(cx, cy, coreR * 1.6, 0, Math.PI * 2); g.fill()
      g.fillStyle = '#F2FFFD'; g.beginPath(); g.arc(cx, cy, coreR * 0.55, 0, Math.PI * 2); g.fill()
      requestAnimationFrame(frame)
    }
    requestAnimationFrame(frame)
    return r
  }
  window.Reactor = Reactor
})()
