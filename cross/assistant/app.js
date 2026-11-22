// Meshron Assistant for Windows: the panel (sign in, conversation, settings).
// Listening and speaking happen in the orb window; this panel shows and sends.
;(function () {
  'use strict'
  const A = window.Assistant
  const root = document.getElementById('app')
  const h = (tag, props, ...kids) => {
    const el = document.createElement(tag)
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue
      if (k === 'class') el.className = v
      else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v)
      else el.setAttribute(k, v === true ? '' : v)
    }
    for (const c of kids.flat()) if (c != null && c !== false) el.append(c.nodeType ? c : String(c))
    return el
  }
  const icon = (cp) => h('i', {}, String.fromCharCode(cp))
  window.Reactor(document.getElementById('mini'))

  let st = { mode: 'idle', lines: [], signedIn: false }
  let page = location.hash === '#settings' ? 'settings' : 'home'
  let settings = {}

  // ---- Sign in (the password becomes an Argon2id key here; only the key is sent) ----
  async function authKey (u, password) {
    const salt = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode('meshron-account-v1:' + u))).slice(0, 16)
    const out = await window.hashwasm.argon2id({ password, salt, parallelism: 1, iterations: 3, memorySize: 65536, hashLength: 64, outputType: 'binary' })
    return [...out.slice(0, 32)].map((b) => b.toString(16).padStart(2, '0')).join('')
  }
  function signIn () {
    const user = h('input', { placeholder: '@username', autocomplete: 'username', spellcheck: 'false' })
    const pass = h('input', { type: 'password', placeholder: 'Password', autocomplete: 'current-password' })
    const err = h('div', { class: 'err' })
    const go = h('button', { class: 'btn accent', type: 'submit' }, 'Sign in')
    const c = h('canvas')
    const r = window.Reactor(c)
    const form = h('form', { onSubmit: async (e) => {
      e.preventDefault()
      if (!user.value.trim() || !pass.value) return
      go.disabled = true; go.textContent = 'Signing in…'; r.mode = 'thinking'; err.textContent = ''
      try {
        const u = user.value.trim().replace(/^@/, '').toLowerCase()
        await A.signIn(u, await authKey(u, pass.value))
        pass.value = ''; st = await A.state(); draw()
      } catch (x) { err.textContent = String(x.message || x).replace(/^Error invoking remote method 'a': (Error: )?/, '') } finally { go.disabled = false; go.textContent = 'Sign in'; r.mode = 'idle' }
    } }, user, pass, err, go)
    root.replaceChildren(h('div', { class: 'signin' }, c, h('h2', {}, 'Meshron Assistant'), h('div', { class: 'sub' }, 'Sign in with your Meshron account'), form,
      h('div', { class: 'fine' }, 'Your password never leaves this PC.')))
    setTimeout(() => user.focus(), 50)
  }

  // ---- Home ----
  let hero, status, convo, input, micBtn, heroReactor
  function home () {
    const c = h('canvas', { title: 'Click to talk', onClick: () => A.listen() })
    heroReactor = window.Reactor(c)
    status = h('div', { class: 'status' })
    hero = h('div', { class: 'hero' }, c, status)
    convo = h('div', { class: 'convo' })
    input = h('input', { placeholder: 'Ask or tell me to do something…', onKeydown: (e) => { if (e.key === 'Enter' && input.value.trim()) { A.ask(input.value.trim()); input.value = '' } } })
    micBtn = h('button', { class: 'mic', title: 'Talk (' + (settings.hotkey || 'no shortcut') + ')', onClick: () => (input.value.trim() ? (A.ask(input.value.trim()), (input.value = '')) : A.listen()) }, icon(0xE720))
    input.addEventListener('input', () => micBtn.replaceChildren(icon(input.value.trim() ? 0xE724 : 0xE720)))
    root.replaceChildren(hero, convo, h('div', { class: 'composer' }, h('div', { class: 'box' }, input), micBtn),
      h('div', { class: 'hint' }, 'Press ' + (settings.hotkey || 'the orb') + (settings.always ? ' or say "Hey ' + (settings.name || 'Meshron') + '"' : '') + ' to talk from anywhere'))
    renderHome()
    input.focus()
  }
  const IDEAS = ['Plan my day', 'What\'s on my screen?', 'Tidy my Downloads folder by file type', 'Open Spotify and play music', 'Remind me to stretch in 1 hour', 'Latest news', 'Lock my PC']
  function renderHome () {
    if (!hero) return
    heroReactor.mode = st.mode
    const empty = !st.lines.length
    hero.classList.toggle('small', !empty)
    const listeningWords = st.mode === 'listening' && st.strip && st.strip !== '…'
    status.className = 'status' + (listeningWords ? ' words' : '')
    status.textContent = st.mode === 'listening' ? (listeningWords ? st.strip : 'LISTENING') : st.mode === 'thinking' ? 'WORKING ON IT' : st.mode === 'speaking' ? 'SPEAKING' : empty ? '' : 'READY'
    micBtn.classList.toggle('on', st.mode === 'listening')
    if (empty) {
      convo.replaceChildren(h('div', { style: 'text-align:center' }, h('div', { class: 'greet' }, greeting()), h('div', { class: 'sub' }, 'What can I do for you?')),
        h('div', { class: 'ideas' }, IDEAS.map((t) => h('button', { class: 'idea', onClick: () => A.ask(t) }, t))))
      convo.style.justifyContent = 'flex-start'
      return
    }
    const atEnd = convo.scrollHeight - convo.scrollTop - convo.clientHeight < 60
    convo.replaceChildren(...st.lines.map((l) =>
      l.who === 'you' ? h('div', { class: 'you' }, l.text)
        : l.who === 'me' ? h('div', { class: 'me' }, h('div', { class: 'who' }, (settings.name || 'Meshron').toUpperCase()), l.text)
          : l.who === 'act' ? h('div', { class: 'act ' + (l.ok ? 'ok' : 'no') }, h('b', {}, l.ok ? '✓' : '!'), l.text)
            : h('div', { class: 'oops' }, '⚠ ' + l.text)))
    if (atEnd) convo.scrollTop = convo.scrollHeight
  }
  function greeting () {
    const hr = new Date().getHours()
    return (hr < 12 ? 'Good morning' : hr < 18 ? 'Good afternoon' : 'Good evening') + (st.user ? ', ' + st.user : '')
  }

  // ---- Settings ----
  function card (ic, title, sub, control) {
    return h('div', { class: 'card' }, icon(ic), h('div', { class: 't' }, h('div', {}, title), sub ? h('div', {}, sub) : null), control)
  }
  function toggle (key, after) {
    const b = h('button', { class: 'toggle' + (settings[key] ? ' on' : ''), role: 'switch', 'aria-checked': String(!!settings[key]) })
    b.onclick = async () => { settings = await A.setSettings({ [key]: !settings[key] }); b.classList.toggle('on', !!settings[key]); after && after() }
    return b
  }
  function hotkeyField () {
    const f = h('button', { class: 'keys', title: 'Click, then press the keys you want' }, settings.hotkey || 'None')
    f.onclick = () => {
      f.classList.add('rec'); f.textContent = 'Press keys…'
      const onKey = async (e) => {
        e.preventDefault()
        if (e.key === 'Escape') { done(settings.hotkey); return }
        if (['Control', 'Shift', 'Alt', 'Meta'].includes(e.key)) return
        const mods = [e.ctrlKey && 'Ctrl', e.altKey && 'Alt', e.shiftKey && 'Shift', e.metaKey && 'Super'].filter(Boolean)
        if (!mods.length) { f.textContent = 'Add Ctrl, Alt or Shift'; return }
        const k = e.code === 'Space' ? 'Space' : e.key.length === 1 ? e.key.toUpperCase() : e.key
        done(mods.concat(k).join('+'))
      }
      const done = async (combo) => {
        window.removeEventListener('keydown', onKey, true)
        settings = await A.setSettings({ hotkey: combo })
        const ok = await A.testHotkey()
        f.classList.remove('rec'); f.textContent = ok ? combo : combo + ' (taken by another app)'
      }
      window.addEventListener('keydown', onKey, true)
    }
    return f
  }
  function nameField () {
    const f = h('input', { class: 'keys', value: settings.name || 'Meshron', maxlength: '24', style: 'text-align:left', spellcheck: 'false' })
    f.addEventListener('change', async () => { const v = f.value.trim() || 'Meshron'; f.value = v; settings = await A.setSettings({ name: v }); document.querySelector('.appname').textContent = v })
    return f
  }
  async function fetchHeard () { const t = await A.lastHeard(); const el = document.getElementById('heard'); if (el && t) el.textContent = t }
  async function settingsPage () {
    setTimeout(fetchHeard, 0)
    settings = await A.settings()
    const voice = h('select', { onChange: async (e) => { settings = await A.setSettings({ studioVoice: e.target.value }) } },
      [['george', 'George · warm, British'], ['daniel', 'Daniel · newsreader, British'], ['brian', 'Brian · deep, American'], ['adam', 'Adam · American'], ['sarah', 'Sarah · soft, American'], ['charlotte', 'Charlotte · Swedish-English'], ['lily', 'Lily · British'], ['rachel', 'Rachel · calm, American']]
        .map(([v, l]) => h('option', { value: v, selected: settings.studioVoice === v }, l)))
    const seg = h('div', { class: 'seg' }, [[false, 'Push to talk'], [true, 'Always listening']].map(([v, l]) =>
      h('button', { class: settings.always === v ? 'on' : '', onClick: async () => { settings = await A.setSettings({ always: v }); settingsPage() } }, l)))
    root.replaceChildren(h('div', { class: 'page' },
      h('h1', {}, 'Settings'),
      h('div', { class: 'group' }, 'Name'),
      card(0xE8D4, 'What it\'s called', 'Also the wake word ("Hey ' + (settings.name || 'Meshron') + '"). Short, clear names like Nova, Jarvis or Atlas are heard best.', nameField()),
      h('div', { class: 'group' }, 'Talking'),
      card(0xE765, 'Shortcut', 'Starts listening from anywhere. Click and press your own combo.', hotkeyField()),
      card(0xE720, 'Listening', settings.always ? 'Say "Hey ' + (settings.name || 'Meshron') + '" any time (Windows\' offline speech engine listens for the phrase only).' : 'Press the shortcut, click the orb or the mic.', seg),
      settings.always ? card(0xE9D9, 'Last heard', h('span', { id: 'heard' }, 'Nothing yet. Say "Hey ' + (settings.name || 'Meshron') + '" and watch here. If nothing shows, Windows is listening to another microphone: set your mic as the default in Sound settings.'), h('button', { class: 'btn', onClick: () => fetchHeard() }, 'Refresh')) : null,
      card(0xE767, 'Speak replies', 'Read answers out loud.', toggle('speak')),
      card(0xE8D6, 'Studio voice', 'A lifelike AI voice. Off: Windows\' own voice.', toggle('studio')),
      card(0xE77B, 'Studio voice', 'Who speaks.', voice),
      h('div', { class: 'group' }, 'On screen'),
      card(0xE7F4, 'Floating orb', 'Stays over every app. Click to talk, drag to move, right-click to open this window.', toggle('orb')),
      card(0xE7E8, 'Start with Windows', 'Quietly in the tray, ready for your shortcut.', toggle('startup')),
      h('div', { class: 'group' }, 'Account'),
      card(0xE77B, 'Signed in as @' + (st.user || ''), 'Meshron account', h('button', { class: 'btn danger', onClick: async () => { await A.signOut(); st = await A.state(); page = 'home'; draw() } }, 'Sign out')),
      h('div', { class: 'group' }, 'What it can do'),
      h('div', { class: 'sub', style: 'line-height:1.6' }, 'Open, switch and close apps, show the desktop, type, press shortcuts, click buttons by name, scroll, read your screen, make folders and tidy files (deletes go to the Recycle Bin after you approve), timers, reminders, tasks, volume, music controls, battery, lock the PC, read documents and pictures, and look things up on the web.')))
  }

  // ---- Approval (ContentDialog) ----
  let dialog = null
  function renderApproval () {
    if (st.asking && !dialog) {
      dialog = h('div', { class: 'scrim' }, h('div', { class: 'dialog', role: 'dialog' },
        h('div', { class: 'in' }, h('h3', {}, st.asking.title), h('p', {}, st.asking.detail || '')),
        h('div', { class: 'row' }, h('button', { class: 'btn accent', onClick: () => A.approve(true) }, 'Approve'), h('button', { class: 'btn', onClick: () => A.approve(false) }, 'Cancel'))))
      document.body.append(dialog)
    } else if (!st.asking && dialog) { dialog.remove(); dialog = null }
  }

  function draw () {
    hero = null
    if (!st.signedIn) return signIn()
    if (page === 'settings') return settingsPage()
    home()
  }

  document.getElementById('gear').onclick = () => { page = page === 'settings' ? 'home' : 'settings'; draw() }
  document.getElementById('newchat').onclick = () => { A.reset(); page = 'home'; draw() }
  A.on(async (ev, data) => {
    if (ev === 'state') {
      const wasSigned = st.signedIn
      st = Object.assign(st, data, { signedIn: st.signedIn })
      if (!wasSigned) return
      renderHome(); renderApproval()
    }
    if (ev === 'page') { page = data; draw() }
    if (ev === 'settings') { settings = data }
    if (ev === 'heard') { const el = document.getElementById('heard'); if (el) el.textContent = data }
  })
  ;(async () => { settings = await A.settings(); st = await A.state(); document.querySelector('.appname').textContent = settings.name || 'Meshron Assistant'; draw(); renderApproval() })()
})()
