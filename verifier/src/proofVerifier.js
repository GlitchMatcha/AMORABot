import { createRequire } from "node:module";

// The SDK's ESM entry does a directory import ("buffer") that Node's ESM loader rejects; its CJS build loads fine.
const { ZKPassport } = createRequire(import.meta.url)("@zkpassport/sdk");

const MIN_AGE = 18;

/**
 * Verifies a zkPassport age proof locally (no third-party verifier API).
 *
 * The query is built here, never taken from the browser, and the session token is committed into the
 * proof via bind(custom_data). A proof generated for one session therefore fails for any other.
 */
export function createProofVerifier({ domain, scope, devMode, zkPassport = new ZKPassport(domain) }) {
  return async function verifyAgeProof({ token, proofs, queryResult }) {
    if (!Array.isArray(proofs) || proofs.length === 0 || typeof queryResult !== "object" || queryResult === null) {
      return { ok: false, reason: "malformed" };
    }

    const gte = queryResult.age?.gte;
    if (gte?.expected !== MIN_AGE || gte?.result !== true) {
      return { ok: false, reason: "age_not_proven" };
    }

    const { query } = zkPassport.createQuery().gte("age", MIN_AGE).bind("custom_data", token).done();

    let outcome;
    try {
      outcome = await zkPassport.verify({
        proofs,
        originalQuery: query,
        queryResult,
        scope,
        devMode,
        verifierMode: "local",
      });
    } catch {
      return { ok: false, reason: "proof_error" };
    }

    if (!outcome.verified) return { ok: false, reason: "proof_invalid" };
    if (!outcome.uniqueIdentifier) return { ok: false, reason: "no_unique_identifier" };
    return { ok: true, uniqueIdentifier: outcome.uniqueIdentifier };
  };
}
