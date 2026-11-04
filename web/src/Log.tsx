// ログ: DB への操作（送った SQL・値・時間・エラー）と、画面の操作で起きたエラーを新しい順に見る
import { useEffect, useRef, useState } from 'react';
import type { Api, LogEntry } from './api';

const PAGE = 200;

/** 1 行の本文（SQL・できごと・画面の操作） */
function body(e: LogEntry): string {
  if (e.sql) return e.sql;
  if (e.kind === 'api') return `${e.path ?? ''}`;
  if (e.kind === 'event') return `${e.who ? `[${e.who}] ` : ''}${e.msg ?? ''}`;
  return e.msg ?? '';
}

export function LogScreen({ api, active, target }: { api: Api; active: boolean; target: { id?: string | null; q?: string } | null }) {
  const [errors, setErrors] = useState(false);
  const [analysis, setAnalysis] = useState(false);
  const [q, setQ] = useState('');
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [capped, setCapped] = useState(false);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const [open, setOpen] = useState<Set<string>>(new Set());
  /** 目立たせる行（知らせやエラーの印から移ってきたとき） */
  const [focus, setFocus] = useState<string | null>(null);
  const list = useRef<HTMLDivElement>(null);

  // 知らせから移ってきたら、その行から読む（行が分からなければ、文で探す）
  useEffect(() => {
    if (!target) return;
    setFocus(target.id ?? null);
    if (!target.id && target.q) {
      setQ(target.q);
      setErrors(true);
    }
  }, [target]);

  const query = { errors, analysis, q: q.trim() || undefined };
  const load = (cursor: string | null, append: boolean, focusId: string | null = null) => {
    setLoading(true);
    setFailed(null);
    api.log({ ...query, cursor, focus: focusId, limit: PAGE }).then(
      (r) => {
        setEntries((old) => (append ? [...old, ...r.entries] : r.entries));
        setNext(r.next);
        setCapped(r.capped);
        setLoading(false);
      },
      (e) => {
        setFailed(e instanceof Error ? e.message : String(e));
        setLoading(false);
      },
    );
  };
  // 絞り込みを変えたら、読み直す（文字は打ち終わるのを少し待つ）
  useEffect(() => {
    if (!active) return;
    const t = window.setTimeout(() => load(null, false, focus), q ? 400 : 0);
    return () => window.clearTimeout(t);
  }, [active, errors, analysis, q, focus]);
  // 開いている間は、新しいものを上に足す（目立たせる行を見ているときは足さない）
  useEffect(() => {
    if (!active || focus) return;
    const t = window.setInterval(() => {
      api.log({ ...query, limit: PAGE }).then(
        (r) =>
          setEntries((old) => {
            const have = new Set(old.map((e) => e.id));
            const fresh = r.entries.filter((e) => !have.has(e.id));
            return fresh.length === 0 ? old : [...fresh, ...old];
          }),
        () => {},
      );
    }, 3000);
    return () => window.clearInterval(t);
  }, [active, focus, errors, analysis, q]);
  useEffect(() => {
    if (focus) list.current?.querySelector('.log-row.focus')?.scrollIntoView({ block: 'center' });
  }, [entries, focus]);

  const toggle = (id: string) =>
    setOpen((old) => {
      const n = new Set(old);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  const today = new Date().toISOString().slice(0, 10);

  return (
    <div className="log-screen page">
      <header className="panel-header">
        <h2>ログ</h2>
        <label>
          <input type="checkbox" checked={errors} onChange={(e) => setErrors(e.target.checked)} /> エラーだけ
        </label>
        <label>
          <input type="checkbox" checked={analysis} onChange={(e) => setAnalysis(e.target.checked)} /> 解析も含める
        </label>
        <input type="search" className="log-filter" placeholder="文字で絞り込む" value={q} onChange={(e) => setQ(e.target.value)} />
        {focus && (
          <button type="button" className="link" onClick={() => setFocus(null)}>
            新しいものから見る
          </button>
        )}
      </header>
      {failed && <p className="error-text">{failed}</p>}
      <div className="log-list" ref={list}>
        <table className="log-table">
          <tbody>
            {entries.map((e) => {
              const isOpen = open.has(e.id);
              return (
                <tr
                  key={e.id}
                  className={['log-row', e.err ? 'error' : '', e.id === focus ? 'focus' : '', isOpen ? 'open' : '']
                    .filter(Boolean)
                    .join(' ')}
                  onClick={() => toggle(e.id)}
                >
                  <td className="log-time" title={e.t}>
                    {e.t.startsWith(today) ? e.t.slice(11) : e.t}
                  </td>
                  <td className="log-src">{e.src === '解析' ? <span className="badge muted">解析</span> : null}</td>
                  <td className="log-conn" title={e.conn}>
                    {e.conn ? `${e.conn}${e.n ? ` #${e.n}` : ''}` : ''}
                  </td>
                  <td className="log-kind">{e.kind}</td>
                  <td className="log-ms">{e.ms !== undefined ? `${e.ms} ms` : ''}</td>
                  <td className="log-body">
                    <div className={isOpen ? 'log-text full' : 'log-text'}>{body(e)}</div>
                    {isOpen && e.values && e.values.length > 0 && <div className="log-values">値: {JSON.stringify(e.values)}</div>}
                    {e.err && (
                      <div className={isOpen ? 'log-err full' : 'log-err'}>
                        {e.state ? `(${e.state}) ` : ''}
                        {e.err}
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {entries.length === 0 && !loading && <p className="hint">ありません</p>}
        {next && (
          <p>
            <button type="button" className="link" disabled={loading} onClick={() => load(next, true)}>
              {loading ? '読んでいます…' : capped ? `続きを探す（読む行数の上限まで探しました）` : '続きを読む'}
            </button>
          </p>
        )}
      </div>
    </div>
  );
}
