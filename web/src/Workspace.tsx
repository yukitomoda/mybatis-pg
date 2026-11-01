// ワークスペース: 開いているワークスペースの名前とソース（プロジェクト・JAR・mapper のファイル）を変え、
// ほかのワークスペースを開いたり、作ったり、消したりする
import { useEffect, useRef, useState } from 'react';
import type { Api, WorkspaceSnapshot, WorkspaceSource } from './api';
import { message } from './Execution';

const TYPES: [WorkspaceSource['type'], string, string][] = [
  ['project', 'プロジェクト', 'Spring Boot のプロジェクトのディレクトリ（application.yml の mapper-locations を読む）'],
  ['jar', 'JAR', 'JAR か war（中の mapper XML を読む。ファイルには保存できない）'],
  ['files', 'mapper のファイル', 'mapper XML のファイルか、それを含むディレクトリ'],
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
  connections,
}: {
  api: Api;
  /** 接続先（リレーションの解析の結果を共有する接続先を選ぶ） */
  connections: { id: string; name: string }[];
  active: boolean;
  /** ほかのワークスペースを開いた（画面を読み込み直す） */
  onOpened: () => void;
  /** ソースを変えた（プロジェクトの接続先を読み直す） */
  onSaved: () => void;
}) {
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
    await run(
      () => api.importWorkspace(def),
      `「${typeof name === 'string' ? name : ''}」を作りました。「開く」で使えます（パスが合わなければ、開いてから直してください）`,
    );
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

  return (
    <div className="workspace page">
      <header className="panel-header">
        <h2>ワークスペース</h2>
        <span className="hint">複数のプロジェクトや JAR の mapper XML をまとめて扱い、リレーションの解析もまとめて行います</span>
      </header>

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
              <th>パス（サーバのマシン上の絶対パス）</th>
              <th>mapper XML</th>
              <th aria-label="操作" />
            </tr>
          </thead>
          <tbody>
            {sources.map((s, i) => (
              <tr key={s.id ?? `new-${i}`} className={s.enabled ? undefined : 'disabled'}>
                <td>
                  <input type="checkbox" checked={s.enabled} aria-label="有効" onChange={(e) => edit(i, { enabled: e.target.checked })} />
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
                  <select
                    value={s.type}
                    onChange={(e) => edit(i, { type: e.target.value as WorkspaceSource['type'] })}
                    title={TYPES.find((t) => t[0] === s.type)?.[2]}
                  >
                    {TYPES.map(([t, label, title]) => (
                      <option key={t} value={t} title={title}>
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
                  ) : (
                    <span className="hint">保存すると読みます</span>
                  )}
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
                  ソースがありません。「＋ ソースを追加」から、プロジェクトや JAR を足してください。
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
          {dirty && <span className="hint">変更があります</span>}
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
                '保存して、読み込み直しました',
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
            <option value="">接続先ごとに分ける</option>
            {connections.map((c) => (
              <option key={c.id} value={c.id}>
                「{c.name}」の結果を、すべての接続先で使う
              </option>
            ))}
            {relationConnection && !connections.some((c) => c.id === relationConnection) && (
              <option value={relationConnection}>（今はない接続先: {relationConnection}）</option>
            )}
          </select>
          <span className="hint">検証用の DB のスキーマが同じなら、1 つの DB で解析した結果を、ほかの DB の値の候補にも使えます</span>
        </label>
        {error && <pre className="render-error">{error}</pre>}
        {notice && <p className="ok-text">{notice}</p>}
        <p className="hint">
          ソースが 2 つ以上あるときは、statement
          の一覧で、ファイルを「ソースの名前/パス」で出します。共通パラメータ・実行の履歴・リレーションの解析の結果は、ワークスペースごとに分けて保存します。
        </p>
      </section>

      <section className="workspace-others">
        <h3>ほかのワークスペース</h3>
        {others.length === 0 ? (
          <p className="hint">ありません</p>
        ) : (
          <ul>
            {others.map((w) => (
              <li key={w.id}>
                <span className="workspace-other-name">{w.name}</span>
                <span className="hint">ソース {w.sources} 件</span>
                <button
                  type="button"
                  disabled={busy || dirty}
                  title={dirty ? '変更を保存するか元に戻してから開いてください' : undefined}
                  onClick={() => run(() => api.openWorkspace(w.id), `「${w.name}」を開きました`, true)}
                >
                  開く
                </button>
                <button
                  type="button"
                  className="link"
                  disabled={busy}
                  onClick={() =>
                    window.confirm(`ワークスペース「${w.name}」を消しますか？（リレーションの解析の結果などは残ります）`) &&
                    run(() => api.deleteWorkspace(w.id))
                  }
                >
                  消す
                </button>
              </li>
            ))}
          </ul>
        )}
        <div className="workspace-transfer">
          <button
            type="button"
            className="link"
            onClick={exportDefinition}
            title="名前・ソース・解析の結果の共有の設定を、JSON のファイルで保存します（パスワードや解析の結果は含めません）"
          >
            このワークスペースを書き出す
          </button>
          <button
            type="button"
            className="link"
            disabled={busy}
            onClick={() => importFile.current?.click()}
            title="書き出したファイルから、新しいワークスペースを作ります"
          >
            書き出したファイルから作る
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
            run(() => api.createWorkspace(newName.trim()), `「${newName.trim() || 'ワークスペース'}」を作って開きました`, true).then(() =>
              setNewName(''),
            );
          }}
        >
          <input value={newName} placeholder="新しいワークスペースの名前" onChange={(e) => setNewName(e.target.value)} />
          <button type="submit" disabled={busy || dirty}>
            作って開く
          </button>
        </form>
      </section>
    </div>
  );
}
