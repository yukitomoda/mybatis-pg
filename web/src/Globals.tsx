// 共通パラメータ（すべての statement に適用する値の集まり）: 状態、共通パラメータの画面、クエリ画面の一覧
import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import type { Api, GlobalsResponse, ParameterName } from './api';
import type { GenContext } from './GenInput';
import type { GlobalSet, StatementSettings, StatementsSettings } from './globalModel';
import { entries } from './globalModel';
import { ParamTree } from './ParamTree';
import type { ParamSchema, PValue } from './params';
import { EMPTY_ROOT, getAt, setAt, toJson } from './params';

/** 共通パラメータの編集では XML の項目を使わない */
const FREE_SCHEMA: ParamSchema = { name: null, usages: [], typeHints: [], properties: [], element: null };

/** 値の保存を待つ時間（入力のたびに送らない） */
const SAVE_DELAY = 400;

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// ---- 状態 ----

export interface Globals {
  /** 一覧の順が既定の順番（下ほど優先） */
  sets: GlobalSet[];
  statements: StatementsSettings;
  error: string | null;
  /** 値を変える（少し待ってから保存する） */
  setValues: (id: string, v: PValue | undefined) => void;
  /** 作った共通パラメータの id を返す */
  create: (copyFrom: GlobalSet | null) => Promise<string | null>;
  rename: (set: GlobalSet) => void;
  remove: (set: GlobalSet) => Promise<boolean>;
  /** 既定の順番を変える */
  move: (set: GlobalSet, offset: number) => void;
  /** statement ごとの設定を変える（順番が undefined なら既定の順番） */
  setStatement: (statementId: string, settings: StatementSettings) => void;
}

export function useGlobals(api: Api, onApiError: (e: unknown) => void): Globals {
  const [state, setState] = useState<GlobalsResponse>({ sets: [], statements: {} });
  const [error, setError] = useState<string | null>(null);
  const pending = useRef<{ id: string; values: PValue; timer: number } | null>(null);

  const flush = () => {
    const p = pending.current;
    if (!p) return Promise.resolve();
    window.clearTimeout(p.timer);
    pending.current = null;
    return api.saveGlobalValues(p.id, p.values).then(
      () => setError(null),
      (e) => {
        onApiError(e);
        setError('共通パラメータを保存できません: ' + message(e));
      },
    );
  };

  useEffect(() => {
    api.globals().then(setState, onApiError);
    return () => {
      flush();
    };
  }, [api]);

  // 名前の変更などの前に、編集中の値を保存しておく（応答の内容で上書きされないように）
  const run = (f: () => Promise<GlobalsResponse>) =>
    flush()
      .then(f)
      .then(
        (s) => {
          setState(s);
          setError(null);
          return s;
        },
        (e) => {
          onApiError(e);
          window.alert(message(e));
          return null;
        },
      );

  return {
    sets: state.sets,
    statements: state.statements,
    error,
    setValues: (id, v) => {
      const values = v ?? EMPTY_ROOT;
      // 画面にはすぐ反映し、保存は少し待ってから行う
      setState((s) => ({ ...s, sets: s.sets.map((x) => (x.id === id ? { ...x, values } : x)) }));
      if (pending.current && pending.current.id !== id) flush();
      if (pending.current) window.clearTimeout(pending.current.timer);
      pending.current = { id, values, timer: window.setTimeout(flush, SAVE_DELAY) };
    },
    create: async (copyFrom) => {
      const name = window.prompt(copyFrom ? `「${copyFrom.name}」を複製します。新しい名前:` : '新しい共通パラメータの名前:');
      if (!name) return null;
      const before = new Set(state.sets.map((s) => s.id));
      const s = await run(() => api.createGlobal(name, copyFrom ? copyFrom.id : null));
      return s?.sets.find((x) => !before.has(x.id))?.id ?? null;
    },
    rename: (set) => {
      const name = window.prompt('新しい名前:', set.name);
      if (!name || name === set.name) return;
      run(() => api.renameGlobal(set.id, name));
    },
    remove: async (set) => {
      if (!window.confirm(`共通パラメータ「${set.name}」を削除しますか？`)) return false;
      return (await run(() => api.deleteGlobal(set.id))) !== null;
    },
    move: (set, offset) => {
      run(() => api.moveGlobal(set.id, offset));
    },
    setStatement: (statementId, settings) => {
      setState((s) => ({ ...s, statements: { ...s.statements, [statementId]: settings } }));
      run(() => api.statementGlobals(statementId, settings.disabled, settings.order ?? null));
    },
  };
}

