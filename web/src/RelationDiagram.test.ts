import { describe, expect, it } from 'vitest';
import type { RelationLink } from './api';
import { tableEdges } from './RelationDiagram';

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
  it('列のつながりを、表の組ごとにまとめる（無効と、同じ表の中のものは除く）', () => {
    const edges = tableEdges([
      link('app.comment.category_id', 'app.post.category_id', 'parent', ['m.a', 'm.b']),
      link('app.comment.post_id', 'app.post.post_id', 'parent', ['m.a']),
      link('app.post.category_id', 'app.category.id', 'parent', [], { manual: true }),
      link('app.x.id', 'app.y.id', 'sibling', ['m.c'], { disabled: true }),
      link('app.t.a', 'app.t.b', 'sibling', ['m.d']),
      link('app.z.k', 'app.b.k', 'sibling', ['m.e']),
    ]);
    expect(edges.map((e) => [e.kind, e.from, e.to, e.links.length, e.statements, e.manual])).toEqual([
      ['parent', 'app.comment', 'app.post', 2, 2, false],
      ['parent', 'app.post', 'app.category', 1, 0, true],
      // 兄弟は名前の小さいほうを from に
      ['sibling', 'app.b', 'app.z', 1, 1, false],
    ]);
  });
});
