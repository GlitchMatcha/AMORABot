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

/** One-way, pepper-keyed fingerprint of the zkPassport unique identifier. The raw identifier never leaves this service. */
export function fingerprint(pepper, uniqueIdentifier) {
  return createHmac("sha256", pepper).update(`zkpassport-id:${uniqueIdentifier}`).digest("hex");
}
