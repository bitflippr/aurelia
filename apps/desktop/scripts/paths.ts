import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

export const root = resolve(import.meta.dir, "../../..");
export const nativeDir = join(root, ".local", "native");

function latest(): { native?: unknown; sources?: unknown } {
  try {
    return JSON.parse(readFileSync(join(nativeDir, "latest.json"), "utf8")) ?? {};
  } catch {
    return {};
  }
}

/** The renderer the last native build published, if any. */
export function latestBinding(): string | undefined {
  const { native } = latest();
  return typeof native === "string" && existsSync(native) ? native : undefined;
}

/** The fingerprint of the sources that renderer was built from. */
export function latestSources(): string | undefined {
  const { sources } = latest();
  return typeof sources === "string" ? sources : undefined;
}
