// リレーションの関係図（試作）: 選んだ表のまわりの表を、ELK（段ごとに並べる配置）で、親を上、子を下に描く。
// 線は箱を避けた縦横の折れ線にし、表にマウスを乗せるとその表の線だけを強調する。
// 表を押すとその表を中心にし、線を押すと理由と根拠の SQL を小窓で見る
import { useDragScroll } from './dragScroll';
import { track } from './busy';
import { NAME_FONT, SUB_FONT, fitText, measure } from './textFit';
import { useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import type { ElkExtendedEdge, ElkNode } from 'elkjs/lib/elk.bundled.js';
import type { RelationLink } from './api';
import { CommentsContext } from './comments';
import { Popover } from './Popover';

const tableOf = (col: string) => col.slice(0, col.lastIndexOf('.'));

/** 表どうしのつながり（列のつながりを、表の組ごとにまとめたもの） */
export type TableEdge = {
  /** 親子なら子の表、兄弟・1 対 1 なら名前の小さいほう */
  from: string;
  /** 親子なら親の表（自己参照なら from と同じ） */
  to: string;
  kind: 'parent' | 'sibling' | 'one-to-one';
  links: RelationLink[];
  /** 根拠の statement（重ねない） */
  statements: number;
  /** 手で設定したものを含む */
  manual: boolean;
};

/** 列のつながりを、表の組ごとにまとめる（無効にしたものと、同じ表の中の兄弟は除く。自己参照の親子は残す） */
export function tableEdges(links: RelationLink[]): TableEdge[] {
  const out = new Map<string, TableEdge & { ids: Set<string> }>();
  for (const l of links) {
    if (l.disabled) continue;
    const ta = tableOf(l.a);
    const tb = tableOf(l.b);
    if (ta === tb && l.kind === 'sibling') continue;
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

/** 1 つの方向に出す表の上限（多すぎるときは、残りを数だけ出す） */
const MAX_PER_LEVEL = 24;

/**
 * 図に出す表を選ぶ: 中心から、つながりを depth 回までたどって届く表（親等のように、親・子の向きを問わず 1 回で 1 段と数える）。
 * 兄弟・1 対 1 のつながりは、siblings のときだけたどる。段ごとに多すぎる表は省いて、省いた数を返す
 */
export function neighborhood(center: string, edges: TableEdge[], depth: number, siblings: boolean) {
  const shown = new Set<string>([center]);
  let hidden = 0;
  let frontier = [center];
  for (let d = 1; d <= depth && frontier.length > 0; d++) {
    const next = edges
      .filter((e) => e.from !== e.to && (siblings || e.kind === 'parent') && (frontier.includes(e.from) || frontier.includes(e.to)))
      .flatMap((e) => [e.from, e.to]);
    const fresh = [...new Set(next)].filter((t) => !shown.has(t));
    fresh.slice(0, MAX_PER_LEVEL).forEach((t) => shown.add(t));
    hidden += Math.max(0, fresh.length - MAX_PER_LEVEL);
    frontier = fresh.slice(0, MAX_PER_LEVEL);
  }
  return { shown, hidden };
}

export const NODE_H = 46;
/** 文字の幅（px）。全角の文字は、半角の倍近くで数える */
/** 箱の幅の上限・下限と、左右の余白 */
const NODE_MAX_W = 320;
const NODE_MIN_W = 120;
const NODE_PAD = 24;
/** 箱の幅（実際の字体で測る。長すぎる名前は上限で切り、箱の中では「…」にする） */
const nodeWidth = (label: string, sub: string) =>
  Math.min(NODE_MAX_W, Math.max(NODE_MIN_W, Math.ceil(Math.max(measure(label, NAME_FONT), measure(sub, SUB_FONT))) + NODE_PAD));

type Placed = { table: string; x: number; y: number; w: number; label: string; sub: string };
type Routed = { edge: TableEdge; points: { x: number; y: number }[] };
export type Laid = { nodes: Placed[]; edges: Routed[]; width: number; height: number };

/** ELK は大きいので、図を開いたときに読み込む */
let elk: Promise<{ layout: (g: ElkNode) => Promise<ElkNode> }> | null = null;
const loadElk = () => (elk ??= import('elkjs/lib/elk.bundled.js').then((m) => new m.default()));

export async function layoutWithElk(
  tables: string[],
  edges: TableEdge[],
  label: (t: string) => string,
  sub: (t: string) => string,
  /** DOWN: 親を上に、RIGHT: 親を左に */
  direction: 'DOWN' | 'RIGHT' = 'DOWN',
): Promise<Laid> {
  const engine = await loadElk();
  const graph: ElkNode = {
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      // 親を上に（線は親 → 子の向きで渡す）
      'elk.direction': direction,
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.spacing.nodeNode': '36',
      'elk.layered.spacing.nodeNodeBetweenLayers': '64',
      'elk.spacing.edgeNode': '16',
      'elk.spacing.edgeEdge': '10',
      'elk.layered.spacing.edgeNodeBetweenLayers': '20',
      'elk.layered.spacing.edgeEdgeBetweenLayers': '10',
      'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
      'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
      'elk.layered.cycleBreaking.strategy': 'GREEDY',
      'elk.padding': '[top=24,left=24,bottom=24,right=24]',
    },
    children: tables.map((t) => ({ id: t, width: nodeWidth(label(t), sub(t)), height: NODE_H })),
    edges: edges.map((e, i): ElkExtendedEdge => ({
      id: `e${i}`,
      // 親子は親 → 子（親が上の段になる）。兄弟・1 対 1 は名前の順
      sources: [e.kind === 'parent' ? e.to : e.from],
      targets: [e.kind === 'parent' ? e.from : e.to],
    })),
  };
  const out = await engine.layout(graph);
  const nodes = (out.children ?? []).map((n) => ({
    table: n.id,
    x: n.x ?? 0,
    y: n.y ?? 0,
    w: n.width ?? 120,
    label: label(n.id),
    sub: sub(n.id),
  }));
  const routed = (out.edges ?? []).map((oe, i) => {
    const s = (oe as ElkExtendedEdge).sections?.[0];
    const points = s ? [s.startPoint, ...(s.bendPoints ?? []), s.endPoint] : [];
    return { edge: edges[i], points };
  });
  return { nodes, edges: routed, width: out.width ?? 0, height: out.height ?? 0 };
}

/** 折れ線を、角を丸めた path にする */
export function roundedPath(points: { x: number; y: number }[], r = 8): string {
  if (points.length < 2) return '';
  let d = `M${points[0].x},${points[0].y}`;
  for (let i = 1; i < points.length - 1; i++) {
    const p = points[i - 1];
    const c = points[i];
    const n = points[i + 1];
    const d1 = Math.hypot(c.x - p.x, c.y - p.y);
    const d2 = Math.hypot(n.x - c.x, n.y - c.y);
    const k = Math.min(r, d1 / 2, d2 / 2);
    const a = { x: c.x - ((c.x - p.x) / (d1 || 1)) * k, y: c.y - ((c.y - p.y) / (d1 || 1)) * k };
    const b = { x: c.x + ((n.x - c.x) / (d2 || 1)) * k, y: c.y + ((n.y - c.y) / (d2 || 1)) * k };
    d += ` L${a.x},${a.y} Q${c.x},${c.y} ${b.x},${b.y}`;
  }
  const last = points[points.length - 1];
  return `${d} L${last.x},${last.y}`;
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
  const [depth, setDepth] = useState(1);
  const [siblings, setSiblings] = useState(true);
  const [zoom, setZoom] = useState(1);
  const [open, setOpen] = useState<{ edge: TableEdge; at: DOMRect } | null>(null);
  /** マウスを乗せている表（その表の線だけを強調する） */
  const [hover, setHover] = useState<string | null>(null);
  /** マウスを乗せている表の箱の位置（詳細を、箱のすぐ下に出す） */
  const [hoverAt, setHoverAt] = useState<DOMRect | null>(null);
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
  // 箱の 2 行目: 表のコメントがあればそれを（長ければ省く）、なければ主キーの列
  const comments = useContext(CommentsContext);
  const sub = (t: string) => {
    const c = comments.tables[t];
    if (c) return c.length > 24 ? `${c.slice(0, 23)}…` : c;
    const pk = pkOf.get(t);
    return pk ? `PK: ${pk.cols.join(', ')}` : '';
  };

  const { shown, hidden } = useMemo(() => neighborhood(center, edges, depth, siblings), [center, edges, depth, siblings]);
  const shownEdges = useMemo(
    () => edges.filter((e) => shown.has(e.from) && shown.has(e.to) && (siblings || e.kind === 'parent')),
    [edges, shown, siblings],
  );
  const [laid, setLaid] = useState<Laid | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let active = true;
    setError(null);
    track(layoutWithElk([...shown], shownEdges, label, sub)).then(
      (l) => active && setLaid(l),
      (e) => active && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      active = false;
    };
  }, [shown, shownEdges, oneSchema, pkOf, comments]);

  // 中心の表が見えるようにスクロールする（配置し直したとき）
  const scroller = useRef<HTMLDivElement>(null);
  useDragScroll(scroller);
  const zoomRef = useRef(zoom);
  zoomRef.current = zoom;
  useEffect(() => {
    const el = scroller.current;
    const c = laid?.nodes.find((n) => n.table === center);
    if (!el || !c) return;
    el.scrollLeft = (c.x + c.w / 2) * zoomRef.current - el.clientWidth / 2;
    el.scrollTop = (c.y + NODE_H / 2) * zoomRef.current - el.clientHeight / 2;
  }, [laid]);
  /** 拡大・縮小しても、この点（画面の中の位置）の下の図の位置を動かさない */
  const zoomAt = useRef<{ px: number; py: number; x: number; y: number } | null>(null);
  const changeZoom = (next: number, px?: number, py?: number) => {
    const el = scroller.current;
    const z = Math.min(2, Math.max(0.3, Math.round(next * 100) / 100));
    if (el) {
      const x = px ?? el.clientWidth / 2;
      const y = py ?? el.clientHeight / 2;
      zoomAt.current = { px: x, py: y, x: (el.scrollLeft + x) / zoomRef.current, y: (el.scrollTop + y) / zoomRef.current };
    }
    setZoom(z);
  };
  useLayoutEffect(() => {
    const el = scroller.current;
    const a = zoomAt.current;
    if (!el || !a) return;
    zoomAt.current = null;
    el.scrollLeft = a.x * zoom - a.px;
    el.scrollTop = a.y * zoom - a.py;
  }, [zoom]);
  // Ctrl（Mac は ⌘）＋ホイールで、マウスの位置を中心に拡大・縮小する
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      const r = el.getBoundingClientRect();
      changeZoom(zoomRef.current * (e.deltaY < 0 ? 1.1 : 1 / 1.1), e.clientX - r.left, e.clientY - r.top);
    };
    el.addEventListener('wheel', wheel, { passive: false });
    return () => el.removeEventListener('wheel', wheel);
  }, []);

  // マウスを乗せた表と、つながる表
  const near = useMemo(() => {
    if (!hover) return null;
    const s = new Set([hover]);
    for (const e of shownEdges) if (e.from === hover || e.to === hover) s.add(e.from === hover ? e.to : e.from);
    return s;
  }, [hover, shownEdges]);

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
          <button type="button" className="icon" title="縮小" onClick={() => changeZoom(zoom - 0.1)}>
            −
          </button>
          <button type="button" className="link" title="元の大きさ" onClick={() => changeZoom(1)}>
            {Math.round(zoom * 100)}%
          </button>
          <button type="button" className="icon" title="拡大" onClick={() => changeZoom(zoom + 0.1)}>
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
          手動
        </span>
        {hidden > 0 && <span className="hint">多すぎる {hidden} 表は省きました</span>}
      </div>
      <div className="relation-diagram-scroll" ref={scroller}>
        {error && <p className="error-text">図を配置できません: {error}</p>}
        {!laid && !error && <p className="hint">配置しています…</p>}
        {laid && (
          <svg
            width={laid.width * zoom}
            height={laid.height * zoom}
            viewBox={`0 0 ${laid.width} ${laid.height}`}
            role="img"
            aria-label={`${label(center)} の関係図`}
            className={near ? 'hovering' : undefined}
          >
            <defs>
              <marker id="rd-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M0,0 L10,5 L0,10 z" className="edge-arrow" />
              </marker>
              <marker id="rd-arrow-lit" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M0,0 L10,5 L0,10 z" className="edge-arrow lit" />
              </marker>
              <marker
                id="rd-arrow-manual"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="7"
                markerHeight="7"
                orient="auto-start-reverse"
              >
                <path d="M0,0 L10,5 L0,10 z" className="edge-arrow manual" />
              </marker>
            </defs>
            {laid.edges.map(({ edge: e, points }) => {
              const d = roundedPath(points);
              // 親子の矢印は親の側（線の始まり。親 → 子の向きで配置している）に付ける
              const lit = hover !== null && (e.from === hover || e.to === hover);
              const marker =
                e.kind !== 'parent' ? undefined : lit ? 'url(#rd-arrow-lit)' : e.manual ? 'url(#rd-arrow-manual)' : 'url(#rd-arrow)';
              const cls = ['edge', e.kind, e.manual ? 'manual' : '', open?.edge === e ? 'active' : '', lit ? 'lit' : '']
                .filter(Boolean)
                .join(' ');
              const cols = e.links.map((l) => `${l.a.slice(l.a.lastIndexOf('.') + 1)} = ${l.b.slice(l.b.lastIndexOf('.') + 1)}`).join(', ');
              return (
                <g key={`${e.kind} ${e.from} ${e.to}`}>
                  <path d={d} className={cls} markerStart={marker} />
                  {/* 押しやすいように、太い透明の線を重ねる */}
                  <path d={d} className="edge-hit" onClick={(ev) => setOpen({ edge: e, at: new DOMRect(ev.clientX, ev.clientY, 0, 0) })}>
                    <title>
                      {label(e.from)} {e.kind === 'parent' ? '→' : '＝'} {label(e.to)}: {cols}（
                      {e.manual && e.statements === 0 ? '手で設定' : `${e.statements} 件の SQL`}）
                    </title>
                  </path>
                </g>
              );
            })}
            {laid.nodes.map((n) => (
              <g
                key={n.table}
                className={['node', n.table === center ? 'center' : '', near && !near.has(n.table) ? 'dim' : ''].filter(Boolean).join(' ')}
                transform={`translate(${n.x},${n.y})`}
                onClick={() => n.table !== center && onCenter(n.table)}
                onMouseEnter={(e) => {
                  setHover(n.table);
                  setHoverAt(e.currentTarget.getBoundingClientRect());
                }}
                onMouseLeave={() => {
                  setHover(null);
                  setHoverAt(null);
                }}
              >
                <rect width={n.w} height={NODE_H} rx="6" />
                <text x={n.w / 2} y={n.sub ? 19 : 28} className="node-name">
                  {fitText(n.label, n.w - NODE_PAD + 8, NAME_FONT)}
                </text>
                {n.sub && (
                  <text x={n.w / 2} y={36} className="node-sub">
                    {fitText(n.sub, n.w - NODE_PAD + 8, SUB_FONT)}
                  </text>
                )}
              </g>
            ))}
          </svg>
        )}
      </div>
      {shown.size === 1 && <p className="hint">この表には、推定したリレーションがありません。</p>}
      {hover && hoverAt && <NodeDetail table={hover} at={hoverAt} comment={comments.tables[hover]} pk={pkOf.get(hover)?.cols ?? []} />}
      {open && (
        <Popover anchor={open.at} onClose={() => setOpen(null)}>
          {edgeInfo(open.edge)}
        </Popover>
      )}
    </div>
  );
}

/** 表の詳細（マウスを乗せた箱のすぐ下に出す）: 表の名前、コメントの全文、主キーの列 */
function NodeDetail({ table, at, comment, pk }: { table: string; at: DOMRect; comment?: string; pk: string[] }) {
  const box = useRef<HTMLDivElement>(null);
  const [left, setLeft] = useState(at.left);
  useEffect(() => {
    const w = box.current?.offsetWidth ?? 0;
    setLeft(Math.max(8, Math.min(at.left, window.innerWidth - w - 8)));
  }, [at]);
  const below = at.bottom + 6;
  return createPortal(
    <div
      ref={box}
      className="node-detail"
      style={{ left, ...(below + 160 > window.innerHeight ? { bottom: window.innerHeight - at.top + 6 } : { top: below }) }}
    >
      <div className="node-detail-name">{table}</div>
      {comment && <div className="node-detail-comment">{comment}</div>}
      {pk.length > 0 && (
        <div className="node-detail-pk">
          <span className="badge pk">主キー</span>
          {pk.map((c) => (
            <code key={c}>{c}</code>
          ))}
        </div>
      )}
    </div>,
    document.body,
  );
}
