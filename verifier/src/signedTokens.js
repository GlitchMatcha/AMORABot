import { createHmac, timingSafeEqual } from "node:crypto";

/** Per-purpose HMAC key derived from the shared secret, so token signatures can't be mixed up. */
export function deriveKey(secret, label) {
  return createHmac("sha256", secret).update(label).digest();
}

/** `base64url(json).base64url(hmac)`; the payload must carry `exp` (epoch millis). */
export function signPayload(key, payload) {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const sig = createHmac("sha256", key).update(body).digest("base64url");
  return `${body}.${sig}`;
}

/** Returns the payload when the signature is valid and unexpired, otherwise null. */
export function readPayload(key, value, now = Date.now()) {
  if (typeof value !== "string") return null;
  const parts = value.split(".");
  if (parts.length !== 2 || !parts[0] || !parts[1]) return null;

  const expected = createHmac("sha256", key).update(parts[0]).digest();
  const actual = Buffer.from(parts[1], "base64url");
  if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) return null;

  let payload;
  try {
    payload = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
  } catch {
    return null;
  }
  if (typeof payload?.exp !== "number" || payload.exp < now) return null;
  return payload;
}

/**
 * The `userId` the portal puts in a Self QR code, formatted as a UUID (the only shape Self accepts besides an EVM
 * address). It is a MAC over the proof's `userDefinedData`, so only a visitor who logged in with Discord as the session
 * owner can obtain a QR whose proof this service will accept for that session. 122 bits survive the version/variant bits.
 */
export function ticketUserId(key, userDefinedData) {
  const mac = createHmac("sha256", key).update(userDefinedData).digest().subarray(0, 16);
  mac[6] = (mac[6] & 0x0f) | 0x40;
  mac[8] = (mac[8] & 0x3f) | 0x80;
  const hex = mac.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function ticketMatches(key, userDefinedData, userId) {
  const expected = Buffer.from(ticketUserId(key, userDefinedData));
  const actual = Buffer.from(String(userId));
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}
