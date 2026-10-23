import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createApp } from "../src/app.js";
import { fingerprint } from "../src/hmac.js";
import { deriveKey, signPayload, ticketUserId } from "../src/signedTokens.js";

const TOKEN = "a".repeat(64);
const USER = "123456789012345678";
const SITE = "https://portal.test";
const config = {
  scope: "test-scope",
  selfEndpoint: "https://api.test/api/self/verify",
  devMode: false,
  siteOrigin: SITE,
  sharedSecret: "k".repeat(40),
  idHashPepper: "p".repeat(32),
  maxConcurrentVerifications: 2,
  maxBodyBytes: 4096,
  rateLimitPerMinute: 1000,
  relayerRateLimitPerMinute: 1000,
};

const sessionKey = deriveKey(config.sharedSecret, "amora-session-v1");
const ticketKey = deriveKey(config.sharedSecret, "amora-self-ticket-v1");
const bearer = (overrides = {}, key = sessionKey) =>
  `Bearer ${signPayload(key, { t: TOKEN, u: USER, exp: Date.now() + 60_000, ...overrides })}`;

/** userContextData as Self's relayer forwards it: bytes32 chain id, bytes32 user id (a UUID), utf8 user data. */
function contextHex(userDefinedData, userId = ticketUserId(ticketKey, userDefinedData)) {
  return "00".repeat(32) + "00".repeat(16) + userId.replaceAll("-", "") + Buffer.from(userDefinedData).toString("hex");
}

const relayerBody = (overrides = {}) => ({
  attestationId: 1,
  proof: { a: [], b: [], c: [] },
  publicSignals: ["1"],
  userContextData: contextHex(`${TOKEN}.${USER}`),
  ...overrides,
});

