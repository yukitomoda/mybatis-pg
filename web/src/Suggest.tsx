// 値の候補（試作）: パラメータと比べられている列の、よく出てくる値を出し、選ぶと入れる
import { useEffect, useState } from 'react';
import type { SourceColumn } from './api';
import type { Connections } from './Execution';
import { message } from './Execution';
import type { PValue } from './params';

type Values = { type: string; estimatedRows: number; method: 'stats' | 'scan' | 'sample'; values: { value: string | null; count: number }[] };

/** 列の値の候補は、開いている間は覚えておく（同じ列を何度も読まない） */
const cache = new Map<string, Promise<Values>>();

const keyOf = (connectionId: string, c: SourceColumn) => `${connectionId}\u0000${c.schema}.${c.table}.${c.column}`;

const NUMBER_TYPES = /^(smallint|integer|bigint|numeric|real|double precision|decimal)/;

/** 列の型に合わせて、選んだ値をパラメータの値にする */
export function toParam(text: string | null, type: string): PValue {
  if (text === null) return { t: 'null' };
  if (NUMBER_TYPES.test(type)) return { t: 'number', v: text };
  if (type.startsWith('timestamp')) return { t: 'timestamp', v: text.replace(/(\.\d+)?([+-]\d{2}(:\d{2})?)?$/, '') };
  if (type === 'date') return { t: 'date', v: text };
  if (type === 'boolean') return { t: 'boolean', v: text === 't' || text === 'true' };
  if (/^(text|character|"char"|name|citext)/.test(type)) return { t: 'string', v: text };
  // 列挙型・uuid・jsonb など: その型でバインドする
  return { t: 'pg', type, v: text };
}

type Rows = { columns: string[]; rows: (string | null)[][]; currentRows: (string | null)[][]; currentError: string | null };

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
};

export function SuggestPanel({
  conn,
  columns,
  list,
  current,
  onPick,
}: {
  conn: Connections;
  columns: SourceColumn[];
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
      p = conn.api.columnValues(connectionId, column);
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

  // 候補の値ごとの行と、入力中の値に当たる行（表のすべての列）
  const [rows, setRows] = useState<Rows | null>(null);
  const top = values ? values.values.slice(0, MAX_ROWS) : [];
  const now = list ? null : currentText(current);
  useEffect(() => {
    if (!connectionId || !values) return;
    let active = true;
    const timer = window.setTimeout(() => {
      conn.api
        .columnRows(
          connectionId,
          column,
          top.map((v) => v.value),
          now,
        )
        .then(
          (r) => active && setRows(r),
          (e) => active && setError(message(e)),
        );
    }, 300);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [connectionId, values, now]);

  const pick = (text: string | null) => {
    if (!values) return;
    const v = toParam(text, values.type);
    onPick(list ? { t: 'list', items: [...(current?.t === 'list' ? current.items : []), v] } : v);
  };

  return (
    <div className="suggest-panel">
      <div className="suggest-head">
        {columns.length > 1 ? (
          <select aria-label="比べている列" value={index} onChange={(e) => setIndex(Number(e.target.value))}>
            {columns.map((c, i) => (
              <option key={i} value={i}>
                {c.table}.{c.column}
              </option>
            ))}
          </select>
        ) : (
          <code>
            {column.table}.{column.column}
          </code>
        )}
        <code className="suggest-condition" title={column.condition}>
          {column.condition}
        </code>
      </div>
      {!column.direct && <p className="hint">列を式で包んで比べているので、列の値がそのまま合わないことがあります。</p>}
      {error && <p className="error-text">{error}</p>}
      {!values && !error && <p className="hint">読み込んでいます…</p>}
      {values && (
        <>
          {rows ? (
            <SuggestRows rows={rows} column={column.column} counts={new Map(top.map((v) => [v.value, v.count]))} onPick={pick} />
          ) : (
            <p className="hint">行を読み込んでいます…</p>
          )}
          <p className="hint suggest-method">
            {METHOD_LABELS[values.method]}（全 {values.estimatedRows.toLocaleString('ja-JP')} 行ほど）
          </p>
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
    <tr key={`${isCurrent ? 'c' : 'v'}${i}`} className={isCurrent ? 'current' : undefined} onClick={() => onPick(r[at])} title="この値を入れる">
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
