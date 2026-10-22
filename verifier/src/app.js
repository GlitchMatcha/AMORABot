import { randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { fingerprint } from "./hmac.js";
import { clearCookie, deriveKey, parseCookies, readPayload, serializeCookie, signPayload } from "./cookies.js";

const TOKEN_PATTERN = /^[a-f0-9]{64}$/;
const SESSION_COOKIE = "amora_session";
const OAUTH_COOKIE = "amora_oauth";
const OAUTH_TTL_MS = 10 * 60_000;
const SESSION_TTL_MS = 15 * 60_000;

const SECURITY_HEADERS = {
  "Cache-Control": "no-store",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  // The zkPassport button talks to its bridge/popup over https/wss; scripts are limited to our own bundle.
  "Content-Security-Policy":
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; " +
    "connect-src 'self' https: wss:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
};

const STATIC_FILES = {
  "/verify": { file: "public/index.html", type: "text/html; charset=utf-8" },
  "/style.css": { file: "public/style.css", type: "text/css; charset=utf-8" },
  "/client.js": { file: "dist/client.js", type: "text/javascript; charset=utf-8" },
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

export function createApp({ config, verifyAgeProof, bot, discordAuth, rootDir, now = Date.now }) {
  const allow = createRateLimiter(config.rateLimitPerMinute, now);
  const sessionKey = deriveKey(config.sharedSecret, "amora-session-cookie-v1");
  const oauthKey = deriveKey(config.sharedSecret, "amora-oauth-state-v1");
  let inFlight = 0;

  function send(res, status, body, headers = {}) {
    const isJson = typeof body === "object";
    res.writeHead(status, {
      ...SECURITY_HEADERS,
      "Content-Type": isJson ? "application/json" : "text/plain; charset=utf-8",
      ...headers,
    });
    res.end(isJson ? JSON.stringify(body) : body);
  }

  function redirect(res, location, cookies = []) {
    res.writeHead(302, { ...SECURITY_HEADERS, Location: location, "Set-Cookie": cookies });
    res.end();
  }

  async function serveStatic(res, entry) {
    try {
      const content = await readFile(new URL(entry.file, rootDir));
      res.writeHead(200, { ...SECURITY_HEADERS, "Content-Type": entry.type });
      res.end(content);
    } catch {
      send(res, 404, "Not found");
    }
  }

  function currentLogin(req) {
    return readPayload(sessionKey, parseCookies(req.headers.cookie)[SESSION_COOKIE], now());
  }

  /** Step 1: remember which session this browser is logging in for, then send the user to Discord. */
  async function handleAuthStart(req, res, url) {
    if (!allow(clientAddress(req))) return send(res, 429, "Too many attempts. Try again in a minute.");
    const token = url.searchParams.get("s");
    if (!TOKEN_PATTERN.test(token ?? "")) return redirect(res, "/verify?error=invalid");

    const nonce = randomBytes(16).toString("hex");
    const pending = signPayload(oauthKey, { t: token, n: nonce, exp: now() + OAUTH_TTL_MS });
    return redirect(res, discordAuth.authorizeUrl(nonce), [
      serializeCookie(OAUTH_COOKIE, pending, { maxAgeSeconds: OAUTH_TTL_MS / 1000, secure: config.secureCookies }),
    ]);
  }

  /** Step 2: Discord sends the user back. Only a login for the account that owns the session is accepted. */
  async function handleAuthCallback(req, res, url) {
    if (!allow(clientAddress(req))) return send(res, 429, "Too many attempts. Try again in a minute.");
    const clear = [clearCookie(OAUTH_COOKIE, config.secureCookies)];
    const fail = (code) => redirect(res, `/verify?error=${code}`, clear);

    const pending = readPayload(oauthKey, parseCookies(req.headers.cookie)[OAUTH_COOKIE], now());
    const code = url.searchParams.get("code");
    if (!pending || !code || url.searchParams.get("state") !== pending.n) {
      return fail(url.searchParams.get("error") === "access_denied" ? "denied" : "login_failed");
    }

    let user;
    try {
      user = await discordAuth.exchange(code);
    } catch (err) {
      console.error("discord login failed:", err.message);
      return fail("login_failed");
    }

    let check;
    try {
      check = await bot.sessionCheck(pending.t, user.id);
    } catch (err) {
      console.error("bot session-check failed:", err.message);
      return fail("unavailable");
    }
    if (check.banned) return fail("banned");
    if (!check.live) return fail("expired");
    if (!check.ownerMatches) return fail("wrong_account");

    const login = signPayload(sessionKey, { t: pending.t, u: user.id, name: user.username, exp: now() + SESSION_TTL_MS });
    return redirect(res, "/verify", [
      clearCookie(OAUTH_COOKIE, config.secureCookies),
      serializeCookie(SESSION_COOKIE, login, { maxAgeSeconds: SESSION_TTL_MS / 1000, secure: config.secureCookies }),
    ]);
  }

  function handleMe(req, res) {
    const login = currentLogin(req);
    if (!login) return send(res, 200, { loggedIn: false });
    return send(res, 200, { loggedIn: true, username: login.name, token: login.t });
  }

  async function handleVerify(req, res) {
    if (!allow(clientAddress(req))) return send(res, 429, { ok: false, message: "Too many attempts. Try again in a minute." });

    const login = currentLogin(req);
    if (!login) return send(res, 401, { ok: false, message: "Log in with Discord first." });
    if (!String(req.headers["content-type"] ?? "").startsWith("application/json")) {
      return send(res, 415, { ok: false, message: "Unsupported request." });
    }

    let payload;
    try {
      payload = JSON.parse(await readBody(req, config.maxBodyBytes));
    } catch (err) {
      if (err instanceof BodyTooLarge) return send(res, 413, { ok: false, message: "Request too large." });
      return send(res, 400, { ok: false, message: "Malformed request." });
    }
    // The session token and Discord identity come from the signed login cookie, never from the request body.
    const token = login.t;
    const discordUserId = login.u;
    const { proofs, queryResult } = payload ?? {};

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

      if (req.method === "GET" && path === "/health") return send(res, 200, { status: "ok" });
      if (req.method === "GET" && path === "/config.json") {
        return send(res, 200, { scope: config.scope, devMode: config.devMode });
      }
      if (req.method === "GET" && path === "/auth/discord") return handleAuthStart(req, res, url);
      if (req.method === "GET" && path === "/auth/callback") return handleAuthCallback(req, res, url);
      if (req.method === "GET" && path === "/api/me") return handleMe(req, res);
      if (req.method === "GET" && STATIC_FILES[path]) return serveStatic(res, STATIC_FILES[path]);
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
