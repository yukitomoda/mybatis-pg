import { useEffect, useMemo, useRef, useState } from 'react';
import type { Api, LoadError, RenderResponse } from './api';
import { ApiError, createApi, takeToken } from './api';
import { ExecutePanel } from './Execution';
import { ParamTree } from './ParamTree';
import type { ParamSchema, PValue } from './params';
import { fromJson, invalidNumbers, missingParams, toJson } from './params';
import { tidySql } from './sql';

export function App() {
  const [token] = useState(() => takeToken());
  const api = useMemo(() => (token ? createApi(token) : null), [token]);
  if (!api) {
    return (
      <div className="fatal">
        <h1>トークンがありません</h1>
        <p>サーバの起動時に表示された URL（<code>?token=...</code> 付き）から開いてください。</p>
      </div>
    );
  }
  return <Workspace api={api} />;
}

// ---- 保存 ----

const STORAGE_PREFIX = 'mybatis-xml-editor.params:';

function loadParams(id: string): PValue | undefined {
  try {
    const text = localStorage.getItem(STORAGE_PREFIX + id);
    return text ? fromJson(text) : undefined;
  } catch {
    return undefined;
  }
}

function saveParams(id: string, value: PValue | undefined) {
  try {
    if (value === undefined) localStorage.removeItem(STORAGE_PREFIX + id);
    else localStorage.setItem(STORAGE_PREFIX + id, toJson(value, ''));
  } catch {
    // 保存できない環境（プライベートウィンドウなど）では保存しない
  }
}

function initialStatement(): string | null {
  const hash = decodeURIComponent(window.location.hash.slice(1));
  return hash || null;
}

// ---- 画面 ----

