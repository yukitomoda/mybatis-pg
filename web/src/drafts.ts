// mapper XML の編集中の内容（下書き）。サーバのファイルに保存し、元のファイルは変えない
import { useEffect, useRef, useState } from 'react';
import type { Api } from './api';

export interface Draft {
  /** ファイル（statement 一覧と同じ、画面のパス） */
  file: string;
  /** 編集を始めたときのファイルの内容。ファイルが変わったかを見分ける */
  base: string;
  content: string;
  /** 最後に編集した日時（ミリ秒） */
  updatedAt: number;
  /** そのファイルを読み込んでいる（消したり名前を変えたりしていない） */
  loaded: boolean;
  /** 編集を始めた後に、ファイルが変わった */
  changed: boolean;
}

/** 以前はブラウザに保存していた。見つけたらサーバへ移す */
const LEGACY_PREFIX = 'mybatis-pg.draft:';

function legacyDrafts(): { key: string; file: string; base: string; content: string }[] {
  const out: { key: string; file: string; base: string; content: string }[] = [];
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key?.startsWith(LEGACY_PREFIX)) continue;
      const d = JSON.parse(localStorage.getItem(key) ?? 'null');
      if (d && typeof d.base === 'string' && typeof d.content === 'string')
        out.push({ key, file: key.slice(LEGACY_PREFIX.length), base: d.base, content: d.content });
    }
  } catch {
    // 読めない環境では移すものなしとする
  }
  return out;
}

export interface Drafts {
  get: (file: string | undefined) => Draft | undefined;
  /** 編集した内容を保存する。元の内容と同じになったら消す */
  set: (file: string, base: string, content: string) => void;
  discard: (files: string[]) => void;
  files: Set<string>;
  /** 更新の新しい順 */
  list: Draft[];
  /** 保存できなかったときの理由 */
  error: string | null;
}

export function useDrafts(api: Api, generation: number, onApiError: (e: unknown) => void): Drafts {
  const [drafts, setDrafts] = useState<Map<string, Draft>>(new Map());
  const [error, setError] = useState<string | null>(null);
  /** ファイルごとに、保存を順番に送る（後の内容が先の内容で上書きされないように） */
  const queues = useRef(new Map<string, Promise<unknown>>());
  const migrated = useRef(false);

  const fail = (e: unknown) => {
    onApiError(e);
    setError('下書きを保存できません: ' + (e instanceof Error ? e.message : String(e)));
  };

  // ファイルが読み込み直されたら取り直す（ファイルが変わったか、同じになったかが変わる）
  useEffect(() => {
    if (generation === 0) return;
    let active = true;
    (async () => {
      if (!migrated.current) {
        migrated.current = true;
        for (const d of legacyDrafts()) {
          try {
            await api.saveDraft(d.file, d.base, d.content);
            localStorage.removeItem(d.key);
          } catch {
            // 読み込んでいないファイルなどは移せない。ブラウザに残しておく
          }
        }
      }
      const r = await api.drafts();
      if (active) setDrafts(new Map(r.drafts.map((d) => [d.file, d])));
    })().catch(onApiError);
    return () => {
      active = false;
    };
  }, [api, generation]);

  const enqueue = (file: string, f: () => Promise<unknown>) => {
    const next = (queues.current.get(file) ?? Promise.resolve()).then(f).then(
      () => setError(null),
      (e) => fail(e),
    );
    queues.current.set(file, next);
  };

  const update = (file: string, d: Draft | undefined) =>
    setDrafts((old) => {
      const next = new Map(old);
      if (d) next.set(file, d);
      else next.delete(file);
      return next;
    });

  return {
    get: (file) => (file === undefined ? undefined : drafts.get(file)),
    set: (file, base, content) => {
      const old = drafts.get(file);
      update(file, content === base ? undefined : { file, base, content, updatedAt: Date.now(), loaded: true, changed: old?.changed ?? false });
      enqueue(file, () => api.saveDraft(file, base, content));
    },
    discard: (files) => {
      for (const f of files) update(f, undefined);
      for (const f of files) enqueue(f, () => api.discardDrafts([f]));
    },
    files: new Set(drafts.keys()),
    list: [...drafts.values()].sort((a, b) => b.updatedAt - a.updatedAt),
    error,
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
