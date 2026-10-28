// ペインの幅をドラッグで変える仕切り
import type { CSSProperties, PointerEvent } from 'react';
import { useEffect, useRef, useState } from 'react';

const STORAGE_KEY = 'mybatis-pg.panes';
/** 各ペインの最小の幅 */
export const MIN_SIDEBAR = 160;
export const MIN_PARAMS = 280;
export const MIN_RESULT = 320;
/** 仕切りの幅（CSS の .splitter と合わせる） */
const SPLITTER = 6;
/** キー操作で動かす幅 */
const STEP = 16;

/** 幅（px）。null は既定（CSS の値）のまま */
export interface PaneWidths {
  sidebar: number | null;
  params: number | null;
}

function load(): PaneWidths {
  try {
    const v = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '{}') as Partial<PaneWidths>;
    return {
      sidebar: typeof v.sidebar === 'number' ? v.sidebar : null,
      params: typeof v.params === 'number' ? v.params : null,
    };
  } catch {
    return { sidebar: null, params: null };
  }
}

function save(w: PaneWidths) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(w));
  } catch {
    // 保存できない環境では、この表示中だけ覚える
  }
}

/**
 * 左と中央のペインの幅が、全体の幅に収まるように詰める（右のペインに最小の幅を残す）。
 * 幅を変えていないペイン（null）は、実際の幅（measured）で計算する。
 */
export function clampWidths(w: PaneWidths, total: number, measured: { sidebar: number; params: number }): PaneWidths {
  let sidebar = w.sidebar;
  let params = w.params;
  const room = total - 2 * SPLITTER - MIN_RESULT;
  if (sidebar !== null) {
    sidebar = Math.max(MIN_SIDEBAR, Math.min(sidebar, room - MIN_PARAMS));
  }
  if (params !== null) {
    params = Math.max(MIN_PARAMS, Math.min(params, room - (sidebar ?? measured.sidebar)));
  }
  return { sidebar, params };
}

