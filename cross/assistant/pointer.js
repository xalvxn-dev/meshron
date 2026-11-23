// The teaching pointer (Windows): a see-through layer over the whole screen. When Meshron explains
// how to do something it circles the button or menu, draws an arrow curving in to it, dims the
// rest of the screen a little and writes a short note beside it. It never takes clicks.
;(function () {
  'use strict'
  const cv = document.getElementById('c'); const g = cv.getContext('2d')
  let target = null // { x, y, width, height, note }
  let t0 = 0

  const ease = (k) => 1 - Math.pow(1 - Math.max(0, Math.min(1, k)), 3)

  function frame (now) {
    const dpr = window.devicePixelRatio || 1
    const w = innerWidth; const h = innerHeight
    if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr) }
    g.setTransform(dpr, 0, 0, dpr, 0, 0)
    g.clearRect(0, 0, w, h)
    if (target) draw(w, h, (now - t0) / 1000)
    requestAnimationFrame(frame)
  }

  function draw (w, h, t) {
    const { x, y, width, height, note } = target
    const cx = x + width / 2; const cy = y + height / 2
    const rx = Math.max(34, width / 2 + 18); const ry = Math.max(26, height / 2 + 14)
    const fade = t > 7.2 ? Math.max(0, 1 - (t - 7.2) / 0.6) : 1
    g.globalAlpha = fade

    // Dim everything but the spot (a spotlight).
    const k = ease(t / 0.5)
    g.save()
    g.fillStyle = 'rgba(2, 6, 14,' + (0.38 * k) + ')'
    g.beginPath(); g.rect(0, 0, w, h); g.ellipse(cx, cy, rx + 10, ry + 10, 0, 0, Math.PI * 2, true); g.fill('evenodd')
    g.restore()

    // The circle draws itself like a pen stroke, a little wobbly, then pulses.
    const draw1 = ease((t - 0.2) / 0.7)
    const pulse = 1 + 0.04 * Math.sin(t * 5)
    g.lineCap = 'round'; g.lineJoin = 'round'
    g.strokeStyle = '#22D3C5'; g.shadowColor = '#22D3C5'; g.shadowBlur = 18; g.lineWidth = 4
    g.beginPath()
    const steps = 80
    for (let i = 0; i <= steps * draw1; i++) {
      const a = -Math.PI * 0.6 + (i / steps) * Math.PI * 2.15
      const wob = 1 + 0.035 * Math.sin(a * 3 + 1)
      const px = cx + Math.cos(a) * rx * wob * pulse; const py = cy + Math.sin(a) * ry * wob * pulse
      if (i === 0) g.moveTo(px, py); else g.lineTo(px, py)
    }
    g.stroke()
    // A ripple going out.
    const rip = (t * 0.8) % 1
    g.shadowBlur = 0; g.lineWidth = 2; g.strokeStyle = 'rgba(34,211,197,' + (0.5 * (1 - rip)) + ')'
    g.beginPath(); g.ellipse(cx, cy, rx * (1 + rip * 0.5), ry * (1 + rip * 0.5), 0, 0, Math.PI * 2); g.stroke()

    // The arrow comes in from the roomier side, curving.
    const fromLeft = cx > w / 2; const fromTop = cy > h / 2
    const sx = cx + (fromLeft ? -1 : 1) * (rx + 170); const sy = cy + (fromTop ? -1 : 1) * (ry + 110)
    const ex = cx + (fromLeft ? -1 : 1) * (rx * 0.78); const ey = cy + (fromTop ? -1 : 1) * (ry * 0.78)
    const mx = (sx + ex) / 2 + (fromTop ? -40 : 40); const my = (sy + ey) / 2 + (fromLeft ? 40 : -40)
    const a1 = ease((t - 0.7) / 0.6)
    if (a1 > 0) {
      g.strokeStyle = '#B6F36A'; g.shadowColor = '#B6F36A'; g.shadowBlur = 12; g.lineWidth = 4
      g.beginPath(); g.moveTo(sx, sy)
      let px = sx; let py = sy
      for (let i = 1; i <= 40 * a1; i++) {
        const u = i / 40
        px = (1 - u) * (1 - u) * sx + 2 * (1 - u) * u * mx + u * u * ex; py = (1 - u) * (1 - u) * sy + 2 * (1 - u) * u * my + u * u * ey
        g.lineTo(px, py)
      }
      g.stroke()
      if (a1 >= 1) {
        // The head, pointing along the curve's end.
        const dir = Math.atan2(ey - my, ex - mx)
        g.fillStyle = '#B6F36A'
        g.beginPath(); g.moveTo(ex, ey)
        g.lineTo(ex - 18 * Math.cos(dir - 0.45), ey - 18 * Math.sin(dir - 0.45))
        g.lineTo(ex - 18 * Math.cos(dir + 0.45), ey - 18 * Math.sin(dir + 0.45)); g.closePath(); g.fill()
      }
      g.shadowBlur = 0
      // The note, at the arrow's tail.
      if (note) {
        const n = ease((t - 1.1) / 0.4)
        g.font = '600 17px "Segoe UI Variable Text", "Segoe UI", sans-serif'
        const tw = Math.min(360, g.measureText(note).width) + 28
        let bx = sx - tw / 2; const by = sy + (fromTop ? -46 : 10)
        bx = Math.max(12, Math.min(w - tw - 12, bx))
        g.globalAlpha = fade * n
        g.fillStyle = 'rgba(8, 14, 26, .92)'; g.strokeStyle = 'rgba(34, 211, 197, .55)'; g.lineWidth = 1.2
        round(bx, by, tw, 36, 18); g.fill(); g.stroke()
        g.fillStyle = '#E6F7FF'; g.textBaseline = 'middle'; g.textAlign = 'left'
        g.fillText(note, bx + 14, by + 18, 360)
      }
    }
    g.globalAlpha = 1
  }
  function round (x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath() }

  window.Assistant.on((ev, d) => { if (ev === 'point') { target = d; t0 = performance.now() } })
  requestAnimationFrame(frame)
})()
