// 共通パラメータ（複数の statement で共有する値の集まり）: 一覧・オンとオフ・範囲・値の編集
import type { ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import type { Api, GlobalsResponse, ParameterName } from './api';
import type { GenContext } from './GenInput';
import { ParamTree } from './ParamTree';
import type { ParamSchema, PValue } from './params';
import { EMPTY_ROOT, getAt, setAt } from './params';
import type { GlobalSet, Scope, Target } from './scope';
import { specificity } from './scope';
import type { StatementEntry, TreeNode } from './tree';
import { buildTree, shortId } from './tree';

/** 共通パラメータの編集では XML の項目を使わない */
const FREE_SCHEMA: ParamSchema = { name: null, usages: [], typeHints: [], properties: [], element: null };

/** 値の保存を待つ時間（入力のたびに送らない） */
const SAVE_DELAY = 400;

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

export interface Globals {
  sets: GlobalSet[];
  error: string | null;
  /** 値を変える（少し待ってから保存する） */
  setValues: (id: string, v: PValue | undefined) => void;
  /** 作った共通パラメータの id を返す */
  create: (copyFrom: GlobalSet | null) => Promise<string | null>;
  rename: (set: GlobalSet) => void;
  remove: (set: GlobalSet) => void;
  setEnabled: (set: GlobalSet, enabled: boolean) => void;
  setScope: (set: GlobalSet, scope: Scope) => void;
  move: (set: GlobalSet, offset: number) => void;
}

export function useGlobals(api: Api, onApiError: (e: unknown) => void): Globals {
  const [state, setState] = useState<GlobalsResponse>({ sets: [] });
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
  const patch = (id: string, change: Partial<GlobalSet>) =>
    setState((s) => ({ sets: s.sets.map((x) => (x.id === id ? { ...x, ...change } : x)) }));

  return {
    sets: state.sets,
    error,
    setValues: (id, v) => {
      const values = v ?? EMPTY_ROOT;
      patch(id, { values });
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
    remove: (set) => {
      if (!window.confirm(`共通パラメータ「${set.name}」を削除しますか？`)) return;
      run(() => api.deleteGlobal(set.id));
    },
    setEnabled: (set, enabled) => {
      patch(set.id, { enabled });
      run(() => api.enableGlobal(set.id, enabled));
    },
    setScope: (set, scope) => {
      patch(set.id, { scope });
      run(() => api.scopeGlobal(set.id, scope));
    },
    move: (set, offset) => {
      run(() => api.moveGlobal(set.id, offset));
    },
  };
}

/** 共通タブ: 一覧と、選んだ共通パラメータの値と範囲の編集 */
export function GlobalPanel({
  api,
  globals,
  statements,
  target,
  generation,
  gen,
  renderJson,
}: {
  api: Api;
  globals: Globals;
  statements: StatementEntry[];
  /** 今表示している statement。一覧にはこれに関わる共通パラメータだけを出す（null ならすべて） */
  target: Target | null;
  generation: number;
  gen: GenContext;
  /** 値の JSON 欄（App の JsonPanel を使う） */
  renderJson: (value: PValue | undefined, onApply: (v: PValue | undefined) => void) => ReactNode;
}) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [showAll, setShowAll] = useState(false);
  const { sets } = globals;
  // 今の statement に範囲が当たるもの（オフのものも、オンにすれば効くので出す）
  const relevant = target ? sets.filter((s) => specificity(s.scope, target) !== null) : sets;
  const listed = showAll || !target ? sets : relevant;
  // 一覧で選ぶまでは、何も編集しない（意図しない共通パラメータを変えないように）
  const editing = sets.find((s) => s.id === editingId) ?? null;

  return (
    <div className="global-panel">
      {target && (
        <div className="global-filter">
          <span>
            {showAll ? 'すべての共通パラメータ' : <><code>{shortId(target.id)}</code> に関わる共通パラメータ</>}
          </span>
          <label className="check">
            <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} />
            すべて表示（{sets.length}）
          </label>
        </div>
      )}
      <ScopedList sets={listed} allSets={sets} statements={statements} editingId={editing?.id ?? null} onEdit={setEditingId} globals={globals} />
      {editing && !listed.includes(editing) && (
        <p className="hint">編集中の「{editing.name}」は、{target ? shortId(target.id) : ''} には効いていません（範囲の外です）。</p>
      )}
      <div className="global-actions">
        <button type="button" onClick={async () => setEditingId((await globals.create(null)) ?? editingId)}>
          新規
        </button>
        <button type="button" disabled={!editing} onClick={async () => editing && setEditingId((await globals.create(editing)) ?? editingId)}>
          複製
        </button>
        <button type="button" disabled={!editing} onClick={() => editing && globals.rename(editing)}>
          名前変更
        </button>
        <button type="button" disabled={!editing} onClick={() => editing && globals.remove(editing)}>
          削除
        </button>
      </div>
      {globals.error && <p className="error-text">{globals.error}</p>}

      {!editing && sets.length > 0 && <p className="hint">値や範囲を編集するには、一覧から共通パラメータを選んでください。</p>}
      {editing && (
        <div className="global-editor">
          <h3>
            「{editing.name}」の値
            {!editing.enabled && <span className="badge muted">オフ</span>}
          </h3>
          <GlobalValues api={api} set={editing} globals={globals} generation={generation} gen={gen} />
          {renderJson(editing.values, (v) => globals.setValues(editing.id, v))}
          <h3>「{editing.name}」を適用する範囲</h3>
          <ScopeEditor scope={editing.scope} statements={statements} onChange={(scope) => globals.setScope(editing, scope)} />
        </div>
      )}
    </div>
  );
}

