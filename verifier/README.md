# AMORA age verifier (zero-knowledge, zkPassport)

Self-hosted service that checks real zero-knowledge proofs of "holder of a valid passport/ID chip is 18 or older" and tells the AMORA bot to unlock the Adult Zone. It does not write its own cryptography: proofs are produced by the [zkPassport](https://docs.zkpassport.id) app and verified here with `@zkpassport/sdk` in `verifierMode: "local"` (no third-party verifier API).

## Flow

1. `/verify` (or the 18+ panel button) in Discord: the bot stores `sha256(token)` for a 15-minute single-use session; the ephemeral embed links to `VERIFIER_PUBLIC_URL/verify?s=<token>`.
2. The portal shows the zkPassport button. The query (`age >= 18`) is fixed; the session token is committed into the proof (`bind.custom_data`), so a proof made for one session fails for any other.
3. `POST /api/verify` receives `proofs` + `queryResult`. The service asks the bot whether the session is live, **rebuilds the query itself** (never trusts the browser's), verifies locally, then calls the bot with `HMAC(ID_HASH_PEPPER, uniqueIdentifier)`.
4. The bot burns the session, stores the fingerprint (unique: one ID verifies one Discord account), sets the user verified and swaps the minor/adult roles.

Bot <-> verifier calls are signed: `X-Amora-Signature = hex(HMAC-SHA256(VERIFIER_SHARED_SECRET, "{X-Amora-Timestamp}.{body}"))`, 60 s window.

## Configuration

Verifier (`verifier/`):

| Variable | Meaning |
|---|---|
| `VERIFIER_DOMAIN` | Host users open the portal on, no scheme (e.g. `amora-verifier.onrender.com`). Proofs are bound to it. |
| `BOT_INTERNAL_URL` | Bot base URL, e.g. `https://amora-bot.onrender.com` |
| `VERIFIER_SHARED_SECRET` | >= 32 chars, identical on the bot |
| `ID_HASH_PEPPER` | >= 32 chars, **verifier only**. Changing it makes previously stored fingerprints unmatchable. |
| `ZK_SCOPE` | Default `amora-discord-18plus`. The unique identifier is scoped to it. |
| `ZK_DEV_MODE` | `true` accepts zkPassport mock passports (all share unique id `1`). **Must be unset/false in production**: the verifier prints a warning at startup when it is on, and refuses it if `NODE_ENV=production` is set. |
| `PORT`, `MAX_CONCURRENT_VERIFICATIONS`, `MAX_BODY_BYTES`, `RATE_LIMIT_PER_MINUTE` | Optional tuning |

Bot: `VERIFIER_PUBLIC_URL`, `VERIFIER_SHARED_SECRET`, optional `ALLOW_SELF_ATTESTATION=false`, and `ZK_ALLOW_DEV_PROOFS=true` **only on test servers** (the bot refuses mock-passport proofs without it, even if the verifier is left in dev mode). See `CHECKPOINTS.md`.

## Run / test

```
npm ci && npm run build   # bundles the portal script into dist/
npm start
npm test                  # unit + HTTP tests, includes the real SDK rejecting forged proofs
```

`verifier/Dockerfile` builds a production image (not exercised in this repo's checks).

## What this proves, and what it does not
- Local-mode verification loads the proving-system code (barretenberg WASM) and, I expect, fetches certificate-registry data at runtime; neither was exercised with a real proof here, so measure memory and egress on the target host before choosing a plan (`node_modules` is ~450 MB).
- Proves: someone holding a passport/ID whose chip signature chains to a certificate in zkPassport's registry is >= 18, without revealing the document or birthdate to AMORA.
- Does **not** prove the Discord user is that person. A borrowed phone and ID passes. FaceMatch (a stricter zkPassport mode) is not enabled.
- The default zkPassport unique identifier is not salted: anyone with the full chip data, including the issuing state, can recompute it. AMORA stores only a pepper-keyed HMAC of it, but the pepper protects against database leaks only, not against that party.
- Not everyone has a supported document (see zkPassport's coverage map), hence the fallback options.
- Trust is placed in zkPassport's circuits, registry and SDK. This service verifies; it does not audit them.
- Not tested here: a genuine proof from a real passport/app (needs a physical document or dev-mode phone). Unit tests cover everything except that positive path.
