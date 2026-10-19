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
          <div className="suggest-values">
            {values.values.map((v, i) => (
              <button key={i} type="button" className="candidate" title={`${v.count.toLocaleString('ja-JP')} 件`} onClick={() => pick(v.value)}>
                {v.value ?? 'NULL'}
                <span className="count">{v.count.toLocaleString('ja-JP')}</span>
              </button>
            ))}
            {values.values.length === 0 && <span className="hint">値がありません</span>}
          </div>
          <p className="hint suggest-method">
            {METHOD_LABELS[values.method]}（全 {values.estimatedRows.toLocaleString('ja-JP')} 行ほど）
          </p>
        </>
      )}
    </div>
  );
}
