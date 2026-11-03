// 組み立てた SQL の構造に色を付ける: サブクエリ（SQL から解析）と、<if>・<foreach> から出た範囲（サーバーが割り出したもの）
import { useMemo, useState, type ReactNode } from 'react';
import type { SqlSpan } from './api';
import { analyze, partLabel, type Part } from './subquery';

export type StructureKind = 'subquery' | 'if' | 'foreach' | 'item';
/** depth: 入れ子の深さ（範囲が同じとき、小さいほうを外側にする） */
type Range = { start: number; end: number; kind: StructureKind; label: string; depth: number };

/** サブクエリは SQL から割り出していて深さがないので、範囲が同じときは内側にする（if の中のサブクエリ） */
const SUBQUERY_DEPTH = 1_000_000;

/** 空白でない文字の位置（空白だけが違う 2 つの文字列で、位置を対応させる） */
function solid(s: string): number[] {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) if (!/\s/.test(s[i])) out.push(i);
  return out;
}

const lowerBound = (xs: number[], v: number) => {
  let lo = 0;
  let hi = xs.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if (xs[mid] < v) lo = mid + 1;
    else hi = mid;
  }
  return lo;
};

/** raw の中の範囲を、空白だけを整えた shown の中の位置に直す（空白以外が食い違えば空） */
export function mapSpans(raw: string, shown: string, spans: SqlSpan[]): Range[] {
  const a = solid(raw);
  const b = solid(shown);
  if (a.length !== b.length || a.some((i, k) => raw[i] !== shown[b[k]])) return [];
  const out: Range[] = [];
  for (const s of spans) {
    const first = lowerBound(a, s.start);
    const last = lowerBound(a, s.end) - 1;
    if (first <= last) out.push({ start: b[first], end: b[last] + 1, kind: s.kind, label: s.label, depth: s.depth ?? 0 });
  }
  return out;
}

/** サブクエリ・CTE の範囲（括弧の内側。メインのクエリは除く） */
export function subqueryRanges(sql: string): Range[] {
  const out: Range[] = [];
  const walk = (p: Part, top: boolean) => {
    if (!top) out.push({ start: p.start, end: p.end, kind: 'subquery', label: partLabel(p), depth: SUBQUERY_DEPTH });
    p.children.forEach((c) => walk(c, false));
  };
  try {
    walk(analyze(sql).root, true);
  } catch {
    return [];
  }
  return out;
}

export type Tree = { range: Range | null; children: Tree[] };

/** 範囲を入れ子に組む（前から、長いものを外側に。同じ範囲なら入れ子の浅いものを外側に。交差するものは除く） */
export function nest(ranges: Range[]): Tree {
  const sorted = [...ranges].sort((x, y) => x.start - y.start || y.end - x.end || x.depth - y.depth);
  const root: Tree = { range: null, children: [] };
  const stack: Tree[] = [root];
  for (const r of sorted) {
    while (stack.length > 1 && stack[stack.length - 1].range!.end <= r.start) stack.pop();
    const parent = stack[stack.length - 1];
    if (parent.range && r.end > parent.range.end) continue;
    const t = { range: r, children: [] };
    parent.children.push(t);
    stack.push(t);
  }
  return root;
}

/** 範囲の下に入れ子になっている段の数（自分を含む）。下線を重ねるとき、外側ほど下に引く */
function height(t: Tree): number {
  return 1 + Math.max(0, ...t.children.map(height));
}

function render(sql: string, t: Tree, from: number, to: number, seq: { item: number }): ReactNode[] {
  const out: ReactNode[] = [];
  let pos = from;
  t.children.forEach((c, i) => {
    const r = c.range!;
    if (r.start > pos) out.push(sql.slice(pos, r.start));
    const odd = r.kind === 'item' ? seq.item++ % 2 === 1 : false;
    out.push(
      <span
        key={`${r.start}-${r.end}-${i}`}
        className={`sql-mark sql-${r.kind}${odd ? ' odd' : ''}`}
        style={{ '--u': height(c) - 1 } as React.CSSProperties}
        title={r.label}
      >
        {render(sql, c, r.start, r.end, seq)}
      </span>,
    );
    pos = r.end;
  });
  if (pos < to) out.push(sql.slice(pos, to));
  return out;
}