/** 範囲のツリーの節（全体・フォルダ・ファイル・statement）と、そこに置く共通パラメータ */
interface ScopeNode {
  key: string;
  label: string;
  kind: 'all' | 'dir' | 'file' | 'statement' | 'missing' | 'none';
  sets: GlobalSet[];
  children: ScopeNode[];
}

/**
 * 共通パラメータを、範囲の位置に置いたツリーにする。全体を一番外に置き、狭い範囲ほど内側にする。
 * 範囲を複数持つものは、それぞれの位置に置く。共通パラメータのない階層は、子が 1 つならまとめて 1 行にする。
 */
export function scopeTree(sets: GlobalSet[], statements: StatementEntry[]): ScopeNode[] {
  interface Raw {
    key: string;
    label: string;
    kind: ScopeNode['kind'];
    sets: GlobalSet[];
    children: Map<string, Raw>;
  }
  const root: Raw = { key: '*', label: '全体', kind: 'all', sets: sets.filter((s) => s.scope.all), children: new Map() };
  const missing: Raw[] = [];
  const child = (parent: Raw, key: string, label: string, kind: ScopeNode['kind']) => {
    let c = parent.children.get(key);
    if (!c) {
      c = { key, label, kind, sets: [], children: new Map() };
      parent.children.set(key, c);
    }
    return c;
  };
  /** ファイルのパス（または末尾が / のフォルダ）までの節を作る */
  const pathNode = (path: string): Raw => {
    const parts = path.split('/');
    const isDir = path.endsWith('/');
    let node = root;
    let prefix = '';
    const dirs = isDir ? parts.slice(0, -1) : parts.slice(0, -1);
    for (const d of dirs) {
      prefix += d + '/';
      node = child(node, prefix, d, 'dir');
    }
    return isDir ? node : child(node, path, parts[parts.length - 1], 'file');
  };
  const items = [...new Set(sets.flatMap((s) => (s.scope.all ? [] : s.scope.items)))];
  for (const item of items) {
    const owners = sets.filter((s) => !s.scope.all && s.scope.items.includes(item));
    const st = statements.find((x) => x.id === item);
    let node: Raw | null = null;
    if (st) node = child(pathNode(st.file), st.id, shortId(st.id), 'statement');
    else if (statements.some((x) => x.file === item)) node = pathNode(item);
    else if (item.endsWith('/') && statements.some((x) => x.file.startsWith(item))) node = pathNode(item);
    if (node) node.sets.push(...owners);
    else missing.push({ key: item, label: item, kind: 'missing', sets: owners, children: new Map() });
  }

  const ORDER: Record<string, number> = { dir: 0, file: 1, statement: 2 };
  const finish = (r: Raw): ScopeNode => {
    const kids = [...r.children.values()].sort((a, b) => ORDER[a.kind] - ORDER[b.kind] || a.label.localeCompare(b.label)).map(finish);
    // 共通パラメータのないフォルダで子が 1 つなら、子とまとめる（shop/ と order/ → shop/order/）
    if (r.kind === 'dir' && r.sets.length === 0 && kids.length === 1) {
      const only = kids[0];
      return { ...only, label: `${r.label}/${only.label}` };
    }
    return { key: r.key, label: r.kind === 'dir' ? `${r.label}/` : r.label, kind: r.kind, sets: r.sets, children: kids };
  };
  const top = finish(root);
  const out: ScopeNode[] = [top, ...missing.map(finish)];
  const nowhere = sets.filter((s) => !s.scope.all && s.scope.items.length === 0);
  if (nowhere.length > 0) out.push({ key: '-', label: '適用する範囲なし', kind: 'none', sets: nowhere, children: [] });
  return out;
}

