import { mountVerifyButton } from "@zkpassport/ui/button";

const status = document.getElementById("status");
const mount = document.getElementById("zkpassport");

function show(message, kind = "info") {
  status.textContent = message;
  status.dataset.kind = kind;
}

const params = new URLSearchParams(window.location.search);
const token = params.get("s");

if (!token || !/^[a-f0-9]{64}$/.test(token)) {
  show("No valid verification session. Run /verify in Discord to get a fresh link.", "error");
} else {
  // Keep the single-use token out of the address bar / history once read.
  window.history.replaceState(null, "", window.location.pathname);

  const config = await (await fetch("/config.json", { cache: "no-store" })).json();
  show("Open the zkPassport app and scan the QR code. Your passport or ID never leaves your phone.");

  mountVerifyButton(mount, {
    purpose: "Prove you are 18 or older for the AMORA Adult Zone. Only a yes/no result is shared.",
    service: { scope: config.scope, devMode: config.devMode },
    query: { age: { min: 18 } },
    bind: { custom_data: token },
    onSuccess: async ({ proofs, result }) => {
      show("Checking your proof...");
      try {
        const response = await fetch("/api/verify", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ token, proofs, queryResult: result }),
        });
        const body = await response.json();
        show(body.message ?? (response.ok ? "Verified." : "Verification failed."), response.ok ? "success" : "error");
        return response.ok;
      } catch {
        show("Could not reach the verifier. Try again.", "error");
        return false;
      }
    },
    onError: (error) => {
      if (error.cancelled) {
        show("Verification cancelled. Reload this page to try again.");
        return;
      }
      show(`Verification failed (${error.kind}). Reload this page to try again.`, "error");
    },
  });
}
