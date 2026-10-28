// 全 statement の一括チェック: 保存してある値でまとめて EXPLAIN し、重そうなものを一覧にする
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Api, DraftParam } from './api';
import { BulkPicker, loadSelection, saveSelection } from './BulkPicker';
import { diagnose } from './diagnose';
import type { Connections } from './Execution';
import { ConnectionPicker, message } from './Execution';
import type { GlobalSet, StatementsSettings } from './globalModel';
import type { ParamSchema } from './params';
import { flatten, parsePlan } from './plan';
import type { Readiness } from './readiness';
import { isReady, problemLabel, readiness } from './readiness';
import type { StatementEntry } from './tree';

interface Row {
  id: string;
  type: string;
  status: 'ok' | 'error';
  /** ANALYZE を指定しても、更新系は計画だけにした */
  planOnly: boolean;
  cost: number | null;
  /** 見積もりの行数 */
  rows: number | null;
  executionMs: number | null;
  /** 全件読み込みする表 */
  seqScans: string[];
  findings: { high: number; medium: number };
  error: string | null;
}

type SortKey = 'cost' | 'time' | 'findings' | 'id';

export function BulkCheck({
  api,
  conn,
  statements,
  globalSets,
  globalStatements,
  active,
  sourceVersion,
  draftOf,
  onOpen,
  onOpenParams,
}: {
  api: Api;
  conn: Connections;
  statements: StatementEntry[];
  /** 共通パラメータ（statement ごとに、範囲に当たるものを重ねる） */
  globalSets: GlobalSet[];
  /** statement ごとの共通パラメータの設定 */
  globalStatements: StatementsSettings;
  /** この画面を表示している（表示するたびに、保存してある値を読み直す） */
  active: boolean;
  /** mapper XML の読み込みと下書きの版（変わったらパラメータの木を取り直す） */
  sourceVersion: string;
  /** そのファイルの下書き（なければ null）。下書きのあるファイルは下書きの内容で調べて実行する */
  draftOf: (file: string) => DraftParam;
  /** statement を開いて EXPLAIN タブを出す */
  onOpen: (id: string) => void;
  /** statement を開いて値を設定する */
  onOpenParams: (id: string) => void;
}) {
  const [analyze, setAnalyze] = useState(false);
  const [selection, setSelection] = useState<Set<string>>(loadSelection);
  const [rows, setRows] = useState<Row[]>([]);
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [sort, setSort] = useState<SortKey>('cost');
  const cancelled = useRef(false);

  const [schemas, setSchemas] = useState<{ version: string; trees: Record<string, ParamSchema>; errors: Record<string, string> } | null>(
    null,
  );
  const [schemaError, setSchemaError] = useState<string | null>(null);
  // 個別の値はクエリ画面で変わるので、表示するたびに読み直す
  const [shown, setShown] = useState(0);
  useEffect(() => {
    if (!active) return;
    setShown((n) => n + 1);
    if (schemas && schemas.version === sourceVersion) return;
    api.allParameters().then(
      (r) => {
        setSchemas({ version: sourceVersion, ...r });
        setSchemaError(null);
      },
      (e) => setSchemaError(message(e)),
    );
  }, [active, sourceVersion]);

  /** statement ごとの足りないもの。パラメータの木を取るまでは null */
  const status = useMemo(() => {
    if (!schemas) return null;
    const out = new Map<string, Readiness>();
    for (const s of statements) {
      const tree = schemas.trees[s.id];
      const error = schemas.errors[s.id];
      if (tree) out.set(s.id, readiness(s.id, tree, globalSets, globalStatements));
      else out.set(s.id, { value: undefined, missing: [], issues: [error ?? 'パラメータを調べられません'] });
    }
    return out;
  }, [schemas, statements, globalSets, globalStatements, shown]);

  // 足りないものがある statement は、選んであっても実行しない
  // 調べ終わるまでは実行しない
  const selected = status ? statements.filter((s) => selection.has(s.id) && status.has(s.id)) : [];
  const readyTargets = selected.filter((s) => isReady(status?.get(s.id)));
  const notReady = selected.filter((s) => !isReady(status?.get(s.id)));
  /** 値が足りないものを選んでいるときに、実行の前に出す確認 */
  const [confirming, setConfirming] = useState(false);
  const [runCount, setRunCount] = useState(0);
  const select = (ids: Set<string>) => {
    setSelection(ids);
    saveSelection(ids);
  };

  const start = () => {
    if (notReady.length > 0) setConfirming(true);
    else run(readyTargets);
  };

  const run = async (targets: StatementEntry[]) => {
    const current = conn.current;
    if (!current) return;
    setConfirming(false);
    setRunCount(targets.length);
    cancelled.current = false;
    setRunning(true);
    setRows([]);
    setProgress(0);
    const out: Row[] = [];
    for (const [i, s] of targets.entries()) {
      if (cancelled.current) break;
      const type = s.type ?? 'UNKNOWN';
      const planOnly = analyze && type !== 'SELECT';
      const row: Row = {
        id: s.id,
        type,
        status: 'ok',
        planOnly,
        cost: null,
        rows: null,
        executionMs: null,
        seqScans: [],
        findings: { high: 0, medium: 0 },
        error: null,
      };
      try {
        const ready = status?.get(s.id);
        if (!ready) throw new Error('パラメータを調べられませんでした');
        const r = await api.explain(
          s.id,
          ready.value,
          current.id,
          {
            analyze: analyze && !planOnly,
            buffers: analyze && !planOnly,
            readOnly: true,
            timeoutSeconds: 30,
            sixthExecution: false,
            repeat: 1,
          },
          draftOf(s.file),
        );
        const plan = parsePlan(r.plan);
        row.cost = plan.root.totalCost;
        row.rows = plan.root.planRows;
        row.executionMs = plan.executionMs;
        row.seqScans = flatten(plan.root)
          .map(({ node }) => node)
          .filter((n) => /Seq Scan/.test(n.title) && n.relation)
          .map((n) => n.relation!)
          .filter((t, j, a) => a.indexOf(t) === j);
        if (plan.analyzed) {
          for (const f of diagnose(plan)) if (f.severity !== 'info') row.findings[f.severity]++;
        }
      } catch (e) {
        row.status = 'error';
        row.error = message(e);
      }
      out.push(row);
      setRows([...out]);
      setProgress(i + 1);
    }
    setRunning(false);
  };

  const sorted = useMemo(() => {
    const list = [...rows];
    const num = (v: number | null) => (v === null ? -1 : v);
    list.sort((a, b) => {
      if (a.status !== b.status) return a.status === 'error' ? 1 : -1;
      switch (sort) {
        case 'cost':
          return num(b.cost) - num(a.cost);
        case 'time':
          return num(b.executionMs) - num(a.executionMs);
        case 'findings':
          return b.findings.high * 100 + b.findings.medium - (a.findings.high * 100 + a.findings.medium) || num(b.cost) - num(a.cost);
        default:
          return a.id.localeCompare(b.id);
      }
    });
    return list;
  }, [rows, sort]);

  const header = (key: SortKey, label: string, title?: string) => (
    <th title={title}>
      <button type="button" className={sort === key ? 'link sorted' : 'link'} onClick={() => setSort(key)}>
        {label}
        {sort === key ? ' ▼' : ''}
      </button>
    </th>
  );

  return (
    <div className="bulk page">
      <header className="panel-header">
        <h2>一括チェック</h2>
        <span className="hint">保存してある個別の値・共通パラメータで、statement をまとめて EXPLAIN します</span>
      </header>
      <div className="bulk-body">
        <BulkPicker
          statements={statements}
          status={status}
          selected={selection}
          onChange={select}
          onOpen={onOpenParams}
          disabled={running}
        />
        <div className="bulk-main">
          <div className="execute-bar">
            <ConnectionPicker conn={conn} />
            <label
              className="check"
              title="SELECT だけを実際に実行して測る（読み取り専用のトランザクションで、ロールバックする）。更新系は計画だけ"
            >
              <input type="checkbox" checked={analyze} onChange={(e) => setAnalyze(e.target.checked)} disabled={running} />
              ANALYZE（SELECT だけ実行して測る）
            </label>
            {running ? (
              <button type="button" onClick={() => (cancelled.current = true)}>
                止める（{progress} / {runCount}）
              </button>
            ) : (
              <button type="button" className="run" disabled={!conn.current || selected.length === 0 || confirming} onClick={start}>
                選んだ {selected.length} 件をチェック
              </button>
            )}
          </div>
          <p className="hint">計画のみ（既定）では SQL を実行しません。行をクリックすると、その statement の EXPLAIN タブを開きます。</p>
          {schemaError && <p className="error-text">{schemaError}</p>}
          {confirming && (
            <div className="notes warn bulk-confirm" role="alertdialog" aria-label="値が足りない statement の確認">
              <p>
                選んだうち {notReady.length} 件は値が足りません。未設定の項目は NULL
                として扱われるため、エラーになったり実際とは違う計画になったりします。
              </p>
              <ul>
                {notReady.slice(0, 10).map((s) => (
                  <li key={s.id}>
                    <code>{s.id}</code> {problemLabel(status!.get(s.id)!).detail}
                  </li>
                ))}
                {notReady.length > 10 && <li>ほか {notReady.length - 10} 件</li>}
              </ul>
              <div className="actions">
                <button type="button" className="run" disabled={readyTargets.length === 0} onClick={() => run(readyTargets)}>
                  値がそろった {readyTargets.length} 件だけチェック
                </button>
                <button type="button" onClick={() => run(selected)}>
                  足りないものも含めて {selected.length} 件チェック
                </button>
                <button type="button" className="link" onClick={() => setConfirming(false)}>
                  やめる
                </button>
              </div>
            </div>
          )}
          {rows.length > 0 && (
            <div className="grid-scroll bulk-scroll">
              <table className="bulk-table">
                <thead>
                  <tr>
                    {header('id', 'statement')}
                    <th>種類</th>
                    {header('cost', 'コスト', '見積もりのコスト（一番上のノード）')}
                    <th title="見積もりの行数">行数</th>
                    {header('time', '実行時間', 'ANALYZE のときだけ')}
                    <th title="全件読み込みする表">全件読み込み</th>
                    {header('findings', '指摘', 'ANALYZE のときだけ。要確認 / 確認')}
                  </tr>
                </thead>
                <tbody>
                  {sorted.map((r) => (
                    <tr
                      key={r.id}
                      className={r.status === 'error' ? 'error' : undefined}
                      onClick={() => {
                        onOpen(r.id);
                      }}
                    >
                      <td className="bulk-id" title={r.id}>
                        {r.id}
                      </td>
                      <td>
                        {r.type}
                        {r.planOnly && <span className="badge muted">計画のみ</span>}
                      </td>
                      {r.status === 'error' ? (
                        <td colSpan={5} className="error-text bulk-error" title={r.error ?? undefined}>
                          {r.error}
                        </td>
                      ) : (
                        <>
                          <td className="num">{r.cost === null ? '-' : Math.round(r.cost).toLocaleString('ja-JP')}</td>
                          <td className="num">{r.rows === null ? '-' : r.rows.toLocaleString('ja-JP')}</td>
                          <td className="num">{r.executionMs === null ? '-' : `${r.executionMs.toFixed(1)} ms`}</td>
                          <td className={r.seqScans.length > 0 ? 'warn-text' : undefined}>{r.seqScans.join(', ') || '-'}</td>
                          <td className="num">
                            {r.findings.high > 0 && <span className="severity high">要確認 {r.findings.high}</span>}
                            {r.findings.medium > 0 && <span className="severity medium">確認 {r.findings.medium}</span>}
                            {r.findings.high + r.findings.medium === 0 && '-'}
                          </td>
                        </>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
