// 実行タブの「部分ごとに実行」（試作）: SQL の中のサブクエリ・CTE を木で見せ、部分ごとに本番と同じバインド変数で実行する
import { useEffect, useMemo, useRef, useState } from 'react';
import type { DraftParam, RenderResponse, ResultTable } from './api';
import type { Connections } from './Execution';
import { message, ResultGrid } from './Execution';
import type { PValue } from './params';
import type { Part } from './subquery';
import { analyze, partLabel, partSql } from './subquery';

type RunResult = { ok: true; table: ResultTable; total: number | null; elapsedMillis: number } | { ok: false; error: string };

const KIND_HINT: Record<Part['kind'], string> = {
  main: '',
  cte: 'CTE',
  from: 'FROM の中',
  in: '条件（IN・ANY など）',
  exists: '条件（EXISTS）',
  select: 'SELECT の列',
  scalar: '式の中',
};

const flatten = (root: Part): Part[] => {
  const out: Part[] = [];
  const walk = (p: Part) => {
    out.push(p);
    p.children.forEach(walk);
  };
  walk(root);
  return out;
};

export function PartsPanel({
  conn,
  statementId,
  value,
  draft,
  rendered,
  onApiError,
}: {
  conn: Connections;
  statementId: string;
  value: PValue | undefined;
  draft: DraftParam;
  rendered: RenderResponse;
  onApiError: (e: unknown) => void;
}) {
  // 実行はバインド変数を番号にした SQL で、位置の表示は値を埋め込んだ SQL で行う（どちらも同じ形に分かれる）
  const numbered = useMemo(() => analyze(rendered.numberedSql), [rendered.numberedSql]);
  const literal = useMemo(() => analyze(rendered.literalSql), [rendered.literalSql]);
  const parts = useMemo(() => flatten(numbered.root), [numbered]);
  const shown = useMemo(() => {
    const lit = flatten(literal.root);
    return lit.length === parts.length && lit.every((p, i) => p.key === parts[i].key) ? new Map(lit.map((p) => [p.key, p])) : null;
  }, [literal, parts]);
  const [selectedKey, setSelectedKey] = useState<string>(numbered.root.key);
  const [values, setValues] = useState<Record<string, string>>({});
  const [results, setResults] = useState<Record<string, RunResult>>({});
  const [running, setRunning] = useState<string | null>(null);

  // SQL が変わったら（値や XML を変えたら）、前の結果は消す
  useEffect(() => setResults({}), [rendered.numberedSql]);

  const selected = parts.find((p) => p.key === selectedKey) ?? numbered.root;
  const runnable = (p: Part) => !p.writes && p.outerRefs.every((r) => (values[r] ?? '') !== '');

  const run = async (p: Part) => {
    const current = conn.current;
    if (!current) return;
    setRunning(p.key);
    try {
      const r = await conn.api.runPart(statementId, value, draft, current.id, partSql(numbered, p, values));
      setResults((old) => ({ ...old, [p.key]: { ok: true, table: r, total: r.total, elapsedMillis: r.elapsedMillis } }));
    } catch (e) {
      onApiError(e);
      setResults((old) => ({ ...old, [p.key]: { ok: false, error: message(e) } }));
    } finally {
      setRunning(null);
    }
  };
  const runAll = async () => {
    for (const p of parts) if (runnable(p)) await run(p);
  };

  // 選んだ部分が見えるよう、SQL の中の位置の欄をその部分までスクロールする
  const mark = useRef<HTMLElement>(null);
  useEffect(() => {
    const m = mark.current;
    const box = m?.closest('pre');
    if (!m || !box) return;
    box.scrollTop = Math.max(0, m.offsetTop - box.offsetTop - 16);
    box.scrollLeft = Math.max(0, m.offsetLeft - box.offsetLeft - 16);
  }, [selectedKey]);

  const result = results[selected.key];
  const where = shown?.get(selected.key) ?? selected;
  const whereSql = shown ? rendered.literalSql : rendered.numberedSql;
  const tree = (p: Part, depth: number) => {
    const r = results[p.key];
    return (
      <li key={p.key}>
        <button
          type="button"
          className={p.key === selected.key ? 'part selected' : 'part'}
          style={{ paddingLeft: `${0.4 + depth * 1.1}rem` }}
          onClick={() => setSelectedKey(p.key)}
        >
          <span className="part-label">{partLabel(p)}</span>
          {p.kind !== 'main' && <span className="part-kind">{KIND_HINT[p.kind]}</span>}
          {p.outerRefs.length > 0 && (
            <span className="badge warn" title={`外側の値を使う（相関サブクエリ）: ${p.outerRefs.join(', ')}`}>
              相関
            </span>
          )}
          {p.writes && <span className="badge muted">書き換え</span>}
          <span className="part-count">{r ? (r.ok ? `${r.total ?? '?'} 件` : 'エラー') : ''}</span>
        </button>
        {p.children.length > 0 && <ul>{p.children.map((c) => tree(c, depth + 1))}</ul>}
      </li>
    );
  };

  return (
    <div className="subquery-panel">
      <div className="execute-bar">
        <button type="button" disabled={!conn.current || running !== null} onClick={runAll} title="外側の値がいらない部分を、すべて実行して件数を出す">
          すべての部分を実行
        </button>
        <span className="hint">本番と同じバインド変数で、読み取り専用で実行し、必ずロールバックします。</span>
      </div>
      <div className="subquery-body">
        <ul className="part-tree">{tree(numbered.root, 0)}</ul>
        <div className="part-detail">
          <details className="part-where" open>
            <summary>SQL の中の位置</summary>
            <pre className="sql">
              {whereSql.slice(0, where.start)}
              <mark ref={mark}>{whereSql.slice(where.start, where.end)}</mark>
              {whereSql.slice(where.end)}
            </pre>
          </details>
          {selected.writes ? (
            <p className="hint">データを書き換える部分は、ここでは実行しません（上の「実行」を使ってください）。</p>
          ) : (
            <>
              {selected.outerRefs.length > 0 && (
                <div className="outer-values">
                  <p className="hint">外側のクエリの値を使います。値を SQL の書き方で入れてください（例: 42、'paid'）。</p>
                  {selected.outerRefs.map((r) => (
                    <label key={r}>
                      <code>{r}</code>
                      <input type="text" value={values[r] ?? ''} onChange={(e) => setValues((v) => ({ ...v, [r]: e.target.value }))} />
                    </label>
                  ))}
                </div>
              )}
              <div className="execute-bar">
                <button type="button" className="run" disabled={!conn.current || !runnable(selected) || running !== null} onClick={() => run(selected)}>
                  {running === selected.key ? '実行中…' : 'この部分を実行'}
                </button>
              </div>
              <details className="part-sql">
                <summary>実行する SQL</summary>
                <pre className="sql">{partSql(numbered, selected, values)}</pre>
              </details>
              {result &&
                (result.ok ? (
                  <>
                    <p className="hint">
                      全 {result.total ?? '?'} 件・{result.elapsedMillis} ms
                    </p>
                    <ResultGrid table={result.table} />
                  </>
                ) : (
                  <pre className="render-error">{result.error}</pre>
                ))}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

/** サブクエリ・CTE の数（メインのクエリを除く） */
export function countParts(sql: string): number {
  return flatten(analyze(sql).root).length - 1;
}
