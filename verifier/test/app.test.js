import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createApp } from "../src/app.js";
import { fingerprint } from "../src/hmac.js";
import { deriveKey, signPayload } from "../src/signedTokens.js";

const TOKEN = "a".repeat(64);
const USER = "123456789012345678";
const SITE = "https://portal.test";
const config = {
  scope: "test-scope",
  devMode: false,
  siteOrigin: SITE,
  sharedSecret: "k".repeat(40),
  idHashPepper: "p".repeat(32),
  maxConcurrentVerifications: 2,
  maxBodyBytes: 1024,
  rateLimitPerMinute: 1000,
};

const sessionKey = deriveKey(config.sharedSecret, "amora-session-v1");
const bearer = (overrides = {}, key = sessionKey) =>
  `Bearer ${signPayload(key, { t: TOKEN, u: USER, exp: Date.now() + 60_000, ...overrides })}`;

async function withServer(overrides, run) {
  const calls = { verify: 0, complete: [], checks: [], exchange: [] };
  const check = overrides.check ?? { live: true, ownerMatches: true, banned: false };
  const deps = {
    config: { ...config, ...overrides.config },
    verifyAgeProof: async (input) => {
      calls.verify++;
      return (overrides.verify ?? (() => ({ ok: true, uniqueIdentifier: "raw-id-123" })))(input);
    },
    bot: {
      sessionCheck: async (token, userId) => {
        calls.checks.push({ token, userId });
        if (overrides.checkError) throw new Error("bot down");
        return check;
      },
      complete: async (token, fp, devMode, userId) => {
        calls.complete.push({ token, fp, devMode, userId });
        return overrides.outcome ?? "ok";
      },
    },
    discordAuth: {
      authorizeUrl: (state) => `https://discord.test/authorize?state=${state}`,
      exchange: async (code) => {
        calls.exchange.push(code);
        if (overrides.exchangeError) throw new Error("boom");
        return overrides.discordUser ?? { id: USER, username: "tester" };
      },
    },
  };
  const server = createServer(createApp(deps));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    await run(base, calls);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

const body = { proofs: [{}], queryResult: {} };
const post = (base, payload, headers = {}) =>
  fetch(`${base}/api/verify`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: bearer(), ...headers },
    body: JSON.stringify(payload),
  });
