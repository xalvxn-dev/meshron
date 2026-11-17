import { sendMail } from './mail.js'
// Moderation: reports, the AI moderator, hidden content, strikes and bans, notices, appeals,
// and the tools human moderators use.
//
// Content on Meshron lives on people's phones (P2P), so "removing" something means the server
// keeps a list of what is hidden and every app filters it out. Nothing is deleted: a moderator
// (or a won appeal) restores it with its likes and comments intact.
//
// KV keys:
//   r:<reportId>                 report { id, ts, reporter, kind, target, owner, reason, details, page, status, ai, actions[] }
//   h:<kind>:<target>            hidden item { kind, target, owner, reason, category, by, ts, report }
//   s:<identity>                 strikes { n, last }
//   b:<identity>                 ban { reason, ts, by }
//   n:<identity>:<ts>:<rand>     notice for one person { ts, from, title, text, kind, target, action }
//   a:<kind>:<target>            appeal { owner, text, ts, status }
//   role:<username>              'admin' | 'mod' (human moderators; founders are admins via ADMINS)
//   rq:<ts>:<reportId>           open review queue entry (reports that need a human)
//   v:<identity>                 verified badge { by, ts }
//   ma:<identity>                application to moderate { id, username, ts, why, hours, languages, status }
//
// Admins are the CEOs (ADMINS = the founders). Only they appoint or remove moderators; a
// moderator can never change anyone's role.
//
// Requests from the app are signed with the person's Meshron identity (Ed25519):
//   body = { id, ts, sig, p }   p = JSON payload string
//   sig  = sign("meshron-mod-v1\n" + path + "\n" + ts + "\n" + sha256hex(p))

const FOUNDER = 'alvinpieterson' // CEO & founder: Alvin Nana Kojo Pieterson
const COFOUNDER = 'clementyeboah' // co-CEO & co-founder: Clement Kofi Yeboah
const KINDS = ['reel', 'comment', 'person', 'group', 'message', 'live', 'moment']
const HIDEABLE = ['reel', 'comment', 'live', 'moment']
const STRIKES_TO_BAN = 3
const NOTICE_TTL = 90 * 86400
const MAX_SHOT = 900 * 1024 // base64 chars
const CHECK_KINDS = ['comment', 'group', 'caption', 'description', 'username', 'name', 'bio']
const CHECK_WHERE = { comment: 'a comment under a video', group: 'a group chat message', caption: 'a video caption', description: 'a description', username: 'a @username', name: 'a display name', bio: 'a profile bio' }

export const RULES_PROMPT = 'You are Meshron AI, checking text the moment someone posts it on Meshron, a social app. You get where it was posted, the audience, and the text. Decide if it clearly breaks Meshron\'s rules.\n' +
  'Rules for everyone: no harassment or bullying of a person, no hate against a group, no threats or calls to violence, no sexual content involving minors (always severity 5), no promoting self-harm or suicide, no scams or fraud, no spam or mass advertising, no impersonation of real people or of Meshron staff, no sharing someone\'s private information (doxxing), no selling drugs or weapons.\n' +
  'Strict audience (everyone, including teens): also no sexual or explicit content, no graphic violence, no slurs, no heavy profanity aimed at someone. Usernames, names and bios must not contain slurs, sexual words, or pretend to be Meshron, a moderator or a famous person.\n' +
  '18+ group: adults may swear, joke crudely and talk about adult topics between themselves; only the rules for everyone apply.\n' +
  'Do not flag disagreement, criticism, mild swearing, jokes, slang, song lyrics, or words in any language that are only rude. When unsure, it is ok. Reply with JSON only: {"ok":true|false,"category":"none|harassment|hate|sexual|child-safety|violence|self-harm|spam|scam|impersonation|privacy|illegal|other","rule":"<the rule broken, 2 to 5 words, in English>","reason":"<one short, kind sentence to the person, written in the reader\'s language given above, saying what is wrong>","severity":1-5}.'

export const OVERSEE_PROMPT = 'You check the decisions of human moderators on Meshron, a social app, to catch abuse of power. You get the action (remove a post, warn, or suspend an account), what was acted on, the reason the moderator gave, and any report or AI verdict behind it. Meshron\'s rules forbid: harassment, hate, sexual content, child sexual content, violence or threats, self-harm promotion, scams, spam, impersonation, dangerous misinformation. Judge only whether the action is justified by the rules and the evidence. An action is NOT fair when the content plainly breaks no rule, when there is no evidence and no real reason, when the reason is personal or insulting, or when the punishment is far too heavy. If the content is missing, judge from the reason and report; a removal with no content, no report and only a generic reason is suspicious. Reply with JSON only: {"fair":true|false,"concern":"<one short sentence for the CEOs, empty if fair>","severity":1-5}.'

export const REVIEW_PROMPT = 'You are the AI moderator of Meshron, a social app. You review a user report. Apply strict but fair rules: no harassment or bullying, no hate, no sexual content involving minors (always remove, severity 5), no nudity or sexual content, no violence or threats, no self-harm promotion, no scams, no spam, no impersonation, no dangerous misinformation. Reply with JSON only: {"action":"none|hide|warn|ban","severity":1-5,"category":"none|harassment|hate|sexual|child-safety|violence|self-harm|spam|scam|impersonation|misinformation|ip|other","reason":"<one short sentence the owner will read>","human":true|false}. "hide" removes the content (it can be restored on appeal); "warn" is a strike on the account; "ban" is for severe or repeated abuse. Set human=true if you are unsure.'

