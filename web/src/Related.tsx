// 関連データ: ある行から、リレーションをたどって関連する行を集める（結果ビューの中に出す）。
// 起点の親（その行が参照する行）は自動で集め、ほかは 1 段ずつ押してたどる。たどった 1 歩ごとに箱を作り、起点を中心にした図で見る
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { Api, RelatedNeighbor, RelatedRows } from './api';
import { useDbComments } from './comments';
import { DataTable } from './DataTable';
import { message } from './Execution';
import { NODE_H } from './RelationDiagram';

/** どのつながりでたどったか（たどった元から見た隣の表） */
type Step = {
  direction: 'parent' | 'child';
  table: string;
  columns: string[];
  otherColumns: string[];
  /** 紐づけた値（たどった先の列の名前で。列ごとに 1 行。id = 2 など） */
  match: string[];
  /** 紐づけた値の全部（線にマウスを乗せると出す） */
  matchAll: string;
};

/** 集めた表（図の 1 つの箱。たどった 1 歩ごとに作る） */
export interface RelatedNode {
  id: string;
  table: string;
  /** たどってきた箱（起点なら null） */
  parent: string | null;
  direction: 'start' | 'parent' | 'child';
  /** たどってきたつながり（起点なら null） */
  step: Step | null;
  rows: RelatedRows;
  /** 元の箱の、選んだ行だけからたどった */
  partial?: boolean;
}

/** 関連データの 1 件（起点の行ごと） */
export interface RelatedEntry {
  key: string;
  connectionId: string;
  table: string;
  /** 起点の行（列 → 値） */
  row: Record<string, string | null>;
  /** 一覧に出す名前（erp.projects id=5 など） */
  label: string;
  nodes: RelatedNode[];
  /** 選んでいる箱 */
  selected: string | null;
  /** 集めている途中 */
  busy: boolean;
  error: string | null;
}

/** 起点から親をたどる段数の上限（自己参照や循環で、止まらなくならないように） */
const MAX_PARENT_DEPTH = 4;
/** 表ごとに引く行の上限 */
const LIMIT = 100;

const neighborCache = new Map<string, Promise<RelatedNeighbor[]>>();

function neighborsOf(api: Api, connectionId: string, table: string): Promise<RelatedNeighbor[]> {
  const key = `${connectionId}\u0000${table}`;
  let p = neighborCache.get(key);
  if (!p) {
    p = api.relatedNeighbors(connectionId, table).then((r) => r.neighbors);
    neighborCache.set(key, p);
    p.catch(() => neighborCache.delete(key));
  }
  return p;
}

/** 行から、列の値の組を取り出す */
function tuples(rows: RelatedRows, columns: string[]): (string | null)[][] {
  const at = columns.map((c) => rows.columns.indexOf(c));
  if (at.some((i) => i < 0)) return [];
  return rows.rows.map((r) => at.map((i) => r[i])).filter((t) => t.every((v) => v !== null));
}

const sameSet = (a: string[], b: string[]) => a.length === b.length && a.every((x) => b.includes(x));
const sameRows = (a: RelatedRows, b: RelatedRows) => {
  const keys = new Set(b.rows.map((r) => JSON.stringify(r)));
  return a.rows.length === b.rows.length && a.rows.every((r) => keys.has(JSON.stringify(r)));
};

/** 値を SQL の書き方にする（数は そのまま、ほかは '…'） */
const literal = (v: string | null) => (v === null ? 'NULL' : /^-?\d+(\.\d+)?$/.test(v) ? v : `'${v.replace(/'/g, "''")}'`);

