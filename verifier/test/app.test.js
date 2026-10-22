import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createApp } from "../src/app.js";
import { fingerprint } from "../src/hmac.js";

const TOKEN = "a".repeat(64);
const config = {
  scope: "test-scope",
  devMode: false,
  idHashPepper: "p".repeat(32),
  maxConcurrentVerifications: 2,
  maxBodyBytes: 1024,
  rateLimitPerMinute: 1000,
};

async function withServer(overrides, run) {
  const calls = { verify: 0, complete: [], sessionLive: 0 };
  const deps = {
    config: { ...config, ...overrides.config },
    rootDir: new URL("../", import.meta.url),
    verifyAgeProof: async (input) => {
      calls.verify++;
      return (overrides.verify ?? (() => ({ ok: true, uniqueIdentifier: "raw-id-123" })))(input);
    },
    bot: {
      sessionLive: async () => {
        calls.sessionLive++;
        return overrides.live ?? true;
      },
      complete: async (token, fp) => {
        calls.complete.push({ token, fp });
        return overrides.outcome ?? "ok";
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

const post = (base, body, headers = {}) =>
  fetch(`${base}/api/verify`, { method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: JSON.stringify(body) });

const goodBody = { token: TOKEN, proofs: [{}], queryResult: {} };

test("rejects a malformed token before touching the bot or verifier", async () => {
  await withServer({}, async (base, calls) => {
    for (const token of ["short", "A".repeat(64), `${"a".repeat(63)}g`, 42, undefined]) {
      const res = await post(base, { ...goodBody, token });
      assert.equal(res.status, 400);
    }
    assert.equal(calls.sessionLive, 0);
    assert.equal(calls.verify, 0);
  });
});

test("expired or used session returns 410 and never runs proof verification", async () => {
  await withServer({ live: false }, async (base, calls) => {
    const res = await post(base, goodBody);
    assert.equal(res.status, 410);
    assert.equal(calls.verify, 0);
    assert.equal(calls.complete.length, 0);
  });
});

test("an invalid proof is rejected and the session is not consumed", async () => {
  await withServer({ verify: () => ({ ok: false, reason: "proof_invalid" }) }, async (base, calls) => {
    const res = await post(base, goodBody);
    assert.equal(res.status, 400);
    assert.equal(calls.complete.length, 0);
  });
});

test("a valid proof completes with a pepper-keyed fingerprint, never the raw identifier", async () => {
  await withServer({}, async (base, calls) => {
    const res = await post(base, goodBody);
    assert.equal(res.status, 200);
    assert.deepEqual(calls.complete, [{ token: TOKEN, fp: fingerprint(config.idHashPepper, "raw-id-123") }]);
    assert.ok(!JSON.stringify(calls.complete).includes("raw-id-123"));
  });
});

test("the same ID on a second account yields 409", async () => {
  await withServer({ outcome: "duplicate_id" }, async (base) => {
    assert.equal((await post(base, goodBody)).status, 409);
  });
});

test("oversized bodies are refused with 413", async () => {
  await withServer({}, async (base, calls) => {
    const res = await post(base, { ...goodBody, proofs: ["x".repeat(4096)] });
    assert.equal(res.status, 413);
    assert.equal(calls.sessionLive, 0);
  });
});

test("rate limit applies per client address (last X-Forwarded-For entry)", async () => {
  await withServer({ config: { rateLimitPerMinute: 2 } }, async (base) => {
    const spoofed = (n) => ({ "X-Forwarded-For": `10.0.0.${n}, 203.0.113.9` });
    assert.equal((await post(base, goodBody, spoofed(1))).status, 200);
    assert.equal((await post(base, goodBody, spoofed(2))).status, 200);
    assert.equal((await post(base, goodBody, spoofed(3))).status, 429);
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
