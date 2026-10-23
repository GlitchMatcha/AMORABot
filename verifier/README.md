# AMORA age verifier (zero-knowledge, Self)

Checks real zero-knowledge proofs of "holder of a valid passport/ID chip is 18 or older" and tells the AMORA bot to unlock the Adult Zone. It does not write its own cryptography: proofs are produced by the [Self](https://self.xyz) app (formerly OpenPassport) and verified here with `@selfxyz/core` (`SelfBackendVerifier`, the Self Pass SDK), which checks the Groth16 proof against Self's verifier contract on Celo through a public RPC.

Two parts:

| Part | What | Where it runs |
|---|---|---|
| `site/` + `src/client.js` | Static portal (home, `verify.html`, about, privacy) | Netlify, configured by the repo-root `netlify.toml` |
| `src/` (rest) | JSON API: login check, QR parameters, proof verification | Render (this folder's `Dockerfile`) |
| `netlify/functions/discord-login.mjs` | The only code that talks to Discord (OAuth code exchange) | Netlify Functions, deployed with the portal |

The API host is only ever called with `fetch` (by the portal) or by Self's relayer, never opened as a page. Browsers (Google Safe Browsing) judge the page people actually open, and that is the static site.

## Flow

1. `/verify` (or the 18+ panel button) in Discord: the bot stores `sha256(token)` for a 15-minute single-use session owned by that Discord user; the ephemeral embed links to `VERIFIER_PUBLIC_URL/verify.html#s=<token>`. The token is in the URL fragment, so it is never sent to a server. Starting again cancels the user's earlier unused links.
2. The portal asks the visitor to **log in with Discord** (OAuth2, `identify` only). Discord sends the browser back to `verify.html`; the page posts the code to the **Netlify login function** (same origin), which exchanges it with Discord and returns a 2-minute assertion `{discordId, username}` signed with `VERIFIER_SHARED_SECRET`. The page then posts that assertion to `POST /api/login`, which verifies the signature (no Discord call) and asks the bot whether that Discord account owns the session. A different account, an expired link or a banned account is refused. On success the API returns a signed 15-minute bearer; the page keeps it in `sessionStorage` (no cookies). Discord is called from Netlify because Cloudflare, in front of Discord, blocked Render's shared outbound IP (HTTP 429 with a Cloudflare block page).
3. After the visitor confirms the short privacy summary, the page calls `GET /api/self-app` (bearer). The API returns `userDefinedData = "<token>.<discordUserId>"` and `userId = MAC(token.discordUserId)`, formatted as a UUID (HMAC-SHA256 keyed from `VERIFIER_SHARED_SECRET`, 122 bits). The page builds the Self app request (`minimumAge: 18`), shows it as a QR code and as an "open the Self app" link, then polls `GET /api/status`.
4. The Self app reads the passport/ID chip on the phone, builds the proof (which commits to `userId` and `userDefinedData`) and sends it to **Self's relayer**, which calls `POST /api/self/verify` on this API. That endpoint is public and replies HTTP 200 with `{status, result, reason}`, as Self requires.
5. `/api/self/verify` first checks that the committed `userId` is our MAC over the committed `userDefinedData`; only a visitor who logged in with Discord as the session owner can have received such a QR. It then asks the bot whether the session is live, owned by that Discord ID and not banned, **before** the expensive step, runs `SelfBackendVerifier` against its own configuration (scope, 18+, passport or biometric ID card only; never the sender's), and calls the bot with `HMAC(ID_HASH_PEPPER, nullifier)` and the Discord ID.
6. The bot burns the session (only if it belongs to that Discord ID), stores the fingerprint (unique: one ID verifies one Discord account), sets the user verified, swaps the minor/adult roles and DMs a confirmation. The portal's poll then shows "Verified". Rejections (for example "this ID was already used") are also shown there, because the Self app only displays a generic failure.

Bot <-> verifier calls are signed: `X-Amora-Signature = hex(HMAC-SHA256(VERIFIER_SHARED_SECRET, "{X-Amora-Timestamp}.{body}"))`, 60 s window.

The verifier keeps one piece of memory: the latest result per QR (15 minutes, at most 1000) for the status poll. After a restart the page simply keeps waiting; the bot remains the source of truth.

## Configuration

Verifier API (`verifier/`, on Render):

| Variable | Meaning |
|---|---|
| `SITE_URL` | Public URL of the static portal without a trailing slash, e.g. `https://amora-verify.netlify.app`. Its origin is the only CORS origin allowed, and `SITE_URL/verify.html` is the Discord OAuth redirect. |
| `API_PUBLIC_URL` | Public https origin of **this** API, e.g. `https://amora-verifier.onrender.com`. Self's relayer posts proofs to `API_PUBLIC_URL/api/self/verify`. Proofs are bound to this **host** and to `ZK_SCOPE`: changing either makes every stored ID fingerprint unmatchable, so one ID could verify a second account. |
| `BOT_INTERNAL_URL` | Bot base URL, e.g. `https://amora-bot.onrender.com` |
| `VERIFIER_SHARED_SECRET` | >= 32 chars, identical on the bot. Also keys the QR tickets and the login bearer. |
| `ID_HASH_PEPPER` | >= 32 chars, **verifier only**. Changing it makes previously stored fingerprints unmatchable. |
| `ZK_SCOPE` | Default `amora-discord-18plus`, 1-30 printable ASCII characters. The nullifier is scoped to it. |
| `ZK_DEV_MODE` | `true` accepts Self **mock passports** (Celo Sepolia) and rejects real ones. **Must be unset/false in production**: the verifier prints a warning at startup when it is on, and refuses it if `NODE_ENV=production` is set. |
| `DISCORD_CLIENT_ID` | Public OAuth2 client id, used only to build the Discord authorize URL. The client **secret** is not needed here; it lives on Netlify (see below). |
| `PORT`, `MAX_CONCURRENT_VERIFICATIONS`, `MAX_BODY_BYTES`, `RATE_LIMIT_PER_MINUTE`, `RELAYER_RATE_LIMIT_PER_MINUTE` | Optional tuning. The relayer limit (default 120/min) is one shared budget because every proof arrives from Self's addresses. |

Bot: `VERIFIER_PUBLIC_URL` (same value as `SITE_URL`), `VERIFIER_SHARED_SECRET`, optional `ALLOW_SELF_ATTESTATION=false`, and `ZK_ALLOW_DEV_PROOFS=true` **only on test servers** (the bot refuses mock-passport proofs without it, even if the verifier is left in dev mode). See `CHECKPOINTS.md`.

There is no Self dashboard, API key or registration: the scope is just a string you choose.

### Deploying the portal to Netlify
1. Netlify -> **Add new site -> Import an existing project -> GitHub** -> pick this repo. Build settings come from `netlify.toml` (base `verifier`, publish `dist/site`); it also sends the CSP / `X-Frame-Options` headers.
2. Site configuration -> **Environment variables**: add `API_BASE_URL` = the Render API origin, e.g. `https://amora-verifier.onrender.com` (no trailing slash). Redeploy after changing it.
3. Site configuration -> **Change site name** to a neutral subdomain. The resulting address is your `SITE_URL`.

### Discord login setup (required)
1. Discord Developer Portal -> your application (any application works; it does not need to be the bot) -> **OAuth2**.
2. Copy the **Client ID** into `DISCORD_CLIENT_ID` on the verifier (Render). Copy the Client ID, the **Client Secret** and your `VERIFIER_SHARED_SECRET` into the same-named variables on **Netlify** (Site configuration -> Environment variables; leave all scopes enabled so Functions can read them). Optionally set `SITE_URL` there too; otherwise Netlify's `URL` is used and must equal the verifier's `SITE_URL`.
3. Under **Redirects** add exactly `<SITE_URL>/verify.html` (the verifier logs the exact value at startup). Only the `identify` scope is requested.

### Ban list
Admins manage it with `/ageban add|remove|list`. A banned account can't start verification, use self-attestation or complete a proof. Banning also removes the 18+ role and bans the ID fingerprint the account verified with, so that ID can't be reused on another account. `/ageban remove` lifts both.

## Testing with a mock passport
Set `ZK_DEV_MODE=true` on the verifier and `ZK_ALLOW_DEV_PROOFS=true` on the bot (test server only). In the Self app, create a mock passport (on the app's first screen tap the **Passport** button five times), then run `/verify`. Mock passports are checked against Self's staging hub on Celo Sepolia, real ones against mainnet. Turn both flags off afterwards.

## Run / test

```
npm ci
npm test                                   # unit + HTTP tests; includes the real SDK refusing a forged proof
npm start                                  # the API (needs the env above)
API_BASE_URL=https://... npm run build     # static portal -> dist/site
```

`npm ci` pulls two dependencies of `@selfxyz/core` straight from GitHub (`snarkjs` and `node-forge` forks, pinned by commit in `package-lock.json`). npm falls back to GitHub tarballs, so `git` is not required (checked with `git` hidden from `PATH`). `verifier/Dockerfile` itself was **not** built here (no Docker daemon available).

## Migrating from zkPassport
- Users already verified stay verified (`user_verifications.verified`); new rows record `method = 'self'`, older ones keep `'zkpassport'`.
- Self nullifiers and zkPassport unique identifiers are different values, so stored zkPassport fingerprints cannot be matched against Self proofs. A document that already verified one account through zkPassport can verify one more through Self, and fingerprints banned earlier do not block the same document on Self.
- Documents differ: only passports and biometric ID cards are accepted (`ALLOWED_ATTESTATION_IDS`). Self's Aadhaar and KYC attestations are rejected on purpose.

## What this proves, and what it does not
- Proves: someone holding a passport/ID whose chip signature chains to a certificate in Self's registry is >= 18, without revealing the document or birthdate to AMORA. The verifier checks the proof on-chain through `forno.celo.org` (mock: `forno.celo-sepolia.celo-testnet.org`), so verification needs that RPC to be reachable (a forged proof took about 2 s to refuse here); if it is down, proofs are refused, not accepted.
- Does **not** prove the person holding the ID is the one who owns the Discord account's real identity: a borrowed phone and ID passes. The Discord login and the QR ticket do stop someone else from completing **your** link. The login function only accepts POSTs from the portal's own origin and holds no state.
- The nullifier is scoped to this API's host and `ZK_SCOPE`. AMORA stores only a pepper-keyed HMAC of it, which protects against database leaks.
- The proof, the session token and the QR ticket travel through Self's relay (`websocket.self.xyz`). The token is single-use and expires after 15 minutes, and without the ticket MAC key nobody can forge a QR that this API accepts.
- Trust is placed in Self's circuits, registry contracts and SDK. This service verifies; it does not audit them.
- Self Pass (`@selfxyz/core`, `@selfxyz/sdk-common`) is marked **legacy** by Self in favour of the managed Self Enterprise product, and `@selfxyz/core@1.2.0-beta.1` is a pre-release. Expect to migrate eventually; the portal's `getUniversalLink` is already flagged deprecated upstream.
- Self is not free of NFC problems: its Play Store rating is 3.9 (zkPassport: 1.9), with recent reviews still describing a recovery loop after the NFC scan. Documents the app cannot read still need the self-attestation fallback or staff help.
- Not tested here: a genuine proof from a real passport/app or a mock passport (needs a phone), the Self relayer actually calling this API, and the cross-origin flow against a real Netlify deployment. Unit tests cover the API and the proof checks; the portal was smoke-tested in a headless browser against the real API handlers with a stubbed proof check and bot.
