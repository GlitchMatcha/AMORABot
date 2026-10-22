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
  const devMode = env.ZK_DEV_MODE === "true";
  if (devMode && env.NODE_ENV === "production") {
    throw new Error("ZK_DEV_MODE accepts mock passports and must not be enabled with NODE_ENV=production");
  }

  // Host the portal is served from (no scheme). Must equal the origin users open: the proof is bound to it.
  const domain = required(env, "VERIFIER_DOMAIN");
  const baseUrl = (env.VERIFIER_BASE_URL?.trim() || `${/^localhost(:\d+)?$/.test(domain) ? "http" : "https"}://${domain}`).replace(/\/+$/, "");

  return {
    port: Number(env.PORT || 8081),
    domain,
    baseUrl,
    // Must be registered exactly in the Discord developer portal (OAuth2 -> Redirects).
    redirectUri: `${baseUrl}/auth/callback`,
    secureCookies: baseUrl.startsWith("https://"),
    // Base URL of the bot's internal API, e.g. https://amora-bot.onrender.com
    botUrl: required(env, "BOT_INTERNAL_URL").replace(/\/+$/, ""),
    sharedSecret: requiredSecret(env, "VERIFIER_SHARED_SECRET"),
    idHashPepper: requiredSecret(env, "ID_HASH_PEPPER"),
    discordClientId: required(env, "DISCORD_CLIENT_ID"),
    discordClientSecret: required(env, "DISCORD_CLIENT_SECRET"),
    scope: (env.ZK_SCOPE || "amora-discord-18plus").trim(),
    devMode,
    maxConcurrentVerifications: Number(env.MAX_CONCURRENT_VERIFICATIONS || 2),
    maxBodyBytes: Number(env.MAX_BODY_BYTES || 8 * 1024 * 1024),
    rateLimitPerMinute: Number(env.RATE_LIMIT_PER_MINUTE || 20),
  };
}
