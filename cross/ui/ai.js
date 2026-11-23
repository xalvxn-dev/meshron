// Meshron AI for the shared screens (iPhone + Windows): the AI chat, automatic translation of
// other people's messages and comments, and @meshronai answers (signed by Meshron AI's own
// account and checked before they're shown). Same server and rules as Android.

;(function () {
  'use strict'
  const UI = window.MeshronUI
  const { h, M, modal, toast, icon } = UI
  const ACC = () => window.MeshronAccount
  const AI_ID = '1814b421f78a892ee898487af96ec45421cf50f996fae9ab4bf1f9754167824e'
  const PREFIX = 'meshron://ai/'
  const lang = (() => { try { return new Intl.DisplayNames(['en'], { type: 'language' }).of((navigator.language || 'en').split('-')[0]) || 'English' } catch { return 'English' } })()

  // ---- Clean AI text (no Markdown symbols) ----
  function clean (raw) {
    return String(raw || '').replace(/```[a-z]*/gi, '').split('\n').map((l) => l
      .replace(/^\s{0,3}#{1,6}\s*/, '').replace(/^\s*[-*+]\s+/, '• ').replace(/^\s*([-*_]\s*){3,}$/, '')).join('\n')
      .replace(/\*\*(.+?)\*\*|__(.+?)__/g, (_, a, b) => a || b).replace(/[*`]/g, '').replace(/\n{3,}/g, '\n\n').trim()
  }

  // ---- Translation (cached per message, like Android) ----
  const cache = new Map()
  function load (id) { try { const v = localStorage.getItem('tr:' + id); return v === null ? undefined : (v ? JSON.parse(v) : null) } catch { return undefined } }
  function save (id, v) { try { localStorage.setItem('tr:' + id, v ? JSON.stringify(v) : '') } catch {} }
  async function translate (id, text) {
    if (cache.has(id)) return cache.get(id)
    const stored = load(id)
    if (stored !== undefined) { cache.set(id, stored); return stored }
    if (!text || text.length < 2 || !/\p{L}/u.test(text) || text.startsWith('meshron://')) { cache.set(id, null); return null }
    if (!ACC().state || UI.st.anon || (UI.st.prefs && UI.st.prefs.settings && UI.st.prefs.settings.autoTranslate === false)) return null
    const p = ACC().authed('/v1/ai', { task: 'autotranslate', text, lang }).then((r) => {
      const v = r.same || !r.text ? null : { text: r.text, note: r.note || ('Translated from ' + r.from), original: r.original || 'See original' }
      cache.set(id, v); save(id, v); return v
    }).catch(() => null)
    cache.set(id, p)
    return p
  }
  /** Puts the translation into [textEl] and a tappable note after it. */
  function translateInto (id, text, textEl, color) {
    Promise.resolve(translate(id, text)).then((t) => {
      if (!t || !textEl.isConnected) return
      let showingOriginal = false
      textEl.textContent = t.text
      const note = h('div', { class: 'tr-note', style: { fontSize: '11px', opacity: 0.65, marginTop: '3px', cursor: 'pointer', color: color || 'inherit' } }, t.note + ' · ' + t.original)
      note.onclick = (e) => { e.stopPropagation(); showingOriginal = !showingOriginal; textEl.textContent = showingOriginal ? text : t.text; note.textContent = showingOriginal ? '↺ ' + t.note : t.note + ' · ' + t.original }
      textEl.after(note)
    })
  }

  // ---- @meshronai ----
  const tagged = (t) => /@meshronai/i.test(t || '')
  async function ask (ref, re, question, caption, creator, context) {
    const r = await ACC().authed('/v1/ai/ask', { ref, re, question, caption: caption || '', creator: creator || '', context: context || [] })
    const ok = await M.call('ai.verify', { ref, re, text: r.text, sig: r.sig })
    if (!ok) throw new Error("Meshron AI's answer couldn't be checked")
    return { re, text: r.text, sig: r.sig }
  }
  /** A group message carrying Meshron AI's answer: { re, text } when it checks out. */
  async function parseGroup (conv, body) {
    if (!String(body).startsWith(PREFIX)) return null
    try {
      const o = JSON.parse(body.slice(PREFIX.length))
      return (await M.call('ai.verify', { ref: conv, re: o.re, text: o.text, sig: o.sig })) ? o : null
    } catch { return null }
  }
  function aiAvatar (size) {
    return h('div', { class: 'ai-avatar', style: { width: size + 'px', height: size + 'px', borderRadius: '50%', display: 'grid', placeItems: 'center', background: 'linear-gradient(135deg,#4F7BFF,#19D3C5,#8B5CFF)', color: '#fff', fontSize: (size * 0.5) + 'px', flex: 'none' } }, '✦')
  }

  // ---- The Meshron AI chat ----
  const history = []
  function openChat () {
    if (!ACC().state) { toast('Create a Meshron account (Me → Create an account) to use Meshron AI'); return }
    const list = h('div', { class: 'ai-list', style: { display: 'flex', flexDirection: 'column', gap: '10px', maxHeight: '60vh', overflowY: 'auto', padding: '4px 2px' } })
    const input = h('textarea', { class: 'input', rows: 2, placeholder: 'Message Meshron AI', style: { resize: 'none' } })
    const send = h('button', { class: 'btn primary', style: { marginTop: '8px', width: '100%' } }, 'Send')
    function bubble (mine, text, image) {
      if (mine) return h('div', { style: { alignSelf: 'flex-end', maxWidth: '80%', background: 'linear-gradient(135deg,#4F7BFF,#19D3C5)', color: '#fff', padding: '10px 14px', borderRadius: '18px 18px 6px 18px', whiteSpace: 'pre-wrap' } }, text)
      return h('div', { style: { display: 'flex', gap: '8px', alignItems: 'flex-start', maxWidth: '90%' } }, aiAvatar(26),
        h('div', { class: 'selectable', style: { background: 'var(--s1, #1b1f2a)', padding: '10px 14px', borderRadius: '6px 18px 18px 18px', whiteSpace: 'pre-wrap', lineHeight: 1.45 } },
          image ? h('img', { src: image, alt: 'Picture made by Meshron AI', style: { display: 'block', width: '100%', maxWidth: '360px', borderRadius: '12px', marginBottom: text ? '8px' : 0 } }) : null,
          image ? h('a', { href: image, download: 'meshron-ai.png', class: 'dim', style: { display: 'inline-block', fontSize: '13px', marginBottom: '6px' } }, 'Save picture') : null,
          image ? h('br') : null,
          clean(text)))
    }
    function render () {
      list.replaceChildren(...(history.length ? history.map((t) => bubble(t.mine, t.text, t.image)) : [h('p', { class: 'dim', style: { textAlign: 'center' } }, 'Ask anything. What you type goes to the AI service, so don\'t share secrets.')]))
      list.scrollTop = list.scrollHeight
    }
    async function go () {
      const t = input.value.trim(); if (!t) return
      input.value = ''
      const before = history.slice(-12).map((x) => ({ role: x.mine ? 'user' : 'assistant', content: x.text }))
      history.push({ mine: true, text: t }); render()
      const typing = h('div', { class: 'dim' }, /(draw|paint|sketch|image|picture|logo|poster|wallpaper)/i.test(t) ? 'Meshron AI is making it…' : 'Meshron AI is thinking…'); list.appendChild(typing)
      try { const r = await ACC().authed('/v1/ai', { task: 'helper', text: t, history: before, pictures: true }); history.push({ mine: false, text: r.text, image: r.image }) } catch (e) { history.push({ mine: false, text: e.message || 'The AI is busy. Try again.' }) }
      render()
    }
    send.onclick = go
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); go() } })
    modal({ title: 'Meshron AI', wide: true, body: h('div', {}, h('p', { class: 'dim', style: { marginTop: 0 } }, 'Not end-to-end encrypted · not saved'), list, input, send) })
    render()
  }

  window.MeshronAI = { AI_ID, PREFIX, lang, clean, translate, translateInto, tagged, ask, parseGroup, aiAvatar, openChat }
})()