export function moderation ({ fail, limit, sha, verifySig, ai, clip, account, authed, HEX64, mailer, AI_ID }) {
  const now = () => Date.now()
  const rid = () => now().toString(36) + Math.random().toString(36).slice(2, 8)

  async function signed (path, body) {
    const { id, ts, sig, p } = body || {}
    if (!HEX64.test(String(id || ''))) throw fail('Bad identity')
    if (typeof p !== 'string' || p.length > 1_200_000) throw fail('Bad request')
    if (Math.abs(now() - Number(ts)) > 5 * 60 * 1000) throw fail('Your phone clock looks wrong. Check the time and try again.')
    const msg = 'meshron-mod-v1\n' + path + '\n' + ts + '\n' + (await sha(p))
    if (!(await verifySig(id, msg, sig))) throw fail('Bad signature', 401)
    let payload
    try { payload = JSON.parse(p) } catch { throw fail('Bad request') }
    return { id, payload }
  }

  async function notice (env, to, n) {
    if (!HEX64.test(String(to || ''))) return
    const ts = now()
    await env.ACCOUNTS.put('n:' + to + ':' + String(ts).padStart(15, '0') + ':' + Math.random().toString(36).slice(2, 6),
      JSON.stringify({ ts, from: 'Meshron Moderation', ...n }), { expirationTtl: NOTICE_TTL })
  }

  async function strike (env, who, reason) {
    const s = JSON.parse((await env.ACCOUNTS.get('s:' + who)) || '{"n":0}')
    s.n++; s.last = now()
    await env.ACCOUNTS.put('s:' + who, JSON.stringify(s))
    return s.n
  }

  async function ban (env, who, reason, by) {
    await env.ACCOUNTS.put('b:' + who, JSON.stringify({ reason, ts: now(), by }))
    await notice(env, who, { title: 'Your account was suspended', text: reason + ' You can appeal from Settings → Safety.', kind: 'person', target: who, action: 'ban' })
  }

  async function hide (env, kind, target, owner, reason, category, by, report) {
    await env.ACCOUNTS.put('h:' + kind + ':' + target, JSON.stringify({ kind, target, owner, reason, category, by, ts: now(), report }))
    await notice(env, owner, { title: 'Your ' + kind + ' was removed', text: reason + ' It is hidden, not deleted: if you think this is a mistake, appeal and a person will review it.', kind, target, action: 'hide' })
  }

  /** Applies a decision (from the AI or a human) to a report. */
  async function decide (env, r, d, by) {
    const out = []
    if (d.action === 'hide' && HIDEABLE.includes(r.kind)) { await hide(env, r.kind, r.target, r.owner, d.reason, d.category, by, r.id); out.push('hidden') }
    if ((d.action === 'warn' || d.action === 'hide') && r.owner) {
      const n = await strike(env, r.owner, d.reason)
      if (d.action === 'warn') await notice(env, r.owner, { title: 'Warning from Meshron', text: d.reason + ' Repeated problems lead to a suspension.', kind: 'person', target: r.owner, action: 'warn' })
      out.push('strike ' + n)
      if (n >= STRIKES_TO_BAN && !(await env.ACCOUNTS.get('b:' + r.owner))) { await ban(env, r.owner, 'Your account broke the rules several times.', by); out.push('banned') }
    }
    if (d.action === 'ban' && r.owner) {
      if (HIDEABLE.includes(r.kind)) await hide(env, r.kind, r.target, r.owner, d.reason, d.category, by, r.id)
      await ban(env, r.owner, d.reason, by); out.push('banned')
    }
    return out
  }

  async function emailModerators (env, subject, html, shot) {
    const to = (env.MOD_EMAIL || 'xalvxn@gmail.com').split(',').map((s) => s.trim()).filter(Boolean)
    if (env.DEV_CODES === '1') { mailer.last = { to, subject, html, shot: !!shot }; return }
    await sendMail(env, { to, subject, html, attachment: shot ? { name: 'screenshot.jpg', content: shot } : null }).catch(() => {})
  }

  const esc = (s) => String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

  async function isMod (env, username, authKey) {
    const { u } = await authed(env, username, authKey)
    const admins = String(env.ADMINS || '').split(/[,;\s]+/).map((s) => s.trim().toLowerCase()).filter(Boolean)
    const role = admins.includes(u) ? 'admin' : await env.ACCOUNTS.get('role:' + u)
    if (role !== 'admin' && role !== 'mod') throw fail('Only Meshron moderators can do that', 403)
    return { u, role }
  }

  async function list (env, prefix, max = 200) {
    const out = []
    let cursor
    do {
      const page = await env.ACCOUNTS.list({ prefix, cursor, limit: 1000 })
      for (const k of page.keys) { out.push(k.name); if (out.length >= max) return out }
      cursor = page.list_complete ? null : page.cursor
    } while (cursor)
    return out
  }

  // ---- Watching the watchers: every moderator action is logged, and the AI checks the ones
  // that punish someone. Unfair ones are flagged to the CEOs (Activity notice + email).
  //   ml:<ts>:<id>  log entry { id, ts, by, role, action, kind, target, owner, who, reason, content, report, check }
  const LOG_TTL = 180 * 86400
  const PUNISH = ['hide', 'warn', 'ban', 'deny-appeal']

  async function logAction (env, e) {
    const key = 'ml:' + String(e.ts).padStart(15, '0') + ':' + e.id
    await env.ACCOUNTS.put(key, JSON.stringify(e), { expirationTtl: LOG_TTL })
    return key
  }

  async function oversee (env, e) {
    let rep = null
    if (e.report) rep = JSON.parse(await env.ACCOUNTS.get('r:' + e.report) || 'null')
    try {
      const o = await ai(env, 'oversee', [{ role: 'user', content:
        'Action: ' + e.action + (e.kind ? ' (' + e.kind + ')' : '') + '\nModerator: ' + e.by + ' (' + e.role + ')' +
        '\nReason given: ' + (e.reason || '(none)') +
        '\nThe content: ' + (e.content || (rep && rep.content) || '(not included)') +
        (rep ? '\nReport: ' + rep.reason + ' — ' + (rep.details || '') + (rep.ai ? '\nAI moderator said: ' + rep.ai.action + ' (' + rep.ai.category + ', severity ' + rep.ai.severity + ') ' + rep.ai.reason : '') : '\nNo report behind this action.') }], true)
      return { fair: o.fair !== false, concern: clip(o.concern, 200), severity: Math.max(1, Math.min(5, Number(o.severity) || 1)) }
    } catch { return null }
  }

  async function alertCeos (env, e) {
    const admins = String(env.ADMINS || '').split(/[,;\s]+/).map((s) => s.trim().toLowerCase()).filter(Boolean)
    for (const name of admins) {
      const a = await account(env, name).catch(() => null)
      if (a && a.pk) await notice(env, a.pk, { title: 'A moderator action needs a look', text: e.by + ' used "' + e.action + '"' + (e.kind ? ' on a ' + e.kind : '') + '. AI: ' + e.check.concern, kind: 'modlog', target: e.id, action: 'mod-flag' })
    }
    await emailModerators(env, '[Meshron] Moderator action flagged: ' + e.by + ' · ' + e.action,
      `<div style="font-family:system-ui,sans-serif"><h2>Moderator action flagged by AI</h2><p><b>${esc(e.by)}</b> (${esc(e.role)}) · <b>${esc(e.action)}</b> ${esc(e.kind || '')} ${esc(e.target || e.who || '')}</p><p><b>Reason given:</b> ${esc(e.reason)}</p><p><b>Content:</b> ${esc(e.content || '(not included)')}</p><p><b>AI concern:</b> ${esc(e.check.concern)} (severity ${e.check.severity})</p><p>Review it in Meshron Mod → Activity.</p></div>`)
  }

  const routes = {
    /** A report about something or someone. The AI moderator reviews it right away. */
    async '/v1/report' (body, env) {
      const { id: reporter, payload: x } = await signed('/v1/report', body)
      if (!(await limit(env, 'report:' + reporter, 40, 86400))) throw fail('You\'ve sent a lot of reports today. Try again tomorrow.', 429)
      if (!KINDS.includes(x.kind)) throw fail('Unknown report type')
      const shot = typeof x.screenshot === 'string' && x.screenshot.length <= MAX_SHOT && /^[A-Za-z0-9+/=]+$/.test(x.screenshot) ? x.screenshot : ''
      const r = {
        id: rid(), ts: now(), reporter, kind: x.kind, target: clip(x.target, 200), owner: HEX64.test(String(x.owner || '')) ? x.owner : '',
        name: clip(x.name, 80), reason: clip(x.reason, 60), details: clip(x.details, 1000), page: clip(x.page, 3000), content: clip(x.content, 2000),
        shot: !!shot, status: 'open', ai: null, actions: []
      }
      // The AI moderator's first look (unless the CEOs turned AI moderation off).
      let d = null
      const aiOn = (await env.ACCOUNTS.get('cfg:aiauto')) !== 'off'
      if (aiOn) try {
        const o = await ai(env, 'review', [{ role: 'user', content:
          `Report type: ${r.kind}\nReason given: ${r.reason}\nReporter's words: ${r.details || '(none)'}\nThe reported content: ${r.content || '(not included)'}\nWhat was on screen: ${r.page || '(none)'}` }], true)
        d = {
          action: ['none', 'hide', 'warn', 'ban'].includes(o.action) ? o.action : 'none',
          severity: Math.max(1, Math.min(5, Number(o.severity) || 1)),
          category: clip(o.category, 30) || 'other', reason: clip(o.reason, 200) || 'This breaks Meshron\'s rules.', human: !!o.human
        }
      } catch { d = null }
      r.ai = d
      if (d && d.action !== 'none' && !d.human) {
        r.actions = await decide(env, r, d, 'ai')
        r.status = 'actioned'
      }
      // Anything the AI was unsure about, couldn't check, or acted on goes to people too.
      if (!d || d.human || d.action !== 'none') await env.ACCOUNTS.put('rq:' + String(r.ts).padStart(15, '0') + ':' + r.id, r.id)
      await env.ACCOUNTS.put('r:' + r.id, JSON.stringify(r))
      await notice(env, reporter, {
        title: 'Thanks for your report',
        text: !d ? 'A moderator will look at it soon.' : d.action === 'none' && !d.human ? 'We reviewed it and it doesn\'t seem to break our rules. A moderator can still look again.'
          : d.human ? 'A moderator will look at it soon.' : 'We took action. Thank you for keeping Meshron safe.',
        kind: r.kind, target: r.target, action: 'report'
      })
      await emailModerators(env, `[Meshron report] ${r.kind}: ${r.reason}${d ? ' → AI: ' + d.action : ''}`,
        `<div style="font-family:system-ui,sans-serif">
          <h2>New report ${esc(r.id)}</h2>
          <p><b>Type:</b> ${esc(r.kind)} · <b>Target:</b> ${esc(r.target)} · <b>Name:</b> ${esc(r.name)}</p>
          <p><b>Owner:</b> ${esc(r.owner || 'unknown')} · <b>Reporter:</b> ${esc(reporter)}</p>
          <p><b>Reason:</b> ${esc(r.reason)}</p><p><b>Details:</b> ${esc(r.details)}</p>
          <p><b>Content:</b></p><pre style="white-space:pre-wrap">${esc(r.content)}</pre>
          <p><b>Page info:</b></p><pre style="white-space:pre-wrap">${esc(r.page)}</pre>
          <p><b>AI moderator:</b> ${d ? esc(`${d.action} (severity ${d.severity}, ${d.category}) — ${d.reason}${d.human ? ' — asked for a human' : ''}`) : 'unavailable'}</p>
          <p><b>Done:</b> ${esc(r.actions.join(', ') || 'nothing yet')}</p>
          <p>Review it in the Meshron moderator panel.</p></div>`, shot)
      return { id: r.id, action: d ? d.action : 'pending', human: !d || d.human }
    },

    /** Which of these are hidden by moderators, and which accounts are suspended? (public) */
    async '/v1/mod/status' ({ items, people }, env) {
      const hidden = []
      for (const it of (Array.isArray(items) ? items : []).slice(0, 300)) {
        if (typeof it !== 'string' || it.length > 220) continue
        const raw = await env.ACCOUNTS.get('h:' + it)
        if (raw) { const h = JSON.parse(raw); hidden.push({ key: it, reason: h.reason, owner: h.owner }) }
      }
      const banned = []
      const verified = []
      for (const p of (Array.isArray(people) ? people : []).slice(0, 300)) {
        if (!HEX64.test(String(p))) continue
        if (await env.ACCOUNTS.get('b:' + p)) banned.push(p)
        if (await env.ACCOUNTS.get('v:' + p)) verified.push(p)
      }
      return { hidden, banned, verified }
    },

    /** My notices from moderation (newest first). */
    async '/v1/mod/inbox' (body, env) {
      const { id } = await signed('/v1/mod/inbox', body)
      const keys = await list(env, 'n:' + id + ':', 200)
      const out = []
      for (const k of keys.reverse().slice(0, 100)) { const v = await env.ACCOUNTS.get(k); if (v) out.push({ key: k.split(':').slice(2).join(':'), ...JSON.parse(v) }) }
      const banned = await env.ACCOUNTS.get('b:' + id)
      return { notices: out, banned: banned ? JSON.parse(banned) : null }
    },

    /**
     * Meshron AI checks text as it's posted (comments, group messages, captions, descriptions,
     * usernames, names, bios). When it breaks a rule the person gets a warning right away (in
     * the app and in their Activity). 18+ groups have looser rules (adult talk, swearing), but
     * the rules that protect people still apply there.
     *   payload: { kind, text, adult }
     *   reply:   { ok, category, rule, reason, severity }
     */
    async '/v1/mod/check' (body, env) {
      const { id, payload: x } = await signed('/v1/mod/check', body)
      const kind = CHECK_KINDS.includes(x.kind) ? x.kind : 'comment'
      const text = clip(x.text, 2000).trim()
      if (!text || !/[\p{L}\p{N}]/u.test(text)) return { ok: true }
      if (!(await limit(env, 'chk:' + id, 600, 86400))) return { ok: true } // never block people for our limit
      const adult = kind === 'group' && x.adult === true
      let o
      try {
        o = await ai(env, 'rules', [{ role: 'user', content: 'Where: ' + CHECK_WHERE[kind] + '\nReader\'s language: ' + (clip(x.lang, 30) || 'English') + '\nAudience: ' + (adult ? '18+ group (adults only, looser rules)' : 'everyone, including teens (strict rules)') + '\nText:\n' + text }], true, env.AI_CHECK_MODEL)
      } catch { return { ok: true } } // the AI being busy never stops anyone posting
      if (o.ok !== false) return { ok: true }
      const severity = Math.max(1, Math.min(5, Number(o.severity) || 2))
      const out = { ok: false, category: clip(o.category, 20) || 'other', rule: clip(o.rule, 60) || 'Community Guidelines', reason: clip(o.reason, 200) || 'This breaks Meshron\'s Community Guidelines.', severity }
      if (await limit(env, 'chkwarn:' + id, 20, 86400)) {
        await notice(env, id, { from: 'Meshron AI', title: 'Warning: ' + out.rule, text: out.reason + ' (' + CHECK_WHERE[kind] + ')', kind: 'person', target: id, action: 'warn' })
      }
      return out
    },

    /** Appeal a removal (or a suspension). A person reviews every appeal. */
    async '/v1/mod/appeal' (body, env) {
      const { id, payload: x } = await signed('/v1/mod/appeal', body)
      if (!(await limit(env, 'appeal:' + id, 10, 86400))) throw fail('Too many appeals today.', 429)
      const key = x.kind === 'person' ? 'b:' + id : 'h:' + x.kind + ':' + clip(x.target, 200)
      const raw = await env.ACCOUNTS.get(key)
      if (!raw) throw fail('There\'s nothing to appeal here')
      const h = JSON.parse(raw)
      if (x.kind !== 'person' && h.owner !== id) throw fail('Only the owner can appeal this', 403)
      const ak = 'a:' + (x.kind === 'person' ? 'person:' + id : x.kind + ':' + clip(x.target, 200))
      await env.ACCOUNTS.put(ak, JSON.stringify({ owner: id, kind: x.kind, target: x.kind === 'person' ? id : clip(x.target, 200), text: clip(x.text, 1500), ts: now(), status: 'open', reason: h.reason }))
      await notice(env, id, { title: 'Appeal received', text: 'A moderator will review it. We\'ll tell you here when it\'s decided.', kind: x.kind, target: x.target, action: 'appeal' })
      await emailModerators(env, '[Meshron appeal] ' + x.kind, `<div style="font-family:system-ui,sans-serif"><h2>New appeal</h2><p><b>${esc(x.kind)}</b> ${esc(x.target)}</p><p><b>Removed for:</b> ${esc(h.reason)}</p><p><b>Owner says:</b> ${esc(x.text)}</p></div>`)
      return { ok: true }
    },

    // ---- Human moderators (the moderator panel and in-app tools) ----

    /** Open reports and appeals. */
    async '/v1/mod/queue' ({ username, authKey }, env) {
      const { role: myRole } = await isMod(env, username, authKey)
      const reports = []
      for (const k of (await list(env, 'rq:', 200)).reverse()) {
        const id = await env.ACCOUNTS.get(k)
        const r = id && await env.ACCOUNTS.get('r:' + id)
        if (r) reports.push(JSON.parse(r))
      }
      const appeals = []
      for (const k of await list(env, 'a:', 200)) { const a = JSON.parse(await env.ACCOUNTS.get(k) || 'null'); if (a && a.status === 'open') appeals.push({ key: k, ...a }) }
      const hidden = []
      for (const k of (await list(env, 'h:', 300))) hidden.push(JSON.parse(await env.ACCOUNTS.get(k) || '{}'))
      const verifications = []
      for (const k of await list(env, 'vr:', 200)) { const v = JSON.parse(await env.ACCOUNTS.get(k) || 'null'); if (v && v.status === 'open') verifications.push(v) }
      // The CEOs also see who wants to moderate, and the team.
      const out = { reports, appeals, hidden, verifications, role: myRole, aiAuto: (await env.ACCOUNTS.get('cfg:aiauto')) !== 'off' }
      // The whole team (everyone sees it): CEOs and admins, then moderators.
      const admins = String(env.ADMINS || '').split(/[,;\s]+/).map((x) => x.trim().toLowerCase()).filter(Boolean)
      out.team = []
      for (const n of admins) if (await env.ACCOUNTS.get('u:' + n)) out.team.push({ username: n, role: 'admin', title: n === FOUNDER ? 'founder' : n === COFOUNDER ? 'cofounder' : 'admin' })
      for (const k of await list(env, 'role:', 200)) { const n = k.slice(5); if (!admins.includes(n)) out.team.push({ username: n, role: await env.ACCOUNTS.get(k), title: null }) }
      if (myRole === 'admin') {
        out.applications = []
        for (const k of await list(env, 'ma:', 200)) { const v = JSON.parse(await env.ACCOUNTS.get(k) || 'null'); if (v && v.status === 'open') out.applications.push(v) }
      }
      return out
    },

    /** A moderator's decision. */
    async _act ({ username, authKey, action, report, kind, target, owner, reason, text, who, role }, env) {
      const { u, role: myRole } = await isMod(env, username, authKey)
      const by = '@' + u
      const why = clip(reason, 200) || 'This breaks Meshron\'s rules.'
      const closeReport = async (status) => {
        if (!report) return
        const raw = await env.ACCOUNTS.get('r:' + report)
        if (!raw) return
        const r = JSON.parse(raw); r.status = status; r.reviewedBy = by
        await env.ACCOUNTS.put('r:' + report, JSON.stringify(r))
        for (const k of await list(env, 'rq:', 1000)) if (k.endsWith(':' + report)) await env.ACCOUNTS.delete(k)
      }
      switch (action) {
        case 'confirm': // the AI (or report) was right: keep it hidden / apply now
          if (kind && target) await hide(env, kind, target, owner || '', why, 'other', by, report || '')
          await closeReport('confirmed')
          return { ok: true }
        case 'restore': { // undo a removal: everything comes back as it was
          const k = 'h:' + kind + ':' + target
          const h = JSON.parse(await env.ACCOUNTS.get(k) || 'null')
          await env.ACCOUNTS.delete(k)
          const ak = 'a:' + kind + ':' + target
          const a = JSON.parse(await env.ACCOUNTS.get(ak) || 'null')
          if (a) { a.status = 'won'; await env.ACCOUNTS.put(ak, JSON.stringify(a)) }
          if (h && h.owner) await notice(env, h.owner, { title: 'Your ' + kind + ' is back', text: a ? 'You won your appeal. It\'s restored with its likes and comments.' : 'A moderator restored it.', kind, target, action: 'restore' })
          await closeReport('restored')
          return { ok: true }
        }
        case 'deny-appeal': {
          const ak = 'a:' + kind + ':' + target
          const a = JSON.parse(await env.ACCOUNTS.get(ak) || 'null')
          if (!a) throw fail('No appeal')
          a.status = 'denied'; await env.ACCOUNTS.put(ak, JSON.stringify(a))
          await notice(env, a.owner, { title: 'Appeal decided', text: 'A moderator reviewed your appeal and the decision stays: ' + why, kind, target, action: 'appeal-denied' })
          return { ok: true }
        }
        case 'hide':
          if (!HIDEABLE.includes(kind) || !target) throw fail('Can\'t hide that')
          await hide(env, kind, target, owner || '', why, 'other', by, report || '')
          await closeReport('actioned')
          return { ok: true }
        case 'warn':
          if (!HEX64.test(String(who || ''))) throw fail('Bad account')
          await strike(env, who, why)
          await notice(env, who, { title: 'Warning from Meshron', text: why, kind: 'person', target: who, action: 'warn' })
          await closeReport('actioned')
          return { ok: true }
        case 'ban':
          if (!HEX64.test(String(who || ''))) throw fail('Bad account')
          await ban(env, who, why, by)
          await closeReport('actioned')
          return { ok: true }
        case 'unban': {
          if (!HEX64.test(String(who || ''))) throw fail('Bad account')
          await env.ACCOUNTS.delete('b:' + who)
          await env.ACCOUNTS.put('s:' + who, JSON.stringify({ n: 0, last: now() }))
          const ak = 'a:person:' + who
          const a = JSON.parse(await env.ACCOUNTS.get(ak) || 'null')
          if (a) { a.status = 'won'; await env.ACCOUNTS.put(ak, JSON.stringify(a)) }
          await notice(env, who, { title: 'Your account is back', text: 'A moderator lifted the suspension.', kind: 'person', target: who, action: 'unban' })
          return { ok: true }
        }
        case 'deny-verify': {
          const vr = JSON.parse(await env.ACCOUNTS.get('vr:' + who) || 'null')
          if (!vr) throw fail('No request')
          vr.status = 'denied'; await env.ACCOUNTS.put('vr:' + who, JSON.stringify(vr))
          await notice(env, who, { title: 'Verification', text: 'Not this time: ' + why + ' You can ask again later.', kind: 'person', target: who, action: 'verify-denied' })
          return { ok: true }
        }
        case 'dismiss':
          await closeReport('dismissed')
          return { ok: true }
        case 'notify': // a message from the moderators (report updates, new features…)
          if (!HEX64.test(String(who || ''))) throw fail('Bad account')
          await notice(env, who, { title: clip(reason, 80) || 'From Meshron', text: clip(text, 1000), kind: 'notice', target: '', action: 'notice' })
          return { ok: true }
        case 'verify': case 'unverify': {
          if (!HEX64.test(String(who || ''))) throw fail('Bad account')
          if (action === 'verify') await env.ACCOUNTS.put('v:' + who, JSON.stringify({ by, ts: now() }))
          else await env.ACCOUNTS.delete('v:' + who)
          const vr = JSON.parse(await env.ACCOUNTS.get('vr:' + who) || 'null')
          if (vr) { vr.status = action === 'verify' ? 'approved' : 'closed'; await env.ACCOUNTS.put('vr:' + who, JSON.stringify(vr)) }
          await notice(env, who, { title: action === 'verify' ? 'You\'re verified ✓' : 'Verification removed', text: action === 'verify' ? 'Your profile now shows the verified check.' : why, kind: 'person', target: who, action })
          return { ok: true }
        }
        case 'role': { // only the CEOs appoint or remove moderators
          if (myRole !== 'admin') throw fail('Only the CEOs can add or remove moderators', 403)
          const name = String(who || '').toLowerCase().replace(/^@/, '')
          const a = await account(env, name)
          if (!a) throw fail('No such account')
          if (role === 'mod') await env.ACCOUNTS.put('role:' + name, 'mod')
          else await env.ACCOUNTS.delete('role:' + name)
          if (a.pk) await notice(env, a.pk, role === 'mod'
            ? { title: 'You\'re a Meshron moderator', text: 'Welcome to the team. Open Settings → Moderator to start.', kind: 'person', target: a.pk, action: 'mod-added' }
            : { title: 'Moderator role removed', text: why, kind: 'person', target: a.pk, action: 'mod-removed' })
          return { ok: true }
        }
        case 'ai-auto': { // AI moderation on/off (CEOs)
          if (myRole !== 'admin') throw fail('Only the CEOs can change that', 403)
          await env.ACCOUNTS.put('cfg:aiauto', reason === 'off' ? 'off' : 'on')
          return { ok: true }
        }
        case 'approve-mod': case 'deny-mod': { // applications to moderate (CEOs only)
          if (myRole !== 'admin') throw fail('Only the CEOs can add or remove moderators', 403)
          const ap = JSON.parse(await env.ACCOUNTS.get('ma:' + who) || 'null')
          if (!ap) throw fail('No application')
          ap.status = action === 'approve-mod' ? 'approved' : 'denied'; ap.by = by
          await env.ACCOUNTS.put('ma:' + who, JSON.stringify(ap))
          if (action === 'approve-mod') await env.ACCOUNTS.put('role:' + ap.username, 'mod')
          await notice(env, who, action === 'approve-mod'
            ? { title: 'You\'re a Meshron moderator', text: 'Your application was approved. Open Settings → Moderator to start.', kind: 'person', target: who, action: 'mod-added' }
            : { title: 'Moderator application', text: 'Not this time: ' + why, kind: 'person', target: who, action: 'mod-denied' })
          return { ok: true }
        }
        default:
          throw fail('Unknown action')
      }
    },

    /** Badges for people (public): verified ✓, moderator, and the founders' own badges. */
    async '/v1/badges' ({ people }, env) {
      const out = {}
      const admins = String(env.ADMINS || '').split(/[,;\s]+/).map((x) => x.trim().toLowerCase()).filter(Boolean)
      for (const p of (Array.isArray(people) ? people : []).slice(0, 200)) {
        if (!HEX64.test(String(p))) continue
        if (p === AI_ID) { out[p] = { verified: true, mod: true, title: 'ai' }; continue }
        const u = await env.ACCOUNTS.get('k:' + p)
        const title = u === FOUNDER ? 'founder' : u === COFOUNDER ? 'cofounder' : null
        const mod = !!u && (admins.includes(u) || !!(await env.ACCOUNTS.get('role:' + u)))
        const verified = !!title || mod || !!(await env.ACCOUNTS.get('v:' + p))
        if (verified || mod || title) out[p] = { verified, mod, title }
      }
      return { badges: out }
    },

    /** Ask for the verified check. A person at Meshron decides. */
    async '/v1/verify/request' (body, env) {
      const { id, payload: x } = await signed('/v1/verify/request', body)
      if (!(await limit(env, 'verify:' + id, 3, 86400))) throw fail("You already asked today. We'll get back to you.", 429)
      if (await env.ACCOUNTS.get('v:' + id)) throw fail("You're already verified")
      const req = { id, ts: now(), name: clip(x.name, 80), username: clip(x.username, 30), category: clip(x.category, 40), reason: clip(x.reason, 1000), links: clip(x.links, 500), status: 'open' }
      await env.ACCOUNTS.put('vr:' + id, JSON.stringify(req))
      await notice(env, id, { title: 'Verification request received', text: "A person at Meshron will review it. We'll tell you here.", kind: 'person', target: id, action: 'verify-request' })
      await emailModerators(env, '[Meshron verification] ' + req.name, `<div style="font-family:system-ui,sans-serif"><h2>Verification request</h2><p><b>${esc(req.name)}</b> @${esc(req.username)} (${esc(id)})</p><p><b>Category:</b> ${esc(req.category)}</p><p><b>Why:</b> ${esc(req.reason)}</p><p><b>Links:</b> ${esc(req.links)}</p></div>`)
      return { ok: true }
    },

    /** Apply to become a moderator (needs a Meshron account). The CEOs decide. */
    async '/v1/mod/apply' (body, env) {
      const { id, payload: x } = await signed('/v1/mod/apply', body)
      if (!(await limit(env, 'modapply:' + id, 2, 86400))) throw fail('You already applied today.', 429)
      const username = await env.ACCOUNTS.get('k:' + id)
      if (!username) throw fail('Create a Meshron account first (Settings → Account)')
      const admins = String(env.ADMINS || '').split(/[,;\s]+/).map((s) => s.trim().toLowerCase()).filter(Boolean)
      if (admins.includes(username) || await env.ACCOUNTS.get('role:' + username)) throw fail("You're already a moderator")
      const prev = JSON.parse(await env.ACCOUNTS.get('ma:' + id) || 'null')
      if (prev && prev.status === 'open') throw fail('Your application is being reviewed')
      const ap = { id, username, ts: now(), name: clip(x.name, 80), why: clip(x.why, 1500), hours: clip(x.hours, 60), languages: clip(x.languages, 120), status: 'open' }
      await env.ACCOUNTS.put('ma:' + id, JSON.stringify(ap))
      await notice(env, id, { title: 'Application received', text: "Meshron's CEOs review every application. We'll tell you here.", kind: 'person', target: id, action: 'mod-apply' })
      await emailModerators(env, '[Meshron moderator application] @' + username, `<div style="font-family:system-ui,sans-serif"><h2>Moderator application</h2><p><b>${esc(ap.name)}</b> @${esc(username)}</p><p><b>Why:</b> ${esc(ap.why)}</p><p><b>Hours/week:</b> ${esc(ap.hours)} · <b>Languages:</b> ${esc(ap.languages)}</p></div>`)
      return { ok: true }
    },

    /** Find people by the start of their @username (moderators). */
    async '/v1/mod/search' ({ username, authKey, q }, env) {
      await isMod(env, username, authKey)
      const p = String(q || '').trim().replace(/^@/, '').toLowerCase().replace(/[^a-z0-9_]/g, '').slice(0, 24)
      if (!p) return { people: [] }
      const admins = String(env.ADMINS || '').split(/[,;\s]+/).map((x) => x.trim().toLowerCase()).filter(Boolean)
      const out = []
      const res = await env.ACCOUNTS.list({ prefix: 'u:' + p, limit: 20 })
      for (const k of res.keys) {
        const n = k.name.slice(2)
        const role = await env.ACCOUNTS.get('role:' + n)
        out.push({ username: n, title: n === FOUNDER ? 'founder' : n === COFOUNDER ? 'cofounder' : admins.includes(n) ? 'admin' : null, role })
      }
      if ('meshronai'.startsWith(p)) out.unshift({ username: 'meshronai', title: 'ai', role: null })
      return { people: out }
    },

    /** A person, for moderators: by @username or Meshron ID. */
    async '/v1/mod/person' ({ username, authKey, who }, env) {
      await isMod(env, username, authKey)
      let id = String(who || '').trim().replace(/^@/, '').toLowerCase()
      if (id === 'meshronai') id = AI_ID
      let name = ''
      if (!HEX64.test(id)) { const a = await account(env, id); if (!a) { console.log('person: no account', JSON.stringify(id)); throw fail('No account with that username') } name = id; id = a.pk }
      else name = (await env.ACCOUNTS.get('k:' + id)) || ''
      const ban = JSON.parse(await env.ACCOUNTS.get('b:' + id) || 'null')
      const strikes = JSON.parse(await env.ACCOUNTS.get('s:' + id) || 'null')
      const hidden = []
      for (const k of await list(env, 'h:', 500)) { const h = JSON.parse(await env.ACCOUNTS.get(k) || 'null'); if (h && h.owner === id) hidden.push(h) }
      const admins = String(env.ADMINS || '').split(/[,;\s]+/).map((x) => x.trim().toLowerCase()).filter(Boolean)
      // Who they are on Meshron: CEO, co-CEO, admin, Meshron AI, moderator, verified.
      const title = id === AI_ID ? 'ai' : name === FOUNDER ? 'founder' : name === COFOUNDER ? 'cofounder' : admins.includes(name) ? 'admin' : null
      const role = name ? await env.ACCOUNTS.get('role:' + name) : null
      // Their record: reports about them, what moderators did to them (kept even after a restore), appeals.
      const reports = []
      for (const k of (await list(env, 'r:', 1000))) {
        const r = JSON.parse(await env.ACCOUNTS.get(k) || 'null')
        if (r && r.owner === id) reports.push({ id: r.id, ts: r.ts, kind: r.kind, reason: r.reason, details: r.details, status: r.status, ai: r.ai ? { action: r.ai.action, category: r.ai.category, severity: r.ai.severity } : null })
      }
      reports.sort((x, y) => y.ts - x.ts)
      const history = []
      for (const k of (await list(env, 'ml:', 1000)).reverse()) {
        const e = JSON.parse(await env.ACCOUNTS.get(k) || 'null')
        if (e && (e.who === id || e.owner === id)) history.push({ ts: e.ts, by: e.by, action: e.action, kind: e.kind, reason: e.reason, content: e.content, flagged: !!(e.check && e.check.fair === false) })
        if (history.length >= 100) break
      }
      const appeals = []
      for (const k of await list(env, 'a:', 500)) { const ap = JSON.parse(await env.ACCOUNTS.get(k) || 'null'); if (ap && ap.owner === id) appeals.push({ ts: ap.ts, kind: ap.kind, text: ap.text, status: ap.status }) }
      const acct = name ? await account(env, name).catch(() => null) : null
      return { id, username: id === AI_ID ? 'meshronai' : name, banned: ban, strikes: strikes ? strikes.n : 0, verified: !!title || !!role || !!(await env.ACCOUNTS.get('v:' + id)), role: title === 'admin' || title === 'founder' || title === 'cofounder' ? 'admin' : role, title, hidden, reports, history, appeals, joined: acct ? acct.created : null, lastStrike: strikes ? strikes.last : null }
    },

    /** Am I a moderator? (for the in-app tools and badge) */
    async '/v1/mod/me' ({ username, authKey }, env) {
      try { const { role } = await isMod(env, username, authKey); return { role } } catch (e) { if (e.status === 403) return { role: null }; throw e }
    },

    /** Everything moderators did, newest first (all moderators see it). flagged: only the AI's concerns. */
    async '/v1/mod/log' ({ username, authKey, flagged }, env) {
      await isMod(env, username, authKey)
      const out = []
      for (const k of (await list(env, 'ml:', 1000)).reverse()) {
        const e = JSON.parse(await env.ACCOUNTS.get(k) || 'null')
        if (!e || (flagged && !(e.check && e.check.fair === false && !e.cleared))) continue
        out.push(e)
        if (out.length >= 200) break
      }
      return { log: out }
    },

    async '/v1/mod/act' (body, env) {
      const { u, role: myRole } = await isMod(env, body.username, body.authKey)
      // CEOs mark a flag as looked at.
      if (body.action === 'clear-flag') {
        if (myRole !== 'admin') throw fail('Only the CEOs can do that', 403)
        for (const k of await list(env, 'ml:', 1000)) {
          if (!k.endsWith(':' + body.target)) continue
          const e = JSON.parse(await env.ACCOUNTS.get(k) || 'null'); if (!e) continue
          e.cleared = '@' + u; await env.ACCOUNTS.put(k, JSON.stringify(e), { expirationTtl: LOG_TTL })
        }
        return { ok: true }
      }
      const res = await routes._act(body, env)
      const e = {
        id: rid(), ts: now(), by: '@' + u, role: myRole, action: String(body.action || ''), kind: clip(body.kind, 20), target: clip(body.target, 200),
        owner: clip(body.owner, 64), who: clip(body.who, 64), reason: clip(body.reason, 200), content: clip(body.content, 1000), report: clip(body.report, 40), check: null
      }
      if (PUNISH.includes(e.action)) {
        e.check = await oversee(env, e)
        if (e.check && !e.check.fair) await alertCeos(env, e).catch(() => {})
      }
      await logAction(env, e)
      return { ...res, flagged: !!(e.check && !e.check.fair) }
    }
  }
  return routes
}
