import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

export const root = resolve(import.meta.dir, "../../..");
export const nativeDir = join(root, ".local", "native");

/** The renderer the last native build published, if any. */
export function latestBinding(): string | undefined {
  try {
    const { native } = JSON.parse(readFileSync(join(nativeDir, "latest.json"), "utf8"));
    return typeof native === "string" && existsSync(native) ? native : undefined;
  } catch {
    return undefined;
  }
}
