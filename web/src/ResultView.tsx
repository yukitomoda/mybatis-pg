// 結果ビュー: クエリの実行結果（実行タブ・サブクエリの部分）と値の候補を、非モーダルの浮いたウィンドウにまとめて表で出す。
// 実行の履歴（サーバに直近の 50 件）と、それぞれの結果がどこから得られたものかも出す。
import { useDbComments } from './comments';
import type { PointerEvent, ReactNode } from 'react';
import { useEffect, useRef, useState } from 'react';
import type { Api, HistoryEntry, HistorySummary, SourceColumn } from './api';
import { DataTable } from './DataTable';
import { Popover } from './Popover';
import type { RelatedEntry } from './Related';
import { RelatedPane } from './Related';
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

type Selection = { kind: 'history'; id: string } | { kind: 'candidate'; key: string } | { kind: 'related'; key: string } | null;
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

/** 画面の端からあける幅 */
const EDGE = 8;

/**
 * 位置と大きさを、画面の中に収める（起動して読み込んだときだけ。広い画面で大きくした後、小さい画面で開いても、右下のつまみに触れなくならないように）
 */
export function clampGeometry(g: Geometry, vw: number, vh: number): Geometry {
  const w = Math.max(Math.min(360, vw - EDGE * 2), Math.min(g.w, vw - EDGE * 2));
  const h = Math.max(Math.min(200, vh - EDGE * 2), Math.min(g.h, vh - EDGE * 2));
  const x = Math.max(0, Math.min(g.x, vw - w - EDGE));
  const y = Math.max(0, Math.min(g.y, vh - h - EDGE));
  return { x, y, w, h };
}

/** 画面からはみ出させても、つかんで戻せるよう残す題名の帯の幅と高さ */
const KEEP_W = 120;
const KEEP_H = 40;

/**
 * はみ出させてもよいが、見失わないよう、題名の帯を画面に残す（上下にははみ出さず、左右は KEEP_W だけ残す）。大きさは変えない
 */
