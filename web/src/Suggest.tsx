// 値の候補（試作）: パラメータと比べられている列の、よく出てくる値を出し、選ぶと入れる
import { useEffect, useState } from 'react';
import type { SourceColumn } from './api';
import type { Connections } from './Execution';
import { message } from './Execution';
import type { PValue } from './params';

type Values = {
  type: string;
  estimatedRows: number;
  /** none: 統計情報によく出る値がなく、表を読んでいない */
  method: 'stats' | 'scan' | 'sample' | 'none';
  /** 列から始まるインデックスがある（入力中の値の行を自動で探しても軽い） */
  indexed: boolean;
  values: { value: string | null; count: number }[];
};

/** 列の値の候補は、開いている間は覚えておく（同じ列を何度も読まない） */
const cache = new Map<string, Promise<Values>>();

const keyOf = (connectionId: string, c: SourceColumn) => `${connectionId}\u0000${c.schema}.${c.table}.${c.column}`;

const NUMBER_TYPES = /^(smallint|integer|bigint|numeric|real|double precision|decimal)/;

/** 列の型に合わせて、選んだ値をパラメータの値にする */
export function toParam(text: string | null, type: string): PValue {
  if (text === null) return { t: 'null' };
  if (NUMBER_TYPES.test(type)) return { t: 'number', v: text };
  if (type.startsWith('timestamp'))
    return {
      t: 'timestamp',
      v: text.replace(/(\.\d+)?([+-]\d{2}(:\d{2})?)?$/, ''),
    };
  if (type === 'date') return { t: 'date', v: text };
  if (type === 'boolean') return { t: 'boolean', v: text === 't' || text === 'true' };
  if (/^(text|character|"char"|name|citext)/.test(type)) return { t: 'string', v: text };
  // 列挙型・uuid・jsonb など: その型でバインドする
  return { t: 'pg', type, v: text };
}

type Rows = {
  columns: string[];
  rows: (string | null)[][];
  currentRows: (string | null)[][];
  currentError: string | null;
};

/** 表に出す候補の数 */
const MAX_ROWS = 15;

/** 入力中の値を、列と比べる文字にする（なければ null） */
function currentText(v: PValue | undefined): string | null {
  if (!v) return null;
  switch (v.t) {
    case 'string':
    case 'number':
    case 'timestamp':
    case 'date':
    case 'pg':
      return v.v.trim() === '' ? null : v.v;
    case 'boolean':
      return String(v.v);
    default:
      return null;
  }
}

const METHOD_LABELS: Record<Values['method'], string> = {
  stats: '統計情報のよく出る値',
  scan: '表を読んで数えた',
  sample: '表の一部を読んで数えた',
  none: '統計情報によく出る値がありません',
};

const ROLE_LABELS: Record<SourceColumn['role'], string> = {
  origin: '（源流）',
  compared: '（比べている列）',
  name: '（同じ名前のパラメータから）',
};

