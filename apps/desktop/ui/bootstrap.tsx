import { createNativeRenderer } from "@gpuix/native/runtime";
import { render, resetRender, startFrameLoop, type EventPayload } from "@gpuix/react";
import { fonts } from "./assets";
import { command, host, schedulePolling, type AureliaRenderer } from "./native";
import { App, searchInput } from "./shell";
import { boot, get, goBack, goForward, onPoll, set, setFull, togglePanel } from "./store";


if (!globalThis.__aurelia) {
  const renderer = createNativeRenderer({
    onError: (error) =>
      queueMicrotask(() => {
        throw error;
      }),
  }) as AureliaRenderer;
  if (typeof renderer.aureliaRequest !== "function") {
    throw new Error("This GPUIX renderer lacks Aurelia's runtime. Run: bun run desktop:native");
  }
  // GPUI clears a new window to white before the first frame, so a window that
  // takes focus opens hidden and the first render reveals it. A background
  // launch (GPUIX_BACKGROUND=1, as automation uses) shows at once, unfocused.
  const focus = process.env.GPUIX_BACKGROUND !== "1";
  renderer.init({
    title: "Aurelia",
    appName: "Aurelia",
    width: 1360,
    height: 860,
    minWidth: 980,
    minHeight: 620,
    titlebarTransparent: true,
    clientDecorations: true,
    trafficLightX: 14,
    trafficLightY: 18,
    fonts,
    imageMemoryMb: 192,
    focus,
    show: !focus,
  });
  globalThis.__aurelia = { renderer, pending: new Map(), seen: 0, reveal: focus };
  host().onPoll = onPoll;
  schedulePolling();
  boot().catch((error) => console.error("Aurelia could not start:", error));
  process.once("SIGINT", () => process.exit(0));
  process.once("SIGTERM", () => process.exit(0));
  // Windows and Linux run GPUI on its own thread; a tick only notices the
  // window has gone, which polling also does.
  startFrameLoop(renderer, {
    frameMs: process.platform === "darwin" ? undefined : 250,
    onTerminated: () => process.exit(0),
  });
} else {
  // A hot reload: keep the window and state, swap the tree without a white
  // frame by holding the unmount batch until the mount's.
  const renderer = host().renderer;
  const join_ = (head: string | undefined, json: string) => (head ? `${head.slice(0, -1)},${json.slice(1)}` : json);
  let unmount: string | undefined;
  renderer.applyBatch = (json: string) => {
    unmount = join_(unmount, json);
    return [];
  };
  try {
    resetRender();
  } finally {
    Reflect.deleteProperty(renderer, "applyBatch");
  }
  const held = unmount;
  if (held)
    renderer.applyBatch = (json: string) => {
      Reflect.deleteProperty(renderer, "applyBatch");
      return renderer.applyBatch(join_(held, json));
    };
  host().onPoll = onPoll;
}

function typing(): boolean {
  const focused = host().renderer.getFocusedElementId();
  return focused !== null && focused !== undefined && focused === searchInput.current?.id;
}

function onKey(event: EventPayload) {
  const key = event.key ?? "";
  const mods = event.modifiers ?? { shift: false, ctrl: false, alt: false, cmd: false };
  const ctrl = mods.ctrl || mods.cmd;
  const s = get();
  if (ctrl && (key === "k" || key === "f")) {
    const id = searchInput.current?.id;
    if (id) host().renderer.focusElement(id);
    return;
  }
  if (key === "escape") {
    if (s.menu) set({ menu: null });
    else if (s.dialog) set({ dialog: null });
    else if (s.full) setFull(false);
    return;
  }
  if (typing() || s.dialog || s.stage !== "ready") return;
  if (mods.alt && key === "left") return goBack();
  if (mods.alt && key === "right") return goForward();
  if (ctrl && key === "right") return command("next");
  if (ctrl && key === "left") return command("previous");
  if (ctrl && key === "b") return set((x) => ({ collapsed: !x.collapsed }));
  if (ctrl || mods.alt) return;
  if (key === "space") return command("toggle");
  if (key === "l") return togglePanel("lyrics");
  if (key === "q") return togglePanel("queue");
  if (key === "f") return setFull(!s.full);
}

try {
  render(<App />, { renderer: host().renderer, onKeyDown: onKey, keyboardFocusDim: false });
} finally {
  if (host().reveal) {
    host().reveal = false;
    host().renderer.activateWindow();
  }
}
