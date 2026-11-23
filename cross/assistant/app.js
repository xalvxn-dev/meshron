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
  // Skin (orb) and theme (this window).
  const applyLook = (s) => { window.ReactorSkin = s.skin || 'reactor'; document.body.dataset.theme = s.theme || 'midnight' }

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
  let hero, status, convo, input, micBtn, heroReactor, devBar
  /** Which device you're talking to: this PC, or one of your other devices (same account). */
  async function drawDevices (list) {
    if (!devBar) return
    const me = await A.deviceId()
    const others = (list || []).filter((d) => !d.me)
    if (!others.length) { devBar.replaceChildren(); return }
    const target = settings.remoteTarget || ''
    const chip = (id, label, sub, on, ok) => h('button', { class: 'devchip' + (on ? ' on' : '') + (ok ? '' : ' off'), title: sub, onClick: async () => { settings = await A.setSettings({ remoteTarget: id }); drawDevices(list) } }, label, h('span', {}, sub))
    devBar.replaceChildren(h('span', { class: 'devlabel' }, 'Talking to'), chip('', 'This PC', 'here', !target || target === me, true),
      ...others.map((d) => chip(d.id, d.name, !d.online ? 'offline' : d.accept ? (d.platform === 'android' ? 'phone' : 'PC') : 'not allowed', target === d.id, d.online && d.accept)))
  }
  function home () {
    const c = h('canvas', { title: 'Click to talk', onClick: () => A.listen() })
    heroReactor = window.Reactor(c)
    status = h('div', { class: 'status' })
    hero = h('div', { class: 'hero' }, c, status)
    convo = h('div', { class: 'convo' })
    input = h('input', { placeholder: 'Ask or tell me to do something…', onKeydown: (e) => { if (e.key === 'Enter' && input.value.trim()) { A.ask(input.value.trim()); input.value = '' } } })
    micBtn = h('button', { class: 'mic', title: 'Talk (' + (settings.hotkey || 'no shortcut') + ')', onClick: () => (input.value.trim() ? (A.ask(input.value.trim()), (input.value = '')) : A.listen()) }, icon(0xE720))
    input.addEventListener('input', () => micBtn.replaceChildren(icon(input.value.trim() ? 0xE724 : 0xE720)))
    devBar = h('div', { class: 'devbar' })
    A.devices().then(drawDevices)
    root.replaceChildren(hero, convo, devBar, h('div', { class: 'composer' }, h('div', { class: 'box' }, input), micBtn),
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
    const time = (l) => l.at ? h('span', { class: 'time' }, new Date(l.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })) : null
    const kids = st.lines.map((l) =>
      l.who === 'you' ? h('div', { class: 'you' }, l.text, time(l))
        : l.who === 'me' ? h('div', { class: 'me' }, h('div', { class: 'who' }, (settings.name || 'Meshron').toUpperCase(), time(l)), md(l.text))
          : l.who === 'act' ? h('div', { class: 'act ' + (l.ok ? 'ok' : 'no') }, h('span', { class: 'ai' }, icon(actIcon(l.text))), h('span', {}, l.text.replace(/^([a-z ]+?) · /, (m, n) => n.charAt(0).toUpperCase() + n.slice(1) + ' · ')))
            : l.who === 'pic' ? h('div', { class: 'pic' }, h('img', { src: l.src, alt: l.text || 'Picture', title: 'Open', onClick: () => A.openPic(l.file) }), l.text ? h('div', { class: 'cap' }, l.text) : null)
            : h('div', { class: 'oops' }, icon(0xE7BA), ' ', l.text))
    if (st.mode === 'thinking') kids.push(h('div', { class: 'typing' }, h('i'), h('i'), h('i')))
    convo.replaceChildren(...kids)
    if (atEnd) convo.scrollTop = convo.scrollHeight
  }
  /** A Fluent icon for each kind of action in the conversation. */
  function actIcon (t) {
    const n = String(t).toLowerCase()
    const map = [[/^web search/, 0xE721], [/^open app|^switch|^open path|^open url/, 0xE8A7], [/^close app|^show desktop/, 0xE8BB], [/^type|^press keys/, 0xE765], [/^click/, 0xE962], [/^scroll/, 0xE74B],
      [/^read screen|^read document/, 0xE7B3], [/files|folder/, 0xE8B7], [/^delete/, 0xE74D], [/timer/, 0xE916], [/reminder/, 0xE823], [/task/, 0xE73E], [/volume|media/, 0xE767],
      [/battery/, 0xE83F], [/lock/, 0xE72E], [/showcase/, 0xE9D9], [/window/, 0xE737]]
    for (const [re, code] of map) if (re.test(n)) return code
    return 0xE945
  }

  /** Light formatting for replies: paragraphs, bullet and numbered lists, headings, bold, italics, code and links. No raw asterisks. */
  function md (text) {
    const frag = document.createDocumentFragment()
    const inline = (str) => {
      const span = document.createElement('span')
      const re = /(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\*[^*\s][^*]*\*|\[[^\]]+\]\(https?:\/\/[^)\s]+\))/g
      let last = 0; let m
      while ((m = re.exec(str))) {
        span.append(str.slice(last, m.index))
        const t = m[0]; let el
        if (t.startsWith('**') || t.startsWith('__')) { el = document.createElement('b'); el.textContent = t.slice(2, -2) }
        else if (t.startsWith('`')) { el = document.createElement('code'); el.textContent = t.slice(1, -1) }
        else if (t.startsWith('[')) { const mm = /\[([^\]]+)\]\(([^)]+)\)/.exec(t); el = document.createElement('a'); el.textContent = mm[1]; el.href = mm[2]; el.target = '_blank' }
        else { el = document.createElement('em'); el.textContent = t.slice(1, -1) }
        span.append(el); last = m.index + t.length
      }
      span.append(str.slice(last).replace(/\*\*/g, ''))
      return span
    }
    let list = null
    for (const raw of String(text || '').replace(/\r/g, '').split('\n')) {
      const l = raw.trim()
      if (!l) { list = null; continue }
      let m
      if ((m = /^#{1,4}\s+(.*)$/.exec(l)) || (m = /^\*\*([^*]+)\*\*:?$/.exec(l))) { list = null; const hd = document.createElement('div'); hd.className = 'mh'; hd.textContent = m[1].replace(/\*/g, ''); frag.append(hd); continue }
      if ((m = /^(?:[-*•]|\d+[.)])\s+(.*)$/.exec(l))) {
        const ordered = /^\d/.test(l)
        if (!list || list.tagName !== (ordered ? 'OL' : 'UL')) { list = document.createElement(ordered ? 'ol' : 'ul'); frag.append(list) }
        const li = document.createElement('li'); li.append(inline(m[1])); list.append(li); continue
      }
      list = null
      const p = document.createElement('p'); p.append(inline(l)); frag.append(p)
    }
    return frag
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
      [['george', 'George · warm, British'], ['daniel', 'Daniel · newsreader, British'], ['brian', 'Brian · deep, American'], ['adam', 'Adam · American'], ['sarah', 'Sarah · soft, American'], ['charlotte', 'Charlotte · Swedish-English'], ['lily', 'Lily · British'], ['rachel', 'Rachel · calm, American'], ['ms:ava', 'Ava · Microsoft, American'], ['ms:andrew', 'Andrew · Microsoft, American'], ['ms:emma', 'Emma · Microsoft, American'], ['ms:brian', 'Brian · Microsoft, American'], ['ms:ryan', 'Ryan · Microsoft, British'], ['ms:sonia', 'Sonia · Microsoft, British'], ['ms:jenny', 'Jenny · Microsoft, American'], ['ms:guy', 'Guy · Microsoft, American'], ['kokoro:af_heart', 'Kylie · on this PC (Kokoro)'], ['kokoro:af_bella', 'Glauxnycti · on this PC (Kokoro)'], ['kokoro:am_michael', 'Alvin · on this PC (Kokoro)'], ['kokoro:am_fenrir', 'Clement · on this PC (Kokoro)'], ['kokoro:am_puck', 'Xalvxn · on this PC (Kokoro)'], ['kokoro:bm_george', 'Kadmiel · British, on this PC (Kokoro)'], ['kokoro:bm_fable', 'Dhorpas · British, on this PC (Kokoro)']]
        .map(([v, l]) => h('option', { value: v, selected: settings.studioVoice === v }, l)))
    const seg = h('div', { class: 'seg' }, [[false, 'Push to talk'], [true, 'Always listening']].map(([v, l]) =>
      h('button', { class: settings.always === v ? 'on' : '', onClick: async () => { settings = await A.setSettings({ always: v }); settingsPage() } }, l)))
    root.replaceChildren(h('div', { class: 'page' },
      h('div', { class: 'pagehead' }, h('button', { class: 'back', title: 'Back to chat', onClick: () => { page = 'home'; draw() } }, icon(0xE72B)), h('h1', {}, 'Settings')),
      h('div', { class: 'group' }, 'Name'),
      card(0xE8D4, 'What it\'s called', 'Also the wake word ("Hey ' + (settings.name || 'Meshron') + '"). Short, clear names like Nova, Jarvis or Atlas are heard best.', nameField()),
      h('div', { class: 'group' }, 'Talking'),
      card(0xE765, 'Shortcut', 'Starts listening from anywhere. Click and press your own combo.', hotkeyField()),
      card(0xE720, 'Listening', settings.always ? 'Say "Hey ' + (settings.name || 'Meshron') + '" any time (Windows\' offline speech engine listens for the phrase only).' : 'Press the shortcut, click the orb or the mic.', seg),
      settings.always ? card(0xE9D9, 'Last heard', h('span', { id: 'heard' }, 'Nothing yet. Say "Hey ' + (settings.name || 'Meshron') + '" and watch here. If nothing shows, Windows is listening to another microphone: set your mic as the default in Sound settings.'), h('button', { class: 'btn', onClick: () => fetchHeard() }, 'Refresh')) : null,
      card(0xE767, 'Speak replies', 'Read answers out loud.', toggle('speak')),
      card(0xE8D6, 'Studio voice', 'A lifelike AI voice. Off: Windows\' own voice.', toggle('studio')),
      card(0xE77B, 'Studio voice', 'Who speaks.', voice),
      h('div', { class: 'group' }, 'Look'),
      h('div', { class: 'look' },
        h('div', { class: 'looktitle' }, 'Orb skin', h('span', {}, 'Faces show feelings and sit at the top of the screen like an island. Companions live on your screen and sit on top of your apps: Strider walks, Wisp flies, Drift floats, Bolt jet-flies. Talk to them as usual, or say "come here", "sit", "dance" or "take a nap".')),
        h('div', { class: 'skins' }, window.Reactor.SKINS.map((k) => {
          const c = h('canvas')
          const r = window.Reactor(c); r.skin = k.id; r.mode = 'idle'
          const b = h('button', { class: 'skin' + ((settings.skin || 'reactor') === k.id ? ' on' : ''), title: k.name,
            onMouseenter: () => { r.mode = 'speaking'; if (k.face) r.feel('happy', 1600) }, onMouseleave: () => { r.mode = 'idle' },
            onClick: async () => { settings = await A.setSettings({ skin: k.id }); applyLook(settings); for (const x of document.querySelectorAll('.skin')) x.classList.toggle('on', x === b) } },
          c, h('span', {}, k.name), k.pal ? h('em', {}, 'Companion') : k.island ? h('em', {}, 'Island') : k.face ? h('em', {}, 'Face') : null)
          return b
        })),
        h('div', { class: 'looktitle' }, 'Theme', h('span', {}, 'The colours of this window.')),
        h('div', { class: 'themes' }, THEMES.map(([id, name, a, b2]) => h('button', { class: 'theme' + ((settings.theme || 'midnight') === id ? ' on' : ''), style: '--a:' + a + ';--b:' + b2,
          onClick: async (e) => { const el = e.currentTarget; settings = await A.setSettings({ theme: id }); applyLook(settings); for (const x of document.querySelectorAll('.theme')) x.classList.toggle('on', x === el) } }, h('i', {}), name)))),
      h('div', { class: 'group' }, 'On screen'),
      card(0xE703, 'Control from my other devices', 'Your phone or other PCs signed in to the same account can give this PC commands ("on my PC, open Spotify"). Anything risky asks on the device that sent it.', toggle('remote')),
      card(0xE8F4, 'Hide from screenshots', 'The chat, the orb and showcases won\'t appear in screenshots, recordings or screen sharing.', toggle('hidden')),
      card(0xE7F4, 'Floating orb', 'Stays over every app. Click to talk, drag to move, right-click to open this window.', toggle('orb')),
      card(0xE7E8, 'Start with Windows', 'Quietly in the tray, ready for your shortcut.', toggle('startup')),
      h('div', { class: 'group' }, 'Account'),
      card(0xE77B, 'Signed in as @' + (st.user || ''), 'Meshron account', h('button', { class: 'btn danger', onClick: async () => { await A.signOut(); st = await A.state(); page = 'home'; draw() } }, 'Sign out')),
      h('div', { class: 'group' }, 'What it can do'),
      h('div', { class: 'sub', style: 'line-height:1.6' }, 'Open, switch and close apps, show the desktop, type, press shortcuts, click buttons by name, scroll, read your screen, make folders and tidy files (deletes go to the Recycle Bin after you approve), timers, reminders, tasks, volume, music controls, battery, lock the PC, read documents and pictures, and look things up on the web.')))
  }

  const THEMES = [['midnight', 'Midnight', '#22D3C5', '#6E7BFF'], ['nebula', 'Nebula', '#B57BFF', '#FF6BD6'], ['ember', 'Ember', '#FF8A3D', '#FF4D5E'], ['forest', 'Forest', '#3DDC84', '#B6F36A'], ['ocean', 'Ocean', '#38BDF8', '#2563EB'], ['gold', 'Gold', '#FFD86B', '#FFB020'], ['mono', 'Mono', '#E6EDF5', '#7A8596']]

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
    if (ev === 'settings') { settings = data; applyLook(data) }
    if (ev === 'devices') drawDevices(data)
    if (ev === 'heard') { const el = document.getElementById('heard'); if (el) el.textContent = data }
  })
  ;(async () => { settings = await A.settings(); applyLook(settings); st = await A.state(); document.querySelector('.appname').textContent = settings.name || 'Meshron Assistant'; draw(); renderApproval() })()
})()
