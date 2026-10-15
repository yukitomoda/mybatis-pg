// 共通パラメータ（複数の statement で共有する値の集まり）の選択と編集
import { useEffect, useRef, useState } from 'react';
import type { Api, GlobalsResponse, GlobalSet, ParameterName } from './api';
import type { GenContext } from './GenInput';
import { ParamTree } from './ParamTree';
import type { ParamSchema, PValue } from './params';
import { EMPTY_ROOT, getAt, setAt } from './params';

/** 共通パラメータの編集では XML の項目を使わない */
const FREE_SCHEMA: ParamSchema = { name: null, usages: [], typeHints: [], properties: [], element: null };

/** 値の保存を待つ時間（入力のたびに送らない） */
const SAVE_DELAY = 400;

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

export interface Globals {
  sets: GlobalSet[];
  active: GlobalSet | null;
  /** 選んでいる共通パラメータの値（編集中のものを含む） */
  values: PValue | undefined;
  setValues: (v: PValue | undefined) => void;
  error: string | null;
  create: (copy: boolean) => void;
  rename: () => void;
  remove: () => void;
  select: (id: string | null) => void;
}

export function useGlobals(api: Api, onApiError: (e: unknown) => void): Globals {
  const [state, setState] = useState<GlobalsResponse>({ active: null, sets: [] });
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
  const run = (f: () => Promise<GlobalsResponse>) => {
    flush()
      .then(f)
      .then(
        (s) => {
          setState(s);
          setError(null);
        },
        (e) => {
          onApiError(e);
          window.alert(message(e));
        },
      );
  };

  const active = state.sets.find((s) => s.id === state.active) ?? null;

  return {
    sets: state.sets,
    active,
    values: active?.values,
    error,
    setValues: (v) => {
      if (!active) return;
      const values = v ?? EMPTY_ROOT;
      setState((s) => ({ ...s, sets: s.sets.map((set) => (set.id === active.id ? { ...set, values } : set)) }));
      if (pending.current) window.clearTimeout(pending.current.timer);
      pending.current = { id: active.id, values, timer: window.setTimeout(flush, SAVE_DELAY) };
    },
    create: (copy) => {
      const name = window.prompt(copy && active ? `「${active.name}」を複製します。新しい名前:` : '新しい共通パラメータの名前:');
      if (!name) return;
      run(async () => {
        const created = await api.createGlobal(name, copy && active ? active.id : null);
        const added = created.sets.find((s) => !state.sets.some((old) => old.id === s.id));
        return added ? api.selectGlobal(added.id) : created;
      });
    },
    rename: () => {
      if (!active) return;
      const name = window.prompt('新しい名前:', active.name);
      if (!name || name === active.name) return;
      run(() => api.renameGlobal(active.id, name));
    },
    remove: () => {
      if (!active || !window.confirm(`共通パラメータ「${active.name}」を削除しますか？`)) return;
      run(() => api.deleteGlobal(active.id));
    },
    select: (id) => run(() => api.selectGlobal(id)),
  };
}

/** 共通パラメータの選択と操作 */
export function GlobalBar({ globals }: { globals: Globals }) {
  const { sets, active } = globals;
  return (
    <div className="global-bar">
      <label>
        使う共通パラメータ
        <select value={active?.id ?? ''} onChange={(e) => globals.select(e.target.value || null)}>
          <option value="">なし</option>
          {sets.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </label>
      <span className="actions">
        <button type="button" onClick={() => globals.create(false)}>
          新規
        </button>
        <button type="button" disabled={!active} onClick={() => globals.create(true)}>
          複製
        </button>
        <button type="button" disabled={!active} onClick={globals.rename}>
          名前変更
        </button>
        <button type="button" disabled={!active} onClick={globals.remove}>
          削除
        </button>
      </span>
      {globals.error && <p className="error-text">{globals.error}</p>}
    </div>
  );
}

/** 候補の項目を足すときの初期値 */
function initialValue(n: ParameterName): PValue {
  if (n.list) return { t: 'list', items: [] };
  if (n.object) return { t: 'object', fields: [] };
  if (n.typeHints.includes('NUMBER')) return { t: 'number', v: '' };
  if (n.typeHints.includes('BOOLEAN')) return { t: 'boolean', v: false };
  return { t: 'string', v: '' };
}

const MAX_CANDIDATES = 30;

/** 共通パラメータの値の編集。全 statement で使われている項目を候補として出す */
export function GlobalEditor({ api, globals, generation, gen }: { api: Api; globals: Globals; generation: number; gen: GenContext }) {
  const [names, setNames] = useState<ParameterName[]>([]);

  useEffect(() => {
    if (generation === 0) return;
    api.parameterNames().then(
      (r) => setNames(r.names),
      () => setNames([]),
    );
  }, [api, generation]);

  const values = globals.values;
  const candidates = names.filter((n) => getAt(values, n.path.split('.')) === undefined).slice(0, MAX_CANDIDATES);

  return (
    <>
      <ParamTree schema={FREE_SCHEMA} value={values} onChange={globals.setValues} free gen={gen} />
      {candidates.length > 0 && (
        <div className="candidates">
          <p className="hint">よく使われている項目（クリックで追加。数字は使っている statement の数）</p>
          {candidates.map((n) => (
            <button
              key={n.path}
              type="button"
              className="candidate"
              title={n.usages.join(', ')}
              onClick={() => globals.setValues(setAt(values, n.path.split('.'), initialValue(n)))}
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
