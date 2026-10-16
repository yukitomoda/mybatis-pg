/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';
import { ratioTo, summarize } from './compare';
import text from './fixtures/plan11.txt?raw';
import { parsePlan } from './plan';

describe('summarize', () => {
  it('比べるための値を取り出す', () => {
    const s = summarize(parsePlan(text));
    expect(s.executionMs).toBe(65.052);
    expect(s.planningMs).toBe(0.411);
    expect(s.rows).toBe(10);
    expect(s.hit).toBe(4222);
    expect(s.read).toBe(0);
    expect(s.temp).toBe(1636 + 2001);
    expect(s.scans).toEqual(['Parallel Seq Scan on orders o', 'Parallel Seq Scan on users u']);
    expect(s.hotNodes[0].title).toBe('Parallel Hash Join');
    expect(s.findings).toEqual({ high: 1, medium: 2, info: 0 });
  });

  it('ANALYZE なしでは実績の値は null', () => {
    const s = summarize(parsePlan('Seq Scan on users  (cost=0.00..4037.71 rows=9818 width=18)'));
    expect(s.executionMs).toBeNull();
    expect(s.rows).toBeNull();
    expect(s.totalCost).toBe(4037.71);
    expect(s.hotNodes).toEqual([]);
  });

  it('倍率', () => {
    expect(ratioTo(10, 5)).toBe(0.5);
    expect(ratioTo(0, 5)).toBeNull();
    expect(ratioTo(null, 5)).toBeNull();
  });
});