/** 値を短く表す（型付きの値は中の文字だけにする） */
function short(v: PValue): string {
  if (v.t === 'timestamp' || v.t === 'date' || v.t === 'pg') return v.v;
  return toJson(v, '');
}

/** 値の要約（tenantId = 1, limit = 20） */
function summary(values: PValue): string {
  if (values.t !== 'object') return short(values);
  return values.fields.map(([k, v]) => `${k} = ${short(v)}`).join(', ');
}

// ---- クエリ画面: この statement での共通パラメータ ----

/**
 * 共通パラメータを、この statement での順番（下ほど優先）に並べる。
 * チェックで有効・無効を切り替え、↑↓ で順番を入れ替える（この statement だけに効く）。
 */
export function AppliedGlobals({ globals, statementId, onSettings }: { globals: Globals; statementId: string; onSettings: (setId: string | null) => void }) {
  const settings = globals.statements[statementId];
  const list = entries(globals.sets, settings);
  const disabled = settings?.disabled ?? [];
  const toggle = (id: string, on: boolean) =>
    globals.setStatement(statementId, { disabled: on ? disabled.filter((x) => x !== id) : [...disabled, id], order: settings?.order });
  const move = (i: number, d: number) => {
    const ids = list.map((e) => e.set.id);
    [ids[i], ids[i + d]] = [ids[i + d], ids[i]];
    globals.setStatement(statementId, { disabled, order: ids });
  };
  return (
    <section className="applied-globals">
      <header>
        <h3>共通パラメータ</h3>
        <span>
          {settings?.order && (
            <button type="button" className="link" onClick={() => globals.setStatement(statementId, { disabled, order: undefined })}>
              既定の順番に戻す
            </button>
          )}
          <button type="button" className="link" onClick={() => onSettings(null)}>
            設定
          </button>
        </span>
      </header>
      {list.length === 0 ? (
        <p className="hint">共通パラメータはまだありません。「設定」から作れます。</p>
      ) : (
        <table>
          <tbody>
            {list.map((e, i) => (
              <tr key={e.set.id} className={e.disabled ? 'off' : undefined}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`${e.set.name} をこの statement で使う`}
                    checked={!e.disabled}
                    onChange={(ev) => toggle(e.set.id, ev.target.checked)}
                  />
                </td>
                <td className="global-name">
                  <button type="button" className="link" onClick={() => onSettings(e.set.id)}>
                    {e.set.name}
                  </button>
                </td>
                <td className="values" title={summary(e.set.values)}>
                  {e.disabled ? '無効' : summary(e.set.values)}
                </td>
                <td className="global-move">
                  <button type="button" className="icon" aria-label={`${e.set.name} を上へ`} disabled={i === 0} onClick={() => move(i, -1)}>
                    ↑
                  </button>
                  <button type="button" className="icon" aria-label={`${e.set.name} を下へ`} disabled={i === list.length - 1} onClick={() => move(i, 1)}>
                    ↓
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// ---- 共通パラメータの画面 ----

export function GlobalsScreen({
  api,
  globals,
  generation,
  gen,
  renderJson,
  focusId,
}: {
  api: Api;
  globals: Globals;
  generation: number;
  gen: GenContext;
  /** 値の JSON 欄（App の JsonPanel を使う） */
  renderJson: (value: PValue | undefined, onApply: (v: PValue | undefined) => void) => ReactNode;
  /** 選んでおく共通パラメータ（クエリ画面から名前で開いたとき） */
  focusId: string | null;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(focusId);
  useEffect(() => {
    if (focusId) setSelectedId(focusId);
  }, [focusId]);
  const { sets } = globals;
  const selected = sets.find((s) => s.id === selectedId) ?? null;
  // 選んだ共通パラメータを無効にしている statement
  const disabledIn = selected
    ? Object.entries(globals.statements)
        .filter(([, st]) => st.disabled.includes(selected.id))
        .map(([id]) => id)
        .sort()
    : [];

  return (
    <div className="globals-screen">
      <aside className="globals-list">
        <div className="globals-list-head">
          <h2>共通パラメータ</h2>
          <button type="button" onClick={async () => setSelectedId((await globals.create(null)) ?? selectedId)}>
            ＋ 新規
          </button>
        </div>
        <ol>
          {sets.map((s, i) => (
            <li key={s.id} className={s.id === selected?.id ? 'selected' : undefined}>
              <button type="button" className="global-item" onClick={() => setSelectedId(s.id)}>
                {s.name}
              </button>
              <span className="global-move">
                <button type="button" className="icon" aria-label={`${s.name} を上へ`} disabled={i === 0} onClick={() => globals.move(s, -1)}>
                  ↑
                </button>
                <button type="button" className="icon" aria-label={`${s.name} を下へ`} disabled={i === sets.length - 1} onClick={() => globals.move(s, 1)}>
                  ↓
                </button>
              </span>
            </li>
          ))}
          {sets.length === 0 && <li className="hint">まだありません。「新規」で作ってください。</li>}
        </ol>
        {globals.error && <p className="error-text">{globals.error}</p>}
      </aside>

      {selected ? (
        <>
          <section className="globals-values">
            <header className="globals-head">
              <h2>{selected.name}</h2>
              <span className="actions">
                <button type="button" onClick={() => globals.rename(selected)}>
                  名前変更
                </button>
                <button type="button" onClick={async () => setSelectedId((await globals.create(selected)) ?? selectedId)}>
                  複製
                </button>
                <button
                  type="button"
                  onClick={async () => {
                    if (await globals.remove(selected)) setSelectedId(null);
                  }}
                >
                  削除
                </button>
              </span>
            </header>
            <h3>値</h3>
            <GlobalValues api={api} set={selected} globals={globals} generation={generation} gen={gen} />
            {renderJson(selected.values, (v) => globals.setValues(selected.id, v))}
          </section>
          <section className="globals-places">
            <h3>無効にしている statement</h3>
            {disabledIn.length === 0 ? (
              <p className="hint">すべての statement で有効です。</p>
            ) : (
              <ul className="excludes">
                {disabledIn.map((id) => (
                  <li key={id}>
                    <code>{id}</code>
                    <button
                      type="button"
                      className="link"
                      onClick={() => {
                        const st = globals.statements[id];
                        globals.setStatement(id, { disabled: st.disabled.filter((x) => x !== selected.id), order: st.order });
                      }}
                    >
                      有効に戻す
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      ) : (
        <section className="globals-values">
          <p className="hint">{sets.length === 0 ? '共通パラメータを作ると、ここで値を設定できます。' : '左の一覧から共通パラメータを選んでください。'}</p>
        </section>
      )}
    </div>
  );
}

/** 候補の項目を足すときの初期値 */
function initialValue(n: ParameterName): PValue {
  if (n.list) return { t: 'list', items: [] };
  if (n.object) return { t: 'object', fields: [] };
  if (n.typeHints.includes('NUMBER')) return { t: 'number', v: '' };
  if (n.typeHints.includes('BOOLEAN')) return { t: 'boolean', v: false };
  if (n.typeHints.includes('TIMESTAMP')) return { t: 'timestamp', v: '' };
  if (n.typeHints.includes('DATE')) return { t: 'date', v: '' };
  return { t: 'string', v: '' };
}

const MAX_CANDIDATES = 30;

/** 共通パラメータの値の編集。全 statement で使われている項目を候補として出す */
function GlobalValues({ api, set, globals, generation, gen }: { api: Api; set: GlobalSet; globals: Globals; generation: number; gen: GenContext }) {
  const [names, setNames] = useState<ParameterName[]>([]);

  useEffect(() => {
    if (generation === 0) return;
    api.parameterNames().then(
      (r) => setNames(r.names),
      () => setNames([]),
    );
  }, [api, generation]);

  const values = set.values;
  const candidates = names.filter((n) => getAt(values, n.path.split('.')) === undefined).slice(0, MAX_CANDIDATES);

  return (
    <>
      <ParamTree schema={FREE_SCHEMA} value={values} onChange={(v) => globals.setValues(set.id, v)} free gen={gen} />
      {candidates.length > 0 && (
        <div className="candidates">
          <p className="hint">よく使われている項目（クリックで追加。数字は使っている statement の数）</p>
          {candidates.map((n) => (
            <button
              key={n.path}
              type="button"
              className="candidate"
              title={n.usages.join(', ')}
              onClick={() => globals.setValues(set.id, setAt(values, n.path.split('.'), initialValue(n)))}
            >
              {n.path}
              <span className="count">{n.count}</span>
            </button>
          ))}
        </div>
      )}
    </>
  );
}
