// Meshron Reels: short public videos, swiped vertically (Windows and iPhone screens).
// Uses the helpers app.js shares on window.MeshronUI; talks to the host's reels.* methods.
/* global MeshronUI */
(function () {
  'use strict'
  const U = () => window.MeshronUI
  const MAX_SECONDS = 60
  const REC_BITS = 1_800_000 // ~13 MB for a full minute at 720p; most reels are far smaller
  const KEEP_ORIGINAL = 6 * 1024 * 1024 // a short, small file is posted as it is (no quality loss)

  let root = null
  let feedKind = 'foryou'
  let pager = null // { el, items, cards, observer, active, more, loading, kind, ch, sound }
  const urls = new Map() // 'ch:id' -> Promise<object URL>
  const order = [] // LRU of loaded URLs
  let muted = false
  let ready = false
  let stack = [] // overlays: profile, sound, search pages

  const k = (it) => it.ch + ':' + it.id
  const fmt = (n) => (n >= 1e6 ? (n / 1e6).toFixed(n >= 1e7 ? 0 : 1) + 'M' : n >= 1e4 ? Math.round(n / 1e3) + 'K' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K' : String(n || 0))
  const ago = (ms) => {
    const s = Math.max(1, (Date.now() - ms) / 1000)
    if (s < 3600) return Math.ceil(s / 60) + 'm'
    if (s < 86400) return Math.round(s / 3600) + 'h'
    if (s < 86400 * 30) return Math.round(s / 86400) + 'd'
    return new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short' })
  }
  const imgSrc = (b64) => (b64 ? 'data:image/jpeg;base64,' + b64 : '')

  function face (a, size) {
    const { h, avatar } = U()
    if (a.photo) return h('div', { class: 'avatar has-photo', style: { width: size + 'px', height: size + 'px' } }, h('img', { src: imgSrc(a.photo), alt: '' }))
    return avatar(a.name || '?', a.id || a.ch, size)
  }

  // ---- Video files (downloaded from peers by the host, cached on disk) -------------

  function videoUrl (it) {
    const key = k(it)
    if (urls.has(key)) {
      const i = order.indexOf(key)
      if (i >= 0) order.splice(i, 1)
      order.push(key)
      return urls.get(key)
    }
    const { M, toBase64 } = U()
    const p = (async () => {
      const { size } = await M.call('reels.load', { ch: it.ch, id: it.id })
      const parts = []
      for (let off = 0; off < size;) {
        const r = await M.call('reels.read', { ch: it.ch, id: it.id, offset: off, length: 2 * 1024 * 1024 })
        const bin = atob(r.data)
        const bytes = new Uint8Array(bin.length)
        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
        parts.push(bytes)
        off += bytes.length
        if (!bytes.length) break
      }
      void toBase64
      return URL.createObjectURL(new Blob(parts, { type: 'video/mp4' }))
    })()
    p.catch(() => urls.delete(key))
    urls.set(key, p)
    order.push(key)
    // Keep at most 8 videos in memory.
    while (order.length > 8) {
      const old = order.shift()
      const u = urls.get(old)
      urls.delete(old)
      if (u) u.then((x) => URL.revokeObjectURL(x), () => {})
    }
    return p
  }

  // ---- The page ----------------------------------------------------------------------

  function show () {
    const { h, $, M } = U()
    if (!root) {
      root = h('section', { id: 'reels' })
      $('#shell').appendChild(root)
      M.on('reels-changed', ({ chans }) => refreshCounts(chans))
      M.on('reels-ready', () => { ready = true; if (root && !root.hidden) build() })
    }
    root.hidden = false
    document.getElementById('shell').classList.add('reels-mode')
    build()
  }

  function hide () {
    if (!root) return
    root.hidden = true
    document.getElementById('shell').classList.remove('reels-mode')
    pauseAll()
  }

  async function build () {
    const { h, M, icon } = U()
    stack.forEach((s) => s.remove())
    stack = []
    const status = await M.call('reels.status').catch(() => ({ on: false }))
    ready = status.ready
    const liveBtn = h('button', { class: 'live-pill', title: "Who's live", onClick: () => openLives(liveBtn._list) }, 'LIVE')
    const top = h('div', { class: 'reels-top' },
      h('button', { class: 'r-icon', title: 'Search people', onClick: openSearch }, icon('search', 24)),
      h('div', { class: 'reels-tabs' },
        ...[['following', 'Following'], ['foryou', 'For You']].map(([id, label]) =>
          h('button', { class: feedKind === id ? 'on' : '', onClick: () => { feedKind = id; build() } }, label))),
      liveBtn)
    M.call('reels.lives').then((list) => {
      const n = (list || []).length
      liveBtn.replaceChildren(...['LIVE', n ? h('span', { class: 'live-n' }, String(n)) : null].filter(Boolean))
      liveBtn._list = list || []
    }).catch(() => {})
    // Posting is in the + menu of the bottom bar, like Android.
    if (!status.on) {
      root.replaceChildren(h('div', { class: 'reels-empty' }, h('div', { class: 'orb' }, icon('eyeOff', 34)), h('div', { class: 't-h1' }, 'Reels are off while anonymous'), h('p', { class: 'dim' }, 'Switch back to public in Me to watch and share reels.')))
      return
    }
    if (!ready) {
      root.replaceChildren(top, h('div', { class: 'reels-empty' }, h('div', { class: 'spinner' }), h('p', { class: 'dim' }, 'Starting Reels…')))
      return
    }
    const p = makePager({ kind: feedKind })
    root.replaceChildren(p.el, top)
    pager = p
    loadMore(p)
  }

  function makePager ({ kind, ch, sound, items, start = 0 }) {
    const { h } = U()
    const el = h('div', { class: 'reel-feed' })
    const p = { el, items: [], cards: [], kind, ch, sound, more: !items, loading: false, active: -1 }
    p.observer = new IntersectionObserver((entries) => {
      for (const e of entries) {
        const i = p.cards.indexOf(e.target)
        if (e.isIntersecting && e.intersectionRatio > 0.6) activate(p, i)
      }
    }, { root: el, threshold: [0.6] })
    el.addEventListener('scroll', () => {
      if (p.more && !p.loading && el.scrollTop + el.clientHeight * 3 > el.scrollHeight) loadMore(p)
    }, { passive: true })
    if (items) {
      addItems(p, items)
      requestAnimationFrame(() => { el.scrollTop = start * el.clientHeight; activate(p, start) })
    }
    return p
  }

  async function loadMore (p) {
    const { M, h, icon } = U()
    if (p.loading) return
    p.loading = true
    try {
      const r = await M.call('reels.feed', { kind: p.kind, ch: p.ch, sound: p.sound, offset: p.items.length, limit: 8 })
      p.more = r.more
      const mod = window.MeshronMod
      const hidden = mod ? await mod.hiddenAmong(r.items.map((it) => 'reel:' + it.ch + ':' + it.id)) : new Set()
      addItems(p, r.items.filter((it) => !hidden.has('reel:' + it.ch + ':' + it.id) || (it.mine && (it.removed = true))))
      if (!p.items.length) {
        p.el.replaceChildren(h('div', { class: 'reels-empty' },
          h('div', { class: 'orb' }, icon(p.kind === 'following' ? 'users' : 'play', 34)),
          h('div', { class: 't-h1' }, p.kind === 'following' ? 'Nobody to show yet' : 'No reels yet'),
          h('p', { class: 'dim' }, p.kind === 'following' ? 'Follow people in For You and their reels show up here.' : "Reels from people on Meshron appear here as you meet them online. Be the first: tap + to share one."),
          h('button', { class: 'btn primary', onClick: () => (p.kind === 'following' ? (feedKind = 'foryou', build()) : openCreate()) }, p.kind === 'following' ? 'Go to For You' : 'Share a reel')))
      }
    } catch (err) {
      U().oops(err)
    } finally {
      p.loading = false
    }
  }

  function addItems (p, list) {
    for (const it of list) {
      if (p.items.some((x) => k(x) === k(it))) continue
      p.items.push(it)
      const card = reelCard(it, p)
      p.cards.push(card)
      p.el.appendChild(card)
      p.observer.observe(card)
    }
    if (p.active < 0 && p.cards.length) activate(p, 0)
  }

  function activate (p, i) {
    if (i < 0 || i === p.active) return
    p.active = i
    p.cards.forEach((c, j) => {
      const v = c.querySelector('video')
      if (j === i) { start(c, p.items[j]); return }
      if (v && !v.paused) v.pause()
    })
    // Get the next two ready.
    for (const j of [i + 1, i + 2]) if (p.items[j]) videoUrl(p.items[j]).catch(() => {})
  }

  async function start (card, it) {
    const v = card.querySelector('video')
    if (!v.src) {
      card.classList.add('loading')
      try {
        v.src = await videoUrl(it)
      } catch (err) {
        card.classList.remove('loading')
        card.querySelector('.reel-err').textContent = (err && err.message) || "Couldn't load this video"
        card.classList.add('failed')
        return
      }
      card.classList.remove('loading')
    }
    if (!card.isConnected || root.hidden) return
    v.muted = muted
    v.currentTime = 0
    v.play().catch(() => { v.muted = true; muted = true; v.play().catch(() => {}); syncMute() })
  }

  function pauseAll () {
    if (!root) return
    for (const v of root.querySelectorAll('video')) v.pause()
  }

  function syncMute () {
    for (const b of document.querySelectorAll('.reel-mute')) b.replaceChildren(U().icon(muted ? 'volumeOff' : 'volume', 20))
    for (const v of document.querySelectorAll('#reels video')) v.muted = muted
  }

  // ---- One reel ----------------------------------------------------------------------

  function reelCard (it, p) {
    const { h, icon } = U()
    const video = h('video', { loop: true, playsinline: true, 'webkit-playsinline': true, preload: 'auto', poster: imgSrc(it.thumb) })
    const bar = h('div', { class: 'reel-bar' }, h('span'))
    video.addEventListener('timeupdate', () => { if (video.duration) bar.firstChild.style.width = (video.currentTime / video.duration * 100) + '%' })
    const heart = h('div', { class: 'reel-heart' })
    const likeBtn = h('button', { class: 'ra like' + (it.liked ? ' on' : ''), onClick: () => toggleLike(it, card) }, icon('heart', 30), h('span', {}, fmt(it.likes)))
    const favBtn = h('button', { class: 'ra fav' + (it.faved ? ' on' : ''), onClick: () => toggleFav(it, card) }, icon('bookmark', 28), h('span', {}, it.faved ? 'Saved' : 'Save'))
    const followBtn = !it.author.mine && !it.author.following
      ? h('button', { class: 'r-follow', title: 'Follow', onClick: (e) => { e.stopPropagation(); follow(it.author, true, card) } }, icon('plus', 14))
      : null
    let taps = 0
    let tapTimer = null
    const stage = h('div', {
      class: 'reel-stage',
      onClick: (e) => {
        taps++
        if (taps === 1) {
          tapTimer = setTimeout(() => { taps = 0; video.paused ? video.play().catch(() => {}) : video.pause(); card.classList.toggle('paused', video.paused) }, 260)
        } else {
          clearTimeout(tapTimer)
          taps = 0
          const r = stage.getBoundingClientRect()
          heart.style.left = (e.clientX - r.left) + 'px'
          heart.style.top = (e.clientY - r.top) + 'px'
          heart.classList.remove('pop'); void heart.offsetWidth; heart.classList.add('pop')
          if (!it.liked) toggleLike(it, card)
        }
      }
    }, video, heart, h('div', { class: 'reel-play' }, icon('play', 54)))
    const card = h('article', { class: 'reel' + (it.thumb ? '' : ' no-thumb') },
      stage,
      h('div', { class: 'reel-err' }),
      h('div', { class: 'reel-spin' }, h('div', { class: 'spinner' })),
      h('div', { class: 'reel-info' },
        it.reposter ? h('button', { class: 'reel-repost', onClick: () => openProfile(it.reposter.ch) }, '↻ Reposted by @' + (it.reposter.handle || it.reposter.name)) : (it.reposted ? h('div', { class: 'reel-repost' }, '↻ You reposted') : null),
        it.reposter && it.quote ? h('div', { class: 'reel-quote', style: { background: 'rgba(0,0,0,0.35)', borderRadius: '12px', padding: '6px 10px', margin: '2px 0 6px', fontSize: '14px' } }, '“' + it.quote + '”') : null,
        it.removed ? h('div', { class: 'reel-repost danger' }, 'Removed by Meshron · appeal in Me → From Meshron') : null,
        h('button', { class: 'reel-who', onClick: () => openProfile(it.ch) }, '@' + (it.author.handle || it.author.name), window.MeshronMod ? window.MeshronMod.badgeEl(it.author.id, 13) : null, it.author.mine ? h('span', { class: 'r-you' }, 'You') : null, h('span', { class: 'r-when' }, ' · ' + ago(it.ts))),
        it.collabs && it.collabs.length ? h('div', { class: 'r-with' }, 'with ', ...it.collabs.flatMap((a, i) => [i ? ', ' : null, h('button', { class: 'r-with-name', style: { background: 'none', border: 'none', padding: 0, color: 'inherit', fontWeight: 700, cursor: 'pointer', font: 'inherit' }, onClick: () => openProfile(a.ch) }, '@' + (a.handle || a.name))].filter(Boolean))) : null,
        it.cap ? captionEl(it.cap) : null,
        h('button', { class: 'reel-sound', onClick: () => openSound(it.sound) }, icon('music', 15), h('span', { class: 'marquee' }, h('span', {}, it.sound.title || 'Original sound')))),
      h('div', { class: 'reel-actions' },
        h('div', { class: 'r-face' }, h('button', { onClick: () => openProfile(it.ch) }, face(it.author, 48)), followBtn),
        likeBtn,
        h('button', { class: 'ra', onClick: () => openComments(it, card) }, icon('chat', 28), h('span', { class: 'cc' }, fmt(it.comments))),
        favBtn,
        h('button', { class: 'ra', onClick: (e) => share(it, e) }, icon('share', 27), h('span', {}, 'Share')),
        h('button', { class: 'ra', title: 'More', onClick: (e) => moreMenu(it, card, p, e) }, icon('more', 26)),
        it.mine ? h('button', { class: 'ra', onClick: () => removeReel(it, card, p) }, icon('trash', 26), h('span', {}, 'Delete')) : null,
        h('button', { class: 'ra reel-mute', title: 'Sound on/off', onClick: () => { muted = !muted; syncMute() } }, icon(muted ? 'volumeOff' : 'volume', 20)),
        h('button', { class: 'r-disc', title: it.sound.title, onClick: () => openSound(it.sound) }, it.thumb ? h('img', { src: imgSrc(it.thumb), alt: '' }) : icon('music', 16))),
      it.views ? h('div', { class: 'reel-views' }, icon('play', 12), fmt(it.views)) : null,
      bar)
    card._it = it
    return card
  }

  /** More: repost, not interested, hide creator, report. */
  function moreMenu (it, card, p, e) {
    const { M, toast, oops } = U()
    const menu = window.MeshronUI.menu || null
    const items = [
      { icon: 'share', label: it.reposted ? 'Undo repost' : 'Repost', onClick: async () => {
        if (it.reposted) { try { await M.call('reels.repost', { ch: it.ch, id: it.id, on: false }); it.reposted = false; toast('Repost removed') } catch (err) { oops(err) } return }
        repostSheet(it)
      } },
      it.mine ? null : { icon: 'eyeOff', label: 'Not interested', onClick: async () => { await M.call('reels.notInterested', { ch: it.ch, id: it.id }).catch(oops); dropCard(card, p) } },
      it.mine ? null : { icon: 'block', label: 'Hide this creator', onClick: async () => { await M.call('reels.hideCreator', { ch: it.ch, on: true }).catch(oops); dropCard(card, p) } },
      it.mine || !window.MeshronMod ? null : { icon: 'flag', label: 'Report', danger: true, onClick: () => window.MeshronMod.report({ kind: 'reel', target: it.ch + ':' + it.id, owner: it.author.id, name: '@' + (it.author.handle || it.author.name), content: 'Caption: ' + (it.cap || ''), page: 'Discover reel by @' + (it.author.handle || it.author.name) }) }
    ].filter(Boolean)
    if (menu) menu(e.clientX, e.clientY, items)
  }
  /** Repost, optionally with a thought your followers see on it. */
  function repostSheet (it) {
    const { h, M, toast, oops, modal } = U()
    const thought = h('textarea', { class: 'input', rows: 3, maxlength: 300, placeholder: 'Add a thought (optional)', style: { width: '100%', resize: 'none' } })
    modal({ title: 'Repost', body: h('div', {}, h('p', { class: 'dim', style: { marginTop: 0 } }, 'Your followers will see this video in their Following feed.'), thought), buttons: [
      { label: 'Cancel', onClick: (m) => m.close() },
      { label: 'Repost', kind: 'primary', onClick: async (m) => { try { await M.call('reels.repost', { ch: it.ch, id: it.id, on: true, quote: thought.value.trim() }); it.reposted = true; m.close(); toast('Reposted to your followers') } catch (err) { oops(err) } } }] })
    setTimeout(() => thought.focus(), 50)
  }
  function dropCard (card, p) {
    const i = p.cards.indexOf(card)
    if (i >= 0) { p.cards.splice(i, 1); p.items.splice(i, 1) }
    card.remove()
  }

  function captionEl (text) {
    const { h } = U()
    const el = h('div', { class: 'reel-cap' }, ...text.split(/(#[\p{L}\p{N}_]+)/u).map((part) => part.startsWith('#') ? h('b', {}, part) : part))
    el.addEventListener('click', () => el.classList.toggle('open'))
    return el
  }

  function setCounts (card, it) {
    const like = card.querySelector('.ra.like')
    like.classList.toggle('on', it.liked)
    like.querySelector('span').textContent = fmt(it.likes)
    const fav = card.querySelector('.ra.fav')
    fav.classList.toggle('on', it.faved)
    fav.querySelector('span').textContent = it.faved ? 'Saved' : 'Save'
    card.querySelector('.cc').textContent = fmt(it.comments)
  }

  async function toggleLike (it, card) {
    it.liked = !it.liked
    it.likes += it.liked ? 1 : -1
    setCounts(card, it)
    if (it.liked) { const b = card.querySelector('.ra.like'); b.classList.remove('bump'); void b.offsetWidth; b.classList.add('bump') }
    try { await U().M.call('reels.like', { ch: it.ch, id: it.id, on: it.liked }) } catch (err) { U().oops(err) }
  }
  async function toggleFav (it, card) {
    it.faved = !it.faved
    setCounts(card, it)
    U().toast(it.faved ? 'Saved to Favourites' : 'Removed from Favourites')
    try { await U().M.call('reels.fav', { ch: it.ch, id: it.id, on: it.faved }) } catch (err) { U().oops(err) }
  }
  async function follow (a, on, card) {
    try {
      await U().M.call('reels.follow', { ch: a.ch, on })
      a.following = on
      for (const c of document.querySelectorAll('#reels .reel')) {
        if (c._it && c._it.ch === a.ch) { c._it.author.following = on; const b = c.querySelector('.r-follow'); if (b && on) b.remove() }
      }
      U().toast(on ? 'Following ' + (a.handle ? '@' + a.handle : a.name) : 'Unfollowed')
    } catch (err) { U().oops(err) }
    void card
  }
  function share (it, ev) {
    const { M, toast } = U()
    if (window.MeshronStoryEditor && U().menu) {
      const r = ev ? { left: ev.clientX, top: ev.clientY } : { left: innerWidth / 2, top: innerHeight / 2 }
      U().menu(r.left, r.top, [
        { icon: 'plus', label: 'Add to story', onClick: () => window.MeshronStoryEditor.open({ reel: { ch: it.ch, id: it.id, thumb: it.thumb ? imgSrc(it.thumb) : null, creator: '@' + (it.author.handle || it.author.name) } }) },
        { icon: 'share', label: 'Share link', onClick: () => shareText(it) }
      ])
      return
    }
    shareText(it)
  }
  function shareText (it) {
    const { M, toast } = U()
    const text = (it.cap ? it.cap + '\n' : '') + 'Watch @' + (it.author.handle || it.author.name) + ' on Meshron Reels'
    if (M.native.share) M.native.share({ text }).catch(() => {})
    else M.native.copy(text).then(() => toast('Copied'))
  }
  async function removeReel (it, card, p) {
    const { confirmBox, M, oops } = U()
    if (!(await confirmBox('Delete this reel?', 'It disappears for everyone. People who already downloaded it may keep their copy.', 'Delete', true))) return
    try {
      await M.call('reels.delete', { id: it.id })
      const i = p.cards.indexOf(card)
      card.remove()
      if (i >= 0) { p.cards.splice(i, 1); p.items.splice(i, 1) }
    } catch (err) { oops(err) }
  }

  async function refreshCounts (chans) {
    if (!root || root.hidden) return
    const { M } = U()
    for (const ch of chans) {
      const cards = [...document.querySelectorAll('#reels .reel')].filter((c) => c._it && c._it.ch === ch)
      if (!cards.length) continue
      const r = await M.call('reels.feed', { kind: 'user', ch, limit: 200 }).catch(() => null)
      if (!r) continue
      for (const c of cards) {
        const fresh = r.items.find((x) => x.id === c._it.id)
        if (fresh) { Object.assign(c._it, { likes: fresh.likes, liked: fresh.liked, comments: fresh.comments, faved: fresh.faved, views: fresh.views }); setCounts(c, c._it) }
      }
    }
    if (openComments.current && chans.includes(openComments.current.it.ch)) openComments.current.reload()
  }

  // ---- Comments ----------------------------------------------------------------------

  function openComments (it, card) {
    const { h, M, modal, oops, icon } = U()
    const list = h('div', { class: 'r-comments' }, h('div', { class: 'spinner' }))
    const input = h('input', { placeholder: 'Add a comment… (@meshronai for AI)', maxlength: 300, autocomplete: 'off' })
    const sendBtn = h('button', { class: 'icon-btn send', title: 'Post' }, icon('send', 20))
    // The creator decides who may comment.
    const cm = (it.author && it.author.cm) || 'everyone'
    const isFriend = (U().st.contacts || []).some((c) => c.keyHex === it.author.id && c.status === 'ADDED')
    const closed = it.mine ? null : cm === 'off' ? 'Comments are turned off' : cm === 'followers' && !it.author.following && !isFriend ? 'Only followers can comment. Follow to join in.' : cm === 'friends' && !isFriend ? 'Only friends can comment on this video.' : null
    const compose = closed ? h('p', { class: 'dim r-none' }, closed) : h('div', { class: 'r-compose' }, input, sendBtn)
    const m = modal({ title: fmt(it.comments) + ' comments', body: h('div', {}, list, compose), onClose: () => { openComments.current = null } })
    async function reload () {
      try {
        const rows = await M.call('reels.comments', { ch: it.ch, id: it.id })
        it.comments = rows.length
        if (card) setCounts(card, it)
        m.box.querySelector('h2').textContent = rows.length === 1 ? '1 comment' : fmt(rows.length) + ' comments'
        // My comments first (newest on top), so they're easy to find and delete.
        const mineFirst = rows.filter((c) => c.mine).sort((a, b) => b.ts - a.ts).concat(rows.filter((c) => !c.mine))
        const AI = window.MeshronAI
        list.replaceChildren(...(rows.length ? mineFirst.map((c) => { const txt = h('div', { class: 'r-c-text selectable' }, c.by === AI.AI_ID ? AI.clean(c.text) : c.text); if (!c.mine && c.by !== AI.AI_ID) AI.translateInto('c:' + it.ch + ':' + it.id + ':' + c.cid, c.text, txt); return h('div', { class: 'r-comment' },
          c.by === AI.AI_ID ? AI.aiAvatar(36) : c.photo ? h('div', { class: 'avatar has-photo', style: { width: '36px', height: '36px' } }, h('img', { src: imgSrc(c.photo), alt: '' })) : U().avatar(c.name || '?', c.by, 36),
          h('div', { class: 'grow' },
            h('div', { class: 'r-c-name' }, c.by === AI.AI_ID ? 'Meshron AI' : (c.name || 'Someone'), window.MeshronMod ? window.MeshronMod.badgeEl(c.by, 11) : null, c.by === it.author.id ? h('span', { class: 'r-creator' }, 'Creator') : null, h('span', { class: 'dim' }, ' · ' + (c.sent ? ago(c.ts) : 'sending…'))),
            txt),
          c.canDelete ? h('button', { class: 'icon-btn', title: 'Delete', onClick: async () => { await M.call('reels.uncomment', { ch: it.ch, cid: c.cid }).catch(oops); reload() } }, icon('trash', 16)) : null) })
          : [h('p', { class: 'dim r-none' }, 'No comments yet. Say something nice.')]))
      } catch (err) { list.replaceChildren(h('p', { class: 'dim r-none' }, err.message)) }
    }
    async function post () {
      const text = input.value.trim()
      if (!text) return
      input.value = ''
      try {
        const cid = await M.call('reels.comment', { ch: it.ch, id: it.id, text }); await reload(); list.scrollTop = 0
        // Tagged @meshronai: Meshron AI answers under the comment, as its own account.
        if (cid && window.MeshronAI.tagged(text)) {
          const rows = await M.call('reels.comments', { ch: it.ch, id: it.id }).catch(() => [])
          const a = await window.MeshronAI.ask(it.ch + ':' + it.id, cid, text, it.cap, (it.author && (it.author.handle ? '@' + it.author.handle : it.author.name)) || '', rows.slice(-10).map((c) => (c.name || 'Someone') + ': ' + c.text))
          await M.call('reels.aiComment', { ch: it.ch, id: it.id, text: a.text, re: a.re, sig: a.sig })
          await reload()
        }
      } catch (err) { oops(err) }
    }
    sendBtn.onclick = post
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') post() })
    openComments.current = { it, reload }
    reload()
  }

  // ---- Profiles, sounds, search (pages over the feed) ---------------------------------

  function page (title, ...kids) {
    const { h, icon } = U()
    pauseAll()
    const el = h('div', { class: 'r-page' },
      h('div', { class: 'r-page-head' },
        h('button', { class: 'r-icon', title: 'Back', onClick: () => closePage(el) }, icon('back', 24)),
        h('div', { class: 't-title ellipsis' }, title), h('span', { style: { width: '44px' } })),
      h('div', { class: 'r-page-body' }, ...kids))
    root.appendChild(el)
    stack.push(el)
    return el
  }
  function closePage (el) {
    el.remove()
    stack = stack.filter((x) => x !== el)
    if (!stack.length && pager && pager.active >= 0) {
      const c = pager.cards[pager.active]
      const v = c && c.querySelector('video')
      if (v && v.src) v.play().catch(() => {})
    }
  }

  function grid (items, onOpen) {
    const { h, icon } = U()
    return h('div', { class: 'r-grid' }, ...items.map((it, i) => h('button', { class: 'r-tile', onClick: () => onOpen(i) },
      it.thumb ? h('img', { src: imgSrc(it.thumb), alt: '' }) : h('div', { class: 'r-tile-blank' }, icon('play', 28)),
      h('span', { class: 'r-tile-n' }, icon('heart', 13), fmt(it.likes)))))
  }

  function openPlayer (items, start) {
    const { h, icon } = U()
    pauseAll()
    const p = makePager({ kind: 'list', items, start })
    p.more = false
    const el = h('div', { class: 'r-page r-player' }, p.el, h('div', { class: 'reels-top' }, h('button', { class: 'r-icon', title: 'Back', onClick: () => { p.cards.forEach((c) => { const v = c.querySelector('video'); if (v) v.pause() }); closePage(el) } }, icon('back', 24))))
    root.appendChild(el)
    stack.push(el)
  }

  async function openProfile (ch) {
    const { h, M, oops, icon } = U()
    const body = h('div', { class: 'r-profile' }, h('div', { class: 'spinner' }))
    const el = page('', body)
    let tab = 'videos'
    async function render () {
      let pr
      try { pr = await M.call('reels.profile', { ch }) } catch (err) { return body.replaceChildren(h('p', { class: 'dim r-none' }, err.message)) }
      if (!pr) return body.replaceChildren(h('p', { class: 'dim r-none' }, 'Looking for this person… try again in a moment.'))
      el.querySelector('.r-page-head .t-title').textContent = pr.handle ? '@' + pr.handle : pr.name
      let list = pr.reels
      if (pr.mine && tab !== 'videos') list = (await M.call('reels.feed', { kind: tab, limit: 200 }).catch(() => ({ items: [] }))).items
      const actions = pr.mine
        ? [h('button', { class: 'btn soft', onClick: () => window.MeshronUI.setTab('me') }, 'Edit profile'), h('button', { class: 'btn primary', onClick: () => openCreate() }, icon('plus', 18), 'New reel')]
        : [h('button', { class: 'btn ' + (pr.following ? 'soft' : 'primary'), onClick: async () => { await follow(pr, !pr.following); render() } }, pr.following ? (pr.followsMe ? 'Friends' : 'Following') : (pr.followsMe ? 'Follow back' : 'Follow')),
            h('button', { class: 'btn soft', onClick: () => message(pr) }, icon('chat', 18), 'Message')]
      body.replaceChildren(
        h('div', { class: 'r-p-face' }, face(pr, 96)),
        h('div', { class: 't-h1 r-p-name' }, pr.name),
        pr.handle ? h('div', { class: 'dim' }, '@' + pr.handle) : null,
        h('div', { class: 'r-stats' },
          h('div', {}, h('b', {}, fmt(pr.followingCount)), h('span', {}, 'Following')),
          h('div', {}, h('b', {}, fmt(pr.followers)), h('span', {}, 'Followers')),
          h('div', {}, h('b', {}, fmt(pr.likes)), h('span', {}, 'Likes'))),
        h('div', { class: 'r-p-actions' }, ...actions),
        pr.bio ? h('p', { class: 'r-p-bio' }, pr.bio) : null,
        pr.mine ? h('div', { class: 'r-p-tabs' }, ...[['videos', 'play', 'Videos'], ['favourites', 'bookmark', 'Favourites'], ['liked', 'heart', 'Liked']].map(([id, ic, label]) =>
          h('button', { class: tab === id ? 'on' : '', title: label, onClick: () => { tab = id; render() } }, icon(ic, 20)))) : h('div', { class: 'r-p-tabs one' }, h('button', { class: 'on' }, icon('play', 20))),
        list.length ? grid(list, (i) => openPlayer(list, i))
          : h('p', { class: 'dim r-none' }, tab === 'videos' ? (pr.mine ? 'Share your first reel with the + button.' : 'No reels yet.') : tab === 'favourites' ? 'Reels you save show up here.' : 'Reels you like show up here.'))
    }
    render().catch(oops)
  }

  async function message (pr) {
    const { M, oops } = U()
    try {
      const link = await M.call('reels.link', { id: pr.id, name: pr.name })
      window.MeshronUI.setTab('contacts')
      window.MeshronUI.showAddContact(link)
    } catch (err) { oops(err) }
  }

  async function openSound (sound) {
    const { h, M, icon, oops } = U()
    const body = h('div', { class: 'r-sound' }, h('div', { class: 'spinner' }))
    page('Sound', body)
    try {
      const r = await M.call('reels.feed', { kind: 'sound', sound, limit: 200 })
      const orig = r.items.find((x) => x.ch === sound.ch && x.id === sound.id)
      body.replaceChildren(
        h('div', { class: 'r-sound-head' },
          h('div', { class: 'r-sound-art' }, orig && orig.thumb ? h('img', { src: imgSrc(orig.thumb), alt: '' }) : icon('music', 36)),
          h('div', { class: 'grow' },
            h('div', { class: 't-h2' }, sound.title || 'Original sound'),
            h('div', { class: 'dim' }, fmt(r.items.length) + (r.items.length === 1 ? ' reel' : ' reels')),
            orig ? h('button', { class: 'btn primary small', onClick: () => openCreate({ sound, soundItem: orig }) }, icon('music', 16), 'Use this sound') : null)),
        r.items.length ? grid(r.items, (i) => openPlayer(r.items, i)) : h('p', { class: 'dim r-none' }, 'No reels with this sound yet.'))
    } catch (err) { oops(err) }
  }

  function openSearch () {
    const { h, M, icon } = U()
    const input = h('input', { placeholder: 'Search names or @usernames', autocomplete: 'off', spellcheck: 'false' })
    const out = h('div', { class: 'r-results' })
    let timer = null
    let seq = 0
    input.addEventListener('input', () => {
      clearTimeout(timer)
      const q = input.value.trim()
      if (!q) return out.replaceChildren()
      timer = setTimeout(async () => {
        const mine = ++seq
        out.replaceChildren(h('div', { class: 'r-searching' }, h('div', { class: 'spinner' }), /^@?[a-z0-9_]{3,24}$/i.test(q) ? 'Searching everyone on Meshron…' : 'Searching…'))
        const users = await M.call('reels.search', { q }).catch(() => [])
        if (mine !== seq) return
        out.replaceChildren(...(users.length ? users.map((u) => h('button', { class: 'r-user', onClick: () => openProfile(u.ch) },
          face(u, 48),
          h('div', { class: 'grow' }, h('div', { class: 't-title' }, u.name), h('div', { class: 'dim t-small' }, (u.handle ? '@' + u.handle + ' · ' : '') + fmt(u.followers) + ' followers · ' + fmt(u.reels) + ' reels')),
          u.mine ? h('span', { class: 'r-you' }, 'You') : u.following ? h('span', { class: 'dim t-small' }, u.followsMe ? 'Friends' : 'Following') : null))
          : [h('p', { class: 'dim r-none' }, 'Nobody found. For an exact match, type their whole @username.')]))
      }, 350)
    })
    page('Search', h('div', { class: 'search r-search' }, icon('search', 20), input), out)
    setTimeout(() => input.focus(), 50)
  }

  /** Who's live now (people you follow first); tap to watch. */
  async function openLives (cached) {
    const { h, M, avatar, icon } = U()
    const list = h('div', { class: 'r-lives' }, h('div', { class: 'spinner' }))
    page('Live now', list)
    const lives = await M.call('reels.lives').catch(() => cached || [])
    list.replaceChildren(...(lives.length ? lives.map((it) => h('button', { class: 'r-live-row', onClick: () => U().watchLive({ author: it, lid: it.lid, title: it.title }) },
      h('div', { class: 'r-live-av' }, it.photo ? h('div', { class: 'avatar has-photo', style: { width: '52px', height: '52px' } }, h('img', { src: imgSrc(it.photo), alt: '' })) : avatar(it.name || '?', it.id, 52), h('span', { class: 'live-tag' }, 'LIVE')),
      h('div', { style: { flex: 1, minWidth: 0, textAlign: 'left' } }, h('div', { style: { fontWeight: 700 } }, it.handle ? '@' + it.handle : it.name), h('div', { class: 'dim ellipsis' }, it.title || 'Live now')),
      h('span', { class: 'btn light small' }, 'Watch')))
      : [h('div', { class: 'reels-empty', style: { position: 'static', minHeight: '60vh' } }, h('div', { class: 'orb' }, icon('play', 30)), h('div', { class: 't-h2' }, 'Nobody is live right now'), h('p', { class: 'dim' }, 'When someone goes live in the Android app, they show up here.'))]))
  }

  // ---- Making a reel -----------------------------------------------------------------

  function openCreate (opts = {}) {
    const { h, icon } = U()
    const input = h('input', { type: 'file', accept: 'video/*' })
    input.onchange = () => { if (input.files[0]) editor(input.files[0], opts) }
    if (opts.sound) {
      // Picked a sound first: say so, then pick the video.
      const body = h('div', { class: 'r-create-intro' },
        h('div', { class: 'orb' }, icon('music', 30)),
        h('div', { class: 't-h2' }, opts.sound.title || 'Original sound'),
        h('p', { class: 'dim' }, 'Pick a video. Its own sound is replaced with this one.'),
        h('button', { class: 'btn primary', onClick: () => input.click() }, icon('plus', 18), 'Choose video'))
      page('New reel', body)
      return
    }
    input.click()
  }

  async function editor (file, opts) {
    const { h, icon, oops, toast } = U()
    const url = URL.createObjectURL(file)
    const video = h('video', { src: url, playsinline: true, muted: !!opts.sound, loop: true, autoplay: true })
    video.muted = !!opts.sound
    const caption = h('textarea', { placeholder: 'Describe your reel… #tags welcome', maxlength: 300, rows: 3 })
    const info = h('div', { class: 'dim t-small' }, 'Loading…')
    const startIn = h('input', { type: 'range', min: 0, max: 0, step: 0.1, value: 0 })
    const trimRow = h('label', { class: 'r-trim', hidden: true }, h('span', {}, 'Start at'), startIn, h('span', { class: 'r-trim-v' }, '0:00'))
    const postBtn = h('button', { class: 'btn primary' }, icon('send', 18), 'Post')
    const progress = h('div', { class: 'r-progress', hidden: true }, h('div', { class: 'r-progress-bar' }, h('span')), h('div', { class: 'dim t-small' }, ''))
    const el = page('New reel', h('div', { class: 'r-editor' },
      h('div', { class: 'r-preview' }, video),
      h('div', { class: 'r-form' },
        opts.sound ? h('div', { class: 'r-chip' }, icon('music', 15), opts.sound.title || 'Original sound') : null,
        caption, trimRow, info, progress, postBtn)))
    const mmss = (s) => Math.floor(s / 60) + ':' + String(Math.floor(s % 60)).padStart(2, '0')
    await new Promise((resolve) => { video.onloadedmetadata = resolve; video.onerror = resolve })
    if (!video.duration || !video.videoWidth) { info.textContent = "This video can't be played here. Try an MP4."; postBtn.disabled = true; return }
    const dur = video.duration
    const length = Math.min(MAX_SECONDS, dur)
    if (dur > MAX_SECONDS + 0.5) {
      trimRow.hidden = false
      startIn.max = String(Math.max(0, dur - MAX_SECONDS))
      startIn.oninput = () => { video.currentTime = +startIn.value; trimRow.querySelector('.r-trim-v').textContent = mmss(+startIn.value) }
    }
    info.textContent = mmss(length) + (dur > MAX_SECONDS + 0.5 ? ' (reels are up to 1 minute; pick where it starts)' : '') + ' · ' + video.videoWidth + '×' + video.videoHeight + ' · ' + (file.size / 1048576).toFixed(1) + ' MB'
    postBtn.onclick = async () => {
      postBtn.disabled = true
      caption.disabled = true
      startIn.disabled = true
      progress.hidden = false
      const setP = (f, text) => { progress.querySelector('span').style.width = Math.round(f * 100) + '%'; progress.querySelector('.dim').textContent = text }
      try {
        const from = +startIn.value || 0
        let soundUrl = null
        if (opts.soundItem) { setP(0.02, 'Getting the sound…'); soundUrl = await videoUrl(opts.soundItem) }
        const thumb = await grabThumb(url, from + Math.min(0.5, length / 3))
        let blob = file
        const small = file.size <= KEEP_ORIGINAL && dur <= MAX_SECONDS + 0.5 && /mp4|quicktime/.test(file.type) && !soundUrl
        if (!small) blob = await transcode(url, from, length, soundUrl, (f) => setP(0.05 + f * 0.75, 'Making it smaller… ' + Math.round(f * 100) + '%'))
        setP(0.82, 'Sharing…')
        const bytes = new Uint8Array(await blob.arrayBuffer())
        const { M, toBase64 } = U()
        const xfer = await M.call('reels.begin')
        const step = 512 * 1024
        for (let o = 0; o < bytes.length; o += step) {
          await M.call('reels.chunk', { xfer, data: toBase64(bytes.subarray(o, o + step)) })
          setP(0.82 + 0.16 * (o / bytes.length), 'Sharing…')
        }
        await M.call('reels.end', {
          xfer, cap: caption.value.trim(), dur: Math.round(length), w: thumb.w, h: thumb.h, thumb: thumb.b64,
          sound: opts.sound || null
        })
        setP(1, 'Posted')
        URL.revokeObjectURL(url)
        toast('Your reel is live')
        closePage(el)
        for (const s of [...stack]) closePage(s)
        feedKind = 'foryou'
        const st = await M.call('reels.status')
        openProfile(st.ch)
      } catch (err) {
        oops(err)
        postBtn.disabled = false
        caption.disabled = false
        startIn.disabled = false
        progress.hidden = true
      }
    }
  }

  async function grabThumb (url, at) {
    const v = document.createElement('video')
    v.muted = true
    v.playsInline = true
    v.src = url
    await new Promise((resolve, reject) => { v.onloadeddata = resolve; v.onerror = () => reject(new Error("Couldn't read the video")) })
    v.currentTime = at
    await new Promise((resolve) => { v.onseeked = resolve; setTimeout(resolve, 1500) })
    const w = 270
    const hgt = Math.round(w * v.videoHeight / v.videoWidth)
    const c = document.createElement('canvas')
    c.width = w
    c.height = Math.min(hgt, 480)
    c.getContext('2d').drawImage(v, 0, 0, c.width, c.height)
    let q = 0.72
    let data = c.toDataURL('image/jpeg', q)
    while (data.length > 38000 && q > 0.3) { q -= 0.12; data = c.toDataURL('image/jpeg', q) }
    return { b64: data.slice(data.indexOf(',') + 1), w: v.videoWidth, h: v.videoHeight }
  }

  /**
   * Re-encodes in the browser engine itself (no extra code to download): plays the video
   * into a canvas at up to 720p and records it, with its own sound or a chosen one.
   */
  async function transcode (url, from, length, soundUrl, onProgress) {
    if (!window.MediaRecorder) throw new Error("This device can't make reels")
    const v = document.createElement('video')
    v.src = url
    v.playsInline = true
    v.muted = false
    v.crossOrigin = 'anonymous'
    await new Promise((resolve, reject) => { v.onloadedmetadata = resolve; v.onerror = () => reject(new Error("Couldn't read the video")) })
    const scale = Math.min(1, 1280 / Math.max(v.videoWidth, v.videoHeight))
    const c = document.createElement('canvas')
    c.width = Math.round(v.videoWidth * scale / 2) * 2
    c.height = Math.round(v.videoHeight * scale / 2) * 2
    const ctx = c.getContext('2d')
    const stream = c.captureStream(30)
    const ac = new (window.AudioContext || window.webkitAudioContext)()
    const dest = ac.createMediaStreamDestination()
    let snd = null
    if (soundUrl) {
      snd = document.createElement('video')
      snd.src = soundUrl
      snd.playsInline = true
      await new Promise((resolve) => { snd.onloadedmetadata = resolve; snd.onerror = resolve })
      ac.createMediaElementSource(snd).connect(dest)
      // The video's own sound goes in silently (a player cut off from the graph stalls).
      const mute = ac.createGain()
      mute.gain.value = 0
      ac.createMediaElementSource(v).connect(mute)
      mute.connect(dest)
    } else {
      ac.createMediaElementSource(v).connect(dest)
    }
    for (const t of dest.stream.getAudioTracks()) stream.addTrack(t)
    const types = ['video/mp4;codecs=avc1.42E01F,mp4a.40.2', 'video/mp4;codecs=avc1,mp4a', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm']
    const mimeType = types.find((t) => MediaRecorder.isTypeSupported(t)) || ''
    const rec = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: REC_BITS, audioBitsPerSecond: 96000 })
    const parts = []
    rec.ondataavailable = (e) => { if (e.data && e.data.size) parts.push(e.data) }
    v.currentTime = from
    await new Promise((resolve) => { v.onseeked = resolve; setTimeout(resolve, 1500) })
    await ac.resume()
    const done = new Promise((resolve) => { rec.onstop = resolve })
    rec.start(1000)
    // Players must be on the page (invisible) or the engine stops handing out frames.
    const host = document.createElement('div')
    host.style.cssText = 'position:fixed;left:0;top:0;width:2px;height:2px;opacity:0;pointer-events:none;overflow:hidden'
    host.append(v)
    if (snd) host.append(snd)
    document.body.append(host)
    try {
      if (snd) { snd.currentTime = 0; snd.play().catch(() => {}) }
      await v.play()
      let stalled = 0
      let lastT = -1
      await new Promise((resolve) => {
        const timer = setInterval(() => {
          ctx.drawImage(v, 0, 0, c.width, c.height)
          const t = v.currentTime - from
          onProgress(Math.min(1, t / length))
          stalled = v.currentTime === lastT ? stalled + 1 : 0
          lastT = v.currentTime
          if (t >= length || v.ended || stalled > 150) { clearInterval(timer); resolve() }
        }, 1000 / 30)
      })
    } finally {
      v.pause()
      if (snd) snd.pause()
      host.remove()
    }
    rec.stop()
    await done
    ac.close().catch(() => {})
    for (const t of stream.getTracks()) t.stop()
    return new Blob(parts, { type: (mimeType || 'video/webm').split(';')[0] })
  }

  /** Opens one reel by its channel and ID (from a story or a link). */
  async function openReel (ch, id) {
    const { M, toast } = U()
    if (U().setTab) U().setTab('reels'); else show()
    const r = await M.call('reels.feed', { kind: 'user', ch, limit: 200 }).catch(() => null)
    const it = r && (r.items || r).find ? (r.items || r).find((x) => x.id === id) : null
    if (!it) { toast("This video isn't available right now"); return }
    openPlayer([it], 0)
  }
  window.MeshronReels = { show, hide, openProfile, openReel, openCreate, transcode, grabThumb, videoUrl, imgSrc }
})()
