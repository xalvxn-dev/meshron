// Meshron moderation for the shared screens (iPhone + Windows): reports (with a screenshot),
// what moderators hid, badges, notices from Meshron, appeals and verification requests.
// The host signs requests with this device's identity; the HTTPS calls are made here.

;(function () {
  'use strict'
  const UI = window.MeshronUI
  const { h, M, st, modal, toast, oops, icon } = UI

  // The account server. Override for testing with ?accounts=http://127.0.0.1:8799 (remembered).
  try { const q = new URLSearchParams(location.search).get('accounts'); if (q) localStorage.setItem('meshron.accountsUrl', q) } catch {}
  const ACCOUNTS = ((() => { try { return localStorage.getItem('meshron.accountsUrl') } catch { return null } })() || 'https://meshron-accounts.meshron.workers.dev').replace(/\/$/, '')

  async function post (path, body) {
    const r = await fetch(ACCOUNTS + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
    const o = await r.json().catch(() => ({}))
    if (!r.ok || o.error) throw new Error(o.error || 'Meshron moderation is unreachable')
    return o
  }
  async function signed (path, payload) {
    if (st.anon) throw new Error('Not available in anonymous mode')
    const s = await M.call('mod.sign', { path, body: JSON.stringify(payload) })
    return post(path, s)
  }

  // ---- Meshron AI's rule check, as things are posted ----
  // Comments, group messages, captions, descriptions, usernames, names and bios. A break shows a
  // warning at once (and lands in Activity). Captions, names, bios and comments aren't posted;
  // a group message is deleted for everyone, except small things in 18+ groups.
  const lang = (() => { try { return new Intl.DisplayNames(['en'], { type: 'language' }).of((navigator.language || 'en').split('-')[0]) || 'English' } catch { return 'English' } })()
  const KIND_NOTE = { comment: "Your comment wasn't posted.", group: 'Your message was flagged.', caption: "Your video wasn't posted.", description: "This description wasn't saved.", username: "This username wasn't saved.", name: "This name wasn't saved.", bio: "This bio wasn't saved." }
  async function check (kind, text, adult) {
    if (st.anon || !String(text || '').trim()) return null
    let o
    try {
      o = await Promise.race([signed('/v1/mod/check', { kind, text: String(text).slice(0, 2000), adult: !!adult, lang }), new Promise((_, no) => setTimeout(() => no(new Error('slow')), 8000))])
    } catch { return null } // the check never stops anyone by failing
    if (!o || o.ok !== false) return null
    const b = { kind, rule: o.rule || 'Community Guidelines', reason: o.reason || "This breaks Meshron's Community Guidelines.", severity: o.severity || 2 }
    showWarning(b)
    return b
  }
  function showWarning (b) {
    modal({ title: 'Warning from Meshron AI', body: h('div', {},
      h('span', { style: { display: 'inline-block', padding: '3px 10px', borderRadius: '8px', background: 'rgba(240,84,92,0.15)', color: '#F0545C', fontWeight: 700, fontSize: '13px' } }, b.rule),
      h('p', {}, b.reason),
      h('p', { class: 'dim', style: { fontSize: '13px' } }, (KIND_NOTE[b.kind] || '') + (b.kind === 'group' ? ' In groups for everyone it was deleted. ' : ' ') + "Repeated breaks can lead to a suspension. You'll find this warning in Activity.")),
    buttons: [{ label: 'OK, got it', kind: 'primary', onClick: (m) => m.close() }] })
  }
  class RuleBroken extends Error { constructor (b) { super(b.reason); this.rule = b } }
  async function guard (kind, text, adult) { const b = await check(kind, text, adult); if (b) throw new RuleBroken(b) }

  // Every post goes through here: check first (or right after, for group messages).
  const groupOf = (conv) => (st.groups || []).find((g) => g.conv === conv)
  const rawCall = M.call.bind(M)
  M.call = async (method, args) => {
    const a = args || {}
    switch (method) {
      case 'reels.comment': await guard('comment', a.text); break
      case 'reels.repost': if (a.on && a.quote && !a.cid) await guard('comment', a.quote); break
      case 'reels.end': await guard('caption', [a.title, a.cap].filter(Boolean).join('\n')); break
      case 'live.start': await guard('description', a.title); break
      case 'identity.rename': await guard('name', a.name); break
      case 'identity.setHandle': if (a.handle) await guard('username', a.handle); break
      case 'profile.set': { const me = (st.profiles && st.profiles.me) || {}; if (typeof a.bio === 'string' && a.bio.trim() !== (me.bio || '').trim()) await guard('bio', a.bio); break }
      case 'group.create': await guard('name', a.name, a.adult); break
      case 'group.info': { const g = groupOf('g:' + a.id) || {}; if (a.name && a.name.trim() !== g.name) await guard('name', a.name, g.adult); if (typeof a.bio === 'string' && a.bio.trim() !== (g.bio || '')) await guard('description', a.bio, g.adult); break }
    }
    const r = await rawCall(method, args)
    if (method === 'chat.send' && String(a.conv || '').startsWith('g:') && r) {
      const g = groupOf(a.conv) || {}
      check('group', a.text, g.adult).then((b) => { if (b && (!g.adult || b.severity >= 4)) rawCall('chat.unsend', { conv: a.conv, id: r }).catch(() => {}) })
    }
    return r
  }

  // ---- What moderators hid (cached 10 minutes) ----
  const hidCache = new Map()
  async function hiddenAmong (keys) {
    if (st.anon || !keys.length) return new Set()
    const t = Date.now()
    const unknown = [...new Set(keys)].filter((k) => !hidCache.has(k) || hidCache.get(k).at < t - 600000)
    for (let i = 0; i < unknown.length; i += 300) {
      const chunk = unknown.slice(i, i + 300)
      try {
        const r = await post('/v1/mod/status', { items: chunk })
        const hid = new Set((r.hidden || []).map((x) => x.key))
        for (const k of chunk) hidCache.set(k, { hidden: hid.has(k), at: t })
      } catch { break }
    }
    return new Set(keys.filter((k) => hidCache.get(k) && hidCache.get(k).hidden))
  }

  // ---- Badges ----
  const badges = new Map([['1814b421f78a892ee898487af96ec45421cf50f996fae9ab4bf1f9754167824e', { verified: true, mod: true, title: 'ai' }]]) // id -> { verified, mod, title } | null
  const asked = new Map()
  const waiting = new Set()
  let timer = null
  function wantBadges (ids) {
    for (const id of ids) if (/^[0-9a-f]{64}$/.test(id) && (!asked.has(id) || asked.get(id) < Date.now() - 600000)) waiting.add(id)
    if (!waiting.size || timer) return
    timer = setTimeout(async () => {
      timer = null
      const list = [...waiting]; waiting.clear()
      for (const id of list) asked.set(id, Date.now())
      try {
        const r = await post('/v1/badges', { people: list })
        for (const id of list) badges.set(id, (r.badges || {})[id] || null)
        document.querySelectorAll('[data-badge]').forEach((el) => fill(el))
      } catch {}
    }, 150)
  }
  /** The person's badges, when they're known (asks the server if needed). */
  function badgeOf (id) {
    if (badges.has(id)) return Promise.resolve(badges.get(id))
    wantBadges([id])
    return new Promise((resolve) => { let n = 0; const t = setInterval(() => { if (badges.has(id) || ++n > 40) { clearInterval(t); resolve(badges.get(id) || null) } }, 250) })
  }
  /** A span that fills with the person's badges (verified hexagon, moderator shield, founders' star). */
  function badgeEl (id, size = 14, full = false) {
    const el = h('span', { class: 'badges', 'data-badge': id, 'data-size': String(size), 'data-full': full ? '1' : '' })
    fill(el)
    wantBadges([id])
    return el
  }
  function fill (el) {
    const b = badges.get(el.dataset.badge)
    const size = Number(el.dataset.size) || 14
    el.replaceChildren()
    if (!b) return
    if (b.title === 'ai') {
      el.append(h('span', { class: 'b-ai', title: 'Meshron AI', style: { background: 'linear-gradient(135deg,#4F7BFF,#19D3C5,#8B5CFF)', color: '#fff', fontWeight: 900, fontSize: Math.round(size * 0.6) + 'px', padding: '0 ' + Math.round(size * 0.3) + 'px', borderRadius: '99px', lineHeight: size + 'px' } }, 'AI'))
    } else if (b.title) {
      const founder = b.title === 'founder'
      const t = founder ? 'CEO · Founder' : 'Co-CEO · Co-founder'
      // Gold for the CEO, silver for the co-CEO.
      el.append(h('span', { class: 'b-title ' + b.title, title: t, style: { background: founder ? 'linear-gradient(135deg,#FFF1B8,#FFC83D,#D99A00)' : 'linear-gradient(135deg,#FFFFFF,#E4E8EE,#B4BBC6,#7D8591)', color: founder ? '#3D2A00' : '#1F242C', borderRadius: '99px', padding: '0 6px', fontWeight: 800, fontSize: Math.round(size * 0.7) + 'px' } }, '★', el.dataset.full ? ' ' + t : ''))
    }
    if (b.verified) {
      const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
      s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('width', size); s.setAttribute('height', size); s.setAttribute('class', 'b-verified')
      s.innerHTML = '<title>Verified</title><defs><linearGradient id="mvg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#19D3C5"/><stop offset=".5" stop-color="#4F7BFF"/><stop offset="1" stop-color="#8B5CFF"/></linearGradient></defs>' +
        '<polygon points="12,0 22.4,6 22.4,18 12,24 1.6,18 1.6,6" fill="url(#mvg)" stroke="rgba(255,255,255,.35)" stroke-width="1.4"/>' +
        '<polyline points="7.2,12.5 10.8,15.8 17,9.1" fill="none" stroke="#fff" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round"/>'
      el.append(s)
    }
    if (b.mod) el.append(h('span', { class: 'b-mod', title: 'Meshron moderator', style: { width: size + 'px', height: size + 'px' } }, icon('shield', Math.round(size * 0.7))))
  }

  // ---- Reports ----
  const REASONS = ['Bullying or harassment', 'Hate speech or symbols', 'Nudity or sexual content', 'Violence or dangerous acts', 'Self-harm or suicide', 'Scam or fraud', 'Spam', 'False information', 'Child safety', 'Impersonation', 'Intellectual property', 'Something else']
  /** kind: reel | comment | person | group | message; owner: the identity it belongs to. */
  function report ({ kind, target, name, owner = '', content = '', page = '' }) {
    if (st.anon) return toast('Reports need your public profile')
    let shot = ''
    const body = h('div', {})
    function pick () {
      body.replaceChildren(
        h('p', { class: 'dim t-small' }, "Reports are private. Meshron's moderators review them and act if it breaks the rules."),
        ...REASONS.map((r) => h('button', { class: 'pick', style: { width: '100%' }, onClick: () => details(r) }, h('div', { class: 't-title' }, r))))
    }
    function details (reason) {
      const text = h('textarea', { class: 'field', rows: 3, placeholder: 'What happened? (optional)' })
      const file = h('input', { type: 'file', accept: 'image/*', hidden: true, onChange: async () => {
        const f = file.files[0]; if (!f) return
        shot = await shrink(f); shotBtn.textContent = 'Screenshot added ✓'
      } })
      const shotBtn = h('button', { class: 'btn soft', onClick: () => file.click() }, 'Add a screenshot')
      body.replaceChildren(h('div', { class: 't-title' }, reason), text, file, shotBtn,
        h('div', { style: { display: 'flex', gap: '8px', marginTop: '12px' } },
          h('button', { class: 'btn soft', onClick: pick }, 'Back'),
          h('button', { class: 'btn primary danger', onClick: async (e) => {
            e.target.disabled = true
            try {
              await signed('/v1/report', { kind, target, owner, name, reason, details: text.value, page, content, screenshot: shot })
              m.close(); toast("Thanks. Meshron's moderators will review it.")
            } catch (err) { e.target.disabled = false; oops(err) }
          } }, 'Submit report')))
    }
    const m = modal({ title: 'Report ' + (name || ''), body })
    pick()
  }
  async function shrink (file) {
    const img = await createImageBitmap(file)
    const scale = Math.min(1, 1400 / Math.max(img.width, img.height))
    const c = document.createElement('canvas'); c.width = Math.round(img.width * scale); c.height = Math.round(img.height * scale)
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height)
    for (let q = 0.75; q > 0.2; q -= 0.15) { const d = c.toDataURL('image/jpeg', q).split(',')[1]; if (d.length < 800000) return d }
    return ''
  }

  // ---- Notices from Meshron, appeals ----
  async function notices () {
    const r = await signed('/v1/mod/inbox', {})
    return { list: r.notices || [], banned: r.banned }
  }
  function appeal (n) {
    const text = h('textarea', { class: 'field', rows: 4, placeholder: 'Your side of the story' })
    const m = modal({
      title: n.kind === 'person' ? 'Appeal your suspension' : 'Appeal this removal',
      body: h('div', {}, h('p', { class: 'dim t-small' }, 'A person at Meshron reviews every appeal. If you win, everything comes back as it was.'), text),
      buttons: [{ label: 'Cancel', onClick: (m) => m.close() }, { label: 'Send appeal', kind: 'primary', onClick: async (m) => {
        if (!text.value.trim()) return
        try { await signed('/v1/mod/appeal', { kind: n.kind, target: n.target, text: text.value }); m.close(); toast("Appeal sent. We'll tell you here.") } catch (e) { oops(e) }
      } }]
    })
    return m
  }
  /** The "From Meshron" page: notices, suspension, appeals. */
  async function showNotices () {
    const box = h('div', {}, h('p', { class: 'dim' }, 'Loading…'))
    modal({ title: 'From Meshron', body: box, wide: true })
    try {
      const { list, banned } = await notices()
      box.replaceChildren(
        banned ? h('div', { class: 'card danger' }, h('div', { class: 't-title' }, 'Your account is suspended'), h('p', { class: 'dim' }, banned.reason || ''), h('button', { class: 'btn small soft', onClick: () => appeal({ kind: 'person', target: '' }) }, 'Appeal')) : null,
        list.length ? null : h('p', { class: 'dim' }, "Updates on your reports, and anything from Meshron's moderators, show up here."),
        ...list.map((n) => h('div', { class: 'card' },
          h('div', { style: { display: 'flex', gap: '8px' } }, h('div', { class: 't-title grow' }, n.title), h('span', { class: 'dim t-small' }, new Date(n.ts).toLocaleString())),
          h('p', { class: 'dim t-small' }, n.text),
          (n.action === 'hide' || n.action === 'ban') ? h('button', { class: 'btn small soft', onClick: () => appeal(n) }, 'Appeal') : null)))
      if (list.length) M.call('prefs.set', { key: 'noticesSeen', value: Math.max(...list.map((n) => n.ts)) }).catch(() => {})
    } catch (e) { box.replaceChildren(h('p', { class: 'dim' }, e.message)) }
  }
  async function unseenNotices () {
    try { const { list } = await notices(); const seen = (st.prefs.settings || {}).noticesSeen || 0; return list.filter((n) => n.ts > seen).length } catch { return 0 }
  }

  function requestVerification () {
    const name = h('input', { class: 'field', value: st.me.username, placeholder: 'Full name' })
    const cat = h('select', { class: 'field' }, ...['Creator', 'Public figure', 'Business', 'Organisation', 'Journalist'].map((c) => h('option', {}, c)))
    const why = h('textarea', { class: 'field', rows: 3, placeholder: 'Why should you be verified?' })
    const links = h('textarea', { class: 'field', rows: 2, placeholder: 'Links (website, other socials, news)' })
    modal({
      title: 'Request verification',
      body: h('div', {}, h('p', { class: 'dim t-small' }, "The check shows people it's really you. A person at Meshron reviews every request."), name, cat, why, links),
      buttons: [{ label: 'Cancel', onClick: (m) => m.close() }, { label: 'Send request', kind: 'primary', onClick: async (m) => {
        if (!name.value.trim() || !why.value.trim()) return
        try { await signed('/v1/verify/request', { name: name.value, username: st.me.handle || '', category: cat.value, reason: why.value, links: links.value }); m.close(); toast("Sent. You'll hear back in From Meshron.") } catch (e) { oops(e) }
      } }]
    })
  }

  window.MeshronMod = { check, guard, badgeOf, hiddenAmong, badgeEl, wantBadges, report, showNotices, unseenNotices, requestVerification, ACCOUNTS }
})()
