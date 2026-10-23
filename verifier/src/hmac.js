import { createHmac, timingSafeEqual } from "node:crypto";

/** Signature over `${timestampMillis}.${rawBody}`, hex encoded. Mirrored by InternalApiServer.java. */
export function sign(secret, timestampMillis, body) {
  return createHmac("sha256", secret).update(`${timestampMillis}.${body}`).digest("hex");
}

export function signatureMatches(secret, timestampMillis, body, signatureHex) {
  const expected = Buffer.from(sign(secret, timestampMillis, body), "hex");
  const actual = Buffer.from(String(signatureHex), "hex");
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

/** One-way, pepper-keyed fingerprint of the Self nullifier. The raw nullifier never leaves this service. */
export function fingerprint(pepper, nullifier) {
  return createHmac("sha256", pepper).update(`self-nullifier:${nullifier}`).digest("hex");
}
