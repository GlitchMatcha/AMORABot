const API = "https://discord.com/api/v10";
const TIMEOUT_MS = 10_000;

/**
 * Enough of a failed response to tell Discord's own rate limit (JSON, `retry_after`) from a Cloudflare block of a
 * shared host IP (HTML or plain text from `server: cloudflare`). The body is truncated and never contains our secrets.
 */
async function describeFailure(response) {
  const body = (await response.text().catch(() => "")).replace(/\s+/g, " ").slice(0, 200);
  const h = (name) => response.headers.get(name) ?? "-";
  return `HTTP ${response.status} server=${h("server")} retry-after=${h("retry-after")} cf-ray=${h("cf-ray")} type=${h("content-type")} body=${body}`;
}

/**
 * Discord OAuth2 (authorization code, `identify` scope only). The access token is used once to read the
 * user's ID and is never stored.
 */
export function createDiscordAuth({ clientId, clientSecret, redirectUri, fetchImpl = fetch }) {
  return {
    authorizeUrl(state) {
      const url = new URL("https://discord.com/oauth2/authorize");
      url.searchParams.set("client_id", clientId);
      url.searchParams.set("response_type", "code");
      url.searchParams.set("redirect_uri", redirectUri);
      url.searchParams.set("scope", "identify");
      url.searchParams.set("state", state);
      url.searchParams.set("prompt", "none");
      return url.toString();
    },

    /** Exchanges the authorization code and returns `{ id, username }` of the logged-in Discord user. */
    async exchange(code) {
      const tokenResponse = await fetchImpl(`${API}/oauth2/token`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          client_id: clientId,
          client_secret: clientSecret,
          grant_type: "authorization_code",
          code,
          redirect_uri: redirectUri,
        }),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (!tokenResponse.ok) throw new Error(`Discord token exchange failed: ${await describeFailure(tokenResponse)}`);
      const { access_token: accessToken } = await tokenResponse.json();
      if (typeof accessToken !== "string" || !accessToken) throw new Error("Discord returned no access token");

      const meResponse = await fetchImpl(`${API}/users/@me`, {
        headers: { Authorization: `Bearer ${accessToken}` },
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      if (!meResponse.ok) throw new Error(`Discord user lookup failed: ${await describeFailure(meResponse)}`);
      const me = await meResponse.json();
      if (typeof me.id !== "string" || !/^\d{15,25}$/.test(me.id)) throw new Error("Discord returned an invalid user id");
      return { id: me.id, username: String(me.global_name ?? me.username ?? "Discord user").slice(0, 64) };
    },
  };
}
