// 共通パラメータ（複数の statement で共有する値の集まり）: 状態、共通パラメータの画面、クエリ画面の一覧
import type { ReactNode } from 'react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Api, GlobalsResponse, ParameterName } from './api';
import type { GenContext } from './GenInput';
import { ParamTree } from './ParamTree';
import type { ParamSchema, PValue } from './params';
import { EMPTY_ROOT, getAt, setAt, toJson } from './params';
import type { GlobalSet, Match, Order, Target } from './scope';
import { ROOT, orderAt, placeLabel, related } from './scope';
import type { StatementEntry } from './tree';
import { shortId } from './tree';

/** 共通パラメータの編集では XML の項目を使わない */
const FREE_SCHEMA: ParamSchema = { name: null, usages: [], typeHints: [], properties: [], element: null };

/** 値の保存を待つ時間（入力のたびに送らない） */
const SAVE_DELAY = 400;

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// ---- 状態 ----

export interface Globals {
  sets: GlobalSet[];
  order: Order;
  error: string | null;
  /** 値を変える（少し待ってから保存する） */
  setValues: (id: string, v: PValue | undefined) => void;
  /** 作った共通パラメータの id を返す */
  create: (copyFrom: GlobalSet | null) => Promise<string | null>;
  rename: (set: GlobalSet) => void;
  remove: (set: GlobalSet) => Promise<boolean>;
  setScope: (set: GlobalSet, places: string[]) => void;
  /** statement で適用しないか */
  setExcluded: (set: GlobalSet, statementId: string, excluded: boolean) => void;
  /** 場所の適用順序（下ほど優先） */
  setOrder: (place: string, ids: string[]) => void;
}

