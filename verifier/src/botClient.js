import { sign } from "./hmac.js";

const REQUEST_TIMEOUT_MS = 10_000;

/** Signed calls to the bot's InternalApiServer. The bot owns sessions, bans, the database and role changes. */
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
    /**
     * Describes a session without consuming it: `live` (unused, unexpired), `ownerMatches` (the session belongs to
     * `discordUserId`) and `banned` (the owner is on the age-verification ban list).
     */
    async sessionCheck(token, discordUserId) {
      const { status, json } = await post("/internal/age/session-check", { token, discordUserId });
      if (status !== 200) throw new Error(`session-check failed with HTTP ${status}`);
      return { live: json.live === true, ownerMatches: json.ownerMatches === true, banned: json.banned === true };
    },

    /**
     * Consumes the session and marks `discordUserId` verified (the bot enforces that they own the session).
     * Returns "ok" | "duplicate_id" | "invalid_session" | "dev_proof_not_allowed" | "banned".
     */
    async complete(token, idFingerprint, devMode, discordUserId) {
      const { status, json } = await post("/internal/age/complete", { token, idFingerprint, devMode, discordUserId });
      if (status === 200 && json.status === "ok") return "ok";
      if (status === 409) return "duplicate_id";
      if (status === 410) return "invalid_session";
      if (status === 403) return json.status === "banned" ? "banned" : "dev_proof_not_allowed";
      throw new Error(`complete failed with HTTP ${status}`);
    },
  };
}
