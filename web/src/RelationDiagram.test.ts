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
  it('親等のように、向きを問わず、つながりをたどった回数で段数を数える', () => {
    const edges = tableEdges([
      link('s.order_items.order_id', 's.orders.id', 'parent', ['m']),
      link('s.order_items.product_id', 's.products.id', 'parent', ['m']),
      link('s.orders.customer_id', 's.customers.id', 'parent', ['m']),
      link('s.customers.tenant_id', 's.tenants.id', 'parent', ['m']),
      link('s.invoices.customer_id', 's.customers.id', 'parent', ['m']),
      link('s.orders.customer_id', 's.points.customer_id', 'sibling', ['m']),
      link('s.orders.manager_id', 's.orders.id', 'parent', ['m']),
    ]);
    // 1 段: 親・子と、兄弟
    expect([...neighborhood('s.orders', edges, 1, { sibling: true, oneToOne: true }).shown].sort()).toEqual([
      's.customers',
      's.order_items',
      's.orders',
      's.points',
    ]);
    // 2 段: 親の親（tenants）、子のほかの親（products）、親のほかの子（invoices）。兄弟はたどらない
    expect([...neighborhood('s.orders', edges, 2, { sibling: false, oneToOne: false }).shown].sort()).toEqual([
      's.customers',
      's.invoices',
      's.order_items',
      's.orders',
      's.products',
      's.tenants',
    ]);
  });
  it('兄弟と 1 対 1 を、別々に出し入れする', () => {
    const edges = tableEdges([
      link('s.orders.customer_id', 's.points.customer_id', 'sibling', ['m']),
      link('s.orders.id', 's.order_notes.order_id', 'one-to-one', ['m']),
    ]);
    expect([...neighborhood('s.orders', edges, 1, { sibling: false, oneToOne: true }).shown].sort()).toEqual(['s.order_notes', 's.orders']);
    expect([...neighborhood('s.orders', edges, 1, { sibling: true, oneToOne: false }).shown].sort()).toEqual(['s.orders', 's.points']);
  });
});
