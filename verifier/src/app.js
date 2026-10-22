import { readFile } from "node:fs/promises";
import { fingerprint } from "./hmac.js";

const TOKEN_PATTERN = /^[a-f0-9]{64}$/;

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

export function createApp({ config, verifyAgeProof, bot, rootDir, now = Date.now }) {
  const allow = createRateLimiter(config.rateLimitPerMinute, now);
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

  async function serveStatic(res, entry) {
    try {
      const content = await readFile(new URL(entry.file, rootDir));
      res.writeHead(200, { ...SECURITY_HEADERS, "Content-Type": entry.type });
      res.end(content);
    } catch {
      send(res, 404, "Not found");
    }
  }

  async function handleVerify(req, res) {
    if (!allow(clientAddress(req))) return send(res, 429, { ok: false, message: "Too many attempts. Try again in a minute." });

    let payload;
    try {
      payload = JSON.parse(await readBody(req, config.maxBodyBytes));
    } catch (err) {
      if (err instanceof BodyTooLarge) return send(res, 413, { ok: false, message: "Request too large." });
      return send(res, 400, { ok: false, message: "Malformed request." });
    }

    const { token, proofs, queryResult } = payload ?? {};
    if (typeof token !== "string" || !TOKEN_PATTERN.test(token)) {
      return send(res, 400, { ok: false, message: "Invalid verification link." });
    }

    // Cheap session check first so random tokens can't make us run the expensive proof verification.
    let live;
    try {
      live = await bot.sessionLive(token);
    } catch (err) {
      console.error("bot session-check failed:", err.message);
      return send(res, 502, { ok: false, message: "Verification is temporarily unavailable." });
    }
    if (!live) return send(res, 410, { ok: false, message: "This link has expired or was already used. Run /verify again." });

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
      outcome = await bot.complete(token, fingerprint(config.idHashPepper, result.uniqueIdentifier));
    } catch (err) {
      console.error("bot complete failed:", err.message);
      return send(res, 502, { ok: false, message: "Verification is temporarily unavailable." });
    }
    if (outcome === "ok") return send(res, 200, { ok: true, message: "Verified. You can return to Discord." });
    if (outcome === "duplicate_id") {
      return send(res, 409, { ok: false, message: "This ID has already been used to verify another Discord account. Contact staff." });
    }
    return send(res, 410, { ok: false, message: "This link has expired or was already used. Run /verify again." });
  }

  return async function handle(req, res) {
    try {
      const path = new URL(req.url, "http://localhost").pathname;

      if (req.method === "GET" && path === "/health") return send(res, 200, { status: "ok" });
      if (req.method === "GET" && path === "/config.json") {
        return send(res, 200, { scope: config.scope, devMode: config.devMode });
      }
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
