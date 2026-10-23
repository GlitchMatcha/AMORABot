import { test } from "node:test";
import assert from "node:assert/strict";
import { deriveKey, readPayload, signPayload, ticketMatches, ticketUserId } from "../src/signedTokens.js";
import { createDiscordAuth } from "../src/discordAuth.js";
import { loadConfig } from "../src/config.js";

const key = deriveKey("s".repeat(40), "label");

test("signed payloads round-trip and reject tampering, other keys and expiry", () => {
  const value = signPayload(key, { u: "1", exp: 2_000 });
  assert.deepEqual(readPayload(key, value, 1_000), { u: "1", exp: 2_000 });
  assert.equal(readPayload(key, value, 2_001), null);
  assert.equal(readPayload(deriveKey("s".repeat(40), "other-label"), value, 1_000), null);

  const [body, sig] = value.split(".");
  const forged = Buffer.from(JSON.stringify({ u: "2", exp: 2_000 })).toString("base64url");
  assert.equal(readPayload(key, `${forged}.${sig}`, 1_000), null);
  assert.equal(readPayload(key, `${body}.${sig}.x`, 1_000), null);
  assert.equal(readPayload(key, undefined, 1_000), null);
  assert.equal(readPayload(key, signPayload(key, { u: "1" }), 1_000), null, "payloads without exp are refused");
});

test("Discord authorize URL requests only the identify scope with our state and redirect", () => {
  const auth = createDiscordAuth({ clientId: "cid", clientSecret: "secret", redirectUri: "https://v.test/auth/callback" });
  const url = new URL(auth.authorizeUrl("nonce123"));
  assert.equal(url.origin + url.pathname, "https://discord.com/oauth2/authorize");
  assert.equal(url.searchParams.get("scope"), "identify");
  assert.equal(url.searchParams.get("state"), "nonce123");
  assert.equal(url.searchParams.get("redirect_uri"), "https://v.test/auth/callback");
  assert.equal(url.searchParams.get("client_id"), "cid");
  assert.equal(url.searchParams.get("response_type"), "code");
});

test("code exchange posts credentials, then reads only the user id with the bearer token", async () => {
  const requests = [];
  const fetchImpl = async (url, init) => {
    requests.push({ url, init });
    if (String(url).endsWith("/oauth2/token")) return new Response(JSON.stringify({ access_token: "tok" }), { status: 200 });
    return new Response(JSON.stringify({ id: "123456789012345678", username: "u", global_name: "Display" }), { status: 200 });
  };
  const auth = createDiscordAuth({ clientId: "cid", clientSecret: "secret", redirectUri: "https://v.test/auth/callback", fetchImpl });

  assert.deepEqual(await auth.exchange("the-code"), { id: "123456789012345678", username: "Display" });
  const form = new URLSearchParams(requests[0].init.body);
  assert.equal(form.get("grant_type"), "authorization_code");
  assert.equal(form.get("code"), "the-code");
  assert.equal(form.get("client_secret"), "secret");
  assert.equal(requests[1].init.headers.Authorization, "Bearer tok");
});

test("code exchange fails on Discord errors and on a malformed user id", async () => {
  const failing = createDiscordAuth({ clientId: "c", clientSecret: "s", redirectUri: "r", fetchImpl: async () => new Response("no", { status: 400 }) });
  await assert.rejects(() => failing.exchange("x"));

  const badId = createDiscordAuth({
    clientId: "c",
    clientSecret: "s",
    redirectUri: "r",
    fetchImpl: async (url) =>
      String(url).endsWith("/oauth2/token")
        ? new Response(JSON.stringify({ access_token: "t" }), { status: 200 })
        : new Response(JSON.stringify({ id: "not-a-snowflake" }), { status: 200 }),
  });
  await assert.rejects(() => badId.exchange("x"));
});

test("config derives the CORS origin, Discord redirect and Self relayer endpoint from the public URLs", () => {
  const env = {
    SITE_URL: "https://amora-verify.netlify.app/",
    API_PUBLIC_URL: "https://amora-verifier.onrender.com/",
    BOT_INTERNAL_URL: "https://bot.test/",
    VERIFIER_SHARED_SECRET: "s".repeat(40),
    ID_HASH_PEPPER: "p".repeat(40),
    DISCORD_CLIENT_ID: "cid",
    DISCORD_CLIENT_SECRET: "csecret",
  };
  const config = loadConfig(env);
  assert.equal(config.siteOrigin, "https://amora-verify.netlify.app");
  assert.equal(config.redirectUri, "https://amora-verify.netlify.app/verify.html");
  assert.equal(config.selfEndpoint, "https://amora-verifier.onrender.com/api/self/verify");
  assert.equal(config.botUrl, "https://bot.test");
  assert.equal(config.scope, "amora-discord-18plus");

  assert.equal(loadConfig({ ...env, SITE_URL: "http://localhost:8082" }).redirectUri, "http://localhost:8082/verify.html");
  assert.throws(() => loadConfig({ ...env, SITE_URL: "http://portal.test" }), /https/);
  assert.throws(() => loadConfig({ ...env, SITE_URL: undefined }), /SITE_URL/);
  assert.throws(() => loadConfig({ ...env, API_PUBLIC_URL: undefined }), /API_PUBLIC_URL/);
  assert.throws(() => loadConfig({ ...env, API_PUBLIC_URL: "http://api.test" }), /https/);
  assert.throws(() => loadConfig({ ...env, ZK_SCOPE: "x".repeat(31) }), /ZK_SCOPE/);
  assert.throws(() => loadConfig({ ...env, ZK_SCOPE: "has space" }), /ZK_SCOPE/);
  assert.throws(() => loadConfig({ ...env, ZK_DEV_MODE: "true", NODE_ENV: "production" }), /ZK_DEV_MODE/);
  assert.throws(() => loadConfig({ ...env, DISCORD_CLIENT_SECRET: "" }), /DISCORD_CLIENT_SECRET/);
  assert.throws(() => loadConfig({ ...env, DISCORD_CLIENT_ID: undefined }), /DISCORD_CLIENT_ID/);
});

test("ticket user ids are valid UUIDs, deterministic per input, and checked in constant shape", () => {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  const id = ticketUserId(key, "token.123");
  assert.match(id, uuid);
  assert.equal(ticketUserId(key, "token.123"), id);
  assert.notEqual(ticketUserId(key, "token.124"), id);
  assert.notEqual(ticketUserId(deriveKey("s".repeat(40), "other"), "token.123"), id);
  assert.equal(ticketMatches(key, "token.123", id), true);
  assert.equal(ticketMatches(key, "token.124", id), false);
  assert.equal(ticketMatches(key, "token.123", id.slice(1)), false);
  assert.equal(ticketMatches(key, "token.123", undefined), false);
});
