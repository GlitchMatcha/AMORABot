import { ATTESTATION_ID, DefaultConfigStore, SelfBackendVerifier } from "@selfxyz/core";
import { parseUserContext } from "./userContext.js";

export const MIN_AGE = 18;

// Documents whose chip signature is checked against a government certificate. Aadhaar and Self's own KYC
// attestations are deliberately left out.
export const ALLOWED_ATTESTATION_IDS = [ATTESTATION_ID.PASSPORT, ATTESTATION_ID.BIOMETRIC_ID_CARD];

function describeIssues(err) {
  if (Array.isArray(err?.issues)) return err.issues.map((issue) => issue.type ?? issue.message).join(",");
  return String(err?.stack ?? err?.message ?? err);
}

/**
 * Verifies a Self age proof: the Groth16 proof is checked by the Self verifier contract on Celo (through a public RPC),
 * the scope and the minimum age are checked against this service's own configuration, never the proof sender's.
 *
 * `scope` and `endpoint` must equal what the portal gives the Self app; the proof is bound to both.
 */
export function createProofVerifier({
  scope,
  endpoint,
  devMode,
  backend = new SelfBackendVerifier(
    scope,
    endpoint,
    devMode,
    new Map(ALLOWED_ATTESTATION_IDS.map((id) => [id, true])),
    new DefaultConfigStore({ minimumAge: MIN_AGE, excludedCountries: [], ofac: false }),
    "uuid",
  ),
}) {
  return async function verifyAgeProof({ attestationId, proof, publicSignals, userContextData }) {
    if (!ALLOWED_ATTESTATION_IDS.includes(attestationId)) return { ok: false, reason: "document_not_allowed" };
    const context = parseUserContext(userContextData);
    const wellFormed =
      context &&
      Array.isArray(publicSignals) &&
      publicSignals.length > 0 &&
      publicSignals.length < 128 &&
      Array.isArray(proof?.a) &&
      Array.isArray(proof?.b) &&
      Array.isArray(proof?.c);
    if (!wellFormed) return { ok: false, reason: "malformed" };

    let result;
    try {
      result = await backend.verify(attestationId, proof, publicSignals, userContextData);
    } catch (err) {
      return { ok: false, reason: "proof_error", detail: describeIssues(err).slice(0, 800) };
    }

    if (!result.isValidDetails?.isValid) return { ok: false, reason: "proof_invalid" };

    const provenAge = Number.parseInt(result.discloseOutput?.minimumAge, 10);
    if (!result.isValidDetails.isMinimumAgeValid || !(provenAge >= MIN_AGE)) {
      return { ok: false, reason: "age_not_proven", detail: String(result.discloseOutput?.minimumAge) };
    }

    // The SDK decoded the same bytes we did; a disagreement means the two parsers drifted apart.
    if (result.userData?.userIdentifier?.toLowerCase() !== context.userId) return { ok: false, reason: "context_mismatch" };

    const nullifier = result.discloseOutput?.nullifier;
    if (typeof nullifier !== "string" || !/^(\d+|0x[0-9a-f]+)$/i.test(nullifier)) return { ok: false, reason: "no_nullifier" };

    return { ok: true, nullifier };
  };
}