const SCOPE_ICONS: Record<ScopeNode['kind'], string> = { all: '◎', dir: '▤', file: '▢', statement: '·', missing: '?', none: '–' };

/** 共通パラメータの一覧: 範囲のツリーの中に置く（内側・下にあるほど優先） */
function ScopedList({
  sets,
  allSets,
  statements,
  editingId,
  onEdit,
  globals,
}: {
  /** 表示するもの */
  sets: GlobalSet[];
  /** すべて（並び替えの位置を決めるのに使う） */
  allSets: GlobalSet[];
  statements: StatementEntry[];
  editingId: string | null;
  onEdit: (id: string) => void;
  globals: Globals;
}) {
  const tree = scopeTree(sets, statements);
  const render = (nodes: ScopeNode[]) => (
    <ul>
      {nodes.map((n) => (
        <li key={n.key} className={`scope-node ${n.kind}`}>
          <div className="scope-label" title={n.kind === 'missing' ? '今の mapper にない範囲' : n.key === '*' ? 'すべての statement' : n.key}>
            <span className="scope-icon">{SCOPE_ICONS[n.kind]}</span>
            {n.label}
          </div>
          {n.sets.length > 0 && (
            <ol className="scope-sets">
              {n.sets.map((s) => {
                // 同じ位置で順番を入れ替えられる（下ほど優先）
                const siblings = n.sets;
                const i = siblings.indexOf(s);
                return (
                  <li
                    key={s.id}
                    className={`${s.id === editingId ? 'selected' : ''}${s.enabled ? '' : ' off'}`}
                    onClick={() => onEdit(s.id)}
                  >
                    <input
                      type="checkbox"
                      aria-label={`${s.name} を使う`}
                      checked={s.enabled}
                      onClick={(e) => e.stopPropagation()}
                      onChange={(e) => globals.setEnabled(s, e.target.checked)}
                    />
                    <span className="global-name">{s.name}</span>
                    {siblings.length > 1 && (
                      <span className="global-move">
                        <button
                          type="button"
                          className="icon"
                          aria-label={`${s.name} を上へ`}
                          disabled={i === 0}
                          onClick={(e) => {
                            e.stopPropagation();
                            moveWithin(globals, allSets, siblings, s, -1);
                          }}
                        >
                          ↑
                        </button>
                        <button
                          type="button"
                          className="icon"
                          aria-label={`${s.name} を下へ`}
                          disabled={i === siblings.length - 1}
                          onClick={(e) => {
                            e.stopPropagation();
                            moveWithin(globals, allSets, siblings, s, 1);
                          }}
                        >
                          ↓
                        </button>
                      </span>
                    )}
                  </li>
                );
              })}
            </ol>
          )}
          {n.children.length > 0 && render(n.children)}
        </li>
      ))}
    </ul>
  );
  return (
    <div className="scoped-list">
      {sets.length === 0 ? (
        <p className="hint">{allSets.length === 0 ? '共通パラメータはまだありません。「新規」で作ってください。' : 'この statement に関わる共通パラメータはありません。'}</p>
      ) : (
        render(tree)
      )}
    </div>
  );
}