const login = (base, payload, headers = {}) =>
  fetch(`${base}/api/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(payload),
  });

// ---- /api/verify ---------------------------------------------------------------------------------

test("verification requires a valid signed bearer token", async () => {
  await withServer({}, async (base, calls) => {
    const forgedWithOtherKey = bearer({}, deriveKey("x".repeat(40), "amora-session-v1"));
    for (const authorization of [undefined, "Bearer garbage", "Basic abc", bearer({ exp: Date.now() - 1 }), forgedWithOtherKey]) {
      const headers = { "Content-Type": "application/json" };
      if (authorization) headers.Authorization = authorization;
      const res = await fetch(`${base}/api/verify`, { method: "POST", headers, body: JSON.stringify(body) });
      assert.equal(res.status, 401);
    }
    assert.equal(calls.checks.length, 0);
    assert.equal(calls.verify, 0);
  });
});

test("the session token and Discord user come from the bearer, never the request body", async () => {
  await withServer({}, async (base, calls) => {
    const res = await post(base, { ...body, token: "f".repeat(64), discordUserId: "999" });
    assert.equal(res.status, 200);
    assert.deepEqual(calls.checks, [{ token: TOKEN, userId: USER }]);
    assert.equal(calls.complete[0].token, TOKEN);
    assert.equal(calls.complete[0].userId, USER);
  });
});

test("non-JSON content type is refused", async () => {
  await withServer({}, async (base) => {
    const res = await fetch(`${base}/api/verify`, { method: "POST", headers: { "Content-Type": "text/plain", Authorization: bearer() }, body: "x" });
    assert.equal(res.status, 415);
  });
});

test("expired, used or foreign sessions return 410 without running proof verification", async () => {
  for (const check of [
    { live: false, ownerMatches: false, banned: false },
    { live: true, ownerMatches: false, banned: false },
  ]) {
    await withServer({ check }, async (base, calls) => {
      assert.equal((await post(base, body)).status, 410);
      assert.equal(calls.verify, 0);
      assert.equal(calls.complete.length, 0);
    });
  }
});

test("a banned user gets 403 and no proof verification runs", async () => {
  await withServer({ check: { live: true, ownerMatches: true, banned: true } }, async (base, calls) => {
    assert.equal((await post(base, body)).status, 403);
    assert.equal(calls.verify, 0);
  });
});

test("an invalid proof is rejected and the session is not consumed", async () => {
  await withServer({ verify: () => ({ ok: false, reason: "proof_invalid" }) }, async (base, calls) => {
    assert.equal((await post(base, body)).status, 400);
    assert.equal(calls.complete.length, 0);
  });
});

test("a valid proof completes with a pepper-keyed fingerprint, never the raw identifier", async () => {
  await withServer({}, async (base, calls) => {
    assert.equal((await post(base, body)).status, 200);
    assert.deepEqual(calls.complete, [{ token: TOKEN, fp: fingerprint(config.idHashPepper, "raw-id-123"), devMode: false, userId: USER }]);
    assert.ok(!JSON.stringify(calls.complete).includes("raw-id-123"));
  });
});

test("bot outcomes map to the right HTTP status", async () => {
  const expected = { duplicate_id: 409, dev_proof_not_allowed: 403, banned: 403, invalid_session: 410 };
  for (const [outcome, status] of Object.entries(expected)) {
    await withServer({ outcome }, async (base) => {
      assert.equal((await post(base, body)).status, status, outcome);
    });
  }
});

test("the dev-mode flag is forwarded to the bot", async () => {
  await withServer({ config: { devMode: true } }, async (base, calls) => {
    await post(base, body);
    assert.equal(calls.complete[0].devMode, true);
  });
});

test("oversized bodies are refused with 413", async () => {
  await withServer({}, async (base, calls) => {
    assert.equal((await post(base, { ...body, proofs: ["x".repeat(4096)] })).status, 413);
    assert.equal(calls.checks.length, 0);
  });
});

test("rate limit applies per client address (last X-Forwarded-For entry)", async () => {
  await withServer({ config: { rateLimitPerMinute: 2 } }, async (base) => {
    const spoofed = (n) => ({ "X-Forwarded-For": `10.0.0.${n}, 203.0.113.9` });
    assert.equal((await post(base, body, spoofed(1))).status, 200);
    assert.equal((await post(base, body, spoofed(2))).status, 200);
    assert.equal((await post(base, body, spoofed(3))).status, 429);
  });
});

test("only POST is allowed on /api/verify and /api/login", async () => {
  await withServer({}, async (base) => {
    for (const path of ["/api/verify", "/api/login"]) {
      const res = await fetch(`${base}${path}`);
      assert.equal(res.status, 405, path);
    }
  });
});

test("config.json exposes scope and devMode for the portal", async () => {
  await withServer({}, async (base) => {
    assert.deepEqual(await (await fetch(`${base}/config.json`)).json(), { scope: "test-scope", devMode: false });
  });
});

// ---- CORS: the portal lives on another origin --------------------------------------------------------

test("the portal origin is allowed and preflights succeed; no credentialed CORS", async () => {
  await withServer({}, async (base) => {
    const pre = await fetch(`${base}/api/verify`, { method: "OPTIONS", headers: { Origin: SITE, "Access-Control-Request-Method": "POST" } });
    assert.equal(pre.status, 204);
    assert.equal(pre.headers.get("access-control-allow-origin"), SITE);
    assert.match(pre.headers.get("access-control-allow-headers"), /Authorization/);
    assert.equal(pre.headers.get("access-control-allow-credentials"), null);

    const res = await fetch(`${base}/config.json`, { headers: { Origin: SITE } });
    assert.equal(res.headers.get("access-control-allow-origin"), SITE);
    assert.equal(res.headers.get("referrer-policy"), "no-referrer");
    assert.equal(res.headers.get("x-content-type-options"), "nosniff");
  });
});

test("the API serves no pages or cookies", async () => {
  await withServer({}, async (base) => {
    for (const path of ["/", "/verify", "/about", "/client.js", "/auth/discord", "/auth/callback", "/api/me"]) {
      const res = await fetch(`${base}${path}`, { redirect: "manual" });
      assert.equal(res.status, 404, path);
      assert.equal(res.headers.get("set-cookie"), null);
    }
  });
});

// ---- Discord login ---------------------------------------------------------------------------------

test("/api/discord-url builds the authorize URL only for a well-formed state", async () => {
  await withServer({}, async (base) => {
    const state = "0123456789abcdef0123456789abcdef";
    const ok = await (await fetch(`${base}/api/discord-url?state=${state}`)).json();
    assert.deepEqual(ok, { ok: true, url: `https://discord.test/authorize?state=${state}` });

    for (const bad of ["", "nope", "A".repeat(32), "a".repeat(64)]) {
      const res = await fetch(`${base}/api/discord-url?state=${bad}`);
      assert.equal(res.status, 400, bad);
    }
  });
});

