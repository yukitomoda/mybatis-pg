// EXPLAIN タブ: 計画を取得（または貼り付け）して、ツリーで表示する
import { Fragment, useMemo, useState } from 'react';
import type { Connections } from './Execution';
import { ConnectionPicker, message } from './Execution';
import type { PValue } from './params';
import type { Plan, PlanNode } from './plan';
import type { Timing } from './plan';
import { estimateRatio, flatten, parsePlan, timings } from './plan';

/** 見積もりと実績の行数がこの倍率以上ずれていたら印を付ける */
const MISESTIMATE = 10;

interface Options {
  analyze: boolean;
  buffers: boolean;
  readOnly: boolean;
  timeoutSeconds: number;
}

const OPTIONS_KEY = 'mybatis-pg.explain';

function loadOptions(): Options {
  const defaults: Options = { analyze: true, buffers: true, readOnly: false, timeoutSeconds: 60 };
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
}

export function ExplainPanel({
  conn,
  statementId,
  value,
  ready,
  onApiError,
}: {
  conn: Connections;
  statementId: string;
  value: PValue | undefined;
  ready: boolean;
  onApiError: (e: unknown) => void;
}) {
  const [options, setOptions] = useState<Options>(loadOptions);
  const [running, setRunning] = useState(false);
  const [shown, setShown] = useState<Shown | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pasting, setPasting] = useState(false);
  const [pasted, setPasted] = useState('');
  const current = conn.current;

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
      const r = await conn.api.explain(statementId, value, current.id, options);
      setShown({
        plan: parsePlan(r.plan),
        raw: r.plan,
        source: `${current.name} で取得（${r.analyze ? 'ANALYZE、ロールバック済み' : '計画のみ'}、${r.elapsedMillis} ms）`,
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
      setShown({ plan: parsePlan(pasted), raw: pasted, source: '貼り付けた結果' });
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

      {error && <pre className="render-error">{error}</pre>}
      {shown && !error && <PlanView shown={shown} />}
    </div>
  );
}

function fmtMs(ms: number): string {
  return ms >= 100 ? ms.toFixed(0) : ms >= 1 ? ms.toFixed(1) : ms.toFixed(3);
}

function fmtInt(n: number): string {
  return n.toLocaleString('ja-JP');
}

function PlanView({ shown }: { shown: Shown }) {
  const { plan } = shown;
  const rows = useMemo(() => flatten(plan.root), [plan]);
  const times = useMemo(() => timings(plan.root), [plan]);
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
        {!plan.analyzed && <span className="hint">見積もりのみ（ANALYZE なし）</span>}
        <button type="button" onClick={() => navigator.clipboard.writeText(shown.raw).catch(() => undefined)}>
          元の結果をコピー
        </button>
      </div>
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
                <PlanRow node={node} timing={times.get(node)!} depth={depth} total={total} analyzed={plan.analyzed} open={open.has(path)} onToggle={() => toggle(path)} />
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
  node,
  timing,
  depth,
  total,
  analyzed,
  open,
  onToggle,
}: {
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
    <tr className={`plan-row${open ? ' open' : ''}`} onClick={onToggle}>
      <td className="plan-node" style={{ paddingLeft: `${0.4 + depth * 1.1}rem` }}>
        <span className="twisty">{open ? '▾' : '▸'}</span>
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
