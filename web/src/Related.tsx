// 関連データ: ある行から、リレーションをたどって関連する行を集める（結果ビューの中に出す）。
// 親（その行が参照する行）は自動で集め、子（その行を参照する行）は 1 段ずつ押してたどる
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Api, RelatedNeighbor, RelatedRows } from './api';
import { useDbComments } from './comments';
import { DataTable } from './DataTable';
import type { Laid, TableEdge } from './RelationDiagram';
import { layoutWithElk, NODE_H, roundedPath } from './RelationDiagram';
import { message } from './Execution';

/** 集めた表（ツリーの 1 つの項目） */
export interface RelatedNode {
  id: string;
  table: string;
  /** たどってきた項目（起点なら null） */
  parent: string | null;
  direction: 'start' | 'parent' | 'child';
  /** どの列でたどったか（client_id → id など） */
  via: string;
  rows: RelatedRows;
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
  /** 選んでいる表（schema.table） */
  selected: string | null;
  /** 集めている途中 */
  busy: boolean;
  error: string | null;
}

/** 親をたどる段数の上限（自己参照や循環で、止まらなくならないように） */
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

/** 集めた行（表ごとに、行を JSON にしたもの）。同じ行を集め直さないために使う */
type Seen = Map<string, Set<string>>;

function seenOf(nodes: RelatedNode[]): Seen {
  const seen: Seen = new Map();
  for (const n of nodes) markSeen(seen, n.table, n.rows.rows);
  return seen;
}

function markSeen(seen: Seen, table: string, rows: (string | null)[][]) {
  if (!seen.has(table)) seen.set(table, new Set());
  for (const r of rows) seen.get(table)!.add(JSON.stringify(r));
}

let seq = 0;
const newId = () => `n${++seq}`;

/**
 * 起点の行を引き、親を自動で集める。集めた項目は add で足していく（途中でも画面に出す）
 */
export async function startRelated(api: Api, e: RelatedEntry, add: (n: RelatedNode) => void): Promise<void> {
  const rows = await api.relatedRows(e.connectionId, e.table, { row: e.row }, 1);
  const root: RelatedNode = { id: newId(), table: e.table, parent: null, direction: 'start', via: '', rows };
  add(root);
  const seen: Seen = new Map();
  markSeen(seen, root.table, rows.rows);
  await collectParents(api, e.connectionId, root, add, 0, null, seen);
}

/** 子を 1 段たどって引き、その子の親も自動で集める */
export async function expandChild(
  api: Api,
  connectionId: string,
  node: RelatedNode,
  n: RelatedNeighbor,
  add: (n: RelatedNode) => void,
  collected: RelatedNode[],
) {
  const values = tuples(node.rows, n.columns);
  const rows = await api.relatedRows(connectionId, n.table, { columns: n.otherColumns, values }, LIMIT);
  const child: RelatedNode = {
    id: newId(),
    table: n.table,
    parent: node.id,
    direction: 'child',
    via: `${n.otherColumns.join(', ')} → ${n.columns.join(', ')}`,
    rows,
  };
  add(child);
  const seen = seenOf(collected);
  markSeen(seen, child.table, rows.rows);
  // たどってきた表へ戻る親（子 → 元の表）は、もう集めてあるので引かない
  await collectParents(api, connectionId, child, add, 0, { table: node.table, columns: n.otherColumns }, seen);
}

