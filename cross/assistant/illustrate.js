// Illustrations on the showcase stage (Windows): the feature tour ("what can you do"), small
// animated cards for actions (volume, timer, alarm, reminder, music, lock, apps, files), and the
// virtual keyboard that types out a web search. Drawn on a canvas; each returns a draw(t) function.
;(function () {
  'use strict'
  const C = { cyan: '#22D3C5', blue: '#6E7BFF', lime: '#B6F36A', amber: '#FFB020', red: '#FF5A6A', text: '#E6F7FF', dim: '#8FA6BC' }
  const ease = (x) => 1 - Math.pow(1 - Math.max(0, Math.min(1, x)), 3)
  const rr = (g, x, y, w, h, r) => { g.beginPath(); g.roundRect(x, y, w, h, r) }
  const label = (g, text, x, y, size = 15, color = C.text, weight = 600) => { g.fillStyle = color; g.font = weight + ' ' + size + 'px "Segoe UI Variable Text", "Segoe UI"'; g.textAlign = 'center'; g.fillText(text, x, y) }

  // ---- Small action pictures (cx, cy = centre, s = size, t = seconds since start) ----
  const PICS = {
    volume (g, cx, cy, s, t, d) {
      // A speaker, and bars rising to the new level.
      g.fillStyle = C.text; g.beginPath(); g.moveTo(cx - s * 0.42, cy - s * 0.1); g.lineTo(cx - s * 0.3, cy - s * 0.1); g.lineTo(cx - s * 0.16, cy - s * 0.24); g.lineTo(cx - s * 0.16, cy + s * 0.24); g.lineTo(cx - s * 0.3, cy + s * 0.1); g.lineTo(cx - s * 0.42, cy + s * 0.1); g.fill()
      const lvl = (d.level || 0) / 100 * ease(t / 0.9)
      for (let i = 0; i < 10; i++) { const on = i / 10 < lvl; g.fillStyle = on ? (i > 7 ? C.amber : C.cyan) : 'rgba(255,255,255,.1)'; rr(g, cx - s * 0.06 + i * s * 0.05, cy + s * 0.2 - (i + 2) * s * 0.035, s * 0.035, (i + 2) * s * 0.035, 2); g.fill() }
      label(g, Math.round(d.level || 0) + '%', cx, cy + s * 0.48, 18)
    },
    timer (g, cx, cy, s, t, d) {
      const r = s * 0.32
      g.strokeStyle = 'rgba(255,255,255,.12)'; g.lineWidth = 8; g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.stroke()
      g.strokeStyle = C.cyan; g.lineCap = 'round'; g.beginPath(); g.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * ease(t / 1.2)); g.stroke()
      const a = -Math.PI / 2 + t * 4
      g.strokeStyle = C.text; g.lineWidth = 3; g.beginPath(); g.moveTo(cx, cy); g.lineTo(cx + Math.cos(a) * r * 0.75, cy + Math.sin(a) * r * 0.75); g.stroke()
      label(g, d.text || '', cx, cy + s * 0.5, 16)
    },
    alarm (g, cx, cy, s, t, d) {
      // A bell that rings.
      g.save(); g.translate(cx, cy - s * 0.06); g.rotate(Math.sin(t * 18) * 0.25 * Math.max(0, 1 - t / 1.6))
      g.fillStyle = C.amber; g.beginPath(); g.moveTo(-s * 0.22, s * 0.16); g.quadraticCurveTo(-s * 0.22, -s * 0.24, 0, -s * 0.26); g.quadraticCurveTo(s * 0.22, -s * 0.24, s * 0.22, s * 0.16); g.closePath(); g.fill()
      g.fillRect(-s * 0.27, s * 0.14, s * 0.54, s * 0.05); g.beginPath(); g.arc(0, s * 0.24, s * 0.05, 0, Math.PI * 2); g.fill(); g.restore()
      label(g, d.text || '', cx, cy + s * 0.48, 18)
    },
    reminder (g, cx, cy, s, t, d) {
      // A note pinned up.
      const y = cy - s * 0.25 + (1 - ease(t / 0.6)) * -s * 0.3
      g.fillStyle = '#FFE58A'; g.save(); g.translate(cx, y + s * 0.25); g.rotate(-0.06); rr(g, -s * 0.3, -s * 0.25, s * 0.6, s * 0.5, 6); g.fill()
      g.fillStyle = '#3B2F00'; g.font = '600 13px "Segoe UI"'; g.textAlign = 'center'
      wrap(g, d.text || '', 0, -s * 0.08, s * 0.5, 16); g.restore()
      g.fillStyle = C.red; g.beginPath(); g.arc(cx, y + s * 0.02, 6, 0, Math.PI * 2); g.fill()
      label(g, d.when || '', cx, cy + s * 0.48, 15, C.dim, 400)
    },
    media (g, cx, cy, s, t, d) {
      g.fillStyle = 'rgba(34,211,197,.15)'; g.beginPath(); g.arc(cx, cy, s * 0.3, 0, Math.PI * 2); g.fill()
      g.fillStyle = C.text
      if (d.action === 'next' || d.action === 'previous') {
        const k = d.action === 'next' ? 1 : -1
        for (const o of [-0.1, 0.06]) { g.beginPath(); g.moveTo(cx + k * (o - 0.08) * s, cy - s * 0.12); g.lineTo(cx + k * (o + 0.08) * s, cy); g.lineTo(cx + k * (o - 0.08) * s, cy + s * 0.12); g.fill() }
      } else if (d.action === 'mute') {
        g.fillRect(cx - s * 0.16, cy - s * 0.07, s * 0.08, s * 0.14); g.strokeStyle = C.red; g.lineWidth = 4; g.beginPath(); g.moveTo(cx + 0.02 * s, cy - s * 0.1); g.lineTo(cx + 0.16 * s, cy + s * 0.1); g.moveTo(cx + 0.16 * s, cy - s * 0.1); g.lineTo(cx + 0.02 * s, cy + s * 0.1); g.stroke()
      } else { g.fillRect(cx - s * 0.11, cy - s * 0.12, s * 0.07, s * 0.24); g.fillRect(cx + s * 0.04, cy - s * 0.12, s * 0.07, s * 0.24) }
      // Music notes floating up.
      for (let i = 0; i < 3; i++) { const p = (t * 0.6 + i / 3) % 1; g.globalAlpha = 1 - p; label(g, '♪', cx + (i - 1) * s * 0.25, cy - s * 0.3 - p * s * 0.3, 20, C.cyan); g.globalAlpha = 1 }
    },
    lock (g, cx, cy, s, t) {
      const k = ease(t / 0.7)
      g.strokeStyle = C.text; g.lineWidth = 8; g.beginPath(); g.arc(cx, cy - s * 0.1 - (1 - k) * s * 0.12, s * 0.14, Math.PI, 0); g.stroke()
      g.fillStyle = C.cyan; rr(g, cx - s * 0.22, cy - s * 0.1, s * 0.44, s * 0.32, 8); g.fill()
      g.fillStyle = '#02060C'; g.beginPath(); g.arc(cx, cy + s * 0.04, s * 0.04, 0, Math.PI * 2); g.fill()
      label(g, k >= 1 ? 'Locked' : '', cx, cy + s * 0.46, 16)
    },
    app (g, cx, cy, s, t, d) {
      // A window popping open.
      const k = ease(t / 0.6); const w = s * 0.7 * k; const h = s * 0.48 * k
      g.fillStyle = 'rgba(255,255,255,.06)'; g.strokeStyle = C.cyan; g.lineWidth = 2; rr(g, cx - w / 2, cy - h / 2, w, h, 8); g.fill(); g.stroke()
      g.fillStyle = 'rgba(34,211,197,.25)'; rr(g, cx - w / 2, cy - h / 2, w, h * 0.16, 8); g.fill()
      label(g, d.name || '', cx, cy + s * 0.46, 16)
    },
    files (g, cx, cy, s, t, d) {
      // Files flying into a folder.
      g.fillStyle = C.amber; rr(g, cx - s * 0.26, cy - s * 0.04, s * 0.52, s * 0.3, 6); g.fill(); rr(g, cx - s * 0.26, cy - s * 0.1, s * 0.2, s * 0.1, 4); g.fill()
      for (let i = 0; i < 3; i++) {
        const p = ((t * 0.9 + i / 3) % 1); const x = cx + (i - 1) * s * 0.3 * (1 - p); const y = cy - s * 0.4 + p * s * 0.4
        g.globalAlpha = 1 - p * 0.8; g.fillStyle = C.text; rr(g, x - 9, y - 12, 18, 24, 3); g.fill(); g.globalAlpha = 1
      }
      label(g, d.text || '', cx, cy + s * 0.48, 14)
    },
    game (g, cx, cy, s, t) {
      // A hand as a hologram wireframe, pinching a block.
      const P = [[0, 0.5], [-0.25, 0.35], [-0.4, 0.15], [-0.5, -0.05], [-0.55, -0.22], [-0.15, -0.05], [-0.15, -0.35], [-0.16, -0.52], [-0.17, -0.66], [0, -0.07], [0.02, -0.4], [0.03, -0.6], [0.04, -0.75], [0.15, -0.05], [0.18, -0.35], [0.2, -0.52], [0.21, -0.64], [0.28, 0.02], [0.33, -0.22], [0.36, -0.36], [0.38, -0.47]]
      const pinch = (Math.sin(t * 3) + 1) / 2
      const pts = P.map(([x, y], i) => [cx + x * s * 0.9 - s * 0.25, cy + y * s * 0.9 + (i === 4 || i === 8 ? pinch * (i === 4 ? -s * 0.15 : s * 0.12) : 0) + (i === 3 ? -pinch * s * 0.08 : 0)])
      const B = [[0, 1], [1, 2], [2, 3], [3, 4], [0, 5], [5, 6], [6, 7], [7, 8], [5, 9], [9, 10], [10, 11], [11, 12], [9, 13], [13, 14], [14, 15], [15, 16], [13, 17], [17, 18], [18, 19], [19, 20], [0, 17]]
      g.strokeStyle = C.cyan; g.shadowColor = C.cyan; g.shadowBlur = 10; g.lineWidth = 2
      for (const [a, b] of B) { g.beginPath(); g.moveTo(pts[a][0], pts[a][1]); g.lineTo(pts[b][0], pts[b][1]); g.stroke() }
      g.fillStyle = '#fff'; for (const p of pts) { g.beginPath(); g.arc(p[0], p[1], 2.5, 0, 7); g.fill() }
      // Falling blocks beside it.
      const cols = [C.cyan, C.amber, C.lime]
      for (let k = 0; k < 4; k++) { const y = cy - s * 0.5 + ((t * 0.6 + k * 0.25) % 1) * s; g.strokeStyle = cols[k % 3]; g.shadowColor = cols[k % 3]; g.strokeRect(cx + s * 0.45 + (k % 2) * 20, y, 18, 18) }
      g.shadowBlur = 0
    },
    skins (g, cx, cy, s, t) {
      // Three skins side by side: rings, a face, a nebula swirl.
      const r = s * 0.24
      g.strokeStyle = C.cyan; g.lineWidth = 3; g.shadowColor = C.cyan; g.shadowBlur = 12
      g.beginPath(); g.arc(cx - s * 0.62, cy, r, t, t + 4.5); g.stroke(); g.fillStyle = '#fff'; g.beginPath(); g.arc(cx - s * 0.62, cy, r * 0.3, 0, 7); g.fill()
      g.fillStyle = '#0B1A2A'; g.beginPath(); g.arc(cx, cy, r, 0, 7); g.fill(); g.stroke()
      const blink = (t % 3) < 0.12
      g.fillStyle = '#F4FBFF'; g.fillRect(cx - r * 0.42, cy - r * 0.25, r * 0.2, blink ? 2 : r * 0.36); g.fillRect(cx + r * 0.22, cy - r * 0.25, r * 0.2, blink ? 2 : r * 0.36)
      g.beginPath(); g.arc(cx, cy + r * 0.12, r * 0.35, 0.3, Math.PI - 0.3); g.stroke()
      for (let k = 0; k < 18; k++) { const a = t * 1.5 + k * 0.6; const d = r * (0.2 + (k % 6) * 0.14); g.fillStyle = k % 2 ? '#FF6BD6' : '#B57BFF'; g.beginPath(); g.arc(cx + s * 0.62 + Math.cos(a) * d, cy + Math.sin(a) * d * 0.8, 2.2, 0, 7); g.fill() }
      g.shadowBlur = 0
    },
    pal (g, cx, cy, s, t) {
      // A companion walking along the top of a window.
      g.strokeStyle = 'rgba(34,211,197,.6)'; g.lineWidth = 1.5; rr(g, cx - s * 0.9, cy + s * 0.18, s * 1.8, s * 0.5, 6); g.stroke()
      g.fillStyle = 'rgba(34,211,197,.12)'; g.fillRect(cx - s * 0.9, cy + s * 0.18, s * 1.8, 10)
      const x = cx + Math.sin(t * 0.7) * s * 0.6; const r = s * 0.16; const y = cy + s * 0.18 - r * 1.67
      const skin = { pal: 'walk', colors: [C.cyan, C.blue, C.lime] }
      if (window.PalBody) window.PalBody(g, skin, x, y, r, t, { walking: true, phase: t * 9, facing: Math.cos(t * 0.7) >= 0 ? 1 : -1 })
      g.fillStyle = '#0B1A2A'; g.strokeStyle = C.cyan; g.lineWidth = 2; g.beginPath(); g.arc(x, y, r, 0, 7); g.fill(); g.stroke()
      g.fillStyle = '#F4FBFF'; g.fillRect(x - r * 0.4, y - r * 0.2, r * 0.18, r * 0.34); g.fillRect(x + r * 0.22, y - r * 0.2, r * 0.18, r * 0.34)
    },
    point (g, cx, cy, s, t) {
      // Teaching: a button circled with an arrow curving in.
      g.strokeStyle = 'rgba(255,255,255,.25)'; g.lineWidth = 1.5; rr(g, cx - s * 0.9, cy - s * 0.4, s * 1.8, s * 0.8, 8); g.stroke()
      g.fillStyle = 'rgba(255,255,255,.12)'; rr(g, cx - s * 0.2, cy - s * 0.08, s * 0.5, s * 0.22, 5); g.fill()
      const k = ease((t % 2.5) / 0.9)
      g.strokeStyle = C.cyan; g.shadowColor = C.cyan; g.shadowBlur = 12; g.lineWidth = 3
      g.beginPath(); g.ellipse(cx + s * 0.05, cy + s * 0.03, s * 0.36, s * 0.2, 0, -1.6, -1.6 + k * 6.6); g.stroke()
      g.strokeStyle = C.lime; g.shadowColor = C.lime; g.beginPath(); g.moveTo(cx - s * 0.75, cy - s * 0.32); g.quadraticCurveTo(cx - s * 0.6, cy, cx - s * 0.35, cy + s * 0.02); g.stroke()
      g.shadowBlur = 0
    },
    shows (g, cx, cy, s, t) {
      // The visual showcases: gauges sweeping round.
      const arc = (x, r, v, col) => { g.strokeStyle = 'rgba(255,255,255,.1)'; g.lineWidth = 8; g.beginPath(); g.arc(x, cy, r, 0, 7); g.stroke(); g.strokeStyle = col; g.shadowColor = col; g.shadowBlur = 10; g.beginPath(); g.arc(x, cy, r, -Math.PI / 2, -Math.PI / 2 + v * Math.PI * 2); g.stroke(); g.shadowBlur = 0 }
      arc(cx - s * 0.6, s * 0.22, 0.35 + 0.25 * Math.sin(t * 1.3), C.cyan); arc(cx, s * 0.26, 0.6 + 0.2 * Math.sin(t), C.lime); arc(cx + s * 0.6, s * 0.22, 0.75, C.amber)
    }
  }

  function wrap (g, text, x, y, max, lh) {
    const words = String(text).split(' '); let line = ''; let n = 0
    for (const w of words) { const test = line ? line + ' ' + w : w; if (g.measureText(test).width > max && line) { g.fillText(line, x, y + n * lh); line = w; if (++n >= 3) return } else line = test }
    g.fillText(line, x, y + n * lh)
  }

  // ---- The virtual keyboard that types a search ----
  const ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']
  function keyboard (g, cx, cy, w, t, query) {
    const q = String(query || '').toLowerCase().slice(0, 48)
    const typed = Math.min(q.length, Math.floor(t * 11))
    const key = w / 11.5; const top = cy - key * 0.6
    // The search box above, filling in.
    g.fillStyle = 'rgba(255,255,255,.06)'; g.strokeStyle = 'rgba(34,211,197,.5)'; g.lineWidth = 1.5; rr(g, cx - w / 2, top - key * 1.9, w, key * 1.1, 10); g.fill(); g.stroke()
    g.fillStyle = C.text; g.font = '15px "Segoe UI"'; g.textAlign = 'left'
    g.fillText('SEARCH  ' + q.slice(0, typed) + ((Math.floor(t * 2) % 2) ? '|' : ''), cx - w / 2 + 14, top - key * 1.22)
    const cur = q[typed - 1]
    ROWS.forEach((row, ri) => {
      const rowW = row.length * key; const x0 = cx - rowW / 2
      ;[...row].forEach((ch, ci) => {
        const x = x0 + ci * key; const y = top + ri * key * 1.08
        const hit = ch === cur && t * 11 - typed < 0.6
        g.fillStyle = hit ? C.cyan : 'rgba(255,255,255,.07)'; rr(g, x + 2, y + 2, key - 4, key - 4, 6); g.fill()
        g.fillStyle = hit ? '#02060C' : C.dim; g.font = '600 ' + Math.round(key * 0.38) + 'px "Segoe UI"'; g.textAlign = 'center'; g.fillText(ch.toUpperCase(), x + key / 2, y + key * 0.62)
      })
    })
    const sy = top + 3 * key * 1.08
    g.fillStyle = cur === ' ' ? C.cyan : 'rgba(255,255,255,.07)'; rr(g, cx - key * 2.5, sy + 2, key * 5, key - 4, 6); g.fill()
    return typed >= q.length
  }

  // ---- The feature tour ("what can you do") ----
  const FEATURES = [
    { tag: 'TALK', title: 'Talk or type', say: '"Hey <name>" · or the shortcut', pic: 'media' },
    { tag: 'APPS', title: 'Open, switch and close apps', say: '"Open Spotify" · "Close Notepad"', pic: 'app' },
    { tag: 'FILES', title: 'Tidy your files', say: '"Sort my Downloads by type"', pic: 'files' },
    { tag: 'TIME', title: 'Timers, alarms, reminders', say: '"Remind me at 6 to call mum"', pic: 'alarm' },
    { tag: 'SOUND', title: 'Volume and music', say: '"Volume 40" · "Next song"', pic: 'volume' },
    { tag: 'WEB', title: 'Live web lookups', say: '"Latest news" · "Weather tomorrow"', pic: 'search' },
    { tag: 'TEACH', title: 'Shows you where to click', say: '"How do I change my wallpaper?"', pic: 'point' },
    { tag: 'SHOW', title: 'Visual showcases', say: '"Show my storage" · "How\'s my PC doing"', pic: 'shows' },
    { tag: 'GAME', title: 'Game mode: hand and face tracking', say: '"Game mode" · Block Drop, Air Keys, Hand Control', pic: 'game' },
    { tag: 'SKIN', title: 'Skins and faces', say: 'Settings > Look · faces with feelings', pic: 'skins' },
    { tag: 'PALS', title: 'Companions on your screen', say: '"Come here" · "Sit" · "Dance" · "Take a nap"', pic: 'pal' },
    { tag: 'LOCK', title: 'Lock the PC', say: '"Lock my PC"', pic: 'lock' }
  ]
  const SHOWS = ['"Recommend voices"', '"Show my storage"', '"How\'s my PC doing"', '"Battery"', '"Show me all your features"']

  function tour (g, cx, cy, R, t, name) {
    const per = 3.2
    const i = Math.floor(t / per) % FEATURES.length
    const f = FEATURES[i]; const lt = t % per
    const fade = Math.min(1, lt / 0.35, (per - lt) / 0.35)
    // Feature dots around the orb; the current one lit.
    FEATURES.forEach((ft, k) => {
      const a = -Math.PI / 2 + (k / FEATURES.length) * Math.PI * 2; const r = R * 0.66
      const x = cx + Math.cos(a) * r; const y = cy + Math.sin(a) * r
      g.globalAlpha = k === i ? 1 : 0.45
      g.fillStyle = k === i ? 'rgba(34,211,197,.25)' : 'rgba(255,255,255,.05)'; g.beginPath(); g.arc(x, y, k === i ? 34 : 26, 0, Math.PI * 2); g.fill()
      g.font = '700 ' + (k === i ? 11 : 9) + 'px "Cascadia Mono", Consolas, monospace'; g.textAlign = 'center'; g.fillStyle = k === i ? C.cyan : C.dim; g.fillText(ft.tag, x, y + 4)
      g.strokeStyle = k === i ? C.cyan : 'rgba(34,211,197,.25)'; g.lineWidth = 1; g.beginPath(); g.arc(x, y, k === i ? 34 : 26, 0, Math.PI * 2); g.stroke()
      g.globalAlpha = 1
    })
    g.globalAlpha = fade
    // The current feature, illustrated under the orb.
    const py = cy + R * 0.47
    const s = R * 0.3
    if (f.pic === 'search') keyboard(g, cx, py + s * 0.1, R * 0.8, lt, 'weather tomorrow')
    else if (PICS[f.pic]) PICS[f.pic](g, cx, py, s, lt, { level: 70, text: '', name: 'Spotify', action: 'play', when: '6:00 PM' })
    label(g, f.title, cx, cy - R * 0.5, 20, C.text, 700)
    label(g, 'Try: ' + f.say.replace('<name>', name || 'Meshron'), cx, cy - R * 0.5 + 24, 14, C.cyan, 400)
    g.globalAlpha = 1
    // The showcase commands, always listed at the bottom.
    label(g, 'Visual commands: ' + SHOWS.join(' · '), cx, cy + R * 0.92, 12.5, C.dim, 400)
  }

  window.Illustrate = { PICS, keyboard, tour, FEATURES }
})()
