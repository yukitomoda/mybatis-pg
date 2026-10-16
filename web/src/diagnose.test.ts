/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';
import { diagnose } from './diagnose';
import json from './fixtures/plan11.json?raw';
import text from './fixtures/plan11.txt?raw';
import { parsePlan } from './plan';

const summary = (input: string) => diagnose(parsePlan(input)).map((f) => `${f.severity} ${f.node}: ${f.title}`);

describe('diagnose', () => {
  it('全件読み込み、ディスクを使う並べ替え、分割したハッシュを指摘する（Limit の下の見積もりのずれは指摘しない）', () => {
    const expected = [
      'high Parallel Seq Scan on users u: 表を全件読んで、大半の行を条件で捨てています',
      'medium Sort: 並べ替えがメモリに収まらず、ディスクを使っています',
      'medium Parallel Hash: ハッシュがメモリに収まらず、分割して処理しています',
    ];
    expect(summary(text)).toEqual(expected);
    expect(summary(json)).toEqual(expected);
  });

  it('根拠に読み取った値を出す', () => {
    const f = diagnose(parsePlan(text));
    expect(f[0].evidence).toMatch(/200,000 行のうち 186,244 行（93%）/);
    expect(f[2].evidence).toBe('Batches: 32');
  });

  it('入れ子ループの内側の全件読み込みと、見積もりのずれと、時間の多くを占めるノード', () => {
    const plan = `Nested Loop  (cost=0.00..100.00 rows=10 width=8) (actual time=0.1..900.0 rows=5000 loops=1)
  ->  Seq Scan on a  (cost=0.00..10.00 rows=10 width=4) (actual time=0.01..1.00 rows=5000 loops=1)
  ->  Seq Scan on b  (cost=0.00..1.00 rows=1 width=4) (actual time=0.10..0.17 rows=1 loops=5000)
        Filter: (b.a_id = a.id)
        Rows Removed by Filter: 999
Planning Time: 0.1 ms
Execution Time: 900.0 ms`;
    expect(summary(plan)).toEqual([
      'high Nested Loop: 入れ子ループの内側で、表を何度も全件読んでいます',
      'high Seq Scan on b: 表を全件読んで、大半の行を条件で捨てています',
      'medium Nested Loop: 実際の行数が見積もりよりずっと多い',
      'medium Seq Scan on a: 実際の行数が見積もりよりずっと多い',
      'info Seq Scan on b: 時間の多くをこのノードが占めています',
    ]);
  });

  it('ANALYZE なしでは実績に基づく指摘をしない', () => {
    expect(summary('Seq Scan on users  (cost=0.00..4037.71 rows=9818 width=18)\n  Filter: (status = 1)')).toEqual([]);
  });
});
