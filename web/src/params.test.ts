import { describe, expect, it } from 'vitest';
import type { ParamSchema, PValue } from './params';
import { defaultType, fromJson, getAt, invalidNumbers, missingParams, inheritedAt, mergeValues, overrideAt, setAt, toJson, withListDefaults } from './params';
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

describe('withListDefaults', () => {
  const ids = node('ids', { usages: ['ITERATE'], element: node(null as unknown as string) });
  const tags = node('tags', { usages: ['ITERATE'], element: node(null as unknown as string) });
  const users = node('users', { usages: ['ITERATE'], element: node(null as unknown as string, { properties: [tags] }) });
  const cond = node('cond', { properties: [node('ids2', { usages: ['ITERATE'] })] });
  const root = node(null as unknown as string, { properties: [ids, cond, users] });

  it('一番外側の foreach の項目を空のリストにする。未設定のオブジェクトの中には作らない', () => {
    expect(toJson(withListDefaults(root, undefined), '')).toBe('{"ids":[],"users":[]}');
  });

  it('値のあるオブジェクトやリストの要素の中にも入れ、設定済みの値は変えない', () => {
    const v = fromJson('{"ids":null,"cond":{},"users":[{},{"tags":[1]}]}');
    expect(toJson(withListDefaults(root, v), '')).toBe('{"ids":null,"cond":{"ids2":[]},"users":[{"tags":[]},{"tags":[1]}]}');
  });

  it('変える必要がなければ同じ値を返す', () => {
    const v = fromJson('{"ids":[1],"users":[]}');
    expect(withListDefaults(root, v)).toBe(v);
  });
});

describe('mergeValues', () => {
  const merge = (base: string | null, override: string | null) =>
    toJson(mergeValues(base === null ? undefined : fromJson(base), override === null ? undefined : fromJson(override)), '');

  it('オブジェクトは項目ごとに重ね、個別の値を優先する', () => {
    expect(merge('{"tenantId":100,"cond":{"status":1}}', '{"cond":{"name":"taro"}}')).toBe(
      '{"tenantId":100,"cond":{"status":1,"name":"taro"}}',
    );
    expect(merge('{"a":1,"b":2}', '{"b":3,"c":4}')).toBe('{"a":1,"b":3,"c":4}');
  });

  it('リストや NULL は丸ごと置き換える', () => {
    expect(merge('{"ids":[1,2],"x":{"y":1}}', '{"ids":[3],"x":null}')).toBe('{"ids":[3],"x":null}');
  });

  it('どちらかがなければ、もう一方を使う', () => {
    expect(merge(null, '{"a":1}')).toBe('{"a":1}');
    expect(merge('{"a":1}', null)).toBe('{"a":1}');
    expect(merge(null, null)).toBe('null');
  });
});

describe('overrideAt と inheritedAt', () => {
  const effective = fromJson('{"tenantId":1,"ids":[1,2],"cond":{"status":1}}');

  it('共通のオブジェクトの中は、その項目だけを個別の値にする', () => {
    expect(toJson(overrideAt(undefined, effective, ['cond', 'name'], { t: 'string', v: 'a' }), '')).toBe('{"cond":{"name":"a"}}');
  });

  it('共通のリストの中を変えるときは、リストを写してから変える', () => {
    expect(toJson(overrideAt(undefined, effective, ['ids', 1], { t: 'number', v: '5' }), '')).toBe('{"ids":[1,5]}');
    expect(toJson(overrideAt(undefined, effective, ['ids', 2], { t: 'number', v: '3' }), '')).toBe('{"ids":[1,2,3]}');
    expect(toJson(overrideAt(undefined, effective, ['ids', 0], undefined), '')).toBe('{"ids":[2]}');
    // 個別のリストがあれば、それを変える
    expect(toJson(overrideAt(fromJson('{"ids":[9]}'), effective, ['ids', 0], undefined), '')).toBe('{"ids":[]}');
  });

  it('個別の値を消したときに来る値', () => {
    expect(inheritedAt(undefined, effective, ['cond', 'status'])).toEqual({ t: 'number', v: '1' });
    // 個別で cond を NULL にしていれば、その下は重ならない
    expect(inheritedAt(fromJson('{"cond":null}'), effective, ['cond', 'status'])).toBeUndefined();
    expect(inheritedAt(fromJson('{"cond":{}}'), effective, ['cond', 'status'])).toEqual({ t: 'number', v: '1' });
  });
});
