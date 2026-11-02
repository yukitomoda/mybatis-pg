// ワークスペース: 開いているワークスペースの名前とソース（プロジェクト・JAR・mapper のファイル）を変え、
// ほかのワークスペースを開いたり、作ったり、消したりする
import { useEffect, useRef, useState } from 'react';
import type { Api, WorkspaceSnapshot, WorkspaceSource } from './api';
import type { Connections } from './Execution';
import { ConnectionPicker, ConnectionsScreen, message } from './Execution';
import { clearDbComments } from './comments';

/** ワークスペースの画面で見るもの（左のサブメニュー） */
export type WorkspaceSection = 'sources' | 'connections' | 'schemas' | 'list';

const SECTIONS: [WorkspaceSection, string][] = [
  ['sources', '全般'],
  ['connections', '接続先'],
  ['schemas', 'スキーマ'],
  ['list', 'ワークスペースの一覧'],
];

const TYPES: [WorkspaceSource['type'], string][] = [
  ['project', 'プロジェクト'],
  ['jar', 'JAR'],
  ['files', 'mapper XML'],
];

const PLACEHOLDER: Record<WorkspaceSource['type'], string> = {
  project: '/home/me/work/order-api',
  jar: '/home/me/lib/common-mapper-1.4.jar',
  files: '/home/me/work/batch/src/main/resources/mapper',
};

/** パスの最後の名前（ソースの名前の候補） */
const lastName = (path: string) =>
  path
    .replace(/[\\/]+$/, '')
    .split(/[\\/]/)
    .pop() ?? '';

