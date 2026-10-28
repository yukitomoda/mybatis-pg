// DB での実行と、接続先の設定
import { useEffect, useRef, useState } from 'react';
import type { Api, ConnectionInput, ConnectionProfile, DraftParam, ExecuteResponse, ResultTable, VaultStatus } from './api';
import type { PValue } from './params';

const CONNECTION_KEY = 'mybatis-pg.connection';
const MAX_ROWS = 1000;

function loadSelected(): string | null {
  try {
    return localStorage.getItem(CONNECTION_KEY);
  } catch {
    return null;
  }
}

function saveSelected(id: string) {
  try {
    localStorage.setItem(CONNECTION_KEY, id);
  } catch {
    // 保存できない環境では、この表示中だけ覚える
  }
}

export function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/** 接続先の一覧と、選んでいる接続先（実行タブと EXPLAIN タブで共有する） */
/** @param openSettings 接続先の画面を開く */
export function useConnections(api: Api, onApiError: (e: unknown) => void, openSettings: () => void) {
  const [connections, setConnections] = useState<ConnectionProfile[]>([]);
  const [selected, setSelected] = useState<string | null>(loadSelected);
  const [vault, setVault] = useState<VaultStatus | null>(null);

  const reload = () => {
    api.vault().then(setVault, onApiError);
    return api
      .connections()
      .then((r) => setConnections(r.connections))
      .catch(onApiError);
  };

  /** マスターパスワードの操作をして、状態と接続先を取り直す。失敗したら理由を投げる */
  const vaultAction = async (f: () => Promise<VaultStatus>) => {
    const v = await f();
    setVault(v);
    await reload();
  };

  useEffect(() => {
    reload();
  }, [api]);

  const select = (id: string) => {
    setSelected(id);
    saveSelected(id);
  };

  return {
    api,
    connections,
    current: connections.find((c) => c.id === selected) ?? connections[0] ?? null,
    select,
    reload,
    openSettings,
    vault,
    vaultAction,
  };
}

export type Connections = ReturnType<typeof useConnections>;

/** 接続先の選択と「接続先の設定」ボタン。保存したパスワードを使う接続先で、まだ開いていなければ、その場で開ける */
export function ConnectionPicker({ conn }: { conn: Connections }) {
  const { connections, current } = conn;
  const needsUnlock = !!current && current.passwordSaved && !current.hasPassword && conn.vault?.exists && !conn.vault.unlocked;
  return (
    <>
      <select
        aria-label="接続先"
        value={current?.id ?? ''}
        onChange={(e) => conn.select(e.target.value)}
        disabled={connections.length === 0}
      >
        {connections.length === 0 && <option value="">接続先がありません</option>}
        {connections.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </select>
      <button type="button" onClick={conn.openSettings}>
        接続先の設定
      </button>
      {needsUnlock && <UnlockForm conn={conn} />}
    </>
  );
}

/** マスターパスワードを入れて開く */
function UnlockForm({ conn }: { conn: Connections }) {
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  return (
    <form
      className="unlock-form"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError(null);
        try {
          await conn.vaultAction(() => conn.api.vaultUnlock(password));
          setPassword('');
        } catch (err) {
          setError(message(err));
        } finally {
          setBusy(false);
        }
      }}
    >
      <input
        type="password"
        aria-label="マスターパスワード"
        placeholder="マスターパスワード"
        autoComplete="current-password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
      />
      <button type="submit" disabled={busy || password === ''}>
        開く
      </button>
      {error && <span className="error-text">{error}</span>}
    </form>
  );
}

