import { useEffect, useMemo, useRef, useState } from 'react';
import type { Api, LoadError, OpenTarget, RenderResponse, TemplateResponse } from './api';
import { ApiError, createApi, takeToken } from './api';
import { BulkCheck } from './Bulk';
import { ExecutePanel, useConnections } from './Execution';
import { ExplainPanel } from './Explain';
import type { GenCache } from './gen';
import { forget, resolveTemplates } from './gen';
import type { GenContext } from './GenInput';
import { AppliedGlobals, GlobalsScreen, useGlobals } from './Globals';
import { ConnectionsScreen } from './Execution';
import { applicable, combine, originAt } from './scope';
import { ParamTree } from './ParamTree';
import { Splitter, usePaneWidths } from './Splitter';
import type { ParamSchema, PValue } from './params';
import { effectiveValue, fromJson, invalidNumbers, missingParams, toJson } from './params';
import { loadParams, saveParams } from './paramStore';
import { tidySql } from './sql';
import { tokenize } from './template';
import type { StatementEntry, TreeNode } from './tree';
import { buildTree, filterEntries, pathTo, shortId } from './tree';

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

type Tab = 'sql' | 'execute' | 'explain' | 'template';
const TAB_KEY = 'mybatis-pg.tab';

function loadTab(): Tab {
  try {
    const saved = localStorage.getItem(TAB_KEY);
    return saved === 'execute' || saved === 'explain' || saved === 'template' ? saved : 'sql';
  } catch {
    return 'sql';
  }
}

function saveTab(tab: Tab) {
  try {
    localStorage.setItem(TAB_KEY, tab);
  } catch {
    // 保存できない環境では、この表示中だけ覚える
  }
}

// ---- 画面の切り替えと URL ----

type Screen = 'query' | 'globals' | 'bulk' | 'connections';

const SCREENS: [Screen, string][] = [
  ['query', 'クエリ'],
  ['globals', '共通パラメータ'],
  ['bulk', '一括チェック'],
  ['connections', '接続先'],
];

/**
 * URL の # の後ろを読む。#/query/statement の id、#/globals、#/bulk、#/connections。
 * 以前の形（#statement の id）はクエリ画面として読む。
 */
function parseHash(): { screen: Screen; statement: string | null } {
  const hash = decodeURIComponent(window.location.hash.slice(1));
  if (!hash) return { screen: 'query', statement: null };
  if (!hash.startsWith('/')) return { screen: 'query', statement: hash };
  const [, screen, ...rest] = hash.split('/');
  const known = SCREENS.some(([s]) => s === screen);
  return { screen: known ? (screen as Screen) : 'query', statement: screen === 'query' && rest.length ? rest.join('/') : null };
}

function hashOf(screen: Screen, statement: string | null): string {
  return screen === 'query' ? `#/query/${statement ? encodeURIComponent(statement) : ''}` : `#/${screen}`;
}

function initialStatement(): string | null {
  return parseHash().statement;
}

// ---- 画面 ----

/** ファイルをエディタで開く。エディタのコマンドがなければ null */
type Opener = { label: string; open: (target: OpenTarget) => void } | null;

function useOpener(api: Api, onApiError: (e: unknown) => void): Opener {
  const [command, setCommand] = useState<string | null>(null);
  useEffect(() => {
    api.editor().then((r) => setCommand(r.available ? r.command : null), onApiError);
  }, [api]);
  if (!command) return null;
  return {
    label: /(^|\/)code(-server)?(\s|$)/.test(command) ? 'VS Code で開く' : 'エディタで開く',
    open: (target) =>
      api.open(target).catch((e) => {
        onApiError(e);
        window.alert('ファイルを開けません\n' + (e instanceof Error ? e.message : String(e)));
      }),
  };
}

