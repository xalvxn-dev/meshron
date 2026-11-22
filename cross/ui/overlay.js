// The floating live chat (Windows). Gets the live's comments, hearts and viewer count from the
// app (main.js forwards them) and sends replies to everyone watching.
;(function () {
  'use strict'
  const n = window.meshronNative
  const $ = (id) => document.getElementById(id)
  const list = $('list')
  let began = Date.now()
  let hearts = 0

  // See-through, remembered.
  const fade = $('fade')
  try { fade.value = localStorage.getItem('meshron.overlayFade') || '85' } catch {}
  const applyFade = () => { document.documentElement.style.setProperty('--a', String(fade.value / 100)); try { localStorage.setItem('meshron.overlayFade', fade.value) } catch {} }
  fade.oninput = applyFade
  applyFade()

  function add (name, text, cls) {
    const empty = list.querySelector('.empty')
    if (empty) empty.remove()
    const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 40
    const row = document.createElement('div')
    row.className = 'msg' + (cls ? ' ' + cls : '')
    const b = document.createElement('b'); b.textContent = name
    row.append(b, document.createTextNode(text))
    list.appendChild(row)
    while (list.children.length > 150) list.firstChild.remove()
    if (atBottom) list.scrollTop = list.scrollHeight
  }

  function burst (count) {
    const box = $('burst')
    for (let i = 0; i < Math.min(6, count); i++) {
      setTimeout(() => {
        const f = document.createElement('span'); f.className = 'fly'; f.textContent = '♥'
        f.style.setProperty('--dx', (Math.random() * 30 - 15) + 'px')
        box.appendChild(f); setTimeout(() => f.remove(), 1700)
      }, i * 120)
    }
  }

  n.onEvent((ev, d) => {
    if (ev === 'overlay-info' && d) { if (d.began) began = d.began; return }
    if (ev === 'live-chat') {
      if (d.joined) return add(d.name || 'Someone', 'joined', 'joined')
      if (d.text) add(d.from ? (d.name || 'Someone') : 'You', d.text, d.from ? '' : 'me')
    } else if (ev === 'live-hearts') {
      hearts += d.n || 1; $('hearts').textContent = String(hearts); burst(d.n || 1)
    } else if (ev === 'live-viewers') {
      $('viewers').textContent = String(d.n || 0)
    }
  })

  setInterval(() => {
    const t = Math.floor((Date.now() - began) / 1000)
    $('time').textContent = (t >= 3600 ? Math.floor(t / 3600) + ':' + String(Math.floor(t / 60) % 60).padStart(2, '0') : Math.floor(t / 60)) + ':' + String(t % 60).padStart(2, '0')
  }, 1000)

  const say = $('say')
  say.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return
    const text = say.value.trim()
    if (!text) return
    say.value = ''
    n.invoke('live.chat', { host: '', text }).catch(() => add('Meshron', "Couldn't send that", 'joined'))
  })
  $('open').onclick = () => n.showMain()
  $('close').onclick = () => n.overlayClose()
})()
