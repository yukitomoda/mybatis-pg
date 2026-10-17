// 一括チェックで実行する statement を選ぶツリー（フォルダ・ファイルごとにまとめて選べる）。
// まとめて選ぶときは値がそろったものだけを選ぶ。値が足りないものは 1 つずつなら選べる（実行の前に確認する）
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Readiness } from './readiness';
import { isReady, problemLabel } from './readiness';
import type { StatementEntry, TreeNode } from './tree';
import { buildTree, filterEntries, shortId } from './tree';

const STORAGE_KEY = 'mybatis-pg.bulk-selection';

/** 選んだ statement を覚えておく（ブラウザ） */
export function loadSelection(): Set<string> {
  try {
    const v = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]');
    return new Set(Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);
  } catch {
    return new Set();
  }
}

export function saveSelection(ids: Set<string>) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify([...ids]));
  } catch {
    // 保存できない環境では覚えない
  }
}

function idsUnder(n: TreeNode): string[] {
  return n.kind === 'file' ? n.statements.map((s) => s.id) : n.children.flatMap(idsUnder);
}

/**
 * フォルダ・ファイルのチェック。付けると値がそろったもの（readyIds）だけを選び、外すと中のすべて（ids）を外す。
 */
function Check({
  ids,
  readyIds,
  selected,
  onChange,
  label,
}: {
  ids: string[];
  readyIds: string[];
  selected: Set<string>;
  onChange: (ids: string[], on: boolean) => void;
  label: string;
}) {
  const ref = useRef<HTMLInputElement>(null);
  const count = ids.filter((id) => selected.has(id)).length;
  const all = readyIds.length > 0 && readyIds.every((id) => selected.has(id));
  const partial = count > 0 && !all;
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = partial;
  }, [partial]);
  return (
    <input
      ref={ref}
      type="checkbox"
      aria-label={`${label} を選ぶ`}
      checked={all}
      disabled={readyIds.length === 0 && count === 0}
      onChange={(e) => onChange(e.target.checked ? readyIds : ids, e.target.checked)}
    />
  );
}

export function BulkPicker({
  statements,
  status,
  selected,
  onChange,
  onOpen,
  disabled,
}: {
  statements: StatementEntry[];
  /** statement ごとの足りないもの。調べ終わるまでは null */
  status: Map<string, Readiness> | null;
  selected: Set<string>;
  onChange: (ids: Set<string>) => void;
  /** statement を開いて値を設定する */
  onOpen: (id: string) => void;
  disabled: boolean;
}) {
  const [query, setQuery] = useState('');
  const [readyOnly, setReadyOnly] = useState(true);
  const ready = (id: string) => isReady(status?.get(id));
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [onlySelected, setOnlySelected] = useState(false);

  const visible = useMemo(
    () => filterEntries(statements, query).filter((s) => (!readyOnly || ready(s.id)) && (!onlySelected || selected.has(s.id))),
    // 選択だけを出しているときは、選び直しても一覧が変わらないよう、切り替えた時点の選択で絞る
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [statements, status, query, readyOnly, onlySelected],
  );
  const tree = useMemo(() => buildTree(visible), [visible]);
  // 絞り込んでいるときは、すべて開く
  const expandAll = query.trim() !== '' || onlySelected;
  const readyVisible = visible.filter((s) => ready(s.id)).map((s) => s.id);
  const selectedCount = statements.filter((s) => selected.has(s.id)).length;

  const set = (ids: string[], on: boolean) => {
    const next = new Set(selected);
    for (const id of ids) {
      if (on) next.add(id);
      else next.delete(id);
    }
    onChange(next);
  };
  const toggle = (key: string) =>
    setOpen((o) => {
      const next = new Set(o);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const render = (nodes: TreeNode[]) => (
    <ul>
      {nodes.map((n) => {
        const isOpen = expandAll || open.has(n.key);
        return (
          <li key={n.key}>
            <div className="pick-line">
              <Check ids={idsUnder(n)} readyIds={idsUnder(n).filter(ready)} selected={selected} onChange={set} label={n.label} />
              <button type="button" className={`node ${n.kind}`} aria-expanded={isOpen} onClick={() => toggle(n.key)} title={n.key}>
                <span className="twisty">{isOpen ? '▾' : '▸'}</span>
                {n.label}
              </button>
            </div>
            {isOpen &&
              (n.kind === 'dir' ? (
                render(n.children)
              ) : (
                <ul>
                  {n.statements.map((s) => {
                    const r = status?.get(s.id);
                    const problem = r && !isReady(r) ? problemLabel(r) : null;
                    return (
                      <li key={s.id}>
                        <label className={problem ? 'pick-line stmt not-ready' : 'pick-line stmt'} title={s.id}>
                          <input type="checkbox" checked={selected.has(s.id)} disabled={!r} onChange={(e) => set([s.id], e.target.checked)} />
                          <span className="node stmt">{shortId(s.id)}</span>
                          {problem && (
                            <button
                              type="button"
                              className="badge warn"
                              title={`${problem.detail}\n（クリックで開いて設定）`}
                              onClick={(e) => {
                                e.preventDefault();
                                onOpen(s.id);
                              }}
                            >
                              {problem.label}
                            </button>
                          )}
                        </label>
                      </li>
                    );
                  })}
                </ul>
              ))}
          </li>
        );
      })}
    </ul>
  );

  return (
    <aside className="bulk-picker">
      <fieldset disabled={disabled}>
        <input type="search" placeholder="statement・ファイルで絞り込み" value={query} onChange={(e) => setQuery(e.target.value)} />
        <label className="check">
          <input type="checkbox" checked={readyOnly} onChange={(e) => setReadyOnly(e.target.checked)} />
          値がそろったものだけ
        </label>
        <label className="check">
          <input type="checkbox" checked={onlySelected} onChange={(e) => setOnlySelected(e.target.checked)} />
          選んだものだけ
        </label>
        <div className="bulk-picker-actions">
          <span>{selectedCount} 件選択中</span>
          <button type="button" className="link" onClick={() => set(readyVisible, true)}>
            表示中の {readyVisible.length} 件を選ぶ
          </button>
          <button type="button" className="link" disabled={selectedCount === 0} onClick={() => onChange(new Set())}>
            解除
          </button>
        </div>
        <nav className="tree-list">
          {!status ? (
            <p className="hint">値がそろっているかを調べています…</p>
          ) : tree.length === 0 ? (
            <p className="hint">{readyOnly ? '値がそろった statement がありません' : '該当する statement がありません'}</p>
          ) : (
            render(tree)
          )}
        </nav>
      </fieldset>
    </aside>
  );
}
