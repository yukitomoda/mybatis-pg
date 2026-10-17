// statement ごとの個別の値の保存（ブラウザ）
import type { PValue } from './params';
import { fromJson, toJson } from './params';

const STORAGE_PREFIX = 'mybatis-pg.params:';

export function loadParams(id: string): PValue | undefined {
  try {
    const text = localStorage.getItem(STORAGE_PREFIX + id);
    return text ? fromJson(text) : undefined;
  } catch {
    return undefined;
  }
}

export function saveParams(id: string, value: PValue | undefined) {
  try {
    if (value === undefined) localStorage.removeItem(STORAGE_PREFIX + id);
    else localStorage.setItem(STORAGE_PREFIX + id, toJson(value, ''));
  } catch {
    // 保存できない環境（プライベートウィンドウなど）では保存しない
  }
}

/** 個別の値を保存してあるか */
export function hasParams(id: string): boolean {
  try {
    return localStorage.getItem(STORAGE_PREFIX + id) !== null;
  } catch {
    return false;
  }
}
