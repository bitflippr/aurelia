// Files the renderer reads from disk: the bundled fonts and the logo. In a
// compiled binary they are embedded, so they are written out once to a real
// folder the native code can open.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import displayBlack from "./assets/fonts/AureliaDisplay-Black.ttf" with { type: "file" };
import sansBold from "./assets/fonts/AureliaSans-Bold.ttf" with { type: "file" };
import sansExtraBold from "./assets/fonts/AureliaSans-ExtraBold.ttf" with { type: "file" };
import sansMedium from "./assets/fonts/AureliaSans-Medium.ttf" with { type: "file" };
import sansRegular from "./assets/fonts/AureliaSans-Regular.ttf" with { type: "file" };
import sansSemiBold from "./assets/fonts/AureliaSans-SemiBold.ttf" with { type: "file" };
import logoFile from "./assets/logo.png" with { type: "file" };

/** Embedded files live in Bun's virtual file system, which only Bun can read. */
const embedded = (path: string) => path.includes("~BUN") || path.startsWith("/$bunfs");

function onDisk(path: string): string {
  if (!embedded(path)) return path;
  const dir = join(tmpdir(), `aurelia-assets-${Bun.version}`);
  const target = join(dir, basename(path));
  if (!existsSync(target)) {
    mkdirSync(dir, { recursive: true });
    writeFileSync(target, readFileSync(path));
  }
  return target;
}

export const fonts = [sansRegular, sansMedium, sansSemiBold, sansBold, sansExtraBold, displayBlack].map(onDisk);
export const logo = onDisk(logoFile);
