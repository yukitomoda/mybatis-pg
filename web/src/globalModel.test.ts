import { describe, expect, it } from 'vitest';
import type { GlobalSet } from './globalModel';
import { addTo, applicable, combine, customized, moved, originAt, removeFrom } from './globalModel';
import { fromJson, toJson } from './params';

const set = (name: string, isDefault: boolean, values: string): GlobalSet => ({ id: name, name, isDefault, values: fromJson(values) });
const base = set('基本', true, '{"tenantId":1,"limit":20}');
const t3 = set('テナント3', false, '{"tenantId":3}');
const orders = set('注文の検索', true, '{"from":"2026-09-01","limit":100}');
const sets = [base, t3, orders];
const names = (l: GlobalSet[]) => l.map((s) => s.name);

describe('applicable', () => {
  it('設定がなければ、既定で入るものを一覧の順に（下ほど優先）', () => {
    expect(names(applicable(sets, undefined))).toEqual(['基本', '注文の検索']);
    expect(toJson(combine(applicable(sets, undefined)), '')).toBe('{"tenantId":1,"limit":100,"from":"2026-09-01"}');
  });

  it('追加すると一番下に入り、外すとなくなる', () => {
    const added = addTo(undefined, t3, applicable(sets, undefined));
    expect(names(applicable(sets, added))).toEqual(['基本', '注文の検索', 'テナント3']);
    expect(toJson(combine(applicable(sets, added)), '')).toBe('{"tenantId":3,"limit":100,"from":"2026-09-01"}');
    const removed = removeFrom(removeFrom(added, t3), base);
    expect(removed).toEqual({ added: [], removed: ['基本'], order: ['注文の検索'] });
    expect(names(applicable(sets, removed))).toEqual(['注文の検索']);
    // 既定で入るものを外してから追加し直す
    expect(names(applicable(sets, addTo(removed, base, applicable(sets, removed))))).toEqual(['注文の検索', '基本']);
    expect(customized(undefined)).toBe(false);
    expect(customized(removed)).toBe(true);
  });

  it('並べ替えた順を使い、その順にないものは最後に足す', () => {
    expect(names(applicable(sets, { added: ['テナント3'], removed: [], order: ['注文の検索', '基本'] }))).toEqual([
      '注文の検索',
      '基本',
      'テナント3',
    ]);
    expect(moved(['a', 'b', 'c'], 0, 2)).toEqual(['b', 'c', 'a']);
    expect(moved(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b']);
  });

  it('値を決めている共通パラメータの名前', () => {
    const applied = applicable(sets, undefined);
    expect(originAt(applied, ['limit'])).toBe('注文の検索');
    expect(originAt(applied, ['tenantId'])).toBe('基本');
    expect(originAt(applied, ['nothing'])).toBeNull();
  });
});
