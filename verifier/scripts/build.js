import { build } from "esbuild";
import { cp, mkdir, rm } from "node:fs/promises";

// Builds the static portal (deployed to GitHub Pages) into dist/site. The only build-time input is the
// public URL of the verifier API the page calls, e.g. https://amora-verifier.onrender.com
const apiBase = (process.env.API_BASE_URL ?? "").trim().replace(/\/+$/, "");
if (!/^https?:\/\/[^/]+$/.test(apiBase)) {
  throw new Error("Set API_BASE_URL to the verifier API origin, e.g. https://amora-verifier.onrender.com");
}

await rm("dist/site", { recursive: true, force: true });
await mkdir("dist/site", { recursive: true });
await cp("site", "dist/site", { recursive: true });

await build({
  entryPoints: ["src/client.js"],
  outfile: "dist/site/client.js",
  bundle: true,
  minify: true,
  format: "esm",
  target: "es2022",
  platform: "browser",
  define: { __API_BASE__: JSON.stringify(apiBase) },
  logLevel: "info",
});
