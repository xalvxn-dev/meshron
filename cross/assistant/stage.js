// The showcase stage (Windows): the orb in the middle of the screen with something shown around it:
// recommended voices orbiting it, a ring per drive, live CPU / GPU / memory gauges, or the battery.
;(function () {
  'use strict'
  const A = window.Assistant
  const $ = (id) => document.getElementById(id)
  const world = $('world'); const fx = $('fx'); const g = fx.getContext('2d')
  const reactor = window.Reactor($('core'))
  const C = { cyan: '#22D3C5', blue: '#6E7BFF', lime: '#B6F36A', amber: '#FFB020', red: '#FF5A6A' }
  let show = null // { kind, data }
  let t0 = performance.now()
  let leaving = false
  let autoClose = null

  const gb = (b) => (b >= 1e12 ? (b / 1e12).toFixed(1) + ' TB' : Math.round(b / 1e9) + ' GB')
  const heat = (p) => (p >= 85 ? C.red : p >= 65 ? C.amber : C.cyan)

  function size () {
    const dpr = window.devicePixelRatio || 1
    const w = fx.clientWidth; const h = fx.clientHeight
    if (fx.width !== Math.round(w * dpr)) { fx.width = Math.round(w * dpr); fx.height = Math.round(h * dpr) }
    g.setTransform(dpr, 0, 0, dpr, 0, 0)
    return { w, h, cx: w / 2, cy: h / 2, R: Math.min(w, h) / 2 }
  }

  /** An arc gauge: track, value (eased in), and its label at the arc's middle. */
  function gauge (cx, cy, r, from, to, value, color, width, label, sub, grow) {
    g.lineCap = 'round'
    g.strokeStyle = 'rgba(255,255,255,.08)'; g.lineWidth = width
    g.beginPath(); g.arc(cx, cy, r, from, to); g.stroke()
    const v = Math.max(0.002, Math.min(1, value)) * grow
    g.strokeStyle = color; g.shadowColor = color; g.shadowBlur = 14
    g.beginPath(); g.arc(cx, cy, r, from, from + (to - from) * v); g.stroke()
    g.shadowBlur = 0
    if (label) {
      const mid = (from + to) / 2; const lr = r + width + 26
      const lx = cx + lr * Math.cos(mid); const ly = cy + lr * Math.sin(mid)
      g.textAlign = 'center'; g.fillStyle = '#E6F7FF'; g.font = '600 15px "Segoe UI Variable Text", "Segoe UI"'
      g.fillText(label, lx, ly)
      if (sub) { g.fillStyle = '#8FA6BC'; g.font = '12px "Segoe UI Variable Text", "Segoe UI"'; g.fillText(sub, lx, ly + 17) }
    }
  }

  // Smoothly follow live values (system load) instead of jumping.
  const shown = {}
  const ease = (k, v) => { shown[k] = shown[k] == null ? v : shown[k] + (v - shown[k]) * 0.12; return shown[k] }

  function frame (now) {
    const { w, h, cx, cy, R } = size()
    g.clearRect(0, 0, w, h)
    const t = (now - t0) / 1000
    const grow = Math.min(1, t / 1.1) // values sweep in over the first second
    if (show) {
      const { kind, data } = show
      if (kind === 'tour') {
        window.Illustrate.tour(g, cx, cy, R, t, data.name)
      } else if (kind === 'toast') {
        // A small card beside the orb for an action (or the keyboard typing a search).
        if (data.pic === 'search') window.Illustrate.keyboard(g, cx, cy + R * 0.32, R * 1.75, t, data.query)
        else if (window.Illustrate.PICS[data.pic]) window.Illustrate.PICS[data.pic](g, cx, cy - R * 0.06, R * 0.9, t, data)
      } else if (kind === 'voices') {
        // Faint orbit track; the bubbles are elements (clickable) placed in place().
        g.strokeStyle = 'rgba(34,211,197,.18)'; g.setLineDash([3, 7]); g.lineWidth = 1
        g.beginPath(); g.arc(cx, cy, R * 0.62, 0, Math.PI * 2); g.stroke(); g.setLineDash([])
        place(cx, cy, R * 0.62, t)
      } else if (kind === 'storage') {
        // One ring per drive, inside out; how full it is fills clockwise from the top.
        data.slice(0, 4).forEach((d, i) => {
          const r = R * (0.34 + i * 0.1); const used = 1 - d.free / d.size
          gauge(cx, cy, r, -Math.PI / 2, Math.PI * 1.5, used, heat(used * 100), 9, null, null, grow)
          g.textAlign = 'left'; g.fillStyle = '#E6F7FF'; g.font = '600 13px "Segoe UI Variable Text", "Segoe UI"'
          const lx = cx + R * 0.78; const ly = cy - R * 0.3 + i * 44
          g.fillStyle = heat(used * 100); g.fillRect(lx - 14, ly - 9, 8, 8)
          g.fillStyle = '#E6F7FF'; g.fillText(d.id + ' ' + (d.name || ''), lx, ly)
          g.fillStyle = '#8FA6BC'; g.font = '12px "Segoe UI Variable Text", "Segoe UI"'; g.fillText(gb(d.free) + ' free of ' + gb(d.size), lx, ly + 16)
        })
      } else if (kind === 'system') {
        const gap = 0.18; const third = (Math.PI * 2) / 3
        const cpu = ease('cpu', data.cpu); const gpu = ease('gpu', data.gpu.load); const ram = ease('ram', 100 * data.ramUsed / data.ramTotal)
        const r = R * 0.48
        // Three equal thirds: CPU at the top, GPU lower right, memory lower left.
        const seg = (centre) => [centre - third / 2 + gap, centre + third / 2 - gap]
        const [c1, c2] = seg(-Math.PI / 2); const [g1, g2] = seg(Math.PI / 6); const [m1, m2] = seg(Math.PI * 5 / 6)
        gauge(cx, cy, r, c1, c2, cpu / 100, heat(cpu), 14, 'CPU ' + Math.round(cpu) + '%', short(data.cpuName), grow)
        gauge(cx, cy, r, g1, g2, gpu / 100, heat(gpu), 14, 'GPU ' + Math.round(gpu) + '%', short(data.gpu.name) + (data.gpu.temp ? ' · ' + data.gpu.temp + '°C' : ''), grow)
        gauge(cx, cy, r, m1, m2, ram / 100, heat(ram), 14, 'RAM ' + Math.round(ram) + '%', gb(data.ramUsed) + ' of ' + gb(data.ramTotal), grow)
        if (data.gpu.memTotal) gauge(cx, cy, r - 24, g1, g2, data.gpu.memUsed / data.gpu.memTotal, 'rgba(110,123,255,.85)', 5, null, null, grow)
      } else if (kind === 'battery') {
        if (data.none) { g.textAlign = 'center'; g.fillStyle = '#A9C3D6'; g.font = '15px "Segoe UI"'; g.fillText('This PC runs on mains power', cx, cy + R * 0.42) } else {
          const p = data.pct / 100
          gauge(cx, cy, R * 0.46, -Math.PI / 2, Math.PI * 1.5, p, p < 0.2 ? C.red : data.charging ? C.lime : C.cyan, 16, null, null, grow)
          g.textAlign = 'center'; g.fillStyle = '#E6F7FF'; g.font = '700 30px "Segoe UI Variable Display", "Segoe UI"'
          g.fillText(Math.round(data.pct * grow) + '%', cx, cy + R * 0.62)
          g.fillStyle = '#8FA6BC'; g.font = '13px "Segoe UI"'
          g.fillText((data.charging ? '⚡ Charging' : 'On battery') + (data.mins ? ' · about ' + Math.floor(data.mins / 60) + ' h ' + (data.mins % 60) + ' min left' : ''), cx, cy + R * 0.62 + 22)
        }
      }
    }
    requestAnimationFrame(frame)
  }
  const short = (s) => String(s || '').replace(/\(R\)|\(TM\)|Laptop GPU|NVIDIA GeForce |Processor|CPU|@.*$/gi, '').replace(/\s+/g, ' ').trim().slice(0, 26)

  // ---- Voices: bubbles orbiting the orb ----
  let bubbles = []
  let hover = false
  function makeBubbles (voices, current) {
    $('bubbles').replaceChildren(...voices.map((v) => {
      const el = document.createElement('div')
      el.className = 'bubble' + (v.id.startsWith('kokoro:') ? ' kokoro' : '') + (v.id === current ? ' on' : '')
      el.innerHTML = '<div><b></b><span></span></div>'
      el.querySelector('b').textContent = v.name; el.querySelector('span').textContent = v.tag
      el.onmouseenter = () => { hover = true }; el.onmouseleave = () => { hover = false }
      el.onclick = () => {
        for (const b of bubbles) b.classList.toggle('on', b === el)
        reactor.mode = 'speaking'
        A.pickVoice(v.id, v.name)
        restartClose(9000)
      }
      return el
    }))
    bubbles = [...$('bubbles').children]
  }
  let spin = 0; let last = performance.now()
  function place (cx, cy, r, t) {
    const now = performance.now(); if (!hover) spin += (now - last) / 1000 * 0.22; last = now
    bubbles.forEach((el, i) => {
      // Each bubble glides out from the centre to its place on the orbit, one after another.
      const k = Math.min(1, Math.max(0, (t - 0.15 - i * 0.06) / 0.6)); const e = 1 - Math.pow(1 - k, 3)
      const a = spin + (i / bubbles.length) * Math.PI * 2 - Math.PI / 2
      el.style.left = (cx + Math.cos(a) * r * e) + 'px'; el.style.top = (cy + Math.sin(a) * r * e) + 'px'
      el.style.opacity = String(e)
    })
  }

  // ---- Coming and going ----
  function restartClose (ms) { clearTimeout(autoClose); autoClose = setTimeout(leave, ms) }
  function leave () { if (leaving) return; leaving = true; world.classList.remove('in'); world.classList.add('out'); setTimeout(() => A.stageClose(), 560) }
  $('x').onclick = leave
  window.addEventListener('keydown', (e) => { if (e.key === 'Escape') leave() })
  document.addEventListener('click', () => { if (show && show.kind === 'tour') leave() })

  const TITLES = { voices: 'RECOMMENDED VOICES', storage: 'STORAGE', system: 'SYSTEM', battery: 'BATTERY', tour: 'WHAT I CAN DO' }
  if (A.settings) A.settings().then((s) => { window.ReactorSkin = s.skin || 'reactor' })
  A.on((ev, d) => {
    if (ev === 'show') {
      show = { kind: d.kind, data: d.data }
      world.style.setProperty('--fx', d.from.x + 'px'); world.style.setProperty('--fy', d.from.y + 'px')
      $('title').textContent = TITLES[d.kind] || ''
      document.body.classList.toggle('compact', d.kind === 'toast')
      document.body.classList.toggle('tour', d.kind === 'tour')
      if (d.kind === 'tour') $('caption').textContent = 'Tap anywhere or press Esc to close'
      reactor.mode = d.kind === 'system' ? 'thinking' : 'idle'
      if (d.kind === 'voices') { makeBubbles(d.data.voices, d.data.current); $('caption').textContent = 'Tap a voice to hear it. It becomes my voice.' }
      if (d.kind === 'storage') $('caption').textContent = d.data.length ? '' : 'No drives found'
      if (d.kind === 'system') $('caption').textContent = 'Live · updates every second'
      t0 = performance.now()
      requestAnimationFrame(() => world.classList.add('in'))
      // A search card stays until the query is typed (plus a moment); the tour runs through every feature once.
      restartClose(d.kind === 'toast' ? (d.data.pic === 'search' ? 1400 + String(d.data.query || '').length * 95 : 2600)
        : d.kind === 'tour' ? 3200 * window.Illustrate.FEATURES.length + 800
          : d.kind === 'voices' ? 30000 : d.kind === 'system' ? 15000 : 10000)
    }
    if (ev === 'update' && show && show.kind === 'system') show.data = d
    if (ev === 'leave') leave()
    if (ev === 'state' && d.mode === 'idle' && reactor.mode === 'speaking') reactor.mode = 'idle'
  })
  requestAnimationFrame(frame)
})()
