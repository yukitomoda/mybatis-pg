import { describe, expect, it } from 'vitest';
import { MIN_PARAMS, MIN_RESULT, MIN_SIDEBAR, clampWidths } from './Splitter';

describe('clampWidths', () => {
  const measured = { sidebar: 250, params: 400 };

  it('最小の幅より狭くしない', () => {
    expect(clampWidths({ sidebar: 10, params: 10 }, 1500, measured)).toEqual({ sidebar: MIN_SIDEBAR, params: MIN_PARAMS });
  });

  it('右のペインに最小の幅を残す', () => {
    const w = clampWidths({ sidebar: 300, params: 2000 }, 1500, measured);
    expect(w).toEqual({ sidebar: 300, params: 1500 - 12 - MIN_RESULT - 300 });
  });

  it('幅を変えていないペインは実際の幅で計算し、null のまま残す', () => {
    expect(clampWidths({ sidebar: null, params: 2000 }, 1500, measured)).toEqual({ sidebar: null, params: 1500 - 12 - MIN_RESULT - 250 });
    expect(clampWidths({ sidebar: null, params: null }, 1500, measured)).toEqual({ sidebar: null, params: null });
  });
});
