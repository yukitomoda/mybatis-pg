// 図の背景をドラッグして、見る場所を動かす（箱・ボタン・リンクの上で押したときは、その操作のまま）
import { useEffect, type RefObject } from 'react';

/** これより動いたらドラッグとみなす（px。少しの手ぶれでは、押しただけとして扱う） */
const THRESHOLD = 4;

export function useDragScroll(ref: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    let start: { x: number; y: number; left: number; top: number; id: number } | null = null;
    let dragged = false;
    // 押している間の動きは、図の外に出ても受け取れるよう、ページ全体で受け取る
    const move = (e: PointerEvent) => {
      if (!start || e.pointerId !== start.id) return;
      const dx = e.clientX - start.x;
      const dy = e.clientY - start.y;
      if (!dragged && Math.abs(dx) + Math.abs(dy) < THRESHOLD) return;
      if (!dragged) {
        dragged = true;
        el.classList.add('dragging');
      }
      el.scrollLeft = start.left - dx;
      el.scrollTop = start.top - dy;
    };
    const up = () => {
      start = null;
      el.classList.remove('dragging');
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', up);
    };
    const down = (e: PointerEvent) => {
      // 前のドラッグを図の外で離したときも、次に押したら印を消す
      dragged = false;
      if (e.button !== 0 || (e.target as Element).closest('.node, button, a, input, select, label')) return;
      start = { x: e.clientX, y: e.clientY, left: el.scrollLeft, top: el.scrollTop, id: e.pointerId };
      // 文字の選択や、ブラウザの画像のドラッグを始めない
      e.preventDefault();
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
      window.addEventListener('pointercancel', up);
    };
    const noNativeDrag = (e: DragEvent) => e.preventDefault();
    // ドラッグした後の click は、押したことにしない（線を押すと小窓が開くため）
    const click = (e: MouseEvent) => {
      if (dragged) {
        e.stopPropagation();
        e.preventDefault();
        dragged = false;
      }
    };
    el.addEventListener('pointerdown', down);
    el.addEventListener('click', click, true);
    el.addEventListener('dragstart', noNativeDrag);
    el.classList.add('drag-scroll');
    return () => {
      up();
      el.removeEventListener('pointerdown', down);
      el.removeEventListener('click', click, true);
      el.removeEventListener('dragstart', noNativeDrag);
      el.classList.remove('drag-scroll');
    };
  }, [ref]);
}
