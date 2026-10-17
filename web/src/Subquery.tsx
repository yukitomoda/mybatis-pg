// サブクエリのタブ（試作）: SQL の中のサブクエリ・CTE を木で見せ、部分ごとに実行して結果を見る
import { useMemo, useState } from 'react';
import type { ResultTable } from './api';
import type { Connections } from './Execution';
import { ConnectionPicker, message, ResultGrid } from './Execution';
import type { Part } from './subquery';
import { analyze, partLabel, partSql } from './subquery';

type RunResult = { ok: true; table: ResultTable; total: number | null; elapsedMillis: number; sql: string } | { ok: false; error: string; sql: string };

const KIND_HINT: Record<Part['kind'], string> = {
  main: '',
  cte: 'CTE',
  from: 'FROM の中',
  in: '条件（IN・ANY など）',
  exists: '条件（EXISTS）',
  select: 'SELECT の列',
  scalar: '式の中',
};

export function SubqueryPanel({ conn, sql, onApiError }: { conn: Connections; sql: string; onApiError: (e: unknown) => void }) {
  const analysis = useMemo(() => analyze(sql), [sql]);
  const [selectedKey, setSelectedKey] = useState<string>(analysis.root.key);
  const [values, setValues] = useState<Record<string, string>>({});
  const [results, setResults] = useState<Record<string, RunResult>>({});
  const [running, setRunning] = useState<string | null>(null);

  const all = useMemo(() => {
    const out: Part[] = [];
    const walk = (p: Part) => {
      out.push(p);
      p.children.forEach(walk);
    };
    walk(analysis.root);
    return out;
  }, [analysis]);
  const selected = all.find((p) => p.key === selectedKey) ?? analysis.root;
  const runnable = (p: Part) => !p.writes && p.outerRefs.every((r) => (values[r] ?? '') !== '');

  const run = async (p: Part) => {
    const current = conn.current;
    if (!current) return;
    const text = partSql(analysis, p, values);
    setRunning(p.key);
    try {
      const r = await conn.api.runPart(current.id, text);
      setResults((old) => ({ ...old, [p.key]: { ok: true, table: r, total: r.total, elapsedMillis: r.elapsedMillis, sql: text } }));
    } catch (e) {
      onApiError(e);
      setResults((old) => ({ ...old, [p.key]: { ok: false, error: message(e), sql: text } }));
    } finally {
      setRunning(null);
    }
  };
  const runAll = async () => {
    for (const p of all) if (runnable(p)) await run(p);
  };

  const result = results[selected.key];
  const tree = (p: Part, depth: number) => {
    const r = results[p.key];
    return (
      <li key={p.key}>
        <button type="button" className={p.key === selected.key ? 'part selected' : 'part'} style={{ paddingLeft: `${0.4 + depth * 1.1}rem` }} onClick={() => setSelectedKey(p.key)}>
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
        <ConnectionPicker conn={conn} />
        <button type="button" disabled={!conn.current || running !== null} onClick={runAll} title="外側の値がいらない部分を、すべて実行して件数を出す">
          すべて実行
        </button>
      </div>
      <p className="hint">読み取り専用で実行し、必ずロールバックします。</p>
      <div className="subquery-body">
        <ul className="part-tree">{tree(analysis.root, 0)}</ul>
        <div className="part-detail">
          <details className="part-where" open>
            <summary>SQL の中の位置</summary>
            <pre className="sql">
              {sql.slice(0, selected.start)}
              <mark>{sql.slice(selected.start, selected.end)}</mark>
              {sql.slice(selected.end)}
            </pre>
          </details>
          {selected.writes ? (
            <p className="hint">データを書き換える部分は実行しません。</p>
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
                <pre className="sql">{partSql(analysis, selected, values)}</pre>
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
