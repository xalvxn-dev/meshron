// The Me header for the shared screens (iPhone + Windows), like Android's: photo, name with
// badges, the CEOs' special bio card, link, Following / Followers / Likes (tap for the lists,
// with follow back / unfollow / unfollow everyone who doesn't follow back) and Edit profile.

;(function () {
  'use strict'
  const UI = window.MeshronUI
  const { h, M, modal, toast, oops, avatar } = UI
  const fmt = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1) + 'M' : n >= 1e4 ? Math.floor(n / 1e3) + 'K' : n >= 1e3 ? (n / 1e3).toFixed(1) + 'K' : String(n || 0))
  const GOLD = 'linear-gradient(135deg,#FFF1B8,#FFC83D,#D99A00,#8A5A00)'
  const SILVER = 'linear-gradient(135deg,#FFFFFF,#E4E8EE,#B4BBC6,#7D8591)'

  /** The bio; for the CEOs a metallic card with their title on it. */
  function roleBio (id, bio) {
    // Just the bio, for everyone (the founders' badge by their name says who they are).
    void id
    return h('div', { class: 'role-bio' }, bio ? h('div', { class: 'me-bio-text', style: { textAlign: 'center', padding: '6px 28px', whiteSpace: 'pre-wrap' } }, bio) : null)
  }

  function stat (value, label, onClick) {
    return h('button', { class: 'me-stat', onClick, style: { background: 'none', border: 'none', color: 'inherit', cursor: 'pointer', textAlign: 'center', padding: '4px 10px' } },
      h('div', { style: { fontWeight: 800, fontSize: '19px' } }, value), h('div', { class: 'dim', style: { fontSize: '13px' } }, label))
  }

  /** The header element (fills its numbers in when they arrive). */
  function head () {
    const st = UI.st
    const me = st.me
    const p = st.profiles.me || {}
    // The last numbers show at once (no '…' flash on every refresh), then update quietly.
    const last = head.last || { following: '…', followers: '…', likes: '…' }
    const nums = h('div', { class: 'me-stats', style: { display: 'flex', justifyContent: 'center', gap: '22px', margin: '12px 0 4px' } }, stat(last.following, 'Following', () => people('following')), stat(last.followers, 'Followers', () => people('followers')), stat(last.likes, 'Likes'))
    Promise.all([M.call('reels.people', { kind: 'following' }).catch(() => null), M.call('reels.status').then((s) => (s.ch ? M.call('reels.profile', { ch: s.ch }) : null)).catch(() => null)]).then(([pp, pr]) => {
      const c = (pp && pp.counts) || {}
      const now = { following: fmt(c.following), followers: fmt(c.followers), likes: fmt(pr ? pr.likes : 0) }
      if (JSON.stringify(now) === JSON.stringify(head.last)) return
      head.last = now
      nums.replaceChildren(stat(now.following, 'Following', () => people('following')), stat(now.followers, 'Followers', () => people('followers')), stat(now.likes, 'Likes'))
    })
    return h('div', { class: 'me-head', style: { display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: '14px' } },
      h('div', { style: { padding: '4px', borderRadius: '50%', background: 'linear-gradient(135deg,#19D3C5,#4F7BFF,#8B5CFF)' } }, h('div', { style: { padding: '3px', borderRadius: '50%', background: 'var(--bg,#0b0d14)' } }, avatar(me.username, me.publicKey, 96))),
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px', marginTop: '10px' } }, h('div', { class: 't-h1' }, me.username), window.MeshronMod ? window.MeshronMod.badgeEl(me.publicKey, 18, true) : null),
      me.handle ? h('div', { class: 'dim' }, '@' + me.handle) : null,
      roleBio(me.publicKey, p.bio || ''),
      p.link ? h('a', { href: p.link, target: '_blank', rel: 'noopener noreferrer', style: { color: '#7C95FF', fontWeight: 600, textDecoration: 'none', marginTop: '4px' } }, '🔗 ' + p.link.replace(/^https:\/\//, '')) : null,
      nums,
      h('div', { style: { display: 'flex', gap: '10px', width: '100%', padding: '8px 16px' } },
        h('button', { class: 'btn light', style: { flex: 1, minHeight: '54px', fontSize: '17px' }, onClick: editProfile }, 'Edit profile'),
        h('button', { class: 'btn dark', style: { flex: 1, minHeight: '54px', fontSize: '17px' }, onClick: () => UI.showAddContact() }, 'Add friends')))
  }

  /** Following / Followers / Friends, with follow back and unfollow. */
  function people (start) {
    let tab = start
    const tabs = h('div', { style: { display: 'flex', gap: '8px', marginBottom: '10px' } })
    const list = h('div', {}, h('div', { class: 'spinner' }))
    const m = modal({ title: 'Connections', body: h('div', {}, tabs, list) })
    async function load () {
      tabs.replaceChildren(...[['following', 'Following'], ['followers', 'Followers'], ['friends', 'Friends']].map(([id, label]) =>
        h('button', { class: 'btn small ' + (tab === id ? 'primary' : 'soft'), onClick: () => { tab = id; load() } }, label)))
      list.replaceChildren(h('div', { class: 'spinner' }))
      try {
        const r = await M.call('reels.people', { kind: tab })
        const rows = r.list || []
        const purge = tab === 'following' && rows.some((a) => !a.followsMe)
          ? h('button', { class: 'btn soft danger wide', style: { marginBottom: '10px' }, onClick: async () => {
            if (!(await UI.confirmBox("Unfollow people who don't follow back?", "You'll stop following everyone here who doesn't follow you. You can follow them again any time.", 'Unfollow', true))) return
            let n = 0
            for (const a of rows) if (!a.followsMe && a.ch) { await M.call('reels.follow', { ch: a.ch, on: false }).catch(() => {}); n++ }
            toast('Unfollowed ' + n); load()
          } }, "Unfollow everyone who doesn't follow you back") : null
        list.replaceChildren(purge, ...(rows.length ? rows.map((a) => {
          const label = !a.ch ? null : a.following ? (a.followsMe ? 'Friends' : 'Following') : a.followsMe ? 'Follow back' : 'Follow'
          return h('div', { style: { display: 'flex', alignItems: 'center', gap: '12px', padding: '8px 2px' } },
            avatar(a.name || '?', a.id, 44),
            h('div', { class: 'grow' }, h('div', { style: { fontWeight: 700 } }, a.name || 'Someone', window.MeshronMod ? window.MeshronMod.badgeEl(a.id, 12) : null),
              h('div', { class: 'dim', style: { fontSize: '12px' } }, [a.handle ? '@' + a.handle : null, a.following && a.followsMe ? 'Friends' : a.followsMe ? 'Follows you' : null].filter(Boolean).join(' · '))),
            label ? h('button', { class: 'btn small ' + (a.following ? 'soft' : 'primary'), onClick: async () => { await M.call('reels.follow', { ch: a.ch, on: !a.following }).catch(oops); load() } }, label) : null)
        }) : [h('p', { class: 'dim' }, tab === 'following' ? "You aren't following anyone yet." : tab === 'followers' ? 'Nobody follows you yet.' : 'Friends are people you follow who follow you back.')]))
      } catch (e) { list.replaceChildren(h('p', { class: 'dim' }, e.message)) }
    }
    load()
    return m
  }

  /** Edit profile: type straight into the rows (name once a week, @username once every 30 days). */
  function editProfile () {
    const st = UI.st
    const me = st.me
    const p = st.profiles.me || {}
    const day = 86400000
    const nameAt = Number(localStorage.getItem('meshron.nameAt') || 0)
    const handleAt = Number(localStorage.getItem('meshron.handleAt') || 0)
    const nameLocked = nameAt + 7 * day > Date.now()
    const handleLocked = handleAt + 30 * day > Date.now()
    const when = (t) => new Date(t).toLocaleDateString()
    const row = (label, input, note) => h('div', { style: { padding: '10px 0', borderBottom: '1px solid rgba(255,255,255,0.06)' } },
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } }, h('div', { class: 'dim', style: { width: '90px', flex: 'none' } }, label), input),
      note ? h('div', { class: 'hint', style: { marginLeft: '100px' } }, note) : null)
    const inp = (v, extra) => h('input', Object.assign({ class: 'input', value: v || '', style: { flex: 1 } }, extra || {}))
    const name = inp(me.username, { maxlength: 32, disabled: nameLocked })
    const handle = inp(me.handle, { maxlength: 24, disabled: handleLocked, autocapitalize: 'none', spellcheck: 'false' })
    const bio = h('textarea', { class: 'input', rows: 3, maxlength: 150, style: { flex: 1 } }); bio.value = p.bio || ''
    const link = inp((p.link || '').replace(/^https:\/\//, ''), { placeholder: 'yourwebsite.com', maxlength: 200, autocapitalize: 'none' })
    const err = h('div', { class: 'hint bad' })
    const body = h('div', {},
      h('div', { style: { textAlign: 'center', marginBottom: '8px' } }, avatar(me.username, me.publicKey, 84), h('div', {}, h('button', { class: 'btn small ghost', onClick: () => UI.pickProfilePhoto && UI.pickProfilePhoto() }, 'Change photo'))),
      row('Name', name, nameLocked ? 'You can change your name again on ' + when(nameAt + 7 * day) : 'You can change your name once every 7 days.'),
      st.anon ? null : row('Username', handle, handleLocked ? 'You can change your username again on ' + when(handleAt + 30 * day) : 'You can change your username once every 30 days.'),
      st.anon ? null : row('Bio', bio, null),
      st.anon ? null : row('Link', link, null), err)
    modal({ title: 'Edit profile', body, buttons: [{ label: 'Cancel', onClick: (m) => m.close() }, { label: 'Save', kind: 'primary', onClick: async (m) => {
      err.textContent = ''
      try {
        if (!nameLocked && name.value.trim() && name.value.trim() !== me.username) { await M.call('identity.rename', { name: name.value }); localStorage.setItem('meshron.nameAt', String(Date.now())) }
        const hh = handle.value.trim().replace(/^@/, '').toLowerCase()
        if (!st.anon && !handleLocked && hh !== (me.handle || '')) { await M.call('identity.setHandle', { handle: hh }); localStorage.setItem('meshron.handleAt', String(Date.now())) }
        if (!st.anon) st.profiles = await M.call('profile.set', { bio: bio.value, link: link.value })
        m.close(); toast('Saved')
      } catch (e) { err.textContent = e.message || String(e) }
    } }] })
  }

  window.MeshronProfile = { head, people, editProfile, roleBio }
})()