export function SuggestPanel({
  conn,
  columns,
  relations,
  onOpenRelation,
  list,
  current,
  onPick,
}: {
  conn: Connections;
  columns: SourceColumn[];
  /** リレーションを解析してあるか */
  relations: boolean;
  /** リレーションの画面で、列（schema.table.column）を開く */
  onOpenRelation: (column: string) => void;
  /** リストの項目（foreach）。選んだ値を要素として足す */
  list: boolean;
  current: PValue | undefined;
  onPick: (v: PValue) => void;
}) {
  const [index, setIndex] = useState(0);
  const column = columns[Math.min(index, columns.length - 1)];
  const [values, setValues] = useState<Values | null>(null);
  const [error, setError] = useState<string | null>(null);
  const connectionId = conn.current?.id;

  useEffect(() => {
    if (!connectionId) return;
    let active = true;
    setValues(null);
    setError(null);
    const key = keyOf(connectionId, column);
    let p = cache.get(key);
    if (!p) {
      // 統計情報に値がなければ、表を読んで数える（虫眼鏡を押したときは、候補が出ることを期待しているので）
      p = conn.api.columnValues(connectionId, column, true);
      cache.set(key, p);
      p.catch(() => cache.delete(key));
    }
    p.then(
      (v) => active && setValues(v),
      (e) => active && setError(message(e)),
    );
    return () => {
      active = false;
    };
  }, [connectionId, column.schema, column.table, column.column]);

  // 候補の値ごとの行（表のすべての列）。候補が決まったら 1 回だけ読む
  const [rows, setRows] = useState<{
    columns: string[];
    rows: (string | null)[][];
  } | null>(null);
  const top = values ? values.values.slice(0, MAX_ROWS) : [];
  useEffect(() => {
    setRows(null);
    if (!connectionId || !values || values.values.length === 0) return;
    let active = true;
    conn.api
      .columnRows(
        connectionId,
        column,
        top.map((v) => v.value),
        null,
      )
      .then(
        (r) => active && setRows(r),
        (e) => active && setError(message(e)),
      );
    return () => {
      active = false;
    };
  }, [connectionId, values]);

  // 入力中の値に当たる行。インデックスがあれば値を変えるたびに自動で、なければ押したときだけ探す
  const now = list ? null : currentText(current);
  const [lookFor, setLookFor] = useState<string | null>(null);
  const auto = values?.indexed ?? false;
  const [found, setFound] = useState<{
    columns: string[];
    rows: (string | null)[][];
    error: string | null;
  } | null>(null);
  useEffect(() => {
    setFound(null);
    if (!connectionId || !values || now === null || (!auto && lookFor !== now)) return;
    let active = true;
    const timer = window.setTimeout(
      () => {
        conn.api.columnRows(connectionId, column, [], now).then(
          (r) =>
            active &&
            setFound({
              columns: r.columns,
              rows: r.currentRows,
              error: r.currentError,
            }),
          (e) => active && setError(message(e)),
        );
      },
      auto ? 300 : 0,
    );
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [connectionId, values, now, auto, lookFor]);

  const pick = (text: string | null) => {
    if (!values) return;
    const v = toParam(text, values.type);
    onPick(
      list
        ? {
            t: 'list',
            items: [...(current?.t === 'list' ? current.items : []), v],
          }
        : v,
    );
  };
  const table: Rows | null =
    rows || found
      ? {
          columns: rows?.columns.length ? rows.columns : (found?.columns ?? []),
          rows: rows?.rows ?? [],
          currentRows: found?.rows ?? [],
          currentError: found?.error ?? null,
        }
      : null;

  const hasOrigin = columns.some((x) => x.role === 'origin');
  return (
    <div className="suggest-panel">
      <div className="suggest-head">
        {columns.length > 1 ? (
          <select aria-label="比べている列" value={index} onChange={(e) => setIndex(Number(e.target.value))}>
            {columns.map((c, i) => (
              <option key={i} value={i}>
                {c.table}.{c.column}
                {ROLE_LABELS[c.role]}
              </option>
            ))}
          </select>
        ) : (
          <code>
            {column.table}.{column.column}
          </code>
        )}
        {column.role === 'origin' ? (
          <span className="suggest-condition" title="リレーションでたどった経路">
            リレーション: {column.condition}（{column.manual ? '手動' : `${column.count} 件の SQL`}）
          </span>
        ) : column.role === 'name' ? (
          <span className="suggest-condition" title="名前が同じパラメータは、同じものを指しているとみなしています">
            {column.condition}（{column.count} 件の SQL）
          </span>
        ) : (
          <code className="suggest-condition" title={column.condition}>
            {column.condition}
          </code>
        )}
        {/* 比べている列のリレーション（なければ、その列を子にして追加する） */}
        <button
          type="button"
          className="icon suggest-relation"
          title={hasOrigin ? 'リレーションを見る' : 'リレーションを追加'}
          aria-label={hasOrigin ? 'リレーションを見る' : 'リレーションを追加'}
          onClick={() => {
            const c = columns.find((x) => x.role === 'compared') ?? column;
            onOpenRelation(`${c.schema}.${c.table}.${c.column}`);
          }}
        >
          <svg width="13" height="11" viewBox="0 0 18 14" aria-hidden="true">
            <rect x="1" y="1" width="6" height="5" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
            <rect x="11" y="8" width="6" height="5" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
            <path d="M7 3.5h3a1.5 1.5 0 0 1 1.5 1.5v3" fill="none" stroke="currentColor" strokeWidth="1.6" />
          </svg>
        </button>
      </div>
      {!relations && <p className="hint">リレーションを解析すると、源流の表（この列が指している表）の値も出せます。</p>}
      {!column.direct && <p className="hint">列を式で包んで比べているので、列の値がそのまま合わないことがあります。</p>}
      {error && <p className="error-text">{error}</p>}
      {!values && !error && <p className="hint">読み込んでいます…</p>}
      {values && (
        <>
          {table ? (
            <SuggestRows rows={table} column={column.column} counts={new Map(top.map((v) => [v.value, v.count]))} onPick={pick} />
          ) : (
            values.values.length > 0 && <p className="hint">行を読み込んでいます…</p>
          )}
          <div className="suggest-actions">
            {now !== null && !auto && lookFor !== now && (
              <button
                type="button"
                className="link"
                onClick={() => setLookFor(now)}
                title="列にインデックスがないので、押したときだけ探します"
              >
                入力中の値の行を探す
              </button>
            )}
            <span className="hint suggest-method">
              {METHOD_LABELS[values.method]}（全 {values.estimatedRows.toLocaleString('ja-JP')} 行ほど）
            </span>
          </div>
        </>
      )}
    </div>
  );
}

/** 候補の行の表。入力中の値の行を上に、色を変えて出す。行を押すと、比べている列の値を入れる */
function SuggestRows({
  rows,
  column,
  counts,
  onPick,
}: {
  rows: Rows;
  column: string;
  counts: Map<string | null, number>;
  onPick: (text: string | null) => void;
}) {
  const at = rows.columns.indexOf(column);
  if (rows.columns.length === 0) return <p className="hint">行がありません</p>;
  const row = (r: (string | null)[], i: number, isCurrent: boolean) => (
    <tr
      key={`${isCurrent ? 'c' : 'v'}${i}`}
      className={isCurrent ? 'current' : undefined}
      onClick={() => onPick(r[at])}
      title="この値を入れる"
    >
      <td className="num">{isCurrent ? '入力中' : (counts.get(r[at])?.toLocaleString('ja-JP') ?? '')}</td>
      {r.map((v, j) => (
        <td key={j} className={j === at ? 'target' : undefined} title={v ?? 'NULL'}>
          {v ?? <span className="null">NULL</span>}
        </td>
      ))}
    </tr>
  );
  return (
    <div className="suggest-table">
      <table>
        <thead>
          <tr>
            <th className="num">件数</th>
            {rows.columns.map((c, j) => (
              <th key={j} className={j === at ? 'target' : undefined}>
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.currentRows.map((r, i) => row(r, i, true))}
          {/* 件数の多い順 */}
          {[...rows.rows].sort((a, b) => (counts.get(b[at]) ?? 0) - (counts.get(a[at]) ?? 0)).map((r, i) => row(r, i, false))}
        </tbody>
      </table>
      {rows.currentError && <p className="hint">入力中の値で探せません: {rows.currentError}</p>}
    </div>
  );
}
