// The teaching layer (Windows): a see-through layer over the whole screen that never takes clicks.
//   'point': circles one thing with an arrow curving in and a note (point_at).
//   'demo':  plays a lesson step by step with Meshron's own hologram mouse pointer: it glides to
//            each thing and points, circles, highlights, clicks (ripples), drags and drops (a ghost
//            copy follows a dashed path), or types (the text appears beside it), with a note.
;(function () {
  'use strict'
  const cv = document.getElementById('c'); const g = cv.getContext('2d')
  const C = { cyan: '#22D3C5', lime: '#B6F36A', amber: '#FFB020', text: '#E6F7FF' }
  let target = null // single point_at
  let steps = null // demo
  let t0 = 0
  const ease = (k) => 1 - Math.pow(1 - Math.max(0, Math.min(1, k)), 3)
  const inout = (k) => { k = Math.max(0, Math.min(1, k)); return k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2 }

  function frame (now) {
    const dpr = window.devicePixelRatio || 1
    const w = innerWidth; const h = innerHeight
    if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr) }
    g.setTransform(dpr, 0, 0, dpr, 0, 0)
    g.clearRect(0, 0, w, h)
    const t = (now - t0) / 1000
    if (target) drawPoint(w, h, t)
    if (steps) drawDemo(w, h, now - t0)
    requestAnimationFrame(frame)
  }

  // ---- One thing, circled (point_at) ----
  function drawPoint (w, h, t) {
    const { x, y, width, height, note } = target
    const cx = x + width / 2; const cy = y + height / 2
    const rx = Math.max(34, width / 2 + 18); const ry = Math.max(26, height / 2 + 14)
    g.globalAlpha = t > 7.2 ? Math.max(0, 1 - (t - 7.2) / 0.6) : 1
    const k = ease(t / 0.5)
    g.fillStyle = 'rgba(2, 6, 14,' + (0.38 * k) + ')'
    g.beginPath(); g.rect(0, 0, w, h); g.ellipse(cx, cy, rx + 10, ry + 10, 0, 0, Math.PI * 2, true); g.fill('evenodd')
    ring(cx, cy, rx, ry, ease((t - 0.2) / 0.7), t)
    const fromLeft = cx > w / 2; const fromTop = cy > h / 2
    const sx = cx + (fromLeft ? -1 : 1) * (rx + 170); const sy = cy + (fromTop ? -1 : 1) * (ry + 110)
    arrow(sx, sy, cx + (fromLeft ? -1 : 1) * rx * 0.78, cy + (fromTop ? -1 : 1) * ry * 0.78, ease((t - 0.7) / 0.6), fromLeft, fromTop)
    if (note) noteBox(note, sx, sy + (fromTop ? -46 : 10), ease((t - 1.1) / 0.4), w)
    g.globalAlpha = 1
  }
  function ring (cx, cy, rx, ry, draw1, t, col = C.cyan) {
    const pulse = 1 + 0.04 * Math.sin(t * 5)
    g.lineCap = 'round'; g.strokeStyle = col; g.shadowColor = col; g.shadowBlur = 18; g.lineWidth = 4
    g.beginPath()
    for (let i = 0; i <= 80 * draw1; i++) {
      const a = -Math.PI * 0.6 + (i / 80) * Math.PI * 2.15; const wob = 1 + 0.035 * Math.sin(a * 3 + 1)
      const px = cx + Math.cos(a) * rx * wob * pulse; const py = cy + Math.sin(a) * ry * wob * pulse
      if (i === 0) g.moveTo(px, py); else g.lineTo(px, py)
    }
    g.stroke(); g.shadowBlur = 0
  }
  function arrow (sx, sy, ex, ey, a1, fromLeft, fromTop) {
    if (a1 <= 0) return
    const mx = (sx + ex) / 2 + (fromTop ? -40 : 40); const my = (sy + ey) / 2 + (fromLeft ? 40 : -40)
    g.strokeStyle = C.lime; g.shadowColor = C.lime; g.shadowBlur = 12; g.lineWidth = 4
    g.beginPath(); g.moveTo(sx, sy)
    for (let i = 1; i <= 40 * a1; i++) { const u = i / 40; g.lineTo((1 - u) * (1 - u) * sx + 2 * (1 - u) * u * mx + u * u * ex, (1 - u) * (1 - u) * sy + 2 * (1 - u) * u * my + u * u * ey) }
    g.stroke()
    if (a1 >= 1) { const dir = Math.atan2(ey - my, ex - mx); g.fillStyle = C.lime; g.beginPath(); g.moveTo(ex, ey); g.lineTo(ex - 18 * Math.cos(dir - 0.45), ey - 18 * Math.sin(dir - 0.45)); g.lineTo(ex - 18 * Math.cos(dir + 0.45), ey - 18 * Math.sin(dir + 0.45)); g.closePath(); g.fill() }
    g.shadowBlur = 0
  }
  function noteBox (note, x, y, alpha, w, col = C.cyan) {
    if (alpha <= 0 || !note) return
    g.save(); g.globalAlpha *= alpha
    g.font = '600 17px "Segoe UI Variable Text", "Segoe UI", sans-serif'
    const tw = Math.min(420, g.measureText(note).width) + 28
    const bx = Math.max(12, Math.min(w - tw - 12, x - tw / 2))
    g.fillStyle = 'rgba(8, 14, 26, .92)'; g.strokeStyle = col; g.lineWidth = 1.2
    g.beginPath(); g.roundRect(bx, y, tw, 36, 18); g.fill(); g.stroke()
    g.fillStyle = C.text; g.textBaseline = 'middle'; g.textAlign = 'left'; g.fillText(note, bx + 14, y + 18, 420)
    g.restore()
  }

  // ---- A lesson, played with a hologram mouse pointer ----
  let from = null // where the pointer was
  function cursorAt (x, y, press, alpha = 1) {
    // Meshron's own pointer: a glowing arrow, a little smaller when pressed.
    const s = press ? 0.86 : 1
    g.save(); g.globalAlpha *= alpha; g.translate(x, y); g.scale(s * 1.35, s * 1.35)
    g.beginPath(); g.moveTo(0, 0); g.lineTo(0, 22); g.lineTo(6, 16.5); g.lineTo(10, 25); g.lineTo(14, 23); g.lineTo(10, 14.5); g.lineTo(17.5, 14.5); g.closePath()
    g.fillStyle = 'rgba(34, 211, 197, .28)'; g.shadowColor = C.cyan; g.shadowBlur = 16; g.fill()
    g.strokeStyle = '#E8FFFC'; g.lineWidth = 1.6; g.stroke()
    g.restore()
  }
  function drawDemo (w, h, ms) {
    const centre = (r) => ({ x: r.x + r.width / 2, y: r.y + r.height / 2 })
    // Which step is playing.
    let i = steps.findIndex((s) => ms < s.at + s.len)
    if (i < 0) i = steps.length - 1
    const s = steps[i]; const local = ms - s.at
    const prevEnd = i > 0 && steps[i - 1].rect ? (steps[i - 1].action === 'drag' && steps[i - 1].to ? centre(steps[i - 1].to) : centre(steps[i - 1].rect)) : (from || { x: w * 0.5, y: h * 0.6 })
    // Step counter, top centre.
    g.font = '700 12px "Cascadia Mono", Consolas, monospace'; g.textAlign = 'center'; g.fillStyle = C.cyan
    g.fillText('STEP ' + (i + 1) + ' / ' + steps.length, w / 2, 34)
    if (!s.rect) { noteBox(s.note || '…', w / 2, h * 0.42, ease(local / 300), w); return }
    const r = s.rect; const c = centre(r)
    // The glide: 0 – 700 ms, curving.
    const k = inout(local / 700)
    const mx = (prevEnd.x + c.x) / 2 - (c.y - prevEnd.y) * 0.15; const my = (prevEnd.y + c.y) / 2 + (c.x - prevEnd.x) * 0.15
    let px = (1 - k) * (1 - k) * prevEnd.x + 2 * (1 - k) * k * mx + k * k * c.x
    let py = (1 - k) * (1 - k) * prevEnd.y + 2 * (1 - k) * k * my + k * k * c.y
    // Faint trail behind the pointer while it moves.
    if (k < 1) { g.strokeStyle = 'rgba(34,211,197,.25)'; g.setLineDash([3, 8]); g.lineWidth = 2; g.beginPath(); g.moveTo(prevEnd.x, prevEnd.y); g.quadraticCurveTo(mx, my, px, py); g.stroke(); g.setLineDash([]) }
    const act = local - 700 // after arriving
    const fade = Math.min(1, (s.len - local) / 300)
    g.globalAlpha = Math.max(0, fade)
    let press = false
    if (act > 0) {
      const a = act / 1000
      if (s.action === 'circle' || s.action === 'point') ring(c.x, c.y, Math.max(30, r.width / 2 + 16), Math.max(24, r.height / 2 + 12), ease(a / 0.7), a, s.action === 'point' ? C.lime : C.cyan)
      if (s.action === 'highlight') {
        const e = ease(a / 0.4)
        g.fillStyle = 'rgba(34,211,197,' + 0.16 * e + ')'; g.strokeStyle = C.cyan; g.shadowColor = C.cyan; g.shadowBlur = 20 * e; g.lineWidth = 2.5
        g.beginPath(); g.roundRect(r.x - 6, r.y - 6, r.width + 12, r.height + 12, 8); g.fill(); g.stroke(); g.shadowBlur = 0
        const sy = r.y - 6 + ((a * 0.9) % 1) * (r.height + 12); g.fillStyle = 'rgba(182,243,106,.35)'; g.fillRect(r.x - 6, sy, r.width + 12, 2)
      }
      if (s.action === 'click') {
        press = a > 0.15 && a < 0.45
        for (let q = 0; q < 2; q++) { const rp = (a - 0.2 - q * 0.18); if (rp > 0 && rp < 0.8) { g.strokeStyle = 'rgba(182,243,106,' + (1 - rp / 0.8) + ')'; g.lineWidth = 3; g.beginPath(); g.arc(c.x, c.y, 10 + rp * 70, 0, Math.PI * 2); g.stroke() } }
        if (a > 0.2) { g.font = '700 11px "Cascadia Mono", Consolas'; g.fillStyle = C.lime; g.textAlign = 'center'; g.fillText('CLICK', c.x, c.y - 26) }
      }
      if (s.action === 'drag' && s.to) {
        const e2 = centre(s.to)
        const dk = inout((a - 0.3) / 1.3)
        press = a > 0.2 && a < 1.8
        // The dashed path and the drop target, then a ghost copy riding the pointer.
        g.strokeStyle = 'rgba(255,176,32,.7)'; g.setLineDash([8, 8]); g.lineDashOffset = -a * 40; g.lineWidth = 2.5
        g.beginPath(); g.moveTo(c.x, c.y); g.lineTo(e2.x, e2.y); g.stroke(); g.setLineDash([])
        g.strokeStyle = C.amber; g.lineWidth = 2; g.beginPath(); g.roundRect(s.to.x - 4, s.to.y - 4, s.to.width + 8, s.to.height + 8, 8); g.stroke()
        if (a > 0.2) {
          px = c.x + (e2.x - c.x) * Math.max(0, dk); py = c.y + (e2.y - c.y) * Math.max(0, dk)
          const gw = Math.min(160, r.width); const gh = Math.min(60, r.height)
          g.fillStyle = 'rgba(34,211,197,.18)'; g.strokeStyle = C.cyan; g.lineWidth = 1.5
          g.beginPath(); g.roundRect(px - gw / 2, py - gh / 2, gw, gh, 6); g.fill(); g.stroke()
        }
        if (dk >= 1) { const rp = a - 1.6; if (rp > 0 && rp < 0.8) { g.strokeStyle = 'rgba(255,176,32,' + (1 - rp / 0.8) + ')'; g.lineWidth = 3; g.beginPath(); g.arc(e2.x, e2.y, 10 + rp * 70, 0, Math.PI * 2); g.stroke() } }
      }
      if (s.action === 'type') {
        g.strokeStyle = C.cyan; g.lineWidth = 2; g.beginPath(); g.roundRect(r.x - 4, r.y - 4, r.width + 8, r.height + 8, 6); g.stroke()
        const n = Math.floor(a * 14); const txt = String(s.text || '').slice(0, n) + (Math.floor(a * 3) % 2 ? '|' : '')
        noteBox(txt || '|', c.x, r.y + r.height + 14, 1, w, C.lime)
      }
      noteBox(s.note, c.x, r.y - 52 < 40 ? r.y + r.height + (s.action === 'type' ? 60 : 16) : r.y - 52, ease(a / 0.35), w)
    }
    g.globalAlpha = 1
    cursorAt(px, py, press)
  }

  window.Assistant.on((ev, d) => {
    if (ev === 'point') { target = d; steps = null; t0 = performance.now() }
    if (ev === 'demo') { steps = d; target = null; t0 = performance.now(); from = { x: innerWidth * 0.5, y: innerHeight * 0.62 } }
  })
  requestAnimationFrame(frame)
})()
