// Meshron accounts for the shared screens (iPhone + Windows): sign in with @username and
// password (on a new device), create an account for this device's ID, and the requests that
// need one (Meshron AI, translation). The password crypto runs in the host, like Android.

;(function () {
  'use strict'
  const UI = window.MeshronUI
  const { h, M, modal, toast, oops } = UI

  // The account server. Override for testing with ?accounts=http://127.0.0.1:8799 (remembered).
  try { const q = new URLSearchParams(location.search).get('accounts'); if (q) localStorage.setItem('meshron.accountsUrl', q) } catch {}
  const ACCOUNTS = ((() => { try { return localStorage.getItem('meshron.accountsUrl') } catch { return null } })() || 'https://meshron-accounts.meshron.workers.dev').replace(/\/$/, '')

  async function post (path, body) {
    let r
    try { r = await fetch(ACCOUNTS + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }) } catch { throw new Error('No connection. Check the internet and try again.') }
    const o = await r.json().catch(() => ({}))
    if (!r.ok || o.error) throw new Error(o.error || 'Something went wrong (' + r.status + ')')
    return o
  }
  /** A request as the signed-in account (AI, translation, moderators' tools). */
  async function authed (path, body) {
    const a = await M.call('account.auth')
    return post(path, Object.assign({}, body, a))
  }

  let state = null
  async function refresh () { state = await M.call('account.state').catch(() => null); return state }
  M.on && M.on('account', (a) => { state = a })

  // ---- Screens ----
  function field (placeholder, type, extra) {
    return h('input', Object.assign({ class: 'input', placeholder, type: type || 'text', autocomplete: 'off', autocapitalize: 'none', spellcheck: 'false' }, extra || {}))
  }

  /** Sign in on this device (also used from the welcome screen). */
  function showSignIn () {
    const user = field('@username')
    const pass = field('Password', 'password')
    const code = field('Email code', 'text', { inputmode: 'numeric', maxlength: 6 })
    const codeWrap = h('div', { style: { display: 'none', marginTop: '10px' } }, h('p', { class: 'dim' }, 'Your account has 2-step verification. Enter the code we emailed you.'), code)
    const err = h('div', { class: 'hint bad' })
    let needCode = false
    const go = h('button', { class: 'btn primary wide', style: { marginTop: '16px' } }, 'Sign in')
    const m = modal({ title: 'Sign in to Meshron', body: h('div', {}, h('p', { class: 'dim' }, 'Use your Meshron @username and password. Your chats on this device stay.'), user, h('div', { style: { height: '10px' } }), pass, codeWrap, err, go) })
    go.onclick = async () => {
      err.textContent = ''; go.disabled = true; go.textContent = 'Signing in…'
      try {
        let res
        if (!needCode) {
          const k = await M.call('account.keys', { username: user.value, password: pass.value })
          res = await post('/v1/login', { username: k.username, authKey: k.authKey })
          if (res.needCode) { needCode = true; codeWrap.style.display = 'block'; go.disabled = false; go.textContent = 'Continue'; return }
        } else {
          const k = await M.call('account.auth').catch(() => null)
          const keys = k || await M.call('account.keys', { username: user.value, password: pass.value })
          res = await post('/v1/login/code', { username: keys.username, authKey: keys.authKey, code: code.value.trim() })
        }
        await M.call('account.adopt', { blob: res.blob, publicKey: res.publicKey, email: res.email, twoFactor: res.twoFactor })
        m && m.close && m.close()
        toast('Signed in as @' + user.value.trim().replace(/^@/, '').toLowerCase())
      } catch (e) { err.textContent = e.message || String(e); go.disabled = false; go.textContent = needCode ? 'Continue' : 'Sign in' }
    }
  }

  /** Create an account for this device's Meshron ID (username + password + email code). */
  function showSignUp () {
    const st = UI.st
    const email = field('Email', 'email')
    const user = field('@username')
    user.value = (st.me && st.me.handle) || ''
    const pass = field('Password (8+ characters)', 'password')
    const code = field('6-digit code', 'text', { inputmode: 'numeric', maxlength: 6 })
    const step2 = h('div', { style: { display: 'none' } }, h('div', { style: { height: '10px' } }), code, h('div', { style: { height: '10px' } }), user, h('div', { style: { height: '10px' } }), pass)
    const err = h('div', { class: 'hint bad' })
    const go = h('button', { class: 'btn primary wide', style: { marginTop: '16px' } }, 'Send code')
    let sent = false
    const m = modal({ title: 'Create an account', body: h('div', {}, h('p', { class: 'dim' }, 'Keeps your ID, chats and contacts. Adds sign-in with a password on new devices.'), email, step2, err, go) })
    go.onclick = async () => {
      err.textContent = ''; go.disabled = true
      try {
        if (!sent) {
          await post('/v1/signup/code', { email: email.value.trim() })
          sent = true; step2.style.display = 'block'; go.textContent = 'Create account'
        } else {
          if (pass.value.length < 8) throw new Error('Passwords need at least 8 characters')
          go.textContent = 'Creating…'
          const k = await M.call('account.keys', { username: user.value, password: pass.value })
          const d = await M.call('account.signupData', { username: k.username })
          await post('/v1/signup', { username: k.username, email: email.value.trim(), code: code.value.trim(), authKey: d.authKey, blob: d.blob, publicKey: d.publicKey, sig: d.sig })
          await M.call('account.saved', { email: email.value.trim(), twoFactor: false })
          m && m.close && m.close()
          toast('Account created: @' + k.username)
        }
      } catch (e) { err.textContent = e.message || String(e); go.textContent = sent ? 'Create account' : 'Send code' }
      go.disabled = false
    }
  }

  window.MeshronAccount = { ACCOUNTS, post, authed, refresh, get state () { return state }, showSignIn, showSignUp }
  refresh()
})()
