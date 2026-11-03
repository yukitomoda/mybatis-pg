import { describe, expect, it } from 'vitest';
import { fitText, measure } from './textFit';

describe('fitText', () => {
  it('収まれば そのまま、収まらなければ末尾を「…」にして幅に収める', () => {
    expect(fitText('users', 200, '600 13px')).toBe('users');
    const long = 'customer_order_history_archive_2024';
    const cut = fitText(long, 120, '600 13px');
    expect(cut.endsWith('…')).toBe(true);
    expect(long.startsWith(cut.slice(0, -1))).toBe(true);
    expect(measure(cut, '600 13px')).toBeLessThanOrEqual(120);
    // 全角は半角より広く数える
    expect(measure('顧客', '10.5px')).toBeGreaterThan(measure('ab', '10.5px'));
  });
});
