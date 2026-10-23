import { test } from "node:test";
import assert from "node:assert/strict";
import { getSolidityPackedUserContextData } from "@selfxyz/common/utils/hash";
import { ALLOWED_ATTESTATION_IDS, createProofVerifier } from "../src/proofVerifier.js";
import { parseUserContext } from "../src/userContext.js";

const USER_ID = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
const DATA = `${"a".repeat(64)}.123456789012345678`;
const userContextData = getSolidityPackedUserContextData(42220, USER_ID, DATA).slice(2);

const proof = { a: ["1", "2"], b: [["1", "2"], ["3", "4"]], c: ["1", "2"] };
const input = { attestationId: 1, proof, publicSignals: ["1", "2"], userContextData };

const goodResult = () => ({
  isValidDetails: { isValid: true, isMinimumAgeValid: true, isOfacValid: false },
  discloseOutput: { minimumAge: "18", nullifier: "987654321" },
  userData: { userIdentifier: USER_ID },
});
const verifierWith = (backend) => createProofVerifier({ scope: "s", endpoint: "https://api.test/api/self/verify", devMode: true, backend });

test("the context parser agrees with Self's own packing of chain id, user id and user data", () => {
  assert.deepEqual(parseUserContext(userContextData), { userId: USER_ID, userDefinedData: DATA });
  assert.deepEqual(parseUserContext(userContextData.toUpperCase()), { userId: USER_ID, userDefinedData: DATA });
});

test("real SDK refuses a fabricated proof with full-size public signals", async () => {
  const verify = createProofVerifier({ scope: "amora-test", endpoint: "https://api.test/api/self/verify", devMode: true });
  const result = await verify({ ...input, publicSignals: new Array(30).fill("1") });
  // Online the SDK reports context/scope/root mismatches; offline it cannot reach the Celo registry. Never ok either way.
  assert.equal(result.ok, false);
  assert.equal(result.reason, "proof_error");
});

test("only passports and biometric ID cards are accepted", async () => {
  assert.deepEqual(ALLOWED_ATTESTATION_IDS, [1, 2]);
  const verify = verifierWith({ verify: () => assert.fail("must not verify") });
  for (const attestationId of [0, 3, 4, "1", undefined]) {
    assert.deepEqual(await verify({ ...input, attestationId }), { ok: false, reason: "document_not_allowed" });
  }
});

test("malformed input is rejected before the SDK runs", async () => {
  const verify = verifierWith({ verify: () => assert.fail("must not verify") });
  for (const bad of [
    { userContextData: "00" },
    { publicSignals: [] },
    { publicSignals: "1" },
    { publicSignals: new Array(200).fill("1") },
    { proof: undefined },
    { proof: { a: [], b: [] } },
  ]) {
    assert.deepEqual(await verify({ ...input, ...bad }), { ok: false, reason: "malformed" });
  }
});

test("a valid proof yields only the nullifier and passes the exact arguments to the SDK", async () => {
  let seen;
  const verify = verifierWith({
    verify: async (...args) => ((seen = args), goodResult()),
  });
  assert.deepEqual(await verify(input), { ok: true, nullifier: "987654321" });
  assert.deepEqual(seen, [1, proof, ["1", "2"], userContextData]);
});

test("SDK rejections surface as proof_error with the mismatch types, never ok", async () => {
  const mismatch = Object.assign(new Error("config mismatch"), { name: "ConfigMismatchError", issues: [{ type: "InvalidScope" }, { type: "InvalidRoot" }] });
  const verify = verifierWith({ verify: async () => { throw mismatch; } });
  assert.deepEqual(await verify(input), { ok: false, reason: "proof_error", detail: "InvalidScope,InvalidRoot" });
});

test("an invalid proof is rejected even when the age flags look fine", async () => {
  const verify = verifierWith({
    verify: async () => ({ ...goodResult(), isValidDetails: { isValid: false, isMinimumAgeValid: true } }),
  });
  assert.deepEqual(await verify(input), { ok: false, reason: "proof_invalid" });
});

test("anything short of a proven age of 18 is rejected", async () => {
  for (const [flag, minimumAge] of [[false, "18"], [true, "00"], [true, "17"], [true, ""], [true, undefined]]) {
    const verify = verifierWith({
      verify: async () => ({
        ...goodResult(),
        isValidDetails: { isValid: true, isMinimumAgeValid: flag },
        discloseOutput: { minimumAge, nullifier: "1" },
      }),
    });
    const result = await verify(input);
    assert.equal(result.ok, false, `${flag}/${minimumAge}`);
    assert.equal(result.reason, "age_not_proven");
  }
});

test("a proof whose user id differs from the one we parsed is rejected", async () => {
  const verify = verifierWith({
    verify: async () => ({ ...goodResult(), userData: { userIdentifier: "11111111-1111-4111-8111-111111111111" } }),
  });
  assert.deepEqual(await verify(input), { ok: false, reason: "context_mismatch" });
});

test("a verified proof without a usable nullifier is rejected", async () => {
  for (const nullifier of [undefined, "", "abc", "12 3"]) {
    const verify = verifierWith({
      verify: async () => ({ ...goodResult(), discloseOutput: { minimumAge: "18", nullifier } }),
    });
    assert.deepEqual(await verify(input), { ok: false, reason: "no_nullifier" }, String(nullifier));
  }
});
