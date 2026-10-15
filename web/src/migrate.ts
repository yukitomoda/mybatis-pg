// 改名（mybatis-xml-editor → mybatis-pg）の前にブラウザへ保存した内容を引き継ぐ

const OLD_PREFIX = 'mybatis-xml-editor.';
const NEW_PREFIX = 'mybatis-pg.';

/** 新しい名前のキーがないものだけを写す。古いキーは残す */
export function migrateStorage(storage: Storage) {
  const keys: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const k = storage.key(i);
    if (k && k.startsWith(OLD_PREFIX)) keys.push(k);
  }
  for (const k of keys) {
    const next = NEW_PREFIX + k.slice(OLD_PREFIX.length);
    const v = storage.getItem(k);
    if (storage.getItem(next) === null && v !== null) storage.setItem(next, v);
  }
}