/** 紐づけた値を、たどった先の列の名前で書く（短いものと、全部） */
function describeMatch(columns: string[], values: (string | null)[][]): { match: string[]; matchAll: string } {
  const one = (t: (string | null)[]) => columns.map((c, i) => `${c} = ${literal(t[i])}`).join(', ');
  if (values.length === 1) return { match: columns.map((c, i) => `${c} = ${literal(values[0][i])}`), matchAll: one(values[0]) };
  const all = values.map(one).join('\n');
  if (columns.length === 1) {
    const vs = values.map((t) => literal(t[0]));
    return { match: [`${columns[0]} IN (${vs.slice(0, 3).join(', ')}${vs.length > 3 ? ', …' : ''})`], matchAll: all };
  }
  return { match: [`(${columns.join(', ')})`, `が ${values.length} 組`], matchAll: all };
}

let seq = 0;
const newId = () => `n${++seq}`;

/** 1 歩たどって、箱を作る（たどる値がなければ null） */
async function step(api: Api, connectionId: string, from: RelatedNode, n: RelatedNeighbor): Promise<RelatedNode | null> {
  const values = tuples(from.rows, n.columns);
  if (values.length === 0) return null;
  const rows = await api.relatedRows(connectionId, n.table, { columns: n.otherColumns, values }, LIMIT);
  return {
    id: newId(),
    table: n.table,
    parent: from.id,
    direction: n.direction,
    step: {
      direction: n.direction,
      table: n.table,
      columns: n.columns,
      otherColumns: n.otherColumns,
      ...describeMatch(n.otherColumns, values),
    },
    rows,
  };
}

/** 起点の行を引き、起点の親を自動で集める。集めた箱は add で足していく（途中でも画面に出す） */
export async function startRelated(api: Api, e: RelatedEntry, add: (n: RelatedNode) => void): Promise<void> {
  const rows = await api.relatedRows(e.connectionId, e.table, { row: e.row }, 1);
  const root: RelatedNode = { id: newId(), table: e.table, parent: null, direction: 'start', step: null, rows };
  add(root);
  await collectParents(api, e.connectionId, root, add, [root]);
}

/** 起点から、親を自動でたどる。たどってきた道に同じ表の同じ行があれば、戻るだけなので足さない */
async function collectParents(api: Api, connectionId: string, node: RelatedNode, add: (n: RelatedNode) => void, path: RelatedNode[]) {
  if (path.length > MAX_PARENT_DEPTH || node.rows.rows.length === 0) return;
  for (const n of await neighborsOf(api, connectionId, node.table)) {
    if (n.direction !== 'parent') continue;
    const p = await step(api, connectionId, node, n);
    if (!p || p.rows.rows.length === 0) continue;
    if (path.some((x) => x.table === p.table && sameRows(x.rows, p.rows))) continue;
    add(p);
    await collectParents(api, connectionId, p, add, [...path, p]);
  }
}

/** 箱の幅（そろえる。長い名前は省く）と、列・行の間 */
const BOX_W = 170;
const COL_GAP = 150;
const ROW_GAP = 18;
const PAD = 16;

type Placed = { node: RelatedNode; col: number; row: number; x: number; y: number };

/**
 * 箱の位置を決める。起点は 0 列。親をたどった箱は元の箱の 1 列左、子をたどった箱は 1 列右。
 * 行は、起点から木をたどり（同じ元からたどった箱は、たどった順）、それぞれの箱の下に、その箱からたどった箱をまとめて置く。
 * 左（親）と右（子）は別々に積む。新しい箱は元の箱のそばに入り、その下の箱は下へずれる（並び順は保ち、線が交差しないようにする）
 */