async function withServer(overrides, run) {
  const calls = { verify: [], complete: [], checks: [], exchange: [] };
  const check = overrides.check ?? { live: true, ownerMatches: true, banned: false };
  const deps = {
    config: { ...config, ...overrides.config },
    verifyAgeProof: async (input) => {
      calls.verify.push(input);
      return (overrides.verify ?? (() => ({ ok: true, nullifier: "123456789" })))(input);
    },
    bot: {
      sessionCheck: async (token, userId) => {
        calls.checks.push({ token, userId });
        if (overrides.checkError) throw new Error("bot down");
        return typeof check === "function" ? check(calls) : check;
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

const relay = (base, payload = relayerBody(), headers = {}) =>
  fetch(`${base}/api/self/verify`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(payload),
  });
const login = (base, payload, headers = {}) =>
  fetch(`${base}/api/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...headers },
    body: JSON.stringify(payload),
  });
const portalGet = (base, path, authorization = bearer()) =>
  fetch(`${base}${path}`, { headers: authorization ? { Authorization: authorization } : {} });
const status = async (base, authorization) => (await portalGet(base, "/api/status", authorization)).json();

const SUCCESS = { status: "success", result: true };
async function rejection(res) {
  assert.equal(res.status, 200, "Self expects HTTP 200 whatever the outcome");
  const out = await res.json();
  assert.equal(out.status, "error");
  assert.equal(out.result, false);
  return out.reason;
}

// ---- portal endpoints (bearer) ---------------------------------------------------------------------

test("QR parameters and status need a valid signed bearer token", async () => {
  await withServer({}, async (base) => {
    const forgedWithOtherKey = bearer({}, deriveKey("x".repeat(40), "amora-session-v1"));
    for (const authorization of [null, "Bearer garbage", "Basic abc", bearer({ exp: Date.now() - 1 }), forgedWithOtherKey]) {
      for (const path of ["/api/self-app", "/api/status"]) {
        assert.equal((await portalGet(base, path, authorization)).status, 401, `${path} ${authorization}`);
      }
    }
  });
});

test("the QR user id is a UUID MAC bound to this session token and Discord account", async () => {
  await withServer({}, async (base) => {
    const mine = await (await portalGet(base, "/api/self-app")).json();
    assert.equal(mine.ok, true);
    assert.equal(mine.userDefinedData, `${TOKEN}.${USER}`);
    assert.match(mine.userId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);

    const otherUser = await (await portalGet(base, "/api/self-app", bearer({ u: "223456789012345678" }))).json();
    const otherToken = await (await portalGet(base, "/api/self-app", bearer({ t: "b".repeat(64) }))).json();
    assert.notEqual(otherUser.userId, mine.userId);
    assert.notEqual(otherToken.userId, mine.userId);
  });
});

// ---- /api/self/verify (called by Self's relayer) -------------------------------------------------

test("a valid proof completes with the committed session and a pepper-keyed fingerprint, never the raw nullifier", async () => {
  await withServer({}, async (base, calls) => {
    assert.deepEqual(await (await relay(base)).json(), SUCCESS);
    assert.deepEqual(calls.checks, [{ token: TOKEN, userId: USER }]);
    assert.deepEqual(calls.complete, [{ token: TOKEN, fp: fingerprint(config.idHashPepper, "123456789"), devMode: false, userId: USER }]);
    assert.ok(!JSON.stringify(calls.complete).includes("123456789\""));
    assert.equal(calls.verify.length, 1);
    assert.equal(calls.verify[0].attestationId, 1);
  });
});

test("a user id that is not our MAC over the committed data is refused before any lookup", async () => {
  const data = `${TOKEN}.${USER}`;
  const foreignTicket = ticketUserId(ticketKey, `${TOKEN}.999999999999999999`);
  const cases = {
    "random uuid": relayerBody({ userContextData: contextHex(data, "11111111-1111-4111-8111-111111111111") }),
    "ticket of another account": relayerBody({ userContextData: contextHex(data, foreignTicket) }),
    "data swapped under a valid ticket": relayerBody({ userContextData: contextHex(`${TOKEN}.999999999999999999`, ticketUserId(ticketKey, data)) }),
    "ticket made with another key": relayerBody({ userContextData: contextHex(data, ticketUserId(deriveKey("z".repeat(40), "amora-self-ticket-v1"), data)) }),
  };
  for (const [name, payload] of Object.entries(cases)) {
    await withServer({}, async (base, calls) => {
      assert.match(await rejection(await relay(base, payload)), /not issued by the AMORA portal/, name);
      assert.equal(calls.checks.length, 0, name);
      assert.equal(calls.verify.length, 0, name);
      assert.equal(calls.complete.length, 0, name);
    });
  }
});

test("malformed relayer payloads are refused without touching the bot", async () => {
  const data = `${TOKEN}.${USER}`;
  const payloads = [
    {},
    relayerBody({ userContextData: undefined }),
    relayerBody({ userContextData: "zz".repeat(80) }),
    relayerBody({ userContextData: "00".repeat(40) }),
    relayerBody({ userContextData: "00".repeat(200) + "00".repeat(300) }),
    relayerBody({ userContextData: contextHex("not-a-session") }),
    relayerBody({ userContextData: contextHex(`${"g".repeat(64)}.${USER}`) }),
    relayerBody({ userContextData: contextHex(`${TOKEN}.12`) }),
    // user id word must hold a UUID: non-zero high bytes are not ours
    relayerBody({ userContextData: contextHex(data).replace(/^(.{64})0{32}/, "$1" + "f".repeat(32)) }),
  ];
  await withServer({}, async (base, calls) => {
    for (const payload of payloads) await rejection(await relay(base, payload));
    assert.equal(calls.checks.length, 0);
    assert.equal(calls.verify.length, 0);
  });
});

test("expired, used or foreign sessions are refused without running proof verification", async () => {
  for (const check of [
    { live: false, ownerMatches: false, banned: false },
    { live: true, ownerMatches: false, banned: false },
  ]) {
    await withServer({ check }, async (base, calls) => {
      assert.match(await rejection(await relay(base)), /expired or was already used/);
      assert.equal(calls.verify.length, 0);
      assert.equal(calls.complete.length, 0);
    });
  }
});

test("a banned user is refused and no proof verification runs", async () => {
  await withServer({ check: { live: true, ownerMatches: true, banned: true } }, async (base, calls) => {
    assert.match(await rejection(await relay(base)), /can't use automatic age verification/);
    assert.equal(calls.verify.length, 0);
  });
});

test("a bot outage while checking the session is reported, not treated as success", async () => {
  await withServer({ checkError: true }, async (base, calls) => {
    assert.match(await rejection(await relay(base)), /temporarily unavailable/);
    assert.equal(calls.complete.length, 0);
  });
});

test("an invalid proof is rejected and the session is not consumed", async () => {
  await withServer({ verify: () => ({ ok: false, reason: "proof_invalid" }) }, async (base, calls) => {
    assert.equal(await rejection(await relay(base)), "The proof could not be verified.");
    assert.equal(calls.complete.length, 0);
  });
});

test("bot outcomes map to distinct, user-facing reasons", async () => {
  const expected = {
    duplicate_id: /already been used to verify another Discord account/,
    dev_proof_not_allowed: /Test-mode proofs/,
    banned: /can't use automatic age verification/,
    invalid_session: /expired or was already used/,
  };
  for (const [outcome, pattern] of Object.entries(expected)) {
    await withServer({ outcome }, async (base) => {
      assert.match(await rejection(await relay(base)), pattern, outcome);
    });
  }
});

test("the dev-mode flag is forwarded to the bot", async () => {
  await withServer({ config: { devMode: true } }, async (base, calls) => {
    await relay(base);
    assert.equal(calls.complete[0].devMode, true);
  });
});

test("when the verifier is saturated the proof is refused instead of queued", async () => {
  await withServer({ config: { maxConcurrentVerifications: 0 } }, async (base, calls) => {
    assert.match(await rejection(await relay(base)), /busy/);
    assert.equal(calls.verify.length, 0);
  });
});

test("the relayer rate limit is one shared budget, not per forwarded address", async () => {
  await withServer({ config: { relayerRateLimitPerMinute: 2 } }, async (base) => {
    const from = (n) => ({ "X-Forwarded-For": `10.0.0.${n}, 203.0.113.9` });
    assert.deepEqual(await (await relay(base, relayerBody(), from(1))).json(), SUCCESS);
    assert.deepEqual(await (await relay(base, relayerBody(), from(2))).json(), SUCCESS);
    assert.match(await rejection(await relay(base, relayerBody(), from(3))), /Too many requests/);
  });
});

test("oversized and non-JSON relayer bodies are refused", async () => {
  await withServer({}, async (base, calls) => {
    assert.equal((await relay(base, relayerBody({ publicSignals: ["x".repeat(8192)] }))).status, 413);
    const res = await fetch(`${base}/api/self/verify`, { method: "POST", headers: { "Content-Type": "text/plain" }, body: "x" });
    assert.equal(res.status, 415);
    assert.equal(calls.checks.length, 0);
  });
});

// ---- status the portal polls ---------------------------------------------------------------------

test("status goes pending -> verified, and a relayer retry after success cannot mask it", async () => {
  // The first session check sees a live session; any later one sees it burned, as the real bot would.
  const burnedAfterFirst = ({ checks }) =>
    checks.length === 1 ? { live: true, ownerMatches: true, banned: false } : { live: false, ownerMatches: false, banned: false };
  await withServer({ check: burnedAfterFirst }, async (base) => {
    assert.deepEqual(await status(base), { ok: true, state: "pending" });
    assert.deepEqual(await (await relay(base)).json(), SUCCESS);
    const done = { ok: true, state: "verified", message: "Verified. You can return to Discord." };
    assert.deepEqual(await status(base), done);

    await rejection(await relay(base)); // retry: session already burned
    assert.deepEqual(await status(base), done);
  });
});

test("status shows why a scan was rejected, only to the account that owns the QR", async () => {
  await withServer({ outcome: "duplicate_id" }, async (base) => {
    await relay(base);
    const mine = await status(base);
    assert.equal(mine.state, "rejected");
    assert.match(mine.message, /already been used/);

    const someoneElse = await status(base, bearer({ u: "223456789012345678" }));
    assert.deepEqual(someoneElse, { ok: true, state: "pending" });
  });
});

// ---- plumbing ------------------------------------------------------------------------------------

test("only POST is allowed on /api/self/verify and /api/login, only GET on the portal reads", async () => {
  await withServer({}, async (base) => {
    for (const path of ["/api/self/verify", "/api/login"]) {
      const res = await fetch(`${base}${path}`);
      assert.equal(res.status, 405, path);
    }
    for (const path of ["/api/self-app", "/api/status", "/api/verify"]) {
      const res = await fetch(`${base}${path}`, { method: "POST", headers: { Authorization: bearer() } });
      assert.equal(res.status, 404, path);
    }
  });
});

test("config.json exposes scope, relayer endpoint and devMode for the portal", async () => {
  await withServer({}, async (base) => {
    assert.deepEqual(await (await fetch(`${base}/config.json`)).json(), {
      scope: "test-scope",
      endpoint: "https://api.test/api/self/verify",
      devMode: false,
    });
  });
});

// ---- CORS: the portal lives on another origin --------------------------------------------------------

test("the portal origin is allowed and preflights succeed; no credentialed CORS", async () => {
  await withServer({}, async (base) => {
    const pre = await fetch(`${base}/api/self-app`, { method: "OPTIONS", headers: { Origin: SITE, "Access-Control-Request-Method": "GET" } });
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

test("the account that owns the session gets a bearer that unlocks the QR parameters", async () => {
  await withServer({}, async (base, calls) => {
    const res = await login(base, { token: TOKEN, code: "abc" });
    assert.equal(res.status, 200);
    const out = await res.json();
    assert.equal(out.ok, true);
    assert.equal(out.username, "tester");
    assert.deepEqual(calls.exchange, ["abc"]);
    assert.deepEqual(calls.checks.at(-1), { token: TOKEN, userId: USER });

    const app = await (await portalGet(base, "/api/self-app", `Bearer ${out.session}`)).json();
    assert.equal(app.userDefinedData, `${TOKEN}.${USER}`);
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
