# AMORA age verifier (zero-knowledge, zkPassport)

Checks real zero-knowledge proofs of "holder of a valid passport/ID chip is 18 or older" and tells the AMORA bot to unlock the Adult Zone. It does not write its own cryptography: proofs are produced by the [zkPassport](https://docs.zkpassport.id) app and verified here with `@zkpassport/sdk` in `verifierMode: "local"` (no third-party verifier API).

Two parts:

| Part | What | Where it runs |
|---|---|---|
| `site/` + `src/client.js` | Static portal (home, `verify.html`, about, privacy) | Netlify, configured by the repo-root `netlify.toml` |
| `src/` (rest) | JSON API: Discord login check + proof verification | Render (this folder's `Dockerfile`) |

The API host is only ever called with `fetch`, never opened as a page. Browsers (Google Safe Browsing) judge the page people actually open, and that is the static site.

## Flow

1. `/verify` (or the 18+ panel button) in Discord: the bot stores `sha256(token)` for a 15-minute single-use session owned by that Discord user; the ephemeral embed links to `VERIFIER_PUBLIC_URL/verify.html#s=<token>`. The token is in the URL fragment, so it is never sent to a server. Starting again cancels the user's earlier unused links.
2. The portal asks the visitor to **log in with Discord** (OAuth2, `identify` only). Discord sends the browser back to `verify.html`; the page posts the code to `POST /api/login`, which asks the bot whether that Discord account owns the session. A different account, an expired link or a banned account is refused. On success the API returns a signed 15-minute bearer; the page keeps it in `sessionStorage` (no cookies).
3. The portal shows the zkPassport button. The query (`age >= 18`) is fixed; the session token is committed into the proof (`bind.custom_data`), so a proof made for one session fails for any other.
4. `POST /api/verify` (bearer auth) receives `proofs` + `queryResult`. The API re-checks the session, **rebuilds the query itself** (never trusts the browser's), verifies locally, then calls the bot with `HMAC(ID_HASH_PEPPER, uniqueIdentifier)` and the logged-in Discord ID. The session token and Discord ID come from the bearer, never from the request body.
5. The bot burns the session (only if it belongs to that Discord ID), stores the fingerprint (unique: one ID verifies one Discord account), sets the user verified, swaps the minor/adult roles and DMs a confirmation.

Bot <-> verifier calls are signed: `X-Amora-Signature = hex(HMAC-SHA256(VERIFIER_SHARED_SECRET, "{X-Amora-Timestamp}.{body}"))`, 60 s window.

## Configuration

Verifier API (`verifier/`, on Render):

| Variable | Meaning |
|---|---|
| `SITE_URL` | Public URL of the static portal without a trailing slash, e.g. `https://amora-verify.netlify.app`. Its hostname is the zkPassport proof domain, its origin is the only CORS origin allowed, and `SITE_URL/verify.html` is the Discord OAuth redirect. |
| `BOT_INTERNAL_URL` | Bot base URL, e.g. `https://amora-bot.onrender.com` |
| `VERIFIER_SHARED_SECRET` | >= 32 chars, identical on the bot |
| `ID_HASH_PEPPER` | >= 32 chars, **verifier only**. Changing it makes previously stored fingerprints unmatchable. |
| `ZK_SCOPE` | Default `amora-discord-18plus`. The unique identifier is scoped to it. |
| `ZK_DEV_MODE` | `true` accepts zkPassport mock passports (all share unique id `1`). **Must be unset/false in production**: the verifier prints a warning at startup when it is on, and refuses it if `NODE_ENV=production` is set. |
| `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET` | OAuth2 credentials used for the "Log in with Discord" step (see below). Keep the secret private. |
| `PORT`, `MAX_CONCURRENT_VERIFICATIONS`, `MAX_BODY_BYTES`, `RATE_LIMIT_PER_MINUTE` | Optional tuning |

Bot: `VERIFIER_PUBLIC_URL` (same value as `SITE_URL`), `VERIFIER_SHARED_SECRET`, optional `ALLOW_SELF_ATTESTATION=false`, and `ZK_ALLOW_DEV_PROOFS=true` **only on test servers** (the bot refuses mock-passport proofs without it, even if the verifier is left in dev mode). See `CHECKPOINTS.md`.

### Deploying the portal to Netlify
1. Netlify -> **Add new site -> Import an existing project -> GitHub** -> pick this repo. Build settings come from `netlify.toml` (base `verifier`, publish `dist/site`); it also sends the CSP / `X-Frame-Options` headers.
2. Site configuration -> **Environment variables**: add `API_BASE_URL` = the Render API origin, e.g. `https://amora-verifier.onrender.com` (no trailing slash). Redeploy after changing it.
3. Site configuration -> **Change site name** to a neutral subdomain. The resulting address is your `SITE_URL`.

### Discord login setup (required)
1. Discord Developer Portal -> your application (any application works; it does not need to be the bot) -> **OAuth2**.
2. Copy the **Client ID** and **Client Secret** into `DISCORD_CLIENT_ID` / `DISCORD_CLIENT_SECRET` on the verifier.
3. Under **Redirects** add exactly `<SITE_URL>/verify.html` (the verifier logs the exact value at startup). Only the `identify` scope is requested.

### Ban list
Admins manage it with `/ageban add|remove|list`. A banned account can't start verification, use self-attestation or complete a proof. Banning also removes the 18+ role and bans the zkPassport ID fingerprint the account verified with, so that ID can't be reused on another account. `/ageban remove` lifts both.

## Run / test

```
npm ci
npm test                                   # unit + HTTP tests, includes the real SDK rejecting forged proofs
npm start                                  # the API (needs the env above)
API_BASE_URL=https://... npm run build     # static portal -> dist/site
```

`verifier/Dockerfile` builds the production API image (not exercised in this repo's checks).

## What this proves, and what it does not
- Local-mode verification loads the proving-system code (barretenberg WASM) and, I expect, fetches certificate-registry data at runtime; neither was exercised with a real proof here, so measure memory and egress on the target host before choosing a plan (`node_modules` is ~450 MB).
- Proves: someone holding a passport/ID whose chip signature chains to a certificate in zkPassport's registry is >= 18, without revealing the document or birthdate to AMORA.
- Does **not** prove the person holding the ID is the one who owns the Discord account's real identity: a borrowed phone and ID passes. FaceMatch (a stricter zkPassport mode) is not enabled. The Discord login does stop someone else from completing **your** link.
- The default zkPassport unique identifier is not salted: anyone with the full chip data, including the issuing state, can recompute it. AMORA stores only a pepper-keyed HMAC of it, but the pepper protects against database leaks only, not against that party.
- Not everyone has a supported document (see zkPassport's coverage map), hence the fallback options.
- Trust is placed in zkPassport's circuits, registry and SDK. This service verifies; it does not audit them.
- Not tested here: a genuine proof from a real passport/app (needs a physical document or dev-mode phone), and the cross-origin browser flow against a real Netlify deployment. Unit tests cover the API; the build output was smoke-tested locally.
