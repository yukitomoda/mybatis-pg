import { describe, expect, it } from 'vitest';
import type { TreeNode } from './tree';
import { buildTree, filterEntries, pathTo } from './tree';

/** 見やすい形（字下げ付きの行）にする */
function show(nodes: TreeNode[], indent = ''): string[] {
  return nodes.flatMap((n) =>
    n.kind === 'dir'
      ? [`${indent}${n.label}/`, ...show(n.children, indent + '  ')]
      : [`${indent}${n.label}: ${n.statements.map((s) => s.id).join(', ')}`],
  );
}

const entries = [
  { id: 'a.User.find', file: 'src/main/resources/mapper/user/UserMapper.xml' },
  { id: 'a.User.count', file: 'src/main/resources/mapper/user/UserMapper.xml' },
  { id: 'a.Order.find', file: 'src/main/resources/mapper/order/OrderMapper.xml' },
  { id: 'a.Item.find', file: 'src/main/resources/mapper/ItemMapper.xml' },
];

describe('buildTree', () => {
  it('子が 1 つのディレクトリはまとめ、ディレクトリ → ファイルの順に並べる', () => {
    expect(show(buildTree(entries))).toEqual([
      'src/main/resources/mapper/',
      '  order/OrderMapper.xml: a.Order.find',
      '  user/UserMapper.xml: a.User.count, a.User.find',
      '  ItemMapper.xml: a.Item.find',
    ]);
  });

  it('ディレクトリが分かれていれば、分かれ目まででまとめる', () => {
    const tree = buildTree([
      { id: 'x.a', file: 'src/main/java/com/em/maps/api/service/A.xml' },
      { id: 'x.b', file: 'src/main/java/com/em/maps/api/service/B.xml' },
      { id: 'y.c', file: 'src/main/resources/C.xml' },
    ]);
    expect(show(tree)).toEqual([
      'src/main/',
      '  java/com/em/maps/api/service/',
      '    A.xml: x.a',
      '    B.xml: x.b',
      '  resources/C.xml: y.c',
    ]);
    // key はまとめた後も、最も深いディレクトリのパス
    expect(tree[0].key).toBe('src/main/');
  });

  it('絶対パスや、直下のファイルも扱える', () => {
    expect(
      show(
        buildTree([
          { id: 'p.a', file: '/opt/m/A.xml' },
          { id: 'p.b', file: '/opt/n/B.xml' },
        ]),
      ),
    ).toEqual(['/opt/', '  m/A.xml: p.a', '  n/B.xml: p.b']);
    expect(show(buildTree([{ id: 'p.a', file: 'A.xml' }]))).toEqual(['A.xml: p.a']);
  });
});

describe('pathTo と filterEntries', () => {
  it('statement までの項目の key', () => {
    expect(pathTo(buildTree(entries), 'a.User.find')).toEqual([
      'src/main/resources/mapper/',
      'src/main/resources/mapper/user/UserMapper.xml',
    ]);
    expect(pathTo(buildTree(entries), 'no.such')).toEqual([]);
  });

  it('id かファイルのパスで絞り込む', () => {
    expect(filterEntries(entries, 'COUNT').map((e) => e.id)).toEqual(['a.User.count']);
    expect(filterEntries(entries, 'order/').map((e) => e.id)).toEqual(['a.Order.find']);
    expect(filterEntries(entries, ' ')).toBe(entries);
  });
});
