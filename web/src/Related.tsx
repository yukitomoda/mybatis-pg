// 関連データ: ある行から、リレーションをたどって関連する行を集める（結果ビューの中に出す）。
// 親（その行が参照する行）は自動で集め、子（その行を参照する行）は 1 段ずつ押してたどる
import { useEffect, useState } from 'react';
import type { Api, RelatedNeighbor, RelatedRows } from './api';
import { useDbComments } from './comments';
import { DataTable } from './DataTable';
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

const LABEL = { start: '起点', parent: '親', child: '子' } as const;

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
  const add = (n: RelatedNode) => update((e) => ({ ...e, nodes: [...e.nodes, n], selected: e.selected ?? n.id }));
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

  const node = entry.nodes.find((n) => n.id === entry.selected) ?? entry.nodes[0];
  // 選んだ項目の子（まだたどっていないもの）
  const [children, setChildren] = useState<RelatedNeighbor[]>([]);
  useEffect(() => {
    setChildren([]);
    if (!node) return;
    let active = true;
    neighborsOf(api, entry.connectionId, node.table).then(
      (ns) => active && setChildren(ns.filter((n) => n.direction === 'child')),
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [node?.id]);
  const expanded = (n: RelatedNeighbor) =>
    entry.nodes.some(
      (x) => x.parent === node?.id && x.direction === 'child' && x.table === n.table && x.via.startsWith(n.otherColumns.join(', ')),
    );

  // ツリーの並び（親子の順。深さで字下げする）
  const ordered: { n: RelatedNode; depth: number }[] = [];
  const walk = (parent: string | null, depth: number) => {
    for (const n of entry.nodes.filter((x) => x.parent === parent)) {
      ordered.push({ n, depth });
      walk(n.id, depth + 1);
    }
  };
  walk(null, 0);

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
        <ul className="related-tree">
          {ordered.map(({ n, depth }) => (
            <li key={n.id}>
              <button
                type="button"
                className={n.id === node?.id ? 'selected' : undefined}
                style={{ paddingLeft: `${0.4 + depth * 0.9}rem` }}
                title={[n.table, comments.tables[n.table], n.via].filter(Boolean).join('\n')}
                onClick={() => update((e) => ({ ...e, selected: n.id }))}
              >
                <span className={`related-kind ${n.direction}`}>{LABEL[n.direction]}</span>
                <span className="related-table">{n.table.slice(n.table.indexOf('.') + 1)}</span>
                <span className="count">
                  {n.rows.rows.length}
                  {n.rows.truncated && n.rows.total !== null ? `/${n.rows.total}` : ''}
                </span>
              </button>
            </li>
          ))}
        </ul>
        {node && (
          <div className="related-detail">
            <div className="related-node-head">
              <code>{node.table}</code>
              {comments.tables[node.table] && <span className="relations-comment">{comments.tables[node.table]}</span>}
              {node.via && <span className="hint">{node.via}</span>}
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