function Workspace({ api }: { api: Api }) {
  const [fatal, setFatal] = useState<string | null>(null);
  const [generation, setGeneration] = useState(0);
  const [statements, setStatements] = useState<StatementEntry[]>([]);
  const [loadErrors, setLoadErrors] = useState<LoadError[]>([]);
  const [selected, setSelected] = useState<string | null>(initialStatement);
  const [schema, setSchema] = useState<ParamSchema | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [value, setValue] = useState<PValue | undefined>(() => (selected ? loadParams(selected) : undefined));
  const [result, setResult] = useState<RenderResponse | null>(null);
  const [renderError, setRenderError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>(loadTab);
  const panes = usePaneWidths();
  const [screen, setScreen] = useState<Screen>(() => parseHash().screen);
  /** 共通パラメータの画面で選んでおくもの（クエリ画面で名前を押したとき） */
  const [globalFocus, setGlobalFocus] = useState<string | null>(null);
  /** パラメータ欄で編集するもの: 個別の値か、共通パラメータか */

  const handleError = (e: unknown) => {
    if (e instanceof ApiError && e.status === 401) {
      setFatal('トークンが一致しません。サーバを起動し直した場合は、新しく表示された URL から開き直してください。');
    }
  };

  const opener = useOpener(api, handleError);
  const conn = useConnections(api, handleError, () => go('connections'));

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

  const globals = useGlobals(api, handleError);
  // 選んでいる statement に効く共通パラメータ（優先順位の低い順）と、それを重ねた値
  const target = statements.find((s) => s.id === selected) ?? null;
  const applied = useMemo(() => applicable(globals.sets, globals.order, target), [globals.sets, globals.order, target]);
  const base = useMemo(() => combine(applied), [applied]);
  // レンダリングと実行に使う値（共通パラメータ + 個別の値 + 既定の値）
  const effective = useMemo(() => effectiveValue(schema, base, value), [schema, base, value]);
  // 生成値（式）を計算した値。一度作った値は genCache に覚え、↻ で作り直す
  const genCache = useRef<GenCache>(new Map()).current;
  const [genVersion, setGenVersion] = useState(0);
  const gen: GenContext = useMemo(
    () => ({
      cache: genCache,
      version: genVersion,
      regenerate: (key: string) => {
        forget(genCache, key);
        setGenVersion((v) => v + 1);
      },
    }),
    [genCache, genVersion],
  );
  const resolved = useMemo(() => resolveTemplates(effective, genCache), [effective, genCache, genVersion]);
  const sent = resolved.value;
  const numberIssues = useMemo(() => {
    // 式の誤りがある項目は、数値の誤りとしては重ねて出さない
    const genPaths = new Set(resolved.issues.map((i) => i.path));
    return [...resolved.issues, ...invalidNumbers(sent).filter((i) => !genPaths.has(i.path))];
  }, [resolved, sent]);
  const missing = useMemo(() => (schema ? missingParams(schema, effective) : []), [schema, effective]);

  // 入力や XML が変わるたびにレンダリングする
  useEffect(() => {
    if (!selected || !schema || numberIssues.length > 0) return;
    const abort = new AbortController();
    const timer = setTimeout(() => {
      api
        .render(selected, sent, abort.signal)
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
  }, [api, selected, schema, sent, numberIssues.length, generation]);

  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const select = (id: string) => {
    setSelected(id);
    setValue(loadParams(id));
    setResult(null);
    setRenderError(null);
    window.history.replaceState(null, '', hashOf('query', id));
  };

  /** 画面を切り替える（ブラウザの履歴に残す） */
  const go = (next: Screen) => {
    setScreen(next);
    window.history.pushState(null, '', hashOf(next, selected));
  };

  // ブラウザの戻る・進むで、画面と statement を合わせる
  useEffect(() => {
    const onPop = () => {
      const h = parseHash();
      setScreen(h.screen);
      if (h.statement && h.statement !== selectedRef.current) select(h.statement);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

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
    <div className="app">
      <nav className="topbar">
        <span className="brand">mybatis-pg</span>
        {SCREENS.map(([key, label]) => (
          <button key={key} type="button" className={screen === key ? 'nav selected' : 'nav'} aria-current={screen === key ? 'page' : undefined} onClick={() => go(key)}>
            {label}
          </button>
        ))}
      </nav>

      {/* 切り替えても入力中の内容が消えないよう、画面は残しておいて隠す */}
      <div className="screen" hidden={screen !== 'query'}>
    <div className="layout" ref={panes.layout} style={panes.style}>
      <aside className="sidebar">
        <StatementTree statements={statements} selected={selected} onSelect={select} opener={opener} />
        <LoadErrors errors={loadErrors} opener={opener} />
      </aside>
      <Splitter pane="sidebar" label="statement 一覧の幅" panes={panes} />

      <main className="params">
        <header className="panel-header">
          <h2>パラメータ</h2>
          {selected && <code className="statement-id">{selected}</code>}
        </header>
        {!selected && <p className="hint">左の一覧から statement を選んでください。</p>}
        {schemaError && <p className="error-text">{schemaError}</p>}
        {selected && schema && (
          <>
            {target && (
              <AppliedGlobals
                globals={globals}
                target={target}
                onSettings={(id) => {
                  setGlobalFocus(id);
                  go('globals');
                }}
              />
            )}
            <ParamTree schema={schema} value={value} onChange={changeValue} global={base} originOf={(path) => originAt(applied, path)} gen={gen} />
            {warnings.length > 0 && (
              <ul className="notes warn">
                {warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            )}
            <JsonPanel value={value} onApply={changeValue} />
            <details className="json-panel">
              <summary>実際に渡す値（共通パラメータを重ねたもの）</summary>
              <pre className="sql">{toJson(sent ?? { t: 'object', fields: [] })}</pre>
            </details>
          </>
        )}
      </main>
      <Splitter pane="params" label="パラメータ欄の幅" panes={panes} />

      <section className="result">
        <div className="tabs" role="tablist">
          {(
            [
              ['template', 'テンプレート'],
              ['sql', 'SQL'],
              ['execute', '実行'],
              ['explain', 'EXPLAIN'],
            ] as const
          ).map(([key, label]) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={tab === key}
              className={tab === key ? 'tab selected' : 'tab'}
              onClick={() => {
                setTab(key);
                saveTab(key);
              }}
            >
              {label}
            </button>
          ))}
        </div>

        {tab === 'sql' && (
          <>
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
            {resolved.generated.length > 0 && (
              <div className="notes info generated">
                式で作った値:
                <ul>
                  {resolved.generated.map((g) => (
                    <li key={g.path}>
                      <code>{g.path}</code> <code className="source">{g.source}</code> → <code>{g.result}</code>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {result && !renderError && <RenderResult result={result} />}
            {!selected && <p className="hint">左の一覧から statement を選んでください。</p>}
          </>
        )}

        {/* 実行結果が消えないよう、切り替えても実行のタブは残しておく */}
        <div hidden={tab !== 'execute'}>
          {selected && schema ? (
            <ExecutePanel
              key={selected}
              conn={conn}
              statementId={selected}
              value={sent}
              ready={numberIssues.length === 0 && !renderError}
              onApiError={handleError}
            />
          ) : (
            <p className="hint">左の一覧から statement を選んでください。</p>
          )}
        </div>

        {/* 取得した計画が消えないよう、切り替えても EXPLAIN のタブは残しておく */}
        <div hidden={tab !== 'explain'}>
          {selected && schema ? (
            <ExplainPanel
              key={selected}
              conn={conn}
              statementId={selected}
              value={sent}
              ready={numberIssues.length === 0 && !renderError}
              onApiError={handleError}
            />
          ) : (
            <p className="hint">左の一覧から statement を選んでください。</p>
          )}
        </div>

        {tab === 'template' &&
          (selected ? (
            <TemplateView api={api} statementId={selected} generation={generation} onApiError={handleError} opener={opener} />
          ) : (
            <p className="hint">左の一覧から statement を選んでください。</p>
          ))}
      </section>
    </div>
      </div>

      <div className="screen" hidden={screen !== 'globals'}>
        <GlobalsScreen
          api={api}
          globals={globals}
          statements={statements}
          generation={generation}
          gen={gen}
          renderJson={(v, apply) => <JsonPanel value={v} onApply={apply} />}
          focusId={globalFocus}
        />
      </div>

      <div className="screen scroll" hidden={screen !== 'bulk'}>
        <BulkCheck
          api={api}
          conn={conn}
          statements={statements}
          globalSets={globals.sets}
          globalOrder={globals.order}
          onOpen={(id) => {
            select(id);
            setTab('explain');
            saveTab('explain');
            setScreen('query');
            window.history.pushState(null, '', hashOf('query', id));
          }}
        />
      </div>

      <div className="screen scroll" hidden={screen !== 'connections'}>
        <ConnectionsScreen conn={conn} />
      </div>
    </div>
  );
}

// ---- statement の一覧（ディレクトリ → ファイル → statement のツリー） ----

const TOGGLED_KEY = 'mybatis-pg.tree';

/** 既定（ディレクトリは開く、ファイルは閉じる）から切り替えた項目の key */
function loadToggled(): Set<string> {
  try {
    return new Set(JSON.parse(localStorage.getItem(TOGGLED_KEY) ?? '[]') as string[]);
  } catch {
    return new Set();
  }
}

function saveToggled(keys: Set<string>) {
  try {
    localStorage.setItem(TOGGLED_KEY, JSON.stringify([...keys]));
  } catch {
    // 保存できない環境では、この表示中だけ覚える
  }
}

function StatementTree({
  statements,
  selected,
  onSelect,
  opener,
}: {
  statements: StatementEntry[];
  selected: string | null;
  onSelect: (id: string) => void;
  opener: Opener;
}) {
  const [filter, setFilter] = useState('');
  const [toggled, setToggled] = useState(loadToggled);
  const filtering = filter.trim() !== '';
  const tree = useMemo(() => buildTree(filterEntries(statements, filter)), [statements, filter]);

  const isOpen = (n: TreeNode) => filtering || (n.kind === 'dir' ? !toggled.has(n.key) : toggled.has(n.key));
  const update = (f: (next: Set<string>) => void) =>
    setToggled((prev) => {
      const next = new Set(prev);
      f(next);
      saveToggled(next);
      return next;
    });
  const toggle = (n: TreeNode) => update((next) => (next.has(n.key) ? next.delete(n.key) : next.add(n.key)));

  // 選ばれている statement が見えるよう、そこまでの項目を開く（URL から開いたときなど）
  const loaded = statements.length > 0;
  useEffect(() => {
    if (!selected || !loaded) return;
    const keys = pathTo(buildTree(statements), selected);
    update((next) => {
      for (const k of keys) {
        if (k.endsWith('/')) next.delete(k);
        else next.add(k);
      }
    });
  }, [selected, loaded]);

  const render = (nodes: TreeNode[]) => (
    <ul>
      {nodes.map((n) => {
        const open = isOpen(n);
        return (
          <li key={n.key}>
            <div className="node-line">
              <button type="button" className={`node ${n.kind}`} aria-expanded={open} onClick={() => toggle(n)} title={n.key}>
                <span className="twisty">{open ? '▾' : '▸'}</span>
                {n.kind === 'dir' ? n.label + '/' : n.label}
                {n.kind === 'file' && <span className="count">{n.statements.length}</span>}
              </button>
              {n.kind === 'file' && opener && (
                <button type="button" className="icon open-file" title={opener.label} aria-label={`${n.label} を${opener.label}`} onClick={() => opener.open({ file: n.key })}>
                  ↗
                </button>
              )}
            </div>
            {open && n.kind === 'dir' && render(n.children)}
            {open && n.kind === 'file' && (
              <ul>
                {n.statements.map((s) => (
                  <li key={s.id}>
                    <button type="button" className={s.id === selected ? 'node stmt selected' : 'node stmt'} onClick={() => onSelect(s.id)} title={s.id}>
                      {shortId(s.id)}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </li>
        );
      })}
    </ul>
  );

  return (
    <div className="statements">
      <input type="search" placeholder="statement・ファイルを絞り込み" aria-label="statement を絞り込み" value={filter} onChange={(e) => setFilter(e.target.value)} />
      {tree.length === 0 ? <p className="hint">該当する statement がありません</p> : <nav className="tree-list">{render(tree)}</nav>}
    </div>
  );
}

function LoadErrors({ errors, opener }: { errors: LoadError[]; opener: Opener }) {
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
              {opener && e.source.endsWith('.xml') && (
                <button type="button" className="link" onClick={() => opener.open({ file: e.source, line: e.line })}>
                  {opener.label}
                </button>
              )}
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

/** mapper XML に書かれたままの statement */
function TemplateView({
  api,
  statementId,
  generation,
  onApiError,
  opener,
}: {
  api: Api;
  statementId: string;
  generation: number;
  onApiError: (e: unknown) => void;
  opener: Opener;
}) {
  const [template, setTemplate] = useState<TemplateResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  // XML が変わったら取り直す
  useEffect(() => {
    if (generation === 0) return;
    let active = true;
    api
      .template(statementId)
      .then((t) => {
        if (!active) return;
        setTemplate(t);
        setError(null);
      })
      .catch((e) => {
        if (!active) return;
        onApiError(e);
        setTemplate(null);
        setError(String(e.message ?? e));
      });
    return () => {
      active = false;
    };
  }, [api, statementId, generation]);

  if (error) return <pre className="render-error">{error}</pre>;
  if (!template) return null;
  const lineCount = template.xml.split('\n').length;
  return (
    <div className="sql-block">
      <div className="sql-title">
        <code className="template-file">
          {template.file}:{template.line}
        </code>
        <span className="actions">
          {opener && (
            <button type="button" onClick={() => opener.open({ statementId })}>
              {opener.label}
            </button>
          )}
          <CopyButton text={template.xml} />
        </span>
      </div>
      <div className="template">
        <pre className="gutter" aria-hidden="true">
          {Array.from({ length: lineCount }, (_, i) => template.line + i).join('\n')}
        </pre>
        <pre className="code">
          {tokenize(template.xml).map((t, i) =>
            t.kind === 'text' ? t.text : (
              <span key={i} className={'t-' + t.kind}>
                {t.text}
              </span>
            ),
          )}
        </pre>
      </div>
    </div>
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
