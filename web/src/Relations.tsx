// リレーション（試作）: statement の結合の条件から推し量った、テーブルどうしのつながりを見る
import { useEffect, useMemo, useState } from 'react';
import type { Api, RelationLink, RelationsResult, RelationsTiming } from './api';
import type { Connections } from './Execution';
import { ConnectionPicker, message } from './Execution';
import { SideSplitter, useSideWidth } from './Splitter';
import type { GlobalSet } from './globalModel';
import { resolveTemplates } from './gen';

/** 解析に使う共通パラメータの設定（ブラウザに覚える） */
const SET_KEY = 'mybatis-pg.relations.set';

function loadSetId(): string {
  try {
    return localStorage.getItem(SET_KEY) ?? '';
  } catch {
    return '';
  }
}

/** schema.table.column の表の部分（schema.table） */
const tableOf = (col: string) => col.slice(0, col.lastIndexOf('.'));
/** 表示用。schema が 1 つだけなら省く */
const shorten = (col: string, oneSchema: boolean) => (oneSchema ? col.slice(col.indexOf('.') + 1) : col);

/** 時間を「1 時間 5 分」「3 分」「40 秒」の形にする */
export function duration(ms: number): string {
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `${sec} 秒`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min} 分`;
  return `${Math.floor(min / 60)} 時間${min % 60 ? ` ${min % 60} 分` : ''}`;
}

/** 1 件あたりの時間と、そのうち計画を作った時間 */
function perStatement(t: RelationsTiming): string {
  if (t.perStatementMillis === undefined) return '';
  return `（1 件 ${(t.perStatementMillis / 1000).toFixed(2)} 秒、うち計画 ${((t.planPerStatementMillis ?? 0) / 1000).toFixed(2)} 秒）`;
}

export function RelationsScreen({
  api,
  conn,
  globalSets,
  active,
  onOpen,
  focus,
}: {
  api: Api;
  conn: Connections;
  /** 共通パラメータの設定（1 つ選んで、その値をすべての statement の解析に使う） */
  globalSets: GlobalSet[];
  active: boolean;
  onOpen: (id: string) => void;
  /** 開いたときに見る列（schema.table.column。クエリの画面のパラメータから飛んできたとき） */
  focus: string | null;
}) {
  const side = useSideWidth('relations');
  const connectionId = conn.current?.id;
  const [result, setResult] = useState<RelationsResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [table, setTable] = useState<string | null>(null);
  // パラメータから飛んできたら、その列の表を開く
  useEffect(() => {
    if (focus) setTable(tableOf(focus));
  }, [focus]);
  /** 手で設定したリレーションを変えて、読み直す */
  const change = (c: Parameters<Api['changeManualRelation']>[0]): Promise<void> =>
    api.changeManualRelation(c).then(load, (e) => setError(message(e)));

  const load = () => {
    if (!connectionId) return;
    api.relations(connectionId).then(
      (r) => {
        setResult(r);
        setError(null);
      },
      (e) => setError(message(e)),
    );
  };
  useEffect(() => {
    if (active) load();
  }, [active, connectionId]);
  // 解析中は、進み具合を読み直す
  const running = !!result?.progress;
  useEffect(() => {
    if (!running) return;
    const t = window.setInterval(load, 1000);
    return () => window.clearInterval(t);
  }, [running, connectionId]);

  const [setId, setSetId] = useState(loadSetId);
  const chosen = globalSets.find((g) => g.id === setId);
  const chooseSet = (id: string) => {
    setSetId(id);
    try {
      localStorage.setItem(SET_KEY, id);
    } catch {
      // 保存できない環境では、この表示中だけ覚える
    }
  };
  const start = () => {
    if (!connectionId) return;
    // {{date:now}} などの式は、値にしてから送る
    const params = chosen ? resolveTemplates(chosen.values, new Map()).value : undefined;
    api.analyzeRelations(connectionId, params).then(load, (e) => setError(message(e)));
  };
  const stop = () => {
    if (!connectionId) return;
    api.stopRelations(connectionId).then(load, (e) => setError(message(e)));
  };
  // 一時停止した後か、まだ解析していない statement がある（続きから解析できる）
  const partial = !!result?.updatedAt && (!result.complete || (result.pending ?? 0) > 0);

  const links = result?.links ?? [];
  const oneSchema = useMemo(() => new Set(links.flatMap((l) => [l.a, l.b]).map((c) => c.slice(0, c.indexOf('.')))).size <= 1, [links]);
  const s = (col: string) => shorten(col, oneSchema);
  // DB の表（リレーションがまだない表にも、追加できるように）
  const [allTables, setAllTables] = useState<string[]>([]);
  useEffect(() => {
    if (!active || !connectionId) return;
    api.dbTables(connectionId).then(
      (r) => setAllTables(r.tables),
      () => setAllTables([]),
    );
  }, [active, connectionId]);
  const tables = useMemo(() => {
    const count = new Map<string, number>();
    for (const l of links) {
      for (const t of new Set([tableOf(l.a), tableOf(l.b)])) count.set(t, (count.get(t) ?? 0) + 1);
    }
    if (focus && !count.has(tableOf(focus))) count.set(tableOf(focus), 0);
    // リレーションのある表を先に、ほかの表を後に
    for (const t of allTables) if (!count.has(t)) count.set(t, 0);
    return [...count.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  }, [links, focus, allTables]);
  const shown = tables.filter(([t]) => t.toLowerCase().includes(filter.trim().toLowerCase()));
  const origins = useMemo(() => new Map((result?.origins ?? []).map((o) => [o.column, o.origins])), [result]);

  const download = () => {
    if (!result) return;
    const blob = new Blob([toText(result, oneSchema)], {
      type: 'text/plain;charset=utf-8',
    });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'relations.txt';
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <div className="relations">
      <div className="relations-head">
        <h2>リレーション</h2>
        <ConnectionPicker conn={conn} />
        <select
          aria-label="解析に使う共通パラメータ"
          title="選んだ共通パラメータの値（スキーマを決める ${schema} など）を、すべての statement の解析に使います。値のない項目は仮の値で埋めます"
          value={chosen ? chosen.id : ''}
          onChange={(e) => chooseSet(e.target.value)}
          disabled={running}
        >
          <option value="">共通パラメータなし</option>
          {globalSets.map((g) => (
            <option key={g.id} value={g.id}>
              {g.name}
            </option>
          ))}
        </select>
        {running ? (
          <button type="button" disabled={result?.progress?.stopping} onClick={stop}>
            {result?.progress?.stopping ? '止めています…' : '一時停止'}
          </button>
        ) : (
          <button type="button" className="run" disabled={!connectionId} onClick={start}>
            {partial ? '続きから解析する' : '解析する'}
          </button>
        )}
        {result && !running && result.updatedAt && (
          <button type="button" onClick={download}>
            テキストで書き出す
          </button>
        )}
      </div>
      {error && <p className="error-text">{error}</p>}
      {result?.progress && (
        <p className="hint">
          {result.progress.done} / {result.progress.total}
          {result.progress.remainingMillis !== undefined && `　残り約 ${duration(result.progress.remainingMillis)}`}
          {perStatement(result.progress)}　{result.progress.current}
        </p>
      )}
      {result && !running && (
        <p className="hint">
          {result.updatedAt
            ? `${new Date(result.updatedAt).toLocaleString('ja-JP')} に解析　statement ${result.statements} 件${partial ? `（途中まで。残り ${result.pending} 件）` : ''}　つながり ${links.length} 組　表 ${tables.filter(([, n]) => n > 0).length}${result.lastRun && result.lastRun.analyzed > 0 ? `　前回 ${result.lastRun.analyzed} 件を ${duration(result.lastRun.elapsedMillis)}${perStatement(result.lastRun)}` : ''}`
            : 'まだ解析していません。statement を EXPLAIN（実行はしない）して、結合の条件を集めます。'}
        </p>
      )}
      {result?.errors && result.errors.length > 0 && !running && (
        <details className="relations-errors">
          <summary>解析できなかった statement（{result.errors.length} 件）</summary>
          <ul>
            {result.errors.map((e) => (
              <li key={e.id}>
                <button type="button" className="link" onClick={() => onOpen(e.id)}>
                  {e.id}
                </button>
                <span className="hint"> {e.error.split('\n')[0]}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
      {connectionId && !running && (
        <div className="relations-body" style={side.style}>
          <div className="relations-tables">
            <input type="search" placeholder="表を絞り込む" value={filter} onChange={(e) => setFilter(e.target.value)} />
            <ul>
              {shown.map(([t, n]) => (
                <li key={t}>
                  <button type="button" className={t === table ? 'selected' : undefined} onClick={() => setTable(t)}>
                    <span>{oneSchema ? t.slice(t.indexOf('.') + 1) : t}</span>
                    <span className="count">{n || ''}</span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
          <SideSplitter label="表の一覧の幅" side={side} />
          <div className="relations-detail">
            {table ? (
              <TableRelations
                table={table}
                links={links}
                origins={origins}
                s={s}
                onOpen={onOpen}
                focus={focus}
                onChange={change}
                api={api}
                connectionId={connectionId}
                allTables={allTables}
              />
            ) : (
              <p className="hint">表を選んでください</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function TableRelations({
  table,
  links,
  origins,
  s,
  onOpen,
  focus,
  onChange,
  api,
  connectionId,
  allTables,
}: {
  api: Api;
  connectionId: string;
  /** DB の表（追加するときに選ぶ） */
  allTables: string[];
  table: string;
  links: RelationLink[];
  origins: Map<string, { column: string; path: string[]; count: number; manual: boolean }[]>;
  s: (col: string) => string;
  onOpen: (id: string) => void;
  /** 強調する列 */
  focus: string | null;
  onChange: (c: Parameters<Api['changeManualRelation']>[0]) => Promise<void>;
}) {
  const mine = links.filter((l) => tableOf(l.a) === table || tableOf(l.b) === table);
  // この表の列が子（親を指している）
  const parents = mine.filter((l) => l.kind === 'parent' && tableOf(l.a) === table);
  // ほかの表がこの表を指している
  const children = mine.filter((l) => l.kind === 'parent' && tableOf(l.b) === table && tableOf(l.a) !== table);
  const others = mine.filter((l) => l.kind !== 'parent');
  // 兄弟をたどって見つけた源流（直接の親は上に出ている）
  const cols = [...new Set(mine.flatMap((l) => [l.a, l.b]).filter((c) => tableOf(c) === table))].sort();
  const farOrigins = cols.flatMap((c) => (origins.get(c) ?? []).filter((o) => o.path.length > 1).map((o) => ({ col: c, ...o })));

  const row = (l: RelationLink, left: string, right: string, arrow: string) => (
    <tr
      key={`${l.a} ${l.b}`}
      className={
        [l.disabled ? 'disabled' : '', focus && (l.a === focus || l.b === focus) ? 'focus' : ''].filter(Boolean).join(' ') || undefined
      }
    >
      <td>
        <code>{s(left)}</code>
      </td>
      <td className="arrow">{arrow}</td>
      <td>
        <code>{s(right)}</code>
        {l.manual && <span className="badge">手動</span>}
      </td>
      <td>{l.statements.length > 0 && <Evidence ids={l.statements} onOpen={onOpen} />}</td>
      <td className="relations-actions">
        {l.manual ? (
          <button type="button" className="link" onClick={() => onChange({ action: 'remove', child: l.a, parent: l.b })}>
            削除
          </button>
        ) : l.disabled ? (
          <button type="button" className="link" onClick={() => onChange({ action: 'enable', a: l.a, b: l.b })}>
            有効に戻す
          </button>
        ) : (
          <button
            type="button"
            className="link"
            title="誤ったリレーションを、源流を探すときに使わないようにします"
            onClick={() => onChange({ action: 'disable', a: l.a, b: l.b })}
          >
            無効にする
          </button>
        )}
      </td>
    </tr>
  );
  return (
    <>
      <h3>親（この表の列が指している表）</h3>
      {parents.length > 0 && (
        <table className="relations-table">
          <tbody>{parents.map((l) => row(l, l.a, l.b, '→'))}</tbody>
        </table>
      )}
      <AddLink
        key={`parent ${table} ${focus}`}
        side="parent"
        api={api}
        connectionId={connectionId}
        allTables={allTables}
        table={table}
        initialColumn={focus && tableOf(focus) === table ? focus.slice(focus.lastIndexOf('.') + 1) : null}
        onAdd={(child, parent) => onChange({ action: 'add', child, parent })}
      />
      {farOrigins.length > 0 && (
        <>
          <h3>兄弟をたどった源流</h3>
          <table className="relations-table">
            <tbody>
              {farOrigins.map((o) => (
                <tr key={`${o.col} ${o.column}`}>
                  <td>
                    <code>{s(o.col)}</code>
                  </td>
                  <td className="arrow">→</td>
                  <td>
                    <code>{s(o.column)}</code>
                  </td>
                  <td className="hint">
                    {o.path
                      .slice(1)
                      .map((p) => s(p))
                      .join(' → ')}{' '}
                    経由（{o.manual ? '手動' : `${o.count} 件の SQL`}）
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      <h3>子（この表を指している表）</h3>
      {children.length > 0 && (
        <table className="relations-table">
          <tbody>{children.map((l) => row(l, l.b, l.a, '←'))}</tbody>
        </table>
      )}
      <AddLink
        key={`child ${table}`}
        side="child"
        api={api}
        connectionId={connectionId}
        allTables={allTables}
        table={table}
        initialColumn={null}
        onAdd={(child, parent) => onChange({ action: 'add', child, parent })}
      />
      {others.length > 0 && (
        <>
          <h3>兄弟・1 対 1（どちらが親か決められないもの）</h3>
          <table className="relations-table">
            <tbody>
              {others.map((l) => {
                const [left, right] = tableOf(l.a) === table ? [l.a, l.b] : [l.b, l.a];
                return row(l, left, right, l.kind === 'sibling' ? '＝' : '1:1');
              })}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}

/** 表の列を選ぶ（表は DB の一覧から入力欄の候補で、列は表を選んだら一覧から） */
function ColumnPicker({
  api,
  connectionId,
  tables,
  label,
  value,
  onChange,
}: {
  api: Api;
  connectionId: string;
  tables: string[];
  label: string;
  /** schema.table.column（選びかけなら schema.table.） */
  value: { table: string; column: string };
  onChange: (v: { table: string; column: string }) => void;
}) {
  const [columns, setColumns] = useState<string[]>([]);
  const known = tables.includes(value.table);
  useEffect(() => {
    setColumns([]);
    if (!known) return;
    let active = true;
    api.dbColumns(connectionId, value.table).then(
      (r) => active && setColumns(r.columns),
      () => active && setColumns([]),
    );
    return () => {
      active = false;
    };
  }, [connectionId, value.table, known]);
  const listId = `relation-tables-${label}`;
  return (
    <span className="relation-column">
      <input
        aria-label={`${label}の表`}
        placeholder="schema.table"
        list={listId}
        value={value.table}
        onChange={(e) => onChange({ table: e.target.value, column: '' })}
      />
      <datalist id={listId}>
        {tables.map((t) => (
          <option key={t} value={t} />
        ))}
      </datalist>
      <select
        aria-label={`${label}の列`}
        value={value.column}
        disabled={!known}
        onChange={(e) => onChange({ ...value, column: e.target.value })}
      >
        <option value="">列</option>
        {columns.map((c) => (
          <option key={c} value={c}>
            {c}
          </option>
        ))}
      </select>
    </span>
  );
}

/** 表の列を選ぶ（表は決まっている） */
function OwnColumn({
  api,
  connectionId,
  table,
  label,
  value,
  onChange,
}: {
  api: Api;
  connectionId: string;
  table: string;
  label: string;
  value: string;
  onChange: (column: string) => void;
}) {
  const [columns, setColumns] = useState<string[]>([]);
  useEffect(() => {
    let active = true;
    api.dbColumns(connectionId, table).then(
      (r) => active && setColumns(r.columns),
      () => active && setColumns([]),
    );
    return () => {
      active = false;
    };
  }, [connectionId, table]);
  return (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">列</option>
      {columns.map((c) => (
        <option key={c} value={c}>
          {c}
        </option>
      ))}
    </select>
  );
}

/**
 * 選んだ表のリレーションを手で追加する。parent: この表の列 → 親の列、child: 子の列 → この表の列
 */
function AddLink({
  side,
  api,
  connectionId,
  allTables,
  table,
  initialColumn,
  onAdd,
}: {
  side: 'parent' | 'child';
  api: Api;
  connectionId: string;
  allTables: string[];
  /** 選んだ表（schema.table） */
  table: string;
  /** この表の列（パラメータから飛んできたとき） */
  initialColumn: string | null;
  onAdd: (child: string, parent: string) => Promise<void>;
}) {
  const [open, setOpen] = useState(!!initialColumn);
  const [own, setOwn] = useState(initialColumn ?? '');
  const [other, setOther] = useState({ table: '', column: '' });
  if (!open) {
    return (
      <p className="relations-add-open">
        <button type="button" className="link" onClick={() => setOpen(true)}>
          {side === 'parent' ? '＋ 親を追加' : '＋ 子を追加'}
        </button>
      </p>
    );
  }
  const ownColumn = (
    <OwnColumn
      api={api}
      connectionId={connectionId}
      table={table}
      label={side === 'parent' ? 'この表の列（子）' : 'この表の列（親）'}
      value={own}
      onChange={setOwn}
    />
  );
  const otherColumn = (
    <ColumnPicker
      api={api}
      connectionId={connectionId}
      tables={allTables}
      label={side === 'parent' ? '親' : '子'}
      value={other}
      onChange={setOther}
    />
  );
  const ready = own !== '' && other.column !== '';
  const mine = `${table}.${own}`;
  const theirs = `${other.table}.${other.column}`;
  return (
    <div className="relations-add">
      {side === 'parent' ? ownColumn : otherColumn}
      <span className="arrow">→</span>
      {side === 'parent' ? otherColumn : ownColumn}
      <button
        type="button"
        disabled={!ready}
        onClick={() =>
          (side === 'parent' ? onAdd(mine, theirs) : onAdd(theirs, mine)).then(() => {
            setOther({ table: '', column: '' });
            setOpen(false);
          })
        }
      >
        追加
      </button>
      <button type="button" className="link" onClick={() => setOpen(false)}>
        閉じる
      </button>
    </div>
  );
}

/** 根拠の statement。件数を出し、押すと一覧を開く */
function Evidence({ ids, onOpen }: { ids: string[]; onOpen: (id: string) => void }) {
  return (
    <details className="relations-evidence">
      <summary>{ids.length} 件の SQL</summary>
      <ul>
        {ids.map((id) => (
          <li key={id}>
            <button type="button" className="link" onClick={() => onOpen(id)}>
              {id}
            </button>
          </li>
        ))}
      </ul>
    </details>
  );
}

/** 解析の結果をまとめたテキスト */
export function toText(r: RelationsResult, oneSchema: boolean): string {
  const s = (c: string) => shorten(c, oneSchema);
  const links = r.links ?? [];
  const lines: string[] = [];
  lines.push('# リレーション（SQL の結合の条件から推し量ったもの）');
  lines.push('');
  lines.push(
    `解析: ${r.updatedAt ? new Date(r.updatedAt).toLocaleString('ja-JP') : '-'}　statement ${r.statements ?? 0} 件　解析できなかったもの ${r.errors?.length ?? 0} 件　つながり ${links.length} 組`,
  );
  const section = (title: string, list: RelationLink[], fmt: (l: RelationLink) => string) => {
    lines.push('', `## ${title}（${list.length} 組）`, '');
    for (const l of list)
      lines.push(
        `${fmt(l)}${l.manual ? '（手動）' : ''}${l.disabled ? '（無効）' : ''}　[${l.statements.length} 件] ${l.statements.join(', ')}`,
      );
  };
  const byName = (x: RelationLink, y: RelationLink) => x.a.localeCompare(y.a) || x.b.localeCompare(y.b);
  section('親子（子の列 → 親の列）', links.filter((l) => l.kind === 'parent').sort(byName), (l) => `${s(l.a)} → ${s(l.b)}`);
  section('1 対 1（どちらも一意）', links.filter((l) => l.kind === 'one-to-one').sort(byName), (l) => `${s(l.a)} ＝ ${s(l.b)}`);
  section('兄弟（どちらも一意でない）', links.filter((l) => l.kind === 'sibling').sort(byName), (l) => `${s(l.a)} ＝ ${s(l.b)}`);
  const far = (r.origins ?? []).flatMap((o) => o.origins.filter((x) => x.path.length > 1).map((x) => ({ col: o.column, ...x })));
  lines.push('', `## 兄弟をたどった源流（${far.length} 件）`, '');
  for (const o of far) lines.push(`${s(o.col)} → ${s(o.column)}　（${o.path.slice(1).map(s).join(' → ')} 経由、${o.count} 件の SQL）`);
  lines.push('', `## 解析できなかった statement（${r.errors?.length ?? 0} 件）`, '');
  for (const e of r.errors ?? []) lines.push(`${e.id}: ${e.error.split('\n')[0]}`);
  return lines.join('\n') + '\n';
}
