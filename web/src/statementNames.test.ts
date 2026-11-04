import { describe, expect, it } from 'vitest';
import { makeShortener } from './statementNames';

describe('makeShortener', () => {
  it('Mapper の名前と statement の名前にし、同じ名前の Mapper があるときだけ名前空間をさかのぼる', () => {
    const short = makeShortener([
      'com.foo.order.UserMapper.find',
      'com.foo.admin.UserMapper.find',
      'com.foo.bar.baz.MyCustomMapper.mySql',
      'erp.OrgMapper.deptTree',
    ]);
    expect(short('com.foo.bar.baz.MyCustomMapper.mySql')).toBe('MyCustomMapper.mySql');
    expect(short('com.foo.order.UserMapper.find')).toBe('order.UserMapper.find');
    expect(short('com.foo.admin.UserMapper.find')).toBe('admin.UserMapper.find');
    expect(short('erp.OrgMapper.deptTree')).toBe('OrgMapper.deptTree');
    // ワークスペースにない名前空間も短くする
    expect(short('x.y.OtherMapper.q')).toBe('OtherMapper.q');
    // 名前空間のない id
    expect(short('find')).toBe('find');
  });
});
