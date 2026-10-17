// EXPLAIN タブ: 計画を取得（または貼り付け）して、ツリーで表示する
import { Fragment, useEffect, useMemo, useState } from 'react';
import type { Snapshot } from './compare';
import { loadSnapshots, ratioTo, saveSnapshots, summarize } from './compare';
import type { Finding } from './diagnose';
import { diagnose } from './diagnose';
import type { Connections } from './Execution';
import { ConnectionPicker, message } from './Execution';
import type { PValue } from './params';
import { toJson } from './params';
import type { Plan, PlanNode } from './plan';
import type { Timing } from './plan';
import { estimateRatio, flatten, parsePlan, relations, timings } from './plan';
import type { DraftParam, TableInfo } from './api';

/** 見積もりと実績の行数がこの倍率以上ずれていたら印を付ける */
const MISESTIMATE = 10;

interface Options {
  analyze: boolean;
  buffers: boolean;
  readOnly: boolean;
  timeoutSeconds: number;
  /** 6 回目の計画（汎用プランになるか）を見る */
  sixthExecution: boolean;
  /** ANALYZE を繰り返す回数 */
  repeat: number;
}

const OPTIONS_KEY = 'mybatis-pg.explain';

function loadOptions(): Options {
  const defaults: Options = { analyze: true, buffers: true, readOnly: false, timeoutSeconds: 60, sixthExecution: false, repeat: 1 };
  try {
    return { ...defaults, ...(JSON.parse(localStorage.getItem(OPTIONS_KEY) ?? '{}') as Partial<Options>) };
  } catch {
    return defaults;
  }
}

function saveOptions(o: Options) {
  try {
    localStorage.setItem(OPTIONS_KEY, JSON.stringify(o));
  } catch {
    // 保存できない環境では、この表示中だけ覚える
  }
}

/** 表示している計画と、その出どころ */
interface Shown {
  plan: Plan;
  /** 元の結果（コピー用） */
  raw: string;
  source: string;
  /** 6 回目の計画を見たとき、汎用プランだったか */
  generic: boolean | null;
  /** 繰り返したときの各回の実行時間（ms） */
  runs?: number[];
}

