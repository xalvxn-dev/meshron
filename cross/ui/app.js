// Meshron shared screens (iPhone + Windows). Plain DOM, no framework, no build step.
// Talks to the shared host through window.M (bridge.js).

;(function () {
  'use strict'

  const VERSION = '1.5.0'
  const DOWNLOAD_URL = 'https://github.com/xalvxn-dev/meshron/releases/latest'
  const PRIVACY_URL = 'https://xalvxn-dev.github.io/meshron/PRIVACY'
  const GROUP = 'g:'
  const PENDING = 0, SENT = 1, DELIVERED = 2
  const PAGE = 120

  // ---- Tiny DOM helpers ----------------------------------------------------------

  const $ = (sel) => document.querySelector(sel)

  function h(tag, props, ...kids) {
    const el = document.createElement(tag)
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue
      if (k === 'class') el.className = v
      else if (k === 'style') Object.assign(el.style, v)
      else if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v)
      else if (k === 'value') el.value = v
      else el.setAttribute(k, v === true ? '' : v)
    }
    for (const kid of kids.flat(Infinity)) {
      if (kid == null || kid === false) continue
      el.appendChild(typeof kid === 'string' || typeof kid === 'number' ? document.createTextNode(String(kid)) : kid)
    }
    return el
  }

  const ICONS = {
    reply: '<polyline points="9 17 4 12 9 7"/><path d="M20 18v-2a4 4 0 0 0-4-4H4"/>',
    expand: '<polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>',
    chat: '<path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>',
    users: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    user: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
    userPlus: '<path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="8.5" cy="7" r="4"/><line x1="20" y1="8" x2="20" y2="14"/><line x1="23" y1="11" x2="17" y2="11"/>',
    search: '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
    send: '<line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>',
    plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
    x: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
    check: '<polyline points="20 6 9 17 4 12"/>',
    checks: '<polyline points="17 7 8 17 4 13"/><polyline points="22.5 7 13.5 17 12 15.4"/>',
    clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    clip: '<path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/>',
    smile: '<circle cx="12" cy="12" r="10"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><line x1="9" y1="9" x2="9.01" y2="9"/><line x1="15" y1="9" x2="15.01" y2="9"/>',
    back: '<line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/>',
    more: '<circle cx="12" cy="5" r="1.4"/><circle cx="12" cy="12" r="1.4"/><circle cx="12" cy="19" r="1.4"/>',
    share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/>',
    copy: '<rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
    key: '<path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/>',
    globe: '<circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>',
    bell: '<path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/>',
    bellOff: '<path d="M13.73 21a2 2 0 0 1-3.46 0"/><path d="M18.63 13A17.89 17.89 0 0 1 18 8"/><path d="M6.26 6.26A5.86 5.86 0 0 0 6 8c0 7-3 9-3 9h14"/><path d="M18 8a6 6 0 0 0-9.33-5"/><line x1="1" y1="1" x2="23" y2="23"/>',
    pin: '<path d="M12 17v5"/><path d="M9 3h6l-1 6 3 3v2H7v-2l3-3z"/>',
    trash: '<polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    block: '<circle cx="12" cy="12" r="10"/><line x1="4.93" y1="4.93" x2="19.07" y2="19.07"/>',
    at: '<circle cx="12" cy="12" r="4"/><path d="M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-3.92 7.94"/>',
    qr: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><path d="M14 14h3v3h-3z"/><path d="M21 14v.01"/><path d="M21 21h-4v-1"/><path d="M21 18v.01"/>',
    edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/>',
    file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
    down: '<polyline points="6 9 12 15 18 9"/>',
    right: '<polyline points="9 18 15 12 9 6"/>',
    pause: '<rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/>',
    phone: '<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>',
    video: '<polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/>',
    videoOff: '<path d="M16 16v1a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2m5.66 0H14a2 2 0 0 1 2 2v3.34l1 1L23 7v10"/><line x1="1" y1="1" x2="23" y2="23"/>',
    mic: '<path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/>',
    micOff: '<line x1="1" y1="1" x2="23" y2="23"/><path d="M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6"/><path d="M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23"/><line x1="12" y1="19" x2="12" y2="23"/>',
    hangup: '<path d="M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7a2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91"/><line x1="23" y1="1" x2="1" y2="23"/>',
    flip: '<path d="M20 7h-3l-2-3H9L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z"/><path d="M9 13a3 3 0 0 0 5.2 2"/><path d="M15 13a3 3 0 0 0-5.2-2"/>',
    info: '<circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/>',
    sliders: '<line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/>',
    volume: '<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/>',
    verified: '<path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>',
    warn: '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
    lock: '<rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    zap: '<polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>',
    enter: '<polyline points="9 10 4 15 9 20"/><path d="M20 4v7a4 4 0 0 1-4 4H4"/>',
    logo: '<path d="M3 20 6.5 4.5 12 14l5.5-9.5L21 20" stroke-width="2.8"/>'
  }

  function icon(name, size = 22) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('viewBox', '0 0 24 24')
    svg.setAttribute('width', size)
    svg.setAttribute('height', size)
    svg.setAttribute('fill', 'none')
    svg.setAttribute('stroke', 'currentColor')
    svg.setAttribute('stroke-width', '2')
    svg.setAttribute('stroke-linecap', 'round')
    svg.setAttribute('stroke-linejoin', 'round')
    svg.innerHTML = ICONS[name] || ''
    return svg
  }

  // ---- State ---------------------------------------------------------------------

  const st = {
    me: null, contacts: [], groups: [], summaries: [], net: { status: 'stopped', peers: {}, log: [] },
    prefs: { settings: {}, pinned: [], muted: [], drafts: {} }, fileStatus: {}, limits: { MAX_BODY: 4000, MAX_NAME: 32, MAX_FILE: 25 * 1024 * 1024 },
    tab: 'chats', open: null, rows: [], shown: PAGE, query: '', hits: [], firstUnread: null, flash: null,
    seen: new Set(), sending: 0, pendingBelow: 0, typing: {}, reply: null, editing: null
  }
  const isIos = M.platform === 'ios'
  document.body.classList.add(M.platform)

  const isGroup = (conv) => conv.startsWith(GROUP)
  const contact = (key) => st.contacts.find((c) => c.keyHex === key)
  const group = (conv) => st.groups.find((g) => g.conv === conv)
  const isOnline = (key) => !!(st.net.peers[key] && st.net.peers[key].state === 'connected')
  const shortKey = (key) => key.slice(0, 6) + '…' + key.slice(-4)

  function nameOf(key) {
    if (st.me && key === st.me.publicKey) return 'You'
    const c = contact(key)
    if (c && c.name) return c.name
    const p = st.net.peers[key]
    if (p && p.announcedName) return p.announcedName
    return c ? c.shortId : shortKey(key)
  }
  const convTitle = (conv) => (isGroup(conv) ? (group(conv) || { name: 'Group' }).name : nameOf(conv))

  // ---- Formatting ----------------------------------------------------------------

  const AVATARS = [['#6366F1', '#A855F7'], ['#14B8A6', '#3B82F6'], ['#EC4899', '#F97316'], ['#F59E0B', '#EF4444'], ['#8B5CF6', '#EC4899'], ['#06B6D4', '#6366F1'], ['#22C55E', '#0EA5E9'], ['#F43F5E', '#A855F7']]
  // Same as Kotlin's String.hashCode, so a person has the same colours on every platform.
  function javaHash(s) {
    let x = 0
    for (let i = 0; i < s.length; i++) x = (Math.imul(31, x) + s.charCodeAt(i)) | 0
    return x & 0x7fffffff
  }
  function initials(name) {
    const words = String(name).trim().split(' ').filter(Boolean)
    if (!words.length) return '?'
    return (words.length === 1 ? [...words[0]][0] : [...words[0]][0] + [...words[1]][0]).toUpperCase()
  }
  function avatar(name, seed, size = 48, o = {}) {
    const [a, b] = AVATARS[javaHash(seed) % AVATARS.length]
    const el = h('div', { class: 'avatar' + (o.group ? ' squircle' : ''), style: { width: size + 'px', height: size + 'px', fontSize: Math.round(size * 0.38) + 'px', background: `linear-gradient(135deg, ${a}, ${b})` } }, initials(name))
    if (o.online && !o.ring) el.appendChild(h('span', { class: 'dot' }))
    if (!o.ring) return el
    return h('div', { class: 'ring' }, h('div', { class: 'gap' }, el), h('span', { class: 'dot' }))
  }

  const startOfDay = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime() }
  const daysAgo = (ms) => Math.round((startOfDay(Date.now()) - startOfDay(ms)) / 86400000)
  const clock = (ms) => new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  function friendlyTime(ms) {
    const d = daysAgo(ms)
    if (d === 0) return clock(ms)
    if (d === 1) return 'Yesterday'
    if (d < 7) return new Date(ms).toLocaleDateString([], { weekday: 'short' })
    return new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'numeric', year: '2-digit' })
  }
  function dayLabel(ms) {
    const d = daysAgo(ms)
    if (d === 0) return 'Today'
    if (d === 1) return 'Yesterday'
    return new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'long', year: 'numeric' })
  }
  function fileSize(n) {
    if (n < 1024) return n + ' B'
    if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB'
    return (n / 1024 / 1024).toFixed(1) + ' MB'
  }
  function parseJson(s) { try { return JSON.parse(s) } catch { return null } }
  function fileInfo(row) {
    const o = parseJson(row.body)
    return o && typeof o.name === 'string' ? { name: o.name, mime: o.mime || 'application/octet-stream', size: Number(o.size) || 0 } : null
  }
  // Replies: "↩ <quote>" line, newline, the reply (same on every platform).
  const REPLY_RE = /^↩ (.*)\n([\s\S]+)$/
  const bodyText = (body) => { const m = REPLY_RE.exec(body); return m ? m[2] : body }
  function quoteOf(r) {
    let text = bodyText(r.body)
    if (r.kind === 'file') { const fi = fileInfo(r); text = fi ? (fi.mime.startsWith('image/') ? '📷 Photo' : '📎 ' + fi.name) : '📎 File' }
    const line = String(text).replace(/\s+/g, ' ').trim()
    return line.length > 90 ? line.slice(0, 89) + '…' : line
  }
  const QUICK = ['❤️', '😂', '😮', '😢', '🙏', '👍']
  const EDIT_WINDOW = 15 * 60 * 1000
  function displayBody(row) {
    if (row.kind === 'deleted') return row.outgoing ? 'You deleted this message' : 'This message was deleted'
    if (row.kind === 'text') return bodyText(row.body)
    if (row.kind === 'file') {
      const f = fileInfo(row)
      return f ? (f.mime.startsWith('image/') ? 'Photo: ' : 'File: ') + f.name : 'File'
    }
    if (row.kind === 'invite') {
      const o = parseJson(row.body)
      const name = (o && o.name) || 'a group'
      return row.outgoing ? `You invited them to “${name}”` : `Added you to the group “${name}”`
    }
    return row.body
  }

  // ---- Toasts, modals, menus -----------------------------------------------------

  function toast(text, ms = 2600) {
    const el = h('div', { class: 'toast' }, text)
    $('#toasts').appendChild(el)
    setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300) }, ms)
  }
  const oops = (err) => toast((err && err.message) || String(err), 4000)

  const layer = $('#layer')
  const openModals = []

  function modal({ title, body, buttons = [], wide, onClose, back }) {
    const close = () => {
      const i = openModals.indexOf(api)
      if (i >= 0) openModals.splice(i, 1)
      scrim.remove()
      if (onClose) onClose()
    }
    const box = h('div', { class: 'modal' + (wide ? ' wide' : '') },
      h('div', { class: 'head' },
        back ? h('button', { class: 'icon-btn', onClick: back, 'aria-label': 'Back' }, icon('back')) : null,
        h('h2', { class: 't-h2' }, title),
        h('button', { class: 'icon-btn', onClick: close, 'aria-label': 'Close' }, icon('x'))),
      body,
      buttons.length ? h('div', { class: 'buttons' }, buttons.map((b) => h('button', { class: 'btn small ' + (b.kind || 'soft'), disabled: b.disabled, onClick: () => b.onClick(api) }, b.label))) : null)
    const scrim = h('div', { class: 'scrim', onMousedown: (e) => { if (e.target === scrim) close() } }, box)
    const api = { close, box }
    layer.appendChild(scrim)
    openModals.push(api)
    const first = box.querySelector('input, textarea')
    if (first && !isIos) setTimeout(() => first.focus(), 50)
    return api
  }

  function confirmBox(title, text, label, danger) {
    return new Promise((resolve) => {
      let answered = false
      modal({
        title, body: h('p', { class: 'dim' }, text), onClose: () => { if (!answered) resolve(false) },
        buttons: [
          { label: 'Cancel', onClick: (m) => m.close() },
          { label, kind: danger ? 'soft danger' : 'primary', onClick: (m) => { answered = true; m.close(); resolve(true) } }
        ]
      })
    })
  }

  function menu(x, y, items) {
    closeMenu()
    const el = h('div', { class: 'menu' }, items.filter(Boolean).map((it) => h('button', { class: it.danger ? 'danger' : '', onClick: () => { closeMenu(); it.onClick() } }, icon(it.icon, 18), it.label)))
    const catcher = h('div', { class: 'menu-catcher', id: 'menu-catcher', onMousedown: closeMenu, onContextmenu: (e) => { e.preventDefault(); closeMenu() } })
    layer.appendChild(catcher)
    layer.appendChild(el)
    const r = el.getBoundingClientRect()
    el.style.left = Math.max(8, Math.min(x, innerWidth - r.width - 8)) + 'px'
    el.style.top = Math.max(8, Math.min(y, innerHeight - r.height - 8)) + 'px'
    el.id = 'menu'
  }
  function closeMenu() {
    for (const id of ['#menu', '#menu-catcher']) { const el = $(id); if (el) el.remove() }
  }
  // Right-click on desktop, long-press on touch.
  function onContext(el, fn) {
    el.addEventListener('contextmenu', (e) => { e.preventDefault(); fn(e.clientX, e.clientY) })
    let timer = null
    el.addEventListener('touchstart', (e) => {
      const t = e.touches[0]
      timer = setTimeout(() => { timer = null; fn(t.clientX, t.clientY) }, 480)
    }, { passive: true })
    for (const ev of ['touchend', 'touchmove', 'touchcancel']) el.addEventListener(ev, () => clearTimeout(timer), { passive: true })
  }

  async function copy(text, done = 'Copied') {
    if (await M.native.copy(text)) toast(done)
  }

  function shareMe() {
    const text = `Chat with me on Meshron: ${st.me.shareLink}` + (st.me.handle ? `\nOr find me as @${st.me.handle}` : '') + `\nGet the app: ${DOWNLOAD_URL}`
    if (M.native.share) M.native.share({ text }).catch(oops)
    else copy(text, 'Invite copied. Paste it to a friend.')
  }

  // ---- Onboarding ----------------------------------------------------------------

  const ob = $('#onboarding')
  const point = (ic, color, title, body) => h('div', { class: 'ob-point' },
    h('div', { class: 'tile', style: { background: color } }, icon(ic, 20)),
    h('div', {}, h('div', { class: 't-title' }, title), h('div', { class: 't-small dim' }, body)))

  function showWelcome() {
    ob.replaceChildren(h('div', { class: 'ob-card' },
      h('div', { class: 'ob-logo' }, icon('logo', 34)),
      h('h1', { class: 't-display', style: { marginTop: '26px' } }, 'Talk freely.', h('br'), h('span', { class: 'grad-text' }, 'No one in between.')),
      h('p', { class: 'dim', style: { marginTop: '12px', fontSize: '16px' } }, "Meshron sends your messages straight to your friends' devices. Nothing is stored on a server, and there's no account."),
      h('div', { class: 'ob-points' },
        point('user', '#6366F1', 'No number, no sign-up', "Pick a name and you're in."),
        point('lock', '#22A06B', 'Encrypted end to end', 'Only you and your friend can read it.'),
        point('zap', '#F59E0B', 'Works with the phone app', 'Chat with friends on Android, iPhone and Windows.')),
      h('button', { class: 'btn primary wide', onClick: showCreate }, 'Get started'),
      h('button', { class: 'btn ghost wide', style: { marginTop: '8px' }, onClick: showRestore }, 'I have a recovery key')))
  }

  function showCreate() {
    let handleEdited = false
    const av = h('div', { class: 'ob-avatar' })
    const name = h('input', { class: 'input', placeholder: 'e.g. Alex', maxlength: 40, autocomplete: 'off' })
    const handle = h('input', { class: 'input has-prefix', placeholder: 'username', maxlength: 24, autocomplete: 'off', autocapitalize: 'none', spellcheck: 'false' })
    const nameHint = h('div', { class: 'hint' })
    const handleHint = h('div', { class: 'hint' }, 'Optional. Friends can find you by it.')
    const go = h('button', { class: 'btn primary wide', style: { marginTop: '18px' }, disabled: true }, 'Continue')
    const problem = (hh) => (hh.length < 3 ? 'At least 3 characters' : hh.length > 20 ? 'At most 20 characters' : !/^[a-z0-9_]+$/.test(hh) ? 'Only letters, numbers and _' : null)
    const refresh = () => {
      const n = name.value.trim().replace(/\s+/g, ' ')
      if (!handleEdited) handle.value = n.toLowerCase().replace(/[^a-z0-9_]/g, '').slice(0, 20)
      const hh = handle.value.trim().replace(/^@/, '').toLowerCase()
      const p = hh ? problem(hh) : null
      av.replaceChildren(avatar(n || '?', 'me', 88))
      nameHint.textContent = n.length > st.limits.MAX_NAME ? `At most ${st.limits.MAX_NAME} characters` : ''
      nameHint.className = 'hint' + (n.length > st.limits.MAX_NAME ? ' error' : '')
      handleHint.textContent = p || 'Optional. Friends can find you by it.'
      handleHint.className = 'hint' + (p ? ' error' : '')
      go.disabled = !(n.length >= 1 && n.length <= st.limits.MAX_NAME && !p)
    }
    name.addEventListener('input', refresh)
    handle.addEventListener('input', () => { handleEdited = true; refresh() })
    const submit = async () => {
      if (go.disabled) return
      go.disabled = true
      go.replaceChildren(h('div', { class: 'spinner' }))
      try {
        await M.call('identity.create', { name: name.value, handle: handle.value })
      } catch (err) {
        oops(err)
        go.replaceChildren('Continue')
        refresh()
      }
    }
    go.addEventListener('click', submit)
    for (const el of [name, handle]) el.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit() })
    ob.replaceChildren(h('div', { class: 'ob-card' },
      h('button', { class: 'icon-btn', style: { marginLeft: '-8px' }, onClick: showWelcome }, icon('back')),
      av,
      h('h1', { class: 't-h1' }, 'What should friends call you?'),
      h('p', { class: 'dim', style: { marginTop: '6px' } }, 'Your contacts see this name. You can change it any time.'),
      h('label', { class: 'field' }, h('span', {}, 'Your name'), name), nameHint,
      h('label', { class: 'field' }, h('span', {}, 'Username'), h('div', { class: 'input-wrap' }, h('span', { class: 'prefix' }, '@'), handle)), handleHint,
      go,
      h('p', { class: 't-small dimmer', style: { marginTop: '14px' } }, 'A private key is made on this device and kept in its secure storage. It never leaves the device.')))
    refresh()
    if (!isIos) name.focus()
  }

  function showRestore() {
    const key = h('textarea', { class: 'input', rows: 3, placeholder: '52 letters and numbers', autocapitalize: 'none', spellcheck: 'false' })
    const name = h('input', { class: 'input', placeholder: 'e.g. Alex', maxlength: 40 })
    const hint = h('div', { class: 'hint' }, '0/52')
    const go = h('button', { class: 'btn primary wide', style: { marginTop: '18px' }, disabled: true }, 'Restore')
    const clean = () => key.value.toLowerCase().replace(/[\s-]/g, '')
    const refresh = () => {
      hint.textContent = clean().length + '/52'
      hint.className = 'hint'
      go.disabled = !(clean().length === 52 && name.value.trim())
    }
    key.addEventListener('input', refresh)
    name.addEventListener('input', refresh)
    go.addEventListener('click', async () => {
      go.disabled = true
      try {
        await M.call('identity.restore', { key: key.value, name: name.value })
      } catch (err) {
        hint.textContent = err.message
        hint.className = 'hint error'
        go.disabled = false
      }
    })
    ob.replaceChildren(h('div', { class: 'ob-card' },
      h('button', { class: 'icon-btn', style: { marginLeft: '-8px' }, onClick: showWelcome }, icon('back')),
      h('h1', { class: 't-h1', style: { marginTop: '8px' } }, 'Restore your account'),
      h('p', { class: 'dim', style: { marginTop: '6px' } }, 'Type the recovery key you wrote down. Your Meshron ID comes back, so friends reach you again. Old chats and contacts stay on the old device.'),
      h('label', { class: 'field' }, h('span', {}, 'Recovery key'), key), hint,
      h('label', { class: 'field' }, h('span', {}, 'Your name'), name),
      go))
  }

  // ---- Shell: rail -----------------------------------------------------------------

  const TABS = [['chats', 'Chats', 'chat'], ['contacts', 'Contacts', 'users'], ['me', 'Me', 'user']]
  const unreadTotal = () => st.summaries.reduce((n, s) => n + (st.prefs.muted.includes(s.conv) ? 0 : s.unread), 0)
  const requestCount = () => st.contacts.filter((c) => c.status === 'REQUEST').length

  function netLabel() {
    const n = st.net
    if (n.status === 'failed') return ['Problem', '']
    if (n.status !== 'online') return ['Starting', 'pulse']
    if (n.dht && n.dht.internet === false) return ['Internet off', 'off']
    if (n.dht && n.dht.bootstrapped) return ['Online', 'online']
    return ['Connecting', 'pulse']
  }

  function renderRail() {
    const [label, cls] = netLabel()
    $('#rail').replaceChildren(
      h('div', { class: 'logo' }, icon('logo', 24)),
      ...TABS.map(([id, text, ic]) => {
        const n = id === 'chats' ? unreadTotal() : id === 'contacts' ? requestCount() : 0
        return h('button', { class: 'rail-btn' + (st.tab === id ? ' on' : ''), title: text, onClick: () => setTab(id) },
          icon(ic, 24), h('span', { class: 'label' }, text), n && st.tab !== id ? h('span', { class: 'badge' }, n > 99 ? '99+' : n) : null)
      }),
      h('div', { class: 'rail-spacer' }),
      h('button', { class: 'rail-btn rail-net', title: label, onClick: showConnection }, h('span', { class: 'net-dot ' + cls })))
    const pill = $('#status-pill')
    if (pill) pill.replaceChildren(h('span', { class: 'net-dot ' + cls }), label)
    const total = unreadTotal()
    document.title = (total ? `(${total}) ` : '') + 'Meshron'
    if (M.native.badge) try { M.native.badge(total) } catch {}
  }

  function setTab(id) {
    if (st.tab === id) return
    st.tab = id
    st.query = ''
    st.hits = []
    renderRail()
    buildSide()
  }

  // ---- Side pane -------------------------------------------------------------------

  function buildSide() {
    const side = $('#side')
    const title = TABS.find((t) => t[0] === st.tab)[1]
    const head = h('div', { class: 'side-head' },
      h('h1', { class: 't-h1' }, title),
      h('button', { class: 'sos-pill', title: 'Emergency SOS', onClick: showSos }, 'SOS'),
      h('button', { class: 'status-pill', id: 'status-pill', onClick: showConnection }),
      st.tab === 'chats' ? h('button', { class: 'icon-btn', title: 'New group', onClick: showNewGroup }, icon('users')) : null,
      st.tab !== 'me' ? h('button', { class: 'icon-btn', title: 'Add contact', onClick: () => showAddContact() }, icon('userPlus')) : null)
    const kids = [head]
    if (st.tab !== 'me') {
      const input = h('input', { id: 'search', placeholder: st.tab === 'chats' ? 'Search chats and messages' : 'Search contacts', autocomplete: 'off', spellcheck: 'false' })
      const clear = h('button', { class: 'icon-btn clear', hidden: true, onClick: () => { input.value = ''; input.dispatchEvent(new Event('input')); input.focus() } }, icon('x', 18))
      let timer = null
      input.addEventListener('input', () => {
        st.query = input.value
        clear.hidden = !input.value
        renderSide()
        clearTimeout(timer)
        if (st.tab === 'chats') {
          timer = setTimeout(async () => {
            const q = st.query
            const hits = q.trim().length >= 2 ? await M.call('chat.search', { q }) : []
            if (q === st.query) { st.hits = hits; renderSide() }
          }, 220)
        }
      })
      kids.push(h('div', { class: 'search' }, icon('search', 20), input, clear))
    }
    const bodyEl = h('div', { class: 'side-body enter', id: 'side-body' })
    setTimeout(() => bodyEl.classList.remove('enter'), 700)
    kids.push(bodyEl)
    if (st.tab === 'chats') kids.push(h('button', { class: 'fab', onClick: showNewChat }, icon('edit', 20), 'New chat'))
    side.replaceChildren(...kids)
    renderRail()
    renderSide()
  }

  function renderSide() {
    const body = $('#side-body')
    if (!body) return
    const top = body.scrollTop
    if (st.tab === 'chats') renderChats(body)
    else if (st.tab === 'contacts') renderContacts(body)
    else renderMe(body)
    body.scrollTop = top
  }

  function tickIcon(status, size = 16) {
    if (status === PENDING) return h('span', { class: 'tick', title: 'Waiting' }, icon('clock', size - 2))
    if (status === SENT) return h('span', { class: 'tick', title: 'Sent' }, icon('check', size))
    return h('span', { class: 'tick done', title: 'Delivered' }, icon('checks', size))
  }

  function chatRows() {
    const byConv = new Map(st.summaries.map((s) => [s.conv, s]))
    const rows = []
    for (const c of st.contacts) {
      if (c.status === 'BLOCKED') continue
      if (c.status !== 'ADDED' && !byConv.has(c.keyHex)) continue
      rows.push({ conv: c.keyHex, title: c.displayName, group: false, online: isOnline(c.keyHex), fallback: c.status === 'REQUEST' ? 'Wants to chat with you' : 'Tap to say hi', s: byConv.get(c.keyHex) })
    }
    for (const g of st.groups) rows.push({ conv: g.conv, title: g.name, group: true, online: false, fallback: g.members.length + ' members', s: byConv.get(g.conv) })
    const pinned = (r) => (st.prefs.pinned.includes(r.conv) ? 1 : 0)
    return rows.sort((a, b) => pinned(b) - pinned(a) || ((b.s ? b.s.last.localTime : 0) - (a.s ? a.s.last.localTime : 0)))
  }

  function chatRow(r) {
    const s = r.s
    const unread = s ? s.unread : 0
    const last = s && s.last
    const muted = st.prefs.muted.includes(r.conv)
    const draft = st.prefs.drafts[r.conv]
    let preview = r.fallback
    if (draft && st.open !== r.conv) preview = null
    else if (last) preview = (r.group && !last.outgoing && last.senderKey ? nameOf(last.senderKey).split(' ')[0] + ': ' : '') + displayBody(last)
    const el = h('div', { class: 'row' + (unread ? ' unread' : '') + (st.open === r.conv ? ' on' : ''), onClick: () => openChat(r.conv) },
      avatar(r.title, r.conv, 54, { group: r.group, online: r.online }),
      h('div', { class: 'grow' },
        h('div', { class: 'top' }, h('span', { class: 'name ellipsis' }, r.title),
          verifiedBadge(r.conv),
          h('span', { class: 'time' }, last ? friendlyTime(last.localTime) : '')),
        h('div', { class: 'sub' },
          preview === null ? [h('span', { style: { color: 'var(--danger)', flex: 'none' } }, 'Draft:'), h('span', { class: 'ellipsis', style: { flex: 1 } }, draft)]
            : [last && last.outgoing ? tickIcon(last.status) : null, h('span', { class: 'ellipsis', style: { flex: 1 } }, preview)],
          st.prefs.pinned.includes(r.conv) ? h('span', { class: 'flag' }, icon('pin', 15)) : null,
          muted ? h('span', { class: 'flag' }, icon('bellOff', 15)) : null,
          unread ? h('span', { class: 'badge' + (muted ? ' quiet' : '') }, unread > 99 ? '99+' : unread) : null)))
    onContext(el, (x, y) => chatMenu(r.conv, x, y))
    return el
  }

  function verifiedBadge(conv) {
    const c = !isGroup(conv) && contact(conv)
    return c && c.verified ? h('span', { title: 'Verified', style: { color: 'var(--accent)', flex: 'none' } }, icon('verified', 15)) : null
  }

  function chatMenu(conv, x, y) {
    const pinned = st.prefs.pinned.includes(conv)
    const muted = st.prefs.muted.includes(conv)
    const s = st.summaries.find((v) => v.conv === conv)
    menu(x, y, [
      { icon: 'pin', label: pinned ? 'Unpin' : 'Pin to top', onClick: () => M.call('prefs.pin', { conv, on: !pinned }) },
      { icon: muted ? 'bell' : 'bellOff', label: muted ? 'Unmute' : 'Mute', onClick: () => M.call('prefs.mute', { conv, on: !muted }) },
      s && s.unread ? { icon: 'check', label: 'Mark as read', onClick: () => M.call('chat.markRead', { conv }) } : null,
      { icon: 'trash', label: 'Delete chat', danger: true, onClick: () => deleteChat(conv) }
    ])
  }

  async function deleteChat(conv) {
    if (!(await confirmBox('Delete this chat?', `All messages with ${convTitle(conv)} are removed from this device only. They keep their copy.`, 'Delete', true))) return
    await M.call('chat.deleteConversation', { conv })
    toast('Chat deleted')
  }

  function renderChats(body) {
    const q = st.query.trim().toLowerCase()
    const rows = chatRows().filter((r) => !q || r.title.toLowerCase().includes(q))
    const added = st.contacts.filter((c) => c.status === 'ADDED')
    const kids = []
    if (!q) kids.push(storiesStrip(added))
    if (!rows.length && !q) {
      kids.push(h('div', { class: 'empty' },
        h('div', { class: 'orb' }, icon('chat', 36)),
        h('div', { class: 't-h2' }, 'No chats yet'),
        h('p', {}, 'Add a friend by their @username or Meshron ID, or send them yours.'),
        h('div', { class: 'actions' },
          h('button', { class: 'btn primary', onClick: () => showAddContact() }, icon('userPlus', 18), 'Add a contact'),
          h('button', { class: 'btn soft', onClick: shareMe }, icon('share', 18), 'Invite a friend'))))
    }
    if (q && rows.length) kids.push(h('div', { class: 'section' }, 'Chats'))
    kids.push(rows.map(chatRow))
    if (q && st.hits.length) {
      kids.push(h('div', { class: 'section' }, 'Messages'))
      kids.push(st.hits.map((m) => h('div', { class: 'row', onClick: () => openChat(m.peerKey, m.id) },
        avatar(convTitle(m.peerKey), m.peerKey, 44, { group: isGroup(m.peerKey) }),
        h('div', { class: 'grow' },
          h('div', { class: 'top' }, h('span', { class: 'name ellipsis', style: { fontSize: '15px' } }, convTitle(m.peerKey)), h('span', { class: 'time' }, friendlyTime(m.localTime))),
          h('div', { class: 'sub' }, h('span', { class: 'ellipsis' }, (m.outgoing ? 'You: ' : '') + m.body))))))
    }
    if (q && !rows.length && !st.hits.length) {
      kids.push(h('div', { class: 'empty' }, h('div', { class: 'orb' }, icon('search', 34)), h('div', { class: 't-h2' }, 'No match'), h('p', {}, `Nothing found for “${st.query.trim()}”.`)))
    }
    body.replaceChildren(...kids.flat(Infinity).filter(Boolean))
  }

  // ---- Contacts ------------------------------------------------------------------

  function contactMenu(c, x, y) {
    menu(x, y, [
      { icon: 'chat', label: 'Message', onClick: () => openChat(c.keyHex) },
      { icon: 'shield', label: 'Verify safety code', onClick: () => showSafety(c.keyHex) },
      { icon: 'block', label: 'Block', onClick: () => blockContact(c.keyHex) },
      { icon: 'trash', label: 'Remove', danger: true, onClick: () => removeContact(c) }
    ])
  }
  async function removeContact(c) {
    if (await confirmBox(`Remove ${c.displayName}?`, "You'll stop connecting to them. Your chat history stays. You can add them again any time.", 'Remove', true)) M.call('contacts.remove', { key: c.keyHex })
  }
  async function blockContact(key) {
    if (await confirmBox(`Block ${nameOf(key)}?`, "They won't be able to message or call you. You can unblock them in Contacts.", 'Block', true)) {
      await M.call('contacts.block', { key })
      if (st.open === key) closeChat()
    }
  }

  function renderContacts(body) {
    const q = st.query.trim().toLowerCase().replace(/^@/, '')
    const list = st.contacts.filter((c) => !q || c.displayName.toLowerCase().includes(q) || (c.handle || '').includes(q))
    const requests = list.filter((c) => c.status === 'REQUEST')
    const blocked = list.filter((c) => c.status === 'BLOCKED')
    const added = list.filter((c) => c.status === 'ADDED').sort((a, b) => a.displayName.localeCompare(b.displayName))
    const kids = []
    if (!q) {
      kids.push(h('div', { class: 'row action', onClick: () => showAddContact() }, h('div', { class: 'action-circle' }, icon('userPlus')),
        h('div', { class: 'grow' }, h('div', { class: 'name' }, 'Add contact'), h('div', { class: 'sub' }, 'By @username, ID or link'))))
      kids.push(h('div', { class: 'row action', onClick: shareMe }, h('div', { class: 'action-circle' }, icon('share')),
        h('div', { class: 'grow' }, h('div', { class: 'name' }, 'Invite friends'), h('div', { class: 'sub ellipsis' }, st.me.handle ? `Send your link, or tell them @${st.me.handle}` : 'Send them your Meshron link'))))
    }
    if (requests.length) {
      kids.push(h('div', { class: 'section' }, 'Waiting for you'))
      for (const c of requests) {
        kids.push(h('div', { class: 'row', onClick: () => openChat(c.keyHex) },
          avatar(c.displayName, c.keyHex, 48),
          h('div', { class: 'grow' }, h('div', { class: 'name ellipsis' }, c.displayName), h('div', { class: 'sub ellipsis' }, c.handle ? `@${c.handle} · added you` : 'Added you')),
          h('button', { class: 'icon-btn', title: 'Ignore', onClick: (e) => { e.stopPropagation(); M.call('contacts.remove', { key: c.keyHex }) } }, icon('x', 20)),
          h('button', { class: 'btn primary small', onClick: (e) => { e.stopPropagation(); M.call('contacts.add', { key: c.keyHex, name: c.name }) } }, 'Accept')))
      }
    }
    const section = (title, people) => {
      if (!people.length) return
      kids.push(h('div', { class: 'section' }, `${title} · ${people.length}`))
      for (const c of people) {
        const on = isOnline(c.keyHex)
        const el = h('div', { class: 'row', onClick: () => openChat(c.keyHex) },
          avatar(c.displayName, c.keyHex, 48, { online: on }),
          h('div', { class: 'grow' },
            h('div', { class: 'top' }, h('span', { class: 'name ellipsis' }, c.displayName), verifiedBadge(c.keyHex)),
            h('div', { class: 'sub', style: on ? { color: 'var(--online)' } : null }, [on ? 'online' : null, c.handle ? '@' + c.handle : null].filter(Boolean).join(' · ') || 'offline')),
          h('button', { class: 'icon-btn', onClick: (e) => { e.stopPropagation(); const r = e.currentTarget.getBoundingClientRect(); contactMenu(c, r.left, r.bottom) } }, icon('more', 20)))
        onContext(el, (x, y) => contactMenu(c, x, y))
        kids.push(el)
      }
    }
    section('Online', added.filter((c) => isOnline(c.keyHex)))
    section('Offline', added.filter((c) => !isOnline(c.keyHex)))
    if (!added.length && !requests.length) {
      kids.push(h('div', { class: 'empty' }, h('div', { class: 'orb' }, icon('users', 36)),
        h('div', { class: 't-h2' }, q ? 'No match' : 'No contacts yet'),
        h('p', {}, q ? `Nobody in your contacts matches “${st.query.trim()}”.` : 'Add a friend by their @username or ID, or send them your link.')))
    }
    if (blocked.length) {
      kids.push(h('div', { class: 'section' }, 'Blocked'))
      for (const c of blocked) {
        kids.push(h('div', { class: 'row', style: { cursor: 'default' } }, avatar(c.displayName, c.keyHex, 48),
          h('div', { class: 'grow' }, h('div', { class: 'name ellipsis' }, c.displayName), h('div', { class: 'sub' }, "Can't message or call you")),
          h('button', { class: 'btn ghost small', onClick: () => M.call('contacts.unblock', { key: c.keyHex }) }, 'Unblock')))
      }
    }
    body.replaceChildren(...kids)
  }

  // ---- Add contact, new chat, new group ------------------------------------------------

  function showAddContact(prefill) {
    let m = null
    const open = (opts) => { if (m) m.close(); m = modal(opts) }
    const choose = () => open({
      title: 'Add contact',
      body: h('div', {},
        h('div', { class: 'pick', onClick: byHandle }, h('div', { class: 'action-circle' }, icon('at')), h('div', {}, h('div', { class: 't-title' }, 'By username'), h('div', { class: 't-small dim' }, 'Search for their @username'))),
        h('div', { class: 'pick', onClick: () => byId('') }, h('div', { class: 'action-circle' }, icon('key')), h('div', {}, h('div', { class: 't-title' }, 'By ID or link'), h('div', { class: 't-small dim' }, 'Paste what they sent you'))),
        h('div', { class: 'pick', onClick: () => { m.close(); showQr() } }, h('div', { class: 'action-circle' }, icon('qr')), h('div', {}, h('div', { class: 't-title' }, 'Show my QR code'), h('div', { class: 't-small dim' }, 'Let them scan it with the phone app'))))
    })
    const add = async (key, name) => {
      try {
        await M.call('contacts.add', { key, name: name || '' })
        m.close()
        openChat(key)
        toast('Contact added')
      } catch (err) { oops(err) }
    }
    const byHandle = () => {
      const input = h('input', { class: 'input has-prefix', placeholder: 'username', autocapitalize: 'none', spellcheck: 'false', autocomplete: 'off' })
      const out = h('div', { style: { minHeight: '84px', marginTop: '12px' } }, h('p', { class: 'dim t-small' }, 'Type their exact username.'))
      let timer = null
      input.addEventListener('input', () => {
        clearTimeout(timer)
        const hh = input.value.trim().replace(/^@/, '').toLowerCase()
        if (hh.length < 3) return out.replaceChildren(h('p', { class: 'dim t-small' }, 'Type their exact username.'))
        out.replaceChildren(h('div', { style: { display: 'flex', gap: '10px', alignItems: 'center' }, class: 'dim' }, h('div', { class: 'spinner' }), 'Searching…'))
        timer = setTimeout(async () => {
          const res = await M.call('handle.find', { handle: hh }).catch(() => ({ keys: [], error: 'failed' }))
          if (input.value.trim().replace(/^@/, '').toLowerCase() !== hh) return
          if (res.error === 'offline') return out.replaceChildren(h('p', { class: 'hint error' }, "You're offline. Username search needs the internet."))
          if (!res.keys.length) return out.replaceChildren(h('p', { class: 'dim' }, `Nobody online is using @${hh} right now. They need to have Meshron open.`))
          out.replaceChildren(...res.keys.map((key) => {
            const c = contact(key)
            const known = c && c.status === 'ADDED'
            return h('div', { class: 'pick', style: { cursor: 'default' } }, avatar(c ? c.displayName : '@' + hh, key, 46),
              h('div', { style: { flex: 1, minWidth: 0 } }, h('div', { class: 't-title ellipsis' }, c && c.name ? c.name : '@' + hh), h('div', { class: 't-small dim' }, 'ID ' + shortKey(key))),
              known ? h('button', { class: 'btn soft small', onClick: () => { m.close(); openChat(key) } }, 'Message') : h('button', { class: 'btn primary small', onClick: () => add(key, '') }, 'Add'))
          }))
        }, 450)
      })
      open({ title: 'Find by username', back: choose, body: h('div', {}, h('div', { class: 'input-wrap' }, h('span', { class: 'prefix' }, '@'), input), out) })
    }
    const byId = (text) => {
      const input = h('textarea', { class: 'input', rows: 3, placeholder: 'Paste a Meshron ID or meshron:// link', spellcheck: 'false', autocapitalize: 'none' })
      const hint = h('div', { class: 'hint' })
      const name = h('input', { class: 'input', placeholder: 'Name (optional)', maxlength: 64 })
      let parsed = null
      const btn = h('button', { class: 'btn primary wide', style: { marginTop: '14px' }, disabled: true, onClick: () => parsed && add(parsed.keyHex, name.value || parsed.name) }, 'Add contact')
      const check = async () => {
        parsed = null
        btn.disabled = true
        const v = input.value.trim()
        if (!v) { hint.textContent = ''; return }
        const p = await M.call('contacts.parse', { text: v })
        if (input.value.trim() !== v) return
        if (p.error) { hint.textContent = p.error; hint.className = 'hint error'; return }
        parsed = p
        hint.textContent = 'Looks good' + (p.name ? `: ${p.name}` : '')
        hint.className = 'hint ok'
        if (p.name && !name.value) name.value = p.name
        btn.disabled = false
      }
      input.addEventListener('input', check)
      open({ title: 'Add by ID or link', back: choose, body: h('div', {}, input, hint, h('label', { class: 'field' }, name), btn) })
      if (text) { input.value = text; check() }
    }
    if (prefill) byId(prefill)
    else choose()
  }

  function showNewChat() {
    const added = st.contacts.filter((c) => c.status === 'ADDED').sort((a, b) => a.displayName.localeCompare(b.displayName))
    const m = modal({
      title: 'New chat',
      body: h('div', {},
        h('div', { class: 'pick', onClick: () => { m.close(); showAddContact() } }, h('div', { class: 'action-circle' }, icon('userPlus')), h('div', {}, h('div', { class: 't-title' }, 'Add a contact'), h('div', { class: 't-small dim' }, 'By @username, ID or link'))),
        h('div', { class: 'pick', onClick: () => { m.close(); showNewGroup() } }, h('div', { class: 'action-circle' }, icon('users')), h('div', {}, h('div', { class: 't-title' }, 'New group'), h('div', { class: 't-small dim' }, 'Chat with several contacts at once'))),
        added.length ? h('div', { class: 'section', style: { padding: '14px 8px 4px' } }, 'Your contacts') : null,
        added.map((c) => h('div', { class: 'pick', onClick: () => { m.close(); openChat(c.keyHex) } }, avatar(c.displayName, c.keyHex, 44, { online: isOnline(c.keyHex) }),
          h('div', { style: { minWidth: 0 } }, h('div', { class: 't-title ellipsis' }, c.displayName), h('div', { class: 't-small dim' }, isOnline(c.keyHex) ? 'Online' : "Offline · they'll get messages later")))))
    })
  }

  function showNewGroup() {
    const added = st.contacts.filter((c) => c.status === 'ADDED').sort((a, b) => a.displayName.localeCompare(b.displayName))
    const picked = new Set()
    const name = h('input', { class: 'input', placeholder: 'e.g. Family', maxlength: 48 })
    const list = h('div', { style: { maxHeight: '300px', overflowY: 'auto', marginTop: '8px' } })
    const count = h('div', { class: 'section', style: { padding: '14px 4px 0' } })
    let m = null
    const refresh = () => {
      count.textContent = `Who's in it? (${picked.size} picked)`
      list.replaceChildren(...added.map((c) => h('div', { class: 'pick', onClick: () => { picked.has(c.keyHex) ? picked.delete(c.keyHex) : picked.add(c.keyHex); refresh() } },
        avatar(c.displayName, c.keyHex, 42), h('div', { class: 't-title ellipsis', style: { flex: 1 } }, c.displayName),
        h('div', { class: 'checkbox' + (picked.has(c.keyHex) ? ' on' : '') }, icon('check', 16)))))
      const btn = m && m.box.querySelector('.btn.primary')
      if (btn) btn.disabled = !(name.value.trim() && picked.size)
    }
    name.addEventListener('input', refresh)
    m = modal({
      title: 'New group',
      body: added.length ? h('div', {}, h('label', { class: 'field', style: { marginTop: 0 } }, h('span', {}, 'Group name'), name), count, list)
        : h('p', { class: 'dim' }, "You need contacts first. Only people you've added can join a group."),
      buttons: added.length ? [{ label: 'Cancel', onClick: (x) => x.close() }, {
        label: 'Create', kind: 'primary', disabled: true, onClick: async (x) => {
          try {
            const conv = await M.call('group.create', { name: name.value, members: [...picked] })
            x.close()
            setTimeout(() => openChat(conv), 80)
          } catch (err) { oops(err) }
        }
      }] : [{ label: 'OK', onClick: (x) => x.close() }]
    })
    refresh()
  }

  // ---- Me ------------------------------------------------------------------------

  const setRow = (ic, color, title, o = {}) => h('div', { class: 'set-row', onClick: o.onClick },
    h('div', { class: 'tile', style: { background: color } }, icon(ic, 19)),
    h('div', { class: 'grow' }, h('div', { class: 't-title', style: { fontWeight: 500 } }, title), o.sub ? h('div', { class: 't-small dim' }, o.sub) : null),
    o.value ? h('span', { class: 'value ellipsis' }, o.value) : null,
    o.checked != null ? h('div', { class: 'switch' + (o.checked ? ' on' : '') }) : h('span', { class: 'dimmer' }, icon('right', 18)))

  function renderMe(body) {
    const me = st.me
    const s = st.prefs.settings
    const toggle = (key) => () => M.call('prefs.set', { key, value: !s[key] }).catch(oops)
    const dht = st.net.dht
    body.replaceChildren(
      h('div', { class: 'cover' }),
      h('div', { class: 'me-avatar' }, avatar(me.username, me.publicKey, 96)),
      h('div', { class: 'me-name t-h1' }, me.username),
      h('div', { class: 'me-sub' }, [me.handle ? '@' + me.handle : null, 'ID ' + me.shortId].filter(Boolean).join('  ·  ')),
      h('div', { class: 'actions3' },
        h('button', { onClick: shareMe }, h('div', { class: 'orb' }, icon('share', 20)), 'Share'),
        h('button', { onClick: showQr }, h('div', { class: 'orb' }, icon('qr', 20)), 'QR code'),
        h('button', { onClick: () => copy(me.id, 'ID copied') }, h('div', { class: 'orb' }, icon('copy', 20)), 'Copy ID')),
      h('div', { class: 'section' }, 'Account'),
      h('div', { class: 'group' },
        setRow('user', '#3D7BE0', 'Name', { value: me.username, onClick: editName }),
        setRow('at', '#5F55D6', 'Username', { value: me.handle ? '@' + me.handle : 'Not set', onClick: editHandle }),
        setRow('key', '#D9822B', 'Recovery key', { sub: 'Restore your ID on a new device', onClick: showRecovery })),
      h('div', { class: 'section' }, 'Connections'),
      h('div', { class: 'group' },
        setRow('globe', '#3D7BE0', 'Internet', { sub: 'Reach contacts anywhere', checked: s.useInternet !== false, onClick: toggle('useInternet') }),
        setRow('share', '#2F9E5B', 'Pass messages on', { sub: dht && dht.relaying ? 'Helping others connect right now' : "Carry and relay others' locked messages", checked: s.carry !== false, onClick: toggle('carry') })),
      h('p', { class: 'footnote' }, 'Messages are end-to-end encrypted on every route. Phones nearby can also connect over Wi-Fi or Bluetooth in the Android app.'),
      h('div', { class: 'section' }, 'Notifications'),
      h('div', { class: 'group' },
        setRow('bell', '#C94F72', 'Message alerts', { sub: isIos ? 'While Meshron is open' : 'When Meshron is in the background', checked: !!s.notifications, onClick: toggle('notifications') }),
        setRow('volume', '#7A5CC2', 'Sounds', { checked: !!s.sounds, onClick: toggle('sounds') }),
        isIos ? null : setRow('enter', '#239C8E', 'Enter sends', { sub: 'Shift+Enter makes a new line', checked: s.enterSends !== false, onClick: toggle('enterSends') })),
      h('div', { class: 'section' }, 'About'),
      h('div', { class: 'group' },
        setRow('info', '#5B5B66', 'About Meshron', { sub: 'Version ' + VERSION, onClick: showAbout }),
        setRow('sliders', '#5B5B66', 'Connection details', { sub: 'For troubleshooting', onClick: showConnection })))
  }

  function qrSvg(text) {
    const qr = qrcode(0, 'M')
    qr.addData(text)
    qr.make()
    const n = qr.getModuleCount()
    let d = ''
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += `M${c} ${r}h1v1h-1z`
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
    svg.setAttribute('viewBox', `0 0 ${n} ${n}`)
    svg.setAttribute('shape-rendering', 'crispEdges')
    svg.innerHTML = `<path d="${d}" fill="#111"/>`
    return svg
  }

  function showQr() {
    modal({
      title: 'My QR code',
      body: h('div', { style: { textAlign: 'center' } },
        h('div', { class: 'qr-box' }, qrSvg(st.me.shareLink)),
        h('div', { class: 't-title' }, st.me.username),
        h('p', { class: 'dim t-small', style: { marginTop: '4px' } }, 'A friend scans this in the Meshron phone app to add you.')),
      buttons: [{ label: 'Copy link', onClick: () => copy(st.me.shareLink, 'Link copied') }, { label: 'Share', kind: 'primary', onClick: (m) => { m.close(); shareMe() } }]
    })
  }

  function editName() {
    const input = h('input', { class: 'input', value: st.me.username, maxlength: 40 })
    const save = async (m) => {
      try { await M.call('identity.rename', { name: input.value }); m.close() } catch (err) { oops(err) }
    }
    const m = modal({ title: 'Your name', body: h('div', {}, input, h('div', { class: 'hint' }, 'Your contacts see this name.')), buttons: [{ label: 'Cancel', onClick: (x) => x.close() }, { label: 'Save', kind: 'primary', onClick: save }] })
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(m) })
  }

  function editHandle() {
    const input = h('input', { class: 'input has-prefix', value: st.me.handle, maxlength: 24, autocapitalize: 'none', spellcheck: 'false' })
    const hint = h('div', { class: 'hint' }, 'Friends can find you by it. Leave empty to have none.')
    let timer = null
    let ok = true
    let m = null
    const setOk = (v) => { ok = v; const b = m && m.box.querySelector('.btn.primary'); if (b) b.disabled = !v }
    input.addEventListener('input', () => {
      clearTimeout(timer)
      const hh = input.value.trim().replace(/^@/, '').toLowerCase()
      if (!hh || hh === st.me.handle) { hint.textContent = ' '; hint.className = 'hint'; return setOk(true) }
      if (!/^[a-z0-9_]{3,20}$/.test(hh)) { hint.textContent = '3–20 letters, numbers or _'; hint.className = 'hint error'; return setOk(false) }
      hint.textContent = 'Checking…'
      hint.className = 'hint'
      setOk(false)
      timer = setTimeout(async () => {
        const res = await M.call('handle.find', { handle: hh }).catch(() => ({ keys: [] }))
        if (input.value.trim().replace(/^@/, '').toLowerCase() !== hh) return
        if (res.keys.length) { hint.textContent = 'Someone online is already using that'; hint.className = 'hint error'; return setOk(false) }
        hint.textContent = res.error === 'offline' ? "Can't check while offline, but you can save it" : 'Nobody online is using it'
        hint.className = 'hint ok'
        setOk(true)
      }, 500)
    })
    m = modal({
      title: 'Username', body: h('div', {}, h('div', { class: 'input-wrap' }, h('span', { class: 'prefix' }, '@'), input), hint),
      buttons: [{ label: 'Cancel', onClick: (x) => x.close() }, { label: 'Save', kind: 'primary', onClick: async (x) => { if (!ok) return; try { await M.call('identity.setHandle', { handle: input.value }); x.close() } catch (err) { oops(err) } } }]
    })
  }

  function showRecovery() {
    const box = h('div', {})
    const reveal = async () => {
      try {
        const key = await M.call('identity.recoveryKey')
        box.replaceChildren(
          h('div', { class: 'key-box selectable' }, key.match(/.{1,4}/g).join(' ')),
          h('div', { style: { display: 'flex', justifyContent: 'center', marginTop: '10px' } }, h('button', { class: 'btn ghost small', onClick: () => copy(key, 'Recovery key copied. Paste it somewhere safe.') }, icon('copy', 16), 'Copy')))
      } catch (err) { oops(err) }
    }
    box.appendChild(h('button', { class: 'btn soft wide', onClick: reveal }, icon('key', 18), 'Show my recovery key'))
    modal({
      title: 'Recovery key',
      body: h('div', {},
        h('p', { class: 'dim' }, 'These 52 characters bring your Meshron ID back on a new device. Write them down and keep them somewhere safe.'),
        h('div', { class: 'warn' }, icon('warn', 20), h('span', {}, 'Anyone who has this key can be you. Never send it to anyone, and never type it into a website.')),
        box)
    })
  }

  function showAbout() {
    modal({
      title: 'About Meshron',
      body: h('div', {},
        h('div', { style: { display: 'flex', gap: '14px', alignItems: 'center', marginBottom: '14px' } },
          h('div', { class: 'ob-logo', style: { width: '54px', height: '54px', borderRadius: '17px' } }, icon('logo', 28)),
          h('div', {}, h('div', { class: 't-h2' }, 'Meshron'), h('div', { class: 't-small dim' }, 'Version ' + VERSION + ' · by xalvxnLab'))),
        h('p', { class: 'dim' }, 'Private messages, device to device. No accounts, nothing stored on servers. Messages are end-to-end encrypted, and everything on this device is stored encrypted.'),
        h('div', { class: 'group', style: { margin: '16px 0 0' } },
          h('div', { class: 'set-row', onClick: () => window.open(PRIVACY_URL, '_blank') }, h('div', { class: 'grow' }, 'Privacy policy'), h('span', { class: 'dimmer' }, icon('right', 18))),
          h('div', { class: 'set-row', onClick: () => window.open(DOWNLOAD_URL, '_blank') }, h('div', { class: 'grow' }, 'Get Meshron for other devices'), h('span', { class: 'dimmer' }, icon('right', 18)))))
    })
  }

  function showConnection() {
    const n = st.net
    const d = n.dht || {}
    const kv = (a, b) => h('div', { class: 'kv' }, h('span', {}, a), h('span', { class: 'selectable' }, b))
    modal({
      title: 'Connection details', wide: true,
      body: h('div', {},
        kv('Network', netLabel()[0]),
        kv('Contacts connected', String(Object.values(n.peers).filter((p) => p.state === 'connected').length)),
        kv('Public address', d.host ? `${d.host}:${d.port}` : '–'),
        kv('Connection type', d.host ? (d.firewalled ? 'Behind a firewall' : 'Open') + (d.randomized ? ', strict NAT' : '') : '–'),
        kv('Relays known', String(d.relays || 0)),
        kv('Relaying for others', d.relaying ? 'Yes' : 'No'),
        kv('Key matches ID', n.keyMatches == null ? '–' : n.keyMatches ? 'Yes' : 'NO'),
        kv('Storage', 'Encrypted on this device'),
        n.lastError ? h('p', { class: 'hint error' }, n.lastError) : null,
        h('div', { class: 'log selectable' }, (n.log || []).slice(-25).join('\n') || '–'))
    })
  }

  function showSafety(key) {
    const c = contact(key)
    M.call('contacts.safetyCode', { key }).then((code) => {
      const m = modal({
        title: 'Safety code',
        body: h('div', {},
          h('p', { class: 'dim' }, `Compare these numbers with ${nameOf(key)}, in person or on a call. If they match on both devices, nobody is listening in.`),
          h('div', { class: 'code-grid selectable' }, code.map((x) => h('span', {}, x))),
          c && c.verified ? h('p', { class: 'hint ok' }, 'You marked this contact as verified.') : null),
        buttons: [c && c.verified
          ? { label: 'Remove verified mark', onClick: (x) => { M.call('contacts.setVerified', { key, verified: false }); x.close() } }
          : { label: 'They match', kind: 'primary', onClick: (x) => { M.call('contacts.setVerified', { key, verified: true }); x.close(); toast('Marked as verified') } }]
      })
      return m
    }).catch(oops)
  }

  // ---- Chat ----------------------------------------------------------------------

  const main = $('#main')
  const blobs = new Map() // "conv|id" -> object URL
  let chatEls = null

  function showHero() {
    main.replaceChildren(h('div', { class: 'hero' }, h('div', { class: 'empty' },
      h('div', { class: 'orb' }, icon('logo', 40)),
      h('div', { class: 't-h1' }, 'Meshron'),
      h('p', {}, 'Pick a chat on the left, or start a new one. Messages go straight to your friends, end-to-end encrypted.'),
      h('div', { class: 'actions' }, h('button', { class: 'btn primary', onClick: showNewChat }, icon('edit', 18), 'New chat')))))
    chatEls = null
  }

  async function openChat(conv, jumpTo) {
    if (st.open === conv && !jumpTo) { $('#shell').classList.add('chat-open'); return }
    saveDraft()
    st.open = conv
    st.shown = PAGE
    st.flash = jumpTo || null
    st.pendingBelow = 0
    st.rows = await M.call('chat.open', { conv })
    if (st.open !== conv) return
    const firstUnread = st.rows.find((r) => !r.outgoing && !r.read)
    st.firstUnread = firstUnread ? firstUnread.id : null
    st.seen = new Set(st.rows.map((r) => r.id))
    if (jumpTo) {
      const i = st.rows.findIndex((r) => r.id === jumpTo)
      if (i >= 0) st.shown = Math.max(PAGE, st.rows.length - i + 20)
    }
    buildChat()
    $('#shell').classList.add('chat-open')
    renderSide()
    markRead()
  }

  function closeChat() {
    saveDraft()
    st.open = null
    $('#shell').classList.remove('chat-open')
    showHero()
    renderSide()
  }

  function markRead() {
    if (st.open && M.native.focusState() && st.rows.some((r) => !r.outgoing && !r.read)) M.call('chat.markRead', { conv: st.open })
  }

  function saveDraft() {
    if (!st.open || !chatEls) return
    const text = chatEls.input.value.trim()
    if ((st.prefs.drafts[st.open] || '') === text) return
    if (text) st.prefs.drafts[st.open] = text
    else delete st.prefs.drafts[st.open]
    M.call('prefs.draft', { conv: st.open, text })
  }

  function headerStatus(conv) {
    if (isGroup(conv)) {
      const g = group(conv)
      if (!g) return ['', false]
      const others = g.members.filter((k) => k !== st.me.publicKey)
      const on = others.filter(isOnline).length
      return [`${g.members.length} members` + (on ? ` · ${on} online` : ''), false]
    }
    const c = contact(conv)
    if (c && c.status === 'REQUEST') return ['Wants to chat with you', false]
    if ((st.typing[conv] || 0) > Date.now()) return ['typing…', true]
    if (isOnline(conv)) return ['Online', true]
    return ["Offline · they'll get messages later", false]
  }

  function refreshHeader() {
    if (!chatEls || !st.open) return
    const conv = st.open
    const title = convTitle(conv)
    const [status, on] = headerStatus(conv)
    chatEls.who.replaceChildren(
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, h('span', { class: 't-title ellipsis' }, title), verifiedBadge(conv)),
      h('div', { class: 'status ellipsis' + (on ? ' on' : '') }, status))
    chatEls.av.replaceChildren(avatar(title, conv, 42, { group: isGroup(conv), online: !isGroup(conv) && isOnline(conv) }))
    const c = !isGroup(conv) && contact(conv)
    chatEls.banner.hidden = !(c && c.status === 'REQUEST')
  }

  function buildChat() {
    const conv = st.open
    const av = h('div', {})
    const who = h('div', { class: 'who', onClick: () => (isGroup(conv) ? showGroupInfo(conv) : showSafety(conv)) })
    const msgs = h('div', { class: 'msgs', id: 'msgs' })
    const toBottom = h('button', { class: 'to-bottom hide', onClick: () => scrollBottom(true) }, icon('down', 22))
    const input = h('textarea', { rows: 1, placeholder: 'Message', maxlength: st.limits.MAX_BODY })
    const send = h('button', { class: 'send-btn hide', title: 'Send (right-click or hold: send urgently)', onClick: () => sendText() }, icon('send', 20))
    if (!isGroup(conv)) onContext(send, (x, y) => {
      if (st.editing || !input.value.trim()) return
      menu(x - 220, y - 70, [{ icon: 'zap', label: 'Send urgently — every Meshron phone nearby passes it on', onClick: () => sendText(true) }])
    })
    const counter = h('div', { class: 'counter', hidden: true })
    const emojiBtn = isIos ? null : h('button', { class: 'icon-btn', title: 'Emoji', onClick: () => toggleEmoji() }, icon('smile', 24))
    const banner = h('div', { class: 'banner', hidden: true },
      h('span', { class: 'grow' }, "This person isn't in your contacts yet."),
      h('button', { class: 'btn ghost small danger', onClick: () => blockContact(conv) }, 'Block'),
      h('button', { class: 'btn primary small', onClick: () => M.call('contacts.add', { key: conv, name: '' }) }, 'Accept'))
    const replyBar = h('div', { class: 'reply-bar', hidden: true })
    const composer = h('div', { class: 'composer' },
      h('button', { class: 'icon-btn', title: 'Send a photo or file', onClick: () => $('#file-input').click() }, icon('clip', 22)),
      emojiBtn, input, send, counter)

    const autosize = () => {
      input.style.height = 'auto'
      input.style.height = Math.min(160, input.scrollHeight + 2) + 'px'
      send.classList.toggle('hide', !input.value.trim())
      const left = st.limits.MAX_BODY - input.value.length
      counter.hidden = left > 200
      counter.textContent = left + ' left'
    }
    input.addEventListener('input', autosize)
    input.addEventListener('input', () => {
      // "typing…" for the other person, at most every 4 seconds (1:1 chats, live only).
      if (isGroup(conv) || !input.value.trim() || Date.now() - lastTyping < 4000) return
      lastTyping = Date.now()
      M.call('chat.typing', { conv }).catch(() => {})
    })
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && (st.reply || st.editing)) { e.preventDefault(); cancelReply(); return }
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && !isIos && st.prefs.settings.enterSends !== false) {
        e.preventDefault()
        sendText()
      }
    })
    input.addEventListener('paste', (e) => {
      const files = [...(e.clipboardData ? e.clipboardData.files : [])]
      if (files.length) { e.preventDefault(); sendFiles(files) }
    })
    msgs.addEventListener('scroll', () => {
      const away = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight
      toBottom.classList.toggle('hide', away < 240)
      if (away < 60 && st.pendingBelow) { st.pendingBelow = 0; updateToBottom() }
    })

    const head = h('div', { class: 'chat-head' },
      h('button', { class: 'icon-btn back', onClick: closeChat, 'aria-label': 'Back' }, icon('back')),
      av, who,
      isGroup(conv) ? null : h('button', { class: 'icon-btn', title: 'Video call', onClick: () => startCall(conv, true) }, icon('video', 22)),
      isGroup(conv) ? null : h('button', { class: 'icon-btn', title: 'Voice call', onClick: () => startCall(conv, false) }, icon('phone', 21)),
      h('button', { class: 'icon-btn', title: 'More', onClick: (e) => { const r = e.currentTarget.getBoundingClientRect(); chatHeadMenu(conv, r.right - 210, r.bottom + 6) } }, icon('more')))

    main.classList.add('enter')
    setTimeout(() => main.classList.remove('enter'), 500)
    main.replaceChildren(head, banner, msgs, toBottom, replyBar, composer)
    chatEls = { av, who, msgs, input, send, toBottom, banner, composer, autosize, replyBar }
    st.reply = null
    st.editing = null
    input.value = st.prefs.drafts[conv] || ''
    autosize()
    refreshHeader()
    renderMsgs(true)
    if (!isIos && innerWidth > 820) input.focus()
  }

  let lastTyping = 0
  function renderReplyBar() {
    if (!chatEls) return
    const r = st.editing || st.reply
    const bar = chatEls.replyBar
    bar.hidden = !r
    if (!r) return bar.replaceChildren()
    const who = st.editing ? 'Editing message' : 'Replying to ' + (r.outgoing ? 'yourself' : isGroup(r.peerKey) ? nameOf(r.senderKey) : convTitle(r.peerKey))
    bar.replaceChildren(
      h('div', { class: 'rb-line' }),
      h('div', { class: 'rb-text' }, h('div', { class: 'rb-who' }, st.editing ? icon('edit', 14) : icon('reply', 14), who), h('div', { class: 'rb-quote ellipsis' }, quoteOf(r))),
      h('button', { class: 'icon-btn', title: 'Cancel', onClick: cancelReply }, icon('x', 20)))
  }
  function startReply(row) {
    if (!chatEls || row.kind === 'deleted') return
    if (st.editing) { chatEls.input.value = ''; chatEls.autosize() }
    st.editing = null
    st.reply = row
    renderReplyBar()
    chatEls.input.focus()
  }
  function startEdit(row) {
    if (!chatEls) return
    st.reply = null
    st.editing = row
    chatEls.input.value = bodyText(row.body)
    chatEls.autosize()
    renderReplyBar()
    chatEls.input.focus()
  }
  function cancelReply() {
    if (st.editing && chatEls) { chatEls.input.value = st.prefs.drafts[st.open] || ''; chatEls.autosize() }
    st.reply = null
    st.editing = null
    renderReplyBar()
  }
  function react(row, e) {
    const mine = (row.reactions || {})[st.me.publicKey]
    M.call('chat.react', { conv: row.peerKey, id: row.id, e: mine === e ? '' : e }).catch(oops)
  }
  function jumpTo(id) {
    const i = st.rows.findIndex((r) => r.id === id)
    if (i < 0) return
    if (st.rows.length - i > st.shown) st.shown = st.rows.length - i + 10
    st.flash = id
    renderMsgs()
  }
  async function unsend(row) {
    if (!(await confirmBox('Delete for everyone?', "It's removed from this chat on every device that has it, including theirs. Anyone who already read it may remember it.", 'Delete', true))) return
    M.call('chat.unsend', { conv: row.peerKey, id: row.id }).catch(oops)
  }

  const SOS_PREFIX = '🆘 SOS'
  function sosBody(text, pos) {
    let out = SOS_PREFIX + ': ' + (String(text || '').trim() || 'I need help.')
    if (pos) out += '\n📍 https://maps.google.com/?q=' + pos.latitude.toFixed(6) + ',' + pos.longitude.toFixed(6) + ' (±' + Math.round(pos.accuracy) + ' m)'
    return out + '\n🕒 ' + new Date().toLocaleString([], { dateStyle: 'short', timeStyle: 'short' })
  }

  /** Emergency SOS: message + location to every contact as urgent, plus call / SMS / Wi-Fi routes. */
  function showSos() {
    let pos = null
    const msg = h('textarea', { class: 'input', rows: 2, maxlength: 500 })
    msg.value = 'I need help.'
    const where = h('div', { class: 'sos-where' }, 'Finding your location…')
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (p) => { pos = p.coords; where.textContent = '📍 Location added (±' + Math.round(p.coords.accuracy) + ' m)'; where.classList.add('ok') },
        () => { where.textContent = 'Location unavailable — the SOS goes without it' },
        { enableHighAccuracy: true, timeout: 15000, maximumAge: 120000 })
    } else where.textContent = 'Location unavailable — the SOS goes without it'
    const note = h('p', { class: 'sos-note' }, 'Sends your message and location to all your contacts. Any Meshron phone nearby passes it on, even with no signal.')
    const big = h('button', { class: 'sos-big', onClick: async () => {
      big.disabled = true
      try {
        const n = await M.call('sos.send', { text: sosBody(msg.value, pos) })
        note.textContent = n ? `Sent to ${n} ${n === 1 ? 'contact' : 'contacts'}. Every Meshron phone that comes near passes it on, and the first one with internet delivers it.` : 'You have no contacts yet. Use the call or SMS buttons below.'
        big.querySelector('small').textContent = 'Send again'
      } catch (err) { oops(err) }
      big.disabled = false
    } }, h('b', {}, 'SOS'), h('small', {}, 'Tap to send'))
    const route = (ic, title, sub, onClick) => h('button', { class: 'sos-route', onClick }, h('span', { class: 'ic' }, icon(ic, 20)), h('span', {}, h('b', {}, title), h('small', {}, sub)))
    const box = h('div', { class: 'sos' },
      h('div', { class: 'sos-head' }, h('div', {}, h('div', { class: 'sos-title' }, 'Emergency SOS'), h('div', { class: 'sos-sub' }, 'Works without internet')),
        h('button', { class: 'icon-btn sos-x', 'aria-label': 'Close', onClick: () => box.remove() }, icon('x', 24))),
      h('div', { class: 'sos-body' }, msg, where, h('div', { class: 'sos-ring' }, big), note,
        h('div', { class: 'sos-label' }, 'Other ways to get help'),
        route('phone', 'Call emergency services', 'Works on any network, even without credit or a SIM', () => { location.href = 'tel:112' }),
        route('chat', 'Send as a text (SMS)', 'Needs a little phone signal, not data', () => { location.href = 'sms:?&body=' + encodeURIComponent(sosBody(msg.value, pos)) }),
        route('globe', 'Join an open Wi-Fi', isIos ? 'Open Settings → Wi-Fi and pick a network without a lock' : 'Open your Wi-Fi settings and pick an open network', () => {
          if (M.native.openWifi) M.native.openWifi()
          else toast(isIos ? 'Open Settings → Wi-Fi and pick a network without a lock' : 'Open Wi-Fi settings and pick an open network', 5000)
        })))
    layer.appendChild(box)
  }

  function chatHeadMenu(conv, x, y) {
    const muted = st.prefs.muted.includes(conv)
    const pinned = st.prefs.pinned.includes(conv)
    menu(x, y, [
      isGroup(conv) ? { icon: 'users', label: 'Members', onClick: () => showGroupInfo(conv) } : { icon: 'shield', label: 'Verify safety code', onClick: () => showSafety(conv) },
      { icon: 'pin', label: pinned ? 'Unpin' : 'Pin to top', onClick: () => M.call('prefs.pin', { conv, on: !pinned }) },
      { icon: muted ? 'bell' : 'bellOff', label: muted ? 'Unmute' : 'Mute', onClick: () => M.call('prefs.mute', { conv, on: !muted }) },
      { icon: 'trash', label: 'Delete chat', danger: true, onClick: () => deleteChat(conv) },
      isGroup(conv) ? null : { icon: 'block', label: 'Block', danger: true, onClick: () => blockContact(conv) }
    ])
  }

  function showGroupInfo(conv) {
    const g = group(conv)
    if (!g) return
    modal({
      title: g.name,
      body: h('div', {},
        h('p', { class: 'dim t-small' }, `${g.members.length} members. Everyone's messages are encrypted for the group.`),
        h('div', { style: { marginTop: '8px', maxHeight: '340px', overflowY: 'auto' } }, g.members.map((k) => {
          const me = k === st.me.publicKey
          const known = !!contact(k)
          return h('div', { class: 'pick', style: { cursor: me || known ? 'default' : 'pointer' }, onClick: () => { if (!me && !known) M.call('contacts.add', { key: k, name: nameOf(k) === shortKey(k) ? '' : nameOf(k) }).then(() => toast('Contact added')) } },
            avatar(me ? st.me.username : nameOf(k), k, 42, { online: !me && isOnline(k) }),
            h('div', { style: { flex: 1, minWidth: 0 } }, h('div', { class: 't-title ellipsis' }, me ? st.me.username + ' (you)' : nameOf(k)), h('div', { class: 't-small dim' }, k === g.creatorKey ? 'Created the group' : me ? '' : known ? '' : 'Tap to add as a contact')))
        })))
    })
  }

  function scrollBottom(smooth) {
    if (!chatEls) return
    chatEls.msgs.scrollTo({ top: chatEls.msgs.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
    st.pendingBelow = 0
    updateToBottom()
  }
  function updateToBottom() {
    if (!chatEls) return
    const old = chatEls.toBottom.querySelector('.badge')
    if (old) old.remove()
    if (st.pendingBelow) chatEls.toBottom.appendChild(h('span', { class: 'badge' }, st.pendingBelow))
  }

  const URL_RE = /(https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)\]])/g
  function richText(text, highlight) {
    const out = []
    for (const part of text.split(URL_RE)) {
      if (!part) continue
      if (/^https?:\/\//.test(part)) out.push(h('a', { href: part, target: '_blank', rel: 'noopener noreferrer' }, part))
      else if (highlight && part.toLowerCase().includes(highlight)) {
        let rest = part
        let i
        while ((i = rest.toLowerCase().indexOf(highlight)) >= 0) {
          if (i) out.push(rest.slice(0, i))
          out.push(h('mark', {}, rest.slice(i, i + highlight.length)))
          rest = rest.slice(i + highlight.length)
        }
        if (rest) out.push(rest)
      } else out.push(part)
    }
    return out
  }
  const BIG_EMOJI = /^(?:\p{Extended_Pictographic}|\p{Emoji_Component}|‍|️|\s)+$/u

  async function loadBlob(row, mime) {
    const k = row.peerKey + '|' + row.id
    if (blobs.has(k)) return blobs.get(k)
    const parts = []
    let offset = 0
    for (;;) {
      const r = await M.call('file.read', { conv: row.peerKey, id: row.id, offset })
      const bin = atob(r.data)
      const bytes = new Uint8Array(bin.length)
      for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
      parts.push(bytes)
      offset += bytes.length
      if (r.done || !bytes.length) break
    }
    const blob = new Blob(parts, { type: mime })
    const entry = { url: URL.createObjectURL(blob), blob }
    blobs.set(k, entry)
    return entry
  }

  async function openFile(row) {
    const f = fileInfo(row)
    if (!f || !row.localPath) return
    try {
      if (M.native.openFile) return await M.native.openFile({ path: row.localPath })
      const b = await loadBlob(row, f.mime)
      await M.native.saveFile({ blob: b.blob, name: f.name, path: row.localPath })
    } catch (err) { oops(err) }
  }

  function lightbox(row, f, url) {
    const close = () => { box.remove(); document.removeEventListener('keydown', onKey) }
    const onKey = (e) => { if (e.key === 'Escape') close() }
    const box = h('div', { class: 'lightbox', onClick: close },
      h('img', { src: url, alt: f.name }),
      h('div', { class: 'bar', onClick: (e) => e.stopPropagation() },
        h('button', { class: 'icon-btn', title: isIos ? 'Share' : 'Open', onClick: () => openFile(row) }, icon(isIos ? 'share' : 'download')),
        h('button', { class: 'icon-btn', title: 'Close', onClick: close }, icon('x'))))
    document.addEventListener('keydown', onKey)
    layer.appendChild(box)
  }

  function messageMenu(row, x, y) {
    const f = row.kind === 'file' ? fileInfo(row) : null
    const gone = row.kind === 'deleted'
    const canEdit = row.outgoing && row.kind === 'text' && Date.now() - row.sentAt < EDIT_WINDOW
    menu(x, y, [
      gone ? null : { icon: 'reply', label: 'Reply', onClick: () => startReply(row) },
      canEdit ? { icon: 'edit', label: 'Edit', onClick: () => startEdit(row) } : null,
      row.kind === 'text' ? { icon: 'copy', label: 'Copy', onClick: () => copy(bodyText(row.body)) } : null,
      f && row.localPath ? { icon: isIos ? 'share' : 'download', label: isIos ? 'Share' : 'Open', onClick: () => openFile(row) } : null,
      f && row.localPath && M.native.showFile ? { icon: 'file', label: 'Show in folder', onClick: () => M.native.showFile({ path: row.localPath }) } : null,
      { icon: 'info', label: `${row.outgoing ? 'Sent' : 'Received'} ${dayLabel(row.sentAt)}, ${clock(row.sentAt)}`, onClick: () => {} },
      { icon: 'trash', label: 'Delete for me', danger: true, onClick: () => M.call('chat.deleteMessage', { conv: row.peerKey, id: row.id }) },
      row.outgoing && (row.kind === 'text' || row.kind === 'file') ? { icon: 'trash', label: 'Delete for everyone', danger: true, onClick: () => unsend(row) } : null
    ])
    // Quick reactions across the top of the menu.
    const m = $('#menu')
    if (m && !gone) {
      const mine = (row.reactions || {})[st.me.publicKey]
      m.prepend(h('div', { class: 'react-row' }, QUICK.map((e) => h('button', { class: e === mine ? 'on' : '', onClick: () => { closeMenu(); react(row, e) } }, e))))
      const r = m.getBoundingClientRect()
      if (r.bottom > innerHeight - 8) m.style.top = Math.max(8, innerHeight - r.height - 8) + 'px'
      if (r.right > innerWidth - 8) m.style.left = Math.max(8, innerWidth - r.width - 8) + 'px'
    }
  }

  /** Quick reaction picker on its own (hover button on desktop). */
  function reactPicker(row, x, y) {
    closeMenu()
    const mine = (row.reactions || {})[st.me.publicKey]
    const el = h('div', { class: 'menu react-pop', id: 'menu' }, h('div', { class: 'react-row' }, QUICK.map((e) => h('button', { class: e === mine ? 'on' : '', onClick: () => { closeMenu(); react(row, e) } }, e))))
    layer.appendChild(h('div', { class: 'menu-catcher', id: 'menu-catcher', onMousedown: closeMenu }))
    layer.appendChild(el)
    const r = el.getBoundingClientRect()
    el.style.left = Math.max(8, Math.min(x - r.width / 2, innerWidth - r.width - 8)) + 'px'
    el.style.top = Math.max(8, y - r.height - 8) + 'px'
  }

  /** Swipe a message sideways (either way) on touch screens to reply to it. */
  function swipeToReply(el, row) {
    let x0 = 0
    let y0 = 0
    let dx = 0
    let mode = null
    el.addEventListener('touchstart', (e) => { const t = e.touches[0]; x0 = t.clientX; y0 = t.clientY; dx = 0; mode = null; el.style.transition = 'none' }, { passive: true })
    el.addEventListener('touchmove', (e) => {
      const t = e.touches[0]
      const mx = t.clientX - x0
      const my = t.clientY - y0
      if (!mode && (Math.abs(mx) > 10 || Math.abs(my) > 10)) mode = Math.abs(mx) > Math.abs(my) * 1.3 ? 'x' : 'y'
      if (mode !== 'x') return
      dx = Math.max(-96, Math.min(96, mx * 0.6))
      el.style.transform = 'translateX(' + dx + 'px)'
      el.classList.toggle('armed', Math.abs(dx) >= 60)
      el.dataset.dir = dx > 0 ? 'r' : 'l'
    }, { passive: true })
    const end = () => {
      if (mode === 'x' && Math.abs(dx) >= 60) startReply(row)
      el.style.transition = 'transform .25s cubic-bezier(.3,1.5,.6,1)'
      el.style.transform = ''
      el.classList.remove('armed')
      mode = null
    }
    el.addEventListener('touchend', end, { passive: true })
    el.addEventListener('touchcancel', end, { passive: true })
  }

  function bubbleFor(row, first) {
    const groupChat = isGroup(row.peerKey)
    const kids = []
    let sos = false
    let media = false
    if (groupChat && !row.outgoing && first && row.senderKey) {
      const [a] = AVATARS[javaHash(row.senderKey) % AVATARS.length]
      kids.push(h('div', { class: 'sender', style: { color: a } }, nameOf(row.senderKey)))
    }
    if (row.kind === 'file') {
      const f = fileInfo(row)
      const status = st.fileStatus[row.peerKey + '|' + row.id]
      if (f && f.mime.startsWith('image/') && row.localPath && f.size < 12 * 1024 * 1024) {
        media = true
        const img = h('img', { class: 'photo', alt: f.name })
        loadBlob(row, f.mime).then((b) => { img.src = b.url; img.onclick = () => lightbox(row, f, b.url) }).catch(() => { img.alt = "Couldn't show this photo" })
        kids.push(img)
      } else {
        kids.push(h('div', { class: 'file-card', onClick: () => (row.localPath ? openFile(row) : M.call('file.retry')) },
          h('div', { class: 'ic' }, icon(f && f.mime.startsWith('image/') ? 'download' : 'file', 22)),
          h('div', { style: { minWidth: 0 } }, h('div', { class: 'fname ellipsis' }, f ? f.name : 'File'),
            h('div', { class: 'fsub' }, row.localPath ? (f ? fileSize(f.size) : '') : status || 'Waiting to download…'))))
      }
    } else if (row.kind === 'invite') {
      kids.push(h('div', { class: 'invite-card' }, icon('users', 20), h('span', {}, displayBody(row))))
    } else if (row.kind === 'deleted') {
      kids.push(h('div', { class: 'text deleted' }, icon('block', 14), displayBody(row)))
    } else if (row.kind === 'text' && row.body.startsWith(SOS_PREFIX)) {
      sos = true
      kids.push(h('div', { class: 'text' }, richText(row.body)))
      const link = /https:\/\/maps\.google\.com\/\?q=[-0-9.,]+/.exec(row.body)
      if (link) kids.push(h('a', { class: 'sos-map', href: link[0], target: '_blank', rel: 'noopener noreferrer' }, 'Open map'))
    } else if (REPLY_RE.test(row.body)) {
      const [, q, text] = REPLY_RE.exec(row.body)
      const i = st.rows.findIndex((r) => r.id === row.id)
      let quoted = null
      for (let k = i - 1; k >= 0; k--) if (st.rows[k].kind !== 'deleted' && quoteOf(st.rows[k]) === q) { quoted = st.rows[k]; break }
      const who = quoted ? (quoted.outgoing ? 'You' : isGroup(row.peerKey) ? nameOf(quoted.senderKey) : convTitle(row.peerKey)) : 'Reply'
      kids.push(h('div', { class: 'quote' + (quoted ? ' link' : ''), onClick: () => quoted && jumpTo(quoted.id) }, h('div', { class: 'q-who' }, who), h('div', { class: 'q-text' }, q)))
      const hl = st.flash === row.id && st.query.trim().length >= 2 ? st.query.trim().toLowerCase() : null
      kids.push(h('div', { class: 'text' }, richText(text, hl)))
    } else {
      const big = row.body.length <= 12 && BIG_EMOJI.test(row.body) && !/^[\d\s#*]+$/.test(row.body)
      const hl = st.flash === row.id && st.query.trim().length >= 2 ? st.query.trim().toLowerCase() : null
      const reply = /^↪ Story: (.*)\n([\s\S]+)$/.exec(row.body)
      if (reply) {
        kids.push(h('div', { class: 'story-quote' }, icon('zap', 14), h('span', { class: 'ellipsis' }, (row.outgoing ? 'You replied to their story: ' : 'Replied to your story: ') + reply[1])))
        const rbig = reply[2].length <= 12 && BIG_EMOJI.test(reply[2])
        kids.push(h('div', { class: 'text' + (rbig ? ' big' : '') }, richText(reply[2], hl)))
      } else kids.push(h('div', { class: 'text' + (big ? ' big' : '') }, richText(row.body, hl)))
    }
    kids.push(h('div', { class: 'meta' }, row.edited && row.kind === 'text' ? h('span', { class: 'edited' }, 'edited ·') : null, clock(row.sentAt), row.outgoing && row.kind !== 'deleted' ? tickIcon(groupChat && row.status === DELIVERED ? SENT : row.status, 15) : null))
    const b = h('div', { class: 'bubble' + (media ? ' media' : '') + (row.kind === 'deleted' ? ' gone' : '') + (sos ? ' sos' : '') }, kids)
    onContext(b, (x, y) => messageMenu(row, x, y))
    if (row.kind === 'deleted') return b
    // Double-click / double-tap: quick heart.
    b.addEventListener('dblclick', (e) => { e.preventDefault(); window.getSelection && window.getSelection().removeAllRanges(); react(row, '❤️') })
    const out = [b]
    const reactions = Object.entries(row.reactions || {})
    if (reactions.length) {
      const counts = {}
      for (const [, e] of reactions) counts[e] = (counts[e] || 0) + 1
      const mine = (row.reactions || {})[st.me.publicKey]
      const tops = Object.entries(counts).sort((a, c) => c[1] - a[1]).slice(0, 4).map(([e]) => e)
      out.push(h('button', {
        class: 'reacts' + (mine ? ' mine' : ''),
        title: reactions.map(([k, e]) => e + ' ' + (k === st.me.publicKey ? 'You' : nameOf(k))).join('\n'),
        onClick: (e) => { if (mine) react(row, mine); else { const r = e.currentTarget.getBoundingClientRect(); reactPicker(row, r.left + r.width / 2, r.top) } }
      }, tops.join(''), reactions.length > 1 ? h('span', {}, String(reactions.length)) : null))
    }
    // Hover actions on desktop: react and reply.
    out.push(h('div', { class: 'msg-actions' },
      h('button', { title: 'React', onClick: (e) => { const r = e.currentTarget.getBoundingClientRect(); reactPicker(row, r.left + r.width / 2, r.top) } }, icon('smile', 17)),
      h('button', { title: 'Reply', onClick: () => startReply(row) }, icon('reply', 17))))
    return out
  }

  const sameGroup = (a, b) => a.outgoing === b.outgoing && a.senderKey === b.senderKey && Math.abs(b.localTime - a.localTime) < 180000 && a.kind !== 'invite' && b.kind !== 'invite'

  function renderMsgs(initial) {
    if (!chatEls) return
    const box = chatEls.msgs
    const away = box.scrollHeight - box.scrollTop - box.clientHeight
    const stick = initial || away < 90
    const fromBottom = box.scrollHeight - box.scrollTop
    const rows = st.rows.slice(-st.shown)
    const kids = [h('div', { class: 'spacer' })]
    if (st.rows.length > rows.length) {
      kids.push(h('button', { class: 'btn soft small load-earlier', onClick: () => { st.shown += PAGE; renderMsgs(false, true) } }, 'Show earlier messages'))
    } else if (!isGroup(st.open)) {
      kids.push(h('div', { class: 'day', style: { background: 'none', color: 'var(--text3)', display: 'flex', gap: '6px', alignItems: 'center' } }, icon('lock', 13), 'Messages here are end-to-end encrypted'))
    }
    let flashEl = null
    rows.forEach((m, i) => {
      const older = rows[i - 1]
      const newer = rows[i + 1]
      const newDay = !older || startOfDay(older.localTime) !== startOfDay(m.localTime)
      if (newDay) kids.push(h('div', { class: 'day' }, dayLabel(m.localTime)))
      if (m.id === st.firstUnread) kids.push(h('div', { class: 'new-line' }, 'New messages'))
      const first = newDay || !sameGroup(older, m) || m.id === st.firstUnread
      const last = !newer || !sameGroup(m, newer) || startOfDay(newer.localTime) !== startOfDay(m.localTime)
      const isNew = !st.seen.has(m.id)
      st.seen.add(m.id)
      const el = h('div', { class: 'msg' + (m.outgoing ? ' mine' : '') + (first ? ' first' : '') + (last ? ' last' : '') + (isNew ? ' new' : '') + (st.flash === m.id ? ' flash' : '') + (m.reactions && Object.keys(m.reactions).length && m.kind !== 'deleted' ? ' has-reacts' : '') },
        m.kind === 'deleted' ? null : h('div', { class: 'swipe-hint' }, icon('reply', 16)),
        h('div', { class: 'msg-body' }, bubbleFor(m, first)))
      if (m.kind !== 'deleted') swipeToReply(el.querySelector('.msg-body'), m)
      if (st.flash === m.id) flashEl = el
      kids.push(el)
    })
    box.replaceChildren(...kids)
    if (flashEl) {
      flashEl.scrollIntoView({ block: 'center' })
      st.flash = null
    } else if (initial && st.firstUnread) {
      const line = box.querySelector('.new-line')
      if (line) line.scrollIntoView({ block: 'center' })
      else box.scrollTop = box.scrollHeight
    } else if (stick) box.scrollTop = box.scrollHeight
    else if (arguments[1]) box.scrollTop = box.scrollHeight - fromBottom
  }

  // ---- Sending ---------------------------------------------------------------------

  async function sendText(urgent = false) {
    if (!chatEls || !st.open) return
    const text = chatEls.input.value.trim()
    if (!text) return
    chatEls.input.value = ''
    chatEls.autosize()
    delete st.prefs.drafts[st.open]
    M.call('prefs.draft', { conv: st.open, text: '' })
    st.firstUnread = null
    const edit = st.editing
    const reply = st.reply
    st.editing = null
    st.reply = null
    renderReplyBar()
    lastTyping = 0
    try {
      if (edit) await M.call('chat.edit', { conv: st.open, id: edit.id, text })
      else {
        await M.call('chat.send', { conv: st.open, text, replyTo: reply ? reply.id : null, urgent: !!urgent })
        if (urgent) toast('Sent urgently: every Meshron phone nearby will pass it on')
        scrollBottom(false)
      }
    } catch (err) { oops(err) }
    if (!isIos) chatEls.input.focus()
  }

  function toBase64(bytes) {
    let bin = ''
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000))
    return btoa(bin)
  }

  /** Shows picked / pasted / dropped files first: photo previews, names and sizes, a caption. */
  function sendFiles(list) {
    const conv = st.open
    if (!conv) return
    const files = list.filter((x) => x.size > 0)
    if (!files.length) return
    const urls = []
    const caption = h('textarea', { class: 'input', rows: 2, placeholder: 'Add a caption…', maxlength: 1000 })
    const items = files.map((file) => {
      const big = file.size > st.limits.MAX_FILE
      if (/^image\//.test(file.type) && !big) {
        const url = URL.createObjectURL(file)
        urls.push(url)
        return h('div', { class: 'fp-item photo' }, h('img', { src: url, alt: file.name }), h('div', { class: 'fp-name ellipsis' }, fileSize(file.size)))
      }
      return h('div', { class: 'fp-item' + (big ? ' big' : '') }, h('div', { class: 'fp-ic' }, icon('file', 28)),
        h('div', { class: 'fp-name ellipsis' }, file.name || 'File'), h('div', { class: 'fp-size' }, big ? 'Over 25 MB, can\'t send' : fileSize(file.size)))
    })
    const ok = files.filter((x) => x.size <= st.limits.MAX_FILE)
    modal({
      title: files.length === 1 ? 'Send ' + (/^image\//.test(files[0].type) ? 'photo' : 'file') : `Send ${files.length} files`,
      wide: true,
      body: h('div', { class: 'fp' }, h('div', { class: 'fp-grid' + (files.length === 1 ? ' one' : '') }, items), h('div', { class: 'dim t-small', style: { margin: '10px 4px 6px' } }, 'To ' + convTitle(conv)), caption),
      onClose: () => urls.forEach((u) => URL.revokeObjectURL(u)),
      buttons: [
        { label: 'Cancel', onClick: (m) => m.close() },
        { label: ok.length > 1 ? `Send ${ok.length}` : 'Send', kind: 'primary', disabled: !ok.length, onClick: async (m) => {
          const text = caption.value.trim()
          m.close()
          await uploadFiles(ok, conv)
          if (text) M.call('chat.send', { conv, text }).catch(oops)
        } }
      ]
    })
  }

  async function uploadFiles(files, conv) {
    if (!conv) return
    for (const file of files) {
      if (file.size > st.limits.MAX_FILE) { toast(`“${file.name}” is over 25 MB`); continue }
      if (!file.size) continue
      st.sending++
      const note = h('div', { class: 'toast' }, h('span', { style: { display: 'inline-flex', gap: '10px', alignItems: 'center' } }, h('div', { class: 'spinner' }), `Sending ${file.name}…`))
      $('#toasts').appendChild(note)
      try {
        const id = await M.call('file.begin', { conv, name: file.name || 'photo.png', mime: file.type || 'application/octet-stream', size: file.size })
        const bytes = new Uint8Array(await file.arrayBuffer())
        const step = 240 * 1024
        for (let o = 0; o < bytes.length; o += step) await M.call('file.chunk', { id, data: toBase64(bytes.subarray(o, o + step)) })
        await M.call('file.end', { id })
        if (st.open === conv) scrollBottom(false)
      } catch (err) { oops(err) }
      note.remove()
      st.sending--
    }
  }
  $('#file-input').addEventListener('change', (e) => {
    sendFiles([...e.target.files])
    e.target.value = ''
  })

  // Drag files onto the chat (desktop).
  let dragDepth = 0
  main.addEventListener('dragenter', (e) => {
    if (!st.open || !e.dataTransfer || ![...e.dataTransfer.types].includes('Files')) return
    e.preventDefault()
    if (dragDepth++ === 0) main.appendChild(h('div', { class: 'drop', id: 'drop' }, h('div', { class: 'empty' }, h('div', { class: 'orb' }, icon('clip', 34)), h('div', { class: 't-h2' }, 'Drop to send'))))
  })
  main.addEventListener('dragover', (e) => { if (st.open) e.preventDefault() })
  const endDrag = () => { dragDepth = 0; const d = $('#drop'); if (d) d.remove() }
  main.addEventListener('dragleave', () => { if (--dragDepth <= 0) endDrag() })
  main.addEventListener('drop', (e) => {
    e.preventDefault()
    endDrag()
    if (st.open && e.dataTransfer) sendFiles([...e.dataTransfer.files])
  })

  const EMOJI = [
    ['Smileys', '😀 😁 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😜 🤪 🤗 🤔 🤨 😐 😏 😴 😌 😔 😢 😭 😤 😡 🤯 😱 🥳 😎 🤓 🥺 😬 🙄 🤐 🤒'],
    ['Gestures', '👍 👎 👌 ✌️ 🤞 🤟 🤙 👋 👏 🙌 🙏 💪 🤝 👀 🫶 🫡'],
    ['Hearts', '❤️ 🧡 💛 💚 💙 💜 🖤 🤍 💔 💕 💖 ✨ 🔥 💯 🎉 🎊'],
    ['Things', '☕ 🍕 🍔 🍺 🎂 ⚽ 🎮 🎵 📷 💻 📱 🚗 ✈️ 🏠 🌙 ☀️ 🌧️ ⏰ 💤 ✅ ❌ ❓ ❗ 💡']
  ]
  function toggleEmoji() {
    const old = $('#emoji')
    if (old) return old.remove()
    const pop = h('div', { class: 'emoji-pop', id: 'emoji' }, EMOJI.map(([cat, list]) => [h('div', { class: 'cat' }, cat),
      h('div', { class: 'grid' }, list.split(' ').map((e) => h('button', {
        onClick: () => {
          const i = chatEls.input
          const at = i.selectionStart
          i.value = i.value.slice(0, at) + e + i.value.slice(i.selectionEnd)
          i.selectionStart = i.selectionEnd = at + e.length
          chatEls.autosize()
          i.focus()
        }
      }, e)))]))
    chatEls.composer.appendChild(pop)
    const away = (e) => { if (!pop.contains(e.target) && !e.target.closest('.icon-btn')) { pop.remove(); document.removeEventListener('mousedown', away) } }
    setTimeout(() => document.addEventListener('mousedown', away), 0)
  }

  // ---- Alerts ------------------------------------------------------------------------

  let audio = null
  function ping() {
    try {
      audio = audio || new (window.AudioContext || window.webkitAudioContext)()
      const t = audio.currentTime
      for (const [f, at] of [[880, 0], [1174.7, 0.09]]) {
        const o = audio.createOscillator()
        const g = audio.createGain()
        o.type = 'sine'
        o.frequency.value = f
        g.gain.setValueAtTime(0.0001, t + at)
        g.gain.exponentialRampToValueAtTime(0.12, t + at + 0.02)
        g.gain.exponentialRampToValueAtTime(0.0001, t + at + 0.22)
        o.connect(g).connect(audio.destination)
        o.start(t + at)
        o.stop(t + at + 0.25)
      }
    } catch {}
  }

  function onIncoming({ conv, row, title }) {
    const focused = M.native.focusState()
    const viewing = st.open === conv && focused
    if (viewing) return
    const s = st.prefs.settings
    if (s.sounds) ping()
    const who = isGroup(conv) && row.senderKey ? `${nameOf(row.senderKey)} in ${title}` : title
    const body = displayBody(row)
    if (!focused && M.native.alertsInBackground) return // the desktop app shows its own alert
    if (!focused && s.notifications && M.native.notify) return M.native.notify({ title: who, body, conv })
    if (!s.notifications) return
    const note = h('div', { class: 'toast note', onClick: () => { note.remove(); openChat(conv) } },
      avatar(title, conv, 38, { group: isGroup(conv) }),
      h('div', { style: { minWidth: 0 } }, h('div', { class: 'ellipsis', style: { fontWeight: 800 } }, who), h('div', { class: 'ellipsis dim', style: { fontWeight: 500, maxWidth: '280px' } }, body)))
    document.body.appendChild(note)
    setTimeout(() => note.remove(), 4500)
  }

  // ---- Stories ---------------------------------------------------------------------
  //
  // Instagram-style 24-hour posts. The strip at the top of Chats shows your story first, then
  // friends with new stories (gradient ring), friends whose stories you've seen (grey ring),
  // then friends who are online right now (green dot).

  const STORY_BGS = [
    'linear-gradient(160deg, #8b5cf6, #d946ef 55%, #ff7a59)',
    'linear-gradient(160deg, #0ea5e9, #6366f1)',
    'linear-gradient(160deg, #f43f5e, #f59e0b)',
    'linear-gradient(160deg, #10b981, #0ea5e9)',
    'linear-gradient(160deg, #111827, #4b5563)',
    'linear-gradient(160deg, #ec4899, #8b5cf6)',
    'linear-gradient(160deg, #f97316, #ef4444 60%, #7c3aed)',
    'radial-gradient(circle at 30% 20%, #22d3ee, #6366f1 45%, #0a0a0f)'
  ]
  const REACTIONS = ['❤️', '🔥', '😂', '😮', '😢', '👏']
  const STORY_MS = 5500

  function timeAgo(ms) {
    const m = Math.max(1, Math.round((Date.now() - ms) / 60000))
    if (m < 60) return m + 'm'
    return Math.round(m / 60) + 'h'
  }

  function storyBubble({ label, ring, onClick, inner, badge, dot }) {
    return h('button', { class: 'story', onClick },
      h('div', { class: 'sring ' + ring }, h('div', { class: 'gap' }, inner), badge || null, dot ? h('span', { class: 'sdot' }) : null),
      h('span', { class: 'ellipsis' }, label))
  }

  function storiesStrip(added) {
    const stories = st.stories || { mine: [], friends: [] }
    const withStories = new Set(stories.friends.map((f) => f.key))
    const online = added.filter((c) => isOnline(c.keyHex) && !withStories.has(c.keyHex))
    const items = []
    const me = st.me
    const mineAv = avatar(me.username, me.publicKey, 58)
    items.push(storyBubble({
      label: 'Your story',
      ring: stories.mine.length ? 'mine' : 'none',
      onClick: () => (stories.mine.length ? openViewer('me', 0) : openComposer()),
      inner: mineAv,
      badge: h('span', { class: 'splus', onClick: (e) => { e.stopPropagation(); openComposer() } }, icon('plus', 14))
    }))
    for (const f of stories.friends) {
      const c = contact(f.key)
      const name = c ? c.displayName : nameOf(f.key)
      items.push(storyBubble({ label: name.split(' ')[0], ring: f.allSeen ? 'seen' : 'new', onClick: () => openViewer(f.key, 0), inner: avatar(name, f.key, 58), dot: isOnline(f.key) }))
    }
    for (const c of online) items.push(storyBubble({ label: c.displayName.split(' ')[0], ring: 'none', onClick: () => openChat(c.keyHex), inner: avatar(c.displayName, c.keyHex, 58), dot: true }))
    if (!added.length) items.push(h('button', { class: 'story', onClick: () => showAddContact() }, h('div', { class: 'add' }, icon('userPlus', 24)), h('span', {}, 'Add friend')))
    const live = online.length + stories.friends.filter((f) => isOnline(f.key)).length
    return [
      h('div', { class: 'section' }, 'Stories', live ? [h('span', { class: 'dimmer' }, '·'), h('span', { class: 'net-dot online', style: { width: '8px', height: '8px' } }), h('span', { style: { color: 'var(--online)' } }, live + ' active')] : null),
      h('div', { class: 'stories' }, items)
    ]
  }

  // -- Viewer --

  let viewer = null // { owner, index, el, timer, paused, started, elapsed, urls }

  function storyList(owner) {
    if (owner === 'me') return st.stories.mine
    const f = st.stories.friends.find((x) => x.key === owner)
    return f ? f.items : []
  }
  function ownerOrder() {
    return st.stories.friends.map((f) => f.key)
  }

  async function photoUrl(item) {
    const k = item.conv + '|' + item.id
    if (blobs.has(k)) return blobs.get(k).url
    const r = await M.call('stories.photo', { conv: item.conv, id: item.id })
    const bin = atob(r.data)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    const blob = new Blob([bytes], { type: item.mime || 'image/jpeg' })
    const entry = { url: URL.createObjectURL(blob), blob }
    blobs.set(k, entry)
    return entry.url
  }

  function openViewer(owner, index) {
    closeViewer()
    const root = h('div', { class: 'sv', tabindex: '-1' })
    layer.appendChild(root)
    viewer = { owner, index, el: root, timer: null, paused: false, started: 0, elapsed: 0 }
    root.addEventListener('keydown', (e) => {
      if (e.target.tagName === 'INPUT') return
      if (e.key === 'ArrowRight') step(1)
      if (e.key === 'ArrowLeft') step(-1)
      if (e.key === 'Escape') closeViewer()
      if (e.key === ' ') { e.preventDefault(); setPaused(!viewer.paused) }
    })
    showItem()
    root.focus()
  }

  function closeViewer() {
    if (!viewer) return
    clearTimeout(viewer.timer)
    const el = viewer.el
    viewer = null
    el.classList.add('out')
    setTimeout(() => el.remove(), 220)
  }

  function refreshViewer() {
    if (!viewer) return
    const list = storyList(viewer.owner)
    if (!list.length) return closeViewer()
    if (viewer.index >= list.length) viewer.index = list.length - 1
    // Re-render only things that may have changed (photo arrived, viewers).
    const item = list[viewer.index]
    const v = viewer.el.querySelector('.sv-viewers')
    if (v && item.viewers) v.lastChild.textContent = item.viewers.length ? `Seen by ${item.viewers.length}` : 'No views yet'
    if (item.hasPhoto && item.photoReady && viewer.el.querySelector('.sv-wait')) showItem(true)
  }

  function setPaused(p) {
    if (!viewer) return
    if (p === viewer.paused) return
    viewer.paused = p
    viewer.el.classList.toggle('paused', p)
    if (p) {
      clearTimeout(viewer.timer)
      viewer.elapsed += Date.now() - viewer.started
    } else {
      viewer.started = Date.now()
      viewer.timer = setTimeout(() => step(1), Math.max(200, viewer.duration - viewer.elapsed))
    }
  }

  function step(dir) {
    if (!viewer) return
    const list = storyList(viewer.owner)
    const next = viewer.index + dir
    if (next >= 0 && next < list.length) {
      viewer.index = next
      return showItem()
    }
    if (viewer.owner === 'me') return dir > 0 ? closeViewer() : showItem()
    const order = ownerOrder()
    const at = order.indexOf(viewer.owner)
    const other = order[at + dir]
    if (!other) return dir > 0 ? closeViewer() : showItem()
    viewer.owner = other
    viewer.index = dir > 0 ? 0 : storyList(other).length - 1
    viewer.el.classList.remove('swap')
    void viewer.el.offsetWidth
    viewer.el.classList.add('swap')
    showItem()
  }

  function showItem(keepTime) {
    if (!viewer) return
    const { owner } = viewer
    const list = storyList(owner)
    const item = list[viewer.index]
    if (!item) return closeViewer()
    clearTimeout(viewer.timer)
    const mine = owner === 'me'
    const name = mine ? 'Your story' : nameOf(owner)
    const seed = mine ? st.me.publicKey : owner
    const bars = h('div', { class: 'sv-bars' }, list.map((_, i) => h('div', { class: 'sv-bar' + (i < viewer.index ? ' done' : '') }, h('i', i === viewer.index ? { class: 'run' } : {}))))
    const head = h('div', { class: 'sv-head' },
      avatar(mine ? st.me.username : name, seed, 36),
      h('div', { class: 'sv-who' }, h('b', {}, name), h('span', {}, timeAgo(item.ts))),
      h('button', { class: 'icon-btn sv-x', title: 'Pause', onClick: () => setPaused(!viewer.paused) }, icon(viewer.paused ? 'right' : 'pause', 20)),
      mine ? h('button', { class: 'icon-btn sv-x', title: 'Delete', onClick: async () => {
        setPaused(true)
        if (await confirmBox('Delete this story?', 'It disappears for everyone who has the new version of Meshron.', 'Delete', true)) {
          await M.call('stories.delete', { sid: item.sid })
          toast('Story deleted')
        } else setPaused(false)
      } }, icon('trash', 20)) : null,
      h('button', { class: 'icon-btn sv-x', title: 'Close', onClick: closeViewer }, icon('x', 22)))

    let body
    if (item.type === 'photo') {
      if (!item.photoReady) {
        body = h('div', { class: 'sv-media sv-wait', style: { background: STORY_BGS[4] } }, h('div', { class: 'spinner' }), h('p', {}, 'Downloading photo…'))
      } else {
        const img = h('img', { class: 'sv-photo', alt: '' })
        const back = h('div', { class: 'sv-blur' })
        photoUrl(item).then((url) => { img.src = url; back.style.backgroundImage = `url("${url}")` }).catch(() => {})
        body = h('div', { class: 'sv-media' }, back, img, item.text ? h('div', { class: 'sv-caption' }, item.text) : null)
      }
    } else {
      const len = item.text.length
      body = h('div', { class: 'sv-media sv-text', style: { background: STORY_BGS[item.bg % STORY_BGS.length] } },
        h('div', { class: 'sv-words', style: { fontSize: (len < 40 ? 34 : len < 120 ? 26 : 20) + 'px' } }, item.text))
    }

    let foot
    if (mine) {
      foot = h('div', { class: 'sv-foot' },
        h('button', { class: 'sv-viewers', onClick: () => showViewers(item) }, icon('users', 18), h('span', {}, item.viewers && item.viewers.length ? `Seen by ${item.viewers.length}` : 'No views yet')),
        h('button', { class: 'btn primary small', onClick: () => { closeViewer(); openComposer() } }, icon('plus', 16), 'Add'))
    } else {
      const input = h('input', { class: 'sv-reply', placeholder: `Reply to ${name.split(' ')[0]}…`, maxlength: 1000 })
      input.addEventListener('focus', () => setPaused(true))
      input.addEventListener('blur', () => { if (!input.value) setPaused(false) })
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && input.value.trim()) { replyToStory(owner, item, input.value.trim()); input.value = ''; input.blur() } })
      foot = h('div', { class: 'sv-foot' }, input,
        h('div', { class: 'sv-reacts' }, REACTIONS.map((r) => h('button', { onClick: (e) => { burst(e.currentTarget, r); replyToStory(owner, item, r) } }, r))))
    }

    const tapPrev = h('div', { class: 'sv-tap left' })
    const tapNext = h('div', { class: 'sv-tap right' })
    const card = h('div', { class: 'sv-card' }, body, h('div', { class: 'sv-shade' }), bars, head, tapPrev, tapNext, foot)
    // Tap left/right to move, hold anywhere to pause, swipe down to close.
    for (const [el, dir] of [[tapPrev, -1], [tapNext, 1]]) {
      let downAt = 0
      let y0 = 0
      el.addEventListener('pointerdown', (e) => { downAt = Date.now(); y0 = e.clientY; setTimeout(() => { if (downAt) setPaused(true) }, 220) })
      el.addEventListener('pointerup', (e) => {
        const held = Date.now() - downAt
        downAt = 0
        if (e.clientY - y0 > 90) return closeViewer()
        if (held < 220) step(dir)
        else setPaused(false)
      })
      el.addEventListener('pointerleave', () => { if (downAt) { downAt = 0; setPaused(false) } })
    }
    viewer.el.replaceChildren(h('div', { class: 'sv-back', onClick: closeViewer }), card)
    viewer.el.classList.toggle('paused', viewer.paused)
    if (!keepTime) viewer.elapsed = 0
    viewer.duration = item.type === 'text' ? Math.min(12000, STORY_MS + item.text.length * 35) : STORY_MS
    const run = bars.querySelector('.run')
    if (run) {
      run.style.animationDuration = viewer.duration + 'ms'
      run.style.animationDelay = -viewer.elapsed + 'ms'
    }
    if (item.type === 'photo' && !item.photoReady) {
      if (run) run.style.animationPlayState = 'paused'
      return
    }
    if (!mine && !item.seen) {
      item.seen = true
      M.call('stories.seen', { key: owner, sid: item.sid }).catch(() => {})
    }
    if (!viewer.paused) {
      viewer.started = Date.now()
      viewer.timer = setTimeout(() => step(1), viewer.duration - viewer.elapsed)
    }
  }

  function burst(el, emoji) {
    const r = el.getBoundingClientRect()
    for (let i = 0; i < 7; i++) {
      const p = h('span', { class: 'burst', style: { left: r.left + r.width / 2 + 'px', top: r.top + 'px', '--dx': (Math.random() * 160 - 80) + 'px', '--d': (600 + Math.random() * 500) + 'ms' } }, emoji)
      document.body.appendChild(p)
      setTimeout(() => p.remove(), 1200)
    }
  }

  async function replyToStory(owner, item, text) {
    const quote = item.type === 'photo' ? (item.text ? `📷 ${item.text}` : '📷 Photo') : item.text
    const body = `↪ Story: ${quote.replace(/\s+/g, ' ').slice(0, 60)}${quote.length > 60 ? '…' : ''}\n${text}`
    try {
      await M.call('chat.send', { conv: owner, text: body })
      toast(text.length <= 4 ? `Sent ${text}` : 'Reply sent')
    } catch (err) { oops(err) }
  }

  function showViewers(item) {
    setPaused(true)
    modal({
      title: 'Seen by',
      onClose: () => setPaused(false),
      body: item.viewers && item.viewers.length
        ? h('div', {}, item.viewers.map((k) => h('div', { class: 'pick', style: { cursor: 'default' } }, avatar(nameOf(k), k, 40), h('div', { class: 't-title' }, nameOf(k)))))
        : h('p', { class: 'dim' }, "Nobody has seen it yet. Friends see it when they're online with a new version of Meshron.")
    })
  }

  // -- Composer --

  function openComposer() {
    closeViewer()
    let bg = Math.floor(Math.random() * STORY_BGS.length)
    let photo = null // { blob, url }
    const text = h('textarea', { class: 'sc-text', placeholder: 'Type something…', maxlength: 700, rows: 3 })
    const caption = h('input', { class: 'sv-reply', placeholder: 'Add a caption…', maxlength: 300 })
    const stage = h('div', { class: 'sc-stage' })
    const swatches = h('div', { class: 'sc-swatches' })
    const photoBtn = h('button', { class: 'sc-photo', onClick: () => picker.click() }, icon('clip', 18), 'Photo')
    const share = h('button', { class: 'btn primary', onClick: post }, 'Share to story')
    const picker = h('input', { type: 'file', accept: 'image/*', hidden: true })
    const root = h('div', { class: 'sv sc' })
    const close = () => { root.classList.add('out'); setTimeout(() => root.remove(), 220); if (photo) URL.revokeObjectURL(photo.url) }

    const draw = () => {
      photoBtn.hidden = !!photo
      if (photo) {
        stage.style.background = '#000'
        stage.replaceChildren(h('div', { class: 'sv-blur', style: { backgroundImage: `url("${photo.url}")` } }), h('img', { class: 'sv-photo', src: photo.url }),
          h('button', { class: 'icon-btn sc-remove', title: 'Remove photo', onClick: () => { URL.revokeObjectURL(photo.url); photo = null; draw() } }, icon('x', 20)))
        swatches.replaceChildren(caption)
      } else {
        stage.style.background = STORY_BGS[bg]
        stage.replaceChildren(text)
        swatches.replaceChildren(...STORY_BGS.map((b, i) => h('button', { class: 'sc-swatch' + (i === bg ? ' on' : ''), style: { background: b }, onClick: () => { bg = i; draw() } })))
        const len = text.value.length
        text.style.fontSize = (len < 40 ? 32 : len < 120 ? 24 : 19) + 'px'
        setTimeout(() => text.focus(), 30)
      }
    }
    text.addEventListener('input', () => { const len = text.value.length; text.style.fontSize = (len < 40 ? 32 : len < 120 ? 24 : 19) + 'px' })
    picker.addEventListener('change', async () => {
      const file = picker.files[0]
      picker.value = ''
      if (!file) return
      try {
        photo = await shrinkImage(file)
        draw()
      } catch { toast("Couldn't open that picture") }
    })

    async function post() {
      share.disabled = true
      share.replaceChildren(h('div', { class: 'spinner' }))
      try {
        if (photo) {
          const id = await M.call('file.begin', { conv: 'story', name: 'story.jpg', mime: 'image/jpeg', size: photo.blob.size })
          const bytes = new Uint8Array(await photo.blob.arrayBuffer())
          const stepSize = 240 * 1024
          for (let o = 0; o < bytes.length; o += stepSize) await M.call('file.chunk', { id, data: toBase64(bytes.subarray(o, o + stepSize)) })
          await M.call('stories.post', { type: 'photo', text: caption.value, upload: id })
        } else {
          await M.call('stories.post', { type: 'text', text: text.value, bg })
        }
        close()
        toast('Shared to your story')
      } catch (err) {
        oops(err)
        share.disabled = false
        share.replaceChildren('Share to story')
      }
    }

    root.append(h('div', { class: 'sv-back', onClick: close }),
      h('div', { class: 'sv-card' }, stage,
        h('div', { class: 'sv-head sc-head' }, h('div', { class: 'sv-who' }, h('b', {}, 'New story'), h('span', {}, 'Your contacts see it for 24 hours')), photoBtn, h('button', { class: 'icon-btn sv-x', onClick: close }, icon('x', 22))),
        h('div', { class: 'sc-foot' }, swatches, share)),
      picker)
    layer.appendChild(root)
    draw()
  }

  /** Photos are shrunk to 1440 px on the long side (JPEG) so stories stay quick to send. */
  function shrinkImage(file) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file)
      const img = new Image()
      img.onload = () => {
        const scale = Math.min(1, 1440 / Math.max(img.width, img.height))
        const c = document.createElement('canvas')
        c.width = Math.round(img.width * scale)
        c.height = Math.round(img.height * scale)
        c.getContext('2d').drawImage(img, 0, 0, c.width, c.height)
        URL.revokeObjectURL(url)
        c.toBlob((blob) => (blob ? resolve({ blob, url: URL.createObjectURL(blob) }) : reject(new Error('encode'))), 'image/jpeg', 0.85)
      }
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('decode')) }
      img.src = url
    })
  }


  // ---- Calls -------------------------------------------------------------------------
  //
  // Same protocol and media as the Android app (calls/CallManager.kt, VoiceEngine.kt, VideoEngine.kt):
  //   signalling  { t: offer | answer | reject | end | video | vack | keyreq | unreachable, id, ... }
  //   audio       [seq u16][count u8] + count × ([len u16][Opus frame])   16 kHz mono, 20 ms frames, 2 per packet
  //   video       [seq u32][flags u8: 1 = key][rotation/90 u8][width u16][height u16] + H.264 Annex B
  // Encoding and decoding use WebCodecs; capture and playback use the microphone/camera and an AudioWorklet.

  const RING_MS = 45000
  const AUDIO_RATE = 16000
  const callSupported = () => typeof AudioEncoder !== 'undefined' && typeof AudioDecoder !== 'undefined' && !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
  const videoSupported = () => typeof VideoEncoder !== 'undefined' && typeof VideoDecoder !== 'undefined'

  let call = null // { phase, peer, id, video, remoteVideo, muted, startedAt, ... }

  const b64 = (bytes) => toBase64(bytes)
  const unb64 = (s) => { const bin = atob(s); const out = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return out }
  const newCallId = () => { const b = new Uint8Array(16); crypto.getRandomValues(b); return [...b].map((x) => x.toString(16).padStart(2, '0')).join('') }
  const signal = (peer, msg) => M.call('call.send', { key: peer, msg }).catch(() => {})

  // ---- Tones (ring for incoming, ringback for outgoing) ----
  let toneCtx = null
  let toneTimer = null
  function playTone(kind) {
    stopTone()
    try {
      toneCtx = new (window.AudioContext || window.webkitAudioContext)()
      const beep = () => {
        const t = toneCtx.currentTime
        const pairs = kind === 'ring' ? [[880, 0], [1100, 0.18], [880, 0.5], [1100, 0.68]] : [[440, 0], [480, 0]]
        for (const [f, at] of pairs) {
          const o = toneCtx.createOscillator()
          const g = toneCtx.createGain()
          o.frequency.value = f
          g.gain.setValueAtTime(0.0001, t + at)
          g.gain.exponentialRampToValueAtTime(kind === 'ring' ? 0.14 : 0.06, t + at + 0.03)
          g.gain.exponentialRampToValueAtTime(0.0001, t + at + (kind === 'ring' ? 0.16 : 1.2))
          o.connect(g).connect(toneCtx.destination)
          o.start(t + at)
          o.stop(t + at + 1.3)
        }
      }
      beep()
      toneTimer = setInterval(beep, kind === 'ring' ? 2200 : 3000)
    } catch {}
  }
  function stopTone() {
    clearInterval(toneTimer)
    toneTimer = null
    if (toneCtx) { try { toneCtx.close() } catch {} toneCtx = null }
  }

  // ---- Audio engine ----

  async function startAudio(c) {
    const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 48000 })
    c.ctx = ctx
    await ctx.audioWorklet.addModule('audio-worklet.js')
    c.mic = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } })
    const src = ctx.createMediaStreamSource(c.mic)
    const micNode = new AudioWorkletNode(ctx, 'mesh-mic')
    const speaker = new AudioWorkletNode(ctx, 'mesh-speaker', { outputChannelCount: [2] })
    speaker.connect(ctx.destination)
    src.connect(micNode)
    c.speaker = speaker

    // Encoder: 48 kHz capture -> 16 kHz mono -> Opus 20 ms frames, two per packet.
    let seq = 0
    let frames = []
    const enc = new AudioEncoder({
      output: (chunk) => {
        const f = new Uint8Array(chunk.byteLength)
        chunk.copyTo(f)
        frames.push(f)
        if (frames.length === 2) {
          const size = 3 + frames.reduce((n, x) => n + 2 + x.length, 0)
          const p = new Uint8Array(size)
          const dv = new DataView(p.buffer)
          dv.setUint16(0, seq++ & 0xffff)
          p[2] = frames.length
          let o = 3
          for (const x of frames) { dv.setUint16(o, x.length); p.set(x, o + 2); o += 2 + x.length }
          frames = []
          M.call('call.audio', { key: c.peer, data: b64(p) }).catch(() => {})
        }
      },
      error: (e) => console.warn('audio encoder', e)
    })
    enc.configure({ codec: 'opus', sampleRate: AUDIO_RATE, numberOfChannels: 1, bitrate: 24000, opus: { frameDuration: 20000, complexity: 5 } })
    c.enc = enc
    let pending = new Float32Array(0)
    let ts = 0
    const ratio = ctx.sampleRate / AUDIO_RATE
    const FRAME = AUDIO_RATE / 50 // 320 samples = 20 ms
    micNode.port.onmessage = (e) => {
      if (c.muted || enc.state !== 'configured') return
      const input = e.data
      // Simple averaging downsampler (48k -> 16k).
      const n = Math.floor(input.length / ratio)
      const down = new Float32Array(n)
      for (let i = 0; i < n; i++) {
        let s = 0
        for (let k = 0; k < ratio; k++) s += input[Math.floor(i * ratio + k)] || 0
        down[i] = s / ratio
      }
      const merged = new Float32Array(pending.length + down.length)
      merged.set(pending)
      merged.set(down, pending.length)
      let o = 0
      while (merged.length - o >= FRAME) {
        const data = merged.slice(o, o + FRAME)
        o += FRAME
        enc.encode(new AudioData({ format: 'f32', sampleRate: AUDIO_RATE, numberOfFrames: FRAME, numberOfChannels: 1, timestamp: ts, data }))
        ts += 20000
      }
      pending = merged.slice(o)
    }

    // Decoder: Opus -> PCM -> speaker worklet (which plays at the context rate).
    const dec = new AudioDecoder({
      output: (ad) => {
        const n = ad.numberOfFrames
        const pcm = new Float32Array(n)
        ad.copyTo(pcm, { planeIndex: 0, format: 'f32-planar' })
        const rate = ad.sampleRate
        ad.close()
        // Resample to the context rate.
        const outLen = Math.round(n * ctx.sampleRate / rate)
        const out = new Float32Array(outLen)
        for (let i = 0; i < outLen; i++) out[i] = pcm[Math.min(n - 1, Math.floor(i * rate / ctx.sampleRate))]
        speaker.port.postMessage(out)
      },
      error: (e) => console.warn('audio decoder', e)
    })
    dec.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 1 })
    c.dec = dec
    c.decTs = 0
  }

  function onAudioPacket(c, data) {
    if (!c.dec || c.dec.state !== 'configured') return
    const p = unb64(data)
    if (p.length < 3) return
    const dv = new DataView(p.buffer)
    const count = p[2]
    let o = 3
    for (let i = 0; i < count; i++) {
      if (o + 2 > p.length) return
      const len = dv.getUint16(o)
      if (!len || o + 2 + len > p.length) return
      c.dec.decode(new EncodedAudioChunk({ type: 'key', timestamp: c.decTs, data: p.subarray(o + 2, o + 2 + len) }))
      c.decTs += 20000
      o += 2 + len
    }
  }

  // ---- Video engine ----
  async function startCamera(c) {
    if (!videoSupported()) { toast("Video isn't supported on this device yet"); c.video = false; return }
    try {
      c.cam = await navigator.mediaDevices.getUserMedia({ video: { facingMode: c.front === false ? 'environment' : 'user', width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 20 } } })
    } catch {
      toast("Couldn't open the camera")
      c.video = false
      return
    }
    const vid = document.createElement('video')
    vid.muted = true
    vid.playsInline = true
    vid.srcObject = c.cam
    await vid.play().catch(() => {})
    c.localVideo = vid
    renderCall()
    const canvas = document.createElement('canvas')
    const g = canvas.getContext('2d')
    let seq = 0
    let width = 0
    let height = 0
    c.bitrate = 450000
    c.inflight = new Map() // seq -> sent at
    c.forceKey = true
    const makeEncoder = () => {
      if (c.venc) try { c.venc.close() } catch {}
      c.venc = new VideoEncoder({
        output: (chunk) => {
          const body = new Uint8Array(chunk.byteLength)
          chunk.copyTo(body)
          const p = new Uint8Array(10 + body.length)
          const dv = new DataView(p.buffer)
          const s = seq++
          dv.setUint32(0, s)
          p[4] = chunk.type === 'key' ? 1 : 0
          p[5] = 0
          dv.setUint16(6, width)
          dv.setUint16(8, height)
          p.set(body, 10)
          c.inflight.set(s, Date.now())
          M.call('call.video', { key: c.peer, data: b64(p) }).catch(() => {})
        },
        error: (e) => console.warn('video encoder', e)
      })
      c.venc.configure({ codec: 'avc1.42E01F', width, height, bitrate: c.bitrate, framerate: 20, latencyMode: 'realtime', avc: { format: 'annexb' } })
    }
    c.camTimer = setInterval(() => {
      if (!c.video || vid.readyState < 2 || !vid.videoWidth) return
      // Congestion control like Android: at most 6 frames waiting for an ack.
      const now = Date.now()
      for (const [s, t] of c.inflight) if (now - t > 3000) c.inflight.delete(s)
      if (c.inflight.size >= 6) return
      const w = vid.videoWidth & ~1
      const hh = vid.videoHeight & ~1
      if (w !== width || hh !== height || !c.venc) {
        width = w
        height = hh
        canvas.width = w
        canvas.height = hh
        makeEncoder()
        c.forceKey = true
      }
      g.drawImage(vid, 0, 0, w, hh)
      const frame = new VideoFrame(canvas, { timestamp: now * 1000 })
      c.venc.encode(frame, { keyFrame: c.forceKey })
      c.forceKey = false
      frame.close()
    }, 50)
    signal(c.peer, { t: 'video', id: c.id, on: true })
  }

  function stopCamera(c, tell = true) {
    clearInterval(c.camTimer)
    c.camTimer = null
    if (c.venc) { try { c.venc.close() } catch {} c.venc = null }
    if (c.cam) for (const t of c.cam.getTracks()) t.stop()
    c.cam = null
    c.localVideo = null
    if (tell) signal(c.peer, { t: 'video', id: c.id, on: false })
  }

  function onVideoFrame(c, data) {
    const p = unb64(data)
    if (p.length < 11) return
    const dv = new DataView(p.buffer)
    const s = dv.getUint32(0)
    const key = p[4] === 1
    const rot = p[5]
    signal(c.peer, { t: 'vack', id: c.id, seq: s })
    if (!c.vdec || c.vdec.state === 'closed') {
      if (!key) return
      c.vdec = new VideoDecoder({
        output: (frame) => {
          const cv = c.remoteCanvas
          if (cv) {
            const swap = rot % 2 === 1
            const w = swap ? frame.displayHeight : frame.displayWidth
            const hgt = swap ? frame.displayWidth : frame.displayHeight
            if (cv.width !== w || cv.height !== hgt) { cv.width = w; cv.height = hgt }
            const g = cv.getContext('2d')
            g.save()
            g.translate(w / 2, hgt / 2)
            g.rotate((rot * Math.PI) / 2)
            g.drawImage(frame, -frame.displayWidth / 2, -frame.displayHeight / 2)
            g.restore()
            c.lastFrameAt = Date.now()
          }
          frame.close()
        },
        error: () => { c.vdec = null; signal(c.peer, { t: 'keyreq', id: c.id }) }
      })
      c.vdec.configure({ codec: 'avc1.42E01F', optimizeForLatency: true })
    }
    if (c.vdec.decodeQueueSize > 4 && !key) { signal(c.peer, { t: 'keyreq', id: c.id }); return }
    try {
      c.vdec.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: s * 50000, data: p.subarray(10) }))
    } catch {
      c.vdec = null
      signal(c.peer, { t: 'keyreq', id: c.id })
    }
  }

  // ---- Call flow ----
  let wakeLock = null
  async function keepAwake(on) {
    try {
      if (M.native.keepAwake) return await M.native.keepAwake(on)
      if (on && !wakeLock && navigator.wakeLock) wakeLock = await navigator.wakeLock.request('screen')
      else if (!on && wakeLock) { await wakeLock.release(); wakeLock = null }
    } catch {}
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden && call && !M.native.keepAwake) { wakeLock = null; keepAwake(true) } })

  async function startCall(peer, video) {
    if (call) return toast("You're already in a call")
    if (!callSupported()) return toast(isIos ? 'Calls need a newer version of iOS. Update your iPhone in Settings.' : "Calls aren't supported on this device")
    if (!isOnline(peer)) return toast(`${nameOf(peer)} isn't connected. Calls need you both online or nearby.`)
    call = { phase: 'outgoing', peer, id: newCallId(), video: video && videoSupported(), remoteVideo: false, muted: false, front: true }
    signal(peer, { t: 'offer', id: call.id, video: call.video })
    keepAwake(true)
    playTone('ringback')
    renderCall()
    const id = call.id
    call.timeout = setTimeout(() => { if (call && call.id === id && call.phase === 'outgoing') { signal(peer, { t: 'end', id }); endCall('No answer') } }, RING_MS)
  }

  async function begin() {
    const c = call
    clearTimeout(c.timeout)
    stopTone()
    c.phase = 'active'
    c.startedAt = Date.now()
    keepAwake(true)
    renderCall()
    try {
      await startAudio(c)
    } catch (err) {
      signal(c.peer, { t: 'end', id: c.id })
      return endCall("Couldn't use the microphone")
    }
    if (c.video) await startCamera(c)
    c.ticker = setInterval(() => {
      const el = $('#call-time')
      if (el && call) el.textContent = durationText(Date.now() - call.startedAt)
      // Ends the call if the connection is gone for too long.
      if (call && !isOnline(call.peer)) { call.lostSince = call.lostSince || Date.now(); if (Date.now() - call.lostSince > 20000) { endCall('Connection lost') } }
      else if (call) call.lostSince = 0
    }, 1000)
  }

  function endCall(message) {
    const c = call
    if (!c) return
    call = null
    keepAwake(false)
    clearTimeout(c.timeout)
    clearInterval(c.ticker)
    stopTone()
    stopCamera(c, false)
    if (c.mic) for (const t of c.mic.getTracks()) t.stop()
    for (const k of ['enc', 'dec', 'vdec']) if (c[k]) try { c[k].close() } catch {}
    if (c.ctx) try { c.ctx.close() } catch {}
    const root = $('#call')
    if (root) {
      root.querySelector('.call-status').textContent = message
      root.classList.add('ending')
      setTimeout(() => root.remove(), 1400)
    }
  }

  const durationText = (ms) => { const s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') }

  function onCallSignal({ key, msg }) {
    if (!msg) return
    const c = call
    const { t, id } = msg
    if (t === 'vack') { if (c && c.id === id && c.inflight) c.inflight.delete(msg.seq); return }
    if (t === 'keyreq') { if (c && c.id === id) c.forceKey = true; return }
    if (t === 'offer') {
      if (c) { if (c.id !== id) signal(key, { t: 'reject', id, reason: 'busy' }); return }
      const ct = contact(key)
      if (!ct || ct.status !== 'ADDED') return
      call = { phase: 'incoming', peer: key, id, video: false, videoCall: !!msg.video, remoteVideo: !!msg.video, muted: false, front: true }
      playTone('ring')
      renderCall()
      if (!M.native.focusState() && M.native.notify) M.native.notify({ title: nameOf(key), body: msg.video ? 'Incoming video call' : 'Incoming call', conv: key })
      call.timeout = setTimeout(() => { if (call && call.id === id && call.phase === 'incoming') endCall('Missed call') }, RING_MS)
      return
    }
    if (!c || c.id !== id || c.peer !== key) return
    if (t === 'answer' && c.phase === 'outgoing') begin()
    else if (t === 'reject' && c.phase === 'outgoing') endCall(msg.reason === 'busy' ? `${nameOf(key)} is on another call` : 'Declined')
    else if (t === 'end') endCall(c.phase === 'incoming' ? 'Missed call' : 'Call ended')
    else if (t === 'unreachable') endCall(`Couldn't reach ${nameOf(key)}`)
    else if (t === 'video') { c.remoteVideo = !!msg.on; if (!msg.on) c.vdec = null; renderCall() }
  }

  function accept(video) {
    const c = call
    if (!c || c.phase !== 'incoming') return
    if (!callSupported()) { signal(c.peer, { t: 'reject', id: c.id, reason: 'declined' }); return endCall(isIos ? 'Calls need a newer version of iOS' : "Calls aren't supported here") }
    c.video = video && videoSupported()
    signal(c.peer, { t: 'answer', id: c.id })
    begin()
  }
  function hangUp() {
    const c = call
    if (!c) return
    if (c.phase === 'incoming') { signal(c.peer, { t: 'reject', id: c.id, reason: 'declined' }); return endCall('Declined') }
    signal(c.peer, { t: 'end', id: c.id })
    endCall('Call ended')
  }

  function callButton(ic, label, onClick, cls = '') {
    return h('div', { class: 'cbtn ' + cls }, h('button', { onClick, 'aria-label': label }, icon(ic, 26)), h('span', {}, label))
  }

  function minimizeCall(min) {
    if (!call) return
    call.min = min
    renderCall()
  }

  /** Drag the minimised call anywhere. */
  function makeDraggable(el) {
    if (el.dataset.drag) return
    el.dataset.drag = '1'
    let sx = 0, sy = 0, ox = 0, oy = 0, moved = false, down = false
    el.addEventListener('pointerdown', (e) => {
      if (!el.classList.contains('mini') || e.target.closest('button')) return
      down = true; moved = false
      const r = el.getBoundingClientRect()
      sx = e.clientX; sy = e.clientY; ox = r.left; oy = r.top
      el.setPointerCapture(e.pointerId)
    })
    el.addEventListener('pointermove', (e) => {
      if (!down) return
      const dx = e.clientX - sx, dy = e.clientY - sy
      if (Math.abs(dx) + Math.abs(dy) > 4) moved = true
      const r = el.getBoundingClientRect()
      el.style.left = Math.max(6, Math.min(innerWidth - r.width - 6, ox + dx)) + 'px'
      el.style.top = Math.max(6, Math.min(innerHeight - r.height - 6, oy + dy)) + 'px'
      el.style.right = 'auto'; el.style.bottom = 'auto'
    })
    el.addEventListener('pointerup', () => { if (down && !moved && el.classList.contains('mini')) minimizeCall(false); down = false })
  }

  function renderCall() {
    const c = call
    let root = $('#call')
    if (!c) { if (root) root.remove(); return }
    if (!root) { root = h('div', { id: 'call', class: 'call' }); layer.appendChild(root) }
    const name = nameOf(c.peer)
    if (c.min && c.phase !== 'incoming') {
      if (!c.remoteCanvas) c.remoteCanvas = h('canvas', { class: 'call-remote' })
      root.className = 'call mini'
      makeDraggable(root)
      const video = c.phase === 'active' && c.remoteVideo
      root.replaceChildren(...[
        video ? c.remoteCanvas : null,
        h('div', { class: 'mini-top' },
          video ? null : avatar(name, c.peer, 56),
          h('div', { class: 'call-name' }, name),
          h('div', { class: 'call-status' }, c.phase === 'active' ? h('span', { id: 'call-time' }, durationText(Date.now() - (c.startedAt || Date.now()))) : 'Calling…')),
        h('div', { class: 'mini-bar' },
          h('button', { class: c.muted ? 'on' : '', title: c.muted ? 'Unmute' : 'Mute', onClick: () => { c.muted = !c.muted; renderCall() } }, icon(c.muted ? 'micOff' : 'mic', 18)),
          h('button', { title: 'Back to the call', onClick: () => minimizeCall(false) }, icon('expand', 18)),
          h('button', { class: 'red', title: 'Hang up', onClick: hangUp }, icon('hangup', 18)))].filter(Boolean))
      return
    }
    root.className = 'call'
    root.style.left = root.style.top = root.style.right = root.style.bottom = ''
    const status = c.phase === 'incoming' ? (c.videoCall ? 'Incoming video call' : 'Incoming call') : c.phase === 'outgoing' ? 'Calling…' : ''
    const showRemote = c.phase === 'active' && c.remoteVideo
    if (!c.remoteCanvas) c.remoteCanvas = h('canvas', { class: 'call-remote' })
    const kids = [h('div', { class: 'call-bg', style: { background: `radial-gradient(circle at 50% 30%, ${AVATARS[javaHash(c.peer) % AVATARS.length][0]}66, #0a0a0f 70%)` } })]
    if (showRemote) kids.push(c.remoteCanvas)
    kids.push(h('div', { class: 'call-top' + (showRemote ? ' over' : '') },
      showRemote ? null : h('div', { class: 'call-av' + (c.phase !== 'active' ? ' pulse' : '') }, avatar(name, c.peer, 132)),
      h('div', { class: 'call-name' }, name),
      h('div', { class: 'call-status' }, c.phase === 'active' ? h('span', { id: 'call-time' }, durationText(Date.now() - (c.startedAt || Date.now()))) : status)))
    if (c.localVideo && c.video) {
      c.localVideo.className = 'call-self'
      kids.push(c.localVideo)
    }
    let buttons
    if (c.phase === 'incoming') {
      buttons = [callButton('hangup', 'Decline', hangUp, 'red'), c.videoCall ? callButton('video', 'Video', () => accept(true), 'green') : null, callButton('phone', 'Accept', () => accept(false), 'green')]
    } else {
      buttons = [
        callButton(c.muted ? 'micOff' : 'mic', c.muted ? 'Unmute' : 'Mute', () => { c.muted = !c.muted; renderCall() }, c.muted ? 'on' : ''),
        callButton(c.video ? 'video' : 'videoOff', 'Camera', async () => { c.video = !c.video; if (c.phase === 'active') { if (c.video) await startCamera(c); else stopCamera(c) } renderCall() }, c.video ? 'on' : ''),
        c.video ? callButton('flip', 'Flip', async () => { c.front = !c.front; stopCamera(c, false); await startCamera(c) }) : null,
        callButton('chat', 'Chat', () => { const p = c.peer; minimizeCall(true); openChat(p) }),
        callButton('hangup', 'End', hangUp, 'red')
      ]
      // Minimise: keep talking while using the rest of the app.
      kids.push(h('button', { class: 'call-min-btn', title: 'Minimise', onClick: () => minimizeCall(true) }, icon('down', 26)))
    }
    kids.push(h('div', { class: 'call-buttons' }, buttons))
    root.replaceChildren(...kids)
  }

  // ---- Host events -------------------------------------------------------------------

  M.on('identity', (me) => {
    const first = !st.me
    st.me = me
    if (first && me) enterApp()
    else if (st.tab === 'me') renderSide()
  })
  M.on('contacts', (list) => { st.contacts = list; renderRail(); renderSide(); refreshHeader() })
  M.on('groups', (list) => { st.groups = list; renderSide(); refreshHeader() })
  M.on('summaries', (list) => { st.summaries = list; renderRail(); if (st.tab === 'chats') renderSide() })
  M.on('prefs', (p) => { st.prefs = p; renderRail(); renderSide() })
  M.on('net', (n) => { st.net = n; renderRail(); if (st.tab !== 'me' || true) renderSideSoon(); refreshHeader() })
  M.on('file-status', ({ key, text }) => {
    if (text == null) delete st.fileStatus[key]
    else st.fileStatus[key] = text
    if (st.open && key.startsWith(st.open + '|')) renderMsgs()
  })
  M.on('msgs', ({ conv, rows }) => {
    if (conv !== st.open) return
    let added = 0
    let mine = false
    for (const r of rows) {
      const i = st.rows.findIndex((x) => x.id === r.id)
      if (i >= 0) st.rows[i] = r
      else { st.rows.push(r); added++; if (r.outgoing) mine = true; else if (chatEls && chatEls.msgs.scrollHeight - chatEls.msgs.scrollTop - chatEls.msgs.clientHeight > 240) st.pendingBelow++ }
    }
    if (added) st.rows.sort((a, b) => a.localTime - b.localTime)
    renderMsgs()
    if (mine) scrollBottom(false)
    updateToBottom()
    if (added) markRead()
  })
  M.on('msgs-removed', ({ conv, ids, all }) => {
    if (conv !== st.open) return
    st.rows = all ? [] : st.rows.filter((r) => !ids.includes(r.id))
    renderMsgs()
  })
  M.on('incoming', onIncoming)
  M.on('typing', ({ key }) => {
    st.typing[key] = Date.now() + 6000
    if (st.open === key) { refreshHeader(); setTimeout(refreshHeader, 6100) }
  })
  M.on('stories', (v) => { st.stories = v; if (st.tab === 'chats') renderSideSoon(); refreshViewer() })
  M.on('request', ({ key }) => toast(`${nameOf(key)} wants to chat with you`))
  M.on('open-chat', ({ conv }) => openChat(conv))
  M.on('open-link', ({ text }) => { if (st.me) showAddContact(text) })
  M.on('call-msg', onCallSignal)
  M.on('call-audio', ({ key, data }) => { if (call && call.phase === 'active' && call.peer === key) onAudioPacket(call, data) })
  M.on('call-video', ({ key, data }) => { if (call && call.phase === 'active' && call.peer === key && call.remoteVideo) onVideoFrame(call, data) })

  let sideTimer = null
  function renderSideSoon() {
    if (sideTimer) return
    sideTimer = setTimeout(() => { sideTimer = null; renderSide() }, 250)
  }

  // ---- Keyboard and focus ------------------------------------------------------------

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if ($('#menu')) return closeMenu()
      if ($('#emoji')) return $('#emoji').remove()
      if (openModals.length) return openModals[openModals.length - 1].close()
      if (st.open) return closeChat()
    }
    const mod = e.ctrlKey || e.metaKey
    if (mod && e.key.toLowerCase() === 'k') { e.preventDefault(); if (st.tab === 'me') setTab('chats'); const s = $('#search'); if (s) s.focus() }
    if (mod && e.key.toLowerCase() === 'n') { e.preventDefault(); if (st.me) showNewChat() }
  })
  window.addEventListener('focus', markRead)
  document.addEventListener('visibilitychange', () => { if (!document.hidden) markRead() })
  window.addEventListener('beforeunload', saveDraft)
  window.addEventListener('online', () => M.call('net.changed').catch(() => {}))

  // ---- Start -------------------------------------------------------------------------

  function enterApp() {
    ob.hidden = true
    $('#shell').hidden = false
    buildSide()
    showHero()
  }

  async function start() {
    try {
      const init = await M.call('init')
      Object.assign(st, { stories: init.stories || { mine: [], friends: [] }, me: init.identity, contacts: init.contacts, groups: init.groups, summaries: init.summaries, net: init.net, prefs: init.prefs, fileStatus: init.fileStatus || {}, limits: init.limits })
      if (st.me) enterApp()
      else { ob.hidden = false; showWelcome() }
    } catch (err) {
      ob.hidden = false
      ob.replaceChildren(h('div', { class: 'ob-card' }, h('h1', { class: 't-h1' }, "Meshron couldn't start"), h('p', { class: 'dim selectable', style: { marginTop: '8px' } }, (err && err.message) || String(err))))
    }
    $('#boot').classList.add('gone')
    setTimeout(() => $('#boot').remove(), 400)
  }
  start()
})()
