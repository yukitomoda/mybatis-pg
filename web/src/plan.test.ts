/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';
// PostgreSQL 11.5 で同じクエリを JSON 形式とテキスト形式で EXPLAIN (ANALYZE, BUFFERS) したもの
import json from './fixtures/plan11.json?raw';
import text from './fixtures/plan11.txt?raw';
import { estimateRatio, flatten, parsePlan, timings } from './plan';

describe('parsePlan', () => {
  it('JSON 形式とテキスト形式で、同じ木と名前になる', () => {
    // 並列実行のため、2 回の実行で行数が少し違うことがある。木の形と名前と回数で比べる
    const shape = (input: string) => flatten(parsePlan(input).root).map((r) => `${'  '.repeat(r.depth)}${r.node.title} loops=${r.node.actual?.loops}`);
    expect(shape(json)).toEqual(shape(text));
    const b = flatten(parsePlan(text).root).map((r) => `${'  '.repeat(r.depth)}${r.node.title} rows=${r.node.actual?.rows} loops=${r.node.actual?.loops}`);
    expect(b).toEqual([
      'Limit rows=10 loops=1',
      '  Sort rows=10 loops=1',
      '    Finalize GroupAggregate rows=13756 loops=1',
      '      Gather Merge rows=13770 loops=1',
      '        Partial GroupAggregate rows=6885 loops=2',
      '          Sort rows=10317 loops=2',
      '            Parallel Hash Join rows=10317 loops=2',
      '              Parallel Seq Scan on orders o rows=150000 loops=2',
      '              Parallel Hash rows=6878 loops=2',
      '                Parallel Seq Scan on users u rows=6878 loops=2',
    ]);
  });

  it('テキスト形式の詳細とバッファと時間', () => {
    const p = parsePlan(text);
    expect(p.analyzed).toBe(true);
    expect(p.planningMs).toBe(0.411);
    expect(p.executionMs).toBe(65.052);
    const rows = flatten(p.root);
    expect(rows[0].node.buffers).toEqual({ hit: 4222, read: 0, dirtied: 0, written: 0, tempRead: 1636, tempWritten: 2001 });
    const sort = rows[5].node;
    expect(sort.details).toContainEqual(['Sort Method', 'external merge  Disk: 368kB']);
    expect(sort.details).toContainEqual(['Worker 0', 'Sort Method: external merge  Disk: 368kB']);
    const users = rows[9].node;
    expect(users.details).toEqual([
      ['Filter', "((name ~~ '%7%'::text) AND (status = 1))"],
      ['Rows Removed by Filter', '93122'],
    ]);
  });

  it('JSON 形式の詳細', () => {
    const rows = flatten(parsePlan(json).root);
    expect(rows[9].node.details).toContainEqual(['Rows Removed by Filter', '93122']);
    expect(rows[8].node.details).toContainEqual(['Hash Batches', '32']);
    expect(rows[0].node.buffers?.tempRead).toBeGreaterThan(0);
  });

  it('ANALYZE なしのテキスト形式', () => {
    const p = parsePlan(`QUERY PLAN
-----------------------------------------------------------------------
 Limit  (cost=0.42..2.96 rows=5 width=18)
   ->  Index Scan Backward using users_pkey on users  (cost=0.42..8480.42 rows=16690 width=18)
         Filter: ((name ~~ '%9%'::text) AND (status = 1))
(3 rows)`);
    expect(p.analyzed).toBe(false);
    expect(p.root.children[0].title).toBe('Index Scan Backward using users_pkey on users');
    expect(p.root.children[0].details).toEqual([['Filter', "((name ~~ '%9%'::text) AND (status = 1))"]]);
  });

  it('解釈できない入力は誤り', () => {
    expect(() => parsePlan('')).toThrow('空');
    expect(() => parsePlan('hello')).toThrow('解釈できません');
    expect(() => parsePlan('[{"x":1}]')).toThrow('Plan');
  });
});

describe('時間と見積もり', () => {
  it('自身の時間は合計から子の時間を引き、見積もりとの比を出す', () => {
    const p = parsePlan(text);
    const rows = flatten(p.root);
    const t = timings(p.root);
    const limit = rows[0].node;
    expect(t.get(limit)!.inclusive).toBeCloseTo(64.805);
    expect(t.get(limit)!.self).toBeCloseTo(64.805 - 57.533);
    // Gather Merge の下は 2 プロセスで動いているので、ループ数（2）で割った経過時間で数える
    const join = rows[6].node;
    expect(t.get(join)!.inclusive).toBeCloseTo(47.439);
    expect(t.get(join)!.self).toBeCloseTo(47.439 - 10.749 - 12.144);
    const gather = rows[3].node;
    expect(t.get(gather)!.self).toBeCloseTo(61.67 - 51.989);
    // 見積もり 9818 行に対して実績 6878 行
    expect(estimateRatio(rows[9].node)).toBeCloseTo(6878 / 9818);
  });
});

describe('JSON 形式の名前', () => {
  it('テキスト形式と同じ書き方にする', () => {
    const p = parsePlan(
      JSON.stringify([
        {
          Plan: {
            'Node Type': 'Bitmap Heap Scan',
            'Relation Name': 'users',
            Alias: 'users',
            'Plan Rows': 1,
            Plans: [{ 'Node Type': 'Bitmap Index Scan', 'Index Name': 'users_status', 'Plan Rows': 1 }],
          },
        },
      ]),
    );
    expect(flatten(p.root).map((r) => r.node.title)).toEqual(['Bitmap Heap Scan on users', 'Bitmap Index Scan on users_status']);
  });
});
