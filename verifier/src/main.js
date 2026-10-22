import { createServer } from "node:http";
import { loadConfig } from "./config.js";
import { createApp } from "./app.js";
import { createBotClient } from "./botClient.js";
import { createProofVerifier } from "./proofVerifier.js";

const config = loadConfig();

const app = createApp({
  config,
  verifyAgeProof: createProofVerifier(config),
  bot: createBotClient({ botUrl: config.botUrl, secret: config.sharedSecret }),
  rootDir: new URL("../", import.meta.url),
});

createServer(app).listen(config.port, () => {
  console.log(`AMORA age verifier listening on :${config.port} (domain=${config.domain}, scope=${config.scope}, devMode=${config.devMode})`);
});
