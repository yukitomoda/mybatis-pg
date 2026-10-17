import { describe, expect, it } from 'vitest';
import { fromJson, toJson } from './params';
import type { GlobalSet } from './scope';
import { applicable, combine, matchPlace, orderAt, originAt, placeLabel, placesOf, related } from './scope';

const set = (name: string, values: string, scope: string[], excludes: string[] = []): GlobalSet => ({ id: name, name, values: fromJson(values), scope, excludes });

const recent = { id: 'shop.order.OrderMapper.recent', file: 'm/shop/order/OrderMapper.xml' };
const search = { id: 'shop.customer.CustomerMapper.search', file: 'm/shop/customer/CustomerMapper.xml' };

describe('placesOf と matchPlace', () => {
  it('statement を含む場所を狭い順に並べ、スコープのうち一番狭い場所で当てる', () => {
    expect(placesOf(recent).map((p) => p.key)).toEqual(['shop.order.OrderMapper.recent', 'm/shop/order/OrderMapper.xml', 'm/shop/order/', 'm/shop/', 'm/', '*']);
    expect(placesOf(recent).map(placeLabel)).toEqual(['recent', 'OrderMapper.xml', 'order/', 'shop/', 'm/', '全体']);
    expect(matchPlace(set('A', '{}', ['*', 'm/shop/order/']), recent)?.key).toBe('m/shop/order/');
    expect(matchPlace(set('A', '{}', ['*', 'm/shop/order/']), search)?.key).toBe('*');
    expect(matchPlace(set('A', '{}', ['m/shop/ord/']), recent)).toBeNull();
  });
});

describe('related と applicable', () => {
  const base = set('基本', '{"tenantId":1,"limit":20}', ['*']);
  const t3 = set('テナント3', '{"tenantId":3}', ['*'], ['shop.order.OrderMapper.recent']);
  const orders = set('注文の検索', '{"from":"2026-09-01","limit":100}', ['m/shop/order/']);

  it('スコープに入っているもの（適用しないものも含む）を、狭いスコープほど後に並べる', () => {
    const r = related([orders, base, t3], {}, recent);
    expect(r.map((m) => `${m.set.name}:${m.place.key}:${m.excluded ? '適用しない' : '適用'}`)).toEqual([
      '基本:*:適用',
      'テナント3:*:適用しない',
      '注文の検索:m/shop/order/:適用',
    ]);
    expect(toJson(combine(applicable([orders, base, t3], {}, recent)), '')).toBe('{"tenantId":1,"limit":100,"from":"2026-09-01"}');
  });

  it('同じ場所なら、その場所の適用順序で下にあるほうを優先する', () => {
    expect(toJson(combine(applicable([base, t3], {}, search)), '')).toBe('{"tenantId":3,"limit":20}');
    expect(toJson(combine(applicable([base, t3], { '*': ['テナント3', '基本'] }, search)), '')).toBe('{"tenantId":1,"limit":20}');
  });

  it('順序に入っていないものは最後に置く', () => {
    expect(orderAt([base, t3, orders], { '*': ['テナント3'] }, '*').map((s) => s.name)).toEqual(['テナント3', '基本']);
  });

  it('値を決めている共通パラメータの名前', () => {
    const sets = applicable([orders, base], {}, recent);
    expect(originAt(sets, ['limit'])).toBe('注文の検索');
    expect(originAt(sets, ['tenantId'])).toBe('基本');
    expect(originAt(sets, ['nothing'])).toBeNull();
  });
});
