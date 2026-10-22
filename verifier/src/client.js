import { mountVerifyButton } from "@zkpassport/ui/button";

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
  wrong_account: "This link belongs to a different Discord account. Log in to Discord as the account that ran /verify, then run /verify again.",
  banned: "This account can't use automatic age verification. Please contact staff.",
};

const params = new URLSearchParams(window.location.search);
const error = params.get("error");
const token = params.get("s");

function startZkPassport(sessionToken) {
  mountVerifyButton(mount, {
    purpose: "Prove you are 18 or older for the AMORA Adult Zone. Only a yes/no result is shared.",
    service: { scope: config.scope, devMode: config.devMode },
    query: { age: { min: 18 } },
    // The button's bind option is { data }, which the SDK maps to custom_data; the server checks the same value.
    bind: { data: sessionToken },
    onSuccess: async ({ proofs, result }) => {
      show("Checking your proof...");
      try {
        const response = await fetch("/api/verify", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ proofs, queryResult: result }),
        });
        const body = await response.json();
        show(body.message ?? (response.ok ? "Verified." : "Verification failed."), response.ok ? "success" : "error");
        return response.ok;
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

const config = await (await fetch("/config.json", { cache: "no-store" })).json();

if (error) {
  show(ERRORS[error] ?? ERRORS.login_failed, "error");
} else {
  const me = await (await fetch("/api/me", { cache: "no-store" })).json();
  // A newer link (?s=) than the logged-in session means the cookie is stale (the bot cancels older links): log in again.
  if (me.loggedIn && (!token || token === me.token)) {
    // The session token comes from the signed login cookie; keep it out of the address bar.
    window.history.replaceState(null, "", window.location.pathname);
    show(`Logged in as ${me.username}. Open the zkPassport app and scan the QR code. Your passport or ID never leaves your phone.`);
    startZkPassport(me.token);
  } else if (token && /^[a-f0-9]{64}$/.test(token)) {
    login.href = `/auth/discord?s=${token}`;
    login.hidden = false;
    window.history.replaceState(null, "", window.location.pathname);
    show("First, log in with Discord so AMORA knows which account is verifying.");
  } else {
    show("No valid verification session. Run /verify in Discord to get a fresh link.", "error");
  }
}
