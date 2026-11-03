import { describe, expect, it } from 'vitest';
import { mapSpans, nest, subqueryRanges } from './sqlStructure';

describe('mapSpans', () => {
  it('空白だけが違う SQL の中の位置に直す', () => {
    const raw = 'SELECT *\n    FROM t\n    WHERE a = 1';
    const shown = 'SELECT *\nFROM t\nWHERE a = 1';
    const at = raw.indexOf('a = 1');
    const r = mapSpans(raw, shown, [{ start: at, end: at + 5, kind: 'if', label: '<if>' }]);
    expect(shown.slice(r[0].start, r[0].end)).toBe('a = 1');
  });
  it('空白以外が食い違えば、色を付けない', () => {
    expect(mapSpans('SELECT 1', 'SELECT 2', [{ start: 7, end: 8, kind: 'if', label: '' }])).toEqual([]);
  });
});

describe('subqueryRanges', () => {
  it('括弧の中のサブクエリの範囲（メインのクエリは除く）', () => {
    const sql = 'SELECT * FROM (SELECT id FROM t) s WHERE x IN (SELECT y FROM u)';
    expect(subqueryRanges(sql).map((r) => sql.slice(r.start, r.end))).toEqual(['SELECT id FROM t', 'SELECT y FROM u']);
  });
});

describe('nest', () => {
  it('範囲が同じなら、入れ子の浅いもの（foreach の 1 回分）を外側に、深いもの（その中の if）を内側にする', () => {
    const t = nest([
      { start: 0, end: 1, kind: 'if', label: 'if', depth: 2 },
      { start: 0, end: 1, kind: 'item', label: 'item', depth: 1 },
    ]);
    expect(t.children[0].range!.kind).toBe('item');
    expect(t.children[0].children[0].range!.kind).toBe('if');
  });
});
