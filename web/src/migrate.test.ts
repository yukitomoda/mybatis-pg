import { describe, expect, it } from 'vitest';
import { migrateStorage } from './migrate';

/** テスト用の Storage */
function memoryStorage(init: Record<string, string>): Storage {
  const m = new Map(Object.entries(init));
  return {
    get length() {
      return m.size;
    },
    key: (i: number) => [...m.keys()][i] ?? null,
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => void m.set(k, v),
    removeItem: (k: string) => void m.delete(k),
    clear: () => m.clear(),
  };
}

describe('migrateStorage', () => {
  it('新しい名前のキーがないものだけを写し、古いキーは残す', () => {
    const s = memoryStorage({
      'mybatis-xml-editor.params:a.b': '{"x":1}',
      'mybatis-xml-editor.tab': 'sql',
      'mybatis-pg.tab': 'execute',
      other: 'keep',
    });
    migrateStorage(s);
    expect(s.getItem('mybatis-pg.params:a.b')).toBe('{"x":1}');
    expect(s.getItem('mybatis-pg.tab')).toBe('execute');
    expect(s.getItem('mybatis-xml-editor.tab')).toBe('sql');
    expect(s.length).toBe(5);
  });
});
