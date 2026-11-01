// 関連データ: ある行から、リレーションをたどって関連する行を集める（結果ビューの中に出す）。
// 起点の親（その行が参照する行）は自動で集め、ほかは 1 段ずつ押してたどる。たどった 1 歩ごとに箱を作り、起点を中心にした図で見る
import { useEffect, useRef, useState } from 'react';
import type { Api, RelatedNeighbor, RelatedRows } from './api';
import { useDbComments } from './comments';
import { DataTable } from './DataTable';
import { message } from './Execution';
import type { Laid, TableEdge } from './RelationDiagram';
import { layoutWithElk, NODE_H, roundedPath } from './RelationDiagram';

/** どのつながりでたどったか（たどった元から見た隣の表） */
type Step = { direction: 'parent' | 'child'; table: string; columns: string[]; otherColumns: string[] };

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
    step: { direction: n.direction, table: n.table, columns: n.columns, otherColumns: n.otherColumns },
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

/** 図の線（子の箱 → 親の箱） */
function edgesOf(nodes: RelatedNode[]): TableEdge[] {
  const out: TableEdge[] = [];
  for (const n of nodes) {
    if (!n.parent) continue;
    const [child, parent] = n.direction === 'parent' ? [n.parent, n.id] : [n.id, n.parent];
    out.push({ from: child, to: parent, kind: 'parent', links: [], statements: 0, manual: false });
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
  const done = (n: RelatedNeighbor) =>
    entry.nodes.some(
      (c) => c.parent === node?.id && c.step?.direction === n.direction && c.step.table === n.table && sameSet(c.step.columns, n.columns),
    );
  const follow = (n: RelatedNeighbor) =>
    run(async () => {
      const c = node && (await step(api, entry.connectionId, node, n));
      if (c) add(c, true);
    });

  const buttons = (direction: 'parent' | 'child', label: string) => {
    const list = neighbors.filter((n) => n.direction === direction);
    if (list.length === 0) return null;
    return (
      <div className="related-children">
        <span className="hint">{label}</span>
        {list.map((n) => (
          <button
            key={`${n.table} ${n.columns.join(',')}`}
            type="button"
            disabled={entry.busy || done(n) || !node || node.rows.rows.length === 0}
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
            />
          </div>
        )}
      </div>
    </div>
  );
}

/** 集めた箱の図（起点を中心に、親を左、子を右に並べる。箱を押すと選ぶ） */
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
  const [laid, setLaid] = useState<Laid | null>(null);
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const label = (id: string) => {
    const t = byId.get(id)!.table;
    return t.slice(t.indexOf('.') + 1);
  };
  const sub = (id: string) => {
    const r = byId.get(id)!.rows;
    return `${r.rows.length}${r.truncated ? '+' : ''} 行`;
  };
  const key = nodes.map((n) => `${n.id}:${n.parent}`).join(',');
  useEffect(() => {
    let active = true;
    // 結果ビューの中では図の場所が横長なので、親を左、子を右に並べる（起点が真ん中に来る）
    layoutWithElk(
      nodes.map((n) => n.id),
      edgesOf(nodes),
      label,
      sub,
      'RIGHT',
    ).then(
      (l) => active && setLaid(l),
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [key]);

  // 選んでいる箱が見えるようにスクロールする（配置し直したときと、選び直したとき）
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = box.current;
    const n = laid?.nodes.find((x) => x.table === selected);
    if (!el || !n) return;
    if (n.x < el.scrollLeft || n.x + n.w > el.scrollLeft + el.clientWidth) el.scrollLeft = n.x + n.w / 2 - el.clientWidth / 2;
    if (n.y < el.scrollTop || n.y + NODE_H > el.scrollTop + el.clientHeight) el.scrollTop = n.y + NODE_H / 2 - el.clientHeight / 2;
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
        {laid.nodes.map((n) => {
          // 配置の id は箱の id
          const node = byId.get(n.table);
          if (!node) return null;
          return (
            <g
              key={n.table}
              className={['node', node.direction === 'start' ? 'center' : '', n.table === selected ? 'selected' : '']
                .filter(Boolean)
                .join(' ')}
              transform={`translate(${n.x},${n.y})`}
              onClick={() => onSelect(n.table)}
            >
              <title>
                {[node.table, comments[node.table], node.step && `${node.step.columns.join(', ')} → ${node.step.otherColumns.join(', ')}`]
                  .filter(Boolean)
                  .join('\n')}
              </title>
              <rect width={n.w} height={NODE_H} rx="6" />
              <text x={n.w / 2} y={19} className="node-name">
                {n.label}
              </text>
              <text x={n.w / 2} y={36} className="node-sub">
                {n.sub}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
