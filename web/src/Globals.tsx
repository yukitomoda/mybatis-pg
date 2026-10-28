// 共通パラメータ（複数の statement で共有する値の集まり）: 状態、共通パラメータの画面、クエリ画面の共通タブ
import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import type { Api, GlobalsResponse, ParameterName } from './api';
import type { GenContext } from './GenInput';
import { DragList } from './DragList';
import type { GlobalSet, StatementSettings, StatementsSettings } from './globalModel';
import { addTo, applicable, customized, NO_SETTINGS, removeFrom } from './globalModel';
import { ParamTree } from './ParamTree';
import { SideSplitter, useSideWidth } from './Splitter';
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
  /** すべての statement に既定で入れるか */
  setDefault: (set: GlobalSet, on: boolean) => void;
  /** 既定の順番を変える */
  reorder: (sets: GlobalSet[]) => void;
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
    setDefault: (set, on) => {
      setState((s) => ({ ...s, sets: s.sets.map((x) => (x.id === set.id ? { ...x, isDefault: on } : x)) }));
      run(() => api.defaultGlobal(set.id, on));
    },
    reorder: (sets) => {
      setState((s) => ({ ...s, sets }));
      run(() => api.reorderGlobals(sets.map((x) => x.id)));
    },
    setStatement: (statementId, settings) => {
      setState((s) => ({ ...s, statements: { ...s.statements, [statementId]: settings } }));
      run(() => api.statementGlobals(statementId, settings));
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

// ---- クエリ画面: 個別の値を共通パラメータにコピー ----

/**
 * 個別の値の項目を選んで、この statement に適用されている共通パラメータの 1 つにコピーする（個別の値は残す）。
 */
export function CopyToGlobal({
  globals,
  applied,
  local,
  onClose,
}: {
  globals: Globals;
  applied: GlobalSet[];
  local: PValue;
  onClose: () => void;
}) {
  const fields = local.t === 'object' ? local.fields : [];
  // 既定のコピー先は、一番優先される（一覧の一番下の）もの
  const [targetId, setTargetId] = useState<string>(applied[applied.length - 1]?.id ?? '');
  const [checked, setChecked] = useState<Set<string>>(() => new Set(fields.map(([k]) => k)));
  const target = applied.find((s) => s.id === targetId) ?? null;
  const copy = () => {
    if (!target) return;
    let values: PValue | undefined = target.values;
    for (const [k, v] of fields) if (checked.has(k)) values = setAt(values, [k], v);
    globals.setValues(target.id, values);
    onClose();
  };
  return (
    <section className="copy-to-global">
      <header>
        <h3>共通パラメータにコピー</h3>
        <label>
          コピー先
          <select value={targetId} onChange={(e) => setTargetId(e.target.value)}>
            {applied.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </label>
      </header>
      <table>
        <tbody>
          {fields.map(([k, v]) => {
            const before = target ? getAt(target.values, [k]) : undefined;
            return (
              <tr key={k}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`${k} をコピーする`}
                    checked={checked.has(k)}
                    onChange={(e) =>
                      setChecked((c) => {
                        const next = new Set(c);
                        if (e.target.checked) next.add(k);
                        else next.delete(k);
                        return next;
                      })
                    }
                  />
                </td>
                <td className="global-name">{k}</td>
                <td className="values" title={before === undefined ? short(v) : `${short(before)} → ${short(v)}`}>
                  {before === undefined ? (
                    <>
                      {short(v)} <span className="badge muted">新しく足す</span>
                    </>
                  ) : (
                    `${short(before)} → ${short(v)}`
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="actions">
        <button type="button" className="run" disabled={!target || checked.size === 0} onClick={copy}>
          {checked.size} 項目をコピー
        </button>
        <button type="button" className="link" onClick={onClose}>
          やめる
        </button>
      </div>
    </section>
  );
}

// ---- クエリ画面: 共通タブ ----

/**
 * この statement に適用する共通パラメータを、優先順（下ほど優先）に並べる。
 * 追加・外す・ドラッグで並べ替えができる（この statement だけに効く）。
 */
export function StatementGlobals({
  globals,
  statementId,
  onSettings,
}: {
  globals: Globals;
  statementId: string;
  onSettings: (setId: string | null) => void;
}) {
  const settings = globals.statements[statementId];
  const list = applicable(globals.sets, settings);
  const rest = globals.sets.filter((s) => !list.includes(s));
  const save = (st: StatementSettings) => globals.setStatement(statementId, st);
  return (
    <section className="statement-globals">
      <DragList
        items={list}
        keyOf={(s) => s.id}
        label={(s) => s.name}
        onReorder={(next) => save({ ...(settings ?? NO_SETTINGS), order: next.map((s) => s.id) })}
        render={(s) => (
          <>
            <button type="button" className="link global-name" onClick={() => onSettings(s.id)}>
              {s.name}
            </button>
            <span className="values" title={summary(s.values)}>
              {summary(s.values)}
            </span>
            <button
              type="button"
              className="icon"
              aria-label={`${s.name} を外す`}
              title="外す"
              onClick={() => save(removeFrom(settings, s))}
            >
              ×
            </button>
          </>
        )}
      />
      <div className="statement-globals-actions">
        {rest.length > 0 && (
          <select
            aria-label="共通パラメータを追加"
            value=""
            onChange={(e) => {
              const set = rest.find((s) => s.id === e.target.value);
              if (set) save(addTo(settings, set, list));
            }}
          >
            <option value="">＋ 追加</option>
            {rest.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        )}
        {customized(settings) && (
          <button type="button" className="link" onClick={() => save(NO_SETTINGS)}>
            既定に戻す
          </button>
        )}
        <button type="button" className="link" onClick={() => onSettings(null)}>
          設定
        </button>
      </div>
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
  const side = useSideWidth('globals');
  const [selectedId, setSelectedId] = useState<string | null>(focusId);
  useEffect(() => {
    if (focusId) setSelectedId(focusId);
  }, [focusId]);
  const { sets } = globals;
  const selected = sets.find((s) => s.id === selectedId) ?? null;
  // 既定で入れるものはそれを外している statement、そうでないものはそれを追加している statement
  const changedIn = selected
    ? Object.entries(globals.statements)
        .filter(([, st]) => (selected.isDefault ? st.removed : st.added).includes(selected.id))
        .map(([id]) => id)
        .sort()
    : [];

  return (
    <div className="globals-screen" style={side.style}>
      <aside className="globals-list">
        <div className="globals-list-head">
          <h2>共通パラメータ</h2>
          <button type="button" onClick={async () => setSelectedId((await globals.create(null)) ?? selectedId)}>
            ＋ 新規
          </button>
        </div>
        <DragList
          items={sets}
          keyOf={(s) => s.id}
          label={(s) => s.name}
          selected={(s) => s.id === selected?.id}
          onPick={(s) => setSelectedId(s.id)}
          onReorder={globals.reorder}
          render={(s) => (
            <button type="button" className="global-item">
              {s.name}
            </button>
          )}
        />
        {sets.length === 0 && <p className="hint">まだありません。「新規」で作ってください。</p>}
        {globals.error && <p className="error-text">{globals.error}</p>}
      </aside>
      <SideSplitter label="共通パラメータの一覧の幅" side={side} />

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
            <label className="check global-default">
              <input type="checkbox" checked={selected.isDefault} onChange={(e) => globals.setDefault(selected, e.target.checked)} />
              すべての statement に入れる
            </label>
            <h3>値</h3>
            <GlobalValues api={api} set={selected} globals={globals} generation={generation} gen={gen} />
            {renderJson(selected.values, (v) => globals.setValues(selected.id, v))}
          </section>
          <section className="globals-places">
            <h3>{selected.isDefault ? '外している statement' : '追加している statement'}</h3>
            {changedIn.length === 0 ? (
              <p className="hint">ありません。</p>
            ) : (
              <ul className="excludes">
                {changedIn.map((id) => {
                  const st = globals.statements[id];
                  return (
                    <li key={id}>
                      <code>{id}</code>
                      <button
                        type="button"
                        className="link"
                        onClick={() =>
                          globals.setStatement(
                            id,
                            selected.isDefault ? { ...st, removed: st.removed.filter((x) => x !== selected.id) } : removeFrom(st, selected),
                          )
                        }
                      >
                        {selected.isDefault ? '入れ直す' : '外す'}
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </>
      ) : (
        <section className="globals-values">
          <p className="hint">
            {sets.length === 0 ? '共通パラメータを作ると、ここで値を設定できます。' : '左の一覧から共通パラメータを選んでください。'}
          </p>
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
function GlobalValues({
  api,
  set,
  globals,
  generation,
  gen,
}: {
  api: Api;
  set: GlobalSet;
  globals: Globals;
  generation: number;
  gen: GenContext;
}) {
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
