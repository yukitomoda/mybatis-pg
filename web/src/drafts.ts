// mapper XML の編集中の内容（下書き）。ファイルごとにブラウザに保存し、元のファイルは変えない
import { useState } from 'react';

export interface Draft {
  /** ファイル（statement 一覧と同じ、画面のパス） */
  file: string;
  /** 編集を始めたときのファイルの内容。ファイルが変わったかを見分ける */
  base: string;
  content: string;
}

const PREFIX = 'mybatis-pg.draft:';

function loadAll(): Map<string, Draft> {
  const out = new Map<string, Draft>();
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key?.startsWith(PREFIX)) continue;
      const d = JSON.parse(localStorage.getItem(key) ?? 'null');
      if (d && typeof d.base === 'string' && typeof d.content === 'string') out.set(key.slice(PREFIX.length), { file: key.slice(PREFIX.length), base: d.base, content: d.content });
    }
  } catch {
    // 読めない環境では下書きなしとする
  }
  return out;
}

export interface Drafts {
  get: (file: string | undefined) => Draft | undefined;
  /** 編集した内容を保存する。元の内容と同じになったら消す */
  set: (file: string, base: string, content: string) => void;
  discard: (file: string) => void;
  files: Set<string>;
}

export function useDrafts(): Drafts {
  const [drafts, setDrafts] = useState(loadAll);
  const update = (file: string, d: Draft | undefined) => {
    setDrafts((old) => {
      const next = new Map(old);
      if (d) next.set(file, d);
      else next.delete(file);
      return next;
    });
    try {
      if (d) localStorage.setItem(PREFIX + file, JSON.stringify({ base: d.base, content: d.content }));
      else localStorage.removeItem(PREFIX + file);
    } catch {
      // 保存できない環境では、開いている間だけ使う
    }
  };
  return {
    get: (file) => (file === undefined ? undefined : drafts.get(file)),
    set: (file, base, content) => update(file, content === base ? undefined : { file, base, content }),
    discard: (file) => update(file, undefined),
    files: new Set(drafts.keys()),
  };
}

/** サーバに渡す形 */
export function draftParam(d: Draft | undefined): { file: string; content: string } | null {
  return d ? { file: d.file, content: d.content } : null;
}

/** statement の開始行（1 始まり）。見つからなければ null */
export function statementLine(content: string, statementId: string): number | null {
  const id = statementId.slice(statementId.lastIndexOf('.') + 1).replace(/!selectKey$/, '');
  const escaped = id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`<(select|insert|update|delete)\\b[^>]*\\bid\\s*=\\s*["']${escaped}["']`).exec(content);
  return m ? content.slice(0, m.index).split('\n').length : null;
}