/** 接続先の画面の上: マスターパスワードの設定・開く・閉じる・変更 */
function VaultPanel({ conn }: { conn: Connections }) {
  const { vault, api } = conn;
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [current, setCurrent] = useState('');
  const [changing, setChanging] = useState(false);
  const [status, setStatus] = useState<{ ok: boolean; text: string } | null>(null);
  if (!vault) return null;
  const run = async (f: () => Promise<VaultStatus>, done: string) => {
    setStatus(null);
    try {
      await conn.vaultAction(f);
      setPassword('');
      setConfirm('');
      setCurrent('');
      setChanging(false);
      setStatus({ ok: true, text: done });
    } catch (e) {
      setStatus({ ok: false, text: message(e) });
    }
  };
  const mismatch = confirm !== '' && password !== confirm;
  const newPassword = (
    <>
      <input
        type="password"
        aria-label="新しいマスターパスワード"
        placeholder={`新しいマスターパスワード（${vault.minLength} 文字以上）`}
        autoComplete="new-password"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
      />
      <input
        type="password"
        aria-label="確認のため、もう一度"
        placeholder="確認のため、もう一度"
        autoComplete="new-password"
        value={confirm}
        onChange={(e) => setConfirm(e.target.value)}
      />
      {mismatch && <span className="error-text">一致しません</span>}
    </>
  );
  return (
    <section className="vault-panel">
      <h3>パスワードの保存</h3>
      {!vault.exists ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            run(() => api.vaultSetup(password), 'マスターパスワードを設定しました');
          }}
        >
          <p className="hint">
            マスターパスワードを設定すると、接続先のパスワードを暗号化して保存できます。起動するたびに、マスターパスワードを 1
            回入れて開きます。
          </p>
          {newPassword}
          <button type="submit" disabled={password.length < vault.minLength || password !== confirm}>
            設定
          </button>
        </form>
      ) : !vault.unlocked ? (
        <div className="vault-row">
          <UnlockForm conn={conn} />
          <button
            type="button"
            className="link"
            onClick={() => {
              if (
                window.confirm('マスターパスワードと、保存しているパスワードをすべて消します。よろしいですか？（接続先の設定は残ります）')
              )
                run(() => api.vaultReset(), '保存していたパスワードを消しました');
            }}
          >
            マスターパスワードを忘れた
          </button>
        </div>
      ) : changing ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            run(() => api.vaultChange(current, password), 'マスターパスワードを変えました');
          }}
        >
          <input
            type="password"
            aria-label="今のマスターパスワード"
            placeholder="今のマスターパスワード"
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
          {newPassword}
          <button type="submit" disabled={current === '' || password.length < vault.minLength || password !== confirm}>
            変更
          </button>
          <button type="button" className="link" onClick={() => setChanging(false)}>
            やめる
          </button>
        </form>
      ) : (
        <div className="vault-row">
          <span className="ok-text">開いています（保存したパスワードを使えます）</span>
          <button type="button" onClick={() => run(() => api.vaultLock(), '閉じました')}>
            閉じる
          </button>
          <button type="button" className="link" onClick={() => setChanging(true)}>
            マスターパスワードを変更
          </button>
        </div>
      )}
      {status && <p className={status.ok ? 'ok-text' : 'error-text'}>{status.text}</p>}
    </section>
  );
}

/**
 * 実行の操作と結果。実行は常にトランザクションの中で行い、既定ではロールバックする。
 * 「コミットする」を選んだときだけ、確認のうえコミットする（実行後は既定に戻す）。
 */
export function ExecutePanel({
  conn,
  statementId,
  value,
  draft,
  ready,
  onApiError,
}: {
  conn: Connections;
  statementId: string;
  value: PValue | undefined;
  /** 編集中の mapper XML（なければ null） */
  draft: DraftParam;
  /** 入力に誤りがなく、SQL を組み立てられる状態 */
  ready: boolean;
  onApiError: (e: unknown) => void;
}) {
  const [commit, setCommit] = useState(false);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ExecuteResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const current = conn.current;

  const run = async () => {
    if (!current) return;
    if (commit && !window.confirm(`「${current.name}」でコミットします。変更は取り消せません。実行しますか？`)) return;
    setRunning(true);
    setError(null);
    try {
      setResult(await conn.api.execute(statementId, value, current.id, commit, MAX_ROWS, draft));
    } catch (e) {
      onApiError(e);
      setResult(null);
      setError(message(e));
    } finally {
      setRunning(false);
      setCommit(false);
    }
  };

  return (
    <div className="execute">
      <div className="execute-bar">
        <ConnectionPicker conn={conn} />
        <label className="check commit-check">
          <input type="checkbox" checked={commit} onChange={(e) => setCommit(e.target.checked)} />
          コミットする
        </label>
        <button type="button" className={commit ? 'run danger' : 'run'} disabled={!current || !ready || running} onClick={run}>
          {running ? '実行中…' : commit ? '実行してコミット' : '実行（ロールバック）'}
        </button>
      </div>
      {!ready && current && <p className="hint">SQL を組み立てられないため実行できません。SQL タブに出ているエラーを直してください。</p>}

      {error && <pre className="render-error">{error}</pre>}
      {result && !error && <ExecuteResult result={result} />}
    </div>
  );
}

function ExecuteResult({ result }: { result: ExecuteResponse }) {
  return (
    <div className="execute-result">
      <p className={result.committed ? 'outcome committed' : 'outcome'}>
        {result.committed ? 'コミットしました' : 'ロールバックしました'}
        <span className="hint">（{result.elapsedMillis} ms）</span>
        {result.updateCounts.length > 0 && <span>更新件数: {result.updateCounts.join(', ')}</span>}
      </p>
      {result.warnings.length > 0 && (
        <ul className="notes warn">
          {result.warnings.map((w, i) => (
            <li key={i}>{w}</li>
          ))}
        </ul>
      )}
      {result.resultSets.map((t, i) => (
        <ResultGrid key={i} table={t} />
      ))}
    </div>
  );
}

