# Meshron test run — 16 Nov 2026

## Automated

| Area | Test | Result |
|---|---|---|
| Android | `./gradlew :app:testDebugUnitTest` (6 tests: account crypto, …) | pass |
| Android | `:app:compileDebugKotlin`, install on emulator | pass |
| Accounts server | `server/accounts/test/flow.js` (sign-up, codes, 2-step, AI, reserved names, rename) | pass |
| Moderation server | `server/accounts/test/moderation.js` (signed reports, AI hides, email, notices, appeal → restore, ban/unban, verify, badges, verification requests, `/panel`) | pass |
| Reels P2P | `tools/test-reposts.js` (repost, Following, counts, collab invite/accept, uninvited claim ignored, threaded reply) | pass |
| Live | `tools/test-live.js` (2 viewers, video+audio fan-out, chat, hearts, leave, end) | pass |
| Groups | `tools/test-group-roles.js` (admin, rename, admin can't remove owner, settings hidden from chat, leave) | pass |
| Comment rules | `tools/test-comment-rules.js` (friends-only: friend in, stranger refused) | **failed, fixed, pass** |
| Chat requests | `tools/test-requests.js` (stranger already linked through Reels adds you: nothing while off, a request once on) | **failed, fixed, pass** |

## Bugs found and fixed in this run

- **Comment rule never reached viewers**: the Reels worklet dropped the `cm` field when reading a profile, so the author's "who can comment" choice was enforced but never shown to others. Fixed in `app/src/main/js/reels.js`; re-tested.
- (Earlier today) group settings entries were turned into plain text by the worklet (`GROUP_KINDS`); fixed and covered by `test-group-roles.js`.

- **A stranger you were already linked to through Reels could never send you a chat request**: their hello arrived on the quiet Reels link and was ignored. Now a hello on that link turns into a request (`app/src/main/js/app.js`).

## On the emulator (test copy)

| Check | Result |
|---|---|
| Report a reel (More → Report → Spam) → server receives it → Activity shows "Thanks for your report" | pass |
| Settings → Who can comment → Everyone → Followers; another phone sees "followers" | pass (set back to Everyone) |
| Badges: verified mark on @winnie_pc; Settings → Meshron badges shows all four | pass |
| Agreement screen on first launch | pass |
| Comments panel: box above keyboard, my comments first | pass |
| Repost a comment (quote reaches other phones) | pass |
| Collab: invite in chat shows a card, Accept → the author confirms you as collaborator | pass |
| Download my data: file saved, has profile/friends/groups/messages, no keys | pass |
| Chat requests off: a stranger connects but no request appears | pass |
| Edit profile no longer lists videos; More on your own video has Private (with a confirm) and Delete | built (not tapped) |
| Activity: pinned top, hints only on their tab, unseen count | pass |

## Profile, people, live, moderators (2026-11-16)

Automated: `server/accounts/test/moderation.js` now covers moderator applications (needs an account; CEOs approve; a moderator can't change roles or see applications). `tools/test-comment-rules.js` now treats friends as people who follow each other. All JS tests pass (reposts, live, comment rules, requests, group roles, moderation).

| Check (test copy) | Result |
|---|---|
| Me shows my verified badge, bio link, Following / Followers / Likes, and a separate Private (lock) tab | pass |
| Edit profile: Change photo, Name, Username + meshron link, Bio, Links rows; a link saves and shows on Me | pass |
| My card (QR): saves to Pictures/Meshron | pass |
| Connections page: Following / Followers / Friends tabs, unfollow-non-followers button | pass |
| Feed tag reads "Following" for someone who doesn't follow back | pass |
| Live hub (For you / Following / Games & screens) and Camera / Game-screen choice | pass |
| Screen live: reaches Android's "Share your screen" consent | reached; consent left to the user, so streaming a game is not verified on device |
| Repost with a thought, typing / recording / Seen, collab Accept remembered, @collab names clickable, moderator app, Log out | built, compiled, not tapped |

## Not covered yet

- In-app moderator buttons (needs a moderator account), Android live viewer on a second phone, founder badges on real accounts (needs the deployed server and reserved sign-ups), AI against the real router.
- Windows/iPhone screens (paused).
