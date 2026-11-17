// The moment (story) editor for the shared screens (iPhone + Windows), like Android's
// MomentEditor: a photo, or a shared reel as a tilted card that plays its video, decorated
// with text, stickers, @mentions and drawing, then flattened to one JPEG and posted as a
// photo story. A reel card's place is sent along ("⧉card:cx,cy,scale,turn" with its
// meshron://reel link) so viewers see its video play right where the card sits.

;(function () {
  'use strict'
  const UI = () => window.MeshronUI
  const STICKERS = ['😂', '😍', '🔥', '💯', '😭', '🥹', '😎', '🤯', '👀', '🙌', '❤️', '💜', '✨', '🎉', '🥳', '👏', '💀', '🤝', '🙏', '😅', '🤔', '😴', '🎶', '📍', '⭐', '🌙', '☀️', '🌈', '🍕', '⚽', '🎮', '🎬', '📸', '💪', '🫶', '👑']
  const COLORS = ['#FFFFFF', '#000000', '#FF3B30', '#FF9500', '#FFCC00', '#34C759', '#19D3C5', '#007AFF', '#8B5CFF', '#FF2D92']
  const GRADIENTS = [['#4F7BFF', '#8B5CFF'], ['#FF5F6D', '#FFC371'], ['#11998E', '#38EF7D'], ['#232526', '#414345'], ['#FC466B', '#3F5EFB'], ['#0F2027', '#2C5364'], ['#F7971E', '#FFD200']]
  const BASE = 26 // text size (px on screen) at scale 1
  const REEL = /meshron:\/\/reel\/([0-9a-f]{64})\/([A-Za-z0-9_-]+)/
  const CARD = /⧉card:([-0-9.]+),([-0-9.]+),([-0-9.]+),([-0-9.]+)/

  function loadImage (src) {
    return new Promise((resolve, reject) => { const i = new Image(); i.onload = () => resolve(i); i.onerror = reject; i.src = src })
  }
  const contrast = (hex) => { const n = parseInt(hex.slice(1), 16); const l = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)); return l > 160 ? '#000000' : '#FFFFFF' }

  /**
   * Opens the editor. [photo]: a Blob; [reel]: { ch, id, thumb (image URL), creator }.
   */
  async function open ({ photo, reel } = {}) {
    const { h, M, toast, oops, icon, toBase64 } = UI()
    const bgUrl = photo ? URL.createObjectURL(photo) : reel && reel.thumb ? reel.thumb : null
    const bgImg = bgUrl ? await loadImage(bgUrl).catch(() => null) : null
    const isCard = !!reel
    let grad = 0
    const card = { cx: 0.5, cy: 0.46, scale: 1, turn: -6 }
    const layers = [] // { el, text, color, boxed, sticker, cx, cy, scale, turn }
    const strokes = [] // { color, width (fraction of W), points: [[x, y] fractions] }
    let selected = null
    let mode = 'idle' // idle | text | draw | rotate
    let penColor = COLORS[0]

    // ---- Layout: a 9:16 stage that fits the screen ----
    const root = h('div', { class: 'se', style: { position: 'fixed', inset: 0, zIndex: 300, background: '#000', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', touchAction: 'none', userSelect: 'none' } })
    const stageH = Math.min(window.innerHeight - 150, (window.innerWidth - 16) * 16 / 9)
    const stageW = stageH * 9 / 16
    const stage = h('div', { class: 'se-stage', style: { position: 'relative', width: stageW + 'px', height: stageH + 'px', borderRadius: '18px', overflow: 'hidden', background: '#111' } })
    const bg = h('div', { style: { position: 'absolute', inset: 0 } })
    stage.appendChild(bg)
    const paintBg = () => {
      if (isCard) bg.style.background = 'linear-gradient(180deg,' + GRADIENTS[grad][0] + ',' + GRADIENTS[grad][1] + ')'
      else if (bgImg) bg.replaceChildren(h('img', { src: bgUrl, alt: '', style: { width: '100%', height: '100%', objectFit: 'contain' } }))
      else bg.style.background = 'linear-gradient(180deg,' + GRADIENTS[grad][0] + ',' + GRADIENTS[grad][1] + ')'
    }
    paintBg()

    // The reel card (plays its video while you edit).
    let cardEl = null
    let video = null
    if (isCard) {
      const cw = stageW * 0.62
      video = h('video', { muted: true, loop: true, playsinline: true, autoplay: true, style: { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' } })
      video.muted = true
      cardEl = h('div', { style: { position: 'absolute', width: cw + 'px', height: (cw * 16 / 9) + 'px', borderRadius: '16px', overflow: 'hidden', boxShadow: '0 10px 30px rgba(0,0,0,0.45)', background: '#000' } },
        bgImg ? h('img', { src: bgUrl, alt: '', style: { position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover' } }) : null,
        video,
        reel.creator ? h('div', { style: { position: 'absolute', left: '10px', top: '10px', color: '#fff', fontWeight: 700, fontSize: '12px', textShadow: '0 0 6px rgba(0,0,0,0.6)' } }, reel.creator) : null,
        h('div', { style: { position: 'absolute', right: '10px', bottom: '10px', color: '#fff', fontWeight: 700, fontSize: '12px', textShadow: '0 0 6px rgba(0,0,0,0.6)' } }, 'Meshron'))
      stage.appendChild(cardEl)
      if (window.MeshronReels && window.MeshronReels.videoUrl) window.MeshronReels.videoUrl({ ch: reel.ch, id: reel.id }).then((u) => { video.src = u; video.play().catch(() => {}) }).catch(() => {})
      drag(cardEl, card, placeCard)
    }
    function placeCard () {
      if (!cardEl) return
      const cw = stageW * 0.62; const ch = cw * 16 / 9
      cardEl.style.left = (card.cx * stageW - cw / 2) + 'px'
      cardEl.style.top = (card.cy * stageH - ch / 2) + 'px'
      cardEl.style.transform = 'rotate(' + card.turn + 'deg) scale(' + card.scale + ')'
    }
    placeCard()

    // Drawing.
    const ink = h('canvas', { width: Math.round(stageW * 2), height: Math.round(stageH * 2), style: { position: 'absolute', inset: 0, width: '100%', height: '100%', pointerEvents: 'none' } })
    stage.appendChild(ink)
    const ictx = ink.getContext('2d')
    function redrawInk () {
      ictx.clearRect(0, 0, ink.width, ink.height)
      for (const s of strokes) strokePath(ictx, s, ink.width, ink.height)
    }
    ink.addEventListener('pointerdown', (e) => {
      if (mode !== 'draw') return
      e.preventDefault()
      const r = stage.getBoundingClientRect()
      const s = { color: penColor, width: 0.012, points: [[(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]] }
      strokes.push(s)
      const move = (ev) => { s.points.push([(ev.clientX - r.left) / r.width, (ev.clientY - r.top) / r.height]); redrawInk() }
      const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up) }
      window.addEventListener('pointermove', move); window.addEventListener('pointerup', up)
    })

    // ---- Layers ----
    function styleLayer (l) {
      const fg = l.sticker ? '#000' : l.boxed ? contrast(l.color) : l.color
      Object.assign(l.el.style, {
        left: (l.cx * stageW) + 'px', top: (l.cy * stageH) + 'px',
        transform: 'translate(-50%,-50%) rotate(' + l.turn + 'deg) scale(' + l.scale + ')',
        color: fg, background: l.boxed ? l.color : 'transparent',
        textShadow: l.boxed || l.sticker ? 'none' : '0 0 8px rgba(0,0,0,0.45)',
        outline: selected === l ? '1.5px dashed rgba(255,255,255,0.8)' : 'none'
      })
      l.el.textContent = l.text
    }
    function addLayer (o) {
      const l = Object.assign({ text: '', color: '#FFFFFF', boxed: false, sticker: false, cx: 0.5, cy: 0.42, scale: 1, turn: 0 }, o)
      l.el = h('div', { class: 'se-layer', style: { position: 'absolute', maxWidth: (stageW * 0.85) + 'px', padding: '4px 10px', borderRadius: '10px', fontWeight: 800, fontSize: (l.sticker && /^\p{Extended_Pictographic}/u.test(l.text) ? BASE * 2 : BASE) + 'px', lineHeight: 1.2, textAlign: 'center', whiteSpace: 'pre-wrap', wordBreak: 'break-word', cursor: 'grab', touchAction: 'none' } })
      layers.push(l)
      stage.insertBefore(l.el, ink.nextSibling)
      drag(l.el, l, () => styleLayer(l), () => { select(l) }, () => { if (!l.sticker || l.text.startsWith('@')) editText(l) })
      styleLayer(l)
      return l
    }
    function select (l) {
      const prev = selected
      selected = l
      if (prev) styleLayer(prev)
      if (l) styleLayer(l)
      renderBars()
    }
    function removeLayer (l) {
      const i = layers.indexOf(l); if (i >= 0) layers.splice(i, 1)
      l.el.remove(); if (selected === l) selected = null
      renderBars()
    }

    /** Drag to move; a tap (no movement) selects, a second tap edits. */
    function drag (el, obj, onMove, onTap, onTap2) {
      el.addEventListener('pointerdown', (e) => {
        if (mode === 'draw') return
        e.preventDefault(); e.stopPropagation()
        const r = stage.getBoundingClientRect()
        const sx = e.clientX; const sy = e.clientY; const ox = obj.cx; const oy = obj.cy
        let moved = false
        const move = (ev) => {
          const dx = (ev.clientX - sx) / r.width; const dy = (ev.clientY - sy) / r.height
          if (Math.abs(ev.clientX - sx) + Math.abs(ev.clientY - sy) > 4) moved = true
          obj.cx = Math.min(1, Math.max(0, ox + dx)); obj.cy = Math.min(1, Math.max(0, oy + dy))
          onMove()
        }
        const up = () => {
          window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up)
          if (!moved) { if (onTap2 && selected === obj) onTap2(); else if (onTap) onTap() }
        }
        window.addEventListener('pointermove', move); window.addEventListener('pointerup', up)
      })
    }
    stage.addEventListener('pointerdown', () => { if (mode === 'idle' && selected) select(null) })

    // ---- Text editing (also @mentions) ----
    function editText (l, mention) {
      mode = 'text'
      const fresh = !l
      l = l || addLayer({ text: mention ? '@' : '', boxed: !!mention, color: mention ? '#FFFFFF' : '#FFFFFF', sticker: !!mention })
      l.el.style.visibility = 'hidden'
      const ta = h('textarea', { rows: 3, maxlength: 200, style: { width: '86%', background: 'transparent', border: 'none', outline: 'none', color: l.color, textAlign: 'center', fontSize: '28px', fontWeight: 800, resize: 'none', textShadow: '0 0 8px rgba(0,0,0,0.5)' } })
      ta.value = l.text
      const sw = h('div', { style: { display: 'flex', gap: '8px', justifyContent: 'center', flexWrap: 'wrap', margin: '10px 0' } })
      const boxBtn = h('button', { class: 'btn small soft', onClick: () => { l.boxed = !l.boxed; paintSw() } }, 'A▢')
      const paintSw = () => {
        sw.replaceChildren(boxBtn, ...COLORS.map((c) => h('button', { onClick: () => { l.color = c; ta.style.color = c; paintSw() }, style: { width: '26px', height: '26px', borderRadius: '50%', background: c, border: l.color === c ? '3px solid #fff' : '2px solid rgba(255,255,255,0.4)' } })))
      }
      paintSw()
      let suggest = h('div')
      if (mention || l.text.startsWith('@')) {
        const people = (UI().st.contacts || []).filter((c) => c.status === 'ADDED')
        const fill = () => {
          const q = ta.value.replace(/^@/, '').toLowerCase()
          suggest.replaceChildren(...people.filter((c) => (c.handle || c.name || '').toLowerCase().includes(q)).slice(0, 6).map((c) =>
            h('button', { class: 'btn small soft', onClick: () => { ta.value = '@' + (c.handle || c.name); ta.focus() } }, '@' + (c.handle || c.name))))
        }
        suggest = h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap', justifyContent: 'center' } })
        ta.addEventListener('input', fill); fill()
      }
      const done = () => {
        l.text = ta.value.trim()
        overlay.remove(); mode = 'idle'; l.el.style.visibility = ''
        if (!l.text || l.text === '@') removeLayer(l); else { select(l); styleLayer(l) }
        renderBars()
      }
      const overlay = h('div', { style: { position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', zIndex: 5 } },
        h('button', { class: 'btn small primary', style: { position: 'absolute', top: '12px', right: '12px' }, onClick: done }, 'Done'), ta, suggest, sw)
      stage.appendChild(overlay)
      setTimeout(() => ta.focus(), 30)
      void fresh
    }

    function stickers () {
      const sheet = h('div', { style: { position: 'absolute', left: 0, right: 0, bottom: 0, maxHeight: '55%', overflowY: 'auto', background: 'rgba(20,20,24,0.96)', borderRadius: '18px 18px 0 0', padding: '14px', display: 'grid', gridTemplateColumns: 'repeat(6,1fr)', gap: '8px', zIndex: 5 } },
        ...STICKERS.map((s) => h('button', { style: { fontSize: '30px', background: 'none', border: 'none', cursor: 'pointer' }, onClick: () => { sheet.remove(); select(addLayer({ text: s, sticker: true, cx: 0.5, cy: 0.4 })) } }, s)))
      stage.appendChild(sheet)
    }

    async function download () {
      try {
        const blob = await flatten()
        const a = document.createElement('a')
        a.href = URL.createObjectURL(blob); a.download = 'meshron-moment.jpg'; a.click()
        setTimeout(() => URL.revokeObjectURL(a.href), 4000)
        toast('Saved')
      } catch (e) { oops(e) }
    }

    // ---- Flatten to one 1080-wide JPEG (same drawing as Android) ----
    function strokePath (ctx, s, W, H) {
      if (s.points.length < 2) return
      ctx.save(); ctx.strokeStyle = s.color; ctx.lineWidth = s.width * W; ctx.lineCap = 'round'; ctx.lineJoin = 'round'
      ctx.beginPath(); ctx.moveTo(s.points[0][0] * W, s.points[0][1] * H)
      for (const p of s.points.slice(1)) ctx.lineTo(p[0] * W, p[1] * H)
      ctx.stroke(); ctx.restore()
    }
    function rounded (ctx, x, y, w, hh, r) { ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + hh, r); ctx.arcTo(x + w, y + hh, x, y + hh, r); ctx.arcTo(x, y + hh, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath() }
    function wrap (ctx, text, maxW) {
      const out = []
      for (const para of text.split('\n')) {
        let line = ''
        for (const word of para.split(' ')) {
          const t = line ? line + ' ' + word : word
          if (ctx.measureText(t).width > maxW && line) { out.push(line); line = word } else line = t
        }
        out.push(line)
      }
      return out
    }
    function flatten () {
      const W = 1080; const H = Math.round(W * stageH / stageW); const k = W / stageW
      const c = document.createElement('canvas'); c.width = W; c.height = H
      const ctx = c.getContext('2d')
      if (isCard || !bgImg) {
        const g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, GRADIENTS[grad][0]); g.addColorStop(1, GRADIENTS[grad][1])
        ctx.fillStyle = g; ctx.fillRect(0, 0, W, H)
      } else {
        ctx.fillStyle = '#111111'; ctx.fillRect(0, 0, W, H)
        const sc = Math.min(W / bgImg.width, H / bgImg.height); const w = bgImg.width * sc; const hh = bgImg.height * sc
        ctx.drawImage(bgImg, (W - w) / 2, (H - hh) / 2, w, hh)
      }
      if (isCard) {
        const cw = W * 0.62; const ch = cw * 16 / 9
        ctx.save(); ctx.translate(card.cx * W, card.cy * H); ctx.rotate(card.turn * Math.PI / 180); ctx.scale(card.scale, card.scale)
        ctx.fillStyle = 'rgba(0,0,0,0.33)'; rounded(ctx, -cw / 2, -ch / 2 + 14, cw, ch, 40); ctx.fill()
        rounded(ctx, -cw / 2, -ch / 2, cw, ch, 40); ctx.save(); ctx.clip()
        ctx.fillStyle = '#000'; ctx.fillRect(-cw / 2, -ch / 2, cw, ch)
        const src = video && video.readyState >= 2 && video.videoWidth ? video : bgImg
        if (src) { const sw = src.videoWidth || src.width; const shh = src.videoHeight || src.height; const sc = Math.max(cw / sw, ch / shh); ctx.drawImage(src, -sw * sc / 2, -shh * sc / 2, sw * sc, shh * sc) }
        ctx.restore()
        ctx.font = 'bold 30px system-ui, sans-serif'; ctx.fillStyle = '#fff'; ctx.shadowColor = 'rgba(0,0,0,0.55)'; ctx.shadowBlur = 6
        if (reel.creator) ctx.fillText(reel.creator, -cw / 2 + 24, -ch / 2 + 48)
        ctx.fillText('Meshron', cw / 2 - 24 - ctx.measureText('Meshron').width, ch / 2 - 24)
        ctx.restore()
      }
      for (const s of strokes) strokePath(ctx, s, W, H)
      for (const l of layers) {
        if (!l.text) continue
        const size = (l.sticker && /^\p{Extended_Pictographic}/u.test(l.text) ? BASE * 2 : BASE) * k
        ctx.save(); ctx.translate(l.cx * W, l.cy * H); ctx.rotate(l.turn * Math.PI / 180); ctx.scale(l.scale, l.scale)
        ctx.font = '800 ' + size + 'px system-ui, sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
        const lines = wrap(ctx, l.text, stageW * 0.85 * k - 20 * k)
        const lh = size * 1.2; const tw = Math.max(...lines.map((t) => ctx.measureText(t).width)); const bw = tw + 20 * k; const bh = lines.length * lh + 8 * k
        if (l.boxed) { ctx.fillStyle = l.color; rounded(ctx, -bw / 2, -bh / 2, bw, bh, 10 * k); ctx.fill() }
        ctx.fillStyle = l.sticker ? '#000' : l.boxed ? contrast(l.color) : l.color
        if (!l.boxed && !l.sticker) { ctx.shadowColor = 'rgba(0,0,0,0.45)'; ctx.shadowBlur = 8 * k }
        lines.forEach((t, i) => ctx.fillText(t, 0, -((lines.length - 1) * lh) / 2 + i * lh))
        ctx.restore()
      }
      return new Promise((resolve, reject) => c.toBlob((b) => (b ? resolve(b) : reject(new Error("Couldn't make the picture"))), 'image/jpeg', 0.9))
    }

    // ---- Tools, selection bar, caption and share ----
    const tools = h('div', { style: { position: 'absolute', right: '10px', top: '60px', display: 'flex', flexDirection: 'column', gap: '10px', zIndex: 4 } })
    const top = h('div', { style: { position: 'absolute', left: '10px', right: '10px', top: '10px', display: 'flex', justifyContent: 'space-between', zIndex: 4 } })
    const bottom = h('div', { style: { position: 'absolute', left: '10px', right: '10px', bottom: '10px', display: 'flex', flexDirection: 'column', gap: '8px', zIndex: 4 } })
    stage.append(tools, top, bottom)
    const round = (label, title, onClick, on) => h('button', { title, onClick: (e) => { e.stopPropagation(); onClick() }, onPointerdown: (e) => e.stopPropagation(), style: { minWidth: '40px', height: '40px', padding: '0 10px', borderRadius: '20px', border: 'none', background: on ? '#fff' : 'rgba(0,0,0,0.45)', color: on ? '#000' : '#fff', fontWeight: 800, fontSize: '15px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '6px' } }, label)
    const caption = h('input', { class: 'input', placeholder: 'Add a caption…', maxlength: 300, style: { background: 'rgba(0,0,0,0.5)', color: '#fff', border: 'none' } })
    caption.addEventListener('pointerdown', (e) => e.stopPropagation())
    const shareBtn = h('button', { class: 'btn primary', style: { flex: 1 }, onClick: post }, 'Your story')
    const close = () => { root.remove(); if (photo && bgUrl) URL.revokeObjectURL(bgUrl); if (video) video.pause() }

    function renderBars () {
      top.replaceChildren(round('✕', 'Close', close), mode === 'draw'
        ? h('div', { style: { display: 'flex', gap: '6px', alignItems: 'center' } }, ...COLORS.map((c) => h('button', { onClick: () => { penColor = c; renderBars() }, style: { width: '22px', height: '22px', borderRadius: '50%', background: c, border: penColor === c ? '3px solid #fff' : '2px solid rgba(255,255,255,0.4)' } })),
          round('↶', 'Undo', () => { strokes.pop(); redrawInk() }), round('Done', 'Done', () => { mode = 'idle'; ink.style.pointerEvents = 'none'; renderBars() }, true))
        : h('span'))
      tools.replaceChildren(...(mode === 'idle' ? [
        round('Aa', 'Text', () => editText(null)),
        round('☺', 'Stickers', stickers),
        round('@', 'Mention', () => editText(null, true)),
        isCard || !bgImg ? round('◐', 'Background', () => { grad = (grad + 1) % GRADIENTS.length; paintBg() }) : null,
        isCard ? round('↻', 'Rotate', () => { mode = 'rotate'; renderBars() }) : null,
        round('✎', 'Draw', () => { mode = 'draw'; ink.style.pointerEvents = 'auto'; select(null); renderBars() }),
        round('⤓', 'Download', download)
      ].filter(Boolean) : []))
      const sel = selected && mode === 'idle'
        ? h('div', { style: { display: 'flex', gap: '6px', justifyContent: 'center' } },
          round('A−', 'Smaller', () => { selected.scale = Math.max(0.4, selected.scale - 0.15); styleLayer(selected) }),
          round('A+', 'Bigger', () => { selected.scale = Math.min(4, selected.scale + 0.15); styleLayer(selected) }),
          round('⟲', 'Turn left', () => { selected.turn -= 10; styleLayer(selected) }),
          round('⟳', 'Turn right', () => { selected.turn += 10; styleLayer(selected) }),
          round('🗑', 'Remove', () => removeLayer(selected)))
        : null
      const rot = mode === 'rotate'
        ? h('div', { style: { background: 'rgba(0,0,0,0.6)', borderRadius: '14px', padding: '10px 12px', color: '#fff' } },
          h('div', { style: { fontSize: '12px', opacity: 0.8 } }, 'Turn'), slider(-45, 45, 1, card.turn, (v) => { card.turn = v; placeCard() }),
          h('div', { style: { fontSize: '12px', opacity: 0.8 } }, 'Size'), slider(0.5, 1.5, 0.01, card.scale, (v) => { card.scale = v; placeCard() }),
          h('div', { style: { textAlign: 'right', marginTop: '6px' } }, round('Done', 'Done', () => { mode = 'idle'; renderBars() }, true)))
        : null
      bottom.replaceChildren(...[sel, rot, mode === 'idle' ? caption : null, mode === 'idle' ? h('div', { style: { display: 'flex', gap: '8px' } }, shareBtn) : null].filter(Boolean))
    }
    function slider (min, max, step, value, on) {
      const s = h('input', { type: 'range', min, max, step, style: { width: '100%' } }); s.value = value
      s.addEventListener('input', () => on(Number(s.value))); s.addEventListener('pointerdown', (e) => e.stopPropagation())
      return s
    }

    async function post () {
      shareBtn.disabled = true; shareBtn.textContent = 'Sharing…'
      try {
        const blob = await flatten()
        const meta = isCard ? '⧉card:' + [card.cx, card.cy, card.scale, card.turn].map((v) => v.toFixed(4)).join(',') : ''
        const link = isCard ? '🎬 ' + (reel.creator || 'Meshron') + ' · meshron://reel/' + reel.ch + '/' + reel.id : ''
        const text = [caption.value.trim(), link, meta].filter(Boolean).join('\n')
        const id = await M.call('file.begin', { conv: 'story', name: 'story.jpg', mime: 'image/jpeg', size: blob.size })
        const bytes = new Uint8Array(await blob.arrayBuffer())
        for (let o = 0; o < bytes.length; o += 240 * 1024) await M.call('file.chunk', { id, data: toBase64(bytes.subarray(o, o + 240 * 1024)) })
        await M.call('stories.post', { type: 'photo', text, upload: id })
        close(); toast('Added to your moment')
      } catch (e) { oops(e); shareBtn.disabled = false; shareBtn.textContent = 'Your story' }
    }

    void icon
    renderBars()
    root.appendChild(stage)
    document.body.appendChild(root)
  }

  /** Splits a story's text into what to show and the shared reel's card (if any). */
  function parse (text) {
    const t = String(text || '')
    const m = REEL.exec(t)
    const c = CARD.exec(t)
    const shown = t.split('\n').filter((l) => !l.includes('meshron://reel/') && !l.startsWith('Watch this') && !l.startsWith('🎬') && !l.startsWith('⧉')).join('\n').trim()
    const by = (/🎬 (@?[^·]+?) ·/.exec(t) || [])[1]
    return { shown, reel: m ? { ch: m[1], id: m[2] } : null, card: c ? c.slice(1).map(Number) : null, by: by ? by.trim() : null }
  }

  window.MeshronStoryEditor = { open, parse }
})()
