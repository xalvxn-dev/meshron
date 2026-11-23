// Meshron's board: the whole screen becomes a hologram whiteboard it writes and draws on while it
// explains (Windows and Android). Items appear one after another, as if written live:
//   text / math   handwriting (math: fractions frac{a}{b}, roots sqrt{x}, powers x^2, x^{n+1},
//                 subscripts a_1, Greek \pi \theta…, and × ÷ ≤ ≥ ≠ ± → from * / <= >= != +- ->)
//   line, arrow, circle, rect, highlight, cross, tick, dot, bracket
//   graph         axes and a plotted function (x^2-2, sin(x), …), with labelled points
//   steps         a numbered list down the side, one line at a time
// Coordinates are 0–100 across and down the screen. Positions are where things start (top left of
// text). The board stays until cleared; new items add to it unless told to start fresh.
;(function () {
  'use strict'
  const A = window.Assistant
  const cv = document.getElementById('c'); const g = cv.getContext('2d')
  const COL = { cyan: '#22D3C5', lime: '#B6F36A', amber: '#FFB020', pink: '#FF6BD6', blue: '#8E9BFF', red: '#FF5A6A', white: '#EEF6FF', yellow: '#FFE36B', orange: '#FF8A3D', green: '#3DDC84', purple: '#B57BFF' }
  const col = (c) => COL[String(c || '').toLowerCase()] || (/^#[0-9a-f]{3,8}$/i.test(c || '') ? c : COL.white)
  const HAND = '"Segoe Print", "Segoe UI Variable Display", "Segoe UI", Roboto, sans-serif'
  const MATHF = '"Cambria Math", "STIX Two Math", "Segoe UI Variable Display", "Segoe UI", serif'
  let W = 0; let H = 0
  let items = [] // { item, start, len }
  let clock = 0 // when the next item starts (ms, page time)
  let dim = 0.55
  let title = ''

  // ---- Little math layout engine ----
  const SYM = { '\\pi': 'π', '\\theta': 'θ', '\\alpha': 'α', '\\beta': 'β', '\\gamma': 'γ', '\\delta': 'δ', '\\Delta': 'Δ', '\\lambda': 'λ', '\\mu': 'μ', '\\sigma': 'σ', '\\Sigma': 'Σ', '\\omega': 'ω', '\\phi': 'φ', '\\infty': '∞', '\\int': '∫', '\\sum': '∑', '\\approx': '≈', '\\times': '×', '\\div': '÷', '\\cdot': '·', '\\pm': '±', '\\neq': '≠', '\\leq': '≤', '\\geq': '≥', '\\to': '→', '\\degree': '°', '\\angle': '∠', '\\triangle': '△', '\\therefore': '∴' }
  function tidy (s) {
    let t = String(s)
    for (const [k, v] of Object.entries(SYM).sort((a, b) => b[0].length - a[0].length)) t = t.split(k).join(v)
    return t.replace(/<=/g, '≤').replace(/>=/g, '≥').replace(/!=/g, '≠').replace(/\+-/g, '±').replace(/->/g, '→').replace(/(\d|\)|\s)\*(\s|\d|\(|[a-z])/gi, '$1×$2').replace(/\bpi\b/g, 'π')
  }
  /** Parses into nodes: { t: 'txt', s } | { t: 'sup', a } | { t: 'sub', a } | { t: 'frac', n, d } | { t: 'sqrt', a } */
  function parse (src) {
    const s = tidy(src); let i = 0
    function group () {
      if (s[i] === '{') { i++; const out = seq('}'); i++; return out }
      // A single token: a number or one character.
      const m = /^(\d+(\.\d+)?|[^\s{}^_])/.exec(s.slice(i)); i += m ? m[0].length : 1
      return [{ t: 'txt', s: m ? m[0] : '' }]
    }
    function seq (end) {
      const out = []; let buf = ''
      const flush = () => { if (buf) out.push({ t: 'txt', s: buf }); buf = '' }
      while (i < s.length && s[i] !== end) {
        if (s.startsWith('frac{', i) || s.startsWith('\\frac{', i)) { flush(); i = s.indexOf('{', i); const n = group(); const d = group(); out.push({ t: 'frac', n, d }); continue }
        if (s.startsWith('sqrt{', i) || s.startsWith('\\sqrt{', i)) { flush(); i = s.indexOf('{', i); out.push({ t: 'sqrt', a: group() }); continue }
        if (s.startsWith('sqrt(', i)) { flush(); i += 5; let depth = 1; let j = i; while (j < s.length && depth) { if (s[j] === '(') depth++; if (s[j] === ')') depth--; j++ } const inner = s.slice(i, j - 1); i = j; out.push({ t: 'sqrt', a: parse(inner) }); continue }
        if (s[i] === '^') { flush(); i++; out.push({ t: 'sup', a: group() }); continue }
        if (s[i] === '_') { flush(); i++; out.push({ t: 'sub', a: group() }); continue }
        buf += s[i++]
      }
      flush(); return out
    }
    return seq(null)
  }
  /** Measures nodes at a font size: { w, up, down } (height above and below the baseline). */
  function measure (nodes, size) {
    let w = 0; let up = size * 0.75; let down = size * 0.25
    for (const n of nodes) {
      if (n.t === 'txt') { g.font = size + 'px ' + MATHF; w += g.measureText(n.s).width }
      if (n.t === 'sup') { const m = measure(n.a, size * 0.62); w += m.w; up = Math.max(up, size * 0.42 + m.up) }
      if (n.t === 'sub') { const m = measure(n.a, size * 0.62); w += m.w; down = Math.max(down, size * 0.2 + m.down) }
      if (n.t === 'frac') { const a = measure(n.n, size * 0.82); const b = measure(n.d, size * 0.82); w += Math.max(a.w, b.w) + size * 0.3; up = Math.max(up, size * 0.3 + a.up + a.down); down = Math.max(down, b.up + b.down - size * 0.15) }
      if (n.t === 'sqrt') { const m = measure(n.a, size); w += m.w + size * 0.6; up = Math.max(up, m.up + size * 0.15); down = Math.max(down, m.down) }
    }
    return { w, up, down }
  }
  /** Draws nodes from x on a baseline y; reveal (0..1) shows them left to right like writing. Returns the pen's x. */
  function drawMath (nodes, x, y, size, color, reveal, limitX) {
    for (const n of nodes) {
      if (n.t === 'txt') {
        g.font = size + 'px ' + MATHF; g.fillStyle = color
        let s = n.s; const full = g.measureText(s).width
        if (x + full > limitX) { const keep = Math.max(0, Math.floor(s.length * (limitX - x) / Math.max(1, full))); s = s.slice(0, keep) }
        g.fillText(s, x, y); x += g.measureText(n.s).width
      }
      if (n.t === 'sup') x = drawMath(n.a, x, y - size * 0.42, size * 0.62, color, reveal, limitX)
      if (n.t === 'sub') x = drawMath(n.a, x, y + size * 0.2, size * 0.62, color, reveal, limitX)
      if (n.t === 'frac') {
        const a = measure(n.n, size * 0.82); const b = measure(n.d, size * 0.82); const w = Math.max(a.w, b.w)
        const lx = x + size * 0.15
        if (lx < limitX) { g.strokeStyle = color; g.lineWidth = Math.max(1.5, size * 0.06); g.beginPath(); g.moveTo(lx, y - size * 0.3); g.lineTo(Math.min(limitX, lx + w), y - size * 0.3); g.stroke() }
        drawMath(n.n, lx + (w - a.w) / 2, y - size * 0.3 - a.down - size * 0.12, size * 0.82, color, reveal, limitX)
        drawMath(n.d, lx + (w - b.w) / 2, y - size * 0.3 + b.up + size * 0.08, size * 0.82, color, reveal, limitX)
        x += w + size * 0.3
      }
      if (n.t === 'sqrt') {
        const m = measure(n.a, size)
        if (x < limitX) {
          g.strokeStyle = color; g.lineWidth = Math.max(1.5, size * 0.06); g.beginPath()
          g.moveTo(x, y - size * 0.3); g.lineTo(x + size * 0.15, y - size * 0.38); g.lineTo(x + size * 0.32, y + m.down * 0.6); g.lineTo(x + size * 0.5, y - m.up - size * 0.1); g.lineTo(Math.min(limitX, x + size * 0.6 + m.w), y - m.up - size * 0.1); g.stroke()
        }
        x = drawMath(n.a, x + size * 0.58, y, size, color, reveal, limitX)
      }
    }
    return x
  }

  // ---- A tiny safe expression evaluator for graphs ----
  function compile (expr) {
    const src = String(expr).replace(/\s+/g, '').replace(/\*\*/g, '^').replace(/π/g, 'pi')
    let i = 0
    const peek = () => src[i]
    function num () { const m = /^(\d+(\.\d+)?|\.\d+)/.exec(src.slice(i)); if (!m) return null; i += m[0].length; return parseFloat(m[0]) }
    function atom () {
      if (peek() === '(') { i++; const v = add(); i++; return v }
      if (peek() === '-') { i++; const v = pow(); return (x) => -v(x) }
      const n = num(); if (n != null) return () => n
      const m = /^[a-z]+/i.exec(src.slice(i)); if (!m) throw new Error('bad')
      i += m[0].length; const w = m[0].toLowerCase()
      if (w === 'x') return (x) => x
      if (w === 'pi') return () => Math.PI
      if (w === 'e') return () => Math.E
      const F = { sin: Math.sin, cos: Math.cos, tan: Math.tan, sqrt: Math.sqrt, abs: Math.abs, ln: Math.log, log: Math.log10, exp: Math.exp, asin: Math.asin, acos: Math.acos, atan: Math.atan, floor: Math.floor, round: Math.round }[w]
      if (!F) throw new Error('bad')
      const arg = atom(); return (x) => F(arg(x))
    }
    function implicit () { let v = atom(); while (i < src.length && /[a-z(0-9]/i.test(peek()) && peek() !== undefined) { const a = v; const b = atom(); v = (x) => a(x) * b(x) } return v }
    function pow () { const b = implicit(); if (peek() === '^') { i++; const e = pow(); return (x) => Math.pow(b(x), e(x)) } return b }
    function mul () { let v = pow(); while (peek() === '*' || peek() === '/') { const op = src[i++]; const a = v; const b = pow(); v = op === '*' ? (x) => a(x) * b(x) : (x) => a(x) / b(x) } return v }
    function add () { let v = mul(); while (peek() === '+' || peek() === '-') { const op = src[i++]; const a = v; const b = mul(); v = op === '+' ? (x) => a(x) + b(x) : (x) => a(x) - b(x) } return v }
    const f = add()
    return f
  }

  // ---- Items: how long each takes, and how to draw it part-way ----
  const P = (x, y) => [x / 100 * W, y / 100 * H]
  const fontPx = (sz) => Math.max(12, (sz || 4) / 100 * H)
  function lenOf (it) {
    switch (it.type) {
      case 'text': case 'math': return Math.min(4000, 350 + String(it.text || '').length * 45)
      case 'steps': return 600 + (it.lines || []).length * 900
      case 'graph': return 2200
      case 'line': case 'arrow': return 700
      case 'circle': case 'rect': case 'highlight': case 'bracket': return 650
      default: return 400
    }
  }
  function pen (x, y, color) { g.save(); g.fillStyle = '#fff'; g.shadowColor = color; g.shadowBlur = 16; g.beginPath(); g.arc(x, y, 4, 0, Math.PI * 2); g.fill(); g.restore() }
  function drawItem (it, k) {
    const color = col(it.color || (it.type === 'highlight' ? 'yellow' : it.type === 'math' ? 'white' : 'cyan'))
    g.save(); g.lineCap = 'round'; g.lineJoin = 'round'; g.shadowColor = color; g.shadowBlur = 8
    const lw = Math.max(2, (it.width || 0.35) / 100 * H)
    switch (it.type) {
      case 'text': {
        const [x, y] = P(it.x, it.y); const size = fontPx(it.size || 4)
        g.font = (it.bold ? '700 ' : '') + size + 'px ' + HAND; g.fillStyle = color; g.textBaseline = 'top'
        const s = String(it.text || ''); const n = Math.ceil(s.length * k)
        // Wrap long text to the width it was given (or to the screen edge).
        const maxW = (it.w ? it.w / 100 * W : W - x - 20)
        const words = s.slice(0, n).split(' '); let line = ''; let ly = y; let lastX = x
        for (const w of words) { const t = line ? line + ' ' + w : w; if (g.measureText(t).width > maxW && line) { g.fillText(line, x, ly); ly += size * 1.25; line = w } else line = t }
        g.fillText(line, x, ly); lastX = x + g.measureText(line).width
        if (k < 1) pen(lastX + 2, ly + size * 0.6, color)
        break
      }
      case 'math': {
        const [x, y] = P(it.x, it.y); const size = fontPx(it.size || 5)
        const nodes = parse(it.text || ''); const m = measure(nodes, size)
        const limit = x + m.w * k
        const end = drawMath(nodes, x, y + m.up, size, color, k, limit)
        if (k < 1) pen(Math.min(end, limit), y + m.up - size * 0.3, color)
        break
      }
      case 'line': case 'arrow': {
        const pts = (it.points || [it.from, it.to]).filter(Boolean).map(([a, b]) => P(a, b)); if (pts.length < 2) break
        g.strokeStyle = color; g.lineWidth = lw; if (it.dashed) g.setLineDash([lw * 3, lw * 3])
        // Reveal along the length.
        const segs = []; let total = 0
        for (let i = 1; i < pts.length; i++) { const l = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); segs.push(l); total += l }
        let left = total * k; g.beginPath(); g.moveTo(...pts[0]); let ex = pts[0]
        for (let i = 1; i < pts.length && left > 0; i++) { const f = Math.min(1, left / segs[i - 1]); ex = [pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * f, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * f]; g.lineTo(...ex); left -= segs[i - 1] }
        g.stroke(); g.setLineDash([])
        if (it.type === 'arrow' && k >= 1) { const a = pts[pts.length - 2]; const b = pts[pts.length - 1]; const an = Math.atan2(b[1] - a[1], b[0] - a[0]); const hs = lw * 5; g.fillStyle = color; g.beginPath(); g.moveTo(...b); g.lineTo(b[0] - hs * Math.cos(an - 0.45), b[1] - hs * Math.sin(an - 0.45)); g.lineTo(b[0] - hs * Math.cos(an + 0.45), b[1] - hs * Math.sin(an + 0.45)); g.closePath(); g.fill() }
        if (k < 1) pen(...ex, color)
        if (it.label && k >= 1) { const m = pts[Math.floor(pts.length / 2)]; g.font = fontPx(3) + 'px ' + HAND; g.fillStyle = color; g.fillText(it.label, m[0] + 8, m[1] - 8) }
        break
      }
      case 'circle': {
        const [x, y] = P(it.x, it.y); const r = (it.r || 5) / 100 * H
        g.strokeStyle = color; g.lineWidth = lw; g.beginPath(); g.arc(x, y, r, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * k); g.stroke()
        if (it.fill) { g.globalAlpha = 0.15 * k; g.fillStyle = color; g.fill() }
        break
      }
      case 'rect': case 'highlight': {
        const [x, y] = P(it.x, it.y); const w = (it.w || 10) / 100 * W; const h = (it.h || 6) / 100 * H
        if (it.type === 'highlight') { g.globalAlpha = 0.28; g.fillStyle = color; g.fillRect(x, y, w * k, h); break }
        g.strokeStyle = color; g.lineWidth = lw
        // Drawn round from the top-left corner, like a pen.
        const corners = [[x, y], [x + w, y], [x + w, y + h], [x, y + h], [x, y]]
        let left = 2 * (w + h) * k; g.beginPath(); g.moveTo(x, y)
        for (let i = 1; i < corners.length && left > 0; i++) { const [ax, ay] = corners[i - 1]; const [bx, by] = corners[i]; const l = Math.hypot(bx - ax, by - ay); const f = Math.min(1, left / l); g.lineTo(ax + (bx - ax) * f, ay + (by - ay) * f); left -= l }
        g.stroke()
        break
      }
      case 'tick': case 'cross': case 'dot': {
        const [x, y] = P(it.x, it.y); const s = fontPx(it.size || 4)
        g.strokeStyle = color; g.fillStyle = color; g.lineWidth = lw * 1.3
        if (it.type === 'dot') { g.beginPath(); g.arc(x, y, s * 0.15 * k, 0, Math.PI * 2); g.fill() }
        if (it.type === 'tick') { g.beginPath(); g.moveTo(x - s * 0.4, y); g.lineTo(x - s * 0.1, y + s * 0.3 * Math.min(1, k * 2)); if (k > 0.5) g.lineTo(x - s * 0.1 + s * 0.6 * (k - 0.5) * 2, y + s * 0.3 - s * 0.7 * (k - 0.5) * 2); g.stroke() }
        if (it.type === 'cross') { g.beginPath(); g.moveTo(x - s * 0.3, y - s * 0.3); g.lineTo(x - s * 0.3 + s * 0.6 * Math.min(1, k * 2), y - s * 0.3 + s * 0.6 * Math.min(1, k * 2)); if (k > 0.5) { g.moveTo(x + s * 0.3, y - s * 0.3); g.lineTo(x + s * 0.3 - s * 0.6 * (k - 0.5) * 2, y - s * 0.3 + s * 0.6 * (k - 0.5) * 2) } g.stroke() }
        if (it.label) { g.font = fontPx(3) + 'px ' + HAND; g.fillText(it.label, x + s * 0.4, y - s * 0.4) }
        break
      }
      case 'bracket': {
        const [x, y] = P(it.x, it.y); const h = (it.h || 10) / 100 * H * k
        g.strokeStyle = color; g.lineWidth = lw; g.beginPath(); g.moveTo(x, y); g.quadraticCurveTo(x + 14, y, x + 14, y + h / 2 - 6); g.lineTo(x + 22, y + h / 2); g.lineTo(x + 14, y + h / 2 + 6); g.quadraticCurveTo(x + 14, y + h, x, y + h); g.stroke()
        if (it.label && k >= 1) { g.font = fontPx(3) + 'px ' + HAND; g.fillStyle = color; g.fillText(it.label, x + 30, y + h / 2 + 6) }
        break
      }
      case 'steps': {
        const [x, y] = P(it.x == null ? 64 : it.x, it.y == null ? 18 : it.y); const size = fontPx(it.size || 3.2)
        const lines = it.lines || []; const shown = lines.length * k
        lines.forEach((ln, i) => {
          const part = Math.max(0, Math.min(1, shown - i)); if (!part) return
          const ly = y + i * size * 1.9
          g.fillStyle = color; g.beginPath(); g.arc(x + size * 0.5, ly + size * 0.55, size * 0.55, 0, Math.PI * 2); g.globalAlpha = 0.25; g.fill(); g.globalAlpha = 1
          g.font = '700 ' + size * 0.75 + 'px ' + MATHF; g.textAlign = 'center'; g.textBaseline = 'middle'; g.fillText(String(i + 1), x + size * 0.5, ly + size * 0.58)
          g.textAlign = 'left'; g.textBaseline = 'alphabetic'
          const nodes = parse(ln); const m = measure(nodes, size)
          drawMath(nodes, x + size * 1.4, ly + m.up * 0.85, size, COL.white, part, x + size * 1.4 + m.w * part)
          g.textAlign = 'left'; g.textBaseline = 'alphabetic'
        })
        break
      }
      case 'graph': {
        const [x, y] = P(it.x == null ? 10 : it.x, it.y == null ? 20 : it.y); const w = (it.w || 40) / 100 * W; const h = (it.h || 50) / 100 * H
        const xmin = it.xmin == null ? -5 : +it.xmin; const xmax = it.xmax == null ? 5 : +it.xmax; const ymin = it.ymin == null ? -5 : +it.ymin; const ymax = it.ymax == null ? 5 : +it.ymax
        const sx = (v) => x + (v - xmin) / (xmax - xmin) * w; const sy = (v) => y + h - (v - ymin) / (ymax - ymin) * h
        // Grid and axes first (the first 30%), then each curve.
        const ka = Math.min(1, k / 0.3)
        g.shadowBlur = 0; g.strokeStyle = 'rgba(34,211,197,.12)'; g.lineWidth = 1
        for (let v = Math.ceil(xmin); v <= xmax; v++) { g.beginPath(); g.moveTo(sx(v), y); g.lineTo(sx(v), y + h * ka); g.stroke() }
        for (let v = Math.ceil(ymin); v <= ymax; v++) { g.beginPath(); g.moveTo(x, sy(v)); g.lineTo(x + w * ka, sy(v)); g.stroke() }
        g.strokeStyle = 'rgba(238,246,255,.75)'; g.lineWidth = 2
        if (ymin <= 0 && ymax >= 0) { g.beginPath(); g.moveTo(x, sy(0)); g.lineTo(x + w * ka, sy(0)); g.stroke() }
        if (xmin <= 0 && xmax >= 0) { g.beginPath(); g.moveTo(sx(0), y + h); g.lineTo(sx(0), y + h - h * ka); g.stroke() }
        g.fillStyle = 'rgba(238,246,255,.6)'; g.font = Math.max(11, h * 0.035) + 'px ' + MATHF
        if (ka >= 1) { g.fillText('x', x + w + 6, Math.min(y + h, Math.max(y, sy(0))) + 4); g.fillText('y', Math.min(x + w, Math.max(x, sx(0))) + 6, y - 6) }
        const fns = (it.fns || [it.fn]).filter(Boolean)
        const kc = Math.max(0, (k - 0.3) / 0.7)
        fns.forEach((fx, fi) => {
          let f; try { f = compile(fx) } catch { return }
          const c2 = col((it.colors || [])[fi] || ['cyan', 'pink', 'lime', 'amber'][fi % 4])
          g.strokeStyle = c2; g.shadowColor = c2; g.shadowBlur = 10; g.lineWidth = 3; g.beginPath(); let started = false
          const steps = 400; const upto = steps * kc
          for (let s2 = 0; s2 <= upto; s2++) {
            const xv = xmin + (xmax - xmin) * s2 / steps; const yv = f(xv)
            // Off the top or bottom of the graph: lift the pen (no flat lines along the edge).
            if (!isFinite(yv) || yv < ymin - (ymax - ymin) * 0.02 || yv > ymax + (ymax - ymin) * 0.02) { started = false; continue }
            const px = sx(xv); const py = sy(yv)
            if (!started) { g.moveTo(px, py); started = true } else g.lineTo(px, py)
          }
          g.stroke()
          if (kc >= 1) { const lsz = Math.max(13, h * 0.05); g.shadowBlur = 0; drawMath(parse('y = ' + fx), x + w * 0.6, y + lsz * 1.3 + fi * lsz * 1.6, lsz, c2, 1, 1e9) }
        })
        if (kc >= 1) for (const p of it.points || []) { g.fillStyle = col(p.color || 'amber'); g.beginPath(); g.arc(sx(p.x), sy(p.y), 6, 0, Math.PI * 2); g.fill(); if (p.label) { g.font = Math.max(12, h * 0.04) + 'px ' + MATHF; g.fillText(p.label, sx(p.x) + 9, sy(p.y) - 9) } }
        break
      }
    }
    g.restore()
  }

  // ---- Showing ----
  function frame (now) {
    const dpr = window.devicePixelRatio || 1
    W = cv.clientWidth; H = cv.clientHeight
    if (cv.width !== Math.round(W * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr) }
    g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, W, H)
    if (items.length) {
      // Hologram glass: the screen dims behind a faint grid so the writing reads over anything.
      g.fillStyle = 'rgba(3,8,16,' + dim + ')'; g.fillRect(0, 0, W, H)
      g.strokeStyle = 'rgba(34,211,197,.05)'; g.lineWidth = 1
      for (let x = 0; x < W; x += 40) { g.beginPath(); g.moveTo(x, 0); g.lineTo(x, H); g.stroke() }
      for (let y = 0; y < H; y += 40) { g.beginPath(); g.moveTo(0, y); g.lineTo(W, y); g.stroke() }
      if (title) { g.font = '700 ' + Math.max(16, H * 0.035) + 'px ' + HAND; g.fillStyle = COL.cyan; g.shadowColor = COL.cyan; g.shadowBlur = 14; g.fillText(title, W * 0.04, H * 0.08); g.shadowBlur = 0; g.fillStyle = 'rgba(34,211,197,.5)'; g.fillRect(W * 0.04, H * 0.095, Math.min(W * 0.5, g.measureText(title).width), 2) }
      // Highlights go under everything else (a marker behind the writing).
      for (const pass of [true, false]) for (const e of items) {
        if ((e.item.type === 'highlight') !== pass) continue
        const k = Math.max(0, Math.min(1, (now - e.start) / e.len))
        if (k > 0) drawItem(e.item, k)
      }
    }
    requestAnimationFrame(frame)
  }
  /** Adds items (in order). fresh: start a clean board. */
  function add (list, opts = {}) {
    const now = performance.now()
    if (opts.fresh || !items.length) { items = []; clock = now + 250 }
    if (opts.title != null) title = String(opts.title)
    if (opts.dim != null) dim = Math.max(0, Math.min(0.9, +opts.dim))
    clock = Math.max(clock, now)
    for (const it of list.slice(0, 80)) {
      const len = lenOf(it); items.push({ item: it, start: clock, len })
      clock += len + (it.pause ? Math.min(4000, it.pause * 1000) : 150)
    }
  }
  function clear () { items = []; title = ''; if (A && A.boardClose) A.boardClose() }

  document.getElementById('x').onclick = clear
  window.addEventListener('keydown', (e) => { if (e.key === 'Escape') clear() })
  // Only the close button takes clicks (Windows: the rest passes through to your apps).
  const xb = document.getElementById('x')
  xb.addEventListener('mouseenter', () => A && A.boardMouse && A.boardMouse(true))
  xb.addEventListener('mouseleave', () => A && A.boardMouse && A.boardMouse(false))
  if (A && A.on) A.on((ev, d) => { if (ev === 'board') add(d.items || [], d); if (ev === 'board-clear') { items = []; title = '' } })
  window.MeshronBoard = { add, clear, compile }
  requestAnimationFrame(frame)
})()