function Workspace({ api }: { api: Api }) {
  const [fatal, setFatal] = useState<string | null>(null);
  const [generation, setGeneration] = useState(0);
  const [statements, setStatements] = useState<string[]>([]);
  const [loadErrors, setLoadErrors] = useState<LoadError[]>([]);
  const [selected, setSelected] = useState<string | null>(initialStatement);
  const [schema, setSchema] = useState<ParamSchema | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [value, setValue] = useState<PValue | undefined>(() => (selected ? loadParams(selected) : undefined));
  const [result, setResult] = useState<RenderResponse | null>(null);
  const [renderError, setRenderError] = useState<string | null>(null);

  const handleError = (e: unknown) => {
    if (e instanceof ApiError && e.status === 401) {
      setFatal('トークンが一致しません。サーバを起動し直した場合は、新しく表示された URL から開き直してください。');
    }
  };

  // mapper の読み込み直しを待ち続ける（ロングポーリング）
  useEffect(() => {
    const abort = new AbortController();
    let since = 0;
    (async () => {
      while (!abort.signal.aborted) {
        try {
          const r = await api.changes(since, abort.signal);
          if (r.generation !== since) {
            since = r.generation;
            setGeneration(r.generation);
          }
        } catch (e) {
          if (abort.signal.aborted) return;
          handleError(e);
          if (e instanceof ApiError && e.status === 401) return;
          // サーバが止まっている間は間隔を空けて再試行する
          await new Promise((resolve) => setTimeout(resolve, 3000));
        }
      }
    })();
    return () => abort.abort();
  }, [api]);

  // statement の一覧
  useEffect(() => {
    if (generation === 0) return;
    api
      .statements()
      .then((r) => {
        setStatements(r.statements);
        setLoadErrors(r.loadErrors);
      })
      .catch(handleError);
  }, [api, generation]);

  // パラメータの木（XML が変わったら取り直す）
  useEffect(() => {
    if (!selected || generation === 0) {
      setSchema(null);
      return;
    }
    let active = true;
    api
      .parameters(selected)
      .then((r) => {
        if (!active) return;
        setSchema(r.tree);
        setWarnings(r.warnings);
        setSchemaError(null);
      })
      .catch((e) => {
        if (!active) return;
        handleError(e);
        setSchema(null);
        setSchemaError(e instanceof ApiError && e.status === 404 ? 'この statement は見つかりません（削除または改名された可能性があります）' : String(e.message ?? e));
      });
    return () => {
      active = false;
    };
  }, [api, selected, generation]);

  const numberIssues = useMemo(() => invalidNumbers(value), [value]);
  const missing = useMemo(() => (schema ? missingParams(schema, value) : []), [schema, value]);

  // 入力や XML が変わるたびにレンダリングする
  useEffect(() => {
    if (!selected || !schema || numberIssues.length > 0) return;
    const abort = new AbortController();
    const timer = setTimeout(() => {
      api
        .render(selected, value, abort.signal)
        .then((r) => {
          setResult(r);
          setRenderError(null);
        })
        .catch((e) => {
          if (abort.signal.aborted) return;
          handleError(e);
          setResult(null);
          setRenderError(String(e.message ?? e));
        });
    }, 150);
    return () => {
      clearTimeout(timer);
      abort.abort();
    };
  }, [api, selected, schema, value, numberIssues.length, generation]);

  const select = (id: string) => {
    setSelected(id);
    setValue(loadParams(id));
    setResult(null);
    setRenderError(null);
    window.history.replaceState(null, '', '#' + encodeURIComponent(id));
  };

  const changeValue = (v: PValue | undefined) => {
    setValue(v);
    if (selected) saveParams(selected, v);
  };

  if (fatal) {
    return (
      <div className="fatal">
        <h1>接続できません</h1>
        <p>{fatal}</p>
      </div>
    );
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <StatementList statements={statements} selected={selected} onSelect={select} />
        <LoadErrors errors={loadErrors} />
      </aside>

      <main className="params">
        <header className="panel-header">
          <h2>パラメータ</h2>
          {selected && <code className="statement-id">{selected}</code>}
        </header>
        {!selected && <p className="hint">左の一覧から statement を選んでください。</p>}
        {schemaError && <p className="error-text">{schemaError}</p>}
        {selected && schema && (
          <>
            <ParamTree schema={schema} value={value} onChange={changeValue} />
            {warnings.length > 0 && (
              <ul className="notes warn">
                {warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            )}
            <JsonPanel value={value} onApply={changeValue} />
          </>
        )}
      </main>

      <section className="result">
        <header className="panel-header">
          <h2>SQL</h2>
        </header>
        {numberIssues.length > 0 && (
          <ul className="notes error">
            {numberIssues.map((i) => (
              <li key={i.path}>
                <code>{i.path}</code>: {i.message}
              </li>
            ))}
          </ul>
        )}
        {missing.length > 0 && (
          <div className="notes info">
            未設定の項目（NULL として扱われます）: {missing.map((m) => <code key={m}>{m}</code>)}
          </div>
        )}
        {renderError && <pre className="render-error">{renderError}</pre>}
        {result && !renderError && <RenderResult result={result} />}

        {selected && schema && (
          <>
            <header className="panel-header execute-header">
              <h2>実行</h2>
            </header>
            <ExecutePanel
              key={selected}
              api={api}
              statementId={selected}
              value={value}
              ready={numberIssues.length === 0 && !renderError}
              onApiError={handleError}
            />
          </>
        )}
      </section>
    </div>
  );
}

function StatementList({ statements, selected, onSelect }: { statements: string[]; selected: string | null; onSelect: (id: string) => void }) {
  const [filter, setFilter] = useState('');
  const shown = statements.filter((s) => s.toLowerCase().includes(filter.trim().toLowerCase()));
  return (
    <div className="statements">
      <input type="search" placeholder="statement を絞り込み" aria-label="statement を絞り込み" value={filter} onChange={(e) => setFilter(e.target.value)} />
      <ul>
        {shown.map((id) => {
          const dot = id.lastIndexOf('.');
          return (
            <li key={id}>
              <button type="button" className={id === selected ? 'selected' : undefined} onClick={() => onSelect(id)} title={id}>
                <span className="ns">{id.slice(0, dot + 1)}</span>
                <span className="sid">{id.slice(dot + 1)}</span>
              </button>
            </li>
          );
        })}
        {shown.length === 0 && <li className="hint">該当する statement がありません</li>}
      </ul>
    </div>
  );
}

function LoadErrors({ errors }: { errors: LoadError[] }) {
  if (errors.length === 0) return null;
  return (
    <details className="load-errors" open>
      <summary>読み込みエラー {errors.length} 件</summary>
      <ul>
        {errors.map((e, i) => (
          <li key={i}>
            <div className="source">
              {e.source}
              {e.line !== null && `:${e.line}`}
            </div>
            <pre>{e.message}</pre>
          </li>
        ))}
      </ul>
    </details>
  );
}

/** JSON での表示と、貼り付けによるまとめての書き換え */
function JsonPanel({ value, onApply }: { value: PValue | undefined; onApply: (v: PValue | undefined) => void }) {
  const [draft, setDraft] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const text = draft ?? toJson(value ?? { t: 'object', fields: [] });
  const apply = () => {
    if (draft === null) return;
    try {
      const parsed = fromJson(draft);
      if (parsed.t !== 'object') throw new Error('一番外側はオブジェクト（{ ... }）にしてください');
      onApply(parsed.fields.length === 0 ? undefined : parsed);
      setDraft(null);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <details className="json-panel">
      <summary>JSON</summary>
      <textarea
        aria-label="パラメータの JSON"
        spellCheck={false}
        value={text}
        onChange={(e) => {
          setDraft(e.target.value);
          setError(null);
        }}
      />
      <div className="actions">
        <button type="button" disabled={draft === null} onClick={apply}>
          反映
        </button>
        <button
          type="button"
          disabled={draft === null}
          onClick={() => {
            setDraft(null);
            setError(null);
          }}
        >
          取消
        </button>
        {error && <span className="error-text">{error}</span>}
      </div>
    </details>
  );
}

function RenderResult({ result }: { result: RenderResponse }) {
  const literal = tidySql(result.literalSql);
  return (
    <>
      <div className="sql-block">
        <div className="sql-title">
          <h3>値を埋め込んだ SQL</h3>
          <CopyButton text={literal} />
        </div>
        <pre className="sql">{literal}</pre>
      </div>

      <div className="sql-block">
        <h3>バインド値</h3>
        {result.bindings.length === 0 ? (
          <p className="hint">バインド値はありません</p>
        ) : (
          <table className="bindings">
            <thead>
              <tr>
                <th>#</th>
                <th>パラメータ</th>
                <th>値</th>
              </tr>
            </thead>
            <tbody>
              {result.bindings.map((b) => (
                <tr key={b.index}>
                  <td>{b.index}</td>
                  <td>
                    <code>{b.property}</code>
                  </td>
                  <td>
                    <code>{b.literal}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="sql-block">
        <div className="sql-title">
          <h3>? 付き SQL</h3>
          <CopyButton text={tidySql(result.placeholderSql)} />
        </div>
        <pre className="sql">{tidySql(result.placeholderSql)}</pre>
      </div>
    </>
  );
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
  return (
    <button
      type="button"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setCopied(true);
          window.clearTimeout(timer.current);
          timer.current = window.setTimeout(() => setCopied(false), 1500);
        } catch {
          setCopied(false);
        }
      }}
    >
      {copied ? 'コピーしました' : 'コピー'}
    </button>
  );
}
