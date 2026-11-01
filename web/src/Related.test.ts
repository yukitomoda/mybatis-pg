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
  const rowsOf = (nodes: RelatedNode[]) => Object.fromEntries(placeNodes(nodes).placed.map((p) => [p.node.id, [p.col, p.row]]));

  it('起点を 0 列に、親を左、子を右に置く', () => {
    const nodes = [
      node('start', null, 'start'),
      node('dept', 'start', 'parent'),
      node('pos', 'start', 'parent'),
      node('items', 'start', 'child'),
    ];
    expect(rowsOf(nodes)).toEqual({ start: [0, 0], dept: [-1, 0], pos: [-1, 1], items: [1, 0] });
  });

  it('あとからたどった箱は元の箱のそばに入り、下の箱は下へずれる（並び順は保つ）', () => {
    // 起点の親: 上司（mgr）と部署（dept）。上司の親: 上司の部署（mgrDept）
    const nodes = [
      node('start', null, 'start'),
      node('mgr', 'start', 'parent'),
      node('dept', 'start', 'parent'),
      node('mgrDept', 'mgr', 'parent'),
    ];
    expect(rowsOf(nodes)).toEqual({ start: [0, 0], mgr: [-1, 0], dept: [-1, 1], mgrDept: [-2, 0] });
    // 上司の親（上司の役職）をあとからたどると、上司の部署の下に入り、部署（dept）が下へずれる
    const more = [...nodes, node('mgrPos', 'mgr', 'parent')];
    expect(rowsOf(more)).toEqual({ start: [0, 0], mgr: [-1, 0], dept: [-1, 2], mgrDept: [-2, 0], mgrPos: [-2, 1] });
  });

  it('親の箱から子をたどると、元へ戻る列でも、ほかの箱と重ならない', () => {
    const nodes = [node('start', null, 'start'), node('dept', 'start', 'parent'), node('emps', 'dept', 'child')];
    expect(rowsOf(nodes)).toEqual({ start: [0, 0], dept: [-1, 0], emps: [0, 1] });
  });
});
