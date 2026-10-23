import { createServer } from "node:http";
import { loadConfig } from "./config.js";
import { createApp } from "./app.js";
import { createBotClient } from "./botClient.js";
import { createDiscordAuth } from "./discordAuth.js";
import { createProofVerifier } from "./proofVerifier.js";

const config = loadConfig();

if (config.devMode) {
  console.warn("WARNING: ZK_DEV_MODE is ON. Self mock passports are accepted and ANYONE can verify as 18+. Unset it before real use.");
}

const app = createApp({
  config,
  verifyAgeProof: createProofVerifier({ scope: config.scope, endpoint: config.selfEndpoint, devMode: config.devMode }),
  bot: createBotClient({ botUrl: config.botUrl, secret: config.sharedSecret }),
  discordAuth: createDiscordAuth({
    clientId: config.discordClientId,
    redirectUri: config.redirectUri,
  }),
});

createServer(app).listen(config.port, () => {
  console.log(`AMORA age verifier API listening on :${config.port} (site=${config.siteOrigin}, scope=${config.scope}, devMode=${config.devMode})`);
  console.log(`Self relayer endpoint (proofs are bound to this host): ${config.selfEndpoint}`);
  console.log(`Discord OAuth redirect URI (register this exactly): ${config.redirectUri}`);
});
