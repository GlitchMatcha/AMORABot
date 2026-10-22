import { randomBytes } from "node:crypto";
import { fingerprint } from "./hmac.js";
import { deriveKey, readPayload, signPayload } from "./signedTokens.js";

const TOKEN_PATTERN = /^[a-f0-9]{64}$/;
const STATE_PATTERN = /^[a-f0-9]{32}$/;
const SESSION_TTL_MS = 15 * 60_000;

// JSON API only: the portal is a static site on another origin, so there is nothing here for a browser to
// navigate to. Auth is a signed bearer token, so no cookies and no credentialed CORS.
const SECURITY_HEADERS = {
  "Cache-Control": "no-store",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
};

class BodyTooLarge extends Error {}

async function readBody(req, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new BodyTooLarge();
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function createRateLimiter(limitPerMinute, now) {
  const hits = new Map();
  return function allow(key) {
    const t = now();
    const recent = (hits.get(key) ?? []).filter((ts) => t - ts < 60_000);
    if (recent.length >= limitPerMinute) {
      hits.set(key, recent);
      return false;
    }
    recent.push(t);
    hits.set(key, recent);
    if (hits.size > 10_000) {
      for (const [k, v] of hits) if (v.every((ts) => t - ts >= 60_000)) hits.delete(k);
    }
    return true;
  };
}

/** Render appends the real peer address as the last X-Forwarded-For entry; earlier entries are client-supplied. */
function clientAddress(req) {
  const forwarded = req.headers["x-forwarded-for"];
  if (forwarded) return String(forwarded).split(",").at(-1).trim();
  return req.socket.remoteAddress ?? "unknown";
}

export function createApp({ config, verifyAgeProof, bot, discordAuth, now = Date.now }) {
  const allow = createRateLimiter(config.rateLimitPerMinute, now);
  const sessionKey = deriveKey(config.sharedSecret, "amora-session-v1");
  let inFlight = 0;

  const corsHeaders = {
    "Access-Control-Allow-Origin": config.siteOrigin,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
    "Access-Control-Max-Age": "600",
    Vary: "Origin",
  };

  function send(res, status, body, headers = {}) {
    const isJson = typeof body === "object";
    res.writeHead(status, {
      ...SECURITY_HEADERS,
      ...corsHeaders,
      "Content-Type": isJson ? "application/json" : "text/plain; charset=utf-8",
      ...headers,
    });
    res.end(isJson ? JSON.stringify(body) : body);
  }

  function currentLogin(req) {
    const match = /^Bearer (\S+)$/.exec(req.headers.authorization ?? "");
    return match ? readPayload(sessionKey, match[1], now()) : null;
  }

  async function readJson(req, res) {
    if (!String(req.headers["content-type"] ?? "").startsWith("application/json")) {
      send(res, 415, { ok: false, message: "Unsupported request." });
      return undefined;
    }
    try {
      return JSON.parse(await readBody(req, config.maxBodyBytes)) ?? {};
    } catch (err) {
      if (err instanceof BodyTooLarge) send(res, 413, { ok: false, message: "Request too large." });
      else send(res, 400, { ok: false, message: "Malformed request." });
      return undefined;
    }
  }

  /** The portal asks for the Discord authorize URL so the OAuth client id and redirect stay configured in one place. */
  function handleDiscordUrl(res, url) {
    const state = url.searchParams.get("state") ?? "";
    if (!STATE_PATTERN.test(state)) return send(res, 400, { ok: false, error: "invalid" });
    return send(res, 200, { ok: true, url: discordAuth.authorizeUrl(state) });
  }

  /**
   * Exchanges the Discord authorization code. Only a login for the account that owns the session is accepted;
   * it returns a short-lived signed bearer the portal sends to /api/verify.
   */
  async function handleLogin(req, res) {
    if (!allow(clientAddress(req))) return send(res, 429, { ok: false, error: "rate_limited" });
    const payload = await readJson(req, res);
    if (payload === undefined) return undefined;

    const { token, code } = payload;
    if (!TOKEN_PATTERN.test(token ?? "")) return send(res, 400, { ok: false, error: "invalid" });
    if (typeof code !== "string" || !code || code.length > 512) return send(res, 400, { ok: false, error: "login_failed" });

    let user;
    try {
      user = await discordAuth.exchange(code);
    } catch (err) {
      console.error("discord login failed:", err.message);
      return send(res, 400, { ok: false, error: "login_failed" });
    }

    let check;
    try {
      check = await bot.sessionCheck(token, user.id);
    } catch (err) {
      console.error("bot session-check failed:", err.message);
      return send(res, 502, { ok: false, error: "unavailable" });
    }
    if (check.banned) return send(res, 403, { ok: false, error: "banned" });
    if (!check.live) return send(res, 410, { ok: false, error: "expired" });
    if (!check.ownerMatches) return send(res, 403, { ok: false, error: "wrong_account" });

    const session = signPayload(sessionKey, { t: token, u: user.id, exp: now() + SESSION_TTL_MS });
    return send(res, 200, { ok: true, username: user.username, session });
  }

  async function handleVerify(req, res) {
    if (!allow(clientAddress(req))) return send(res, 429, { ok: false, message: "Too many attempts. Try again in a minute." });

    const login = currentLogin(req);
    if (!login) return send(res, 401, { ok: false, message: "Log in with Discord first." });
    const payload = await readJson(req, res);
    if (payload === undefined) return undefined;

    // The session token and Discord identity come from the signed bearer, never from the request body.
    const token = login.t;
    const discordUserId = login.u;
    const { proofs, queryResult } = payload;

    // Cheap check first so we only run the expensive proof verification for a live session of this very user.
    let check;
    try {
      check = await bot.sessionCheck(token, discordUserId);
    } catch (err) {
      console.error("bot session-check failed:", err.message);
      return send(res, 502, { ok: false, message: "Verification is temporarily unavailable." });
    }
    if (check.banned) return send(res, 403, { ok: false, message: "You can't use automatic age verification. Contact staff." });
    if (!check.live || !check.ownerMatches) {
      return send(res, 410, { ok: false, message: "This link has expired or was already used. Run /verify again." });
    }

    if (inFlight >= config.maxConcurrentVerifications) {
      return send(res, 503, { ok: false, message: "Verifier is busy. Try again in a moment." });
    }
    inFlight++;
    let result;
    try {
      result = await verifyAgeProof({ token, proofs, queryResult });
    } finally {
      inFlight--;
    }
    if (!result.ok) {
      console.warn(`proof rejected: ${result.reason}${result.detail ? ` | ${result.detail}` : ""}`);
      return send(res, 400, { ok: false, message: "The proof could not be verified." });
    }

    let outcome;
    try {
      outcome = await bot.complete(token, fingerprint(config.idHashPepper, result.uniqueIdentifier), config.devMode, discordUserId);
    } catch (err) {
      console.error("bot complete failed:", err.message);
      return send(res, 502, { ok: false, message: "Verification is temporarily unavailable." });
    }
    if (outcome === "ok") return send(res, 200, { ok: true, message: "Verified. You can return to Discord." });
    if (outcome === "banned") {
      return send(res, 403, { ok: false, message: "You can't use automatic age verification. Contact staff." });
    }
    if (outcome === "dev_proof_not_allowed") {
      return send(res, 403, { ok: false, message: "Test-mode proofs are not accepted on this server." });
    }
    if (outcome === "duplicate_id") {
      return send(res, 409, { ok: false, message: "This ID has already been used to verify another Discord account. Contact staff." });
    }
    return send(res, 410, { ok: false, message: "This link has expired or was already used. Run /verify again." });
  }

  return async function handle(req, res) {
    try {
      const url = new URL(req.url, "http://localhost");
      const path = url.pathname;

      if (req.method === "OPTIONS") {
        res.writeHead(204, { ...SECURITY_HEADERS, ...corsHeaders });
        return res.end();
      }
      if (req.method === "GET" && path === "/health") return send(res, 200, { status: "ok" });
      if (req.method === "GET" && path === "/config.json") {
        return send(res, 200, { scope: config.scope, devMode: config.devMode });
      }
      if (req.method === "GET" && path === "/api/discord-url") return handleDiscordUrl(res, url);
      if (path === "/api/login") {
        if (req.method !== "POST") return send(res, 405, "Method not allowed", { Allow: "POST" });
        return handleLogin(req, res);
      }
      if (path === "/api/verify") {
        if (req.method !== "POST") return send(res, 405, "Method not allowed", { Allow: "POST" });
        return handleVerify(req, res);
      }
      return send(res, 404, "Not found");
    } catch (err) {
      console.error("unhandled error:", err);
      if (!res.headersSent) send(res, 500, { ok: false, message: "Internal error." });
    }
  };
}
