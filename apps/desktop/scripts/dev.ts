/**
 * Run Aurelia for development. A renderer older than the native code (the
 * runtime, the renderer extension, the GPUIX fork or its GPUI) is rebuilt
 * before the app starts. The interface hot-reloads; a change to native code
 * while it runs rebuilds the renderer and restarts the app on the new build.
 * A failed build leaves the app on the previous one.
 *
 *   bun run desktop
 */
import { existsSync, watch, type FSWatcher } from "node:fs";
import { join } from "node:path";
import { latestBinding, latestSources, root } from "./paths";
import { NOT_SOURCES, SOURCE_FILES, sourceDirectories, sourceFingerprint } from "./sources";

let app: Bun.Subprocess | undefined;
let build: Bun.Subprocess | undefined;
let buildAgain = false;
let restarting = false;
let stopping = false;
let timer: ReturnType<typeof setTimeout> | undefined;
const watchers: FSWatcher[] = [];

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
  // Saves that leave the sources as the renderer was built need no build.
  if (before && latestSources() === sourceFingerprint()) return;
  console.log(before ? "Native code changed; rebuilding the renderer." : "Building the renderer.");
  build = Bun.spawn([process.execPath, join(root, "apps/desktop/scripts/build-native.ts")], {
    cwd: root,
    stdout: "inherit",
    stderr: "inherit",
  });
  const code = await build.exited;
  build = undefined;
  if (stopping) return;
  if (code !== 0 && before) console.error(`The renderer build failed; the app ${app ? "keeps running" : "starts"} on the previous build.`);
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

/** Rebuild after a burst of saves settles. */
function changed() {
  clearTimeout(timer);
  timer = setTimeout(() => void rebuild(), 400);
}

function watchSources() {
  for (const file of SOURCE_FILES) {
    const path = join(root, file);
    if (existsSync(path)) watchers.push(watch(path, changed));
  }
  for (const [directory, pattern] of sourceDirectories()) {
    const base = join(root, directory);
    if (!existsSync(base)) continue;
    watchers.push(
      watch(base, { recursive: true }, (_, name) => {
        if (name && pattern.test(name) && !NOT_SOURCES.test(name)) changed();
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
await rebuild();
if (!stopping && !latestBinding()) {
  console.error("The renderer build failed, and there's no earlier build to start on.");
  process.exitCode = 1;
  await stop();
}
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
