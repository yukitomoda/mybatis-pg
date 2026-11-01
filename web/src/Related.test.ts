import { describe, expect, it } from 'vitest';
import type { RelatedNode } from './Related';
import { placeNodes } from './Related';

const rows = { columns: [], columnTypes: [], rows: [], truncated: false, total: 0, pk: [] };
const node = (id: string, parent: string | null, direction: RelatedNode['direction']): RelatedNode => ({
  id,
  table: `app.${id}`,
  parent,
  direction,
  step: null,
  rows,
});

describe('placeNodes', () => {
  it('起点を 0 列に、親を左、子を右に置き、たどってもすでにある箱は動かない', () => {
    const nodes = [
      node('start', null, 'start'),
      node('dept', 'start', 'parent'),
      node('pos', 'start', 'parent'),
      node('items', 'start', 'child'),
    ];
    const before = placeNodes(nodes).placed.map((p) => [p.node.id, p.col, p.row]);
    expect(before).toEqual([
      ['start', 0, 0],
      ['dept', -1, 0],
      ['pos', -1, 1],
      ['items', 1, 0],
    ]);
    // dept から子をたどる（起点と同じ列。空いている行に置く）
    const after = placeNodes([...nodes, node('emps', 'dept', 'child')]);
    expect(after.placed.slice(0, 4).map((p) => [p.node.id, p.col, p.row])).toEqual(before);
    expect(after.placed.slice(0, 4).map((p) => [p.x, p.y])).toEqual(placeNodes(nodes).placed.map((p) => [p.x, p.y]));
    expect(after.placed[4].col).toBe(0);
    expect(after.placed[4].row).toBe(1);
  });
});
