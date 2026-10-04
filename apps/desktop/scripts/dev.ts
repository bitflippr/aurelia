/**
 * Run Aurelia for development. The interface hot-reloads; a change to native
 * code (the runtime, the renderer extension, the GPUIX fork or its GPUI)
 * rebuilds the renderer and restarts the app on the new build. A failed build
 * leaves the app running on the previous one.
 *
 *   bun run desktop
 */
import { createHash } from "node:crypto";
import { existsSync, readFileSync, watch, type FSWatcher } from "node:fs";
import { join } from "node:path";
import { latestBinding, root } from "./paths";

/** Native sources, and the files in them that a build reads. */
const WATCHED: [string, RegExp][] = [
  ["apps/desktop/src", /\.rs$/],
  ["apps/desktop/native", /\.rs$/],
  ["crates", /\.(rs|toml)$/],
  ["vendor/gpuix/packages/native/src", /\.rs$/],
  ["vendor/gpuix/zed/crates/gpui/src", /\.rs$/],
  ["vendor/gpuix/zed/crates/gpui_windows/src", /\.rs$/],
];
const MANIFESTS = ["Cargo.toml", "Cargo.lock", "apps/desktop/Cargo.toml", "vendor/gpuix/packages/native/Cargo.toml"];

let app: Bun.Subprocess | undefined;
let build: Bun.Subprocess | undefined;
let buildAgain = false;
let restarting = false;
let stopping = false;
let timer: ReturnType<typeof setTimeout> | undefined;
const watchers: FSWatcher[] = [];
const contents = new Map<string, string>();

/** End a process and, on Windows, everything it started, such as Cargo. */
async function end(child: Bun.Subprocess) {
  if (child.exitCode !== null) return;
  if (process.platform === "win32") {
    await Bun.spawn(["taskkill.exe", "/PID", String(child.pid), "/T", "/F"], {
      stdout: "ignore",
      stderr: "ignore",
      windowsHide: true,
    }).exited;
  } else child.kill("SIGTERM");
}

async function rebuild() {
  if (stopping) return;
  if (build) {
    buildAgain = true;
    return;
  }
  const before = latestBinding();
  console.log("Native code changed; rebuilding the renderer.");
  build = Bun.spawn([process.execPath, join(root, "apps/desktop/scripts/build-native.ts")], {
    cwd: root,
    stdout: "inherit",
    stderr: "inherit",
  });
  const code = await build.exited;
  build = undefined;
  if (stopping) return;
  if (code !== 0) console.error("The renderer build failed; the app keeps running on the previous build.");
  else if (latestBinding() !== before && app) {
    console.log("Restarting on the new renderer.");
    restarting = true;
    await end(app);
  }
  if (buildAgain) {
    buildAgain = false;
    void rebuild();
  }
}

function fingerprint(path: string) {
  try {
    return createHash("sha256").update(readFileSync(path)).digest("hex");
  } catch {
    return "missing";
  }
}

/** Rebuild after a burst of saves settles, and only if bytes changed. */
function changed(path: string) {
  const value = fingerprint(path);
  if (contents.get(path) === value) return;
  contents.set(path, value);
  clearTimeout(timer);
  timer = setTimeout(() => void rebuild(), 400);
}

function watchSources() {
  for (const manifest of MANIFESTS) {
    const path = join(root, manifest);
    contents.set(path, fingerprint(path));
    watchers.push(watch(path, () => changed(path)));
  }
  for (const [directory, pattern] of WATCHED) {
    const base = join(root, directory);
    if (!existsSync(base)) continue;
    watchers.push(
      watch(base, { recursive: true }, (_, name) => {
        if (!name || !pattern.test(name) || /(^|[\\/])(target|node_modules)([\\/]|$)/.test(name)) return;
        changed(join(base, name));
      }),
    );
  }
}

async function stop() {
  if (stopping) return;
  stopping = true;
  clearTimeout(timer);
  for (const watcher of watchers) watcher.close();
  await Promise.all([build && end(build), app && end(app)]);
}
process.on("SIGINT", () => void stop());
process.on("SIGTERM", () => void stop());

watchSources();
while (!stopping) {
  restarting = false;
  app = Bun.spawn([process.execPath, "--hot", join(root, "apps/desktop/ui/main.tsx")], {
    cwd: root,
    stdin: "inherit",
    stdout: "inherit",
    stderr: "inherit",
  });
  const code = await app.exited;
  app = undefined;
  // Closing the window ends the session; a rebuild starts the app again.
  if (!restarting) {
    process.exitCode = code ?? 0;
    break;
  }
}
await stop();
