import { build } from "esbuild";

await build({
  entryPoints: ["src/client.js"],
  outfile: "dist/client.js",
  bundle: true,
  minify: true,
  format: "esm",
  target: "es2022",
  platform: "browser",
  logLevel: "info",
});
