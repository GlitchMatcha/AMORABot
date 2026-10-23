import { SelfAppBuilder, getUniversalLink } from "@selfxyz/sdk-common";
import qrcode from "qrcode-generator";

// Injected by scripts/build.js from API_BASE_URL (the verifier API, e.g. https://amora-verifier.onrender.com).
const API = __API_BASE__;
const STORAGE_KEY = "amora_login";
const LOGIN_FUNCTION = "/.netlify/functions/discord-login";
const POLL_INTERVAL_MS = 3000;
const CHECK_WINDOW_MS = 15 * 60_000;

const status = document.getElementById("status");
const login = document.getElementById("login");
const check = document.getElementById("check");
const startButton = document.getElementById("start");
const qrPanel = document.getElementById("qr-panel");
const qrImage = document.getElementById("qr");
const openApp = document.getElementById("open-app");

function show(message, kind = "info") {
  status.textContent = message;
  status.dataset.kind = kind;
}

// Before the QR code appears, the visitor reads a short summary and confirms they understood it
// (remembered for this tab).
const UNDERSTOOD_KEY = "amora_understood_zkp";
const gate = document.getElementById("gate");
let afterGate = null;

function installUnderstandGate() {
  document.getElementById("gate-cancel").addEventListener("click", () => gate.close());
  document.getElementById("gate-yes").addEventListener("click", () => {
    sessionStorage.setItem(UNDERSTOOD_KEY, "1");
    gate.close();
    afterGate?.();
  });
}

function whenUnderstood(next) {
  if (sessionStorage.getItem(UNDERSTOOD_KEY) === "1") return next();
  afterGate = next;
  gate.showModal();
}

installUnderstandGate();

const ERRORS = {
  invalid: "That verification link is not valid. Run /verify in Discord for a fresh one.",
  expired: "This link has expired or was already used. Run /verify in Discord for a fresh one.",
  denied: "Discord login was cancelled. Open your link again to retry.",
  login_failed: "Discord login failed. Open your link again to retry.",
  unavailable: "Verification is temporarily unavailable. Try again in a moment.",
  rate_limited: "Too many attempts. Try again in a minute.",
  wrong_account: "This link belongs to a different Discord account. Log in to Discord as the account that ran /verify, then run /verify again.",
  banned: "This account can't use automatic age verification. Please contact staff.",
};

const readStored = () => {
  try {
    return JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? "null");
  } catch {
    return null;
  }
};
const writeStored = (value) => sessionStorage.setItem(STORAGE_KEY, JSON.stringify(value));

async function api(path, init) {
  const response = await fetch(`${API}${path}`, { cache: "no-store", ...init });
  return { status: response.status, body: await response.json().catch(() => ({})) };
}

const authed = (stored) => ({ headers: { Authorization: `Bearer ${stored.session}` } });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function drawQr(text) {
  const qr = qrcode(0, "L");
  qr.addData(text);
  qr.make();
  const svg = qr.createSvgTag({ cellSize: 4, margin: 4, scalable: true });
  qrImage.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}

/** The proof goes phone -> Self relayer -> AMORA verifier, so this page only waits and asks the verifier for the result. */
async function waitForResult(stored) {
  const deadline = Date.now() + CHECK_WINDOW_MS;
  while (Date.now() < deadline) {
    await sleep(POLL_INTERVAL_MS);
    let result;
    try {
      result = await api("/api/status", authed(stored));
    } catch {
      continue; // transient network error: keep waiting
    }
    if (result.status === 401) return show(ERRORS.expired, "error");
    if (result.body.state === "verified") {
      qrPanel.hidden = true;
      return show(result.body.message ?? "Verified. You can return to Discord.", "success");
    }
    if (result.body.state === "rejected") show(`${result.body.message} You can scan the QR code again.`, "error");
  }
  show("This check timed out. Run /verify in Discord for a fresh link.", "error");
}

