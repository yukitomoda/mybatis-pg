// サブクエリのタブ: SQL の中のサブクエリ・CTE を木で見せ、部分ごとに本番と同じバインド変数で実行する（読み取り専用、必ずロールバック）
import { useEffect, useMemo, useRef, useState } from 'react';
import type { DraftParam, RenderResponse, ResultTable } from './api';
import type { Connections } from './Execution';
import { ConnectionPicker, message, ResultGrid } from './Execution';
import { resolveTemplates } from './gen';
import type { GenContext } from './GenInput';
import { ParamTree } from './ParamTree';
import type { ParamSchema, PValue } from './params';
import { getAt, invalidNumbers, isValidNumber } from './params';
import type { Part } from './subquery';
import { analyze, missingIn, partLabel, partSql } from './subquery';

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

/** 外側の値を SQL の書き方にする（型に合わせる）。使えない値は理由を投げる */
export function sqlLiteral(v: PValue): string {
  const quote = (s: string) => `'${s.replace(/'/g, "''")}'`;
  switch (v.t) {
    case 'string':
      return quote(v.v);
    case 'number':
      if (!isValidNumber(v.v)) throw new Error(`数値として解釈できません: "${v.v}"`);
      return v.v;
    case 'timestamp':
      return `TIMESTAMP ${quote(v.v)}`;
    case 'date':
      return `DATE ${quote(v.v)}`;
    case 'pg':
      return `CAST(${quote(v.v)} AS ${v.type})`;
    case 'boolean':
      return v.v ? 'TRUE' : 'FALSE';
    case 'null':
      return 'NULL';
    default:
      throw new Error('リストやオブジェクトは使えません');
  }
}

/** 外側の値（statement ごとにブラウザに覚えておく） */
const OUTER_PREFIX = 'mybatis-pg.outer:';

function loadOuter(statementId: string): PValue | undefined {
  try {
    const v = JSON.parse(localStorage.getItem(OUTER_PREFIX + statementId) ?? 'null');
    return v && v.t === 'object' ? (v as PValue) : undefined;
  } catch {
    return undefined;
  }
}

function saveOuter(statementId: string, v: PValue | undefined) {
  try {
    if (v) localStorage.setItem(OUTER_PREFIX + statementId, JSON.stringify(v));
    else localStorage.removeItem(OUTER_PREFIX + statementId);
  } catch {
    // 覚えられない環境では、開いている間だけ使う
  }
}

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
  gen,
  missing,
  onApiError,
}: {
  conn: Connections;
  statementId: string;
  value: PValue | undefined;
  draft: DraftParam;
  rendered: RenderResponse;
  gen: GenContext;
  /** 値が未設定の項目（パラメータ欄の未設定と同じ） */
  missing: string[];
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
  /** 外側の値（パラメータと同じ形。項目名は o.id など） */
  const [outer, setOuterState] = useState<PValue | undefined>(() => loadOuter(statementId));
  const setOuter = (v: PValue | undefined) => {
    setOuterState(v);
    saveOuter(statementId, v);
  };
  // 式を計算して、外側の参照ごとに SQL の書き方にする（できないものは理由）
  const literals = useMemo(() => {
    const resolved = resolveTemplates(outer, gen.cache);
    const out: Record<string, string> = {};
    const errors: Record<string, string> = {};
    for (const i of [...resolved.issues, ...invalidNumbers(resolved.value)]) errors[i.path] = i.message;
    for (const p of parts) {
      for (const ref of p.outerRefs) {
        const v = getAt(resolved.value, [ref]);
        if (!v || errors[ref]) continue;
        try {
          out[ref] = sqlLiteral(v);
        } catch (e) {
          errors[ref] = message(e);
        }
      }
    }
    return { values: out, errors };
  }, [outer, parts, gen.cache, gen.version]);
  const values = literals.values;
  const [results, setResults] = useState<Record<string, RunResult>>({});
  const [running, setRunning] = useState<string | null>(null);

  // SQL が変わったら（値や XML を変えたら）、前の結果は消す
  useEffect(() => setResults({}), [rendered.numberedSql]);

  const selected = parts.find((p) => p.key === selectedKey) ?? numbered.root;
  const runnable = (p: Part) => !p.writes && p.outerRefs.every((r) => (values[r] ?? '') !== '');
  // 部分ごとの、その部分で使っているのに未設定のパラメータ（全体では空けておくことはあっても、部分の中で未設定なのはミスのことが多い）
  const missingByPart = useMemo(() => {
    const props = rendered.bindings.map((b) => b.property);
    return new Map(parts.map((p) => [p.key, missingIn(partSql(numbered, p, values), props, missing)]));
  }, [parts, numbered, values, rendered.bindings, missing]);

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
          {(missingByPart.get(p.key)?.length ?? 0) > 0 && (
            <span className="warn-mark" title={`未設定: ${missingByPart.get(p.key)!.join(', ')}`} aria-label="未設定のパラメータがある">
              ⚠
            </span>
          )}
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
        <button type="button" disabled={!conn.current || running !== null} onClick={runAll} title="実行できる部分をまとめて実行し、部分ごとの件数を出す">
          すべての部分を実行
        </button>
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
            <p className="hint">データを書き換える部分は、ここでは実行しません（実行タブを使ってください）。</p>
          ) : (
            <>
              {selected.outerRefs.length > 0 && (
                <div className="outer-values">
                  <h4>外側の値</h4>
                  <ParamTree schema={outerSchema(selected)} value={outer} onChange={setOuter} gen={gen} />
                  {selected.outerRefs
                    .filter((r) => literals.errors[r])
                    .map((r) => (
                      <p key={r} className="error-text">
                        {r}: {literals.errors[r]}
                      </p>
                    ))}
                </div>
              )}
              {(missingByPart.get(selected.key)?.length ?? 0) > 0 && (
                <p className="warn-text part-missing">⚠ 未設定: {missingByPart.get(selected.key)!.join(', ')}</p>
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

/** 外側の参照を、パラメータと同じ形の項目にする（型の選び方や式の書き方をパラメータとそろえる） */
function outerSchema(part: Part): ParamSchema {
  return {
    name: null,
    usages: [],
    typeHints: [],
    properties: part.outerRefs.map((r) => ({ name: r, usages: ['BIND'], typeHints: [], properties: [], element: null })),
    element: null,
  };
}

/** サブクエリ・CTE の数（メインのクエリを除く） */
export function countParts(sql: string): number {
  return flatten(analyze(sql).root).length - 1;
}
