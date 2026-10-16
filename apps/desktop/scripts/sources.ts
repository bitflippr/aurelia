/**
 * The native sources a renderer build compiles, and a fingerprint of them. A
 * build records the fingerprint with the binding it publishes, so
 * `bun run desktop` can tell whether that binding is current.
 */
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { root } from "./paths";

const ZED_CRATES = "vendor/gpuix/zed/crates";

/** Files a build reads outside the source directories. */
export const SOURCE_FILES = [
  "Cargo.toml",
  "Cargo.lock",
  "apps/desktop/Cargo.toml",
  "apps/desktop/scripts/build-native.ts",
  "vendor/gpuix/packages/native/Cargo.toml",
  "vendor/gpuix/packages/native/Cargo.lock",
  "vendor/gpuix/packages/native/build.rs",
];

/** Build output and installed packages, which are never sources. */
export const NOT_SOURCES = /(^|[\\/])(target|node_modules)([\\/]|$)/;

/** Native source directories, and the files in them that a build reads. */
export function sourceDirectories(): [string, RegExp][] {
  const zed = join(root, ZED_CRATES);
  const gpui = existsSync(zed) ? readdirSync(zed).filter((name) => name.startsWith("gpui")) : [];
  return [
    ["apps/desktop/src", /\.rs$/],
    ["apps/desktop/native", /\.rs$/],
    ["crates", /\.(rs|toml)$/],
    ["vendor/gpuix/packages/native/src", /\.rs$/],
    // A build applies these to GPUI before it compiles.
    ["vendor/gpuix/downstream/zed", /\.patch$/],
    // GPUI and its platform crates, shaders included.
    ...gpui.map((name): [string, RegExp] => [`${ZED_CRATES}/${name}`, /\.(rs|toml|metal|wgsl|hlsl)$/]),
  ];
}

/** A hash of every native source's path and contents. */
export function sourceFingerprint() {
  const paths = [...SOURCE_FILES];
  for (const [directory, pattern] of sourceDirectories()) {
    const base = join(root, directory);
    if (!existsSync(base)) continue;
    for (const name of readdirSync(base, { recursive: true, encoding: "utf8" }))
      if (pattern.test(name) && !NOT_SOURCES.test(name)) paths.push(`${directory}/${name.replaceAll("\\", "/")}`);
  }
  const hash = createHash("sha256");
  for (const path of paths.sort()) {
    hash.update(`${path}\0`);
    try {
      hash.update(readFileSync(join(root, path)));
    } catch {
      hash.update("missing");
    }
    hash.update("\0");
  }
  return hash.digest("hex");
}
