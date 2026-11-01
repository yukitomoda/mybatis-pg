// 値の候補（結果ビューの中に出す）: パラメータと比べられている列の、よく出てくる値ごとの行を、表のすべての列付きで出す。
// セルを押すと、入れる先のパラメータにその値を入れる（比べている列を強調し、「この列の値を使うのが普通」と分かるようにする）
import { useEffect, useState } from 'react';
import type { SourceColumn } from './api';
import { useDbComments } from './comments';
import { DataTable } from './DataTable';
import { rowLabel, rowRecord } from './ResultView';
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
  /** まだ続きがありそうか */
  more: boolean;
};

type Rows = { columns: string[]; columnTypes: string[]; rows: (string | null)[][] };

/** 列の値の候補は、開いている間は覚えておく（同じ列を何度も読まない） */
const cache = new Map<string, Promise<Values>>();

type Extra = { values: Values['values']; rows: Rows['rows']; more: boolean };
/** 「続きを取得」で足した値と行も、開いている間は列ごとに覚えておく（表を開き直しても消えないように） */
const extraCache = new Map<string, Extra>();

const keyOf = (connectionId: string, c: SourceColumn) => `${connectionId}\u0000${c.schema}.${c.table}.${c.column}`;

/** 表に出す候補の数（続きを取るときも、この数ずつ） */
const MAX_ROWS = 50;

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

