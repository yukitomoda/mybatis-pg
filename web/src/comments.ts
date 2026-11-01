// DB の表と列のコメント（COMMENT ON）。接続先ごとに一度だけ読んで覚えておき、表や列の説明として出す
import { createContext, useEffect, useState } from 'react';
import type { Api } from './api';

export type DbComments = { tables: Record<string, string>; columns: Record<string, string> };

const EMPTY: DbComments = { tables: {}, columns: {} };
const cache = new Map<string, Promise<DbComments>>();

/** 画面の中の部品へ、コメントを渡す */
export const CommentsContext = createContext<DbComments>(EMPTY);

/** 接続先のコメント（読めなければ、コメントなしとする） */
export function useDbComments(api: Api, connectionId: string | null | undefined): DbComments {
  const [comments, setComments] = useState<DbComments>(EMPTY);
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
  }, [api, connectionId]);
  return comments;
}
