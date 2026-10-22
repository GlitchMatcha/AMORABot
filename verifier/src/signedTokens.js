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
