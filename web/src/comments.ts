// DB の表と列のコメント（COMMENT ON）。接続先ごとに一度だけ読んで覚えておき、表や列の説明として出す
import { createContext, useEffect, useState } from 'react';
import type { Api } from './api';

export type DbComments = { tables: Record<string, string>; columns: Record<string, string> };

const EMPTY: DbComments = { tables: {}, columns: {} };
const cache = new Map<string, Promise<DbComments>>();
/** 読み直しを頼まれたら増やす（使っている部品が読み直す） */
const listeners = new Set<() => void>();

/** 覚えておいたコメントを捨てて、読み直させる（出すスキーマを変えた、解析した） */
export function clearDbComments() {
  cache.clear();
  for (const f of listeners) f();
}

/** 画面の中の部品へ、コメントを渡す */
export const CommentsContext = createContext<DbComments>(EMPTY);

/** 接続先のコメント（読めなければ、コメントなしとする） */
export function useDbComments(api: Api, connectionId: string | null | undefined): DbComments {
  const [comments, setComments] = useState<DbComments>(EMPTY);
  const [version, setVersion] = useState(0);
  useEffect(() => {
    const f = () => setVersion((v) => v + 1);
    listeners.add(f);
    return () => {
      listeners.delete(f);
    };
  }, []);
  useEffect(() => {
    setComments(EMPTY);
    if (!connectionId) return;
    let p = cache.get(connectionId);
    if (!p) {
      p = api.dbComments(connectionId).catch(() => EMPTY);
      cache.set(connectionId, p);
    }
    let active = true;
    p.then((c) => active && setComments(c));
    return () => {
      active = false;
    };
  }, [api, connectionId, version]);
  return comments;
}
