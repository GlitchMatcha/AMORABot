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

  // Public URL the static portal (verify.html, about.html, ...) is served from, e.g. https://user.github.io/AMORABot.
  // The zkPassport proof is bound to its hostname and Discord redirects back to it after login.
  const siteUrl = new URL(required(env, "SITE_URL"));
  if (siteUrl.protocol !== "https:" && siteUrl.hostname !== "localhost") throw new Error("SITE_URL must be https");
  const site = siteUrl.href.replace(/\/+$/, "");

  return {
    port: Number(env.PORT || 8081),
    domain: siteUrl.hostname,
    // Value of the CORS Access-Control-Allow-Origin header: the only origin allowed to call this API.
    siteOrigin: siteUrl.origin,
    // Must be registered exactly in the Discord developer portal (OAuth2 -> Redirects).
    redirectUri: `${site}/verify.html`,
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
