// 裏で動いているもの（サーバーへの問い合わせ、図の配置、リレーションの解析など）を数えて、上の帯に出す
import { useEffect, useState } from 'react';

/** 動いている問い合わせ・計算の数 */
let pending = 0;
/** 長く続く作業（リレーションの解析など）。名前 → 出す文 */
const tasks = new Map<string, string>();
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((f) => f());

/** 終わるまで「読み込み中」に数える */
export function track<T>(p: Promise<T>): Promise<T> {
  pending++;
  notify();
  const done = () => {
    pending--;
    notify();
  };
  p.then(done, done);
  return p;
}

/** 長く続く作業を出す（null で消す） */
export function setTask(name: string, label: string | null) {
  if (label === null ? !tasks.has(name) : tasks.get(name) === label) return;
  if (label === null) tasks.delete(name);
  else tasks.set(name, label);
  notify();
}

/** 一瞬で終わるものでは出さないよう、これより続いたら出す（ミリ秒） */
const DELAY = 300;

/** 今動いているもの: 長く続く作業の文と、読み込み中か（DELAY より続いている問い合わせ・計算があるか） */
export function useBusy(): { tasks: string[]; loading: boolean } {
  const [state, setState] = useState({ tasks: [...tasks.values()], loading: false });
  useEffect(() => {
    let timer: number | undefined;
    const f = () => {
      if (pending > 0 && timer === undefined) {
        timer = window.setTimeout(() => {
          timer = undefined;
          setState((s) => ({ ...s, loading: pending > 0 }));
        }, DELAY);
      } else if (pending === 0) {
        window.clearTimeout(timer);
        timer = undefined;
      }
      setState((s) => ({ tasks: [...tasks.values()], loading: pending > 0 && s.loading }));
    };
    listeners.add(f);
    return () => {
      listeners.delete(f);
      window.clearTimeout(timer);
    };
  }, []);
  return state;
}
