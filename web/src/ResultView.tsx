// 結果ビュー: クエリの実行結果（実行タブ・サブクエリの部分）と値の候補を、非モーダルの浮いたウィンドウにまとめて表で出す。
// 実行の履歴（サーバに直近の 50 件）と、それぞれの結果がどこから得られたものかも出す。
import type { PointerEvent, ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import type { Api, HistoryEntry, HistorySummary, SourceColumn } from './api';
import { DataTable } from './DataTable';
import type { Path } from './params';

/** 値の候補（パラメータごとに 1 件。履歴には残さない） */
export interface CandidateEntry {
  key: string;
  statementId: string;
  path: Path;
  /** パラメータの項目（cond.customerId など） */
  label: string;
  /** パラメータの名前（customerId） */
  paramName: string;
  columns: SourceColumn[];
  list: boolean;
  relations: boolean;
}

type Selection = { kind: 'history'; id: string } | { kind: 'candidate'; key: string } | null;
type Geometry = { x: number; y: number; w: number; h: number };

const GEOMETRY_KEY = 'mybatis-pg.result-view';

function loadGeometry(): Geometry {
  try {
    const g = JSON.parse(localStorage.getItem(GEOMETRY_KEY) ?? 'null') as Geometry | null;
    if (g && [g.x, g.y, g.w, g.h].every((n) => typeof n === 'number')) return g;
  } catch {
    // 既定の位置にする
  }
  // 既定は画面の右側（パラメータ欄に重ならないように）
  const w = Math.round(window.innerWidth * 0.45);
  return { x: window.innerWidth - w - 16, y: 64, w, h: Math.round(window.innerHeight * 0.65) };
}

function saveGeometry(g: Geometry) {
  try {
    localStorage.setItem(GEOMETRY_KEY, JSON.stringify(g));
  } catch {
    // 保存できない環境では、この表示中だけ覚える
  }
}

/** 結果ビューの状態 */
export function useResultView(api: Api | null) {
  const [open, setOpen] = useState(false);
  const [minimized, setMinimized] = useState(false);
  const [maximized, setMaximized] = useState(false);
  const [geometry, setGeometry] = useState<Geometry>(loadGeometry);
  const [history, setHistory] = useState<HistorySummary[]>([]);
  const [candidates, setCandidates] = useState<CandidateEntry[]>([]);
  const [selection, setSelection] = useState<Selection>(null);
  /** 最小化している間に、新しい結果が入った */
  const [fresh, setFresh] = useState(false);

  const refresh = () => {
    if (!api) return Promise.resolve();
    return api.results().then(
      (r) => setHistory(r.entries),
      () => undefined,
    );
  };
  useEffect(() => {
    refresh();
  }, [api]);

  /** 開く（最小化しているなら、開かずに印を付ける） */
  const show = () => {
    setOpen(true);
    if (minimized) setFresh(true);
  };

  return {
    open,
    minimized,
    maximized,
    geometry,
    history,
    candidates,
    selection,
    fresh,
    refresh,
    setSelection,
    /** 実行の結果を出す（実行したら自動で開く） */
    showHistory: (id: string) => {
      refresh().then(() => {
        setSelection({ kind: 'history', id });
        show();
      });
    },
    /** 値の候補を出す（同じパラメータなら、前の 1 件を新しくする） */
    showCandidate: (c: CandidateEntry) => {
      setCandidates((old) => [c, ...old.filter((x) => x.key !== c.key)].slice(0, 20));
      setSelection({ kind: 'candidate', key: c.key });
      setOpen(true);
      setMinimized(false);
    },
    removeCandidate: (key: string) => setCandidates((old) => old.filter((x) => x.key !== key)),
    toggle: () => {
      if (open && !minimized) setOpen(false);
      else {
        setOpen(true);
        setMinimized(false);
        setFresh(false);
      }
    },
    close: () => setOpen(false),
    minimize: () => setMinimized(true),
    restore: () => {
      setMinimized(false);
      setFresh(false);
    },
    toggleMaximized: () => setMaximized((m) => !m),
    moveTo: (g: Geometry) => {
      setGeometry(g);
      saveGeometry(g);
    },
    clearHistory: () => {
      if (!api) return;
      api.clearResults().then(() => {
        setHistory([]);
        if (selection?.kind === 'history') setSelection(null);
      });
    },
  };
}

export type ResultViewState = ReturnType<typeof useResultView>;

const time = (ms: number) => new Date(ms).toLocaleString('ja-JP');

/** 結果ビューのウィンドウ */
export function ResultView({
  rv,
  api,
  target,
  onClearTarget,
  onCell,
  onOpenStatement,
  onRestoreParams,
  renderCandidate,
}: {
  rv: ResultViewState;
  api: Api;
  /** 値を入れる先（最後にフォーカスしたパラメータの欄）の見出し。なければ null */
  target: string | null;
  onClearTarget: () => void;
  /** セルを押したとき（入れる先がなければ undefined） */
  onCell?: (text: string | null, type: string) => void;
  onOpenStatement: (id: string) => void;
  /** 履歴のパラメータを、その statement のパラメータ欄に戻す */
  onRestoreParams: (statementId: string, parameters: unknown) => void;
  /** 値の候補の中身（見えているときだけ DB を読む） */
  renderCandidate: (c: CandidateEntry, visible: boolean) => ReactNode;
}) {
  const drag = useRef<{ x: number; y: number; gx: number; gy: number } | null>(null);
  const box = useRef<HTMLDivElement>(null);

  // 端をドラッグして変えた大きさを覚える
  useEffect(() => {
    const el = box.current;
    if (!el || rv.maximized) return;
    let timer = 0;
    const ro = new ResizeObserver(() => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => {
        const r = el.getBoundingClientRect();
        if (Math.round(r.width) !== rv.geometry.w || Math.round(r.height) !== rv.geometry.h) {
          rv.moveTo({ ...rv.geometry, w: Math.round(r.width), h: Math.round(r.height) });
        }
      }, 300);
    });
    ro.observe(el);
    return () => {
      ro.disconnect();
      window.clearTimeout(timer);
    };
  }, [rv.open, rv.minimized, rv.maximized, rv.geometry]);

  if (!rv.open) return null;
  if (rv.minimized) {
    return (
      <button
        type="button"
        className={rv.fresh ? 'result-view-bar fresh' : 'result-view-bar'}
        onClick={rv.restore}
        title="結果ビューを開く"
      >
        結果ビュー{rv.fresh && <span className="badge warn">新しい結果</span>}
      </button>
    );
  }
  const g = rv.geometry;
  const style = rv.maximized ? undefined : { left: g.x, top: g.y, width: g.w, height: g.h };
  const end = (e: PointerEvent<HTMLDivElement>) => {
    drag.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    document.body.classList.remove('resizing');
  };
  const candidate =
    rv.selection?.kind === 'candidate' ? rv.candidates.find((c) => c.key === (rv.selection as { key: string }).key) : undefined;
  const historyId = rv.selection?.kind === 'history' ? rv.selection.id : null;

  return (
    <div ref={box} className={rv.maximized ? 'result-view maximized' : 'result-view'} style={style} role="dialog" aria-label="結果ビュー">
      <div
        className="result-view-head"
        onPointerDown={(e) => {
          if (rv.maximized || (e.target as HTMLElement).closest('button')) return;
          e.currentTarget.setPointerCapture(e.pointerId);
          drag.current = { x: e.clientX, y: e.clientY, gx: g.x, gy: g.y };
          document.body.classList.add('resizing');
        }}
        onPointerMove={(e) => {
          if (!drag.current) return;
          const x = Math.max(0, Math.min(window.innerWidth - 120, drag.current.gx + e.clientX - drag.current.x));
          const y = Math.max(0, Math.min(window.innerHeight - 40, drag.current.gy + e.clientY - drag.current.y));
          rv.moveTo({ ...g, x, y });
        }}
        onPointerUp={end}
        onPointerCancel={end}
        onDoubleClick={rv.toggleMaximized}
      >
        <span className="result-view-title">結果ビュー</span>
        <span className="result-view-target">
          {target ? (
            <>
              挿入先: <code>{target}</code>
              <button type="button" className="icon" title="値を入れる先を解除" aria-label="値を入れる先を解除" onClick={onClearTarget}>
                ×
              </button>
            </>
          ) : (
            <span className="hint">パラメータの入力欄を選ぶと、表の値を押して入れられます</span>
          )}
        </span>
        <button type="button" className="icon" title="最小化" aria-label="最小化" onClick={rv.minimize}>
          ＿
        </button>
        <button
          type="button"
          className="icon"
          title={rv.maximized ? '元の大きさ' : '最大化'}
          aria-label="最大化"
          onClick={rv.toggleMaximized}
        >
          {rv.maximized ? '❐' : '□'}
        </button>
        <button type="button" className="icon" title="閉じる" aria-label="閉じる" onClick={rv.close}>
          ×
        </button>
      </div>
      <div className="result-view-body">
        <aside className="result-view-list">
          {rv.candidates.length > 0 && (
            <>
              <h4>値の候補</h4>
              <ul>
                {rv.candidates.map((c) => (
                  <li key={c.key}>
                    <button
                      type="button"
                      className={candidate?.key === c.key ? 'selected' : undefined}
                      onClick={() => rv.setSelection({ kind: 'candidate', key: c.key })}
                      title={`${c.statementId} の ${c.label}`}
                    >
                      <span className="result-view-item-title">{c.label}</span>
                      <span className="hint">{shortId(c.statementId)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
          <h4>
            実行の履歴
            {rv.history.length > 0 && (
              <button type="button" className="link" onClick={() => window.confirm('実行の履歴をすべて消しますか？') && rv.clearHistory()}>
                消す
              </button>
            )}
          </h4>
          {rv.history.length === 0 && <p className="hint">まだありません</p>}
          <ul>
            {rv.history.map((h) => (
              <li key={h.id}>
                <button
                  type="button"
                  className={historyId === h.id ? 'selected' : undefined}
                  onClick={() => rv.setSelection({ kind: 'history', id: h.id })}
                  title={h.statementId}
                >
                  <span className="result-view-item-title">
                    {h.error && <span className="warn-mark">⚠ </span>}
                    {shortId(h.statementId)}
                    {h.kind === 'part' && <span className="hint"> {h.label ?? '部分'}</span>}
                  </span>
                  <span className="hint">
                    {new Date(h.executedAt).toLocaleTimeString('ja-JP')}　{h.error ? 'エラー' : `${h.rows} 行`}　{h.elapsedMillis} ms
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </aside>
        <section className="result-view-detail">
          {candidate ? (
            <>
              <h3>
                <code>{candidate.label}</code> の値の候補 <span className="hint">{candidate.statementId}</span>
              </h3>
              {renderCandidate(candidate, true)}
            </>
          ) : historyId ? (
            <HistoryDetail
              key={historyId}
              api={api}
              id={historyId}
              onCell={onCell}
              onOpenStatement={onOpenStatement}
              onRestoreParams={onRestoreParams}
            />
          ) : (
            <p className="hint">左の一覧から選んでください</p>
          )}
        </section>
      </div>
    </div>
  );
}

/** statement の id の最後の 2 つ（OrderMapper.findById） */
const shortId = (id: string) => id.split('.').slice(-2).join('.');

/** 実行の履歴の 1 件: どこから得た結果かと、結果の表 */
function HistoryDetail({
  api,
  id,
  onCell,
  onOpenStatement,
  onRestoreParams,
}: {
  api: Api;
  id: string;
  onCell?: (text: string | null, type: string) => void;
  onOpenStatement: (id: string) => void;
  onRestoreParams: (statementId: string, parameters: unknown) => void;
}) {
  const [entry, setEntry] = useState<HistoryEntry | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState(0);
  useEffect(() => {
    let active = true;
    api.result(id).then(
      (e) => active && setEntry(e),
      (e) => active && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      active = false;
    };
  }, [api, id]);
  if (error) return <p className="error-text">{error}</p>;
  if (!entry) return <p className="hint">読み込んでいます…</p>;
  const sets = entry.resultSets;
  const t = sets[Math.min(tab, sets.length - 1)];
  return (
    <div className="history-detail">
      <div className="history-source">
        <span className="badge">{entry.kind === 'part' ? `サブクエリ: ${entry.label ?? '部分'}` : '実行'}</span>
        <button type="button" className="link" onClick={() => onOpenStatement(entry.statementId)} title="この statement を開く">
          {entry.statementId}
        </button>
        {entry.draft && <span className="badge warn">編集中の XML</span>}
        <span className="hint">
          {entry.connectionName}　{time(entry.executedAt)}　{entry.elapsedMillis} ms
          {entry.kind === 'execute' && entry.committed !== null && (entry.committed ? '　コミットした' : '　ロールバックした')}
          {entry.total !== null && entry.total !== undefined && `　全 ${entry.total} 件`}
          {entry.updateCounts.length > 0 && `　更新件数 ${entry.updateCounts.join(', ')}`}
        </span>
      </div>
      {entry.error && <pre className="render-error">{entry.error}</pre>}
      {entry.warnings.length > 0 && (
        <ul className="notes warn">
          {entry.warnings.map((w, i) => (
            <li key={i}>{w}</li>
          ))}
        </ul>
      )}
      <details className="history-params">
        <summary>パラメータ</summary>
        <pre className="sql">{JSON.stringify(entry.parameters ?? {}, null, 2)}</pre>
        <button type="button" className="link" onClick={() => onRestoreParams(entry.statementId, entry.parameters)}>
          この値をパラメータ欄に戻す
        </button>
      </details>
      {entry.sql && (
        <details className="history-sql">
          <summary>SQL</summary>
          <pre className="sql">{entry.sql}</pre>
        </details>
      )}
      {sets.length > 1 && (
        <div className="result-tabs">
          {sets.map((_, i) => (
            <button key={i} type="button" className={i === tab ? 'active' : undefined} onClick={() => setTab(i)}>
              結果 {i + 1}
            </button>
          ))}
        </div>
      )}
      {t && (
        <>
          {t.truncated && <p className="hint">先頭 {t.rows.length} 行だけを取りました</p>}
          <DataTable columns={t.columns} types={t.columnTypes} rows={t.rows} onCell={onCell} />
        </>
      )}
    </div>
  );
}