export function placeNodes(nodes: RelatedNode[]): { placed: Placed[]; width: number; height: number; minCol: number } {
  const kids = new Map<string | null, RelatedNode[]>();
  for (const n of nodes) kids.set(n.parent, [...(kids.get(n.parent) ?? []), n]);
  const at = new Map<string, { col: number; row: number }>();
  /** 列ごとの、次に空いている行 */
  const nextFree = new Map<number, number>();
  /** 箱を置き、その箱と、その箱からたどった箱が使ったいちばん下の行を返す */
  const place = (n: RelatedNode, col: number, want: number): number => {
    const row = Math.max(want, nextFree.get(col) ?? 0);
    nextFree.set(col, row + 1);
    at.set(n.id, { col, row });
    let bottom = row;
    // 左（親）と右（子）は、それぞれ箱の行から下へ積む
    const cursor = { parent: row, child: row };
    for (const c of kids.get(n.id) ?? []) {
      const side = c.direction === 'parent' ? 'parent' : 'child';
      const b = place(c, col + (side === 'parent' ? -1 : 1), cursor[side]);
      cursor[side] = b + 1;
      bottom = Math.max(bottom, b);
    }
    return bottom;
  };
  let top = 0;
  for (const root of kids.get(null) ?? []) top = place(root, 0, top) + 1;
  const cols = [...at.values()].map((v) => v.col);
  const minCol = Math.min(0, ...cols);
  const maxCol = Math.max(0, ...cols);
  const maxRow = Math.max(0, ...[...at.values()].map((v) => v.row));
  const placed = nodes.map((node) => {
    const { col, row } = at.get(node.id)!;
    return { node, col, row, x: PAD + (col - minCol) * (BOX_W + COL_GAP), y: PAD + row * (NODE_H + ROW_GAP) };
  });
  return {
    placed,
    minCol,
    width: PAD * 2 + (maxCol - minCol + 1) * BOX_W + (maxCol - minCol) * COL_GAP,
    height: PAD * 2 + (maxRow + 1) * NODE_H + maxRow * ROW_GAP,
  };
}

/** 箱の名前を、幅に収まるように省く */
function fit(text: string, max = BOX_W - 16, half = 7.4): string {
  let w = 0;
  let out = '';
  for (const ch of text) {
    w += ch.charCodeAt(0) > 0xff ? half * 1.75 : half;
    if (w > max) return `${out}…`;
    out += ch;
  }
  return out;
}

