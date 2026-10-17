import { describe, expect, it } from 'vitest';
import { scopeTree } from './Globals';
import { fromJson } from './params';
import type { GlobalSet } from './scope';

const set = (name: string, all: boolean, items: string[] = []): GlobalSet => ({ id: name, name, values: fromJson('{}'), enabled: true, scope: { all, items } });

const statements = [
  { id: 'shop.order.OrderMapper.search', file: 'm/shop/order/OrderMapper.xml' },
  { id: 'shop.order.OrderMapper.recent', file: 'm/shop/order/OrderMapper.xml' },
  { id: 'shop.customer.CustomerMapper.search', file: 'm/shop/customer/CustomerMapper.xml' },
];

/** 字下げ付きの行にする */
function show(nodes: ReturnType<typeof scopeTree>, indent = ''): string[] {
  return nodes.flatMap((n) => [`${indent}${n.label} [${n.sets.map((s) => s.name).join(', ')}]`, ...show(n.children, indent + '  ')]);
}

describe('scopeTree', () => {
  it('全体を一番外に、狭い範囲ほど内側に置き、共通パラメータのない枝は省く', () => {
    const sets = [
      set('基本', true),
      set('注文', false, ['m/shop/order/']),
      set('検索だけ', false, ['shop.order.OrderMapper.search', 'shop.customer.CustomerMapper.search']),
      set('テナント3', true),
      set('消えた範囲', false, ['m/old/']),
      set('どこにも', false, []),
    ];
    expect(show(scopeTree(sets, statements))).toEqual([
      '全体 [基本, テナント3]',
      '  m/shop/ []',
      '    customer/CustomerMapper.xml []',
      '      search [検索だけ]',
      '    order/ [注文]',
      '      OrderMapper.xml []',
      '        search [検索だけ]',
      'm/old/ [消えた範囲]',
      '適用する範囲なし [どこにも]',
    ]);
  });
});
