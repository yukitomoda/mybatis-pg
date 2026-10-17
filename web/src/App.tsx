import { useEffect, useMemo, useRef, useState } from 'react';
import type { Api, LoadError, OpenTarget, PlaygroundEntry, RenderResponse, TemplateResponse } from './api';
import { ApiError, createApi, takeToken } from './api';
import { BulkCheck } from './Bulk';
import { ExecutePanel, useConnections } from './Execution';
import { ExplainPanel } from './Explain';
import { SubqueryPanel } from './Subquery';
import type { GenCache } from './gen';
import { forget, resolveTemplates } from './gen';
import type { GenContext } from './GenInput';
import type { Drafts } from './drafts';
import { draftParam, useDrafts } from './drafts';
import { FileEditor } from './FileEditor';
import { GlobalsScreen, StatementGlobals, useGlobals } from './Globals';
import { ConnectionsScreen } from './Execution';
import { applicable, combine, originAt } from './globalModel';
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

type Tab = 'sql' | 'execute' | 'explain' | 'subquery' | 'template';
const TAB_KEY = 'mybatis-pg.tab';

function loadTab(): Tab {
  try {
    const saved = localStorage.getItem(TAB_KEY);
    return saved === 'execute' || saved === 'explain' || saved === 'subquery' || saved === 'template' ? saved : 'sql';
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
  const [playgrounds, setPlaygrounds] = useState<PlaygroundEntry[]>([]);
  /** ファイルに保存できない（JAR から読み込んだときなど） */
  const [readOnly, setReadOnly] = useState(false);
  /** プレイグラウンドを作ったり保存したりするたびに増やして、statement 一覧を取り直す */
  const [playgroundVersion, setPlaygroundVersion] = useState(0);
  /** statement を選ばずにエディタで開いているファイル（statement がないプレイグラウンドなど） */
  const [looseFile, setLooseFile] = useState<string | null>(null);
  const [schema, setSchema] = useState<ParamSchema | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [value, setValue] = useState<PValue | undefined>(() => (selected ? loadParams(selected) : undefined));
  const [result, setResult] = useState<RenderResponse | null>(null);
  const [renderError, setRenderError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>(loadTab);
  /** テンプレートのタブを開いたことがあるか（エディタは開くまで読み込まない） */
  const [templateSeen, setTemplateSeen] = useState(false);
  if (tab === 'template' && !templateSeen) setTemplateSeen(true);
  /** パラメータ欄のタブ: 個別の値か、共通パラメータか */
  const [paramTab, setParamTab] = useState<'local' | 'global'>('local');
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
  const drafts = useDrafts(api, generation, handleError);

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
        setPlaygrounds(r.playgrounds);
        setReadOnly(r.readOnly);
        setLoadErrors(r.loadErrors);
      })
      .catch(handleError);
    // 下書きやプレイグラウンドを保存するたびにも取り直す（足した・消した statement を反映する）
  }, [api, generation, drafts.version, playgroundVersion]);

  const target = statements.find((s) => s.id === selected) ?? null;
  /**
   * 選んでいる statement が最後にあったファイル。XML の誤りで statement が一時的に一覧から消えても、
   * そのファイルをエディタで開いたままにして、誤りを直せるようにする
   */
  const [lastFile, setLastFile] = useState<{ id: string; file: string } | null>(loadLastFile);
  if (target && (lastFile?.id !== target.id || lastFile.file !== target.file)) {
    setLastFile({ id: target.id, file: target.file });
    saveLastFile({ id: target.id, file: target.file });
  }
  const editorFile = target?.file ?? (lastFile && lastFile.id === selected ? lastFile.file : null);
  const brokenFile = !target && editorFile !== null && playgrounds.some((p) => p.file === editorFile && p.errors.length > 0);
  // 編集中の mapper XML があれば、組み立て・実行・EXPLAIN にその内容を使う
  const draftP = draftParam(drafts.get(target?.file));
  // プレイグラウンドは自動で保存するので、保存するたびに組み立て直す
  const playgroundKey = target?.playground ? playgroundVersion : 0;

  /** statement が見つからないときの説明（XML を読み込めないためか、消えたためか。表示するときに決める） */
  const notFoundMessage = brokenFile
    ? 'XML を読み込めないため、この statement を使えません。テンプレートのタブで XML を直してください'
    : 'この statement は見つかりません（削除または改名された可能性があります）';
  const describe = (message: string | null) => (message === NOT_FOUND ? notFoundMessage : message);

  // パラメータの木（XML が変わったら取り直す）
  useEffect(() => {
    if (!selected || generation === 0) {
      setSchema(null);
      return;
    }
    let active = true;
    api
      .parameters(selected, draftP)
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
        const message = e instanceof ApiError && e.status === 404 ? NOT_FOUND : String(e.message ?? e);
        setSchemaError(message);
        // 前の SQL が今の XML のものに見えないよう、消してエラーを出す
        setResult(null);
        setRenderError(message);
      });
    return () => {
      active = false;
    };
  }, [api, selected, generation, draftP?.content, playgroundKey]);

  const globals = useGlobals(api, handleError);
  // 選んでいる statement に効く共通パラメータ（優先順位の低い順）と、それを重ねた値
  const applied = useMemo(() => (selected ? applicable(globals.sets, globals.statements[selected]) : []), [globals.sets, globals.statements, selected]);
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
        .render(selected, sent, abort.signal, draftP)
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
  }, [api, selected, schema, sent, numberIssues.length, generation, draftP?.content, playgroundKey]);

  const selectedRef = useRef(selected);
  selectedRef.current = selected;
  const select = (id: string) => {
    setSelected(id);
    setLooseFile(null);
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

  /** ファイルを「テンプレート」タブのエディタで開く（今の statement がそのファイルのものなら、そのまま） */
  const editFile = (file: string) => {
    const s = statements.find((x) => x.file === file);
    if (!s) setLooseFile(file);
    else if (target?.file !== file) select(s.id);
    else setLooseFile(null);
    setTab('template');
    saveTab('template');
  };

  // ---- プレイグラウンド ----
  const reloadPlaygrounds = () => setPlaygroundVersion((v) => v + 1);
  const createPlayground = async () => {
    const name = window.prompt('新しいプレイグラウンドの名前:');
    if (!name) return;
    try {
      await api.createPlayground(name);
      const r = await api.statements();
      setStatements(r.statements);
      setPlaygrounds(r.playgrounds);
      const file = `playground:${name.trim()}`;
      const s = r.statements.find((x) => x.file === file);
      if (s) select(s.id);
      else setLooseFile(file);
      setTab('template');
      saveTab('template');
    } catch (e) {
      handleError(e);
      window.alert(e instanceof Error ? e.message : String(e));
    }
  };
  const playgroundOf = (file: string) => {
    const p = playgrounds.find((x) => x.file === file);
    if (!p) return undefined;
    return {
      name: p.name,
      onSaved: reloadPlaygrounds,
      onRename: async () => {
        const newName = window.prompt('新しい名前:', p.name);
        if (!newName || newName === p.name) return;
        try {
          await api.renamePlayground(p.name, newName);
          if (looseFile === p.file) setLooseFile(`playground:${newName.trim()}`);
          reloadPlaygrounds();
        } catch (e) {
          handleError(e);
          window.alert(e instanceof Error ? e.message : String(e));
        }
      },
      onDelete: async () => {
        if (!window.confirm(`プレイグラウンド「${p.name}」を削除しますか？`)) return;
        try {
          await api.deletePlayground(p.name);
          if (target?.file === p.file) {
            setSelected(null);
            window.history.replaceState(null, '', hashOf('query', null));
          }
          setLooseFile(null);
          reloadPlaygrounds();
        } catch (e) {
          handleError(e);
          window.alert(e instanceof Error ? e.message : String(e));
        }
      },
    };
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
        <DraftList
          drafts={drafts}
          onOpen={editFile}
          hasStatements={(file) => statements.some((x) => x.file === file)}
        />
        <StatementTree
          statements={statements}
          playgrounds={playgrounds}
          onCreatePlayground={createPlayground}
          selected={selected}
          onSelect={select}
          onEdit={editFile}
          drafted={drafts.files}
        />
        <LoadErrors errors={loadErrors} opener={opener} />
      </aside>
      <Splitter pane="sidebar" label="statement 一覧の幅" panes={panes} />

      <main className="params">
        <header className="panel-header">
          <h2>パラメータ</h2>
          {selected && <code className="statement-id">{selected}</code>}
        </header>
        {!selected && <p className="hint">左の一覧から statement を選んでください。</p>}
        {schemaError && <p className="error-text">{describe(schemaError)}</p>}
        {selected && schema && (
          <>
            <div className="tabs mode" role="tablist">
              <button type="button" role="tab" aria-selected={paramTab === 'local'} className={paramTab === 'local' ? 'tab selected' : 'tab'} onClick={() => setParamTab('local')}>
                個別
              </button>
              <button type="button" role="tab" aria-selected={paramTab === 'global'} className={paramTab === 'global' ? 'tab selected' : 'tab'} onClick={() => setParamTab('global')}>
                共通<span className="hint">（{applied.length}）</span>
              </button>
            </div>
            {paramTab === 'global' && target ? (
              <StatementGlobals
                globals={globals}
                statementId={target.id}
                onSettings={(id) => {
                  setGlobalFocus(id);
                  go('globals');
                }}
              />
            ) : (
              <>
                <ParamTree schema={schema} value={value} onChange={changeValue} global={base} originOf={(path) => originAt(applied, path)} gen={gen} />
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
              ['subquery', 'サブクエリ'],
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
              {key === 'template' && draftP && (
                <span className="draft-mark" title="編集中（ファイルには保存していません）" aria-label="編集中">
                  ●
                </span>
              )}
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
            {renderError && <pre className="render-error">{describe(renderError)}</pre>}
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
              draft={draftP}
              ready={numberIssues.length === 0 && !renderError}
              onApiError={handleError}
            />
          ) : (
            <p className="hint">左の一覧から statement を選んでください。</p>
          )}
        </div>

        {/* 実行した結果が消えないよう、切り替えてもサブクエリのタブは残しておく */}
        <div hidden={tab !== 'subquery'}>
          {selected && result && !renderError ? (
            <SubqueryPanel key={selected} conn={conn} sql={result.literalSql} onApiError={handleError} />
          ) : (
            <p className="hint">{selected ? 'SQL を組み立てると、サブクエリを調べられます。' : '左の一覧から statement を選んでください。'}</p>
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
              draft={draftP}
              ready={numberIssues.length === 0 && !renderError}
              onApiError={handleError}
            />
          ) : (
            <p className="hint">左の一覧から statement を選んでください。</p>
          )}
        </div>

        {/* 編集中の位置や取り消しの履歴が消えないよう、切り替えてもテンプレートのタブは残しておく */}
        <div hidden={tab !== 'template'}>
          {templateSeen && looseFile ? (
            <FileEditor
              key={looseFile}
              api={api}
              statementId=""
              file={looseFile}
              drafts={drafts}
              generation={generation}
              onApiError={handleError}
              opener={opener}
              visible={tab === 'template'}
              playground={playgroundOf(looseFile)}
              readOnly={readOnly}
            />
          ) : selected ? (
            !templateSeen ? null : editorFile ? (
              <FileEditor
                api={api}
                statementId={selected}
                file={editorFile}
                drafts={drafts}
                generation={generation}
                onApiError={handleError}
                opener={opener}
                visible={tab === 'template'}
                playground={playgroundOf(editorFile)}
                readOnly={readOnly}
              />
            ) : (
              <TemplateView api={api} statementId={selected} generation={generation} onApiError={handleError} opener={opener} />
            )
          ) : (
            <p className="hint">左の一覧から statement を選んでください。</p>
          )}
        </div>
      </section>
    </div>
      </div>

      <div className="screen" hidden={screen !== 'globals'}>
        <GlobalsScreen
          api={api}
          globals={globals}
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
          globalStatements={globals.statements}
          active={screen === 'bulk'}
          sourceVersion={`${generation}:${drafts.version}`}
          draftOf={(file) => draftParam(drafts.get(file))}
          onOpen={(id) => {
            select(id);
            setTab('explain');
            saveTab('explain');
            setScreen('query');
            window.history.pushState(null, '', hashOf('query', id));
          }}
          onOpenParams={(id) => {
            select(id);
            setParamTab('local');
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
  playgrounds,
  onCreatePlayground,
  selected,
  onSelect,
  onEdit,
  drafted,
}: {
  statements: StatementEntry[];
  playgrounds: PlaygroundEntry[];
  onCreatePlayground: () => void;
  /** ファイルをエディタで開く */
  onEdit: (file: string) => void;
  /** 編集中（下書きがある）ファイル */
  drafted: Set<string>;
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const [filter, setFilter] = useState('');
  const [toggled, setToggled] = useState(loadToggled);
  const filtering = filter.trim() !== '';
  const project = useMemo(() => statements.filter((s) => !s.playground), [statements]);
  const tree = useMemo(() => buildTree(filterEntries(project, filter)), [project, filter]);
  // プレイグラウンドは、ファイルの代わりに名前で並べる（絞り込んでいるときは、当たるものだけ）
  const playgroundNodes = useMemo<TreeNode[]>(() => {
    const matched = new Set(filterEntries(statements.filter((s) => s.playground), filter).map((s) => s.id));
    return playgrounds
      .map((p): TreeNode => ({ kind: 'file', key: p.file, label: p.name, statements: statements.filter((s) => s.file === p.file && (!filtering || matched.has(s.id))) }))
      .filter((n) => !filtering || n.kind !== 'file' || n.statements.length > 0 || n.label.toLowerCase().includes(filter.trim().toLowerCase()));
  }, [statements, playgrounds, filter, filtering]);
  const brokenPlaygrounds = new Set(playgrounds.filter((p) => p.errors.length > 0).map((p) => p.file));
  const playgroundsOpen = filtering || !toggled.has(PLAYGROUND_KEY);

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
  // プレイグラウンドの名前を変えたときなど、ファイルが変わったときも開く
  const selectedFile = statements.find((x) => x.id === selected)?.file;
  useEffect(() => {
    if (!selected || !loaded) return;
    const s = statements.find((x) => x.id === selected);
    const keys = s?.playground ? [s.file] : pathTo(buildTree(statements.filter((x) => !x.playground)), selected);
    update((next) => {
      for (const k of keys) {
        if (k.endsWith('/')) next.delete(k);
        else next.add(k);
      }
    });
  }, [selected, loaded, selectedFile]);

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
                {n.kind === 'file' && brokenPlaygrounds.has(n.key) && (
                  <span className="warn-mark" title="XML を読み込めません" aria-label="読み込めません">
                    ⚠
                  </span>
                )}
                {n.kind === 'file' && drafted.has(n.key) && (
                  <span className="draft-mark" title="編集中（ファイルには保存していません）" aria-label="編集中">
                    ●
                  </span>
                )}
                {n.kind === 'file' && <span className="count">{n.statements.length}</span>}
              </button>
              {n.kind === 'file' && (
                <button type="button" className="icon open-file" title="編集する" aria-label={`${n.label} を編集する`} onClick={() => onEdit(n.key)}>
                  ✎
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
                      {s.added && (
                        <span className="added-mark" title="下書きで足した statement（ファイルには保存していません）" aria-label="下書きで足した">
                          ＋
                        </span>
                      )}
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
      <nav className="tree-list">
        <ul>
          <li>
            <div className="node-line">
              <button
                type="button"
                className="node dir"
                aria-expanded={playgroundsOpen}
                onClick={() => update((next) => (next.has(PLAYGROUND_KEY) ? next.delete(PLAYGROUND_KEY) : next.add(PLAYGROUND_KEY)))}
              >
                <span className="twisty">{playgroundsOpen ? '▾' : '▸'}</span>
                プレイグラウンド
              </button>
              <button type="button" className="icon open-file" title="新しいプレイグラウンド" aria-label="新しいプレイグラウンド" onClick={onCreatePlayground}>
                ＋
              </button>
            </div>
            {playgroundsOpen && playgroundNodes.length > 0 && render(playgroundNodes)}
          </li>
        </ul>
        {tree.length === 0 ? <p className="hint">該当する statement がありません</p> : render(tree)}
      </nav>
    </div>
  );
}

/** statement が見つからないことを表す印（説明は表示するときに決める） */
const NOT_FOUND = '\u0000not-found';

const LAST_FILE_KEY = 'mybatis-pg.last-file';

/** 選んでいた statement が最後にあったファイル（読み込み直しても、XML の誤りを直せるように覚えておく） */
function loadLastFile(): { id: string; file: string } | null {
  try {
    const v = JSON.parse(localStorage.getItem(LAST_FILE_KEY) ?? 'null');
    return v && typeof v.id === 'string' && typeof v.file === 'string' ? { id: v.id, file: v.file } : null;
  } catch {
    return null;
  }
}

function saveLastFile(v: { id: string; file: string }) {
  try {
    localStorage.setItem(LAST_FILE_KEY, JSON.stringify(v));
  } catch {
    // 覚えられない環境では、開いている間だけ覚える
  }
}

/** statement 一覧で、プレイグラウンドの欄を閉じたかを覚えておく key（ディレクトリと同じく、既定は開く） */
const PLAYGROUND_KEY = 'playground/';

/** 編集中（下書きがある）ファイルの一覧。いらなくなった下書きを片付ける */
function DraftList({ drafts, onOpen, hasStatements }: { drafts: Drafts; onOpen: (file: string) => void; hasStatements: (file: string) => boolean }) {
  if (drafts.list.length === 0) return null;
  const when = (t: number) => new Date(t).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  return (
    <details className="draft-list">
      <summary>編集中のファイル {drafts.list.length} 件</summary>
      <ul>
        {drafts.list.map((d) => (
          <li key={d.file}>
            <button type="button" className="link draft-file" title={d.file} disabled={!hasStatements(d.file)} onClick={() => onOpen(d.file)}>
              {d.file.slice(d.file.lastIndexOf('/') + 1)}
            </button>
            <span className="hint">{when(d.updatedAt)}</span>
            {!d.loaded && <span className="badge warn">ファイルがありません</span>}
            {d.loaded && d.changed && <span className="badge warn" title="編集を始めた後に、ファイルが変更されました">ファイルが変更済み</span>}
            <button
              type="button"
              className="link"
              onClick={() => {
                if (window.confirm(`${d.file} の編集を取り消しますか？`)) drafts.discard([d.file]);
              }}
            >
              取り消す
            </button>
          </li>
        ))}
      </ul>
      {drafts.list.length > 1 && (
        <button
          type="button"
          className="link"
          onClick={() => {
            if (window.confirm(`${drafts.list.length} 件のファイルの編集をすべて取り消しますか？`)) drafts.discard(drafts.list.map((d) => d.file));
          }}
        >
          すべて取り消す
        </button>
      )}
    </details>
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
