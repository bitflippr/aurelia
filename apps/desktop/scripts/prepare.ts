/**
 * Prepare the GPUIX checkout: the pinned Zed with its GPUI patches, and the
 * JavaScript of the native and React packages, which the fork's branch
 * provides as TypeScript.
 *
 *   bun run desktop:prepare
 */
import { existsSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { root } from "./paths";

const gpuix = resolve(root, "vendor/gpuix");

function run(args: string[], cwd: string) {
  const result = Bun.spawnSync(args, { cwd, stdout: "inherit", stderr: "inherit" });
  if (result.exitCode !== 0) throw new Error(`${args.join(" ")} failed in ${cwd}`);
}

function newest(dir: string): number {
  let latest = 0;
  for (const name of readdirSync(dir, { withFileTypes: true })) {
    if (name.name === "__tests__" || name.name === "node_modules") continue;
    const path = join(dir, name.name);
    latest = Math.max(latest, name.isDirectory() ? newest(path) : statSync(path).mtimeMs);
  }
  return latest;
}

/** Compile a package's TypeScript into `dist` when its sources are newer. */
function compile(pkg: string, sources: string) {
  const dir = join(gpuix, "packages", pkg);
  const dist = join(dir, "dist");
  if (existsSync(dist) && newest(dist) >= newest(join(dir, sources))) return;
  run(["bun", "x", "tsc", "-p", "tsconfig.json"], dir);
}

export function prepare() {
  if (!existsSync(join(gpuix, "package.json"))) run(["git", "submodule", "update", "--init", "vendor/gpuix"], root);
  run(["bun", join(gpuix, "downstream", "prepare.ts")], root);
  compile("native", "js");
  compile("react", "src");
}

if (import.meta.main) prepare();
