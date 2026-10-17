import { describe, expect, it } from 'vitest';
import { fromJson, toJson } from './params';
import type { GlobalSet } from './scope';
import { applicable, combine, originAt, specificity } from './scope';

const set = (name: string, values: string, scope: Partial<GlobalSet['scope']> = {}, enabled = true): GlobalSet => ({
  id: name,
  name,
  values: fromJson(values),
  enabled,
  scope: { all: false, items: [], ...scope },
});

const order = { id: 'shop.order.OrderMapper.search', file: 'src/main/resources/mapper/shop/order/OrderMapper.xml' };
const customer = { id: 'shop.customer.CustomerMapper.search', file: 'src/main/resources/mapper/shop/customer/CustomerMapper.xml' };

describe('specificity', () => {
  it('statement ＞ ファイル ＞ 深いフォルダ ＞ 浅いフォルダ ＞ 全体。当たらなければ null', () => {
    expect(specificity({ all: true, items: [] }, order)).toBe(0);
    const shallow = specificity({ all: false, items: ['src/main/resources/mapper/'] }, order)!;
    const deep = specificity({ all: false, items: ['src/main/resources/mapper/shop/order/'] }, order)!;
    const file = specificity({ all: false, items: [order.file] }, order)!;
    const stmt = specificity({ all: false, items: [order.id] }, order)!;
    expect(0 < shallow && shallow < deep && deep < file && file < stmt).toBe(true);
    expect(specificity({ all: false, items: ['src/main/resources/mapper/shop/order/'] }, customer)).toBeNull();
    // フォルダの名前の途中で一致しても当たらない
    expect(specificity({ all: false, items: ['src/main/resources/mapper/shop/ord/'] }, order)).toBeNull();
  });
});

describe('applicable と combine', () => {
  const base = set('基本', '{"tenantId":1,"limit":20}', { all: true });
  const orders = set('注文の検索', '{"from":"2026-09-01","limit":100}', { items: ['src/main/resources/mapper/shop/order/'] });
  const off = set('オフ', '{"tenantId":3}', { all: true }, false);
  const later = set('後の全体', '{"limit":30}', { all: true });

  it('オンで範囲に当たるものを、範囲の狭いほうを後にして重ねる', () => {
    expect(applicable([orders, base, off], order).map((s) => s.name)).toEqual(['基本', '注文の検索']);
    expect(toJson(combine(applicable([orders, base, off], order)), '')).toBe('{"tenantId":1,"limit":100,"from":"2026-09-01"}');
    expect(toJson(combine(applicable([orders, base, off], customer)), '')).toBe('{"tenantId":1,"limit":20}');
  });

  it('同じ狭さなら一覧で下にあるほうを優先する', () => {
    expect(toJson(combine(applicable([base, later], customer)), '')).toBe('{"tenantId":1,"limit":30}');
    expect(toJson(combine(applicable([later, base], customer)), '')).toBe('{"limit":20,"tenantId":1}');
  });

  it('値を決めている共通パラメータの名前', () => {
    const sets = applicable([orders, base], order);
    expect(originAt(sets, ['limit'])).toBe('注文の検索');
    expect(originAt(sets, ['tenantId'])).toBe('基本');
    expect(originAt(sets, ['nothing'])).toBeNull();
  });
});