async function startCheck(stored, config) {
  startButton.hidden = true;
  show("Preparing your QR code...");

  let app;
  try {
    const result = await api("/api/self-app", authed(stored));
    if (result.status === 401) return show(ERRORS.expired, "error");
    if (!result.body.ok) return show(ERRORS.unavailable, "error");
    app = result.body;
  } catch {
    return show(ERRORS.unavailable, "error");
  }

  let link;
  try {
    const selfApp = new SelfAppBuilder({
      version: 2,
      appName: "AMORA 18+",
      scope: config.scope,
      endpoint: config.endpoint,
      endpointType: config.devMode ? "staging_https" : "https",
      userId: app.userId,
      userIdType: "uuid",
      // The verifier trusts this only because userId is a MAC over it (see /api/self/verify).
      userDefinedData: app.userDefinedData,
      disclosures: { minimumAge: 18 },
    }).build();
    link = getUniversalLink(selfApp);
    drawQr(link);
  } catch {
    return show(ERRORS.unavailable, "error");
  }

  openApp.href = link;
  qrPanel.hidden = false;
  show("Open the Self app and scan this QR code (or tap the button if you are on your phone). Your passport or ID never leaves your phone.");
  await waitForResult(stored);
}

function showReady(stored, config) {
  show(`Logged in as ${stored.username}. Press the button when you have your passport or ID card and the Self app ready.`);
  check.hidden = false;
  startButton.hidden = false;
  startButton.onclick = () => whenUnderstood(() => startCheck(stored, config));
}

/** The bot link carries the session token in the URL fragment, which is never sent to any server. */
async function beginLogin(token) {
  const state = Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, "0")).join("");
  writeStored({ state, token });
  const { body } = await api(`/api/discord-url?state=${state}`);
  if (!body.url) return show(ERRORS.unavailable, "error");
  login.href = body.url;
  login.hidden = false;
  show("First, log in with Discord so AMORA knows which account is verifying.");
}

/**
 * Back from Discord. The Netlify login function (same origin) exchanges the code with Discord and returns a signed
 * assertion of the account; the API then checks that account owns the session and hands back a bearer.
 */
async function finishLogin(code, state, config) {
  const pending = readStored();
  if (!pending?.state || pending.state !== state || !pending.token) return show(ERRORS.login_failed, "error");
  sessionStorage.removeItem(STORAGE_KEY);

  show("Logging you in...");
  let result;
  try {
    const discord = await fetch(LOGIN_FUNCTION, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      cache: "no-store",
      body: JSON.stringify({ code }),
    });
    const identity = await discord.json().catch(() => ({}));
    if (!identity.ok) return show(ERRORS[identity.error] ?? ERRORS.login_failed, "error");

    result = await api("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: pending.token, assertion: identity.assertion }),
    });
  } catch {
    return show(ERRORS.unavailable, "error");
  }
  if (!result.body.ok) return show(ERRORS[result.body.error] ?? ERRORS.login_failed, "error");

  const stored = { token: pending.token, session: result.body.session, username: result.body.username };
  writeStored(stored);
  showReady(stored, config);
}

const params = new URLSearchParams(window.location.search);
const fragment = new URLSearchParams(window.location.hash.slice(1));
const token = fragment.get("s");

// Keep tokens, codes and states out of the address bar and history.
window.history.replaceState(null, "", window.location.pathname);

try {
  const config = (await api("/config.json")).body;

  if (params.get("error")) {
    show(params.get("error") === "access_denied" ? ERRORS.denied : ERRORS.login_failed, "error");
  } else if (params.get("code")) {
    await finishLogin(params.get("code"), params.get("state"), config);
  } else if (token && /^[a-f0-9]{64}$/.test(token)) {
    await beginLogin(token);
  } else {
    // A reload after login: the bearer is still in this tab's session storage.
    const stored = readStored();
    if (stored?.session) showReady(stored, config);
    else show("No valid verification session. Run /verify in Discord to get a fresh link.", "error");
  }
} catch {
  show(ERRORS.unavailable, "error");
}
