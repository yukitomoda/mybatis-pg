// リレーションの関係図（試作）: 選んだ表を中心に、親を上、子を下、兄弟を横に、段ごとに並べて描く。
// 表を押すとその表を中心にし、線を押すと理由と根拠の SQL を小窓で見る
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { RelationLink } from './api';
import { Popover } from './Popover';

const tableOf = (col: string) => col.slice(0, col.lastIndexOf('.'));

/** 表どうしのつながり（列のつながりを、表の組ごとにまとめたもの） */
export type TableEdge = {
  /** 親子なら子の表、兄弟・1 対 1 なら名前の小さいほう */
  from: string;
  /** 親子なら親の表 */
  to: string;
  kind: 'parent' | 'sibling' | 'one-to-one';
  links: RelationLink[];
  /** 根拠の statement（重ねない） */
  statements: number;
  /** 手で設定したものを含む */
  manual: boolean;
};

/** 列のつながりを、表の組ごとにまとめる（無効にしたものと、同じ表の中のものは除く） */
export function tableEdges(links: RelationLink[]): TableEdge[] {
  const out = new Map<string, TableEdge & { ids: Set<string> }>();
  for (const l of links) {
    if (l.disabled) continue;
    const ta = tableOf(l.a);
    const tb = tableOf(l.b);
    if (ta === tb) continue;
    const parent = l.kind === 'parent';
    const [from, to] = parent ? [ta, tb] : ta < tb ? [ta, tb] : [tb, ta];
    const key = `${l.kind}\u0000${from}\u0000${to}`;
    let e = out.get(key);
    if (!e) {
      e = { from, to, kind: l.kind, links: [], statements: 0, manual: false, ids: new Set() };
      out.set(key, e);
    }
    e.links.push(l);
    e.manual ||= l.manual;
    for (const id of l.statements) e.ids.add(id);
    e.statements = e.ids.size;
  }
  return [...out.values()];
}

type Node = { table: string; level: number; x: number; y: number; w: number; label: string; sub: string };

const NODE_H = 46;
const ROW_GAP = 74;
const COL_GAP = 28;
const MARGIN = 24;
/** 1 段に出す表の上限（多すぎる段は、残りを数だけ出す） */
const MAX_PER_LEVEL = 24;

/** 段ごとに並べる。中心は 0 段、親は上（負）、子は下（正）、兄弟・1 対 1 は中心と同じ段 */
function layout(
  center: string,
  edges: TableEdge[],
  depth: number,
  siblings: boolean,
  label: (t: string) => string,
  sub: (t: string) => string,
): { nodes: Node[]; hidden: Map<number, number>; width: number; height: number } {
  const level = new Map<string, number>([[center, 0]]);
  const hidden = new Map<number, number>();
  const place = (ts: string[], lv: number) => {
    const fresh = [...new Set(ts)].filter((t) => !level.has(t));
    fresh.slice(0, MAX_PER_LEVEL).forEach((t) => level.set(t, lv));
    if (fresh.length > MAX_PER_LEVEL) hidden.set(lv, fresh.length - MAX_PER_LEVEL);
    return fresh.slice(0, MAX_PER_LEVEL);
  };
  if (siblings) {
    place(
      edges.filter((e) => e.kind !== 'parent' && (e.from === center || e.to === center)).map((e) => (e.from === center ? e.to : e.from)),
      0,
    );
  }
  for (const dir of [-1, 1]) {
    let frontier = [center];
    for (let d = 1; d <= depth && frontier.length > 0; d++) {
      const next = edges
        .filter((e) => e.kind === 'parent' && frontier.includes(dir < 0 ? e.from : e.to))
        .map((e) => (dir < 0 ? e.to : e.from));
      frontier = place(next, dir * d);
    }
  }
  // 中心の子の、ほかの親（order_items の親の products など）も、中心と同じ段に出す
  const children = [...level].filter(([, lv]) => lv === 1).map(([t]) => t);
  place(
    edges.filter((e) => e.kind === 'parent' && children.includes(e.from)).map((e) => e.to),
    0,
  );

  // 段ごとの並び: 中心の段は中心を真ん中に、ほかの段はつながる表の位置の平均の順（線の交わりを減らす）
  const width = (t: string) => Math.min(280, Math.max(120, Math.max(label(t).length, sub(t).length) * 7.4 + 24));
  const xs = new Map<string, number>();
  const rows = new Map<number, string[]>();
  for (const [t, lv] of level) rows.set(lv, [...(rows.get(lv) ?? []), t]);
  const lay = (ts: string[]) => {
    const total = ts.reduce((n, t) => n + width(t), 0) + COL_GAP * (ts.length - 1);
    let x = -total / 2;
    for (const t of ts) {
      xs.set(t, x + width(t) / 2);
      x += width(t) + COL_GAP;
    }
  };
  const side = (rows.get(0) ?? []).filter((t) => t !== center).sort();
  lay([...side.slice(0, Math.ceil(side.length / 2)), center, ...side.slice(Math.ceil(side.length / 2))]);
  const neighbors = (t: string) => edges.filter((e) => e.from === t || e.to === t).map((e) => (e.from === t ? e.to : e.from));
  const levels = [...rows.keys()];
  for (const dir of [-1, 1]) {
    for (let d = 1; levels.includes(dir * d); d++) {
      const ts = rows.get(dir * d)!;
      const bary = (t: string) => {
        const near = neighbors(t).filter((n) => level.get(n) === dir * (d - 1) && xs.has(n));
        return near.length ? near.reduce((n, x) => n + xs.get(x)!, 0) / near.length : 0;
      };
      lay([...ts].sort((a, b) => bary(a) - bary(b) || a.localeCompare(b)));
    }
  }

  const minLevel = Math.min(...levels);
  const nodes: Node[] = [...level].map(([t, lv]) => ({
    table: t,
    level: lv,
    x: xs.get(t)!,
    y: (lv - minLevel) * (NODE_H + ROW_GAP),
    w: width(t),
    label: label(t),
    sub: sub(t),
  }));
  const minX = Math.min(...nodes.map((n) => n.x - n.w / 2));
  const maxX = Math.max(...nodes.map((n) => n.x + n.w / 2));
  for (const n of nodes) {
    n.x += MARGIN - minX;
    n.y += MARGIN;
  }
  const maxY = Math.max(...nodes.map((n) => n.y + NODE_H));
  return { nodes, hidden, width: maxX - minX + MARGIN * 2 + 120, height: maxY + MARGIN };
}

