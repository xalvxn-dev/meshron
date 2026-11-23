// The floating orb (Windows): tap to talk, drag to move (it settles on a side), right-click to open
// the panel. The strip beside it shows what you said, the answer, and approvals. This window also
// owns the microphone and the speaker for the whole app.
;(function () {
  'use strict'
  const A = window.Assistant
  const $ = (id) => document.getElementById(id)
  const reactor = window.Reactor($('c'))
  const strip = $('strip')
  const setSide = (s) => { document.body.classList.toggle('right', s === 'right'); document.body.classList.toggle('center', s === 'center') }
  setSide(location.hash.slice(1) || 'right')
  // Skin: faces and island orbs are a black capsule (at the top centre until you move them).
  const THEME = { midnight: '#22D3C5', nebula: '#B57BFF', ember: '#FF8A3D', forest: '#3DDC84', ocean: '#38BDF8', gold: '#FFD86B', mono: '#E6EDF5' }
  const look = (s) => {
    window.ReactorSkin = s.skin || 'reactor'
    const sk = window.Reactor.SKINS.find((k) => k.id === window.ReactorSkin)
    document.body.classList.toggle('island', !!(sk && (sk.face || sk.island)))
    // The strip's colours follow the chosen theme (and the skin's own colour when the theme is the default).
    const acc = s.theme && s.theme !== 'midnight' ? THEME[s.theme] : sk ? sk.colors[0] : THEME.midnight
    const n = parseInt(acc.slice(1), 16)
    document.body.style.setProperty('--acc', acc)
    document.body.style.setProperty('--acc-soft', 'rgba(' + (n >> 16 & 255) + ',' + (n >> 8 & 255) + ',' + (n & 255) + ',.3)')
  }

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
    if (d.moved) { const r = $('orb').getBoundingClientRect(); A.orbDrop(document.body.classList.contains('right'), window.screenX + r.left + r.width / 2, window.screenY + r.top + r.height / 2) }
    else tap()
  })
  $('orb').addEventListener('contextmenu', (e) => { e.preventDefault(); A.openPanel() })

  /** A soft two-note chime: "I heard my name, go ahead". */
  function chime () {
    try {
      const ctx = new AudioContext()
      ;[[880, 0], [1320, 0.11]].forEach(([f, at]) => {
        const o = ctx.createOscillator(); const g = ctx.createGain()
        o.type = 'sine'; o.frequency.value = f
        g.gain.setValueAtTime(0, ctx.currentTime + at); g.gain.linearRampToValueAtTime(0.18, ctx.currentTime + at + 0.02); g.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + at + 0.25)
        o.connect(g).connect(ctx.destination); o.start(ctx.currentTime + at); o.stop(ctx.currentTime + at + 0.3)
      })
      setTimeout(() => ctx.close(), 700)
    } catch {}
  }

  let mode = 'idle'
  function tap () {
    if (mode === 'listening') { voiceTurn = false; Voice.listen.cancel && Voice.listen.cancel(); return }
    if (mode === 'speaking') { voiceTurn = false; Voice.hush(); A.mode('idle'); return }
    if (mode === 'idle') A.listen()
  }

  // Conversation: after it answers something you said, it listens again (5 s) for your reply,
  // so you don't have to wake it for every turn. Saying nothing ends the conversation.
  let voiceTurn = false
  let prevMode = 'idle'
  let speakOn = true
  A.settings().then((s) => { speakOn = s.speak !== false; look(s) })

  async function startListening (followUp) {
    if (mode === 'listening') return
    Voice.hush()
    A.mode('listening')
    try {
      const text = await Voice.listen((lv) => { reactor.level = lv }, followUp ? 5000 : 6000)
      voiceTurn = !!text
      if (text) A.heard(text); else { A.mode('idle'); A.heard('') }
    } catch (e) {
      voiceTurn = false
      A.mode('idle'); A.heard('')
    }
  }
  const followUp = () => { if (voiceTurn) setTimeout(() => { if (mode === 'idle' && voiceTurn) startListening(true) }, 350) }

  A.on((ev, data) => {
    if (ev === 'state') {
      mode = data.mode; render(data)
      // Replies that aren't spoken: listen again as soon as the answer is shown.
      if (prevMode === 'thinking' && mode === 'idle' && !speakOn && !data.asking) followUp()
      prevMode = mode
    }
    if (ev === 'settings') { speakOn = data.speak !== false; look(data) }
    if (ev === 'mood') reactor.feel(data.mood, data.ms || 2200)
    if (ev === 'side') setSide(data)
    if (ev === 'listen') startListening(false)
    if (ev === 'stop') { voiceTurn = false; Voice.listen.cancel && Voice.listen.cancel() }
    if (ev === 'chime') { chime(); reactor.feel('surprised', 900) }
    if (ev === 'say') Voice.say(data, (on) => { if (on) A.mode('speaking'); else { if (mode === 'speaking') { A.mode('idle'); mode = 'idle' } followUp() } })
  })
  A.state().then(render)
})()
