import { mountVerifyButton } from "@zkpassport/ui/button";

// Injected by scripts/build.js from API_BASE_URL (the verifier API, e.g. https://amora-verifier.onrender.com).
const API = __API_BASE__;
const STORAGE_KEY = "amora_login";

const status = document.getElementById("status");
const mount = document.getElementById("zkpassport");
const login = document.getElementById("login");

function show(message, kind = "info") {
  status.textContent = message;
  status.dataset.kind = kind;
}

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

function startZkPassport({ token, session }, config) {
  mountVerifyButton(mount, {
    purpose: "Prove you are 18 or older for the AMORA Adult Zone. Only a yes/no result is shared.",
    service: { scope: config.scope, devMode: config.devMode },
    query: { age: { min: 18 } },
    // The button's bind option is { data }, which the SDK maps to custom_data; the server checks the same value.
    bind: { data: token },
    onSuccess: async ({ proofs, result }) => {
      show("Checking your proof...");
      try {
        const { status: code, body } = await api("/api/verify", {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${session}` },
          body: JSON.stringify({ proofs, queryResult: result }),
        });
        const ok = code === 200;
        show(body.message ?? (ok ? "Verified." : "Verification failed."), ok ? "success" : "error");
        return ok;
      } catch {
        show("Could not reach the verifier. Try again.", "error");
        return false;
      }
    },
    onError: (err) => {
      if (err.cancelled) {
        show("Verification cancelled. Reload this page to try again.");
        return;
      }
      show(`Verification failed (${err.kind}). Reload this page to try again.`, "error");
    },
  });
}

function showReady(stored, config) {
  show(`Logged in as ${stored.username}. Open the zkPassport app and scan the QR code. Your passport or ID never leaves your phone.`);
  startZkPassport(stored, config);
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

/** Back from Discord: the API checks the Discord account owns the session and hands back a bearer. */
async function finishLogin(code, state, config) {
  const pending = readStored();
  if (!pending?.state || pending.state !== state || !pending.token) return show(ERRORS.login_failed, "error");
  sessionStorage.removeItem(STORAGE_KEY);

  show("Logging you in...");
  let result;
  try {
    result = await api("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ token: pending.token, code }),
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