export function ResultGrid({ table }: { table: ResultTable }) {
  return (
    <div className="result-grid">
      <p className="hint">
        {table.rows.length} 行{table.truncated && `（先頭 ${table.rows.length} 行のみ表示）`}
      </p>
      <div className="grid-scroll">
        <table>
          <thead>
            <tr>
              <th className="rownum">#</th>
              {table.columns.map((c, i) => (
                <th key={i} title={table.columnTypes[i]}>
                  {c}
                  <span className="type">{table.columnTypes[i]}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {table.rows.map((row, r) => (
              <tr key={r}>
                <td className="rownum">{r + 1}</td>
                {row.map((v, c) =>
                  v === null ? (
                    <td key={c} className="null">
                      NULL
                    </td>
                  ) : (
                    <td key={c}>{v}</td>
                  ),
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ---- 接続先の設定 ----

const EMPTY: ConnectionInput = { name: '', host: 'localhost', port: 5432, database: '', user: '', params: '' };

function toInput(p: ConnectionProfile): ConnectionInput {
  return { id: p.id, name: p.name, host: p.host, port: p.port, database: p.database, user: p.user, params: p.params };
}

/** 接続先の画面: 一覧と、選んだ接続先の編集 */
export function ConnectionsScreen({ conn }: { conn: Connections }) {
  const { api, connections } = conn;
  const onChanged = conn.reload;
  const [form, setForm] = useState<ConnectionInput>({ ...EMPTY });
  // 一覧が届いたら、使っている接続先を開いておく
  const opened = useRef(false);
  useEffect(() => {
    if (opened.current || !conn.current) return;
    opened.current = true;
    setForm(toInput(conn.current));
  }, [conn.current]);
  const [password, setPassword] = useState('');
  /** パスワードを暗号化して保存する（開いているときだけ選べる） */
  const [remember, setRemember] = useState(true);
  const [status, setStatus] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const saved = connections.find((c) => c.id === form.id) ?? null;
  /** プロジェクトの設定から読んだ接続先（変えられない） */
  const fromProject = !!saved?.source;

  const edit = (p: ConnectionProfile | null) => {
    setForm(p ? toInput(p) : { ...EMPTY });
    setPassword('');
    setRemember(p ? p.passwordSaved || !p.hasPassword : true);
    setStatus(null);
  };

  const set = <K extends keyof ConnectionInput>(key: K, v: ConnectionInput[K]) => setForm((f) => ({ ...f, [key]: v }));

  /** 保存する。パスワード欄が空なら今のパスワードのまま */
  const save = async (): Promise<ConnectionProfile | null> => {
    try {
      const unlocked = !!conn.vault?.unlocked;
      const p = await api.saveConnection({ ...form, ...(password ? { password } : {}), ...(unlocked ? { remember } : {}) });
      setForm(toInput(p));
      conn.select(p.id);
      setPassword('');
      onChanged();
      return p;
    } catch (e) {
      setStatus({ ok: false, text: message(e) });
      return null;
    }
  };

  const wrap = (f: () => Promise<void>) => async () => {
    setBusy(true);
    setStatus(null);
    try {
      await f();
    } finally {
      setBusy(false);
    }
  };

  const saveOnly = wrap(async () => {
    if (await save()) setStatus({ ok: true, text: '保存しました' });
  });

  const saveAndTest = wrap(async () => {
    const p = await save();
    if (!p) return;
    try {
      const r = await api.testConnection(p.id);
      setStatus({ ok: true, text: `保存しました。接続できました（${r.server}）` });
    } catch (e) {
      setStatus({ ok: false, text: message(e) });
    }
  });

  /** 設定ファイルから読んだ接続先は、保存せずに接続テストだけをする */
  const testOnly = wrap(async () => {
    if (!form.id) return;
    try {
      const r = await api.testConnection(form.id);
      setStatus({ ok: true, text: `接続できました（${r.server}）` });
    } catch (e) {
      setStatus({ ok: false, text: message(e) });
    }
  });

  const clearPassword = wrap(async () => {
    if (!form.id) return;
    try {
      await api.saveConnection({ ...form, password: '' });
      onChanged();
      setStatus({ ok: true, text: 'パスワードを消しました' });
    } catch (e) {
      setStatus({ ok: false, text: message(e) });
    }
  });

  const remove = wrap(async () => {
    if (!form.id || !window.confirm(`接続先「${form.name}」を削除しますか？`)) return;
    try {
      await api.deleteConnection(form.id);
      onChanged();
      edit(null);
    } catch (e) {
      setStatus({ ok: false, text: message(e) });
    }
  });

  return (
    <div className="connections page">
      <header className="panel-header">
        <h2>接続先</h2>
        <span className="hint">PostgreSQL</span>
      </header>
      <VaultPanel conn={conn} />
      <div className="connections-body">
        <ul className="connection-list">
          {connections.map((c) => (
            <li key={c.id}>
              <button type="button" className={c.id === form.id ? 'selected' : undefined} onClick={() => edit(c)}>
                {c.name}
                {c.passwordSaved && (
                  <span className="badge muted saved-mark" title="パスワードを暗号化して保存しています">
                    保存済み
                  </span>
                )}
              </button>
            </li>
          ))}
          <li>
            <button type="button" className={form.id ? undefined : 'selected'} onClick={() => edit(null)}>
              ＋ 新しい接続先
            </button>
          </li>
        </ul>

        <form
          className={fromProject ? 'connection-form from-project' : 'connection-form'}
          onSubmit={(e) => {
            e.preventDefault();
            if (fromProject) testOnly();
            else saveAndTest();
          }}
        >
          {fromProject && (
            <p className="hint from-project-note">
              <code>{saved!.source}</code> から読み込み
            </p>
          )}
          {saved?.error && <p className="error-text">{saved.error}</p>}
          <fieldset className="connection-fields" disabled={fromProject}>
            <label>
              名前
              <input type="text" required value={form.name} placeholder="例: 開発 DB" onChange={(e) => set('name', e.target.value)} />
            </label>
            <div className="host-port">
              <label>
                ホスト
                <input type="text" required value={form.host} onChange={(e) => set('host', e.target.value)} />
              </label>
              <label>
                ポート
                <input type="number" required min={1} max={65535} value={form.port} onChange={(e) => set('port', Number(e.target.value))} />
              </label>
            </div>
            <label>
              データベース
              <input type="text" required value={form.database} onChange={(e) => set('database', e.target.value)} />
            </label>
            <label>
              ユーザー
              <input type="text" value={form.user} autoComplete="off" onChange={(e) => set('user', e.target.value)} />
            </label>
            {!fromProject && (
              <label>
                パスワード
                <input
                  type="password"
                  value={password}
                  autoComplete="new-password"
                  placeholder={
                    saved?.hasPassword
                      ? '入力済み（変える場合だけ入力）'
                      : saved?.passwordSaved
                        ? '保存済み（マスターパスワードで開くと使えます）'
                        : '未入力'
                  }
                  onChange={(e) => setPassword(e.target.value)}
                />
              </label>
            )}
            {!fromProject && (
              <label className="check" title={conn.vault?.unlocked ? undefined : '上でマスターパスワードを設定するか、開くと選べます'}>
                <input
                  type="checkbox"
                  checked={conn.vault?.unlocked ? remember : !!saved?.passwordSaved}
                  disabled={!conn.vault?.unlocked}
                  onChange={(e) => setRemember(e.target.checked)}
                />
                パスワードを保存する（暗号化）
              </label>
            )}
            <label>
              接続パラメータ
              <input
                type="text"
                value={form.params}
                placeholder="例: sslmode=require&stringtype=unspecified"
                onChange={(e) => set('params', e.target.value)}
              />
            </label>
          </fieldset>
          {!fromProject && (
            <p className="hint">
              保存しないパスワードは、ツールを再起動すると消えます。
              {saved?.hasPassword && (
                <button type="button" className="link" disabled={busy} onClick={clearPassword}>
                  パスワードを消す
                </button>
              )}
            </p>
          )}
          {status && <pre className={status.ok ? 'status ok' : 'status render-error'}>{status.text}</pre>}
          {fromProject ? (
            <div className="actions">
              <span className="spacer" />
              <button type="button" disabled={busy || !!saved?.error} onClick={testOnly}>
                接続テスト
              </button>
            </div>
          ) : (
            <div className="actions">
              {form.id && (
                <button type="button" disabled={busy} onClick={remove}>
                  削除
                </button>
              )}
              <span className="spacer" />
              <button type="button" disabled={busy} onClick={saveOnly}>
                保存
              </button>
              <button type="submit" disabled={busy}>
                保存して接続テスト
              </button>
            </div>
          )}
        </form>
      </div>
    </div>
  );
}
