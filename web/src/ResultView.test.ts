import { describe, expect, it } from 'vitest';
import { clampGeometry, keepReachable, tablesOfRow } from './ResultView';

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

describe('tablesOfRow', () => {
  it('出どころの分かる列を、表ごとに、元の列の名前で分ける（式の列は除く）', () => {
    expect(tablesOfRow(['erp.employees.id', 'erp.employees.name', 'erp.positions.name', null], ['43', '社員43', '課長', '2'])).toEqual([
      { table: 'erp.employees', row: { id: '43', name: '社員43' } },
      { table: 'erp.positions', row: { name: '課長' } },
    ]);
  });
});

describe('keepReachable', () => {
  it('はみ出させてもよいが、題名の帯は上下にはみ出さず、左右は 120px 残す。大きさは変えない', () => {
    // 右へはみ出す
    expect(keepReachable({ x: 1000, y: 100, w: 600, h: 400 }, 1280, 720)).toEqual({ x: 1000, y: 100, w: 600, h: 400 });
    // 右へ出すぎ・左へ出すぎ
    expect(keepReachable({ x: 1250, y: 100, w: 600, h: 400 }, 1280, 720).x).toBe(1160);
    expect(keepReachable({ x: -700, y: 100, w: 600, h: 400 }, 1280, 720).x).toBe(-480);
    // 上・下
    expect(keepReachable({ x: 0, y: -50, w: 600, h: 400 }, 1280, 720).y).toBe(0);
    expect(keepReachable({ x: 0, y: 700, w: 600, h: 400 }, 1280, 720).y).toBe(680);
    // 画面より大きくてもよい
    expect(keepReachable({ x: 0, y: 0, w: 2000, h: 1500 }, 1280, 720)).toEqual({ x: 0, y: 0, w: 2000, h: 1500 });
  });
});
