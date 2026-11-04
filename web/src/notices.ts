// エラーの知らせ（画面の右下に出し、数秒で消す。押すとログのその行へ移る）と、エラーの文からログの行を引く表
import { createContext, useContext, useEffect, useState } from 'react';

export type Notice = { key: number; message: string; logId: string | null };

let list: Notice[] = [];
let seq = 0;
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((f) => f());
/** エラーの文の 1 行目 → ログの行（画面の中のエラーの印から、その行へ移るため） */
const ids = new Map<string, string>();
/** 同じ文の知らせを、続けて出さない */
const lastShown = new Map<string, number>();

const firstLine = (s: string) => s.split('\n')[0].trim();

/** 知らせを消すまでの時間（ミリ秒） */
const SHOW_MILLIS = 8000;

/** エラーを知らせる。show が false なら、ログの行だけ覚える（入力に合わせて裏で呼ぶ問い合わせなど） */
export function notifyError(message: string, logId: string | null, show = true) {
  const key1 = firstLine(message);
  if (logId) ids.set(key1, logId);
  if (!show) return;
  const now = Date.now();
  if (now - (lastShown.get(key1) ?? 0) < 5000) return;
  lastShown.set(key1, now);
  const n: Notice = { key: ++seq, message, logId };
  list = [...list, n].slice(-5);
  emit();
  window.setTimeout(() => dismiss(n.key), SHOW_MILLIS);
}

export function dismiss(key: number) {
  const next = list.filter((n) => n.key !== key);
  if (next.length !== list.length) {
    list = next;
    emit();
  }
}

/** エラーの文から、ログの行（分からなければ null） */
export function logIdOf(message: string): string | null {
  return ids.get(firstLine(message)) ?? null;
}

export function useNotices(): Notice[] {
  const [, setTick] = useState(0);
  useEffect(() => {
    const f = () => setTick((n) => n + 1);
    listeners.add(f);
    return () => {
      listeners.delete(f);
    };
  }, []);
  return list;
}

/** ログを開く（行の id か、探す文字） */
export type ShowLog = (target: { id?: string | null; q?: string }) => void;
export const ShowLogContext = createContext<ShowLog>(() => {});
export const useShowLog = () => useContext(ShowLogContext);
