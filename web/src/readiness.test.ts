import { describe, expect, it } from 'vitest';
import type { GlobalSet } from './globalModel';
import type { ParamSchema } from './params';
import { fromJson } from './params';
import { isReady, problemLabel, readiness } from './readiness';

const prop = (name: string): ParamSchema => ({ name, usages: ['BIND'], typeHints: [], properties: [], element: null });
const schema: ParamSchema = { name: null, usages: [], typeHints: [], properties: [prop('tenantId'), prop('since')], element: null };
const set = (values: string): GlobalSet => ({ id: 'g', name: 'g', isDefault: true, values: fromJson(values) });

describe('readiness', () => {
  it('値がない項目があれば実行できない', () => {
    const r = readiness('x.never.saved', schema, [set('{"tenantId":1}')], {});
    expect(r.missing).toEqual(['since']);
    expect(isReady(r)).toBe(false);
    expect(problemLabel(r).label).toBe('未設定 1');
  });

  it('共通パラメータで値がそろえば実行できる', () => {
    expect(isReady(readiness('x.never.saved', schema, [set('{"tenantId":1,"since":"2026-01-01"}')], {}))).toBe(true);
    // その statement で外していれば、そろわない
    expect(isReady(readiness('x.never.saved', schema, [set('{"tenantId":1,"since":"2026-01-01"}')], { 'x.never.saved': { added: [], removed: ['g'] } }))).toBe(
      false,
    );
  });

  it('foreach で回すリストが空なら実行できない', () => {
    const list: ParamSchema = { ...prop('ids'), usages: ['ITERATE'], element: prop('') };
    const withList: ParamSchema = { ...schema, properties: [list] };
    expect(readiness('x.never.saved', withList, [], {}).missing).toEqual(['ids（空のリスト）']);
    expect(isReady(readiness('x.never.saved', withList, [set('{"ids":[1,2]}')], {}))).toBe(true);
  });
});