test("the account that owns the session gets a bearer that /api/verify accepts", async () => {
  await withServer({}, async (base, calls) => {
    const res = await login(base, { token: TOKEN, code: "abc" });
    assert.equal(res.status, 200);
    const out = await res.json();
    assert.equal(out.ok, true);
    assert.equal(out.username, "tester");
    assert.deepEqual(calls.exchange, ["abc"]);
    assert.deepEqual(calls.checks.at(-1), { token: TOKEN, userId: USER });

    const verified = await post(base, body, { Authorization: `Bearer ${out.session}` });
    assert.equal(verified.status, 200);
    assert.deepEqual(calls.complete.at(-1).userId, USER);
  });
});

test("a different Discord account is refused and gets no session", async () => {
  await withServer({ check: { live: true, ownerMatches: false, banned: false }, discordUser: { id: "555555555555555555", username: "friend" } }, async (base) => {
    const res = await login(base, { token: TOKEN, code: "abc" });
    assert.equal(res.status, 403);
    assert.deepEqual(await res.json(), { ok: false, error: "wrong_account" });
  });
});

test("banned and expired sessions are refused at login", async () => {
  const cases = [
    [{ live: true, ownerMatches: true, banned: true }, 403, "banned"],
    [{ live: false, ownerMatches: false, banned: false }, 410, "expired"],
  ];
  for (const [check, status, error] of cases) {
    await withServer({ check }, async (base) => {
      const res = await login(base, { token: TOKEN, code: "abc" });
      assert.equal(res.status, status);
      assert.deepEqual(await res.json(), { ok: false, error });
    });
  }
});

test("login rejects a malformed token or code without contacting Discord, and reports Discord/bot failures", async () => {
  await withServer({}, async (base, calls) => {
    assert.equal((await login(base, { token: "nope", code: "abc" })).status, 400);
    assert.equal((await login(base, { token: TOKEN })).status, 400);
    assert.equal((await login(base, { token: TOKEN, code: "x".repeat(600) })).status, 400);
    assert.equal((await login(base, { token: TOKEN, code: "abc" }, { "Content-Type": "text/plain" })).status, 415);
    assert.equal(calls.exchange.length, 0);
  });
  await withServer({ exchangeError: true }, async (base) => {
    const res = await login(base, { token: TOKEN, code: "abc" });
    assert.equal(res.status, 400);
    assert.deepEqual(await res.json(), { ok: false, error: "login_failed" });
  });
  await withServer({ checkError: true }, async (base) => {
    assert.equal((await login(base, { token: TOKEN, code: "abc" })).status, 502);
  });
});