async function collectParents(
  api: Api,
  connectionId: string,
  node: RelatedNode,
  add: (n: RelatedNode) => void,
  depth: number,
  skip: { table: string; columns: string[] } | null,
  seen: Seen,
) {
  if (depth >= MAX_PARENT_DEPTH || node.rows.rows.length === 0) return;
  const neighbors = await neighborsOf(api, connectionId, node.table);
  for (const n of neighbors) {
    if (n.direction !== 'parent') continue;
    if (skip && n.table === skip.table && sameSet(n.columns, skip.columns)) continue;
    const values = tuples(node.rows, n.columns);
    if (values.length === 0) continue;
    const got = await api.relatedRows(connectionId, n.table, { columns: n.otherColumns, values }, LIMIT);
    // もう集めた行は除く（自己参照や 1 対 1 で、同じ行を行き来しないように）。新しい行がなければ、足さずに先もたどらない
    const already = seen.get(n.table) ?? new Set<string>();
    const fresh = got.rows.filter((r) => !already.has(JSON.stringify(r)));
    if (fresh.length === 0) continue;
    markSeen(seen, n.table, fresh);
    const rows = { ...got, rows: fresh };
    const parent: RelatedNode = {
      id: newId(),
      table: n.table,
      parent: node.id,
      direction: 'parent',
      via: `${n.columns.join(', ')} → ${n.otherColumns.join(', ')}`,
      rows,
    };
    add(parent);
    await collectParents(api, connectionId, parent, add, depth + 1, null, seen);
  }
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
  // 子をたどったら、その子の表を選ぶ（親は自動で集めるので、選んでいる表は変えない）
  const add = (n: RelatedNode) =>
    update((e) => ({ ...e, nodes: [...e.nodes, n], selected: n.direction === 'child' ? n.table : (e.selected ?? n.table) }));
  const run = (f: () => Promise<void>) => {
    update((e) => ({ ...e, busy: true, error: null }));
    f().then(
      () => update((e) => ({ ...e, busy: false })),
      (err) => update((e) => ({ ...e, busy: false, error: message(err) })),
    );
  };
  // 初めて開いたときに、起点の行と親を集める
  useEffect(() => {
    if (entry.nodes.length === 0 && !entry.busy && !entry.error) run(() => startRelated(api, entry, add));
  }, [entry.key]);

  // 表ごとにまとめた行と、表どうしの線（どの経路で集めたかによらず、表は 1 つにまとめる）
  const tables = useMemo(() => mergeByTable(entry.nodes), [entry.nodes]);
  const edges = useMemo(() => edgesOf(entry.nodes), [entry.nodes]);
  const selected = entry.selected && tables.has(entry.selected) ? entry.selected : entry.table;
  const merged = tables.get(selected);
  // 子をたどるときの元（選んだ表の、集めた行すべて）
  const node: RelatedNode | undefined = merged && {
    id: entry.nodes.find((n) => n.table === selected)!.id,
    table: selected,
    parent: null,
    direction: 'start',
    via: '',
    rows: merged,
  };
  // 選んだ表の子（まだたどっていないもの）
  const [children, setChildren] = useState<RelatedNeighbor[]>([]);
  useEffect(() => {
    setChildren([]);
    if (!merged) return;
    let active = true;
    neighborsOf(api, entry.connectionId, selected).then(
      (ns) => active && setChildren(ns.filter((n) => n.direction === 'child')),
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [selected, !!merged]);
  const tableOfNode = new Map(entry.nodes.map((n) => [n.id, n.table]));
  const expanded = (n: RelatedNeighbor) =>
    entry.nodes.some(
      (x) =>
        x.direction === 'child' &&
        x.table === n.table &&
        x.parent !== null &&
        tableOfNode.get(x.parent) === selected &&
        x.via.startsWith(n.otherColumns.join(', ')),
    );

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
          tables={tables}
          edges={edges}
          start={entry.table}
          selected={selected}
          comments={comments.tables}
          onSelect={(t) => update((e) => ({ ...e, selected: t }))}
        />
        {node && (
          <div className="related-detail">
            <div className="related-node-head">
              <code>{node.table}</code>
              {comments.tables[node.table] && <span className="relations-comment">{comments.tables[node.table]}</span>}
            </div>
            {children.length > 0 && (
              <div className="related-children">
                <span className="hint">子をたどる</span>
                {children.map((n) => (
                  <button
                    key={`${n.table} ${n.otherColumns.join(',')}`}
                    type="button"
                    disabled={entry.busy || expanded(n) || node.rows.rows.length === 0}
                    title={[
                      comments.tables[n.table],
                      `${n.table}.${n.otherColumns.join(', ')}`,
                      n.indexed === false ? 'インデックスがないので、表を全件読みます' : '',
                    ]
                      .filter(Boolean)
                      .join('\n')}
                    onClick={() => run(() => expandChild(api, entry.connectionId, node, n, add, entry.nodes))}
                  >
                    {n.table.slice(n.table.indexOf('.') + 1)}
                    {n.indexed === false && <span className="badge warn">全件</span>}
                  </button>
                ))}
              </div>
            )}
            <DataTable
              columns={node.rows.columns}
              types={node.rows.columnTypes}
              rows={node.rows.rows}
              truncated={node.rows.truncated}
              titles={node.rows.columns.map((c) => comments.columns[`${node.table}.${c}`])}
              onCell={onCell}
            />
          </div>
        )}
      </div>
    </div>
  );
}

/** 表ごとにまとめた行（同じ行は 1 つに） */
function mergeByTable(nodes: RelatedNode[]): Map<string, RelatedRows> {
  const out = new Map<string, RelatedRows & { seen: Set<string> }>();
  for (const n of nodes) {
    let t = out.get(n.table);
    if (!t) {
      t = { ...n.rows, rows: [], truncated: false, total: null, seen: new Set() };
      out.set(n.table, t);
    }
    t.truncated ||= n.rows.truncated;
    for (const r of n.rows.rows) {
      const k = JSON.stringify(r);
      if (!t.seen.has(k)) {
        t.seen.add(k);
        t.rows.push(r);
      }
    }
  }
  return new Map([...out].map(([k, { seen: _seen, ...rows }]) => [k, rows]));
}

/** 表どうしの線（子の表 → 親の表。どの列でたどったかをまとめる） */
function edgesOf(nodes: RelatedNode[]): TableEdge[] {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const out = new Map<string, TableEdge>();
  for (const n of nodes) {
    const p = n.parent ? byId.get(n.parent) : undefined;
    if (!p) continue;
    // 親としてたどったなら n が親、子としてたどったなら n が子
    const [child, parent] = n.direction === 'parent' ? [p.table, n.table] : [n.table, p.table];
    const key = `${child}\u0000${parent}\u0000${n.via}`;
    if (!out.has(key)) out.set(key, { from: child, to: parent, kind: 'parent', links: [], statements: 0, manual: false });
  }
  return [...out.values()];
}

/** 集めた表の図（箱を押すと、その表を選ぶ） */
function RelatedGraph({
  tables,
  edges,
  start,
  selected,
  comments,
  onSelect,
}: {
  tables: Map<string, RelatedRows>;
  edges: TableEdge[];
  start: string;
  selected: string;
  comments: Record<string, string>;
  onSelect: (table: string) => void;
}) {
  const [laid, setLaid] = useState<Laid | null>(null);
  const names = [...tables.keys()];
  const label = (t: string) => t.slice(t.indexOf('.') + 1);
  const sub = (t: string) => {
    const r = tables.get(t)!;
    return `${r.rows.length}${r.truncated ? '+' : ''} 行`;
  };
  const key = names.join(',') + '|' + names.map(sub).join(',') + '|' + edges.map((e) => `${e.from}>${e.to}`).join(',');
  useEffect(() => {
    let active = true;
    // 結果ビューの中では図の場所が横長なので、親を左、子を右に並べる
    layoutWithElk(names, edges, label, sub, 'RIGHT').then(
      (l) => active && setLaid(l),
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [key]);
  // 選んでいる表が見えるようにスクロールする（配置し直したときと、選び直したとき）
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = box.current;
    const n = laid?.nodes.find((x) => x.table === selected);
    if (!el || !n) return;
    const left = n.x + n.w / 2 - el.clientWidth / 2;
    const top = n.y + NODE_H / 2 - el.clientHeight / 2;
    if (n.x < el.scrollLeft || n.x + n.w > el.scrollLeft + el.clientWidth) el.scrollLeft = left;
    if (n.y < el.scrollTop || n.y + NODE_H > el.scrollTop + el.clientHeight) el.scrollTop = top;
  }, [laid, selected]);
  if (!laid) return <div className="related-graph" />;
  return (
    <div className="related-graph relation-diagram" ref={box}>
      <svg width={laid.width} height={laid.height} viewBox={`0 0 ${laid.width} ${laid.height}`} role="img" aria-label="集めた表の図">
        <defs>
          <marker id="rg-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="edge-arrow" />
          </marker>
        </defs>
        {laid.edges.map(({ points }, i) => (
          <path key={i} d={roundedPath(points)} className="edge parent" markerStart="url(#rg-arrow)" />
        ))}
        {laid.nodes.map((n) => (
          <g
            key={n.table}
            className={['node', n.table === start ? 'center' : '', n.table === selected ? 'selected' : ''].filter(Boolean).join(' ')}
            transform={`translate(${n.x},${n.y})`}
            onClick={() => onSelect(n.table)}
          >
            <title>{[n.table, comments[n.table]].filter(Boolean).join('\n')}</title>
            <rect width={n.w} height={NODE_H} rx="6" />
            <text x={n.w / 2} y={19} className="node-name">
              {n.label}
            </text>
            <text x={n.w / 2} y={36} className="node-sub">
              {n.sub}
            </text>
          </g>
        ))}
      </svg>
    </div>
  );
}
