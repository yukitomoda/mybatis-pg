// 一括チェックで実行する statement を選ぶツリー（フォルダ・ファイルごとにまとめて選べる）
import { useEffect, useMemo, useRef, useState } from 'react';
import { hasParams } from './paramStore';
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

function Check({ ids, selected, onChange, label }: { ids: string[]; selected: Set<string>; onChange: (ids: string[], on: boolean) => void; label: string }) {
  const ref = useRef<HTMLInputElement>(null);
  const count = ids.filter((id) => selected.has(id)).length;
  const all = count > 0 && count === ids.length;
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = count > 0 && !all;
  }, [count, all]);
  return <input ref={ref} type="checkbox" aria-label={`${label} を選ぶ`} checked={all} onChange={(e) => onChange(ids, e.target.checked)} />;
}

export function BulkPicker({
  statements,
  selected,
  onChange,
  disabled,
}: {
  statements: StatementEntry[];
  selected: Set<string>;
  onChange: (ids: Set<string>) => void;
  disabled: boolean;
}) {
  const [query, setQuery] = useState('');
  const [withValues, setWithValues] = useState(false);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [onlySelected, setOnlySelected] = useState(false);

  const visible = useMemo(
    () => filterEntries(statements, query).filter((s) => (!withValues || hasParams(s.id)) && (!onlySelected || selected.has(s.id))),
    // 選択だけを出しているときは、選び直しても一覧が変わらないよう、切り替えた時点の選択で絞る
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [statements, query, withValues, onlySelected],
  );
  const tree = useMemo(() => buildTree(visible), [visible]);
  // 絞り込んでいるときは、すべて開く
  const expandAll = query.trim() !== '' || withValues || onlySelected;

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
              <Check ids={idsUnder(n)} selected={selected} onChange={set} label={n.label} />
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
                  {n.statements.map((s) => (
                    <li key={s.id}>
                      <label className="pick-line stmt" title={s.id}>
                        <input type="checkbox" checked={selected.has(s.id)} onChange={(e) => set([s.id], e.target.checked)} />
                        <span className="node stmt">{shortId(s.id)}</span>
                        {hasParams(s.id) && <span className="badge muted">値あり</span>}
                      </label>
                    </li>
                  ))}
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
          <input type="checkbox" checked={withValues} onChange={(e) => setWithValues(e.target.checked)} />
          値を設定した statement だけ
        </label>
        <label className="check">
          <input type="checkbox" checked={onlySelected} onChange={(e) => setOnlySelected(e.target.checked)} />
          選んだものだけ
        </label>
        <div className="bulk-picker-actions">
          <span>{selected.size} 件選択中</span>
          <button
            type="button"
            className="link"
            onClick={() =>
              set(
                visible.map((s) => s.id),
                true,
              )
            }
          >
            表示中の {visible.length} 件を選ぶ
          </button>
          <button type="button" className="link" disabled={selected.size === 0} onClick={() => onChange(new Set())}>
            解除
          </button>
        </div>
        <nav className="tree-list">{tree.length === 0 ? <p className="hint">該当する statement がありません</p> : render(tree)}</nav>
      </fieldset>
    </aside>
  );
}
