// 図の箱の中の文字の幅を、実際の字体で測る（字体を読めないとき・テストでは、文字数からの目安で測る）

/** 箱の表の名前と、2 行目（コメント・主キー）の字体（styles.css の .node-name・.node-sub と同じ） */
export const NAME_FONT = '600 13px';
export const SUB_FONT = '10.5px';

let ctx: CanvasRenderingContext2D | null | undefined;
let mono = '';

function context(): CanvasRenderingContext2D | null {
  if (ctx === undefined) {
    try {
      ctx = document.createElement('canvas').getContext('2d');
      mono = getComputedStyle(document.documentElement).getPropertyValue('--mono').trim() || 'monospace';
    } catch {
      ctx = null;
    }
  }
  return ctx;
}

/** 文字の幅（px）。font は太さと大きさ（字体は等幅のもの） */
export function measure(text: string, font: string): number {
  const c = context();
  if (c) {
    c.font = `${font} ${mono}`;
    return c.measureText(text).width;
  }
  // 目安: 半角は大きさの 0.62 倍、全角はその 1.75 倍（太字は少し広く）
  const size = parseFloat(font.replace(/^\d{3}\s+/, ''));
  const half = size * (font.startsWith('6') || font.startsWith('7') ? 0.64 : 0.6);
  return [...text].reduce((w, ch) => w + (ch.charCodeAt(0) > 0xff ? half * 1.75 : half), 0);
}

/** 幅に収まるよう、収まらなければ末尾を「…」にして切る */
export function fitText(text: string, max: number, font: string): string {
  if (measure(text, font) <= max) return text;
  const chars = [...text];
  let lo = 0;
  let hi = chars.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (measure(chars.slice(0, mid).join('') + '…', font) <= max) lo = mid;
    else hi = mid - 1;
  }
  return chars.slice(0, lo).join('') + '…';
}
