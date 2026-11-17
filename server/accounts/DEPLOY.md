# Deploying the Meshron account server (Cloudflare Workers)

Run these in `server/accounts`. Steps 1–3 need you (they sign in to your accounts or paste secrets).

1. Sign in to Cloudflare (opens the browser):

       npx wrangler login

2. Create the database and put its id into `wrangler.toml` (`[[kv_namespaces]]` → `id`):

       npx wrangler kv namespace create ACCOUNTS

3. Add the secrets (each command asks you to paste the value; nothing is stored in the code):

       npx wrangler secret put RESEND_KEY      # from resend.com → API Keys (sends codes and moderator emails)
       npx wrangler secret put AI_KEY          # your AI router key
       npx wrangler secret put RESERVED_CODE   # any long random text; needed to create the founders' reserved usernames

4. Check `wrangler.toml` `[vars]`:
   - `FROM`: a sender on a domain you verified in Resend (until then `onboarding@resend.dev` only emails your own address).
   - `AI_URL`: the router's OpenAI-compatible base URL (ends in `/v1`).
   - `MOD_EMAIL`: where reports and appeals go (xalvxn@gmail.com).
   - `ADMINS`: the founders' usernames (they can appoint moderators in the panel).

5. Deploy:

       npx wrangler deploy

   It prints the address, e.g. `https://meshron-accounts.meshron.workers.dev`.

6. Point the app at it: in the Android project's `local.properties` add

       meshron.accountsUrl=https://meshron-accounts.meshron.workers.dev

   (or build with `-PaccountsUrl=...`), and rebuild.

7. Create the founders' accounts in the app with the usernames `alvinnanakojopieterson` and `clementkofiyeboah`, entering the RESERVED_CODE when asked.

8. The moderator panel is at `https://meshron-accounts.meshron.workers.dev/panel`.

Local testing (no Cloudflare account needed): `node test/mock-ai.js` and `npx wrangler dev --local --port 8799 --env test`, then `node test/flow.js` and `node test/moderation.js`.
