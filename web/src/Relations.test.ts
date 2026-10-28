import { describe, expect, it } from 'vitest';
import { describeLink, duration, keyLabel } from './Relations';

describe('時間の表し方', () => {
  it('秒・分・時間', () => {
    expect(duration(40_000)).toBe('40 秒');
    expect(duration(3 * 60_000)).toBe('3 分');
    expect(duration(65 * 60_000)).toBe('1 時間 5 分');
    expect(duration(120 * 60_000)).toBe('2 時間');
  });
});

describe('一意かどうかと、つながりの判定の理由', () => {
  it('一意の順位を言葉にする', () => {
    expect(keyLabel(2)).toBe('主キー');
    expect(keyLabel(3)).toBe('UNIQUE');
    expect(keyLabel(4)).toBe('主キー（2 列）');
    expect(keyLabel(7)).toBe('UNIQUE（3 列）');
    expect(keyLabel(undefined)).toBe(null);
  });

  it('列の側から見た向きと理由', () => {
    const keys = { 'app.users.id': 2, 'app.posts.id': 2 };
    const s = (c: string) => c.slice(c.indexOf('.') + 1);
    const parent = { a: 'app.posts.user_id', b: 'app.users.id', kind: 'parent' as const, statements: [], manual: false, disabled: false };
    expect(describeLink(parent, 'app.posts.user_id', keys, s)).toEqual({
      label: '→ 親',
      reason: 'users.id が主キー、この列は一意でない',
    });
    expect(describeLink(parent, 'app.users.id', keys, s).label).toBe('← 子');
    const sibling = { ...parent, b: 'app.histories.user_id', kind: 'sibling' as const };
    expect(describeLink(sibling, 'app.posts.user_id', keys, s).reason).toBe('どちらも 1 列では一意でない');
    // 複数の列で一意の列は、そのことも書く
    expect(describeLink(parent, 'app.posts.user_id', { ...keys, 'app.posts.user_id': 4 }, s).reason).toBe(
      'users.id が主キー、この列は 1 列では一意でない（主キー（2 列））',
    );
    expect(describeLink({ ...parent, manual: true }, 'app.posts.user_id', keys, s)).toEqual({ label: '→ 親', reason: '手で設定' });
  });
});
