import { describe, expect, it } from 'vitest';
import { fromJson, toJson } from './params';
import type { GlobalSet, RuleState } from './scope';
import { applicable, combine, decide, originAt, placeLabel, placesOf, related } from './scope';

const set = (name: string, values: string, rules: Record<string, RuleState>): GlobalSet => ({ id: name, name, values: fromJson(values), rules });

const recent = { id: 'shop.order.OrderMapper.recent', file: 'm/shop/order/OrderMapper.xml' };
const search = { id: 'shop.customer.CustomerMapper.search', file: 'm/shop/customer/CustomerMapper.xml' };

describe('placesOf と decide', () => {
  it('statement から見て近い順の場所', () => {
    expect(placesOf(recent).map((p) => p.key)).toEqual([
      'shop.order.OrderMapper.recent',
      'm/shop/order/OrderMapper.xml',
      'm/shop/order/',
      'm/shop/',
      'm/',
      '*',
    ]);
  });

  it('一番近い場所の設定で決まり、どこにも設定がなければ null', () => {
    const s = set('A', '{}', { '*': 'on', 'm/shop/order/': 'off', 'shop.order.OrderMapper.recent': 'on' });
    expect(decide(s, recent)).toMatchObject({ state: 'on', place: { kind: 'statement' } });
    expect(decide(s, { id: 'shop.order.OrderMapper.search', file: recent.file })).toMatchObject({ state: 'off', place: { key: 'm/shop/order/' } });
    expect(decide(s, search)).toMatchObject({ state: 'on', place: { kind: 'all' } });
    expect(decide(set('B', '{}', {}), search)).toBeNull();
  });
});

describe('related・applicable・combine', () => {
  const base = set('基本', '{"tenantId":1,"limit":20}', { '*': 'on' });
  const t3 = set('テナント3', '{"tenantId":3}', { '*': 'off' });
  const orders = set('注文の検索', '{"from":"2026-09-01","limit":100}', { 'm/shop/order/': 'on' });
  const later = set('後の全体', '{"limit":30}', { '*': 'on' });

  it('関わるもの（設定があるもの）と、適用されるもの', () => {
    expect(related([base, t3, orders], recent).map((r) => `${r.set.name}:${r.decision.state}`)).toEqual(['基本:on', 'テナント3:off', '注文の検索:on']);
    expect(related([base, t3, orders], search).map((r) => r.set.name)).toEqual(['基本', 'テナント3']);
  });

  it('「適用する」を決めた場所が近いほうを後に重ね、同じなら一覧で下にあるほうを後にする', () => {
    expect(toJson(combine(applicable([orders, base, t3], recent)), '')).toBe('{"tenantId":1,"limit":100,"from":"2026-09-01"}');
    expect(toJson(combine(applicable([base, later], search)), '')).toBe('{"tenantId":1,"limit":30}');
    expect(toJson(combine(applicable([later, base], search)), '')).toBe('{"limit":20,"tenantId":1}');
  });

  it('値を決めている共通パラメータの名前', () => {
    const sets = applicable([orders, base], recent);
    expect(originAt(sets, ['limit'])).toBe('注文の検索');
    expect(originAt(sets, ['tenantId'])).toBe('基本');
    expect(originAt(sets, ['nothing'])).toBeNull();
  });
});

describe('placeLabel', () => {
  it('場所を短く表す', () => {
    expect(placesOf(recent).map(placeLabel)).toEqual(['recent', 'OrderMapper.xml', 'order/', 'shop/', 'm/', '全体']);
  });
});
