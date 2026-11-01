// 「i」のボタンと、押すとその近くに浮いて出る小窓（理由や根拠など、見たいときだけ見るもの）。
// 画面の中に開かないので、まわりの表示はずれない。外を押すか Esc で閉じる
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

export function InfoButton({ title, children }: { title: string; children: ReactNode }) {
  const [anchor, setAnchor] = useState<DOMRect | null>(null);
  const button = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button
        ref={button}
        type="button"
        className="icon info-button"
        title={title}
        aria-label={title}
        aria-expanded={anchor !== null}
        onClick={(e) => setAnchor(anchor ? null : e.currentTarget.getBoundingClientRect())}
      >
        <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
          <circle cx="8" cy="8" r="6.6" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <circle cx="8" cy="4.9" r="1" fill="currentColor" />
          <path d="M8 7.2v4.6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </button>
      {anchor && (
        <Popover anchor={anchor} ignore={button} onClose={() => setAnchor(null)}>
          {children}
        </Popover>
      )}
    </>
  );
}

export function Popover({
  anchor,
  ignore,
  onClose,
  children,
}: {
  anchor: DOMRect;
  /** 押しても閉じない要素（開いたボタン。押すと閉じるのはボタンの側で） */
  ignore?: React.RefObject<HTMLElement | null>;
  onClose: () => void;
  children: ReactNode;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);

  // ボタンの下に出す。画面の右や下からはみ出すなら、左にずらすか、上に出す
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    const left = Math.max(8, Math.min(anchor.left, window.innerWidth - w - 8));
    const below = anchor.bottom + 4;
    const top = below + h > window.innerHeight - 8 && anchor.top - h - 4 > 8 ? anchor.top - h - 4 : below;
    setPos({ top, left });
  }, [anchor]);

  useEffect(() => {
    const down = (e: MouseEvent) => {
      const t = e.target as Node;
      if (box.current?.contains(t) || ignore?.current?.contains(t)) return;
      onClose();
    };
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    // まわりがスクロールすると、ボタンから離れてしまうので閉じる（小窓の中のスクロールはそのまま）
    const scroll = (e: Event) => {
      if (box.current?.contains(e.target as Node)) return;
      onClose();
    };
    document.addEventListener('mousedown', down);
    document.addEventListener('keydown', key);
    window.addEventListener('scroll', scroll, true);
    window.addEventListener('resize', onClose);
    return () => {
      document.removeEventListener('mousedown', down);
      document.removeEventListener('keydown', key);
      window.removeEventListener('scroll', scroll, true);
      window.removeEventListener('resize', onClose);
    };
  }, [onClose, ignore]);

  return createPortal(
    <div
      ref={box}
      className="popover"
      role="dialog"
      style={pos ? { top: pos.top, left: pos.left } : { top: anchor.bottom + 4, left: anchor.left, visibility: 'hidden' }}
      // 中のリンクを押したら（別の画面に移るなど）閉じる
      onClick={(e) => {
        if ((e.target as HTMLElement).closest('button, a')) onClose();
      }}
    >
      {children}
    </div>,
    document.body,
  );
}