export function RelationDiagram({
  center,
  links,
  keys,
  oneSchema,
  onCenter,
  edgeInfo,
}: {
  center: string;
  links: RelationLink[];
  keys: Record<string, number>;
  oneSchema: boolean;
  /** 表を中心にする */
  onCenter: (table: string) => void;
  /** 線を押したときに出す、理由と根拠 */
  edgeInfo: (edge: TableEdge) => ReactNode;
}) {
  const [depth, setDepth] = useState(2);
  const [siblings, setSiblings] = useState(true);
  const [zoom, setZoom] = useState(1);
  const [open, setOpen] = useState<{ edge: TableEdge; at: DOMRect } | null>(null);
  const edges = useMemo(() => tableEdges(links), [links]);
  const label = (t: string) => (oneSchema ? t.slice(t.indexOf('.') + 1) : t);
  // 主キーの列（列の一意の順位が偶数で、いちばん小さいもの）
  const pkOf = useMemo(() => {
    const by = new Map<string, { rank: number; cols: string[] }>();
    for (const [col, rank] of Object.entries(keys)) {
      if (rank % 2 !== 0) continue;
      const t = tableOf(col);
      const cur = by.get(t);
      const name = col.slice(col.lastIndexOf('.') + 1);
      if (!cur || rank < cur.rank) by.set(t, { rank, cols: [name] });
      else if (rank === cur.rank) cur.cols.push(name);
    }
    return by;
  }, [keys]);
  const sub = (t: string) => {
    const pk = pkOf.get(t);
    return pk ? `PK: ${pk.cols.join(', ')}` : '';
  };
  const { nodes, hidden, width, height } = useMemo(
    () => layout(center, edges, depth, siblings, label, sub),
    [center, edges, depth, siblings, oneSchema, pkOf],
  );
  const at = new Map(nodes.map((n) => [n.table, n]));
  const shownEdges = edges.filter((e) => at.has(e.from) && at.has(e.to) && (siblings || e.kind === 'parent'));

  // 中心の表が見えるようにスクロールする
  const scroller = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = scroller.current;
    const c = at.get(center);
    if (!el || !c) return;
    el.scrollLeft = c.x * zoom - el.clientWidth / 2;
    el.scrollTop = (c.y + NODE_H / 2) * zoom - el.clientHeight / 2;
  }, [center, depth, siblings, zoom]);

  const path = (e: TableEdge) => {
    const a = at.get(e.from)!;
    const b = at.get(e.to)!;
    if (a.level === b.level) {
      // 同じ段: 上に弧を描いて、横の箱どうしをつなぐ
      const [l, r] = a.x < b.x ? [a, b] : [b, a];
      const x1 = l.x + l.w / 2;
      const x2 = r.x - r.w / 2;
      const y = l.y + NODE_H / 2;
      const lift = Math.min(60, 18 + (x2 - x1) / 4);
      return { d: `M${x1},${y} C${x1 + 30},${y - lift} ${x2 - 30},${y - lift} ${x2},${y}`, mx: (x1 + x2) / 2, my: y - lift * 0.75 };
    }
    // 上下: 上の箱の下の辺から、下の箱の上の辺へ
    const [top, bottom] = a.y < b.y ? [a, b] : [b, a];
    const y1 = top.y + NODE_H;
    const y2 = bottom.y;
    const my = (y1 + y2) / 2;
    return { d: `M${top.x},${y1} C${top.x},${my} ${bottom.x},${my} ${bottom.x},${y2}`, mx: (top.x + bottom.x) / 2, my };
  };

  return (
    <div className="relation-diagram">
      <div className="relation-diagram-bar">
        <label>
          段数
          <select value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
            {[1, 2, 3, 4].map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </label>
        <label>
          <input type="checkbox" checked={siblings} onChange={(e) => setSiblings(e.target.checked)} /> 兄弟・1 対 1
        </label>
        <span className="relation-diagram-zoom">
          <button type="button" className="icon" title="縮小" onClick={() => setZoom((z) => Math.max(0.4, z - 0.2))}>
            −
          </button>
          <button type="button" className="link" title="元の大きさ" onClick={() => setZoom(1)}>
            {Math.round(zoom * 100)}%
          </button>
          <button type="button" className="icon" title="拡大" onClick={() => setZoom((z) => Math.min(2, z + 0.2))}>
            ＋
          </button>
        </span>
        <span className="relation-diagram-legend hint">
          <svg width="28" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="28" y2="4" className="edge parent" />
          </svg>
          子 → 親
          <svg width="28" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="28" y2="4" className="edge sibling" />
          </svg>
          兄弟
          <svg width="28" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="28" y2="4" className="edge one-to-one" />
          </svg>
          1 対 1
          <svg width="28" height="8" aria-hidden="true">
            <line x1="0" y1="4" x2="28" y2="4" className="edge parent manual" />
          </svg>
          手動　線の太さは根拠の SQL の数
        </span>
      </div>
      <div className="relation-diagram-scroll" ref={scroller}>
        <svg
          width={width * zoom}
          height={height * zoom}
          viewBox={`0 0 ${width} ${height}`}
          role="img"
          aria-label={`${label(center)} の関係図`}
        >
          <defs>
            <marker id="rd-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" className="edge-arrow" />
            </marker>
            <marker id="rd-arrow-manual" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" className="edge-arrow manual" />
            </marker>
          </defs>
          {shownEdges.map((e) => {
            const p = path(e);
            const a = at.get(e.from)!;
            const b = at.get(e.to)!;
            // 親子の矢印は親の側に付ける（子 → 親）
            const toParentAtEnd = e.kind === 'parent' && (a.level === b.level ? a.x < b.x : a.y < b.y);
            const marker = e.kind === 'parent' ? (e.manual ? 'url(#rd-arrow-manual)' : 'url(#rd-arrow)') : undefined;
            const cls = ['edge', e.kind, e.manual ? 'manual' : '', open?.edge === e ? 'active' : ''].filter(Boolean).join(' ');
            const cols = e.links.map((l) => `${l.a.slice(l.a.lastIndexOf('.') + 1)} = ${l.b.slice(l.b.lastIndexOf('.') + 1)}`).join(', ');
            return (
              <g key={`${e.kind} ${e.from} ${e.to}`}>
                <path
                  d={p.d}
                  className={cls}
                  style={{ strokeWidth: 1.2 + Math.min(2.8, Math.log2(e.statements + 1) * 0.7) }}
                  markerEnd={toParentAtEnd ? marker : undefined}
                  markerStart={!toParentAtEnd ? marker : undefined}
                />
                {/* 押しやすいように、太い透明の線を重ねる */}
                <path d={p.d} className="edge-hit" onClick={(ev) => setOpen({ edge: e, at: new DOMRect(ev.clientX, ev.clientY, 0, 0) })}>
                  <title>
                    {cols}（{e.manual && e.statements === 0 ? '手で設定' : `${e.statements} 件の SQL`}）
                  </title>
                </path>
              </g>
            );
          })}
          {nodes.map((n) => (
            <g
              key={n.table}
              className={n.table === center ? 'node center' : 'node'}
              transform={`translate(${n.x - n.w / 2},${n.y})`}
              onClick={() => n.table !== center && onCenter(n.table)}
            >
              <title>{n.table === center ? n.table : `${n.table}（押すと、この表を中心にします）`}</title>
              <rect width={n.w} height={NODE_H} rx="6" />
              <text x={n.w / 2} y={n.sub ? 19 : 28} className="node-name">
                {n.label}
              </text>
              {n.sub && (
                <text x={n.w / 2} y={36} className="node-sub">
                  {n.sub}
                </text>
              )}
            </g>
          ))}
          {[...hidden].map(([lv, count]) => {
            const row = nodes.filter((n) => n.level === lv);
            const right = Math.max(...row.map((n) => n.x + n.w / 2));
            return (
              <text key={lv} x={right + 12} y={row[0].y + NODE_H / 2 + 4} className="node-more">
                ほか {count} 表
              </text>
            );
          })}
        </svg>
      </div>
      {nodes.length === 1 && <p className="hint">この表には、推定したリレーションがありません。</p>}
      {open && (
        <Popover anchor={open.at} onClose={() => setOpen(null)}>
          {edgeInfo(open.edge)}
        </Popover>
      )}
    </div>
  );
}
