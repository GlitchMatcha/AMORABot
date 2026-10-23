const MIN_SECRET_LENGTH = 32;

function required(env, key) {
  const value = env[key];
  if (!value || !value.trim()) {
    throw new Error(`Missing required environment variable ${key}`);
  }
  return value.trim();
}

function requiredSecret(env, key) {
  const value = required(env, key);
  if (value.length < MIN_SECRET_LENGTH) {
    throw new Error(`${key} must be at least ${MIN_SECRET_LENGTH} characters`);
  }
  return value;
}

export function loadConfig(env = process.env) {
  // Self mock passports (Celo Sepolia). Real passports are rejected while this is on, mock ones accepted.
  const devMode = env.ZK_DEV_MODE === "true";
  if (devMode && env.NODE_ENV === "production") {
    throw new Error("ZK_DEV_MODE accepts mock passports and must not be enabled with NODE_ENV=production");
  }

  // Public URL the static portal (verify.html, about.html, ...) is served from, e.g. https://amora-verify.netlify.app.
  // Discord redirects back to it after login and it is the only CORS origin.
  const siteUrl = new URL(required(env, "SITE_URL"));
  if (siteUrl.protocol !== "https:" && siteUrl.hostname !== "localhost") throw new Error("SITE_URL must be https");
  const site = siteUrl.href.replace(/\/+$/, "");

  // Public origin of THIS API. Self's relayer posts proofs to `${apiUrl}/api/self/verify`, and the proof scope is
  // derived from the host, so changing the host (or ZK_SCOPE) makes every previously stored ID fingerprint unmatchable.
  const apiUrl = new URL(required(env, "API_PUBLIC_URL"));
  if (apiUrl.protocol !== "https:") throw new Error("API_PUBLIC_URL must be https (Self's relayer only calls https endpoints)");

  const scope = (env.ZK_SCOPE || "amora-discord-18plus").trim();
  if (!/^[\x21-\x7e]{1,30}$/.test(scope)) throw new Error("ZK_SCOPE must be 1-30 printable ASCII characters");

  return {
    port: Number(env.PORT || 8081),
    // Value of the CORS Access-Control-Allow-Origin header: the only origin allowed to call this API.
    siteOrigin: siteUrl.origin,
    // Must be registered exactly in the Discord developer portal (OAuth2 -> Redirects).
    redirectUri: `${site}/verify.html`,
    // The endpoint Self's relayer calls with the proof. Also what the portal puts in the QR code.
    selfEndpoint: `${apiUrl.origin}/api/self/verify`,
    // Base URL of the bot's internal API, e.g. https://amora-bot.onrender.com
    botUrl: required(env, "BOT_INTERNAL_URL").replace(/\/+$/, ""),
    sharedSecret: requiredSecret(env, "VERIFIER_SHARED_SECRET"),
    idHashPepper: requiredSecret(env, "ID_HASH_PEPPER"),
    discordClientId: required(env, "DISCORD_CLIENT_ID"),
    discordClientSecret: required(env, "DISCORD_CLIENT_SECRET"),
    scope,
    devMode,
    maxConcurrentVerifications: Number(env.MAX_CONCURRENT_VERIFICATIONS || 2),
    maxBodyBytes: Number(env.MAX_BODY_BYTES || 64 * 1024),
    rateLimitPerMinute: Number(env.RATE_LIMIT_PER_MINUTE || 20),
    // Every user's proof reaches us from Self's relayer addresses, so this is one shared budget, not per user.
    relayerRateLimitPerMinute: Number(env.RELAYER_RATE_LIMIT_PER_MINUTE || 120),
  };
}
