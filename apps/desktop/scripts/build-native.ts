/**
 * Build the GPUIX renderer with Aurelia's runtime compiled in, then publish it
 * to `.local/native/aurelia-<hash>.node` and name it in `latest.json`. A build
 * never overwrites a binding a running Aurelia has loaded.
 *
 *   bun run desktop:native
 */
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { prepare } from "./prepare";
import { latestBinding, nativeDir, root } from "./paths";

const native = resolve(root, "vendor/gpuix/packages/native");
const target = resolve(root, process.env.CARGO_TARGET_DIR ?? ".local/native-target");

prepare();

// GPUIX pins its own toolchain, which `napi build` would pick up from inside
// the package. Build with the one Aurelia's own crates use instead.
const active = Bun.spawnSync(["rustup", "show", "active-toolchain"], { cwd: root }).stdout.toString();
const toolchain = process.env.RUSTUP_TOOLCHAIN ?? active.split(/\s+/)[0];

const build = Bun.spawnSync(
  [
    "bun",
    "x",
    "napi",
    "build",
    "--platform",
    "--release",
    "--no-default-features",
    "--esm",
    "--js",
    "index.js",
    "--target-dir",
    target,
  ],
  {
    cwd: native,
    stdout: "inherit",
    stderr: "inherit",
    env: {
      ...process.env,
      ...(toolchain ? { RUSTUP_TOOLCHAIN: toolchain } : {}),
      CARGO_PROFILE_RELEASE_LTO: "false",
      CARGO_PROFILE_RELEASE_CODEGEN_UNITS: "16",
      ...(process.arch === "x64" && !process.env.RUSTFLAGS ? { RUSTFLAGS: "-C target-cpu=x86-64-v3" } : {}),
      // mimalloc compiles as C++ against the DLL C runtime.
      ...(process.platform === "win32" ? { CXXFLAGS_x86_64_pc_windows_msvc: "-D_ALLOW_RUNTIME_LIBRARY_MISMATCH" } : {}),
    },
  },
);
if (build.exitCode !== 0) process.exit(build.exitCode ?? 1);

const built = readdirSync(native).find((name) => name.startsWith("gpuix-native.") && name.endsWith(".node"));
if (!built) throw new Error("napi build produced no .node file");
const source = join(native, built);
const hash = createHash("sha256").update(readFileSync(source)).digest("hex").slice(0, 20);
mkdirSync(nativeDir, { recursive: true });
const published = join(nativeDir, `aurelia-${hash}.node`);
if (!existsSync(published)) copyFileSync(source, published);
const previous = latestBinding();
writeFileSync(join(nativeDir, "latest.json"), JSON.stringify({ native: published }));

// Keep this build and the one before it; Windows refuses to delete a binding
// that a running Aurelia has loaded, so that one goes on a later build.
for (const name of readdirSync(nativeDir)) {
  const path = join(nativeDir, name);
  if (!name.endsWith(".node") || path === published || path === previous) continue;
  try {
    unlinkSync(path);
  } catch {}
}
console.log(`Published ${published}`);
