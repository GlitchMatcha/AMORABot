import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createApp } from "../src/app.js";
import { fingerprint } from "../src/hmac.js";
import { deriveKey, signPayload } from "../src/cookies.js";

const TOKEN = "a".repeat(64);
const USER = "123456789012345678";
const config = {
  scope: "test-scope",
  devMode: false,
  sharedSecret: "k".repeat(40),
  idHashPepper: "p".repeat(32),
  secureCookies: false,
  maxConcurrentVerifications: 2,
  maxBodyBytes: 1024,
  rateLimitPerMinute: 1000,
};

const sessionKey = deriveKey(config.sharedSecret, "amora-session-cookie-v1");
const loginCookie = (overrides = {}, key = sessionKey) =>
  `amora_session=${signPayload(key, { t: TOKEN, u: USER, name: "tester", exp: Date.now() + 60_000, ...overrides })}`;

async function withServer(overrides, run) {
  const calls = { verify: 0, complete: [], checks: [], exchange: [] };
  const check = overrides.check ?? { live: true, ownerMatches: true, banned: false };
  const deps = {
    config: { ...config, ...overrides.config },
    rootDir: new URL("../", import.meta.url),
    verifyAgeProof: async (input) => {
      calls.verify++;
      return (overrides.verify ?? (() => ({ ok: true, uniqueIdentifier: "raw-id-123" })))(input);
    },
    bot: {
      sessionCheck: async (token, userId) => {
        calls.checks.push({ token, userId });
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
    headers: { "Content-Type": "application/json", Cookie: loginCookie(), ...headers },
    body: JSON.stringify(payload),
  });
const get = (base, path, headers = {}) => fetch(`${base}${path}`, { redirect: "manual", headers });
const cookieValue = (res, name) => {
  const line = res.headers.getSetCookie().find((c) => c.startsWith(`${name}=`));
  return line ? line.split(";")[0] : null;
};

// ---- /api/verify ---------------------------------------------------------------------------------

test("verification requires a valid Discord login cookie", async () => {
  await withServer({}, async (base, calls) => {
    const forgedWithOtherKey = loginCookie({}, deriveKey("x".repeat(40), "amora-session-cookie-v1"));
    for (const cookie of [undefined, "amora_session=garbage", loginCookie({ exp: Date.now() - 1 }), forgedWithOtherKey]) {
      const headers = { "Content-Type": "application/json" };
      if (cookie) headers.Cookie = cookie;
      const res = await fetch(`${base}/api/verify`, { method: "POST", headers, body: JSON.stringify(body) });
      assert.equal(res.status, 401);
    }
    assert.equal(calls.checks.length, 0);
    assert.equal(calls.verify, 0);
  });
});

test("the session token and Discord user come from the cookie, never the request body", async () => {
  await withServer({}, async (base, calls) => {
    const res = await post(base, { ...body, token: "f".repeat(64), discordUserId: "999" });
    assert.equal(res.status, 200);
    assert.deepEqual(calls.checks, [{ token: TOKEN, userId: USER }, ]);
    assert.equal(calls.complete[0].token, TOKEN);
    assert.equal(calls.complete[0].userId, USER);
  });
});

test("non-JSON content type is refused", async () => {
  await withServer({}, async (base) => {
    const res = await fetch(`${base}/api/verify`, { method: "POST", headers: { "Content-Type": "text/plain", Cookie: loginCookie() }, body: "x" });
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

test("only POST is allowed on /api/verify and responses carry hardening headers", async () => {
  await withServer({}, async (base) => {
    const res = await fetch(`${base}/api/verify`);
    assert.equal(res.status, 405);
    assert.equal(res.headers.get("referrer-policy"), "no-referrer");
    assert.equal(res.headers.get("x-content-type-options"), "nosniff");
    assert.match(res.headers.get("content-security-policy"), /script-src 'self'/);
  });
});

test("config.json exposes scope and devMode for the portal", async () => {
  await withServer({}, async (base) => {
    assert.deepEqual(await (await fetch(`${base}/config.json`)).json(), { scope: "test-scope", devMode: false });
  });
});

// ---- Discord login ---------------------------------------------------------------------------------

test("/auth/discord rejects a malformed token and never contacts Discord", async () => {
  await withServer({}, async (base) => {
    const res = await get(base, "/auth/discord?s=nope");
    assert.equal(res.status, 302);
    assert.equal(res.headers.get("location"), "/verify?error=invalid");
  });
});

test("/auth/discord redirects to Discord with a nonce state and an HttpOnly cookie", async () => {
  await withServer({}, async (base) => {
    const res = await get(base, `/auth/discord?s=${TOKEN}`);
    assert.equal(res.status, 302);
    const location = new URL(res.headers.get("location"));
    assert.equal(location.origin, "https://discord.test");
    assert.match(location.searchParams.get("state"), /^[a-f0-9]{32}$/);
    assert.ok(!res.headers.get("location").includes(TOKEN), "the session token must not travel through Discord");
    const setCookie = res.headers.getSetCookie().find((c) => c.startsWith("amora_oauth="));
    assert.match(setCookie, /HttpOnly/);
    assert.match(setCookie, /SameSite=Lax/);
  });
});

async function startLogin(base) {
  const start = await get(base, `/auth/discord?s=${TOKEN}`);
  return { state: new URL(start.headers.get("location")).searchParams.get("state"), cookie: cookieValue(start, "amora_oauth") };
}

test("the account that owns the session logs in and /api/me returns its token", async () => {
  await withServer({}, async (base, calls) => {
    const { state, cookie } = await startLogin(base);
    const res = await get(base, `/auth/callback?code=abc&state=${state}`, { Cookie: cookie });
    assert.equal(res.status, 302);
    assert.equal(res.headers.get("location"), "/verify");
    assert.deepEqual(calls.exchange, ["abc"]);
    assert.deepEqual(calls.checks.at(-1), { token: TOKEN, userId: USER });

    const session = cookieValue(res, "amora_session");
    assert.ok(session);
    const me = await (await get(base, "/api/me", { Cookie: session })).json();
    assert.deepEqual(me, { loggedIn: true, username: "tester", token: TOKEN });
    assert.deepEqual(await (await get(base, "/api/me")).json(), { loggedIn: false });
  });
});

test("a different Discord account is refused and gets no session cookie", async () => {
  await withServer({ check: { live: true, ownerMatches: false, banned: false }, discordUser: { id: "555555555555555555", username: "friend" } }, async (base) => {
    const { state, cookie } = await startLogin(base);
    const res = await get(base, `/auth/callback?code=abc&state=${state}`, { Cookie: cookie });
    assert.equal(res.headers.get("location"), "/verify?error=wrong_account");
    assert.equal(cookieValue(res, "amora_session"), null);
  });
});

test("banned and expired sessions are refused at login", async () => {
  const cases = [
    [{ live: true, ownerMatches: true, banned: true }, "banned"],
    [{ live: false, ownerMatches: false, banned: false }, "expired"],
  ];
  for (const [check, code] of cases) {
    await withServer({ check }, async (base) => {
      const { state, cookie } = await startLogin(base);
      const res = await get(base, `/auth/callback?code=abc&state=${state}`, { Cookie: cookie });
      assert.equal(res.headers.get("location"), `/verify?error=${code}`);
      assert.equal(cookieValue(res, "amora_session"), null);
    });
  }
});

test("callback rejects a missing cookie, a wrong state and a Discord failure", async () => {
  await withServer({}, async (base, calls) => {
    const { state, cookie } = await startLogin(base);
    const attempts = [
      [`/auth/callback?code=abc&state=${state}`, {}],
      [`/auth/callback?code=abc&state=${"0".repeat(32)}`, { Cookie: cookie }],
      [`/auth/callback?state=${state}`, { Cookie: cookie }],
    ];
    for (const [path, headers] of attempts) {
      const res = await get(base, path, headers);
      assert.equal(res.headers.get("location"), "/verify?error=login_failed");
      assert.equal(cookieValue(res, "amora_session"), null);
    }
    assert.equal(calls.exchange.length, 0);
  });
  await withServer({ exchangeError: true }, async (base) => {
    const { state, cookie } = await startLogin(base);
    const res = await get(base, `/auth/callback?code=abc&state=${state}`, { Cookie: cookie });
    assert.equal(res.headers.get("location"), "/verify?error=login_failed");
  });
});

test("a user who cancels on Discord is told so", async () => {
  await withServer({}, async (base) => {
    const { state, cookie } = await startLogin(base);
    const res = await get(base, `/auth/callback?error=access_denied&state=${state}`, { Cookie: cookie });
    assert.equal(res.headers.get("location"), "/verify?error=denied");
  });
});

test("the portal and the about page link to the official app stores, and /about needs no login", async () => {
  await withServer({}, async (base) => {
    for (const path of ["/verify", "/about"]) {
      const res = await fetch(`${base}${path}`);
      assert.equal(res.status, 200, path);
      const html = await res.text();
      assert.ok(html.includes("https://apps.apple.com/us/app/zkpassport/id6477371975"), `${path} App Store link`);
      assert.ok(html.includes("https://play.google.com/store/apps/details?id=app.zkpassport.zkpassport"), `${path} Google Play link`);
      assert.match(res.headers.get("content-security-policy"), /script-src 'self'/);
    }
    assert.match(await (await fetch(`${base}/verify`)).text(), /href="\/about"/);
  });
});
