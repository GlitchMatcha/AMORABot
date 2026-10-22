import { sign } from "./hmac.js";

const REQUEST_TIMEOUT_MS = 10_000;

/** Signed calls to the bot's InternalApiServer. The bot owns sessions, the database and role changes. */
export function createBotClient({ botUrl, secret, fetchImpl = fetch, now = Date.now }) {
  async function post(path, payload) {
    const body = JSON.stringify(payload);
    const timestamp = String(now());
    const response = await fetchImpl(`${botUrl}${path}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Amora-Timestamp": timestamp,
        "X-Amora-Signature": sign(secret, timestamp, body),
      },
      body,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    let json = {};
    try {
      json = await response.json();
    } catch {
      // non-JSON body: fall through with the HTTP status alone
    }
    return { status: response.status, json };
  }

  return {
    /** True when the token maps to an unused, unexpired session. Does not consume it. */
    async sessionLive(token) {
      const { status, json } = await post("/internal/age/session-check", { token });
      if (status !== 200) throw new Error(`session-check failed with HTTP ${status}`);
      return json.live === true;
    },

    /** Consumes the session and marks the owner verified. Returns "ok" | "duplicate_id" | "invalid_session". */
    async complete(token, idFingerprint) {
      const { status, json } = await post("/internal/age/complete", { token, idFingerprint });
      if (status === 200 && json.status === "ok") return "ok";
      if (status === 409) return "duplicate_id";
      if (status === 410) return "invalid_session";
      throw new Error(`complete failed with HTTP ${status}`);
    },
  };
}
