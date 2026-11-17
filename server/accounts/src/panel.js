// The moderator panel: a single page served at GET /panel. Moderators sign in with their
// Meshron username and password; the password is turned into the account key right here in
// the browser (Argon2id, same as the app), so it never leaves the browser.
export const PANEL_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Meshron Moderation</title>
<style>
:root{--bg:#0f1117;--card:#181b24;--line:#262a36;--text:#eef0f6;--dim:#9aa0b4;--accent:#6b5bff;--danger:#ff3b5c;--ok:#2ee59d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
header{display:flex;align-items:center;gap:12px;padding:14px 18px;border-bottom:1px solid var(--line);position:sticky;top:0;background:var(--bg)}
header h1{font-size:18px;margin:0;flex:1}.wrap{max-width:980px;margin:0 auto;padding:16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px;margin:10px 0}
.tabs{display:flex;gap:8px;flex-wrap:wrap;margin:6px 0 12px}.tab{padding:8px 14px;border-radius:999px;background:var(--card);border:1px solid var(--line);cursor:pointer}
.tab.on{background:var(--text);color:var(--bg)}.muted{color:var(--dim);font-size:13px;overflow-wrap:anywhere}.card{overflow-wrap:anywhere}pre{white-space:pre-wrap;background:#0b0d12;border-radius:10px;padding:10px;margin:8px 0;max-height:220px;overflow:auto}
button{border:0;border-radius:10px;padding:8px 12px;margin:4px 6px 0 0;cursor:pointer;font-weight:600;background:#2a2f3d;color:var(--text)}
button.primary{background:var(--accent)}button.danger{background:var(--danger)}button.ok{background:var(--ok);color:#06210f}
input,textarea{width:100%;padding:10px 12px;border-radius:10px;border:1px solid var(--line);background:#0b0d12;color:var(--text);font:inherit;margin:6px 0}
.login{max-width:380px;margin:12vh auto}.pill{display:inline-block;padding:2px 8px;border-radius:999px;background:#2a2f3d;font-size:12px;margin-left:6px}
</style></head><body>
<header><h1>Meshron Moderation</h1><span id="who" class="muted"></span><button id="out" hidden>Sign out</button></header>
<div class="wrap" id="app"></div>
<script src="https://cdn.jsdelivr.net/npm/hash-wasm@4.11.0/dist/argon2.umd.min.js"></script>
<script>
const $ = (h) => { const d = document.createElement('div'); d.innerHTML = h; return d.firstElementChild }
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))
let session = null
try { session = JSON.parse(sessionStorage.getItem('mod') || 'null') } catch {}
async function api (path, body) {
  const r = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })
  const o = await r.json()
  if (o.error) throw new Error(o.error)
  return o
}
async function authKey (username, password) {
  const salt = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode('meshron-account-v1:' + username))).slice(0, 16)
  const out = await hashwasm.argon2id({ password, salt, parallelism: 1, iterations: 3, memorySize: 65536, hashLength: 64, outputType: 'binary' })
  return [...out.slice(0, 32)].map((b) => b.toString(16).padStart(2, '0')).join('')
}
function loginView () {
  const app = document.getElementById('app'); app.innerHTML = ''
  const f = $('<form class="card login"><h2>Moderator sign-in</h2><p class="muted">Use your Meshron username and password. Only moderators get in.</p><input name="u" placeholder="Username" autocomplete="username"><input name="p" type="password" placeholder="Password" autocomplete="current-password"><button class="primary">Sign in</button><p class="muted" id="msg"></p></form>')
  f.onsubmit = async (e) => {
    e.preventDefault()
    const u = f.u.value.trim().replace(/^@/, '').toLowerCase()
    f.querySelector('#msg').textContent = 'Checking…'
    try {
      const k = await authKey(u, f.p.value)
      const me = await api('/v1/mod/me', { username: u, authKey: k })
      if (!me.role) throw new Error('This account is not a Meshron moderator.')
      session = { username: u, authKey: k, role: me.role }
      sessionStorage.setItem('mod', JSON.stringify(session))
      main()
    } catch (err) { f.querySelector('#msg').textContent = err.message }
  }
  app.append(f)
}
let tab = 'reports'
async function act (body, done) {
  try { await api('/v1/mod/act', { username: session.username, authKey: session.authKey, ...body }); if (done) done(); main() } catch (e) { alert(e.message) }
}
function why (def) { const r = prompt('Reason (the person sees this):', def || ''); return r == null ? null : r }
async function main () {
  if (!session) return loginView()
  document.getElementById('who').textContent = '@' + session.username + ' · ' + session.role
  const out = document.getElementById('out'); out.hidden = false; out.onclick = () => { sessionStorage.removeItem('mod'); session = null; out.hidden = true; document.getElementById('who').textContent = ''; loginView() }
  const app = document.getElementById('app'); app.innerHTML = '<p class="muted">Loading…</p>'
  let q
  try { q = await api('/v1/mod/queue', { username: session.username, authKey: session.authKey }) } catch (e) { app.innerHTML = '<p>' + esc(e.message) + '</p>'; return }
  app.innerHTML = ''
  const tabs = $('<div class="tabs"></div>')
  const names = { reports: 'Reports (' + q.reports.length + ')', appeals: 'Appeals (' + q.appeals.length + ')', hidden: 'Removed (' + q.hidden.length + ')', verify: 'Verification (' + q.verifications.length + ')', tools: 'Tools' }
  for (const [k, v] of Object.entries(names)) { const t = $('<span class="tab' + (k === tab ? ' on' : '') + '">' + v + '</span>'); t.onclick = () => { tab = k; main() }; tabs.append(t) }
  app.append(tabs)
  if (tab === 'reports') {
    if (!q.reports.length) app.append($('<p class="muted">Nothing waiting. 🎉</p>'))
    for (const r of q.reports) {
      const ai = r.ai ? r.ai.action + ' · severity ' + r.ai.severity + ' · ' + r.ai.category + ' — ' + r.ai.reason + (r.ai.human ? ' (asked for a human)' : '') : 'AI unavailable'
      const c = $('<div class="card"><b>' + esc(r.kind) + '</b> <span class="pill">' + esc(r.status) + '</span> <span class="muted">' + new Date(r.ts).toLocaleString() + '</span>' +
        '<p><b>Reason:</b> ' + esc(r.reason) + (r.details ? ' — ' + esc(r.details) : '') + '</p>' +
        '<p class="muted">Target: ' + esc(r.target) + ' · Owner: ' + esc(r.owner || '?') + ' · ' + esc(r.name) + '</p>' +
        (r.content ? '<pre>' + esc(r.content) + '</pre>' : '') + (r.page ? '<pre>' + esc(r.page) + '</pre>' : '') +
        '<p><b>AI moderator:</b> ' + esc(ai) + '</p><p class="muted">Done so far: ' + esc((r.actions || []).join(', ') || 'nothing') + (r.shot ? ' · screenshot in the email' : '') + '</p></div>')
      const add = (label, cls, fn) => { const b = $('<button class="' + cls + '">' + label + '</button>'); b.onclick = fn; c.append(b) }
      const hideable = ['reel', 'comment', 'live', 'moment'].includes(r.kind)
      if (hideable) add('Keep removed / remove', 'danger', () => { const w = why(r.ai && r.ai.reason); if (w != null) act({ action: 'confirm', report: r.id, kind: r.kind, target: r.target, owner: r.owner, reason: w }) })
      if (hideable) add('Restore', 'ok', () => act({ action: 'restore', report: r.id, kind: r.kind, target: r.target }))
      if (r.owner) add('Warn', '', () => { const w = why(); if (w != null) act({ action: 'warn', report: r.id, who: r.owner, reason: w }) })
      if (r.owner) add('Ban account', 'danger', () => { const w = why(); if (w != null && confirm('Suspend this account?')) act({ action: 'ban', report: r.id, who: r.owner, reason: w }) })
      add('Dismiss', '', () => act({ action: 'dismiss', report: r.id }))
      app.append(c)
    }
  } else if (tab === 'appeals') {
    if (!q.appeals.length) app.append($('<p class="muted">No open appeals.</p>'))
    for (const a of q.appeals) {
      const c = $('<div class="card"><b>' + esc(a.kind) + '</b> <span class="muted">' + new Date(a.ts).toLocaleString() + '</span><p class="muted">' + esc(a.target) + '</p><p><b>Removed for:</b> ' + esc(a.reason) + '</p><pre>' + esc(a.text) + '</pre></div>')
      const ok = $('<button class="ok">Accept: restore</button>'); ok.onclick = () => act(a.kind === 'person' ? { action: 'unban', who: a.owner } : { action: 'restore', kind: a.kind, target: a.target })
      const no = $('<button class="danger">Deny</button>'); no.onclick = () => { const w = why(); if (w != null) act({ action: 'deny-appeal', kind: a.kind, target: a.target, reason: w }) }
      c.append(ok, no); app.append(c)
    }
  } else if (tab === 'hidden') {
    if (!q.hidden.length) app.append($('<p class="muted">Nothing removed.</p>'))
    for (const h of q.hidden) {
      const c = $('<div class="card"><b>' + esc(h.kind) + '</b> <span class="pill">by ' + esc(h.by) + '</span> <span class="muted">' + new Date(h.ts).toLocaleString() + '</span><p class="muted">' + esc(h.target) + ' · owner ' + esc(h.owner || '?') + '</p><p>' + esc(h.reason) + '</p></div>')
      const b = $('<button class="ok">Restore</button>'); b.onclick = () => act({ action: 'restore', kind: h.kind, target: h.target })
      c.append(b); app.append(c)
    }
  } else if (tab === 'verify') {
    if (!q.verifications.length) app.append($('<p class="muted">No requests.</p>'))
    for (const v of q.verifications) {
      const c = $('<div class="card"><b>' + esc(v.name) + '</b> @' + esc(v.username) + ' <span class="muted">' + new Date(v.ts).toLocaleString() + '</span><p class="muted">' + esc(v.category) + ' · ' + esc(v.id) + '</p><pre>' + esc(v.reason) + '\\n' + esc(v.links) + '</pre></div>')
      const y = $('<button class="ok">Verify ✓</button>'); y.onclick = () => act({ action: 'verify', who: v.id })
      const n = $('<button class="danger">Decline</button>'); n.onclick = () => { const w = why(); if (w != null) act({ action: 'deny-verify', who: v.id, reason: w }) }
      c.append(y, n); app.append(c)
    }
  } else {
    const c = $('<div class="card"><h3>Message someone</h3><input id="nwho" placeholder="Their Meshron ID (64 letters)"><input id="ntitle" placeholder="Title"><textarea id="ntext" placeholder="Message (shows in their Activity)"></textarea><button class="primary" id="nsend">Send</button></div>')
    c.querySelector('#nsend').onclick = () => act({ action: 'notify', who: c.querySelector('#nwho').value.trim(), reason: c.querySelector('#ntitle').value, text: c.querySelector('#ntext').value }, () => alert('Sent'))
    app.append(c)
    const v = $('<div class="card"><h3>Verify / unverify / ban by ID</h3><input id="vwho" placeholder="Meshron ID"><button class="ok" id="v1">Verify</button><button id="v2">Remove check</button><button class="danger" id="v3">Ban</button><button id="v4">Unban</button></div>')
    const who = () => v.querySelector('#vwho').value.trim()
    v.querySelector('#v1').onclick = () => act({ action: 'verify', who: who() })
    v.querySelector('#v2').onclick = () => { const w = why(); if (w != null) act({ action: 'unverify', who: who(), reason: w }) }
    v.querySelector('#v3').onclick = () => { const w = why(); if (w != null) act({ action: 'ban', who: who(), reason: w }) }
    v.querySelector('#v4').onclick = () => act({ action: 'unban', who: who() })
    app.append(v)
    if (session.role === 'admin') {
      const r = $('<div class="card"><h3>Moderators (admins only)</h3><input id="rwho" placeholder="Username"><button class="primary" id="r1">Make moderator</button><button class="danger" id="r2">Remove moderator</button></div>')
      r.querySelector('#r1').onclick = () => act({ action: 'role', who: r.querySelector('#rwho').value.trim().replace(/^@/, ''), role: 'mod' }, () => alert('Done'))
      r.querySelector('#r2').onclick = () => act({ action: 'role', who: r.querySelector('#rwho').value.trim().replace(/^@/, ''), role: '' }, () => alert('Done'))
      app.append(r)
    }
  }
}
main()
</script></body></html>`
