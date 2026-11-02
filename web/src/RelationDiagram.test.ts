import { describe, expect, it } from 'vitest';
import type { RelationLink } from './api';
import { neighborhood, tableEdges } from './RelationDiagram';

const link = (a: string, b: string, kind: RelationLink['kind'], statements: string[], extra: Partial<RelationLink> = {}): RelationLink => ({
  a,
  b,
  kind,
  statements,
  manual: false,
  disabled: false,
  ...extra,
});

describe('tableEdges', () => {
  it('列のつながりを、表の組ごとにまとめる（無効と、同じ表の中の兄弟は除く）', () => {
    const edges = tableEdges([
      link('app.comment.category_id', 'app.post.category_id', 'parent', ['m.a', 'm.b']),
      link('app.comment.post_id', 'app.post.post_id', 'parent', ['m.a']),
      link('app.post.category_id', 'app.category.id', 'parent', [], { manual: true }),
      link('app.x.id', 'app.y.id', 'sibling', ['m.c'], { disabled: true }),
      link('app.t.a', 'app.t.b', 'sibling', ['m.d']),
      link('app.z.k', 'app.b.k', 'sibling', ['m.e']),
      // 自己参照の親子は残す
      link('app.emp.manager_id', 'app.emp.id', 'parent', ['m.f']),
    ]);
    expect(edges.map((e) => [e.kind, e.from, e.to, e.links.length, e.statements, e.manual])).toEqual([
      ['parent', 'app.comment', 'app.post', 2, 2, false],
      ['parent', 'app.post', 'app.category', 1, 0, true],
      // 兄弟は名前の小さいほうを from に
      ['sibling', 'app.b', 'app.z', 1, 1, false],
      ['parent', 'app.emp', 'app.emp', 1, 1, false],
    ]);
  });
});

describe('neighborhood', () => {
  it('親の方向・子の方向に段数まで、兄弟と、中心の子のほかの親（段数が 2 以上のとき）を選ぶ', () => {
    const edges = tableEdges([
      link('s.order_items.order_id', 's.orders.id', 'parent', ['m']),
      link('s.order_items.product_id', 's.products.id', 'parent', ['m']),
      link('s.orders.customer_id', 's.customers.id', 'parent', ['m']),
      link('s.customers.tenant_id', 's.tenants.id', 'parent', ['m']),
      link('s.orders.customer_id', 's.points.customer_id', 'sibling', ['m']),
      link('s.orders.manager_id', 's.orders.id', 'parent', ['m']),
    ]);
    expect([...neighborhood('s.orders', edges, 1, true).shown].sort()).toEqual(['s.customers', 's.order_items', 's.orders', 's.points']);
    expect([...neighborhood('s.orders', edges, 2, false).shown].sort()).toEqual([
      's.customers',
      's.order_items',
      's.orders',
      's.products',
      's.tenants',
    ]);
  });
});
