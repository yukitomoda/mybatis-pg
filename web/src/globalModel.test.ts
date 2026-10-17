import { describe, expect, it } from 'vitest';
import type { GlobalSet } from './globalModel';
import { applicable, combine, entries, originAt } from './globalModel';
import { fromJson, toJson } from './params';

const set = (name: string, values: string): GlobalSet => ({ id: name, name, values: fromJson(values) });
const base = set('基本', '{"tenantId":1,"limit":20}');
const t3 = set('テナント3', '{"tenantId":3}');
const orders = set('注文の検索', '{"from":"2026-09-01","limit":100}');
const sets = [base, t3, orders];

describe('entries と applicable', () => {
  it('設定がなければ、一覧の順ですべてに適用する（下ほど優先）', () => {
    expect(entries(sets, undefined).map((e) => e.set.name)).toEqual(['基本', 'テナント3', '注文の検索']);
    expect(toJson(combine(applicable(sets, undefined)), '')).toBe('{"tenantId":3,"limit":100,"from":"2026-09-01"}');
  });

  it('無効にしたものは適用しない', () => {
    expect(toJson(combine(applicable(sets, { disabled: ['テナント3'] })), '')).toBe('{"tenantId":1,"limit":100,"from":"2026-09-01"}');
    expect(entries(sets, { disabled: ['テナント3'] }).map((e) => e.disabled)).toEqual([false, true, false]);
  });

  it('入れ替えた順番を使い、その順にないものは最後に足す', () => {
    expect(entries(sets, { disabled: [], order: ['注文の検索', '基本'] }).map((e) => e.set.name)).toEqual(['注文の検索', '基本', 'テナント3']);
    expect(toJson(combine(applicable(sets, { disabled: ['テナント3'], order: ['注文の検索', '基本'] })), '')).toBe(
      '{"from":"2026-09-01","limit":20,"tenantId":1}',
    );
  });

  it('値を決めている共通パラメータの名前', () => {
    const applied = applicable(sets, { disabled: ['テナント3'] });
    expect(originAt(applied, ['limit'])).toBe('注文の検索');
    expect(originAt(applied, ['tenantId'])).toBe('基本');
    expect(originAt(applied, ['nothing'])).toBeNull();
  });
});
