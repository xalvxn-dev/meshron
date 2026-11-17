// Meshron Mod for Windows and iPhone: the same moderator app as Android (modapp/ModActivity.kt).
// Signs in with the Meshron username and password; the password never leaves the device (it
// becomes the Argon2id auth key, like the Meshron app). The auth key is kept by the platform's
// secure store when there is one (window.ModStore: Windows safeStorage / iPhone Keychain).

;(function () {
  'use strict'
  // ?api=http://127.0.0.1:8799 for local testing (only when the page itself is local).
  const BASE = (/^(localhost|127.0.0.1)$/.test(location.hostname) && new URLSearchParams(location.search).get('api')) || 'https://meshron-accounts.meshron.workers.dev'
  const app = document.getElementById('app')

  // ---- Tiny DOM helper ----
  function h (tag, props, ...kids) {
    const el = document.createElement(tag)
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue
      if (k === 'class') el.className = v
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v)
      else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v)
      else if (k === 'value') el.value = v
      else el.setAttribute(k, v === true ? '' : v)
    }
    for (const c of kids.flat(Infinity)) if (c != null && c !== false) el.append(c.nodeType ? c : String(c))
    return el
  }
  const ago = (ts) => { const m = Math.floor((Date.now() - ts) / 60000); return m < 1 ? 'just now' : m < 60 ? m + 'm ago' : m < 1440 ? Math.floor(m / 60) + 'h ago' : Math.floor(m / 1440) + 'd ago' }
  const short = (id) => (id && id.length > 12 ? id.slice(0, 6) + '…' + id.slice(-4) : id || '')
  const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : '')
  const tag = (label, color) => h('span', { class: 'tag', style: { color } }, label)
  const C = { accent: '#7C95FF', danger: '#F0545C', good: '#2EC98A', warn: '#F5A524', text3: '#656E82', gold: '#FFC83D', silver: '#C9CED6', mod: '#B57BFF', blue: '#4F9BFF' }
  let toastTimer = null
  function toast (t) {
    document.querySelectorAll('.toast').forEach((x) => x.remove())
    const el = h('div', { class: 'toast' }, t); document.body.append(el)
    clearTimeout(toastTimer); toastTimer = setTimeout(() => el.remove(), 2500)
  }

  // ---- Secure store ----
  const store = window.ModStore || {
    get: async (k) => { try { return localStorage.getItem('mod.' + k) } catch { return null } },
    set: async (k, v) => { try { if (v == null) localStorage.removeItem('mod.' + k); else localStorage.setItem('mod.' + k, v) } catch {} }
  }

  // ---- API ----
  let username = ''
  let auth = ''
  class ApiError extends Error { constructor (m, status) { super(m); this.status = status } }
  async function post (path, body, retry = 1) {
    let r
    try { r = await fetch(BASE + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }) } catch {
      if (retry > 0) { await new Promise((ok) => setTimeout(ok, 800)); return post(path, body, retry - 1) }
      throw new ApiError('No connection. Check the internet and try again.', 0)
    }
    const o = await r.json().catch(() => ({}))
    // A hiccup on the server's side (no answer from Meshron): try once more before showing it.
    if (r.status >= 500 && !o.error && retry > 0) { await new Promise((ok) => setTimeout(ok, 800)); return post(path, body, retry - 1) }
    if (!r.ok) throw new ApiError(o.error || (r.status >= 500 ? "Meshron's server didn't answer. Try again in a moment." : 'Something went wrong (' + r.status + ')'), r.status)
    return o
  }
  const me = (path, body) => post(path, Object.assign({}, body || {}, { username, authKey: auth }))
  async function authKey (u, password) {
    const salt = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode('meshron-account-v1:' + u))).slice(0, 16)
    const out = await window.hashwasm.argon2id({ password, salt, parallelism: 1, iterations: 3, memorySize: 65536, hashLength: 64, outputType: 'binary' })
    return [...out.slice(0, 32)].map((b) => b.toString(16).padStart(2, '0')).join('')
  }
  async function signIn (user, password) {
    const u = user.trim().replace(/^@/, '').toLowerCase()
    const a = await authKey(u, password)
    const role = (await post('/v1/mod/me', { username: u, authKey: a })).role
    if (role !== 'admin' && role !== 'mod') throw new ApiError(!role ? "This account isn't a Meshron moderator." : 'No access', 403)
    username = u; auth = a
    await store.set('u', u); await store.set('a', a)
  }
  async function signOut () { username = ''; auth = ''; await store.set('u', null); await store.set('a', null); showSignIn() }

  // ---- Sign in ----
  function showSignIn () {
    const user = h('input', { class: 'field', placeholder: '@username', autocapitalize: 'none', autocomplete: 'username', spellcheck: 'false', maxlength: 30 })
    const pw = h('input', { class: 'field', type: 'password', placeholder: 'Password', autocomplete: 'current-password' })
    const err = h('div', { class: 'err' })
    const go = h('button', { class: 'big' }, 'Sign in')
    const sync = () => { go.disabled = !user.value.trim() || !pw.value }
    user.oninput = sync; pw.oninput = sync; sync()
    const submit = async () => {
      if (go.disabled) return
      go.disabled = true; go.textContent = 'Signing in…'; err.textContent = ''
      try { await signIn(user.value, pw.value); pw.value = ''; showHome() } catch (e) { err.textContent = e.message; go.textContent = 'Sign in'; sync() }
    }
    go.onclick = submit
    pw.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit() })
    app.replaceChildren(h('div', { class: 'signin' },
      h('div', { class: 'logo' }, '🛡'), h('h1', {}, 'Meshron Mod'),
      h('div', { class: 'dim center' }, "For Meshron's moderators. Sign in with your Meshron username and password."),
      h('div', { style: { height: '28px' } }), user, h('div', { style: { height: '10px' } }), pw, err, h('div', { style: { height: '18px' } }), go,
      h('p', { class: 'faint center', style: { marginTop: '18px' } }, 'Not a moderator? Apply in Meshron: Settings → Become a moderator.')))
    setTimeout(() => user.focus(), 50)
  }

  // ---- Home ----
  const TABS = [['queue', 'Reports', '⚑'], ['appeals', 'Appeals', '⚖'], ['removed', 'Removed', '♻'], ['activity', 'Activity', '🕘'], ['people', 'People', '👤'], ['more', 'More', '⋯']]
  const ACTION_WORDS = {
    hide: 'removed', restore: 'restored', warn: 'warned', ban: 'suspended', unban: 'lifted a suspension for', verify: 'verified', unverify: 'removed the check from',
    'deny-verify': 'declined verification for', 'deny-appeal': 'kept a removal after an appeal on', dismiss: 'dismissed a report', confirm: 'confirmed', notify: 'sent a notice to',
    role: 'changed the role of', 'approve-mod': 'approved a moderator application', 'deny-mod': 'declined a moderator application', 'ai-auto': 'switched AI moderation'
  }
  const QUICK = ['Spam', 'Harassment or bullying', 'Hate speech', 'Nudity or sexual content', 'Violence or threats', 'Scam or fraud', 'Impersonation', 'Misinformation']
  let tab = 'queue'
  let q = null
  let flags = 0
  let ceo = false
  let body, tabsEl, titleEl, subEl
  const filters = { queue: 'all', removed: 'all', activity: false }

  function showHome () {
    titleEl = h('div', { class: 'title' })
    subEl = h('div', { class: 'sub' })
    body = h('div', { class: 'body' })
    tabsEl = h('nav', { class: 'tabs' })
    app.replaceChildren(h('div', { class: 'main', style: { display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 } },
      h('div', { class: 'head' }, h('div', { class: 'logo small' }, '🛡'), h('div', { class: 'grow' }, titleEl, subEl), h('button', { class: 'icon-btn', title: 'Refresh', onClick: () => reload() }, '⟳')),
      body), tabsEl)
    reload()
  }
  async function reload () {
    render()
    me('/v1/mod/log', { flagged: true }).then((r) => { flags = (r.log || []).length; renderTabs() }).catch(() => {})
    try { q = await me('/v1/mod/queue'); ceo = q.role === 'admin' } catch (e) {
      if (e.status === 401 || e.status === 403) return signOut()
      if (!q) { body.replaceChildren(h('p', { class: 'err' }, e.message)); return }
      toast(e.message)
    }
    render()
  }
  function renderTabs () {
    tabsEl.replaceChildren(...TABS.map(([id, label, ic]) => {
      const n = id === 'queue' ? (q && q.reports || []).length : id === 'appeals' ? (q && q.appeals || []).length : id === 'activity' ? flags : 0
      return h('button', { class: tab === id ? 'on' : '', onClick: () => { tab = id; if (id === 'people') render(); else reload() } },
        h('span', { class: 'ic' }, ic), label, n > 0 ? h('span', { class: 'badge' }, n > 99 ? '99+' : n) : null)
    }))
  }
  function render () {
    renderTabs()
    titleEl.textContent = TABS.find((t) => t[0] === tab)[1]
    subEl.textContent = '@' + username + ' · ' + (ceo ? 'CEO' : 'Moderator')
    if (!q && tab !== 'people') { body.replaceChildren(h('div', { class: 'spinner' })); return }
    const scroll = body.scrollTop
    const view = tab === 'queue' ? queueTab() : tab === 'appeals' ? appealsTab() : tab === 'removed' ? removedTab() : tab === 'activity' ? activityTab() : tab === 'people' ? peopleTab() : moreTab()
    body.replaceChildren(view)
    body.scrollTop = scroll
  }
  async function act (action, extra, done) {
    try { await me('/v1/mod/act', Object.assign({}, extra, { action })); toast(done); reload(); return true } catch (e) { toast(e.message); return false }
  }
  const empty = (title, sub) => h('div', { class: 'empty' }, h('div', { class: 'ic' }, '🛡'), h('b', {}, title), h('div', { class: 'dim', style: { fontSize: '13px', marginTop: '4px' } }, sub))
  const pill = (label, kind, onClick) => h('button', { class: 'pill ' + (kind || ''), onClick }, label)
  const chip = (label, on, onClick) => h('button', { class: 'chip' + (on ? ' on' : ''), onClick }, label)

  // Reports
  function queueTab () {
    const all = q.reports || []
    const f = filters.queue
    const list = f === 'ai' ? all.filter((r) => r.ai && r.ai.action && r.ai.action !== 'none' && r.status === 'actioned') : f === 'person' ? all.filter((r) => r.status !== 'actioned') : all
    const set = (v) => () => { filters.queue = v; render() }
    return h('div', { class: 'list' },
      h('div', { class: 'chips' }, chip('All ' + all.length, f === 'all', set('all')), chip('Needs a person', f === 'person', set('person')), chip('AI already acted', f === 'ai', set('ai'))),
      list.length ? list.map((r) => {
        const ai = r.ai
        const ex = () => ({ report: r.id, kind: r.kind, target: r.target, owner: r.owner })
        const content = r.content || r.name
        return h('div', { class: 'card' },
          h('div', { class: 'row' }, tag(cap(r.kind), C.accent), h('b', { class: 'grow' }, r.reason), h('span', { class: 'faint' }, ago(r.ts))),
          content ? h('div', { class: 'quote' }, '“' + String(content).slice(0, 400) + '”') : null,
          r.details ? h('div', { class: 'dim', style: { fontSize: '13px', marginTop: '6px' } }, 'Reporter: ' + r.details) : null,
          ai ? h('div', { class: 'row', style: { marginTop: '8px' } }, tag('AI: ' + ai.action + (ai.human ? ' · unsure' : ''), ai.action === 'none' ? C.good : C.warn), h('span', { class: 'dim', style: { fontSize: '12px' } }, ' ' + (ai.category || '') + ' · severity ' + (ai.severity || 0))) : null,
          ai && ai.reason ? h('div', { class: 'dim', style: { fontSize: '13px', marginTop: '4px' } }, ai.reason) : null,
          h('div', { class: 'faint', style: { marginTop: '6px', fontSize: '12px' } }, 'Owner ' + short(r.owner || 'unknown') + (r.shot ? ' · screenshot emailed' : '')),
          h('div', { class: 'actions' },
            ['reel', 'comment', 'live', 'moment'].includes(r.kind) ? pill('Remove', 'danger', () => ask({ title: 'Remove it?', action: 'hide', extra: ex(), danger: true, done: 'Removed · the owner was told' })) : null,
            r.owner ? [pill('Warn', 'warn', () => ask({ title: 'Warn the owner?', action: 'warn', extra: Object.assign(ex(), { who: r.owner }), done: 'Warning sent' })),
              pill('Suspend', 'danger', () => ask({ title: 'Suspend the owner?', action: 'ban', extra: Object.assign(ex(), { who: r.owner }), danger: true, done: 'Account suspended' }))] : null,
            r.status === 'actioned' ? pill('Undo AI', '', () => act('restore', ex(), 'Restored')) : null,
            pill('Looks fine', '', () => act('dismiss', ex(), 'Dismissed'))))
      }) : empty('No reports here', 'New reports show up here. The AI moderator looks at each one first.'))
  }

  // Appeals
  function appealsTab () {
    const list = q.appeals || []
    return h('div', { class: 'list' }, list.length ? list.map((a) => {
      const ex = () => ({ kind: a.kind, target: a.target, who: a.owner })
      return h('div', { class: 'card' },
        h('div', { class: 'row' }, tag(cap(a.kind), C.accent), h('span', { class: 'grow' }), h('span', { class: 'faint' }, ago(a.ts))),
        h('div', { class: 'faint', style: { marginTop: '8px', fontSize: '12px' } }, 'Removed for'), h('div', { class: 'dim' }, a.reason),
        h('div', { class: 'faint', style: { marginTop: '8px', fontSize: '12px' } }, 'They say'), h('div', {}, a.text),
        h('div', { class: 'actions' },
          a.kind === 'person' ? pill('Lift suspension', 'good', () => act('unban', ex(), 'Account restored · they were told')) : pill('Restore it', 'good', () => act('restore', ex(), 'Restored · they were told')),
          pill('Keep removed', '', () => ask({ title: 'Keep the decision?', action: 'deny-appeal', extra: ex(), done: 'Appeal answered' }))))
    }) : empty('No appeals', 'When someone appeals a removal or a suspension, it shows here.'))
  }

  // Removed
  function removedTab () {
    const all = (q.hidden || []).slice().sort((a, b) => (b.ts || 0) - (a.ts || 0))
    const f = filters.removed
    const list = f === 'ai' ? all.filter((x) => x.by === 'ai') : f === 'people' ? all.filter((x) => x.by !== 'ai') : all
    const set = (v) => () => { filters.removed = v; render() }
    return h('div', { class: 'list' },
      h('div', { class: 'chips' }, chip('All ' + all.length, f === 'all', set('all')), chip('By AI', f === 'ai', set('ai')), chip('By moderators', f === 'people', set('people'))),
      list.length ? list.map((x) => h('div', { class: 'card' },
        h('div', { class: 'row' }, tag(cap(x.kind), C.accent), tag(x.by === 'ai' ? 'AI' : x.by, x.by === 'ai' ? C.warn : C.text3), h('span', { class: 'grow' }), h('span', { class: 'faint' }, ago(x.ts))),
        h('div', { style: { marginTop: '8px' } }, x.reason),
        h('div', { class: 'faint', style: { marginTop: '4px', fontSize: '12px' } }, (x.category || '') + ' · owner ' + short(x.owner)),
        h('div', { class: 'actions' }, pill('Restore', 'good', () => act('restore', { kind: x.kind, target: x.target }, 'Restored · the owner was told')))))
        : empty('Nothing removed', 'Removed videos, comments, lives and moments show here. Nothing is deleted: restoring brings it back.'))
  }

  // Activity
  function activityTab () {
    const box = h('div', { class: 'list' })
    const add = (...k) => box.append(...k.flat(Infinity).filter((x) => x != null && x !== false))
    const flaggedOnly = filters.activity
    const set = (v) => () => { filters.activity = v; render() }
    const listEl = h('div', { class: 'list' }, h('div', { class: 'spinner' }))
    add(h('div', { class: 'chips' }, chip('Everything', !flaggedOnly, set(false)), chip('Flagged by AI', flaggedOnly, set(true))),
      h('div', { class: 'faint', style: { margin: '0 4px' } }, 'Every moderator action is recorded here for all moderators to see. The AI checks each removal, warning and suspension; anything it thinks is unfair is flagged to the CEOs.'),
      listEl)
    me('/v1/mod/log', { flagged: flaggedOnly }).then((r) => {
      const log = r.log || []
      if (flaggedOnly) { flags = log.length; renderTabs() }
      listEl.replaceChildren(...(log.length ? log.map(logCard) : [empty(flaggedOnly ? 'Nothing flagged' : 'No actions yet', flaggedOnly ? "The AI hasn't found any moderator decision that looks unfair." : 'Moderator actions show up here as they happen.')]))
    }).catch((e) => listEl.replaceChildren(h('p', { class: 'err' }, e.message)))
    return box
  }
  function logCard (e) {
    const check = e.check
    const flagged = check && check.fair === false && !e.cleared
    const target = e.kind ? 'a ' + e.kind : e.who ? short(e.who) : ''
    let verdict = null
    if (!check && ['hide', 'warn', 'ban', 'deny-appeal'].includes(e.action)) verdict = h('div', { class: 'faint', style: { marginTop: '6px', fontSize: '12px' } }, 'AI check unavailable')
    else if (check && check.fair !== false) verdict = h('div', { style: { marginTop: '8px' } }, tag('AI: looks fair', C.good))
    else if (check) verdict = h('div', { style: { marginTop: '8px' } }, tag(flagged ? 'AI: possibly unfair' : 'AI flag · reviewed by ' + e.cleared, flagged ? C.danger : C.text3), h('div', { style: { fontSize: '13px', marginTop: '4px' } }, check.concern || ''))
    return h('div', { class: 'card' + (flagged ? ' flagged' : '') },
      h('div', { class: 'row' }, h('b', {}, e.by), e.role === 'admin' ? tag('CEO', C.accent) : null, h('span', { class: 'grow' }), h('span', { class: 'faint' }, ago(e.ts))),
      h('div', { style: { marginTop: '4px' } }, (ACTION_WORDS[e.action] || e.action) + (target ? ' ' + target : '')),
      e.reason ? h('div', { class: 'dim', style: { fontSize: '13px', marginTop: '4px' } }, 'Reason: ' + e.reason) : null,
      e.content ? h('div', { class: 'quote dim', style: { fontSize: '13px' } }, '“' + String(e.content).slice(0, 300) + '”') : null,
      verdict,
      ceo && flagged ? h('div', { class: 'actions' },
        ['hide', 'deny-appeal'].includes(e.action) ? pill('Undo: restore it', 'good', () => act('restore', { kind: e.kind, target: e.target }, 'Restored · the owner was told')) : null,
        e.action === 'ban' ? pill('Undo: lift suspension', 'good', () => act('unban', { who: e.who }, 'Suspension lifted')) : null,
        pill('It was fine', '', () => act('clear-flag', { target: e.id }, 'Marked as reviewed')),
        e.role === 'mod' ? pill('Remove ' + e.by, 'danger', () => act('role', { who: String(e.by).replace(/^@/, ''), role: '', reason: "Moderator decisions broke Meshron's moderation rules." }, 'Removed from the team')) : null) : null)
  }

  // People
  let peopleQuery = ''
  function peopleTab () {
    const input = h('input', { class: 'field', placeholder: 'Search @username', value: peopleQuery, autocapitalize: 'none', spellcheck: 'false', maxlength: 70 })
    const results = h('div', { class: 'list', style: { marginTop: '10px' } })
    let timer = null
    const run = async () => {
      const t = input.value.trim().replace(/^@/, '')
      peopleQuery = input.value
      if (!t) { results.replaceChildren(empty('Find someone', 'Type the start of their @username. Tap a name to see their whole record: reports, removals, warnings, suspensions and appeals.')); return }
      results.replaceChildren(h('div', { class: 'spinner' }))
      try {
        const list = (await me('/v1/mod/search', { q: t })).people || []
        if (input.value.trim().replace(/^@/, '') !== t) return
        results.replaceChildren(...(list.length ? list.map((r) => h('button', { class: 'person-row', onClick: () => openPerson(r.username) },
          h('div', { class: 'av' }, (r.username || '?')[0].toUpperCase()), h('div', { class: 'grow', style: { flex: 1 } }, h('b', {}, '@' + r.username), roleTags(r)), h('span', { class: 'faint', style: { fontSize: '24px' } }, '›')))
          : [h('p', { class: 'faint' }, 'Nobody\'s username starts with "' + t + '".')]))
      } catch (e) { results.replaceChildren(h('p', { class: 'err' }, e.message)) }
    }
    input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(run, 300) })
    run()
    setTimeout(() => input.focus(), 30)
    return h('div', { class: 'list' }, input, results)
  }
  function roleTags (x) {
    const t = []
    if (x.title === 'founder') t.push(tag('CEO · Founder', C.gold))
    else if (x.title === 'cofounder') t.push(tag('Co-CEO · Co-founder', C.silver))
    else if (x.title === 'admin') t.push(tag('Admin', C.accent))
    else if (x.title === 'ai') t.push(tag('Meshron AI', C.accent))
    if (x.role === 'mod') t.push(tag('Moderator', C.mod))
    if (x.verified) t.push(tag('Verified ✓', C.blue))
    if (x.id && !x.title && !x.role && !x.verified) t.push(tag('Member', C.text3))
    return h('div', { class: 'row', style: { marginTop: '4px', flexWrap: 'wrap' } }, t)
  }

  // A full page (back arrow + title)
  function page (title, content) {
    const el = h('div', { class: 'page' })
    const close = () => { el.remove(); document.removeEventListener('keydown', esc) }
    const esc = (e) => { if (e.key === 'Escape') close() }
    document.addEventListener('keydown', esc)
    el.append(h('div', { class: 'phead' }, h('button', { class: 'icon-btn', style: { color: 'var(--text)' }, title: 'Back', onClick: close }, '←'), h('b', {}, title)), h('div', { class: 'pbody' }, content))
    document.body.append(el)
    return { el, close, set: (c) => el.querySelector('.pbody').replaceChildren(c) }
  }

  async function openPerson (name) {
    const p = page('@' + name, h('div', { class: 'spinner' }))
    const load = async () => {
      try { p.set(personView(await me('/v1/mod/person', { who: name }), load)) } catch (e) { p.set(h('p', { class: 'err' }, e.message)) }
    }
    load()
  }
  function personView (x, refresh) {
    const id = x.id; const name = x.username; const banned = x.banned
    const reports = x.reports || []; const hidden = x.hidden || []; const history = x.history || []; const appeals = x.appeals || []
    const doAct = async (action, extra, done) => { if (await act(action, extra, done)) refresh() }
    const doAsk = (o) => ask(Object.assign(o, { after: refresh }))
    const card = (...k) => h('div', { class: 'card', style: { marginBottom: '8px' } }, ...k)
    return h('div', {},
      h('div', { class: 'center', style: { display: 'flex', flexDirection: 'column', alignItems: 'center' } },
        h('div', { class: 'av big' }, (name || '?')[0].toUpperCase()), h('div', { style: { fontSize: '22px', fontWeight: 800, marginTop: '10px' } }, name ? '@' + name : 'No username'),
        h('div', { class: 'faint' }, short(id)), roleTags(x)),
      h('div', { class: 'stats' },
        h('div', { class: 'stat' }, h('b', { style: { color: banned ? C.danger : C.good, fontSize: '13px' } }, banned ? 'Suspended' : 'Active'), h('span', {}, 'Status')),
        h('div', { class: 'stat' }, h('b', { style: { color: x.strikes > 0 ? C.warn : '' } }, x.strikes || 0), h('span', {}, 'Strikes')),
        h('div', { class: 'stat' }, h('b', { style: { color: reports.length ? C.warn : '' } }, reports.length), h('span', {}, 'Reports')),
        h('div', { class: 'stat' }, h('b', {}, history.filter((e) => e.action === 'hide').length), h('span', {}, 'Removals'))),
      x.joined ? h('div', { class: 'faint', style: { margin: '8px 4px 0' } }, 'Account since ' + new Date(x.joined).toLocaleDateString()) : null,
      banned ? h('div', { style: { color: C.danger, fontSize: '13px', margin: '8px 4px 0' } }, 'Suspended: ' + (banned.reason || '')) : null,
      h('div', { class: 'section' }, 'Actions'),
      h('div', { class: 'row', style: { gap: '8px' } },
        h('button', { class: 'btn2 warn', onClick: () => doAsk({ title: 'Warn @' + name, action: 'warn', extra: { who: id }, done: 'Warning sent' }) }, 'Warn'),
        banned ? h('button', { class: 'btn2 good', onClick: () => doAct('unban', { who: id }, 'Suspension lifted') }, 'Lift suspension')
          : h('button', { class: 'btn2 danger', onClick: () => doAsk({ title: 'Suspend @' + name, action: 'ban', extra: { who: id }, danger: true, done: 'Suspended' }) }, 'Suspend')),
      h('div', { class: 'row', style: { gap: '8px', marginTop: '8px' } },
        x.verified ? h('button', { class: 'btn2', onClick: () => doAsk({ title: "Remove @" + name + "'s verified check", action: 'unverify', extra: { who: id }, done: 'Check removed' }) }, 'Remove check')
          : h('button', { class: 'btn2 accent', onClick: () => doAct('verify', { who: id }, 'Verified') }, 'Verify ✓'),
        h('button', { class: 'btn2', onClick: () => doAsk({ title: 'Message @' + name, action: 'notify', extra: { who: id, reason: 'From Meshron' }, field: 'notice', done: 'Notice sent' }) }, 'Send a notice')),
      h('div', { class: 'section' }, 'Reports about them (' + reports.length + ')'),
      reports.length ? reports.slice(0, 30).map((r) => card(
        h('div', { class: 'row' }, tag(cap(r.kind), C.accent), h('b', { class: 'grow' }, ' ' + r.reason), h('span', { class: 'faint' }, ago(r.ts))),
        r.details ? h('div', { class: 'dim', style: { fontSize: '13px', marginTop: '4px' } }, r.details) : null,
        h('div', { class: 'faint', style: { marginTop: '4px', fontSize: '12px' } }, 'Outcome: ' + r.status + (r.ai ? ' · AI: ' + r.ai.action + ' (' + (r.ai.category || '') + ')' : ''))))
        : h('div', { class: 'faint', style: { margin: '0 4px' } }, 'Nobody has reported them.'),
      h('div', { class: 'section' }, 'Removed right now (' + hidden.length + ')'),
      hidden.length ? hidden.map((x2) => card(h('div', {}, cap(x2.kind) + ' · ' + x2.reason), h('div', { class: 'faint', style: { marginTop: '4px', fontSize: '12px' } }, 'By ' + (x2.by === 'ai' ? 'AI' : x2.by) + ' · ' + ago(x2.ts)),
        h('div', { class: 'actions' }, pill('Restore', 'good', () => doAct('restore', { kind: x2.kind, target: x2.target }, 'Restored')))))
        : h('div', { class: 'faint', style: { margin: '0 4px' } }, 'Nothing of theirs is removed.'),
      h('div', { class: 'section' }, 'Moderation history (' + history.length + ')'),
      history.length ? history.slice(0, 50).map((e) => card(
        h('div', { class: 'row' }, h('span', { class: 'grow' }, e.by + ' ' + (ACTION_WORDS[e.action] || e.action) + (e.kind ? ' a ' + e.kind : '')), h('span', { class: 'faint' }, ago(e.ts))),
        e.reason ? h('div', { class: 'dim', style: { fontSize: '13px', marginTop: '4px' } }, e.reason) : null,
        e.flagged ? h('div', { style: { marginTop: '6px' } }, tag('AI flagged this action', C.danger)) : null))
        : h('div', { class: 'faint', style: { margin: '0 4px' } }, 'No moderator has acted on them.'),
      appeals.length ? [h('div', { class: 'section' }, 'Their appeals (' + appeals.length + ')'), appeals.map((a) => card(h('b', {}, cap(a.kind) + ' · ' + a.status), h('div', { class: 'dim', style: { fontSize: '13px', marginTop: '4px' } }, a.text)))] : null)
  }

  // More
  function moreTab () {
    const box = h('div', { class: 'list' })
    const add = (...k) => box.append(...k.flat(Infinity).filter((x) => x != null && x !== false))
    const card = (...k) => h('div', { class: 'card' }, ...k)
    const ver = q.verifications || []
    add(h('div', { class: 'section' }, 'Verification requests'),
      ver.length ? ver.map((v) => card(h('b', {}, v.name + '  @' + v.username), h('div', { style: { color: C.accent, fontSize: '13px' } }, v.category), h('div', { class: 'dim', style: { marginTop: '4px' } }, v.reason),
        v.links ? h('div', { class: 'faint', style: { marginTop: '4px' } }, v.links) : null,
        h('div', { class: 'actions' }, pill('Verify ✓', 'good', () => act('verify', { who: v.id }, 'Verified · they were told')), pill('Not now', '', () => ask({ title: 'Decline verification?', action: 'deny-verify', extra: { who: v.id }, done: 'Answered' })))))
        : h('div', { class: 'faint', style: { margin: '0 4px' } }, 'No requests.'))
    if (ceo) {
      const on = q.aiAuto !== false
      const sw = h('button', { class: 'switch' + (on ? ' on' : ''), title: 'AI moderation', onClick: () => act('ai-auto', { reason: on ? 'off' : 'on' }, on ? 'AI moderation off' : 'AI moderation on') })
      add(h('div', { class: 'section' }, 'AI moderation'),
        card(h('div', { class: 'row', style: { gap: '12px' } }, h('div', { class: 'grow' }, h('b', {}, 'AI reviews every report first'),
          h('div', { class: 'dim', style: { fontSize: '13px' } }, on ? 'It can remove, warn or suspend right away; unsure cases come to you.' : 'Off: every report waits for a person.')), sw)))
      const apps = q.applications || []
      add(h('div', { class: 'section' }, 'Moderator applications'),
        apps.length ? apps.map((a) => card(h('b', {}, a.name + '  @' + a.username), h('div', { class: 'dim', style: { marginTop: '4px' } }, a.why),
          h('div', { class: 'faint', style: { marginTop: '4px' } }, 'Hours a week: ' + (a.hours || '—') + ' · Languages: ' + (a.languages || '—')),
          h('div', { class: 'actions' }, pill('Approve', 'good', () => act('approve-mod', { who: a.id }, '@' + a.username + ' is a moderator now')), pill('Decline', '', () => ask({ title: 'Decline the application?', action: 'deny-mod', extra: { who: a.id }, done: 'Answered' })))))
          : h('div', { class: 'faint', style: { margin: '0 4px' } }, 'No applications.'))
    }
    const team = q.team || []
    add(h('div', { class: 'section' }, 'The team (' + team.length + ')'),
      team.map((t) => h('div', { class: 'person-row', style: { cursor: 'pointer' }, onClick: () => openPerson(t.username) },
        h('div', { class: 'av' }, (t.username || '?')[0].toUpperCase()),
        h('div', { style: { flex: 1 } }, h('b', {}, '@' + t.username + (t.username === username ? ' (you)' : '')), roleTags(t)),
        ceo && t.role === 'mod' ? h('button', { class: 'pill danger', onClick: (e) => { e.stopPropagation(); ask({ title: 'Remove @' + t.username + ' as moderator', action: 'role', extra: { who: t.username, role: '' }, danger: true, done: 'Removed from the team' }) } }, 'Remove') : h('span', { class: 'faint', style: { fontSize: '24px' } }, '›'))),
      ceo ? h('button', { class: 'big', style: { marginTop: '4px' }, onClick: () => ask({ title: 'Add a moderator', action: 'role', extra: {}, field: 'who', done: 'Added to the team' }) }, 'Add a moderator') : null,
      h('div', { class: 'section' }, 'Account'),
      card(h('div', {}, 'Signed in as @' + username),
        h('div', { class: 'faint', style: { marginTop: '4px' } }, ceo ? "You're a CEO: you can add and remove moderators and turn AI moderation on or off. Every action is recorded with your username and checked by AI." : 'Only the CEOs can add or remove moderators. Every action is recorded with your username and checked by AI.'),
        h('div', { class: 'actions' }, pill('Sign out', '', signOut))))
    return box
  }

  // A decision that needs a reason (or a username / a message), as a full page.
  function ask ({ title, action, extra, field = 'reason', danger, done, after }) {
    let v = ''
    const input = field === 'notice' ? h('textarea', { class: 'field', placeholder: 'Message', maxlength: 1000 }) : h('input', { class: 'field', placeholder: field === 'who' ? '@username' : 'Or write a reason', maxlength: 200, autocapitalize: field === 'who' ? 'none' : null })
    const ok = h('button', { class: 'big ' + (danger ? 'danger' : 'plain'), disabled: true }, field === 'who' ? 'Add moderator' : field === 'notice' ? 'Send' : 'Confirm')
    const choices = h('div', {})
    const paint = () => {
      ok.disabled = !v.trim()
      if (field === 'reason') choices.replaceChildren(...QUICK.map((r) => h('button', { class: 'choice' + (v === r ? ' on' : ''), onClick: () => { v = r; input.value = ''; paint() } }, h('span', { class: 'dot' }), r)))
    }
    input.addEventListener('input', () => { v = input.value; paint() })
    const p = page(title, h('div', {},
      h('p', { class: 'dim' }, field === 'who' ? "Type their Meshron @username. They'll be told, and can open Settings → Moderator in Meshron. Only the CEOs can add or remove moderators."
        : field === 'notice' ? 'Your message appears in their Activity, from Meshron.' : 'The person reads this reason, and can appeal. Pick the rule that was broken, or write your own. Every action is logged and checked by AI.'),
      choices, input, h('div', { style: { height: '20px' } }), ok))
    ok.onclick = async () => {
      const value = v.trim(); if (!value) return
      const e = Object.assign({}, extra)
      if (field === 'who') { e.who = value.replace(/^@/, ''); e.role = 'mod' } else if (field === 'notice') e.text = value; else e.reason = value
      p.close()
      if (await act(action, e, done) && after) after()
    }
    paint()
    setTimeout(() => input.focus(), 30)
  }

  // ---- Start ----
  ;(async () => {
    username = (await store.get('u')) || ''
    auth = (await store.get('a')) || ''
    if (username && auth) showHome(); else showSignIn()
  })()
})()
