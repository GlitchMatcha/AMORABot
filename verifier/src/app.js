import { fingerprint } from "./hmac.js";
import { deriveKey, loginAssertionKey, readPayload, signPayload, ticketMatches, ticketUserId } from "./signedTokens.js";
import { parseUserContext } from "./userContext.js";

const TOKEN_PATTERN = /^[a-f0-9]{64}$/;
const STATE_PATTERN = /^[a-f0-9]{32}$/;
const DISCORD_ID_PATTERN = /^\d{15,25}$/;
// What the portal commits into the proof as userDefinedData: the session token and the Discord account that owns it.
const USER_DATA_PATTERN = /^([a-f0-9]{64})\.(\d{15,25})$/;
const SESSION_TTL_MS = 15 * 60_000;
const OUTCOME_TTL_MS = 15 * 60_000;
const MAX_OUTCOMES = 1000;

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
  // Status polling is cheap (memory only) and every open portal tab polls every few seconds.
  const allowStatus = createRateLimiter(config.rateLimitPerMinute * 6, now);
  const allowRelayer = createRateLimiter(config.relayerRateLimitPerMinute, now);
  const sessionKey = deriveKey(config.sharedSecret, "amora-session-v1");
  const ticketKey = deriveKey(config.sharedSecret, "amora-self-ticket-v1");
  const assertionKey = loginAssertionKey(config.sharedSecret);
  let inFlight = 0;

  // Latest result per QR ticket so the portal can show what happened. The proof reaches us from Self's relayer, not
  // from the browser, so this is the only way the page learns about it. The bot stays the source of truth.
  const outcomes = new Map();
  function record(ticket, state, message) {
    if (outcomes.get(ticket)?.state === "verified") return; // a relayer retry after success must not mask it
    outcomes.set(ticket, { state, message, at: now() });
    if (outcomes.size > MAX_OUTCOMES) {
      for (const [key, value] of outcomes) {
        if (outcomes.size <= MAX_OUTCOMES / 2 || now() - value.at > OUTCOME_TTL_MS) outcomes.delete(key);
      }
    }
  }
  function currentOutcome(ticket) {
    const outcome = outcomes.get(ticket);
    if (!outcome || now() - outcome.at > OUTCOME_TTL_MS) return { state: "pending" };
    return { state: outcome.state, message: outcome.message };
  }

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
   * Accepts the signed login assertion the portal got from the Netlify login function (which is the only place that
   * talks to Discord: Render's shared IP is blocked by Cloudflare). Only a login for the account that owns the
   * session is accepted; it returns a short-lived signed bearer the portal sends to /api/self-app and /api/status.
   */
  async function handleLogin(req, res) {
    if (!allow(clientAddress(req))) return send(res, 429, { ok: false, error: "rate_limited" });
    const payload = await readJson(req, res);
    if (payload === undefined) return undefined;

    const { token, assertion } = payload;
    if (!TOKEN_PATTERN.test(token ?? "")) return send(res, 400, { ok: false, error: "invalid" });
    const user = typeof assertion === "string" && assertion.length <= 1024 ? readPayload(assertionKey, assertion, now()) : null;
    if (!user || !DISCORD_ID_PATTERN.test(user.u ?? "")) return send(res, 400, { ok: false, error: "login_failed" });

    let check;
    try {
      check = await bot.sessionCheck(token, user.u);
    } catch (err) {
      console.error("bot session-check failed:", err.message);
      return send(res, 502, { ok: false, error: "unavailable" });
    }
    if (check.banned) return send(res, 403, { ok: false, error: "banned" });
    if (!check.live) return send(res, 410, { ok: false, error: "expired" });
    if (!check.ownerMatches) return send(res, 403, { ok: false, error: "wrong_account" });

    const session = signPayload(sessionKey, { t: token, u: user.u, exp: now() + SESSION_TTL_MS });
    return send(res, 200, { ok: true, username: String(user.n ?? "Discord user").slice(0, 64), session });
  }

  /** What the portal needs to build the Self QR code for the logged-in account. */
  function handleSelfApp(req, res) {
    const login = currentLogin(req);
    if (!login) return send(res, 401, { ok: false, error: "login_required" });
    const userDefinedData = `${login.t}.${login.u}`;
    return send(res, 200, { ok: true, userId: ticketUserId(ticketKey, userDefinedData), userDefinedData });
  }

  function handleStatus(req, res, clientKey) {
    if (!allowStatus(clientKey)) return send(res, 429, { ok: false, error: "rate_limited" });
    const login = currentLogin(req);
    if (!login) return send(res, 401, { ok: false, error: "login_required" });
    return send(res, 200, { ok: true, ...currentOutcome(ticketUserId(ticketKey, `${login.t}.${login.u}`)) });
  }

  /**
   * Called by Self's relayer (not a browser) with the proof the phone produced. Self expects HTTP 200 with
   * `{status, result, reason}` whatever the outcome. The Discord account and session come from the committed
   * userDefinedData, which is only trusted when its MAC (the proof's userId) is ours.
   */
  async function handleSelfVerify(req, res) {
    console.log("self verify: proof received from relayer");
    const fail = (reason, ticket) => {
      console.warn(`self verify refused: ${reason}`);
      if (ticket) record(ticket, "rejected", reason);
      return send(res, 200, { status: "error", result: false, reason });
    };
    if (!allowRelayer("relayer")) return fail("Too many requests. Try again in a minute.");
    const payload = await readJson(req, res);
    if (payload === undefined) return undefined;

    const { attestationId, proof, publicSignals, userContextData } = payload;
    const context = parseUserContext(userContextData);
    const data = USER_DATA_PATTERN.exec(context?.userDefinedData ?? "");
    if (!data || !ticketMatches(ticketKey, context.userDefinedData, context.userId)) {
      return fail("This QR code was not issued by the AMORA portal. Start again from /verify in Discord.");
    }
    const [, token, discordUserId] = data;
    const ticket = context.userId;

    // Cheap check first so we only run the expensive proof verification for a live session of this very user.
    let check;
    try {
      check = await bot.sessionCheck(token, discordUserId);
    } catch (err) {
      console.error("bot session-check failed:", err.message);
      return fail("Verification is temporarily unavailable.", ticket);
    }
    if (check.banned) return fail("You can't use automatic age verification. Contact staff.", ticket);
    if (!check.live || !check.ownerMatches) {
      return fail("This link has expired or was already used. Run /verify again.", ticket);
    }

    if (inFlight >= config.maxConcurrentVerifications) {
      return fail("Verifier is busy. Try again in a moment.", ticket);
    }
    inFlight++;
    let result;
    try {
      result = await verifyAgeProof({ attestationId, proof, publicSignals, userContextData });
    } finally {
      inFlight--;
    }
    if (!result.ok) {
      console.warn(`proof rejected: ${result.reason}${result.detail ? ` | ${result.detail}` : ""}`);
      // Only short identifier-like details (SDK issue types such as "InvalidRoot,InvalidScope") reach the page.
      const safeDetail = /^[A-Za-z]{1,40}(,[A-Za-z]{1,40}){0,7}$/.test(result.detail ?? "") ? `: ${result.detail}` : "";
      return fail(`The proof could not be verified (${result.reason}${safeDetail}).`, ticket);
    }

    let outcome;
    try {
      outcome = await bot.complete(token, fingerprint(config.idHashPepper, result.nullifier), config.devMode, discordUserId);
    } catch (err) {
      console.error("bot complete failed:", err.message);
      return fail("Verification is temporarily unavailable.", ticket);
    }
    if (outcome === "ok") {
      console.log("self verify: accepted, session completed");
      record(ticket, "verified", "Verified. You can return to Discord.");
      return send(res, 200, { status: "success", result: true });
    }
    if (outcome === "banned") return fail("You can't use automatic age verification. Contact staff.", ticket);
    if (outcome === "dev_proof_not_allowed") return fail("Test-mode proofs are not accepted on this server.", ticket);
    if (outcome === "duplicate_id") {
      return fail("This ID has already been used to verify another Discord account. Contact staff.", ticket);
    }
    return fail("This link has expired or was already used. Run /verify again.", ticket);
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
        return send(res, 200, { scope: config.scope, endpoint: config.selfEndpoint, devMode: config.devMode });
      }
      if (req.method === "GET" && path === "/api/discord-url") return handleDiscordUrl(res, url);
      if (req.method === "GET" && path === "/api/self-app") return handleSelfApp(req, res);
      if (req.method === "GET" && path === "/api/status") return handleStatus(req, res, clientAddress(req));
      if (path === "/api/login") {
        if (req.method !== "POST") return send(res, 405, "Method not allowed", { Allow: "POST" });
        return handleLogin(req, res);
      }
      if (path === "/api/self/verify") {
        if (req.method !== "POST") return send(res, 405, "Method not allowed", { Allow: "POST" });
        return handleSelfVerify(req, res);
      }
      return send(res, 404, "Not found");
    } catch (err) {
      console.error("unhandled error:", err);
      if (!res.headersSent) send(res, 500, { ok: false, message: "Internal error." });
    }
  };
}
