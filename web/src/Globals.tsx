// 共通パラメータ（複数の statement で共有する値の集まり）: 状態、共通パラメータの画面、クエリ画面の一覧
import type { ReactNode } from 'react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Api, GlobalsResponse, ParameterName } from './api';
import type { GenContext } from './GenInput';
import { ParamTree } from './ParamTree';
import type { ParamSchema, PValue } from './params';
import { EMPTY_ROOT, getAt, setAt, toJson } from './params';
import type { Decision, GlobalSet, RuleState, Target } from './scope';
import { ROOT, decide, placeLabel, related } from './scope';
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
  error: string | null;
  /** 値を変える（少し待ってから保存する） */
  setValues: (id: string, v: PValue | undefined) => void;
  /** 作った共通パラメータの id を返す */
  create: (copyFrom: GlobalSet | null) => Promise<string | null>;
  rename: (set: GlobalSet) => void;
  remove: (set: GlobalSet) => Promise<boolean>;
  /** 場所での適用する・しないを決める。null なら決めない（外側に従う） */
  setRule: (set: GlobalSet, place: string, state: RuleState | null) => void;
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
  const patch = (id: string, change: (set: GlobalSet) => GlobalSet) => setState((s) => ({ sets: s.sets.map((x) => (x.id === id ? change(x) : x)) }));

  return {
    sets: state.sets,
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
    setRule: (set, place, ruleState) => {
      patch(set.id, (x) => {
        const rules = { ...x.rules };
        if (ruleState) rules[place] = ruleState;
        else delete rules[place];
        return { ...x, rules };
      });
      run(() => api.ruleGlobal(set.id, place, ruleState));
    },
    move: (set, offset) => {
      run(() => api.moveGlobal(set.id, offset));
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

/** 判定を「全体で適用」「order/ で適用しない」の形にする */
function decisionLabel(d: Decision): string {
  return `${placeLabel(d.place)} で${d.state === 'on' ? '適用' : '適用しない'}`;
}

// ---- クエリ画面: この statement に関わる共通パラメータ ----

/**
 * statement に関わる共通パラメータ（どこかの場所に設定があるもの）を並べる。
 * チェックは、この statement での適用する・しない（ほかの statement には影響しない）。
 */
export function AppliedGlobals({ globals, target, onSettings }: { globals: Globals; target: Target; onSettings: (setId: string | null) => void }) {
  const list = related(globals.sets, target);
  const toggle = (set: GlobalSet, d: Decision) => {
    const want: RuleState = d.state === 'on' ? 'off' : 'on';
    // この statement の設定を外したときの判定（外側の場所から決まるもの）
    const withoutHere = { ...set, rules: Object.fromEntries(Object.entries(set.rules).filter(([k]) => k !== target.id)) };
    const outerState: RuleState = decide(withoutHere, target)?.state ?? 'off';
    // 外側と同じになるなら、この statement の設定は要らないので消す
    globals.setRule(set, target.id, outerState === want ? null : want);
  };
  return (
    <section className="applied-globals">
      <header>
        <h3>共通パラメータ</h3>
        <button type="button" className="link" onClick={() => onSettings(null)}>
          設定
        </button>
      </header>
      {list.length === 0 ? (
        <p className="hint">この statement に関わる共通パラメータはありません。</p>
      ) : (
        <table>
          <tbody>
            {list.map(({ set, decision }) => (
              <tr key={set.id} className={decision.state === 'on' ? undefined : 'off'}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`${set.name} をこの statement で適用する`}
                    checked={decision.state === 'on'}
                    onChange={() => toggle(set, decision)}
                  />
                </td>
                <td className="global-name">
                  <button type="button" className="link" onClick={() => onSettings(set.id)}>
                    {set.name}
                  </button>
                </td>
                <td className={decision.place.kind === 'statement' ? 'decided here' : 'decided'} title={decision.place.key === ROOT ? '全体' : decision.place.key}>
                  {decisionLabel(decision)}
                </td>
                <td className="values" title={summary(set.values)}>
                  {decision.state === 'on' ? summary(set.values) : ''}
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
        <ul>
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
        </ul>
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
            <h3>適用する場所</h3>
            <PlaceTree set={selected} statements={statements} onRule={(place, state) => globals.setRule(selected, place, state)} />
          </section>
        </>
      ) : (
        <section className="globals-values">
          <p className="hint">{sets.length === 0 ? '共通パラメータを作ると、ここで値と適用する場所を設定できます。' : '左の一覧から共通パラメータを選んでください。'}</p>
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

// ---- 適用する場所のツリー ----

/** 場所のツリーの節 */
export interface PlaceNode {
  key: string;
  label: string;
  kind: 'all' | 'dir' | 'file' | 'statement';
  children: PlaceNode[];
}

/**
 * statement の一覧から、場所のツリーを作る（全体を一番上に置く）。
 * 子がフォルダ 1 つだけのフォルダは子とまとめて 1 行にするが、keep にある場所（設定のある場所）はまとめずに残す。
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
    // 設定のないフォルダで、子もフォルダ 1 つなら、まとめる（src/ main/ → src/main/）
    if (r.kind === 'dir' && !keep.has(r.key) && kids.length === 1 && kids[0].kind === 'dir') {
      return { ...kids[0], label: `${r.label}/${kids[0].label}` };
    }
    return { key: r.key, label: r.label, kind: r.kind, children: kids };
  };
  return finish(root);
}

/** 節の中の statement */
function statementsUnder(n: PlaceNode): string[] {
  return n.kind === 'statement' ? [n.key] : n.children.flatMap(statementsUnder);
}

const RULE_LABELS = { on: '適用する', off: '適用しない' } as const;

/** 適用する場所の編集: 場所ごとに 適用する・適用しない・指定なし（外側に従う） */
function PlaceTree({ set, statements, onRule }: { set: GlobalSet; statements: StatementEntry[]; onRule: (place: string, state: RuleState | null) => void }) {
  const keep = useMemo(() => new Set(Object.keys(set.rules)), [set.rules]);
  const tree = useMemo(() => placeTree(statements, keep), [statements, keep]);
  const targets = useMemo(() => new Map(statements.map((s) => [s.id, { id: s.id, file: s.file }])), [statements]);
  // 開閉を切り替えた場所（フォルダは最初から開き、ファイルは最初は閉じる）
  const [toggled, setToggled] = useState<Set<string>>(new Set());

  // 中の statement での結果（すべて適用・すべて適用しない・一部）
  const result = (n: PlaceNode): 'on' | 'off' | 'mixed' => {
    const states = new Set(statementsUnder(n).map((id) => decide(set, targets.get(id)!)?.state ?? 'off'));
    return states.size === 1 ? ([...states][0] as 'on' | 'off') : 'mixed';
  };

  const render = (n: PlaceNode, depth: number): ReactNode => {
    const own = set.rules[n.key] ?? null;
    const r = result(n);
    const collapsible = n.kind === 'dir' || n.kind === 'file';
    // ファイルは、中の statement に設定があれば最初から開く
    const defaultOpen = n.kind !== 'file' || n.children.some((c) => set.rules[c.key]);
    const expanded = collapsible ? defaultOpen !== toggled.has(n.key) : true;
    return (
      <li key={n.key}>
        <div className={`place-row ${n.kind}${own ? ' has-rule' : ''}`} style={{ paddingLeft: `${depth * 1.1 + 0.3}rem` }}>
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
          <span className="place-label" title={n.key === ROOT ? 'すべての statement' : n.key}>
            {n.kind === 'dir' ? `${n.label}/` : n.label}
          </span>
          {/* 設定のない場所には、外側から決まる結果を薄く出す */}
          {!own && <span className={`place-result ${r}`}>{r === 'on' ? '適用される' : r === 'mixed' ? '一部に適用される' : ''}</span>}
          <span className="rule-switch" role="group" aria-label={`${n.label} での設定`}>
            {(['on', 'off'] as const).map((state) => (
              <button
                key={state}
                type="button"
                className={own === state ? `selected ${state}` : state}
                aria-pressed={own === state}
                title={own === state ? 'もう一度押すと、設定を外して外側に従う' : undefined}
                onClick={() => onRule(n.key, own === state ? null : state)}
              >
                {RULE_LABELS[state]}
              </button>
            ))}
          </span>
        </div>
        {expanded && n.children.length > 0 && <ul>{n.children.map((c) => render(c, depth + 1))}</ul>}
      </li>
    );
  };

  // 今の mapper にない場所の設定（消えたり名前が変わったりした statement やファイル）
  const known = new Set<string>();
  const walk = (n: PlaceNode) => {
    known.add(n.key);
    n.children.forEach(walk);
  };
  walk(tree);
  const missing = Object.keys(set.rules).filter((k) => !known.has(k) && !statements.some((s) => k.endsWith('/') && s.file.startsWith(k)));

  return (
    <div className="place-tree">
      <ul>{render(tree, 0)}</ul>
      {missing.length > 0 && (
        <div className="place-missing">
          <p className="hint">今の mapper にない場所の設定:</p>
          <ul>
            {missing.map((k) => (
              <li key={k}>
                <code>{k}</code>（{RULE_LABELS[set.rules[k]]}）
                <button type="button" className="link" onClick={() => onRule(k, null)}>
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