/** 同じ位置に並ぶもの（siblings）の中で、隣と入れ替える。全体の並びでは、その隣の位置まで動かす */
function moveWithin(globals: Globals, all: GlobalSet[], siblings: GlobalSet[], set: GlobalSet, direction: number) {
  const neighbor = siblings[siblings.indexOf(set) + direction];
  if (!neighbor) return;
  globals.move(set, all.indexOf(neighbor) - all.indexOf(set));
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

/** 範囲の編集: 全体か、ツリーで選んだフォルダ・ファイル・statement */
function ScopeEditor({ scope, statements, onChange }: { scope: Scope; statements: StatementEntry[]; onChange: (scope: Scope) => void }) {
  const tree = buildTree(statements);
  const chosen = new Set(scope.items);
  const toggle = (item: string) => {
    const items = chosen.has(item) ? scope.items.filter((x) => x !== item) : [...scope.items, item];
    onChange({ all: false, items });
  };

  // covered: 上の階層が選ばれていて、すでに範囲に含まれている
  const render = (nodes: TreeNode[], covered: boolean) => (
    <ul>
      {nodes.map((n) => {
        const on = chosen.has(n.key);
        return (
          <li key={n.key}>
            <label className={covered ? 'check covered' : 'check'}>
              <input type="checkbox" checked={on || covered} disabled={covered} onChange={() => toggle(n.key)} />
              {n.kind === 'dir' ? `${n.label}/` : n.label}
            </label>
            {n.kind === 'dir' && render(n.children, covered || on)}
            {n.kind === 'file' && (
              <ul>
                {n.statements.map((s) => (
                  <li key={s.id}>
                    <label className={covered || on ? 'check covered stmt' : 'check stmt'}>
                      <input type="checkbox" checked={chosen.has(s.id) || covered || on} disabled={covered || on} onChange={() => toggle(s.id)} />
                      {shortId(s.id)}
                    </label>
                  </li>
                ))}
              </ul>
            )}
          </li>
        );
      })}
    </ul>
  );

  // 今の mapper にない範囲（消えたり名前が変わったりした statement やファイル）が残っていれば知らせる
  const known = new Set<string>();
  const walk = (nodes: TreeNode[]) =>
    nodes.forEach((n) => {
      known.add(n.key);
      if (n.kind === 'dir') walk(n.children);
      else n.statements.forEach((s) => known.add(s.id));
    });
  walk(tree);
  const missing = scope.items.filter((i) => !known.has(i) && !statements.some((s) => s.file.startsWith(i)));

  return (
    <div className="scope-editor">
      <div className="scope-mode">
        <label className="check">
          <input type="radio" checked={scope.all} onChange={() => onChange({ all: true, items: scope.items })} />
          全体
        </label>
        <label className="check">
          <input type="radio" checked={!scope.all} onChange={() => onChange({ all: false, items: scope.items })} />
          選んだフォルダ・ファイル・statement
        </label>
      </div>
      {!scope.all && (
        <>
          <div className="scope-tree">{render(tree, false)}</div>
          {scope.items.length === 0 && <p className="hint">まだどこも選んでいないため、この共通パラメータはどの statement にも効きません。</p>}
          {missing.length > 0 && (
            <p className="hint">
              今の mapper にない範囲: {missing.join(', ')}
              <button type="button" className="link" onClick={() => onChange({ all: false, items: scope.items.filter((i) => !missing.includes(i)) })}>
                外す
              </button>
            </p>
          )}
        </>
      )}
    </div>
  );
}
