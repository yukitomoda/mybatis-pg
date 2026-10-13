import { describe, expect, it } from 'vitest';
import type { ParamSchema, PValue } from './params';
import { defaultType, fromJson, getAt, invalidNumbers, missingParams, setAt, toJson } from './params';
import { tidySql } from './sql';

const node = (name: string, extra: Partial<ParamSchema> = {}): ParamSchema => ({
  name,
  usages: [],
  typeHints: [],
  properties: [],
  element: null,
  ...extra,
});

describe('JSON の変換', () => {
  it('数値を文字列のまま保ち、精度を落とさない', () => {
    const text = '{"big":12345678901234567890,"dec":0.10,"exp":1.5E+2}';
    const v = fromJson(text);
    expect(v).toEqual({
      t: 'object',
      fields: [
        ['big', { t: 'number', v: '12345678901234567890' }],
        ['dec', { t: 'number', v: '0.10' }],
        ['exp', { t: 'number', v: '1.5E+2' }],
      ],
    });
    expect(toJson(v, '')).toBe(text);
  });

  it('項目の順序とネストを保って往復できる', () => {
    const text = '{"z":"a\\"b","a":[1,true,null,{"x":"日本語"}],"e":{},"l":[]}';
    expect(toJson(fromJson(text), '')).toBe(text);
  });

  it('不正な JSON は位置付きのエラーになる', () => {
    expect(() => fromJson('{"a":}')).toThrow(/値が必要です（6 文字目）/);
    expect(() => fromJson('{"a":1} x')).toThrow(/余分な文字/);
    expect(() => fromJson('[01]')).toThrow();
  });
});

describe('木の操作', () => {
  it('子に値を入れると途中のオブジェクトやリストを作る', () => {
    let v = setAt(undefined, ['cond', 'name'], { t: 'string', v: 'taro' });
    v = setAt(v, ['ids', 1], { t: 'number', v: '2' });
    expect(toJson(v, '')).toBe('{"cond":{"name":"taro"},"ids":[null,2]}');
    expect(getAt(v, ['cond', 'name'])).toEqual({ t: 'string', v: 'taro' });
  });

  it('undefined を入れると削除する', () => {
    const v = fromJson('{"a":1,"b":[1,2,3]}');
    expect(toJson(setAt(setAt(v, ['a'], undefined), ['b', 1], undefined), '')).toBe('{"b":[1,3]}');
  });
});

describe('検査', () => {
  it('数値として不正な入力を位置付きで返す', () => {
    const v: PValue = { t: 'object', fields: [['ids', { t: 'list', items: [{ t: 'number', v: '1' }, { t: 'number', v: '1,000' }] }]] };
    expect(invalidNumbers(v)).toEqual([{ path: 'ids[1]', message: '数値として解釈できません: "1,000"' }]);
  });

  it('参照されているのに値がない項目を返す', () => {
    const schema = node('', {
      properties: [
        node('id', { usages: ['BIND'] }),
        node('cond', { properties: [node('name', { usages: ['CONDITION'] }), node('status', { usages: ['BIND'] })] }),
        node('users', { usages: ['ITERATE'], element: node('[]', { properties: [node('id', { usages: ['BIND'] })] }) }),
      ],
    });
    const v = fromJson('{"cond":{"name":"a"},"users":[{"id":1},{}]}');
    expect(missingParams(schema, v)).toEqual(['id', 'cond.status', 'users[1].id']);
  });

  it('型の初期値は木の形と推測から決める', () => {
    expect(defaultType(node('a', { element: node('[]') }))).toBe('list');
    expect(defaultType(node('a', { properties: [node('b')] }))).toBe('object');
    expect(defaultType(node('a', { typeHints: ['NUMBER'] }))).toBe('number');
    expect(defaultType(node('a', { typeHints: ['BOOLEAN'] }))).toBe('boolean');
    expect(defaultType(node('a'))).toBe('string');
  });
});

describe('SQL の整形', () => {
  it('空白だけの行を除き、共通の字下げを外す', () => {
    const sql = 'SELECT  id \n    FROM users\n     WHERE  a = 1\n      \n      \n    ORDER BY id';
    expect(tidySql(sql)).toBe('SELECT  id\nFROM users\n WHERE  a = 1\nORDER BY id');
  });
});