export function CandidatePane({
  conn,
  columns,
  relations,
  onOpenRelation,
  paramName,
  onOpenRelationName,
  list,
  current,
  visible,
  onCell,
  onRelated,
}: {
  conn: Connections;
  columns: SourceColumn[];
  /** リレーションを解析してあるか */
  relations: boolean;
  /** リレーションの画面で、列（schema.table.column）を開く */
  onOpenRelation: (column: string) => void;
  /** パラメータの名前（同じ名前のパラメータから出した候補の根拠を見るため） */
  paramName: string;
  /** リレーションの画面の「パラメータ名」で、名前を開く */
  onOpenRelationName: (name: string) => void;
  /** リストの項目（foreach） */
  list: boolean;
  /** 入力中の値（statement を切り替えた後は undefined） */
  current: PValue | undefined;
  /** 見えているか（見えていないときは DB を読まない） */
  visible: boolean;
  /** セルを押したとき（入れる先がなければ undefined） */
  onCell?: (text: string | null, type: string) => void;
  /** 行から関連データを集める */
  onRelated?: (connectionId: string, table: string, row: Record<string, string | null>, label: string) => void;
}) {
  const [index, setIndex] = useState(0);
  const column = columns[Math.min(index, columns.length - 1)];
  const [values, setValues] = useState<Values | null>(null);
  const [error, setError] = useState<string | null>(null);
  const connectionId = conn.current?.id;
  const comments = useDbComments(conn.api, connectionId);
  /** 再取得で、最初から取り直す件数（null なら 1 回分） */
  const [reload, setReload] = useState<{ limit: number } | null>(null);

  useEffect(() => {
    if (!connectionId || !visible) return;
    let active = true;
    setValues(null);
    setError(null);
    setExtra(extraCache.get(keyOf(connectionId, column)) ?? null);
    const key = keyOf(connectionId, column);
    let p = cache.get(key);
    if (!p) {
      // 統計情報に値がなければ、表を読んで数える（虫眼鏡を押したときは、候補が出ることを期待しているので）
      p = conn.api.columnValues(connectionId, column, true, 0, reload?.limit ?? MAX_ROWS);
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
  }, [connectionId, column.schema, column.table, column.column, visible, reload]);

  // 候補の値ごとの行（表のすべての列）。候補が決まったら 1 回だけ読む
  const [rows, setRows] = useState<Rows | null>(null);
  /** 「続きを取得」で足した値と行 */
  const [extra, setExtra] = useState<Extra | null>(null);
  const [fetching, setFetching] = useState<'more' | 'refetch' | null>(null);
  const top = values ? [...values.values, ...(extra?.values ?? [])] : [];
  const more = extra ? extra.more : (values?.more ?? false);
  const fetchMore = () => {
    if (!connectionId || !values) return;
    setFetching('more');
    conn.api
      .columnValues(connectionId, column, true, top.length, MAX_ROWS)
      .then(async (v) => {
        const r =
          v.values.length > 0
            ? await conn.api.columnRows(
                connectionId,
                column,
                v.values.map((x) => x.value),
                null,
              )
            : null;
        const key = keyOf(connectionId, column);
        const old = extraCache.get(key);
        const next = { values: [...(old?.values ?? []), ...v.values], rows: [...(old?.rows ?? []), ...(r?.rows ?? [])], more: v.more };
        extraCache.set(key, next);
        setExtra(next);
        if (!rows && r) setRows({ columns: r.columns, columnTypes: r.columnTypes, rows: [] });
      })
      .catch((e) => setError(message(e)))
      .finally(() => setFetching(null));
  };
  const refetch = () => {
    if (!connectionId) return;
    cache.delete(keyOf(connectionId, column));
    extraCache.delete(keyOf(connectionId, column));
    setReload({ limit: Math.min(100, Math.max(MAX_ROWS, top.length)) });
  };
  useEffect(() => {
    setRows(null);
    if (!connectionId || !values || values.values.length === 0) return;
    let active = true;
    conn.api
      .columnRows(
        connectionId,
        column,
        values.values.map((v) => v.value),
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
  const [found, setFound] = useState<{ columns: string[]; columnTypes: string[]; rows: (string | null)[][]; error: string | null } | null>(
    null,
  );
  useEffect(() => {
    setFound(null);
    if (!connectionId || !values || !visible || now === null || (!auto && lookFor !== now)) return;
    let active = true;
    const timer = window.setTimeout(
      () => {
        conn.api.columnRows(connectionId, column, [], now).then(
          (r) => active && setFound({ columns: r.columns, columnTypes: r.columnTypes, rows: r.currentRows, error: r.currentError }),
          (e) => active && setError(message(e)),
        );
      },
      auto ? 300 : 0,
    );
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [connectionId, values, now, auto, lookFor, visible]);

  // 入力中の行を上に、候補の行は件数の多い順に
  const counts = new Map(top.map((v) => [v.value, v.count]));
  const tableColumns = rows?.columns.length ? rows.columns : (found?.columns ?? []);
  const tableTypes = rows?.columnTypes.length ? rows.columnTypes : (found?.columnTypes ?? []);
  const at = tableColumns.indexOf(column.column);
  const currentRows = found?.rows ?? [];
  const candidateRows = [...(rows?.rows ?? []), ...(extra?.rows ?? [])].sort((a, b) => (counts.get(b[at]) ?? 0) - (counts.get(a[at]) ?? 0));
  const allRows = [...currentRows, ...candidateRows];
  const lead = [...currentRows.map(() => '入力中'), ...candidateRows.map((r) => counts.get(r[at])?.toLocaleString('ja-JP') ?? '')];

  const hasOrigin = columns.some((x) => x.role === 'origin');
  const relationTitle = column.role === 'name' ? '同じ名前のパラメータを見る' : hasOrigin ? 'リレーションを見る' : 'リレーションを追加';
  return (
    <div className="candidate-pane">
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
        {comments.columns[`${column.schema}.${column.table}.${column.column}`] && (
          <span className="relations-comment suggest-comment" title={comments.columns[`${column.schema}.${column.table}.${column.column}`]}>
            {comments.columns[`${column.schema}.${column.table}.${column.column}`]}
          </span>
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
        {/* 選んでいる候補の根拠を見る。同じ名前のパラメータからなら「パラメータ名」、源流ならたどり始めた比べている列、
            比べている列ならその列（リレーションがなければ、その列を子にして追加する） */}
        <button
          type="button"
          className="icon suggest-relation"
          title={relationTitle}
          aria-label={relationTitle}
          onClick={() => {
            if (column.role === 'name') {
              onOpenRelationName(paramName);
              return;
            }
            const compared = columns.filter((x) => x.role === 'compared');
            const start =
              column.role === 'origin'
                ? (compared.find((x) => `${x.table}.${x.column}` === column.via[0]) ?? compared[0] ?? column)
                : column;
            onOpenRelation(`${start.schema}.${start.table}.${start.column}`);
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
          {found?.error && <p className="hint">入力中の値で探せません: {found.error}</p>}
          {tableColumns.length > 0 ? (
            <DataTable
              columns={tableColumns}
              types={tableTypes}
              rows={allRows}
              lead={{ header: '件数', cells: lead }}
              rowClass={(i) => (i < currentRows.length ? 'current' : undefined)}
              highlight={column.column}
              titles={tableColumns.map((c) => comments.columns[`${column.schema}.${column.table}.${c}`])}
              csvName={`${column.schema}.${column.table}`}
              onRow={
                onRelated && connectionId
                  ? (i) => {
                      const t = `${column.schema}.${column.table}`;
                      onRelated(connectionId, t, rowRecord(tableColumns, allRows[i]), rowLabel(t, tableColumns, allRows[i]));
                    }
                  : undefined
              }
              onCell={onCell}
              truncated={more}
              actions={
                <>
                  {more && (
                    <button
                      type="button"
                      disabled={fetching !== null}
                      title={`次によく出る ${MAX_ROWS} 件の値と、その行を取ります`}
                      onClick={fetchMore}
                    >
                      {fetching === 'more' ? '取得中…' : '続きを取得'}
                    </button>
                  )}
                  <button
                    type="button"
                    className="link"
                    disabled={fetching !== null || !rows}
                    title="今ある件数を、最初から取り直します"
                    onClick={refetch}
                  >
                    再取得
                  </button>
                </>
              }
            />
          ) : (
            values.values.length > 0 && <p className="hint">行を読み込んでいます…</p>
          )}
        </>
      )}
    </div>
  );
}
