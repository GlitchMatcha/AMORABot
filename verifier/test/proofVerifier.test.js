import { test } from "node:test";
import assert from "node:assert/strict";
import { createProofVerifier } from "../src/proofVerifier.js";
import { createBotClient } from "../src/botClient.js";
import { signatureMatches } from "../src/hmac.js";

const TOKEN = "b".repeat(64);
const ageResult = { age: { gte: { expected: 18, result: true } } };

// The exact payload the removed index.html used to send (plus the age claim our pre-check requires,
// so the request reaches the real SDK). It must never verify.
const OLD_FAKE_PAYLOAD = {
  proofs: [
    {
      pi_a: ["0x25d8...", "0x09f4..."],
      pi_b: [["0x12a...", "0x34b..."], ["0x56c...", "0x78d..."]],
      pi_c: ["0x90e...", "0x12f..."],
      protocol: "groth16",
    },
  ],
  queryResult: { ...ageResult, publicSignals: ["1"] },
};

test("real SDK rejects the fabricated proof the old portal used to send", async () => {
  const verify = createProofVerifier({ domain: "verifier.test", scope: "s", devMode: false });
  const result = await verify({ token: TOKEN, ...OLD_FAKE_PAYLOAD });
  // Must get past our own age pre-check and be refused by the SDK itself.
  assert.equal(result.ok, false);
  assert.ok(["proof_error", "proof_invalid"].includes(result.reason), `unexpected reason ${result.reason}`);
});

test("real SDK rejects a well-formed claim carrying garbage proofs", async () => {
  const verify = createProofVerifier({ domain: "verifier.test", scope: "s", devMode: false });
  const result = await verify({
    token: TOKEN,
    proofs: [{ name: "outer", proof: "00", vkeyHash: "00", version: "0.0.0", publicInputs: [] }],
    queryResult: ageResult,
  });
  assert.equal(result.ok, false);
  assert.ok(["proof_error", "proof_invalid"].includes(result.reason), `unexpected reason ${result.reason}`);
});

test("claimed age below 18 or unproven is rejected without calling the SDK", async () => {
  const zkPassport = { createQuery: () => assert.fail("must not build a query"), verify: () => assert.fail("must not verify") };
  const verify = createProofVerifier({ domain: "d", scope: "s", devMode: false, zkPassport });
  for (const queryResult of [
    { age: { gte: { expected: 16, result: true } } },
    { age: { gte: { expected: 18, result: false } } },
    {},
  ]) {
    assert.equal((await verify({ token: TOKEN, proofs: [{}], queryResult })).reason, "age_not_proven");
  }
});

test("server builds the query itself: age>=18, session token bound, local verification, fixed scope", async () => {
  const seen = {};
  const zkPassport = {
    createQuery: () => {
      const builder = {
        gte: (field, value) => ((seen.gte = [field, value]), builder),
        bind: (key, value) => ((seen.bind = [key, value]), builder),
        done: () => ({ query: { marker: "server-built" } }),
      };
      return builder;
    },
    verify: async (args) => {
      seen.args = args;
      return { verified: true, uniqueIdentifier: "uid" };
    },
  };
  const verify = createProofVerifier({ domain: "d", scope: "fixed-scope", devMode: false, zkPassport });
  const result = await verify({ token: TOKEN, proofs: [{ p: 1 }], queryResult: ageResult });

  assert.deepEqual(result, { ok: true, uniqueIdentifier: "uid" });
  assert.deepEqual(seen.gte, ["age", 18]);
  assert.deepEqual(seen.bind, ["custom_data", TOKEN]);
  assert.equal(seen.args.originalQuery.marker, "server-built");
  assert.equal(seen.args.verifierMode, "local");
  assert.equal(seen.args.scope, "fixed-scope");
  assert.equal(seen.args.devMode, false);
});

test("a verified proof without a unique identifier is rejected", async () => {
  const zkPassport = {
    createQuery: () => {
      const b = { gte: () => b, bind: () => b, done: () => ({ query: {} }) };
      return b;
    },
    verify: async () => ({ verified: true, uniqueIdentifier: undefined }),
  };
  const verify = createProofVerifier({ domain: "d", scope: "s", devMode: false, zkPassport });
  assert.deepEqual(await verify({ token: TOKEN, proofs: [{}], queryResult: ageResult }), { ok: false, reason: "no_unique_identifier" });
});

test("bot client signs `${timestamp}.${body}` so the bot's HMAC check passes", async () => {
  let captured;
  const client = createBotClient({
    botUrl: "https://bot.test",
    secret: "s".repeat(32),
    now: () => 1_700_000_000_000,
    fetchImpl: async (url, init) => {
      captured = { url, init };
      return new Response(JSON.stringify({ live: true }), { status: 200 });
    },
  });
  assert.equal(await client.sessionLive(TOKEN), true);
  assert.equal(captured.url, "https://bot.test/internal/age/session-check");
  const { "X-Amora-Timestamp": ts, "X-Amora-Signature": sig } = captured.init.headers;
  assert.equal(ts, "1700000000000");
  assert.ok(signatureMatches("s".repeat(32), ts, captured.init.body, sig));
  assert.ok(!signatureMatches("t".repeat(32), ts, captured.init.body, sig));
});
