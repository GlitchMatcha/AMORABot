import { createLoginHandler } from "../../src/loginFunction.js";

const handler = createLoginHandler();

// Netlify Functions (v2): Request in, Response out. Served at /.netlify/functions/discord-login
export default (request) => handler(request);