/** ペインの幅を持ち、仕切りの操作を返す */
export function usePaneWidths() {
  const [widths, setWidths] = useState<PaneWidths>(load);
  const layout = useRef<HTMLDivElement>(null);

  const measure = () => {
    const el = layout.current;
    const side = el?.querySelector<HTMLElement>('.sidebar')?.getBoundingClientRect().width ?? 0;
    const params = el?.querySelector<HTMLElement>('.params')?.getBoundingClientRect().width ?? 0;
    return { total: el?.getBoundingClientRect().width ?? window.innerWidth, sidebar: side, params };
  };

  const apply = (next: PaneWidths) => {
    const m = measure();
    const clamped = clampWidths(next, m.total, m);
    setWidths(clamped);
    save(clamped);
  };

  // 窓を狭めたときに、右のペインがはみ出さないよう詰める
  useEffect(() => {
    const onResize = () => setWidths((w) => {
      const m = measure();
      return clampWidths(w, m.total, m);
    });
    window.addEventListener('resize', onResize);
    // 前回より狭い窓で開いたときのため、最初にも詰める
    onResize();
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const style = {
    '--sidebar-width': widths.sidebar === null ? undefined : `${widths.sidebar}px`,
    '--params-width': widths.params === null ? undefined : `${widths.params}px`,
  } as CSSProperties;

  return { layout, style, widths, measure, apply };
}

/**
 * 仕切り。ドラッグで左のペインの幅を変える。矢印キーでも動かせ、ダブルクリックで既定の幅に戻す。
 */
export function Splitter({
  pane,
  label,
  panes,
}: {
  pane: keyof PaneWidths;
  label: string;
  panes: ReturnType<typeof usePaneWidths>;
}) {
  const drag = useRef<{ x: number; width: number } | null>(null);
  const current = () => panes.widths[pane] ?? panes.measure()[pane];
  const set = (width: number | null) => panes.apply({ ...panes.widths, [pane]: width });
  const end = (e: PointerEvent<HTMLDivElement>) => {
    drag.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    document.body.classList.remove('resizing');
  };

  return (
    <div
      className="splitter"
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={Math.round(current())}
      tabIndex={0}
      title="ドラッグで幅を変える（ダブルクリックで元に戻す）"
      onPointerDown={(e) => {
        e.preventDefault();
        e.currentTarget.setPointerCapture(e.pointerId);
        drag.current = { x: e.clientX, width: current() };
        document.body.classList.add('resizing');
      }}
      onPointerMove={(e) => {
        if (!drag.current) return;
        set(drag.current.width + e.clientX - drag.current.x);
      }}
      onPointerUp={end}
      onPointerCancel={end}
      onDoubleClick={() => set(null)}
      onKeyDown={(e) => {
        if (e.key === 'ArrowLeft') set(current() - STEP);
        else if (e.key === 'ArrowRight') set(current() + STEP);
        else return;
        e.preventDefault();
      }}
    />
  );
}

// ---- 2 つのペインの左の幅を変える仕切り（共通パラメータ・リレーションの画面など） ----

const SIDE_KEY = 'mybatis-pg.side-widths';

function loadSide(key: string): number | null {
  try {
    const v = (JSON.parse(localStorage.getItem(SIDE_KEY) ?? '{}') as Record<string, unknown>)[key];
    return typeof v === 'number' ? v : null;
  } catch {
    return null;
  }
}

function saveSide(key: string, width: number | null) {
  try {
    const all = JSON.parse(localStorage.getItem(SIDE_KEY) ?? '{}') as Record<string, number>;
    if (width === null) delete all[key];
    else all[key] = width;
    localStorage.setItem(SIDE_KEY, JSON.stringify(all));
  } catch {
    // 保存できない環境では、この表示中だけ覚える
  }
}

/**
 * 左のペインの幅（画面ごとに覚える）。style の --side-width を、並べる要素の grid-template-columns で使う。
 *
 * @param key 画面の名前（覚えるときの名前）
 */
export function useSideWidth(key: string) {
  const [width, setWidth] = useState<number | null>(() => loadSide(key));
  const set = (w: number | null) => {
    setWidth(w);
    saveSide(key, w);
  };
  const style = { '--side-width': width === null ? undefined : `${width}px` } as CSSProperties;
  return { width, set, style };
}

/**
 * 仕切り。左隣の要素の幅を、ドラッグ（矢印キーでも）で変える。ダブルクリックで既定の幅に戻す。
 * 右に MIN_RESULT を残す。
 */
export function SideSplitter({ label, side }: { label: string; side: ReturnType<typeof useSideWidth> }) {
  const drag = useRef<{ x: number; width: number } | null>(null);
  const el = useRef<HTMLDivElement>(null);
  const current = () => side.width ?? el.current?.previousElementSibling?.getBoundingClientRect().width ?? MIN_SIDEBAR;
  const set = (w: number | null) => {
    if (w === null) return side.set(null);
    const total = el.current?.parentElement?.getBoundingClientRect().width ?? window.innerWidth;
    side.set(Math.round(Math.max(MIN_SIDEBAR, Math.min(w, total - SPLITTER - MIN_RESULT))));
  };
  const end = (e: PointerEvent<HTMLDivElement>) => {
    drag.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    document.body.classList.remove('resizing');
  };
  return (
    <div
      ref={el}
      className="splitter"
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      tabIndex={0}
      title="ドラッグで幅を変える（ダブルクリックで元に戻す）"
      onPointerDown={(e) => {
        e.preventDefault();
        e.currentTarget.setPointerCapture(e.pointerId);
        drag.current = { x: e.clientX, width: current() };
        document.body.classList.add('resizing');
      }}
      onPointerMove={(e) => {
        if (!drag.current) return;
        set(drag.current.width + e.clientX - drag.current.x);
      }}
      onPointerUp={end}
      onPointerCancel={end}
      onDoubleClick={() => set(null)}
      onKeyDown={(e) => {
        if (e.key === 'ArrowLeft') set(current() - STEP);
        else if (e.key === 'ArrowRight') set(current() + STEP);
        else return;
        e.preventDefault();
      }}
    />
  );
}