/** マウスの下のいちばん内側の範囲だけを強調する（入れ子のどれを指しているか分かるように） */
function hover(e: React.MouseEvent<HTMLElement>) {
  const pre = e.currentTarget;
  const hot = (e.target as HTMLElement).closest('.sql-mark');
  pre.querySelectorAll('.sql-mark.hot').forEach((x) => x !== hot && x.classList.remove('hot'));
  hot?.classList.add('hot');
}

function leave(e: React.MouseEvent<HTMLElement>) {
  e.currentTarget.querySelectorAll('.sql-mark.hot').forEach((x) => x.classList.remove('hot'));
}

const KINDS: [string, string][] = [
  ['subquery', 'サブクエリ'],
  ['if', 'if'],
  ['foreachAll', 'foreach'],
];
const STORE = 'mybatis-pg.sql-structure';
const STYLE_STORE = 'mybatis-pg.sql-structure-style';

/** 線の引き方: bracket は範囲の両端の縦線、underline は外側ほど下に重ねる下線 */
export type StructureStyle = 'bracket' | 'underline';

function loadStyle(): StructureStyle {
  try {
    return localStorage.getItem(STYLE_STORE) === 'bracket' ? 'bracket' : 'underline';
  } catch {
    return 'underline';
  }
}

function loadShown(): Set<string> {
  try {
    const v = JSON.parse(localStorage.getItem(STORE) ?? 'null');
    if (Array.isArray(v)) return new Set(v);
  } catch {
    // 既定にする
  }
  return new Set(['subquery', 'if', 'foreachAll']);
}

/** 色を付けた SQL と、色の出し入れ（見出しの横に置く） */
export function useStructuredSql(raw: string, shown: string, spans: SqlSpan[] | undefined) {
  const [on, setOn] = useState(loadShown);
  const [style, setStyle] = useState<StructureStyle>(loadStyle);
  const changeStyle = (v: StructureStyle) => {
    setStyle(v);
    try {
      localStorage.setItem(STYLE_STORE, v);
    } catch {
      // この表示中だけ覚える
    }
  };
  const all = useMemo(() => [...mapSpans(raw, shown, spans ?? []), ...subqueryRanges(shown)], [raw, shown, spans]);
  const present = new Set<string>(all.map((r) => (r.kind === 'item' ? 'foreachAll' : r.kind === 'foreach' ? 'foreachAll' : r.kind)));
  const visible = all.filter((r) => on.has(r.kind === 'item' || r.kind === 'foreach' ? 'foreachAll' : r.kind));
  const body = render(shown, nest(visible), 0, shown.length, { item: 0 });
  const toggle = (k: string) => {
    const next = new Set(on);
    if (next.has(k)) next.delete(k);
    else next.add(k);
    setOn(next);
    try {
      localStorage.setItem(STORE, JSON.stringify([...next]));
    } catch {
      // この表示中だけ覚える
    }
  };
  const legend =
    present.size === 0 ? null : (
      <span className="sql-legend">
        {KINDS.filter(([k]) => present.has(k)).map(([k, label]) => (
          <label key={k} className={`sql-legend-${k}`}>
            <input type="checkbox" checked={on.has(k)} onChange={() => toggle(k)} />
            {label}
          </label>
        ))}
        <select aria-label="線の引き方" value={style} onChange={(e) => changeStyle(e.target.value as StructureStyle)}>
          <option value="underline">下線</option>
          <option value="bracket">縦線</option>
        </select>
      </span>
    );
  return { body, legend, className: `sql-structured sql-style-${style}`, onMouseOver: hover, onMouseLeave: leave };
}