export function WorkspaceScreen({
  api,
  active,
  onOpened,
  onSaved,
  conn,
  section,
  onSection,
}: {
  api: Api;
  conn: Connections;
  section: WorkspaceSection;
  onSection: (s: WorkspaceSection) => void;
  active: boolean;
  /** ほかのワークスペースを開いた（画面を読み込み直す） */
  onOpened: () => void;
  /** ソースを変えた（プロジェクトの接続先を読み直す） */
  onSaved: () => void;
}) {
  const connections = conn.connections;
  const [snap, setSnap] = useState<WorkspaceSnapshot | null>(null);
  const [name, setName] = useState('');
  const [sources, setSources] = useState<WorkspaceSource[]>([]);
  const [relationConnection, setRelationConnection] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [newName, setNewName] = useState('');
  const importFile = useRef<HTMLInputElement>(null);

  /** 開いているワークスペースの定義（保存したもの）を、JSON のファイルにして保存する。パスワードや解析の結果は含めない */
  const exportDefinition = () => {
    const cur = snap?.current;
    if (!cur) return;
    const def = {
      format: 'mybatis-pg-workspace',
      version: 1,
      name: cur.name,
      sources: cur.sources.map(({ name, type, path, enabled }) => ({ name, type, path, enabled })),
      relationConnection: cur.relationConnection,
      schemas: cur.schemas,
    };
    const url = URL.createObjectURL(new Blob([JSON.stringify(def, null, 2)], { type: 'application/json' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `${cur.name}.workspace.json`;
    a.click();
    URL.revokeObjectURL(url);
  };
  const importDefinition = async (file: File) => {
    let def: unknown;
    try {
      def = JSON.parse(await file.text());
    } catch {
      setError('JSON のファイルではありません');
      return;
    }
    const name = (def as { name?: unknown })?.name;
    await run(() => api.importWorkspace(def), `「${typeof name === 'string' ? name : ''}」を作りました`);
  };

  const show = (s: WorkspaceSnapshot) => {
    setSnap(s);
    setName(s.current?.name ?? '');
    setSources(s.current?.sources ?? []);
    setRelationConnection(s.current?.relationConnection ?? null);
    setDirty(false);
  };
  useEffect(() => {
    if (!active || dirty) return;
    api.workspace().then(show, (e) => setError(message(e)));
  }, [api, active]);

  const run = async (f: () => Promise<WorkspaceSnapshot>, done?: string, opened = false, saved = false) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      show(await f());
      if (done) setNotice(done);
      if (opened) onOpened();
      if (saved) onSaved();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  };
  const edit = (i: number, patch: Partial<WorkspaceSource>) => {
    setSources((old) => old.map((s, k) => (k === i ? { ...s, ...patch } : s)));
    setDirty(true);
    setNotice(null);
  };

  if (!snap)
    return (
      <div className="workspace page">{error ? <p className="error-text">{error}</p> : <p className="hint">読み込んでいます…</p>}</div>
    );
  if (!snap.current) return <div className="workspace page">ワークスペースを使っていません。</div>;
  const others = snap.workspaces.filter((w) => !w.current);

  const messages = (
    <>
      {error && <pre className="render-error">{error}</pre>}
      {notice && <p className="ok-text">{notice}</p>}
    </>
  );
  return (
    <div className="workspace">
      <nav className="workspace-nav">
        <div className="workspace-nav-name">{snap.current.name}</div>
        {SECTIONS.map(([key, label]) => (
          <button key={key} type="button" className={section === key ? 'selected' : undefined} onClick={() => onSection(key)}>
            {label}
          </button>
        ))}
      </nav>
      <div className="workspace-content">
        {section === 'sources' && (
          <div className="page">
            <header className="panel-header">
              <h2>全般</h2>
            </header>
            {messages}
            <section className="workspace-current">
              <label className="workspace-name">
                名前
                <input
                  value={name}
                  onChange={(e) => {
                    setName(e.target.value);
                    setDirty(true);
                  }}
                />
              </label>

              <h3>ソース</h3>
              <table className="workspace-sources">
                <thead>
                  <tr>
                    <th>有効</th>
                    <th>名前</th>
                    <th>種類</th>
                    <th>パス</th>
                    <th>mapper XML</th>
                    <th aria-label="操作" />
                  </tr>
                </thead>
                <tbody>
                  {sources.map((s, i) => (
                    <tr key={s.id ?? `new-${i}`} className={s.enabled ? undefined : 'disabled'}>
                      <td>
                        <input
                          type="checkbox"
                          checked={s.enabled}
                          aria-label="有効"
                          onChange={(e) => edit(i, { enabled: e.target.checked })}
                        />
                      </td>
                      <td>
                        <input
                          className="workspace-source-name"
                          value={s.name}
                          placeholder={lastName(s.path) || '名前'}
                          onChange={(e) => edit(i, { name: e.target.value })}
                        />
                      </td>
                      <td>
                        <select value={s.type} onChange={(e) => edit(i, { type: e.target.value as WorkspaceSource['type'] })}>
                          {TYPES.map(([t, label]) => (
                            <option key={t} value={t}>
                              {label}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td>
                        <input
                          className="workspace-source-path"
                          value={s.path}
                          placeholder={PLACEHOLDER[s.type]}
                          onChange={(e) => edit(i, { path: e.target.value, error: null, files: null })}
                        />
                      </td>
                      <td className="workspace-source-state">
                        {s.error ? (
                          <span className="error-text">{s.error}</span>
                        ) : !s.enabled ? (
                          <span className="hint">無効</span>
                        ) : s.files === -1 ? (
                          <span className="error-text">読めません</span>
                        ) : s.files != null ? (
                          `${s.files} 件`
                        ) : null}
                      </td>
                      <td>
                        <button
                          type="button"
                          className="link"
                          onClick={() => {
                            setSources((old) => old.filter((_, k) => k !== i));
                            setDirty(true);
                          }}
                        >
                          外す
                        </button>
                      </td>
                    </tr>
                  ))}
                  {sources.length === 0 && (
                    <tr>
                      <td colSpan={6} className="hint">
                        ソースがありません
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
              <div className="workspace-actions">
                <button
                  type="button"
                  className="link"
                  onClick={() => {
                    setSources((old) => [...old, { name: '', type: 'project', path: '', enabled: true }]);
                    setDirty(true);
                  }}
                >
                  ＋ ソースを追加
                </button>
                <span className="spacer" />
                <button
                  type="button"
                  disabled={busy || !dirty}
                  onClick={() =>
                    run(
                      () =>
                        api.saveWorkspace(
                          name,
                          sources.map((s) => ({ ...s, name: s.name.trim() || lastName(s.path) })),
                          relationConnection,
                        ),
                      '保存しました',
                      false,
                      true,
                    )
                  }
                >
                  保存
                </button>
                {dirty && (
                  <button type="button" className="link" disabled={busy} onClick={() => run(() => api.workspace())}>
                    元に戻す
                  </button>
                )}
              </div>
              <h3>リレーションの解析の結果</h3>
              <label className="workspace-relation">
                <select
                  value={relationConnection ?? ''}
                  onChange={(e) => {
                    setRelationConnection(e.target.value || null);
                    setDirty(true);
                  }}
                >
                  <option value="">接続先ごと</option>
                  {connections.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name} の結果を共有
                    </option>
                  ))}
                  {relationConnection && !connections.some((c) => c.id === relationConnection) && (
                    <option value={relationConnection}>（今はない接続先: {relationConnection}）</option>
                  )}
                </select>
              </label>
            </section>
          </div>
        )}
        {section === 'connections' && <ConnectionsScreen conn={conn} />}
        {section === 'schemas' && (
          <SchemasSection
            api={api}
            conn={conn}
            selected={snap.current.schemas}
            onSave={(schemas) =>
              run(() => api.saveWorkspaceSchemas(schemas), '保存しました').then(() => {
                clearDbComments();
              })
            }
            busy={busy}
            messages={messages}
          />
        )}
        {section === 'list' && (
          <div className="page">
            <header className="panel-header">
              <h2>ワークスペースの一覧</h2>
            </header>
            {messages}
            <section className="workspace-others">
              {others.length === 0 ? (
                <p className="hint">ありません</p>
              ) : (
                <ul>
                  {others.map((w) => (
                    <li key={w.id}>
                      <span className="workspace-other-name">{w.name}</span>
                      <span className="hint">ソース {w.sources} 件</span>
                      <button type="button" disabled={busy || dirty} onClick={() => run(() => api.openWorkspace(w.id), undefined, true)}>
                        開く
                      </button>
                      <button
                        type="button"
                        className="link"
                        disabled={busy}
                        onClick={() => window.confirm(`「${w.name}」を消しますか？`) && run(() => api.deleteWorkspace(w.id))}
                      >
                        消す
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <div className="workspace-transfer">
                <button type="button" className="link" onClick={exportDefinition}>
                  書き出す
                </button>
                <button type="button" className="link" disabled={busy} onClick={() => importFile.current?.click()}>
                  読み込む
                </button>
                <input
                  ref={importFile}
                  type="file"
                  accept=".json,application/json"
                  hidden
                  onChange={(e) => {
                    const f = e.target.files?.[0];
                    e.target.value = '';
                    if (f) importDefinition(f);
                  }}
                />
              </div>
              <form
                className="workspace-create"
                onSubmit={(e) => {
                  e.preventDefault();
                  run(() => api.createWorkspace(newName.trim()), undefined, true).then(() => setNewName(''));
                }}
              >
                <input value={newName} placeholder="新しいワークスペース" onChange={(e) => setNewName(e.target.value)} />
                <button type="submit" disabled={busy || dirty}>
                  作る
                </button>
              </form>
            </section>
          </div>
        )}
      </div>
    </div>
  );
}

type SchemaRow = { name: string; tables: number; searchPath: boolean; used: boolean };

/** 画面に出す DB のスキーマ（表の一覧・コメント）。自動なら、解析で使ったもの（なければ search_path のもの） */
function SchemasSection({
  api,
  conn,
  selected,
  onSave,
  busy,
  messages,
}: {
  api: Api;
  conn: Connections;
  selected: string[] | null;
  onSave: (schemas: string[] | null) => Promise<void>;
  busy: boolean;
  messages: React.ReactNode;
}) {
  const connectionId = conn.current?.id ?? null;
  const [rows, setRows] = useState<SchemaRow[] | null>(null);
  const [shown, setShown] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [auto, setAuto] = useState(selected === null);
  const [picked, setPicked] = useState<Set<string>>(new Set(selected ?? []));
  const [filter, setFilter] = useState('');
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    setAuto(selected === null);
    setPicked(new Set(selected ?? []));
    setDirty(false);
  }, [selected]);
  useEffect(() => {
    setRows(null);
    setError(null);
    if (!connectionId) return;
    api.dbSchemas(connectionId).then(
      (r) => {
        setRows(r.schemas);
        setShown(r.shown);
      },
      (e) => setError(message(e)),
    );
  }, [api, connectionId, selected]);
  // 自動のときに出すもの（解析で使ったもの。なければ search_path のもの）
  const autoSet = new Set(shown ?? (rows ?? []).filter((r) => r.searchPath).map((r) => r.name));
  const isOn = (name: string) => (auto ? autoSet.has(name) : picked.has(name));
  const q = filter.trim().toLowerCase();
  const list = (rows ?? [])
    .filter((r) => r.name.toLowerCase().includes(q))
    // 出すもの、解析で使ったもの、search_path のものを先に
    .sort((a, b) => +isOn(b.name) - +isOn(a.name) || +b.used - +a.used || +b.searchPath - +a.searchPath || a.name.localeCompare(b.name));
  const toggle = (name: string, on: boolean) => {
    setPicked((old) => {
      const next = new Set(auto ? autoSet : old);
      if (on) next.add(name);
      else next.delete(name);
      return next;
    });
    setAuto(false);
    setDirty(true);
  };
  return (
    <div className="page">
      <header className="panel-header">
        <h2>スキーマ</h2>
        <ConnectionPicker conn={conn} />
      </header>
      {messages}
      {error && <pre className="render-error">{error}</pre>}
      <div className="workspace-schemas-mode">
        <label>
          <input
            type="radio"
            checked={auto}
            onChange={() => {
              setAuto(true);
              setDirty(true);
            }}
          />
          自動（解析で使ったもの）
        </label>
        <label>
          <input
            type="radio"
            checked={!auto}
            onChange={() => {
              setPicked(new Set(autoSet));
              setAuto(false);
              setDirty(true);
            }}
          />
          選ぶ
        </label>
        <span className="spacer" />
        <button type="button" disabled={busy || !dirty} onClick={() => onSave(auto ? null : [...picked].sort())}>
          保存
        </button>
      </div>
      {!connectionId ? null : rows === null ? (
        !error && <p className="hint">読み込んでいます…</p>
      ) : (
        <>
          <input
            type="search"
            className="workspace-schemas-filter"
            placeholder="スキーマを絞り込む"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <table className="workspace-schemas">
            <thead>
              <tr>
                <th aria-label="出す" />
                <th>スキーマ</th>
                <th>表</th>
                <th>解析で使用</th>
              </tr>
            </thead>
            <tbody>
              {list.map((r) => (
                <tr key={r.name}>
                  <td>
                    <input type="checkbox" checked={isOn(r.name)} aria-label={r.name} onChange={(e) => toggle(r.name, e.target.checked)} />
                  </td>
                  <td>
                    <code>{r.name}</code>
                  </td>
                  <td className="num">{r.tables}</td>
                  <td>{r.used ? '✓' : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
