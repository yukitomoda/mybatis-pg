import { describe, expect, it } from 'vitest';
import { clampGeometry } from './ResultView';

describe('clampGeometry', () => {
  it('広い画面で大きくした位置と大きさを、小さい画面の中に収める（右下のつまみが画面の中に来る）', () => {
    const g = clampGeometry({ x: 900, y: 300, w: 1600, h: 1000 }, 1280, 720);
    expect(g).toEqual({ x: 8, y: 8, w: 1264, h: 704 });
    expect(g.x + g.w).toBeLessThanOrEqual(1280);
    expect(g.y + g.h).toBeLessThanOrEqual(720);
  });

  it('収まっていれば、そのまま', () => {
    expect(clampGeometry({ x: 100, y: 64, w: 600, h: 400 }, 1280, 720)).toEqual({ x: 100, y: 64, w: 600, h: 400 });
  });

  it('大きさが収まっても、はみ出す位置は戻す', () => {
    expect(clampGeometry({ x: 1000, y: 600, w: 600, h: 400 }, 1280, 720)).toEqual({ x: 672, y: 312, w: 600, h: 400 });
  });
});
