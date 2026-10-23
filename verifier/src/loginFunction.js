import { createDiscordAuth } from "./discordAuth.js";
import { loginAssertionKey, signPayload } from "./signedTokens.js";

const ASSERTION_TTL_MS = 2 * 60_000;
const MAX_BODY_BYTES = 2048;

const json = (status, body) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Referrer-Policy": "no-referrer" },
  });

/**
 * The only component that talks to Discord. It runs as a Netlify Function because Cloudflare (in front of Discord)
 * blocks Render's shared outbound IP. It exchanges the OAuth code and returns a short-lived assertion, signed with
 * the secret it shares with the verifier API, saying which Discord account just logged in. It holds no state.
 */
export function createLoginHandler({ env = process.env, fetchImpl = fetch, now = Date.now } = {}) {
  const clientId = env.DISCORD_CLIENT_ID?.trim();
  const clientSecret = env.DISCORD_CLIENT_SECRET?.trim();
  const sharedSecret = env.VERIFIER_SHARED_SECRET?.trim();
  const siteUrl = (env.SITE_URL || env.URL || "").trim().replace(/\/+$/, "");

  return async function handle(request) {
    if (request.method !== "POST") return json(405, { ok: false, error: "invalid" });
    if (!clientId || !clientSecret || !sharedSecret || sharedSecret.length < 32 || !siteUrl) {
      console.error("login function is missing DISCORD_CLIENT_ID, DISCORD_CLIENT_SECRET, VERIFIER_SHARED_SECRET (>= 32 chars) or a site URL");
      return json(500, { ok: false, error: "unavailable" });
    }
    // Only the portal itself may call this; browsers always send Origin on a cross-site or same-site POST.
    const origin = request.headers.get("origin");
    if (origin && origin !== new URL(siteUrl).origin) return json(403, { ok: false, error: "invalid" });

    let code;
    try {
      const raw = await request.text();
      if (raw.length > MAX_BODY_BYTES) return json(413, { ok: false, error: "invalid" });
      code = JSON.parse(raw)?.code;
    } catch {
      return json(400, { ok: false, error: "invalid" });
    }
    if (typeof code !== "string" || !code || code.length > 512) return json(400, { ok: false, error: "login_failed" });

    let user;
    try {
      user = await createDiscordAuth({ clientId, clientSecret, redirectUri: `${siteUrl}/verify.html`, fetchImpl }).exchange(code);
    } catch (err) {
      console.error("discord login failed:", err.message);
      return json(400, { ok: false, error: "login_failed" });
    }
    return json(200, { ok: true, assertion: signPayload(loginAssertionKey(sharedSecret), { u: user.id, n: user.username, exp: now() + ASSERTION_TTL_MS }) });
  };
}
