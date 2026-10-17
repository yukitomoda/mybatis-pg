// 全 statement の一括チェック: 保存してある値でまとめて EXPLAIN し、重そうなものを一覧にする
import { useMemo, useRef, useState } from 'react';
import type { Api } from './api';
import { BulkPicker, loadSelection, saveSelection } from './BulkPicker';
import { diagnose } from './diagnose';
import type { Connections } from './Execution';
import { ConnectionPicker, message } from './Execution';
import { resolveTemplates } from './gen';
import { loadParams } from './paramStore';
import { effectiveValue, missingParams } from './params';
import { flatten, parsePlan } from './plan';
import type { GlobalSet, StatementsSettings } from './globalModel';
import { applicable, combine } from './globalModel';
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
  /** 値がない（NULL として扱われる）項目 */
  missing: string[];
}

type SortKey = 'cost' | 'time' | 'findings' | 'id';

export function BulkCheck({
  api,
  conn,
  statements,
  globalSets,
  globalStatements,
  onOpen,
}: {
  api: Api;
  conn: Connections;
  statements: StatementEntry[];
  /** 共通パラメータ（statement ごとに、範囲に当たるものを重ねる） */
  globalSets: GlobalSet[];
  /** statement ごとの共通パラメータの設定 */
  globalStatements: StatementsSettings;
  /** statement を開いて EXPLAIN タブを出す */
  onOpen: (id: string) => void;
}) {
  const [analyze, setAnalyze] = useState(false);
  const [selection, setSelection] = useState<Set<string>>(loadSelection);
  const [rows, setRows] = useState<Row[]>([]);
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [sort, setSort] = useState<SortKey>('cost');
  const cancelled = useRef(false);

  const targets = statements.filter((s) => selection.has(s.id));
  const select = (ids: Set<string>) => {
    setSelection(ids);
    saveSelection(ids);
  };

  const run = async () => {
    const current = conn.current;
    if (!current) return;
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
        missing: [],
      };
      try {
        // 画面で開いたときと同じく、個別の値・共通パラメータ・既定の値・式から値を作る
        const schema = (await api.parameters(s.id)).tree;
        const effective = effectiveValue(schema, combine(applicable(globalSets, globalStatements[s.id])), loadParams(s.id));
        row.missing = missingParams(schema, effective);
        const resolved = resolveTemplates(effective, new Map());
        if (resolved.issues.length > 0) throw new Error(`式の誤り: ${resolved.issues.map((x) => `${x.path}: ${x.message}`).join('、')}`);
        const r = await api.explain(s.id, resolved.value, current.id, {
          analyze: analyze && !planOnly,
          buffers: analyze && !planOnly,
          readOnly: true,
          timeoutSeconds: 30,
          sixthExecution: false,
          repeat: 1,
        });
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

  const missingCount = rows.filter((r) => r.missing.length > 0).length;

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
        <BulkPicker statements={statements} selected={selection} onChange={select} disabled={running} />
        <div className="bulk-main">
          <div className="execute-bar">
            <ConnectionPicker conn={conn} />
            <label className="check" title="SELECT だけを実際に実行して測る（読み取り専用のトランザクションで、ロールバックする）。更新系は計画だけ">
              <input type="checkbox" checked={analyze} onChange={(e) => setAnalyze(e.target.checked)} disabled={running} />
              ANALYZE（SELECT だけ実行して測る）
            </label>
            {running ? (
              <button type="button" onClick={() => (cancelled.current = true)}>
                止める（{progress} / {targets.length}）
              </button>
            ) : (
              <button type="button" className="run" disabled={!conn.current || targets.length === 0} onClick={run}>
                選んだ {targets.length} 件をチェック
              </button>
            )}
          </div>
          <p className="hint">計画のみ（既定）では SQL を実行しません。行をクリックすると、その statement の EXPLAIN タブを開きます。</p>
          {missingCount > 0 && (
            <p className="notes warn">
              {missingCount} 件の statement に未設定の項目があります。未設定の項目は NULL として扱うため、実際とは違う計画になることがあります。
            </p>
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
                        {r.missing.length > 0 && (
                          <span className="badge warn" title={`未設定（NULL として扱われます）: ${r.missing.join(', ')}`}>
                            未設定 {r.missing.length}
                          </span>
                        )}
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