export function keepReachable(g: Geometry, vw: number, vh: number): Geometry {
  const x = Math.min(Math.max(g.x, KEEP_W - g.w), vw - KEEP_W);
  const y = Math.min(Math.max(g.y, 0), vh - KEEP_H);
  return x === g.x && y === g.y ? g : { ...g, x, y };
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
  // 画面の中に収めるのは、起動して読み込んだときだけ（その後ははみ出させてもよい）
  const [geometry, setGeometry] = useState<Geometry>(() => clampGeometry(loadGeometry(), window.innerWidth, window.innerHeight));
  const [history, setHistory] = useState<HistorySummary[]>([]);
  const [candidates, setCandidates] = useState<CandidateEntry[]>([]);
  /** 関連データ（起点の行ごと。履歴には残さない） */
  const [related, setRelated] = useState<RelatedEntry[]>([]);
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
    related,
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
    /** 行から関連データを集める（結果ビューに出す） */
    showRelated: (connectionId: string, table: string, row: Record<string, string | null>, label: string) => {
      const key = `r${Date.now()}`;
      setRelated((old) =>
        [{ key, connectionId, table, row, label, nodes: [], selected: null, busy: false, error: null }, ...old].slice(0, 10),
      );
      setSelection({ kind: 'related', key });
      setOpen(true);
      setMinimized(false);
    },
    updateRelated: (key: string, f: (e: RelatedEntry) => RelatedEntry) => setRelated((old) => old.map((e) => (e.key === key ? f(e) : e))),
    removeRelated: (key: string) => setRelated((old) => old.filter((e) => e.key !== key)),
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
  // 画面の大きさが変わったら、題名の帯が見える所に戻す（はみ出しはそのまま）
  const [, setViewport] = useState(0);
  useEffect(() => {
    const onResize = () => setViewport((n) => n + 1);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

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
  const g = keepReachable(rv.geometry, window.innerWidth, window.innerHeight);
  const style = rv.maximized ? undefined : { left: g.x, top: g.y, width: g.w, height: g.h };
  const end = (e: PointerEvent<HTMLDivElement>) => {
    drag.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    document.body.classList.remove('resizing');
  };
  const candidate =
    rv.selection?.kind === 'candidate' ? rv.candidates.find((c) => c.key === (rv.selection as { key: string }).key) : undefined;
  const historyId = rv.selection?.kind === 'history' ? rv.selection.id : null;
  const relatedEntry =
    rv.selection?.kind === 'related' ? rv.related.find((r) => r.key === (rv.selection as { key: string }).key) : undefined;

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
          // 画面の外へはみ出させてもよい（題名の帯は残す）
          rv.moveTo(
            keepReachable(
              { ...g, x: drag.current.gx + e.clientX - drag.current.x, y: drag.current.gy + e.clientY - drag.current.y },
              window.innerWidth,
              window.innerHeight,
            ),
          );
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
          {rv.related.length > 0 && (
            <>
              <h4>関連データ</h4>
              <ul>
                {rv.related.map((r) => (
                  <li key={r.key}>
                    <button
                      type="button"
                      className={rv.selection?.kind === 'related' && rv.selection.key === r.key ? 'selected' : undefined}
                      onClick={() => rv.setSelection({ kind: 'related', key: r.key })}
                      title={r.label}
                    >
                      <span className="result-view-item-title">{r.label}</span>
                      <span className="hint">{r.nodes.length} 表</span>
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
                  title={h.statementId ?? h.label ?? undefined}
                >
                  <span className="result-view-item-title">
                    {h.error && <span className="warn-mark">⚠ </span>}
                    {h.kind === 'table' ? (
                      <>
                        <span className="hint">表 </span>
                        {h.label}
                      </>
                    ) : (
                      shortId(h.statementId ?? '')
                    )}
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
          {relatedEntry ? (
            <RelatedPane
              key={relatedEntry.key}
              api={api}
              entry={relatedEntry}
              update={(f) => rv.updateRelated(relatedEntry.key, f)}
              onCell={onCell}
            />
          ) : candidate ? (
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
              onUpdated={rv.refresh}
              onRelated={rv.showRelated}
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
  onUpdated,
  onRelated,
}: {
  api: Api;
  id: string;
  onCell?: (text: string | null, type: string) => void;
  onOpenStatement: (id: string) => void;
  onRestoreParams: (statementId: string, parameters: unknown) => void;
  /** 続きを取った・取り直した（履歴の一覧の行数などを読み直す） */
  onUpdated: () => void;
  /** 行から関連データを集める（表の中身のとき） */
  onRelated: (connectionId: string, table: string, row: Record<string, string | null>, label: string) => void;
}) {
  const [entry, setEntry] = useState<HistoryEntry | null>(null);
  // 列の見出しに DB のコメントを出す（表の中身と、出どころの分かる列）
  const comments = useDbComments(api, entry?.connectionId);
  /** 複数の表の列がある行から関連データを集めるときに、どの表から始めるかを選ぶ */
  const [pick, setPick] = useState<{ tables: { table: string; row: Record<string, string | null> }[]; anchor: DOMRect } | null>(null);
  /** 続きを取っている・取り直している */
  const [fetching, setFetching] = useState<'more' | 'refetch' | null>(null);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const startFrom = (x: { table: string; row: Record<string, string | null> }) => {
    if (!entry) return;
    const [col, val] = Object.entries(x.row)[0] ?? ['', null];
    onRelated(entry.connectionId, x.table, x.row, `${x.table} ${col}=${val ?? 'NULL'}`);
  };
  const fetchRows = (index: number, refetch: boolean) => {
    setFetching(refetch ? 'refetch' : 'more');
    setFetchError(null);
    api.moreResult(id, index, refetch).then(
      (e) => {
        setEntry(e);
        setFetching(null);
        onUpdated();
      },
      (e) => {
        setFetchError(e instanceof Error ? e.message : String(e));
        setFetching(null);
      },
    );
  };
  const [error, setError] = useState<string | null>(null);
  /** 開いているタブ: 結果セットの番号、パラメータ、SQL */
  const [tab, setTab] = useState<number | 'params' | 'sql'>(0);
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
  const t = typeof tab === 'number' ? sets[Math.min(tab, sets.length - 1)] : undefined;
  return (
    <div className="history-detail">
      <div className="history-source">
        <span className="badge">
          {entry.kind === 'part' ? `サブクエリ: ${entry.label ?? '部分'}` : entry.kind === 'table' ? '表' : '実行'}
        </span>
        {entry.statementId ? (
          <button type="button" className="link" onClick={() => onOpenStatement(entry.statementId!)} title="この statement を開く">
            {entry.statementId}
          </button>
        ) : (
          <code>{entry.label}</code>
        )}
        {entry.draft && <span className="badge warn">編集中の XML</span>}
        <span className="hint">
          {entry.connectionName}　{time(entry.executedAt)}　{entry.elapsedMillis} ms
          {entry.kind === 'execute' && entry.committed !== null && (entry.committed ? '　コミットした' : '　ロールバックした')}
          {entry.total !== null && entry.total !== undefined && `　全 ${entry.total} 件`}
          {entry.updateCounts.length > 0 && `　更新件数 ${entry.updateCounts.join(', ')}`}
        </span>
      </div>
      <div className="result-tabs" role="tablist">
        {(sets.length <= 1 ? [0] : sets.map((_, i) => i)).map((i) => (
          <button
            key={i}
            type="button"
            role="tab"
            aria-selected={tab === i}
            className={tab === i ? 'active' : undefined}
            onClick={() => setTab(i)}
          >
            {sets.length <= 1 ? '結果' : `結果 ${i + 1}`}
          </button>
        ))}
        {entry.kind !== 'table' && (
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'params'}
            className={tab === 'params' ? 'active' : undefined}
            onClick={() => setTab('params')}
          >
            パラメータ
          </button>
        )}
        {entry.sql && (
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'sql'}
            className={tab === 'sql' ? 'active' : undefined}
            onClick={() => setTab('sql')}
          >
            SQL
          </button>
        )}
      </div>
      {typeof tab === 'number' && (
        <>
          {entry.error && <pre className="render-error">{entry.error}</pre>}
          {entry.warnings.length > 0 && (
            <ul className="notes warn">
              {entry.warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          )}
          {t && (
            <>
              {fetchError && <pre className="render-error">{fetchError}</pre>}
              <DataTable
                columns={t.columns}
                types={t.columnTypes}
                rows={t.rows}
                onCell={onCell}
                truncated={t.truncated}
                titles={
                  entry.kind === 'table'
                    ? t.columns.map((c) => comments.columns[`${entry.label}.${c}`])
                    : t.columns.map((_, i) => {
                        const src = t.columnSources?.[i];
                        return src ? [src, comments.columns[src]].filter(Boolean).join('\n') : undefined;
                      })
                }
                onRow={
                  entry.kind === 'table' && entry.label
                    ? (i) =>
                        onRelated(
                          entry.connectionId,
                          entry.label!,
                          rowRecord(t.columns, t.rows[i]),
                          rowLabel(entry.label!, t.columns, t.rows[i]),
                        )
                    : t.columnSources?.some(Boolean)
                      ? (i, anchor) => {
                          // 出どころの分かる列を、表ごとに分ける（1 つの表なら、すぐに始める）
                          const tables = tablesOfRow(t.columnSources!, t.rows[i]);
                          if (tables.length === 1) startFrom(tables[0]);
                          else setPick({ tables, anchor });
                        }
                      : undefined
                }
                actions={
                  <>
                    {t.truncated && (
                      <button
                        type="button"
                        disabled={fetching !== null}
                        title="同じ SQL をもう一度流し、取った行の次から取ります（その間に行が増減していれば、ずれます）"
                        onClick={() => fetchRows(Math.min(tab as number, sets.length - 1), false)}
                      >
                        {fetching === 'more' ? '取得中…' : '続きを取得'}
                      </button>
                    )}
                    {entry.updateCounts.length === 0 && (
                      <button
                        type="button"
                        className="link"
                        disabled={fetching !== null}
                        title="同じ SQL で、今ある行数を最初から取り直します"
                        onClick={() => fetchRows(Math.min(tab as number, sets.length - 1), true)}
                      >
                        {fetching === 'refetch' ? '再取得中…' : '再取得'}
                      </button>
                    )}
                  </>
                }
              />
            </>
          )}
          {!t && !entry.error && <p className="hint">結果の表はありません</p>}
        </>
      )}
      {tab === 'params' && entry.statementId && (
        <div className="history-params">
          <button type="button" className="link" onClick={() => onRestoreParams(entry.statementId!, entry.parameters)}>
            この値をパラメータ欄に戻す
          </button>
          <pre className="sql">{JSON.stringify(entry.parameters ?? {}, null, 2)}</pre>
        </div>
      )}
      {pick && (
        <Popover anchor={pick.anchor} onClose={() => setPick(null)}>
          <h4>どの表の行から集めますか</h4>
          <ul className="popover-sql">
            {pick.tables.map((x) => (
              <li key={x.table}>
                <button type="button" className="link" onClick={() => startFrom(x)}>
                  {x.table}
                </button>
                <span className="hint"> {Object.keys(x.row).join(', ')}</span>
              </li>
            ))}
          </ul>
        </Popover>
      )}
      {tab === 'sql' && entry.sql && (
        <div className="history-sql">
          <pre className="sql">{entry.sql}</pre>
        </div>
      )}
    </div>
  );
}

/** 行を、列 → 値にする */
export function rowRecord(columns: string[], row: (string | null)[]): Record<string, string | null> {
  return Object.fromEntries(columns.map((c, i) => [c, row[i]]));
}

/** 関連データの一覧に出す名前（表と、先頭の列の値。erp.projects id=5） */
export function rowLabel(table: string, columns: string[], row: (string | null)[]): string {
  return `${table} ${columns[0]}=${row[0] ?? 'NULL'}`;
}

/** 行の、出どころの分かる列を表ごとに分ける（表 → 元の列の名前 → 値。表は結果の列の順） */
export function tablesOfRow(sources: (string | null)[], row: (string | null)[]): { table: string; row: Record<string, string | null> }[] {
  const out = new Map<string, Record<string, string | null>>();
  sources.forEach((src, i) => {
    if (!src) return;
    const table = src.slice(0, src.lastIndexOf('.'));
    const column = src.slice(src.lastIndexOf('.') + 1);
    if (!out.has(table)) out.set(table, {});
    out.get(table)![column] = row[i];
  });
  return [...out].map(([table, r]) => ({ table, row: r }));
}
