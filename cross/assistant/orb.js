// The floating orb (Windows): tap to talk, drag to move (it settles on a side), right-click to open
// the panel. The strip beside it shows what you said, the answer, and approvals. This window also
// owns the microphone and the speaker for the whole app.
;(function () {
  'use strict'
  const A = window.Assistant
  const $ = (id) => document.getElementById(id)
  const reactor = window.Reactor($('c'))
  const strip = $('strip')
  const setSide = (s) => document.body.classList.toggle('right', s !== 'left')
  setSide(location.hash.slice(1) || 'right')

  // Clicks pass through the empty, transparent part of this window to whatever is behind it.
  for (const el of [$('orb'), strip]) {
    el.addEventListener('mouseenter', () => A.orbMouse(true))
    el.addEventListener('mouseleave', () => { if (!drag) A.orbMouse(false) })
  }

  function render (s) {
    reactor.mode = s.mode
    const asking = s.asking
    strip.classList.toggle('ask', !!asking)
    strip.classList.toggle('on', !!(asking || s.strip))
    $('tag').textContent = asking ? 'APPROVE?' : s.tag || ''
    $('text').textContent = asking ? asking.title + (asking.detail ? ' — ' + asking.detail : '') : s.strip || ''
  }
  $('yes').onclick = () => A.approve(true)
  $('no').onclick = () => A.approve(false)

  // ---- Drag (screen coordinates) or tap ----
  let drag = null
  $('orb').addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return
    drag = { x: e.screenX, y: e.screenY, moved: false }
    $('orb').setPointerCapture(e.pointerId)
  })
  $('orb').addEventListener('pointermove', (e) => {
    if (!drag) return
    const dx = e.screenX - drag.x; const dy = e.screenY - drag.y
    if (!drag.moved && dx * dx + dy * dy < 25) return
    drag.moved = true; $('orb').style.cursor = 'grabbing'
    drag.x = e.screenX; drag.y = e.screenY
    A.orbMove(dx, dy)
  })
  $('orb').addEventListener('pointerup', () => {
    const d = drag; drag = null; $('orb').style.cursor = ''
    if (!d) return
    if (d.moved) A.orbDrop(document.body.classList.contains('right'))
    else tap()
  })
  $('orb').addEventListener('contextmenu', (e) => { e.preventDefault(); A.openPanel() })

  let mode = 'idle'
  function tap () {
    if (mode === 'listening') { Voice.listen.cancel && Voice.listen.cancel(); return }
    if (mode === 'speaking') { Voice.hush(); A.mode('idle'); return }
    if (mode === 'idle') A.listen()
  }

  async function startListening () {
    if (mode === 'listening') return
    Voice.hush()
    A.mode('listening')
    try {
      const text = await Voice.listen((lv) => { reactor.level = lv })
      if (text) A.heard(text); else { A.mode('idle'); A.heard('') }
    } catch (e) {
      A.mode('idle'); A.heard('')
    }
  }

  A.on((ev, data) => {
    if (ev === 'state') { mode = data.mode; render(data) }
    if (ev === 'side') setSide(data)
    if (ev === 'listen') startListening()
    if (ev === 'stop') Voice.listen.cancel && Voice.listen.cancel()
    if (ev === 'say') Voice.say(data, (on) => { if (on) A.mode('speaking'); else if (mode === 'speaking') A.mode('idle') })
  })
  A.state().then(render)
})()