export function useGlobals(api: Api, onApiError: (e: unknown) => void): Globals {
  const [state, setState] = useState<GlobalsResponse>({ sets: [], order: {} });
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

  // 画面にはすぐ反映し、保存は後から行う
  const patch = (id: string, change: (set: GlobalSet) => GlobalSet) => setState((s) => ({ ...s, sets: s.sets.map((x) => (x.id === id ? change(x) : x)) }));

  return {
    sets: state.sets,
    order: state.order,
    error,
    setValues: (id, v) => {
      const values = v ?? EMPTY_ROOT;
      patch(id, (x) => ({ ...x, values }));
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
    setScope: (set, places) => {
      patch(set.id, (x) => ({ ...x, scope: places }));
      run(() => api.scopeGlobal(set.id, places));
    },
    setExcluded: (set, statementId, excluded) => {
      patch(set.id, (x) => ({ ...x, excludes: excluded ? [...x.excludes.filter((i) => i !== statementId), statementId] : x.excludes.filter((i) => i !== statementId) }));
      run(() => api.excludeGlobal(set.id, statementId, excluded));
    },
    setOrder: (place, ids) => {
      setState((s) => ({ ...s, order: { ...s.order, [place]: ids } }));
      run(() => api.orderGlobal(place, ids));
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

// ---- クエリ画面: この statement がスコープに入っている共通パラメータ ----

/**
 * statement がスコープに入っている共通パラメータを、優先順位の順（下ほど優先）に並べる。
 * チェックを外すと、この statement では適用しない（ほかの statement には影響しない）。
 */
export function AppliedGlobals({ globals, target, onSettings }: { globals: Globals; target: Target; onSettings: (setId: string | null) => void }) {
  const list = related(globals.sets, globals.order, target);
  return (
    <section className="applied-globals">
      <header>
        <h3>共通パラメータ</h3>
        <button type="button" className="link" onClick={() => onSettings(null)}>
          設定
        </button>
      </header>
      {list.length === 0 ? (
        <p className="hint">この statement をスコープに含む共通パラメータはありません。</p>
      ) : (
        <table>
          <tbody>
            {list.map((m: Match) => (
              <tr key={m.set.id} className={m.excluded ? 'off' : undefined}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`${m.set.name} をこの statement で適用する`}
                    checked={!m.excluded}
                    onChange={(e) => globals.setExcluded(m.set, target.id, !e.target.checked)}
                  />
                </td>
                <td className="global-name">
                  <button type="button" className="link" onClick={() => onSettings(m.set.id)}>
                    {m.set.name}
                  </button>
                </td>
                <td className="scope-of" title={m.place.key === ROOT ? '全体' : m.place.key}>
                  {placeLabel(m.place)}
                </td>
                <td className="values" title={summary(m.set.values)}>
                  {m.excluded ? 'この statement では適用しない' : summary(m.set.values)}
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
  statements,
  generation,
  gen,
  renderJson,
  focusId,
}: {
  api: Api;
  globals: Globals;
  statements: StatementEntry[];
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

  return (
    <div className="globals-screen">
      <aside className="globals-list">
        <div className="globals-list-head">
          <h2>共通パラメータ</h2>
          <button type="button" onClick={async () => setSelectedId((await globals.create(null)) ?? selectedId)}>
            ＋ 新規
          </button>
        </div>
        <ScopeGroups globals={globals} statements={statements} selectedId={selected?.id ?? null} onSelect={setSelectedId} />
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
            <h3>スコープ</h3>
            <ScopeEditor set={selected} statements={statements} onChange={(places) => globals.setScope(selected, places)} />
            {selected.excludes.length > 0 && (
              <>
                <h3>適用しない statement</h3>
                <ul className="excludes">
                  {selected.excludes.map((id) => (
                    <li key={id}>
                      <code>{id}</code>
                      <button type="button" className="link" onClick={() => globals.setExcluded(selected, id, false)}>
                        適用に戻す
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>
        </>
      ) : (
        <section className="globals-values">
          <p className="hint">{sets.length === 0 ? '共通パラメータを作ると、ここで値とスコープを設定できます。' : '左の一覧から共通パラメータを選んでください。'}</p>
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

// ---- 場所のツリー ----

/** 場所のツリーの節 */
export interface PlaceNode {
  key: string;
  label: string;
  kind: 'all' | 'dir' | 'file' | 'statement';
  children: PlaceNode[];
}

/**
 * statement の一覧から、場所のツリーを作る（全体を一番上に置く）。
 * 子がフォルダ 1 つだけのフォルダは子とまとめて 1 行にするが、keep にある場所（スコープにしている場所）はまとめずに残す。
 */
export function placeTree(statements: StatementEntry[], keep: Set<string>): PlaceNode {
  interface Raw {
    key: string;
    label: string;
    kind: PlaceNode['kind'];
    children: Map<string, Raw>;
  }
  const root: Raw = { key: ROOT, label: '全体', kind: 'all', children: new Map() };
  const child = (parent: Raw, key: string, label: string, kind: PlaceNode['kind']) => {
    let c = parent.children.get(key);
    if (!c) {
      c = { key, label, kind, children: new Map() };
      parent.children.set(key, c);
    }
    return c;
  };
  for (const st of statements) {
    const parts = st.file.split('/');
    let node = root;
    let prefix = '';
    for (const d of parts.slice(0, -1)) {
      prefix += d + '/';
      node = child(node, prefix, d, 'dir');
    }
    const file = child(node, st.file, parts[parts.length - 1], 'file');
    child(file, st.id, shortId(st.id), 'statement');
  }
  const ORDER = { all: 0, dir: 0, file: 1, statement: 2 };
  const finish = (r: Raw): PlaceNode => {
    const kids = [...r.children.values()].sort((a, b) => ORDER[a.kind] - ORDER[b.kind] || a.label.localeCompare(b.label)).map(finish);
    // スコープにしていないフォルダで、子もフォルダ 1 つなら、まとめる（src/ main/ → src/main/）
    if (r.kind === 'dir' && !keep.has(r.key) && kids.length === 1 && kids[0].kind === 'dir') {
      return { ...kids[0], label: `${r.label}/${kids[0].label}` };
    }
    return { key: r.key, label: r.label, kind: r.kind, children: kids };
  };
  return finish(root);
}

/** 場所の表示名 */
function nodeLabel(n: PlaceNode): string {
  return n.kind === 'dir' ? `${n.label}/` : n.label;
}

/** 今の mapper にない場所（消えたり名前が変わったりしたフォルダ・ファイル・statement） */
function missingPlaces(places: string[], tree: PlaceNode, statements: StatementEntry[]): string[] {
  const known = new Set<string>();
  const walk = (n: PlaceNode) => {
    known.add(n.key);
    n.children.forEach(walk);
  };
  walk(tree);
  return places.filter((k) => !known.has(k) && !(k.endsWith('/') && statements.some((s) => s.file.startsWith(k))));
}

/**
 * 共通パラメータの一覧: スコープの場所ごとに並べる（全体を一番外に、狭いスコープほど内側に）。
 * 同じ場所に複数あるときは、その場所の適用順序で並べ、↑↓ で入れ替える（下ほど優先）。
 */
function ScopeGroups({
  globals,
  statements,
  selectedId,
  onSelect,
}: {
  globals: Globals;
  statements: StatementEntry[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  const { sets, order } = globals;
  const used = useMemo(() => new Set(sets.flatMap((s) => s.scope)), [sets]);
  const tree = useMemo(() => placeTree(statements, used), [statements, used]);

  const has = (n: PlaceNode): boolean => used.has(n.key) || n.children.some(has);

  const group = (key: string) => {
    const list = orderAt(sets, order, key);
    if (list.length === 0) return null;
    const move = (i: number, d: number) => {
      const ids = list.map((s) => s.id);
      [ids[i], ids[i + d]] = [ids[i + d], ids[i]];
      globals.setOrder(key, ids);
    };
    return (
      <ol className="scope-sets">
        {list.map((s, i) => (
          <li key={s.id} className={s.id === selectedId ? 'selected' : undefined}>
            <button type="button" className="global-item" onClick={() => onSelect(s.id)}>
              {s.name}
            </button>
            {list.length > 1 && (
              <span className="global-move">
                <button type="button" className="icon" aria-label={`${s.name} を上へ`} disabled={i === 0} onClick={() => move(i, -1)}>
                  ↑
                </button>
                <button type="button" className="icon" aria-label={`${s.name} を下へ`} disabled={i === list.length - 1} onClick={() => move(i, 1)}>
                  ↓
                </button>
              </span>
            )}
          </li>
        ))}
      </ol>
    );
  };

  const render = (n: PlaceNode): ReactNode => {
    if (!has(n)) return null;
    return (
      <li key={n.key} className={`scope-node ${n.kind}`}>
        <div className="scope-label" title={n.key === ROOT ? 'すべての statement' : n.key}>
          {nodeLabel(n)}
        </div>
        {group(n.key)}
        {n.children.some(has) && <ul>{n.children.map(render)}</ul>}
      </li>
    );
  };

  const missing = missingPlaces([...used], tree, statements);
  const noScope = sets.filter((s) => s.scope.length === 0);
  return (
    <div className="scope-groups">
      {sets.length === 0 ? (
        <p className="hint">まだありません。「新規」で作ってください。</p>
      ) : (
        <ul>
          {render(tree)}
          {missing.map((k) => (
            <li key={k} className="scope-node missing">
              <div className="scope-label" title="今の mapper にない場所">
                {k}
              </div>
              {group(k)}
            </li>
          ))}
          {noScope.length > 0 && (
            <li className="scope-node none">
              <div className="scope-label">スコープなし</div>
              <ol className="scope-sets">
                {noScope.map((s) => (
                  <li key={s.id} className={s.id === selectedId ? 'selected' : undefined}>
                    <button type="button" className="global-item" onClick={() => onSelect(s.id)}>
                      {s.name}
                    </button>
                  </li>
                ))}
              </ol>
            </li>
          )}
        </ul>
      )}
    </div>
  );
}

/** スコープの編集: 場所のツリーで、対象とする場所に印を付ける */
function ScopeEditor({ set, statements, onChange }: { set: GlobalSet; statements: StatementEntry[]; onChange: (places: string[]) => void }) {
  const chosen = useMemo(() => new Set(set.scope), [set.scope]);
  const tree = useMemo(() => placeTree(statements, chosen), [statements, chosen]);
  const [toggled, setToggled] = useState<Set<string>>(new Set());
  const toggle = (key: string) => onChange(chosen.has(key) ? set.scope.filter((p) => p !== key) : [...set.scope, key]);
  const contains = (n: PlaceNode): boolean => n.children.some((c) => chosen.has(c.key) || contains(c));

  // covered: 外側の場所がスコープに入っていて、すでに対象になっている
  const render = (n: PlaceNode, depth: number, covered: boolean): ReactNode => {
    const on = chosen.has(n.key);
    const collapsible = n.kind === 'dir' || n.kind === 'file';
    // フォルダは最初から開く。ファイルは、中の statement をスコープにしていれば最初から開く
    const defaultOpen = n.kind !== 'file' || contains(n);
    const expanded = collapsible ? defaultOpen !== toggled.has(n.key) : true;
    return (
      <li key={n.key}>
        <div className={`place-row ${n.kind}${on ? ' has-rule' : ''}`} style={{ paddingLeft: `${depth * 1.1 + 0.3}rem` }}>
          {collapsible ? (
            <button
              type="button"
              className="twisty"
              aria-label={expanded ? '閉じる' : '開く'}
              onClick={() =>
                setToggled((prev) => {
                  const next = new Set(prev);
                  if (next.has(n.key)) next.delete(n.key);
                  else next.add(n.key);
                  return next;
                })
              }
            >
              {expanded ? '▾' : '▸'}
            </button>
          ) : (
            <span className="twisty" />
          )}
          <label className={covered && !on ? 'check covered' : 'check'} title={n.key === ROOT ? 'すべての statement' : n.key}>
            <input type="checkbox" checked={on || covered} disabled={covered && !on} onChange={() => toggle(n.key)} />
            <span className="place-label">{nodeLabel(n)}</span>
          </label>
        </div>
        {expanded && n.children.length > 0 && <ul>{n.children.map((c) => render(c, depth + 1, covered || on))}</ul>}
      </li>
    );
  };

  const missing = missingPlaces(set.scope, tree, statements);
  return (
    <div className="place-tree">
      <ul>{render(tree, 0, false)}</ul>
      {set.scope.length === 0 && <p className="hint">スコープがないため、どの statement にも適用されません。</p>}
      {missing.length > 0 && (
        <div className="place-missing">
          <p className="hint">今の mapper にない場所:</p>
          <ul>
            {missing.map((k) => (
              <li key={k}>
                <code>{k}</code>
                <button type="button" className="link" onClick={() => onChange(set.scope.filter((p) => p !== k))}>
                  外す
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
