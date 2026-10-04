// Every page is one virtual list: rows of sections, cards or songs. Only the
// rows near the viewport are built, history restores the position, and a
// compact bar takes over once the page header scrolls away.
import { isValidElement, useEffect, useRef, useState, type ReactNode } from "react";
import { AnimatePresence } from "@gpuix/react";
import { Icon } from "./icons";
import { Motion } from "./components";
import { host } from "./native";
import { get, setScrollReader, useStore } from "./store";
import { C, SIZE, alpha, center, mix, motion, row } from "./theme";

export type Sticky = {
  title: string;
  onPlay?: () => void;
  /** Shown under the title bar, such as a table's column header. */
  below?: ReactNode;
  tint?: string;
};

/** Pages with more rows than this mount only a window of them. */
const WINDOWED = 160;
const WINDOW = 60;
/** Past this far into the last header row, the page title has scrolled away. */
const STICKY_OFFSET = 300;

export function Page({
  count,
  row: renderRow,
  estimate = 56,
  sticky,
  stickyAfter = 1,
}: {
  count: number;
  row: (index: number) => ReactNode;
  estimate?: number;
  sticky?: Sticky;
  /** Show the sticky bar once this many rows have scrolled away. */
  stickyAfter?: number;
}) {
  const list = useRef<{ id: number } | null>(null);
  const [start, setStart] = useState(0);
  const [stuck, setStuck] = useState(false);
  const windowed = count > WINDOWED;

  useEffect(() => {
    setScrollReader(() => {
      const id = list.current?.id;
      const top = id ? host().renderer.getListScrollTop(id) : null;
      // Index and offset, packed into one number for history.
      return top ? top[0] * 100_000 + Math.round(top[1]) : 0;
    });
    const restore = get().restoreScroll;
    const id = list.current?.id;
    if (restore && id) {
      const index = Math.floor(restore / 100_000);
      host().renderer.scrollToItem(id, index, restore % 100_000);
      if (windowed) setStart(Math.max(0, index - WINDOW / 4));
    }
  }, []);

  const end = windowed ? Math.min(count, start + WINDOW) : count;
  const rows: ReactNode[] = [];
  for (let i = windowed ? start : 0; i < end; i++) {
    const node = renderRow(i);
    // A list row sizes to its content unless told otherwise, and then nothing
    // in it can grow to share the width.
    rows.push(
      <div key={isValidElement(node) && node.key !== null ? node.key : i} style={{ width: "100%", display: "flex", flexDirection: "column" }}>
        {node}
      </div>,
    );
  }

  return (
    <div style={{ position: "relative", flexGrow: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
      <virtual-list
        ref={list as never}
        estimatedItemHeight={estimate}
        overdraw={480}
        {...((windowed ? { itemCount: count, windowStart: start } : {}) as unknown as { itemCount: number })}
        onVisibleRange={(event) => {
          const first = Math.floor(event.startIndex ?? 0);
          if (windowed) {
            const next = Math.max(0, first - WINDOW / 4);
            if (Math.abs(next - start) >= WINDOW / 8 || (first < start && start > 0)) setStart(next);
          }
          // The range includes overdraw, so read where the viewport really is.
          const id = list.current?.id;
          const top = id ? host().renderer.getListScrollTop(id) : null;
          const [index, offset] = top ?? [first, 0];
          setStuck(index >= stickyAfter || (index === stickyAfter - 1 && offset > STICKY_OFFSET));
        }}
        style={{ flexGrow: 1, minHeight: 0 }}
      >
        {rows}
      </virtual-list>
      {sticky && <StickyBar sticky={sticky} visible={stuck} />}
    </div>
  );
}

function StickyBar({ sticky, visible }: { sticky: Sticky; visible: boolean }) {
  const tint = sticky.tint ? mix(C.bg, sticky.tint, 0.32) : C.surfaceLow;
  return (
    <AnimatePresence>
      {visible && (
        <Motion
          key="sticky"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: motion.fast, ease: "easeOut" }}
          style={{ position: "absolute", left: 0, right: 0, top: 0, backgroundColor: tint, pointerEvents: "auto" }}
        >
          <div style={{ ...row, height: 64, gap: 14, paddingLeft: 24, paddingRight: SIZE.page }}>
            {sticky.onPlay && (
              <div
                role="button"
                aria-label="Play"
                onClick={sticky.onPlay}
                style={{ ...center, width: 40, height: 40, borderRadius: 20, backgroundColor: C.primary, cursor: "pointer" }}
              >
                <Icon name="play" size={18} color={C.onPrimary} />
              </div>
            )}
            <text style={{ fontSize: 20, fontWeight: 800, color: C.text, whiteSpace: "nowrap", textOverflow: "ellipsis", minWidth: 0 }}>
              {sticky.title}
            </text>
          </div>
          {sticky.below && (
            <div style={{ paddingLeft: SIZE.page, paddingRight: SIZE.page, backgroundColor: alpha(C.bg, 0.0) }}>{sticky.below}</div>
          )}
        </Motion>
      )}
    </AnimatePresence>
  );
}

/** A page row with the standard horizontal padding. */
export function Pad({ children, top = 0, bottom = 0 }: { children: ReactNode; top?: number; bottom?: number }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", paddingLeft: SIZE.page, paddingRight: SIZE.page, paddingTop: top, paddingBottom: bottom }}>
      {children}
    </div>
  );
}

export function usePalette(id: string | undefined): [string, string, string] | undefined {
  return useStore((s) => {
    const art = id ? s.artwork[id] : undefined;
    return typeof art === "object" ? art.palette : undefined;
  });
}
