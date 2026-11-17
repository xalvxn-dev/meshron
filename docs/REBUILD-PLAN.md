# Meshron rebuild — feature checklist (Nov 2026)

Status: [x] done and tested on the emulator, [~] built but not fully tested, [ ] to do.

## Done
- [x] Aurora design (light/dark/stealth), text tab rail + shutter, round avatars, Moments
- [x] Anonymous mode (separate identity, PIN, hide IP, disappearing) — Android, web
- [x] Profile photo + bio; Me page; Edit profile (videos: private/public/delete); Settings separate
- [x] Accounts server (Cloudflare Worker): username+password, email codes, 2-step; tests pass locally
- [x] Streaks, per-chat disappearing timers, global search (people/chats/messages/videos), add by @username/link/QR (in Search)
- [x] Reels P2P: feed, likes, comments, follows, saves, sounds, send to friend, private videos
- [x] Camera (clips, flip, flash, timer, sound sync) + editor (trim, speed, looks, adjust, text, crop/rotate, music, cover) + song library (reel sounds, Jamendo, phone)
- [~] Meshron AI via server: helper chat, smart replies, translate, captions, moderation of captions/comments (tested with a mock router only)
- [~] Feed filters + editable algorithm (weights, muted words, hidden creators, not interested)

- [x] Discover: More sheet (friends + Send, share to apps, actions), counts not labels, follow check + Friend tag, own videos open in a player (private/delete in More), search = global search
- [x] Adaptive sizing (scales with screen width)
- [~] @username change in Edit profile (server rename + reserved founder names tested; app flow not tapped through)
- [~] Comment replies + stickers in comments (built, not tested on device)
- [x] Reposts (More → Repost/Undo, Reposts tab, Following shows reposts, repost count), video → moment in a full-screen editor (tap to type, drag, pinch, colours, filled box, @tags; link shown as a Watch reel button), "with @collab" line, TikTok-style subtle Friend/Following tag, Instagram-style "You reposted" bubble, Me tabs (Videos, Reposts, Saved, Liked), share row uses the real icons of installed apps
- [~] Collab invites (chat card + Accept; worklet tested, Accept not tapped on device); comment Repost (built, not tapped)
- [~] Groups (Android): group page with photo, bio, members + Owner/Admin roles, make admin, make owner, remove (admins cannot remove admins/owner), add members, leave (owner must hand over first), admins delete anyone's message, search messages, report group/person (+screenshot, saved for moderation). Roles are signed gmeta log entries checked by every member. Tested on one device; removed members still hold the old group key (rekey on removal = follow-up); Windows/iPhone UI not updated yet
- [~] Live: Go live (title, camera+mic, flip, mute, end), LIVE button in Discover lists who is live (followed first), watch with chat, hearts, viewer count; host fans out frames over a P2P link (live.js, up to 60 viewers). tools/test-live.js passes; emulator host -> dev-server viewer verified (video, audio, chat, hearts); Android viewer decoding not yet seen on a second phone
- [~] Moderation: signed reports (+screenshot) → AI moderator (hide/warn/ban, or asks a human) → email to xalvxn@gmail.com; hidden-not-deleted list filtered by every app (owner sees "Removed"); strikes → suspension; notices + appeals in Activity → From Meshron; moderator API (queue, confirm, restore, warn, ban/unban, verify, notify, appoint mods; admins = founders). server test/moderation.js passes (wrangler dev --env test); app report→notice not yet seen on device
- [x] Activity: header, filters and nearby switch pinned; pull to refresh
- [x] Comments: TikTok-style panel (box rides on the keyboard, tap list hides it, tap above closes)
- [~] Badges: verified ✓ (blue), moderator shield (purple), founders' star marks with titles on profiles (gold "CEO · Founder" for @alvinnanakojopieterson, rose "Co-CEO · Co-founder" for @clementkofiyeboah; both also verified + moderator). Shown on reels, comments, profiles, Me, chat header. Verified badge seen on device; founder marks not yet (needs the reserved accounts)
- [x] Verification: request form in Settings → moderators approve/decline (panel); notices in Activity
- [x] Moderator panel (web, GET /panel on the account server): sign in with Meshron username/password (Argon2id in the browser); reports with AI verdicts, appeals, removals, verification, message/ban/verify by ID, appoint moderators (admins). Tested locally
- [~] In-app moderator tools: Settings → Moderator panel; More → Mod: remove / Mod: ban (shown only to moderators; not tapped on device)
- [x] Comments: my comments listed first
- [~] Privacy & legal: Terms of Service, Privacy Policy, Community Guidelines (in app + Settings → Your data & legal); one-time age (13+) and agreement screen (LEGAL_VERSION); chat requests on/off; who can comment (everyone/followers/friends/no one, enforced by the author's device); blocked accounts list; Download my data (JSON). Texts are drafts: need a lawyer's review; contact address = LEGAL_CONTACT
- [x] Verified mark: small Meshron hexagon (teal→violet) with a check
- [x] Fix: group settings entries (gmeta) were dropped to plain text by the worklet (GROUP_KINDS); now carried as gmeta on every platform
- [x] Windows/iPhone host: group roles (owner/admins, info, add/remove, leave, hand-over, delete for everyone) — tools/test-group-roles.js passes
- [x] Activity: empty hints only on their own tab; From Meshron count = unseen only
- [ ] Deploy: see server/accounts/DEPLOY.md (needs your Cloudflare login + secrets)
- [ ] Windows/iPhone UI still to port: group page, moderation (reports, hidden content, notices, appeals), badges, legal gate, privacy settings, reposts/collab/to-moment, live, comments panel, Me tabs, accounts + AI
- [x] Badge family drawn in-app: verified hexagon, moderator shield (violet→magenta with a mesh node), founder gold crown hexagon, co-founder rose-gold crown hexagon; title pills on profiles; Settings → Meshron badges explains them
- [~] Web (Windows/iPhone) port PAUSED at the user's request (partly done, not browser-tested): host signing for moderation, chat-request switch, comment rule, legal gate, group page, Meshron notices/verification in Me, reels More/Report/badges/hidden filter (cross/ui/moderation.js, legal-text.js via tools/gen-legal.js)
- [x] Chat: stickers, voice notes (hold mic), reel links shown as cards that open the reel

## Next (in order)
3. Voice notes in comments; GIPHY stickers if a key is given
10. Activity: pull to refresh suggestions; filters
11. AI live: deploy Worker, set AI_URL/AI_KEY (user pastes key)
12. Port everything to iPhone + Windows; builds; save to Downloads