export function RelatedPane({
  api,
  entry,
  update,
  onCell,
}: {
  api: Api;
  entry: RelatedEntry;
  /** この 1 件を変える */
  update: (f: (e: RelatedEntry) => RelatedEntry) => void;
  onCell?: (text: string | null, type: string) => void;
}) {
  const comments = useDbComments(api, entry.connectionId);
  /** 箱を足す。押してたどった箱は選ぶ（自動で集めた親は、選び直さない） */
  const add = (n: RelatedNode, select = false) =>
    update((e) => ({ ...e, nodes: [...e.nodes, n], selected: select ? n.id : (e.selected ?? n.id) }));
  const run = (f: () => Promise<void>) => {
    update((e) => ({ ...e, busy: true, error: null }));
    f().then(
      () => update((e) => ({ ...e, busy: false })),
      (err) => update((e) => ({ ...e, busy: false, error: message(err) })),
    );
  };
  // 初めて開いたときに、起点の行と、起点の親を集める
  useEffect(() => {
    if (entry.nodes.length === 0 && !entry.busy && !entry.error) run(() => startRelated(api, entry, (n) => add(n)));
  }, [entry.key]);

  const byId = new Map(entry.nodes.map((n) => [n.id, n]));
  const node = (entry.selected ? byId.get(entry.selected) : undefined) ?? entry.nodes[0];
  // 選んだ箱の隣の表（たどってきた元へ戻るつながりも出す。たどると、元とは別の箱になる）
  const [neighbors, setNeighbors] = useState<RelatedNeighbor[]>([]);
  useEffect(() => {
    setNeighbors([]);
    if (!node) return;
    let active = true;
    neighborsOf(api, entry.connectionId, node.table).then(
      (ns) => active && setNeighbors(ns),
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [node?.id]);
  // 箱ごとの、選んだ行（選んでいれば、その行だけからたどる）
  const [checkedByNode, setCheckedByNode] = useState<Map<string, Set<number>>>(new Map());
  const checked = (node && checkedByNode.get(node.id)) || new Set<number>();
  const setChecked = (rows: Set<number>) => node && setCheckedByNode((old) => new Map(old).set(node.id, rows));
  const done = (n: RelatedNeighbor) =>
    entry.nodes.some(
      (c) =>
        !c.partial &&
        c.parent === node?.id &&
        c.step?.direction === n.direction &&
        c.step.table === n.table &&
        sameSet(c.step.columns, n.columns),
    );
  const follow = (n: RelatedNeighbor) =>
    run(async () => {
      if (!node) return;
      // 行を選んでいれば、その行だけからたどる
      const from = checked.size > 0 ? { ...node, rows: { ...node.rows, rows: node.rows.rows.filter((_, i) => checked.has(i)) } } : node;
      const c = await step(api, entry.connectionId, from, n);
      if (c) add({ ...c, parent: node.id, partial: checked.size > 0 }, true);
    });

  const buttons = (direction: 'parent' | 'child', label: string) => {
    const list = neighbors.filter((n) => n.direction === direction);
    if (list.length === 0) return null;
    return (
      <div className="related-children">
        <span className="hint">
          {label}
          {checked.size > 0 && `（選んだ ${checked.size} 行から）`}
        </span>
        {list.map((n) => (
          <button
            key={`${n.table} ${n.columns.join(',')}`}
            type="button"
            disabled={entry.busy || (checked.size === 0 && done(n)) || !node || node.rows.rows.length === 0}
            title={[
              comments.tables[n.table],
              `${n.columns.join(', ')} → ${n.table}.${n.otherColumns.join(', ')}`,
              n.indexed === false ? 'インデックスがないので、表を全件読みます' : '',
            ]
              .filter(Boolean)
              .join('\n')}
            onClick={() => follow(n)}
          >
            {n.table.slice(n.table.indexOf('.') + 1)}
            {n.indexed === false && <span className="badge warn">全件</span>}
          </button>
        ))}
      </div>
    );
  };

  return (
    <div className="related">
      <div className="related-head">
        <h3>
          関連データ <code>{entry.label}</code>
        </h3>
        {entry.busy && <span className="hint">集めています…</span>}
      </div>
      {entry.error && <pre className="render-error">{entry.error}</pre>}
      <div className="related-body">
        <RelatedGraph
          nodes={entry.nodes}
          selected={node?.id ?? null}
          comments={comments.tables}
          onSelect={(id) => update((e) => ({ ...e, selected: id }))}
        />
        {node && (
          <div className="related-detail">
            <div className="related-node-head">
              <code>{node.table}</code>
              {comments.tables[node.table] && <span className="relations-comment">{comments.tables[node.table]}</span>}
            </div>
            {buttons('parent', '親をたどる')}
            {buttons('child', '子をたどる')}
            <DataTable
              columns={node.rows.columns}
              types={node.rows.columnTypes}
              rows={node.rows.rows}
              truncated={node.rows.truncated}
              titles={node.rows.columns.map((c) => comments.columns[`${node.table}.${c}`])}
              onCell={onCell}
              checked={checked}
              onCheck={setChecked}
            />
          </div>
        )}
      </div>
    </div>
  );
}

/** 集めた箱の図（起点を中心に、親を左、子を右に並べる。たどっても、すでにある箱は動かない。箱を押すと選ぶ） */
function RelatedGraph({
  nodes,
  selected,
  comments,
  onSelect,
}: {
  nodes: RelatedNode[];
  selected: string | null;
  comments: Record<string, string>;
  onSelect: (id: string) => void;
}) {
  const { placed, width, height, minCol } = placeNodes(nodes);
  const at = new Map(placed.map((p) => [p.node.id, p]));
  const box = useRef<HTMLDivElement>(null);

  // 左に列が増えると図全体が右へずれるので、そのぶんスクロールして、見えている箱をその場に留める
  const lastMinCol = useRef(minCol);
  useLayoutEffect(() => {
    const el = box.current;
    if (el && minCol < lastMinCol.current) el.scrollLeft += (lastMinCol.current - minCol) * (BOX_W + COL_GAP);
    lastMinCol.current = minCol;
  }, [minCol]);

  // 選んでいる箱が見えるようにスクロールする（足したときと、選び直したとき）
  const sel = selected ? at.get(selected) : undefined;
  useEffect(() => {
    const el = box.current;
    if (!el || !sel) return;
    if (sel.x < el.scrollLeft || sel.x + BOX_W > el.scrollLeft + el.clientWidth) el.scrollLeft = sel.x + BOX_W / 2 - el.clientWidth / 2;
    if (sel.y < el.scrollTop || sel.y + NODE_H > el.scrollTop + el.clientHeight) el.scrollTop = sel.y + NODE_H / 2 - el.clientHeight / 2;
  }, [selected, nodes.length]);

  return (
    <div className="related-graph relation-diagram" ref={box}>
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label="集めた表の図">
        <defs>
          <marker id="rg-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="edge-arrow" />
          </marker>
        </defs>
        {placed.map(({ node, x, y }) => {
          const p = node.parent ? at.get(node.parent) : undefined;
          if (!p) return null;
          // たどった元の箱から、この箱へ（横に並ぶので、向かい合う辺どうしを曲線でつなぐ）
          const right = x > p.x;
          const x1 = right ? p.x + BOX_W : p.x;
          const x2 = right ? x : x + BOX_W;
          const y1 = p.y + NODE_H / 2;
          const y2 = y + NODE_H / 2;
          const bend = (x2 - x1) / 2;
          // 矢印は、リレーションの親の側（親をたどった箱なら、この箱。子をたどった箱なら、元の箱）
          const toParent = node.direction === 'parent';
          return (
            <g key={node.id}>
              <path
                d={`M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`}
                className="edge parent"
                markerEnd={toParent ? 'url(#rg-arrow)' : undefined}
                markerStart={toParent ? undefined : 'url(#rg-arrow)'}
              />
              {node.step && (
                // 値は、たどった先の箱の入口に書く。左へたどった線は線の上に、右へたどった線は線の下に
                // （同じすき間に、向きの違う線の値が並んでも重ならないように）
                <text
                  x={right ? x2 - 6 : x2 + 6}
                  y={right ? y2 + 14 : y2 - 6 - (node.step.match.length - 1) * 13}
                  className="edge-label"
                  textAnchor={right ? 'end' : 'start'}
                >
                  <title>{node.step.matchAll}</title>
                  {node.step.match.map((line, i) => (
                    <tspan key={i} x={right ? x2 - 6 : x2 + 6} dy={i === 0 ? 0 : 13}>
                      {fit(line, COL_GAP - 8, 6.6)}
                    </tspan>
                  ))}
                </text>
              )}
            </g>
          );
        })}
        {placed.map(({ node, x, y }) => (
          <g
            key={node.id}
            className={['node', node.direction === 'start' ? 'origin' : '', node.id === selected ? 'selected' : '']
              .filter(Boolean)
              .join(' ')}
            transform={`translate(${x},${y})`}
            onClick={() => onSelect(node.id)}
          >
            <title>
              {[node.table, comments[node.table], node.step && `${node.step.columns.join(', ')} → ${node.step.otherColumns.join(', ')}`]
                .filter(Boolean)
                .join('\n')}
            </title>
            <rect width={BOX_W} height={NODE_H} rx="6" />
            <text x={BOX_W / 2} y={19} className="node-name">
              {fit(node.table.slice(node.table.indexOf('.') + 1))}
            </text>
            <text x={BOX_W / 2} y={36} className="node-sub">
              {node.rows.rows.length}
              {node.rows.truncated ? '+' : ''} 行
            </text>
          </g>
        ))}
      </svg>
    </div>
  );
}