export function ExplainPanel({
  conn,
  statementId,
  value,
  draft,
  ready,
  onApiError,
}: {
  conn: Connections;
  statementId: string;
  value: PValue | undefined;
  /** 編集中の mapper XML（なければ null） */
  draft: DraftParam;
  ready: boolean;
  onApiError: (e: unknown) => void;
}) {
  const [options, setOptions] = useState<Options>(loadOptions);
  const [running, setRunning] = useState(false);
  const [shown, setShown] = useState<Shown | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pasting, setPasting] = useState(false);
  const [pasted, setPasted] = useState('');
  const [snapshots, setSnapshots] = useState<Snapshot[]>(() => loadSnapshots(statementId));
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [comparing, setComparing] = useState(false);
  const [storageError, setStorageError] = useState<string | null>(null);
  const current = conn.current;

  const updateSnapshots = (next: Snapshot[]) => {
    setSnapshots(next);
    setStorageError(saveSnapshots(statementId, next));
  };

  /** 表示している結果を、比較用に残す */
  const keep = (s: Shown) => {
    const now = new Date();
    const time = now.toTimeString().slice(0, 8);
    const name = window.prompt('比較用に残す名前（パラメータや変更の内容など）:', `${time} ${s.source.replace(/（.*$/, '')}`);
    if (name === null) return;
    const snapshot: Snapshot = {
      id: `${now.getTime()}-${Math.random().toString(36).slice(2, 8)}`,
      name: name.trim() || time,
      createdAt: now.toISOString(),
      source: s.source,
      raw: s.raw,
      parameters: toJson(value ?? { t: 'object', fields: [] }, ''),
      generic: s.generic,
    };
    updateSnapshots([...snapshots, snapshot]);
    setChecked((prev) => new Set(prev).add(snapshot.id));
  };

  const toggleChecked = (id: string) =>
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const compared = snapshots.filter((s) => checked.has(s.id));

  const set = (o: Partial<Options>) => {
    const next = { ...options, ...o };
    setOptions(next);
    saveOptions(next);
  };

  const run = async () => {
    if (!current) return;
    setRunning(true);
    setError(null);
    try {
      const r = await conn.api.explain(statementId, value, current.id, options, draft);
      setShown({
        plan: parsePlan(r.plan),
        raw: r.plan,
        source: `${current.name} で取得（${options.sixthExecution ? '6 回目の計画、' : ''}${r.analyze ? 'ANALYZE、ロールバック済み' : '計画のみ'}${r.runs ? `、${r.runs.length} 回の中央値` : ''}）`,
        generic: r.generic,
        runs: r.runs,
      });
    } catch (e) {
      onApiError(e);
      setShown(null);
      setError(message(e));
    } finally {
      setRunning(false);
    }
  };

  const showPasted = () => {
    try {
      setShown({ plan: parsePlan(pasted), raw: pasted, source: '貼り付けた結果', generic: null });
      setError(null);
      setPasting(false);
    } catch (e) {
      setError(message(e));
    }
  };

  return (
    <div className="explain">
      <div className="execute-bar">
        <ConnectionPicker conn={conn} />
        <button type="button" className="run" disabled={!current || !ready || running} onClick={run}>
          {running ? '取得中…' : 'EXPLAIN'}
        </button>
      </div>
      <div className="explain-options">
        <label className="check" title="実際に実行して時間と行数を測る。トランザクションは必ずロールバックする">
          <input type="checkbox" checked={options.analyze} onChange={(e) => set({ analyze: e.target.checked })} />
          ANALYZE（実行して測る）
        </label>
        <label className="check" title="読んだブロック数（キャッシュから / ディスクから）を出す">
          <input type="checkbox" checked={options.buffers} disabled={!options.analyze} onChange={(e) => set({ buffers: e.target.checked })} />
          BUFFERS
        </label>
        <label className="check" title="更新系の SQL はエラーにする">
          <input type="checkbox" checked={options.readOnly} onChange={(e) => set({ readOnly: e.target.checked })} />
          読み取り専用
        </label>
        <label className="check" title="JDBC は同じ SQL を 6 回目以降、サーバ側で準備した文として実行し、値に依らない計画（汎用プラン）を使うことがある。そのときの計画を見る（5 回分は計画を作るだけで実行しない）">
          <input type="checkbox" checked={options.sixthExecution} onChange={(e) => set({ sixthExecution: e.target.checked })} />
          6 回目の計画（汎用プランの確認）
        </label>
        <label className="check" title="ANALYZE を繰り返して、実行時間のばらつきを見る。表示する計画は中央値の回のもの">
          繰り返し
          <input
            type="number"
            min={1}
            max={20}
            value={options.repeat}
            disabled={!options.analyze}
            onChange={(e) => set({ repeat: Math.max(1, Math.min(20, Number(e.target.value) || 1)) })}
          />
          回
        </label>
        <label className="check">
          タイムアウト
          <input
            type="number"
            min={1}
            max={3600}
            value={options.timeoutSeconds}
            onChange={(e) => set({ timeoutSeconds: Math.max(1, Math.min(3600, Number(e.target.value) || 60)) })}
          />
          秒
        </label>
        <button type="button" className="link" onClick={() => setPasting(!pasting)}>
          本番などで取った結果を貼り付ける
        </button>
      </div>
      {!ready && current && <p className="hint">SQL を組み立てられないため実行できません。SQL タブに出ているエラーを直してください。</p>}
      {options.analyze && <p className="hint">ANALYZE では SQL を実際に実行します（トランザクションは必ずロールバックします）。</p>}

      {pasting && (
        <div className="explain-paste">
          <textarea
            aria-label="EXPLAIN の結果"
            spellCheck={false}
            placeholder="EXPLAIN の結果（テキスト形式または FORMAT JSON）を貼り付けてください"
            value={pasted}
            onChange={(e) => setPasted(e.target.value)}
          />
          <div className="actions">
            <button type="button" disabled={!pasted.trim()} onClick={showPasted}>
              表示
            </button>
            <button type="button" onClick={() => setPasting(false)}>
              閉じる
            </button>
          </div>
        </div>
      )}

      {snapshots.length > 0 && (
        <details className="snapshots" open>
          <summary>比較用に残した結果 {snapshots.length} 件</summary>
          <ul>
            {snapshots.map((s) => (
              <li key={s.id}>
                <label className="check">
                  <input type="checkbox" checked={checked.has(s.id)} onChange={() => toggleChecked(s.id)} />
                  <span className="snapshot-name">{s.name}</span>
                </label>
                <span className="hint snapshot-params" title={s.parameters}>
                  {s.parameters}
                </span>
                <button
                  type="button"
                  className="link"
                  onClick={() => {
                    const name = window.prompt('名前:', s.name);
                    if (name) updateSnapshots(snapshots.map((x) => (x.id === s.id ? { ...x, name } : x)));
                  }}
                >
                  名前変更
                </button>
                <button
                  type="button"
                  className="icon"
                  aria-label={`${s.name} を削除`}
                  onClick={() => {
                    updateSnapshots(snapshots.filter((x) => x.id !== s.id));
                    setChecked((prev) => {
                      const next = new Set(prev);
                      next.delete(s.id);
                      return next;
                    });
                  }}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
          <div className="actions">
            <button type="button" disabled={compared.length < 2} onClick={() => setComparing(true)}>
              選んだ {compared.length} 件を比べる
            </button>
            {comparing && (
              <button type="button" onClick={() => setComparing(false)}>
                比較を閉じる
              </button>
            )}
            {compared.length < 2 && <span className="hint">2 件以上選ぶと比べられます。</span>}
          </div>
          {storageError && <p className="error-text">{storageError}</p>}
        </details>
      )}

      {comparing && compared.length >= 2 ? (
        <Comparison snapshots={compared} />
      ) : (
        <>
          {error && <pre className="render-error">{error}</pre>}
          {shown && !error && (
            <>
              <PlanView shown={shown} onKeep={() => keep(shown)} />
              <Tables conn={conn} plan={shown.plan} auto={shown.source !== '貼り付けた結果'} />
            </>
          )}
        </>
      )}
    </div>
  );
}

/** 残した結果を並べて比べる */
function Comparison({ snapshots }: { snapshots: Snapshot[] }) {
  const plans = useMemo(() => snapshots.map((s) => parsePlan(s.raw)), [snapshots]);
  const sums = useMemo(() => plans.map(summarize), [plans]);
  /** 数値の行。小さいほど良い値は、1 つ目に対する倍率も出す */
  const numberRow = (label: string, get: (i: number) => number | null, unit: string, lowerIsBetter = true) => {
    const values = sums.map((_, i) => get(i));
    const present = values.filter((v): v is number => v !== null);
    const best = present.length > 1 ? Math.min(...present) : null;
    return (
      <tr>
        <th>{label}</th>
        {values.map((v, i) => {
          const r = i === 0 ? null : ratioTo(values[0], v);
          return (
            <td key={i} className={lowerIsBetter && v !== null && v === best && present.length > 1 ? 'num best' : 'num'}>
              {v === null ? '-' : `${unit === ' ms' ? fmtMs(v) : fmtInt(Math.round(v))}${unit}`}
              {r !== null && <span className={r < 0.9 ? 'ratio better' : r > 1.1 ? 'ratio worse' : 'ratio'}>×{r.toFixed(2)}</span>}
            </td>
          );
        })}
      </tr>
    );
  };
  return (
    <div className="comparison">
      <div className="grid-scroll">
        <table className="compare-table">
          <thead>
            <tr>
              <th />
              {snapshots.map((s) => (
                <th key={s.id} title={s.parameters}>
                  {s.name}
                  <span className="hint">{s.source}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {numberRow('実行時間', (i) => sums[i].executionMs, ' ms')}
            {numberRow('計画時間', (i) => sums[i].planningMs, ' ms')}
            {numberRow('コスト（見積もり）', (i) => sums[i].totalCost, '')}
            {numberRow('キャッシュから読んだブロック', (i) => sums[i].hit, '')}
            {numberRow('ディスクから読んだブロック', (i) => sums[i].read, '')}
            {numberRow('一時ファイルのブロック', (i) => sums[i].temp, '')}
            {numberRow('結果の行数', (i) => sums[i].rows, '', false)}
            <tr>
              <th>汎用プラン</th>
              {snapshots.map((s) => (
                <td key={s.id}>{s.generic === null ? '-' : s.generic ? '汎用プラン' : '値ごとの計画'}</td>
              ))}
            </tr>
            <tr>
              <th>時間のかかったノード</th>
              {sums.map((s, i) => (
                <td key={i} className="list">
                  {s.hotNodes.length === 0 ? '-' : s.hotNodes.map((h) => `${h.title}（${Math.round(h.share * 100)}%）`).join('\n')}
                </td>
              ))}
            </tr>
            <tr>
              <th>表の読み方</th>
              {sums.map((s, i) => (
                <td key={i} className="list">
                  {s.scans.join('\n') || '-'}
                </td>
              ))}
            </tr>
            <tr>
              <th>指摘</th>
              {sums.map((s, i) => (
                <td key={i}>
                  要確認 {s.findings.high} / 確認 {s.findings.medium} / 参考 {s.findings.info}
                </td>
              ))}
            </tr>
            <tr>
              <th>パラメータ</th>
              {snapshots.map((s) => (
                <td key={s.id} className="list params-cell">
                  {s.parameters}
                </td>
              ))}
            </tr>
          </tbody>
        </table>
      </div>
      <p className="hint">×の数字は、左端の結果に対する倍率です（時間やブロックは小さいほど良い）。</p>
      <div className="compare-plans">
        {snapshots.map((s, i) => (
          <div key={s.id} className="compare-plan">
            <h3>{s.name}</h3>
            <PlanView shown={{ plan: plans[i], raw: s.raw, source: s.source, generic: s.generic }} idPrefix={`cmp${i}`} />
          </div>
        ))}
      </div>
    </div>
  );
}

function fmtMs(ms: number): string {
  return ms >= 100 ? ms.toFixed(0) : ms >= 1 ? ms.toFixed(1) : ms.toFixed(3);
}

function fmtInt(n: number): string {
  return n.toLocaleString('ja-JP');
}

function PlanView({ shown, onKeep, idPrefix = 'main' }: { shown: Shown; onKeep?: () => void; idPrefix?: string }) {
  const { plan } = shown;
  const rows = useMemo(() => flatten(plan.root), [plan]);
  const times = useMemo(() => timings(plan.root), [plan]);
  const findings = useMemo(() => diagnose(plan), [plan]);
  const marks = useMemo(() => {
    const m = new Map<string, Finding['severity']>();
    for (const f of findings) if (!m.has(f.path)) m.set(f.path, f.severity);
    return m;
  }, [findings]);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const total = plan.executionMs ?? times.get(plan.root)!.inclusive;
  const toggle = (path: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else next.add(path);
      return next;
    });
  const columns = plan.analyzed ? 6 : 3;
  /** 指摘のノードを開いて、見える位置まで動かす */
  const reveal = (path: string) => {
    setOpen((prev) => new Set(prev).add(path));
    requestAnimationFrame(() => document.getElementById(`plan-node-${idPrefix}-${path}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }));
  };

  return (
    <div className="plan">
      <div className="plan-summary">
        <span className="hint">{shown.source}</span>
        {plan.executionMs !== null && (
          <span>
            実行時間 <strong>{fmtMs(plan.executionMs)} ms</strong>
          </span>
        )}
        {plan.planningMs !== null && <span>計画時間 {fmtMs(plan.planningMs)} ms</span>}
        {shown.runs && shown.runs.length > 1 && <Runs runs={shown.runs} />}
        {!plan.analyzed && <span className="hint">見積もりのみ（ANALYZE なし）</span>}
        <span className="plan-actions">
          {onKeep && (
            <button type="button" onClick={onKeep}>
              比較用に残す
            </button>
          )}
          <button type="button" onClick={() => navigator.clipboard.writeText(shown.raw).catch(() => undefined)}>
            元の結果をコピー
          </button>
        </span>
      </div>
      {shown.generic !== null && (
        <div className={shown.generic ? 'notes warn generic' : 'notes info generic'}>
          {shown.generic ? (
            <>
              <strong>6 回目以降は汎用プラン（パラメータの値を使わない計画）が選ばれます。</strong>
              アプリで同じ SQL を繰り返し実行すると、この計画で動きます。値によって最適な計画が違う場合（特定の値だけ行が多いなど）は、遅くなることがあります。
              値ごとの計画と比べるには、このオプションを外して EXPLAIN してください。
              {findings.some((f) => f.title.startsWith('実際の行数が見積もり')) && (
                <p className="generic-misestimate">
                  汎用プランでは、パラメータの値を見ずに行数を見積もります。下の「見積もりのずれ」は、そのために起きている可能性があります。値ごとの計画では見積もりが合っているなら、汎用プランが原因です。
                </p>
              )}
            </>
          ) : (
            <>6 回目以降も、値ごとの計画が選ばれます（汎用プランは使われません）。</>
          )}
        </div>
      )}
      <Findings findings={findings} analyzed={plan.analyzed} onReveal={reveal} />
      <div className="grid-scroll plan-scroll">
        <table className="plan-table">
          <thead>
            <tr>
              <th>ノード</th>
              {plan.analyzed ? (
                <>
                  <th title="そのノード自身にかかった時間（子のノードの時間を除く）。並列実行の部分はプロセス数で割った目安">自身の時間</th>
                  <th title="子のノードを含む時間（全ループ分。並列実行の部分はプロセス数で割った目安）">合計</th>
                  <th title="実績 / 見積もり（1 ループあたり）">行数</th>
                  <th>ループ</th>
                  <th title="キャッシュから / ディスクから（ブロック数）">バッファ</th>
                </>
              ) : (
                <>
                  <th>コスト</th>
                  <th>行数（見積もり）</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {rows.map(({ node, depth, path }) => (
              <Fragment key={path}>
                <PlanRow path={path} idPrefix={idPrefix} mark={marks.get(path)} node={node} timing={times.get(node)!} depth={depth} total={total} analyzed={plan.analyzed} open={open.has(path)} onToggle={() => toggle(path)} />
                {open.has(path) && (
                  <tr className="plan-details">
                    <td colSpan={columns}>
                      <Details node={node} depth={depth} />
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">ノードをクリックすると、条件などの詳細を表示します。</p>
    </div>
  );
}

function PlanRow({
  path,
  idPrefix,
  mark,
  node,
  timing,
  depth,
  total,
  analyzed,
  open,
  onToggle,
}: {
  path: string;
  idPrefix: string;
  mark: Finding['severity'] | undefined;
  node: PlanNode;
  timing: Timing;
  depth: number;
  total: number;
  analyzed: boolean;
  open: boolean;
  onToggle: () => void;
}) {
  const self = timing.self;
  const share = total > 0 ? Math.min(1, self / total) : 0;
  const ratio = estimateRatio(node);
  const off = ratio !== null && (ratio >= MISESTIMATE || ratio <= 1 / MISESTIMATE);
  return (
    <tr id={`plan-node-${idPrefix}-${path}`} className={`plan-row${open ? ' open' : ''}`} onClick={onToggle}>
      <td className="plan-node" style={{ paddingLeft: `${0.4 + depth * 1.1}rem` }}>
        <span className="twisty">{open ? '▾' : '▸'}</span>
        {mark && <span className={`finding-mark ${mark}`} title="指摘があります" />}
        {node.title}
        {node.neverExecuted && <span className="badge muted">実行されず</span>}
      </td>
      {analyzed ? (
        <>
          <td className="num self-time" title={`${(share * 100).toFixed(1)}%`}>
            <span className="bar" style={{ width: `${share * 100}%`, opacity: 0.25 + share * 0.75 }} />
            <span className="value">
              {node.actual ? `${fmtMs(self)} ms` : '-'}
              {share >= 0.01 && <span className="share">{Math.round(share * 100)}%</span>}
            </span>
          </td>
          <td className="num">{node.actual ? `${fmtMs(timing.inclusive)} ms` : '-'}</td>
          <td className={off ? 'num misestimate' : 'num'} title={ratio === null ? undefined : `実績は見積もりの ${ratio >= 1 ? ratio.toFixed(1) + ' 倍' : '1/' + (1 / ratio).toFixed(1)}`}>
            {node.actual ? fmtInt(node.actual.rows) : '-'} / {node.planRows !== null ? fmtInt(node.planRows) : '-'}
            {off && <span className="warn-mark"> ⚠</span>}
          </td>
          <td className="num">{node.actual ? fmtInt(node.actual.loops) : '-'}</td>
          <td className="num">
            {node.buffers ? (
              <>
                {fmtInt(node.buffers.hit)} / <span className={node.buffers.read > 0 ? 'disk-read' : undefined}>{fmtInt(node.buffers.read)}</span>
                {(node.buffers.tempRead > 0 || node.buffers.tempWritten > 0) && <span className="badge temp" title={`一時ファイル 読み ${node.buffers.tempRead} / 書き ${node.buffers.tempWritten}`}>一時</span>}
              </>
            ) : (
              '-'
            )}
          </td>
        </>
      ) : (
        <>
          <td className="num">{node.totalCost !== null ? `${node.startupCost}..${node.totalCost}` : '-'}</td>
          <td className="num">{node.planRows !== null ? fmtInt(node.planRows) : '-'}</td>
        </>
      )}
    </tr>
  );
}

function fmtBytes(n: number): string {
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(1)} GB`;
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(0)} kB`;
  return `${n} B`;
}

/** 日時を「2026-10-16 09:30（3 日前）」の形にする */
function fmtWhen(iso: string | null): string {
  if (!iso) return 'なし';
  const d = new Date(iso);
  const days = Math.floor((Date.now() - d.getTime()) / 86_400_000);
  const pad = (n: number) => String(n).padStart(2, '0');
  const text = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return `${text}（${days <= 0 ? '今日' : `${days} 日前`}）`;
}

/** 統計が古いとみなす日数 */
const STALE_DAYS = 30;
/** 不要な行がこの割合を超えたら知らせる */
const DEAD_RATIO = 0.2;

/** 計画に出てくる表の情報（行数、サイズ、統計の更新日時、インデックス） */
function Tables({ conn, plan, auto }: { conn: Connections; plan: Plan; auto: boolean }) {
  const names = useMemo(() => relations(plan.root), [plan]);
  const [info, setInfo] = useState<{ tables: TableInfo[]; notFound: string[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const current = conn.current;

  const load = () => {
    if (!current || names.length === 0) return;
    setLoading(true);
    setError(null);
    conn.api
      .tables(current.id, names)
      .then(setInfo, (e) => {
        setInfo(null);
        setError(message(e));
      })
      .finally(() => setLoading(false));
  };

  // DB から取った計画なら、表の情報もすぐに読む（貼り付けた計画は、どの DB のものか分からないので押したときだけ）
  const key = names.join(',');
  useEffect(() => {
    setInfo(null);
    if (auto) load();
  }, [key, plan]);

  if (names.length === 0) return null;
  return (
    <details className="tables" open>
      <summary>
        表の情報
        {current && (
          <button type="button" className="link" disabled={loading} onClick={load}>
            {loading ? '読み込み中…' : `${current.name} から読む`}
          </button>
        )}
      </summary>
      {!auto && !info && <p className="hint">貼り付けた計画の表の情報を、選んでいる接続先から読みます（本番とはデータ量が違うことに注意）。</p>}
      {error && <pre className="render-error">{error}</pre>}
      {info?.notFound.length ? <p className="hint">見つからない表: {info.notFound.join(', ')}</p> : null}
      {info?.tables.map((t) => (
        <TableCard key={`${t.schema}.${t.name}`} t={t} />
      ))}
    </details>
  );
}

function TableCard({ t }: { t: TableInfo }) {
  const analyzedDaysAgo = t.lastAnalyze ? (Date.now() - new Date(t.lastAnalyze).getTime()) / 86_400_000 : null;
  const stale = analyzedDaysAgo === null || analyzedDaysAgo > STALE_DAYS;
  const deadRatio = t.liveRows && t.deadRows !== null ? t.deadRows / Math.max(1, t.liveRows) : 0;
  return (
    <div className="table-card">
      <h4>
        {t.schema}.{t.name}
      </h4>
      <dl className="table-facts">
        <dt>行数（統計）</dt>
        <dd>{fmtInt(t.estimatedRows)}</dd>
        <dt>サイズ</dt>
        <dd>
          {fmtBytes(t.totalBytes)}（表 {fmtBytes(t.tableBytes)}、インデックスなど {fmtBytes(t.totalBytes - t.tableBytes)}）
        </dd>
        <dt>不要な行</dt>
        <dd className={deadRatio > DEAD_RATIO ? 'warn-text' : undefined}>
          {t.deadRows === null ? '-' : `${fmtInt(t.deadRows)} 行（生きている行の ${Math.round(deadRatio * 100)}%）`}
          {deadRatio > DEAD_RATIO && ' ⚠ VACUUM が追いついていない可能性があります'}
        </dd>
        <dt>統計の更新</dt>
        <dd className={stale ? 'warn-text' : undefined}>
          {fmtWhen(t.lastAnalyze)}
          {stale && ` ⚠ ${t.lastAnalyze ? `${STALE_DAYS} 日以上前です` : '一度も更新されていません'}。見積もりがずれる原因になります（ANALYZE ${t.name}）`}
        </dd>
        <dt>読まれた回数</dt>
        <dd>
          全件読み込み {t.seqScans === null ? '-' : fmtInt(t.seqScans)} 回 / インデックス {t.indexScans === null ? '-' : fmtInt(t.indexScans)} 回
        </dd>
      </dl>
      <table className="index-table">
        <thead>
          <tr>
            <th>インデックス</th>
            <th>定義</th>
            <th>サイズ</th>
            <th title="統計を取り始めてから、このインデックスを使って読んだ回数">使用回数</th>
          </tr>
        </thead>
        <tbody>
          {t.indexes.length === 0 && (
            <tr>
              <td colSpan={4} className="hint">
                インデックスはありません
              </td>
            </tr>
          )}
          {t.indexes.map((i) => (
            <tr key={i.name}>
              <td>
                {i.name}
                {i.primary && <span className="badge">主キー</span>}
                {!i.primary && i.unique && <span className="badge">一意</span>}
                {!i.valid && <span className="badge temp">無効</span>}
              </td>
              <td>
                <code>{i.definition.replace(/^CREATE (UNIQUE )?INDEX \S+ ON \S+ (USING btree )?/, '')}</code>
              </td>
              <td className="num">{fmtBytes(i.bytes)}</td>
              <td className={i.scans === 0 ? 'num warn-text' : 'num'} title={i.scans === 0 ? '使われていません（統計を取り始めてから一度も）' : undefined}>
                {i.scans === null ? '-' : fmtInt(i.scans)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** 繰り返したときの実行時間（最小・中央値・最大と各回） */
function Runs({ runs }: { runs: number[] }) {
  const sorted = [...runs].sort((a, b) => a - b);
  const median = sorted.length % 2 === 1 ? sorted[(sorted.length - 1) / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
  return (
    <span className="runs" title={`各回: ${runs.map((r) => fmtMs(r)).join(', ')} ms（1 回目はキャッシュに乗っていないことがある）`}>
      {runs.length} 回: 最小 {fmtMs(sorted[0])} / 中央値 <strong>{fmtMs(median)}</strong> / 最大 {fmtMs(sorted[sorted.length - 1])} ms
    </span>
  );
}

const SEVERITY_LABELS: Record<Finding['severity'], string> = { high: '要確認', medium: '確認', info: '参考' };

/** 自動の指摘の一覧 */
function Findings({ findings, analyzed, onReveal }: { findings: Finding[]; analyzed: boolean; onReveal: (path: string) => void }) {
  if (!analyzed) {
    return <p className="hint">ANALYZE なしの計画では、実績に基づく指摘はできません。</p>;
  }
  if (findings.length === 0) {
    return <p className="hint findings-none">よくある問題のパターンは見つかりませんでした。</p>;
  }
  return (
    <ul className="findings">
      {findings.map((f, i) => (
        <li key={i} className={f.severity}>
          <div className="finding-head">
            <span className={`severity ${f.severity}`}>{SEVERITY_LABELS[f.severity]}</span>
            <strong>{f.title}</strong>
            <button type="button" className="link" onClick={() => onReveal(f.path)}>
              {f.node}
            </button>
          </div>
          <div className="finding-evidence">
            <code>{f.evidence}</code>
          </div>
          <div className="finding-advice">{f.advice}</div>
        </li>
      ))}
    </ul>
  );
}

function Details({ node, depth }: { node: PlanNode; depth: number }) {
  const items: [string, string][] = [...node.details];
  if (node.totalCost !== null) items.push(['コスト', `${node.startupCost}..${node.totalCost}`]);
  if (node.actual) items.push(['実績の時間（1 ループあたり）', `${node.actual.startup}..${node.actual.total} ms`]);
  if (node.buffers) {
    const b = node.buffers;
    items.push(['バッファ', `キャッシュ ${b.hit}、ディスク ${b.read}、更新 ${b.dirtied}、書き出し ${b.written}、一時ファイル 読み ${b.tempRead} / 書き ${b.tempWritten}`]);
  }
  return (
    <dl className="plan-detail-list" style={{ marginLeft: `${1.4 + depth * 1.1}rem` }}>
      {items.map(([k, v], i) => (
        <Fragment key={i}>
          <dt>{k}</dt>
          <dd>
            <code>{v}</code>
          </dd>
        </Fragment>
      ))}
    </dl>
  );
}
