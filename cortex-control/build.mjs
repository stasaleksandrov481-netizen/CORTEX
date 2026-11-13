import { cp } from "node:fs/promises";
import { build } from "esbuild";

await cp("public", "dist", { recursive: true });
await build({ entryPoints: ["app.js"], bundle: true, outfile: "dist/app.js", target: "es2022" });
