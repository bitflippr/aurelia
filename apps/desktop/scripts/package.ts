/**
 * Build a standalone Aurelia executable: the interface, the Bun runtime and the
 * renderer in one file, written to `.local/dist`. Build the renderer first.
 *
 *   bun run desktop:package
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { latestBinding, root } from "./paths";

const binding = latestBinding();
if (!binding) throw new Error("Build the renderer first: bun run desktop:native");

const out = join(root, ".local", "dist");
mkdirSync(out, { recursive: true });

// The GPUIX loader requires its binding at run time, which Bun cannot see. A
// static import embeds it; the loader is then pointed at the embedded copy.
const entry = join(out, "entry.ts");
writeFileSync(
  entry,
  [
    `import binding from ${JSON.stringify(binding.replaceAll("\\", "/"))} with { type: "file" };`,
    "process.env.AURELIA_NATIVE_LIBRARY = binding;",
    `await import(${JSON.stringify(resolve(root, "apps/desktop/ui/main.tsx").replaceAll("\\", "/"))});`,
    "",
  ].join("\n"),
);
const outfile = join(out, process.platform === "win32" ? "Aurelia.exe" : "Aurelia");
const build = Bun.spawnSync(
  [
    "bun",
    "build",
    "--compile",
    "--minify",
    "--define",
    'process.env.NODE_ENV="production"',
    ...(process.platform === "win32" ? ["--windows-hide-console"] : []),
    entry,
    "--outfile",
    outfile,
  ],
  { cwd: root, stdout: "inherit", stderr: "inherit" },
);
if (build.exitCode !== 0) process.exit(build.exitCode ?? 1);
console.log(`Built ${outfile}`);
