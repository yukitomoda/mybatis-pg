// ドラッグで並べ替えられる一覧。つまみにフォーカスしているときは ↑↓ キーでも動かせる
import type { ReactNode } from 'react';
import { useState } from 'react';
import { moved } from './globalModel';

export function DragList<T>({
  items,
  keyOf,
  label,
  render,
  onReorder,
  selected,
}: {
  items: T[];
  keyOf: (item: T) => string;
  /** つまみの読み上げに使う名前 */
  label: (item: T) => string;
  render: (item: T, index: number) => ReactNode;
  onReorder: (items: T[]) => void;
  selected?: (item: T) => boolean;
}) {
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);
  const end = () => {
    setDragging(null);
    setOver(null);
  };
  return (
    <ol className="drag-list">
      {items.map((item, i) => {
        const classes = [
          selected?.(item) && 'selected',
          dragging === i && 'dragging',
          dragging !== null && over === i && dragging !== i && (dragging < i ? 'drop-after' : 'drop-before'),
        ].filter(Boolean);
        return (
          <li
            key={keyOf(item)}
            className={classes.join(' ') || undefined}
            draggable
            onDragStart={(e) => {
              setDragging(i);
              e.dataTransfer.effectAllowed = 'move';
              e.dataTransfer.setData('text/plain', label(item));
            }}
            onDragOver={(e) => {
              if (dragging === null) return;
              e.preventDefault();
              e.dataTransfer.dropEffect = 'move';
              setOver(i);
            }}
            onDrop={(e) => {
              e.preventDefault();
              if (dragging !== null && dragging !== i) onReorder(moved(items, dragging, i));
              end();
            }}
            onDragEnd={end}
          >
            <span
              className="drag-handle"
              role="button"
              tabIndex={0}
              aria-label={`${label(item)} を並べ替える（↑↓ キー）`}
              onKeyDown={(e) => {
                const to = e.key === 'ArrowUp' ? i - 1 : e.key === 'ArrowDown' ? i + 1 : null;
                if (to === null || to < 0 || to >= items.length) return;
                e.preventDefault();
                onReorder(moved(items, i, to));
              }}
            >
              ⠿
            </span>
            {render(item, i)}
          </li>
        );
      })}
    </ol>
  );
}
