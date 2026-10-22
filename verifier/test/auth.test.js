import { test } from "node:test";
import assert from "node:assert/strict";
import { deriveKey, parseCookies, readPayload, signPayload } from "../src/cookies.js";
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

test("cookie header parsing", () => {
  assert.deepEqual(parseCookies("a=1; b=two=2;  c=3"), { a: "1", b: "two=2", c: "3" });
  assert.deepEqual(parseCookies(undefined), {});
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

test("config requires the Discord OAuth credentials and derives the redirect URI", () => {
  const env = {
    VERIFIER_DOMAIN: "amora-verifier.onrender.com",
    BOT_INTERNAL_URL: "https://bot.test/",
    VERIFIER_SHARED_SECRET: "s".repeat(40),
    ID_HASH_PEPPER: "p".repeat(40),
    DISCORD_CLIENT_ID: "cid",
    DISCORD_CLIENT_SECRET: "csecret",
  };
  const config = loadConfig(env);
  assert.equal(config.redirectUri, "https://amora-verifier.onrender.com/auth/callback");
  assert.equal(config.secureCookies, true);
  assert.equal(config.botUrl, "https://bot.test");

  assert.equal(loadConfig({ ...env, VERIFIER_DOMAIN: "localhost:8081" }).redirectUri, "http://localhost:8081/auth/callback");
  assert.throws(() => loadConfig({ ...env, DISCORD_CLIENT_SECRET: "" }), /DISCORD_CLIENT_SECRET/);
  assert.throws(() => loadConfig({ ...env, DISCORD_CLIENT_ID: undefined }), /DISCORD_CLIENT_ID/);
});
