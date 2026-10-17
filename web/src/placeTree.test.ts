import { describe, expect, it } from 'vitest';
import type { PlaceNode } from './Globals';
import { placeTree } from './Globals';

const statements = [
  { id: 'shop.order.OrderMapper.search', file: 'src/main/mapper/shop/order/OrderMapper.xml' },
  { id: 'shop.order.OrderMapper.recent', file: 'src/main/mapper/shop/order/OrderMapper.xml' },
  { id: 'shop.customer.CustomerMapper.search', file: 'src/main/mapper/shop/customer/CustomerMapper.xml' },
];

/** 字下げ付きの行にする */
function show(n: PlaceNode, indent = ''): string[] {
  return [`${indent}${n.label}`, ...n.children.flatMap((c) => show(c, indent + '  '))];
}

describe('placeTree', () => {
  it('全体を一番上に置き、フォルダ 1 つだけのフォルダはまとめる', () => {
    expect(show(placeTree(statements, new Set()))).toEqual([
      '全体',
      '  src/main/mapper/shop',
      '    customer',
      '      CustomerMapper.xml',
      '        search',
      '    order',
      '      OrderMapper.xml',
      '        recent',
      '        search',
    ]);
  });

  it('設定のある場所はまとめずに残す', () => {
    const tree = placeTree(statements, new Set(['src/main/']));
    expect(show(tree).slice(0, 3)).toEqual(['全体', '  src/main', '    mapper/shop']);
    expect(tree.children[0].key).toBe('src/main/');
  });
});
