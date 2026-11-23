// A companion on the Windows screen (Strider walks, Wisp flies, Drift floats, Bolt walks and
// jet-flies). It lives in a see-through layer over everything; clicks pass through except on it.
// It walks along the bottom of the screen and along the tops of your app windows, jumps or flies
// between them, sits on the window you're using, falls when a window moves away, naps when left
// alone, and stops to listen and talk. Click it to talk; drag it to carry it; right-click: panel.
;(function () {
  'use strict'
  const A = window.Assistant
  const cv = document.getElementById('c'); const g = cv.getContext('2d')
  const headCv = document.getElementById('head'); const head = window.Reactor(headCv)
  let W = 0; let H = 0
  let skin = window.Reactor.SKINS.find((k) => k.id === 'strider')
  let state = { mode: 'idle' }
  let wins = [] // [{ x, y, w, h, fg }] app windows in this layer's coordinates (front first)
  const R = 34 // head radius
  const pal = { x: 300, y: 0, vx: 0, vy: 0, facing: 1, phase: 0, mode: 'fall', target: null, surface: null, idle: 0, sleep: false, perch: false, dance: 0, hidden: false, hover: false }
  let drag = null
  let bubble = null // { tag, text, until }
  let t0 = performance.now()

  const ground = () => H - 6 // feet on the taskbar's top edge
  const footY = () => R * 0.72 + R * 0.95 // from head centre to feet
  const canWalk = () => skin.pal === 'walk' || skin.pal === 'jet'
  const canFly = () => skin.pal === 'fly' || skin.pal === 'jet'

  /** Places it can stand: the screen bottom and the visible tops of app windows. */
  function surfaces () {
    const out = [{ x1: 0, x2: W, y: ground(), id: 'floor' }]
    // Only the parts of a window's top edge you can actually see (not behind a window in front of it).
    wins.forEach((w, i) => {
      if (w.y < 40 || w.w < 140) return
      let parts = [[w.x + 10, w.x + w.w - 10]]
      for (const f of wins.slice(0, i)) {
        if (w.y < f.y || w.y > f.y + f.h) continue
        parts = parts.flatMap(([a, b]) => (f.x + f.w <= a || f.x >= b) ? [[a, b]] : [[a, Math.min(b, f.x)], [Math.max(a, f.x + f.w), b]].filter(([p, q]) => q - p > 60))
      }
      parts.forEach(([a, b], k) => out.push({ x1: a, x2: b, y: w.y, id: w.id + ':' + k, fg: w.fg }))
    })
    return out
  }
  const surfaceUnder = (x, y) => surfaces().filter((s) => x >= s.x1 && x <= s.x2 && s.y >= y - 2).sort((a, b) => a.y - b.y)[0]
  function pickTarget () {
    const S = surfaces()
    const roll = Math.random()
    if (roll < 0.35 && wins.length) {
      // Sit on the window you're using (or another one).
      const fg = S.find((s) => s.fg) || S[1 + Math.floor(Math.random() * (S.length - 1))] || S[0]
      return { x: fg.x1 + (fg.x2 - fg.x1) * (0.2 + Math.random() * 0.6), y: fg.y, surface: fg, sit: true }
    }
    const s = S[Math.floor(Math.random() * S.length)]
    return { x: s.x1 + (s.x2 - s.x1) * Math.random(), y: s.y, surface: s }
  }

  function step (dt, t) {
    if (drag || pal.hidden) return
    const talking = state.mode !== 'idle' || state.asking
    if (talking) { pal.sleep = false; pal.idle = 0 }
    if (pal.dance > 0) { pal.dance -= dt }
    const feet = pal.y + footY()
    if (skin.pal === 'float') {
      // Drift: floats, bobbing, wandering slowly; rests over a window top sometimes.
      if (!pal.target || Math.hypot(pal.target.x - pal.x, pal.target.y - pal.y) < 20 || Math.random() < 0.002) pal.target = { x: 80 + Math.random() * (W - 160), y: 80 + Math.random() * (H - 260) }
      if (!talking) { pal.vx += (pal.target.x - pal.x) * 0.02 * dt; pal.vy += (pal.target.y - pal.y) * 0.02 * dt }
      pal.vx *= 0.97; pal.vy *= 0.97
      pal.x += pal.vx * dt * 30; pal.y += pal.vy * dt * 30 + Math.sin(t * 1.6) * 0.3
      pal.y = Math.max(R * 1.6, Math.min(H - R * 3, pal.y))
      pal.facing = pal.vx >= 0 ? 1 : -1; return
    }
    if (pal.mode === 'fly') {
      // Flying: toward the target, gently; land when it's reached (if it can walk or has a perch).
      if (!pal.target) pal.target = pickTarget()
      const tx = pal.target.x; const ty = pal.target.y - footY() - (canWalk() || pal.target.sit ? 0 : 60)
      const dx = tx - pal.x; const dy = ty - pal.y; const dist = Math.hypot(dx, dy)
      const sp = 260
      pal.vx += ((dx / (dist || 1)) * sp - pal.vx) * Math.min(1, dt * 2.5); pal.vy += ((dy / (dist || 1)) * sp - pal.vy) * Math.min(1, dt * 2.5)
      if (talking) { pal.vx *= 0.9; pal.vy *= 0.9 }
      pal.x += pal.vx * dt; pal.y += pal.vy * dt + Math.sin(t * 3) * 0.4
      if (Math.abs(dx) > 2) pal.facing = dx > 0 ? 1 : -1
      if (dist < 10) {
        if (pal.target.sit || canWalk()) { pal.y = pal.target.y - footY(); pal.surface = pal.target.surface; pal.perch = !!pal.target.sit && skin.pal === 'fly'; pal.mode = 'stand'; pal.target = null; pal.idle = 0 } else pal.target = pickTarget()
      }
      return
    }
    if (pal.mode === 'fall' || pal.mode === 'jump') {
      pal.vy += 1400 * dt; pal.x += pal.vx * dt; pal.y += pal.vy * dt
      const s = surfaceUnder(pal.x, feet - pal.vy * dt)
      if (s && pal.vy > 0 && pal.y + footY() >= s.y) { pal.y = s.y - footY(); pal.vy = 0; pal.vx = 0; pal.surface = s; pal.mode = 'stand'; pal.idle = 0 }
      if (pal.y > H) { pal.y = ground() - footY(); pal.mode = 'stand' }
      return
    }
    // Standing or walking on a surface. If the surface went away (window moved), fall or fly.
    const still = surfaceUnder(pal.x, feet - 4)
    if (!still || Math.abs(still.y - feet) > 6) { if (canFly() && skin.pal === 'fly') { pal.mode = 'fly'; pal.target = pickTarget() } else { pal.mode = 'fall'; pal.vy = 0 } return }
    pal.surface = still
    if (talking || pal.dance > 0) { pal.walking = false; return }
    pal.idle += dt
    if (pal.sleep) { if (pal.idle > 40) { pal.sleep = false; pal.idle = 0 } return }
    if (pal.idle > 28 && Math.random() < 0.01) { pal.sleep = true; return }
    if (!pal.target) { if (pal.idle > 2.5 + Math.random() * 4) { pal.target = pickTarget(); pal.idle = 0 } else { pal.walking = false; return } }
    const tg = pal.target
    if (tg.surface && tg.surface.id !== still.id) {
      // Another surface: fly there, or jump if it's within a jump.
      const dx = tg.x - pal.x; const dy = tg.y - feet
      if (canFly() && (Math.abs(dx) > 420 || dy < -260 || skin.pal === 'fly')) { pal.mode = 'fly'; pal.vy = -120; return }
      if (Math.abs(dx) < 520 && dy > -300) {
        const time = 0.75; pal.vx = dx / time; pal.vy = (dy - 0.5 * 1400 * time * time) / time; pal.mode = 'jump'; pal.facing = dx > 0 ? 1 : -1; pal.target = null; return
      }
      // Too far: walk along this one instead.
      pal.target = { x: still.x1 + (still.x2 - still.x1) * Math.random(), y: still.y, surface: still }
      return
    }
    const dx = tg.x - pal.x
    if (Math.abs(dx) < 4) { pal.walking = false; pal.perch = !!tg.sit; pal.target = null; pal.idle = 0; return }
    pal.facing = dx > 0 ? 1 : -1; pal.walking = true; pal.perch = false
    pal.x += pal.facing * Math.min(Math.abs(dx), 95 * dt)
    pal.phase += dt * 9
    pal.x = Math.max(still.x1, Math.min(still.x2, pal.x))
  }

  function draw (t) {
    const dpr = window.devicePixelRatio || 1
    W = cv.clientWidth; H = cv.clientHeight
    if (cv.width !== Math.round(W * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr) }
    g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, W, H)
    if (pal.hidden) return
    const air = pal.mode === 'fly' || pal.mode === 'jump' || pal.mode === 'fall' || skin.pal === 'float'
    const bounce = pal.dance > 0 ? Math.abs(Math.sin(t * 9)) * 18 : pal.walking ? Math.abs(Math.sin(pal.phase)) * 3 : 0
    const x = pal.x; const y = pal.y - bounce
    // Shadow on the surface below.
    if (!air && pal.surface) { g.fillStyle = 'rgba(0,0,0,.28)'; g.beginPath(); g.ellipse(x, pal.surface.y + 1, R * 0.8, R * 0.16, 0, 0, Math.PI * 2); g.fill() }
    window.PalBody(g, skin, x, y, R, t, { walking: pal.walking && !air, phase: pal.phase, air, perch: pal.perch, facing: pal.facing })
    // Head: the face skin, drawn by the skin renderer, copied in.
    head.skin = skin.id; head.faceOnly = true
    head.mode = state.mode || 'idle'
    if (pal.sleep) { head.mood = 'sleep'; head.moodUntil = performance.now() + 500 }
    const hs = R * 2.6
    g.save(); g.translate(x, y); if (pal.facing < 0 && !pal.sleep) g.scale(-1, 1)
    g.drawImage(headCv, -hs / 2, -hs / 2, hs, hs); g.restore()
    if (pal.sleep) { g.fillStyle = 'rgba(230,247,255,.85)'; g.font = '700 14px "Cascadia Mono", Consolas'; for (let k = 0; k < 3; k++) { const p = ((t * 0.6 + k / 3) % 1); g.globalAlpha = 1 - p; g.fillText('z', x + R * 0.6 + p * 26, y - R * 0.7 - p * 40); } g.globalAlpha = 1 }
    drawBubble(x, y - R * 1.35, t)
  }

  function drawBubble (x, y) {
    const text = state.asking ? state.asking.title : state.strip
    if (!text && !(bubble && performance.now() < bubble.until)) return
    const msg = text || bubble.text; const tag = state.asking ? 'APPROVE?' : (state.tag || (bubble && bubble.tag) || '')
    g.font = '13px "Segoe UI Variable Text", "Segoe UI"'
    const words = String(msg).split(/\s+/); const lines = []; let cur = ''
    for (const w of words) { const tr = cur ? cur + ' ' + w : w; if (g.measureText(tr).width > 240 && cur) { lines.push(cur); cur = w } else cur = tr }
    if (cur) lines.push(cur)
    const shown = lines.slice(0, 4); if (lines.length > 4) shown[3] += '…'
    const bw = Math.min(270, Math.max(120, ...shown.map((l) => g.measureText(l).width)) + 28); const bh = 30 + shown.length * 17
    let bx = x - bw / 2; let by = y - bh - 10
    const below = by < 4 // no room above (near the top of the screen): show it underneath
    if (below) by = y + R * 2.6
    bx = Math.max(8, Math.min(W - bw - 8, bx))
    g.fillStyle = 'rgba(8,14,26,.94)'; g.strokeStyle = state.asking ? 'rgba(255,176,32,.7)' : skin.colors[0]; g.lineWidth = 1
    g.beginPath(); g.roundRect(bx, by, bw, bh, 12); g.fill(); g.stroke()
    g.beginPath(); if (below) { g.moveTo(x - 7, by); g.lineTo(x, by - 9); g.lineTo(x + 7, by) } else { g.moveTo(x - 7, by + bh); g.lineTo(x, by + bh + 9); g.lineTo(x + 7, by + bh) } g.closePath(); g.fill()
    g.fillStyle = state.asking ? '#FFB020' : skin.colors[0]; g.font = '700 9px "Cascadia Mono", Consolas'; g.fillText(tag.toUpperCase(), bx + 14, by + 17)
    g.fillStyle = '#E6F7FF'; g.font = '13px "Segoe UI Variable Text", "Segoe UI"'
    shown.forEach((l, i) => g.fillText(l, bx + 14, by + 34 + i * 17))
  }

  let last = performance.now()
  function loop (now) {
    const dt = Math.min(0.05, (now - last) / 1000); last = now
    const t = (now - t0) / 1000
    step(dt, t)
    if (W > 0) pal.x = Math.max(R, Math.min(W - R, pal.x || 300))
    draw(t)
    requestAnimationFrame(loop)
  }

  // ---- Mouse: only the companion takes clicks ----
  const over = (e) => Math.hypot(e.clientX - pal.x, e.clientY - (pal.y + R * 0.4)) < R * 1.4
  window.addEventListener('mousemove', (e) => {
    if (drag) { pal.x = e.clientX - drag.dx; pal.y = e.clientY - drag.dy; drag.vx = e.movementX * 30; drag.vy = e.movementY * 30; drag.moved = true; return }
    const on = over(e); if (on !== pal.hover) { pal.hover = on; A.palMouse(on) }
  })
  window.addEventListener('mousedown', (e) => {
    if (!over(e)) return
    if (e.button === 2) { A.openPanel(); return }
    drag = { dx: e.clientX - pal.x, dy: e.clientY - pal.y, moved: false, vx: 0, vy: 0 }
    pal.sleep = false; pal.idle = 0
  })
  window.addEventListener('mouseup', () => {
    if (!drag) return
    const d = drag; drag = null
    if (!d.moved) { A.listen(); return }
    // Thrown: it flies (if it can) or falls with the throw's speed.
    pal.vx = Math.max(-900, Math.min(900, d.vx)); pal.vy = Math.max(-900, Math.min(900, d.vy)); pal.target = null
    pal.mode = canFly() && skin.pal !== 'jet' ? 'fly' : 'fall'
    head.feel('surprised', 900)
  })
  window.addEventListener('contextmenu', (e) => e.preventDefault())

  /** Commands for the companion itself ("come here", "sit", "sleep", "dance", "fly", "walk left"…). */
  function command (c) {
    pal.sleep = false; pal.idle = 0; pal.hidden = false
    const S = surfaces()
    if (c.action === 'come') { const p = c.at || { x: W / 2, y: H / 2 }; const s = surfaceUnder(p.x, p.y) || S[0]; pal.target = { x: p.x, y: s.y, surface: s }; if (canFly()) pal.mode = 'fly' }
    if (c.action === 'sit') { const fg = S.find((s) => s.fg) || S[0]; pal.target = { x: (fg.x1 + fg.x2) / 2, y: fg.y, surface: fg, sit: true }; if (canFly()) pal.mode = 'fly' }
    if (c.action === 'sleep') { pal.sleep = true; pal.target = null }
    if (c.action === 'wake') pal.sleep = false
    if (c.action === 'dance') { pal.dance = 4; head.feel('happy', 4000) }
    if (c.action === 'fly') { if (canFly()) { pal.mode = 'fly'; pal.target = { x: 100 + Math.random() * (W - 200), y: 160 + Math.random() * (H / 2) } } else { pal.vy = -700; pal.mode = 'jump'; pal.vx = pal.facing * 200 } }
    if (c.action === 'left' || c.action === 'right') { const s = pal.surface || S[0]; pal.target = { x: c.action === 'left' ? s.x1 + 20 : s.x2 - 20, y: s.y, surface: s } }
    if (c.action === 'hide') pal.hidden = true
    if (c.action === 'show') pal.hidden = false
    if (c.action === 'jump') { pal.vy = -650; pal.mode = 'jump'; pal.vx = pal.facing * 150 }
  }

  A.on((ev, d) => {
    if (ev === 'state') { state = d; if (d.mode === 'listening') head.feel('surprised', 600) }
    if (ev === 'settings') setSkin(d.skin)
    if (ev === 'pal-windows') wins = d
    if (ev === 'pal') command(d)
    if (ev === 'mood') head.feel(d.mood, d.ms || 2200)
  })
  function setSkin (id) { const k = window.Reactor.SKINS.find((s) => s.id === id && s.pal); if (k) skin = k; pal.vx = 0; pal.vy = 0; pal.target = null; if (skin.pal === 'fly' && pal.mode !== 'fly') { pal.mode = 'fly'; pal.target = null } }
  A.settings().then((s) => { setSkin(s.skin); pal.x = 200 + Math.random() * 400; pal.y = 40; pal.mode = canFly() && skin.pal === 'fly' ? 'fly' : 'fall' })
  A.state().then((s) => { state = s })
  requestAnimationFrame(loop)
})()
