// リレーション（試作）: statement の結合の条件から推し量った、テーブルどうしのつながりを見る
import { useEffect, useMemo, useState } from 'react';
import type { Api, RelationLink, RelationStatement, RelationsResult, RelationsTiming } from './api';
import type { Connections } from './Execution';
import { ConnectionPicker, message } from './Execution';
import { SideSplitter, useSideWidth } from './Splitter';
import type { GlobalSet } from './globalModel';
import { resolveTemplates } from './gen';

/** 解析に使う共通パラメータの設定（ブラウザに覚える） */
const SET_KEY = 'mybatis-pg.relations.set';

function loadSetId(): string {
  try {
    return localStorage.getItem(SET_KEY) ?? '';
  } catch {
    return '';
  }
}

/** schema.table.column の表の部分（schema.table） */
const tableOf = (col: string) => col.slice(0, col.lastIndexOf('.'));
/** 表示用。schema が 1 つだけなら省く */
const shorten = (col: string, oneSchema: boolean) => (oneSchema ? col.slice(col.indexOf('.') + 1) : col);

/** 時間を「1 時間 5 分」「3 分」「40 秒」の形にする */
export function duration(ms: number): string {
  const sec = Math.round(ms / 1000);
  if (sec < 60) return `${sec} 秒`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min} 分`;
  return `${Math.floor(min / 60)} 時間${min % 60 ? ` ${min % 60} 分` : ''}`;
}

/** 1 件あたりの時間と、そのうち計画を作った時間 */
function perStatement(t: RelationsTiming): string {
  if (t.perStatementMillis === undefined) return '';
  return `（1 件 ${(t.perStatementMillis / 1000).toFixed(2)} 秒、うち計画 ${((t.planPerStatementMillis ?? 0) / 1000).toFixed(2)} 秒）`;
}

export function RelationsScreen({
  api,
  conn,
  globalSets,
  active,
  onOpen,
  focus,
}: {
  api: Api;
  conn: Connections;
  /** 共通パラメータの設定（1 つ選んで、その値をすべての statement の解析に使う） */
  globalSets: GlobalSet[];
  active: boolean;
  onOpen: (id: string) => void;
  /** 開いたときに見る列（schema.table.column。クエリの画面のパラメータから飛んできたとき） */
  focus: string | null;
}) {
  const side = useSideWidth('relations');
  const connectionId = conn.current?.id;
  const [result, setResult] = useState<RelationsResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [table, setTable] = useState<string | null>(null);
  /** 見方: 表と列・statement・パラメータ名 */
  const [view, setView] = useState<'tables' | 'statements' | 'names'>('tables');
  /** 強調する列（パラメータから飛んできたとき、列を押したとき） */
  const [column, setColumn] = useState<string | null>(focus);
  const [statement, setStatement] = useState<string | null>(null);
  const [name, setName] = useState<string | null>(null);
  // パラメータから飛んできたら、その列の表を開く
  useEffect(() => {
    if (focus) {
      setView('tables');
      setTable(tableOf(focus));
      setColumn(focus);
    }
  }, [focus]);
  /** 列の表を開く */
  const goColumn = (col: string) => {
    setView('tables');
    setTable(tableOf(col));
    setColumn(col);
  };
  /** 手で設定したリレーションを変えて、読み直す */
  const change = (c: Parameters<Api['changeManualRelation']>[0]): Promise<void> =>
    api.changeManualRelation(c).then(load, (e) => setError(message(e)));

  const load = () => {
    if (!connectionId) return;
    api.relations(connectionId).then(
      (r) => {
        setResult(r);
        setError(null);
      },
      (e) => setError(message(e)),
    );
  };
  useEffect(() => {
    if (active) load();
  }, [active, connectionId]);
  // 解析中は、進み具合を読み直す
  const running = !!result?.progress;
  useEffect(() => {
    if (!running) return;
    const t = window.setInterval(load, 1000);
    return () => window.clearInterval(t);
  }, [running, connectionId]);

  const [setId, setSetId] = useState(loadSetId);
  const chosen = globalSets.find((g) => g.id === setId);
  const chooseSet = (id: string) => {
    setSetId(id);
    try {
      localStorage.setItem(SET_KEY, id);
    } catch {
      // 保存できない環境では、この表示中だけ覚える
    }
  };
  const start = () => {
    if (!connectionId) return;
    // {{date:now}} などの式は、値にしてから送る
    const params = chosen ? resolveTemplates(chosen.values, new Map()).value : undefined;
    api.analyzeRelations(connectionId, params).then(load, (e) => setError(message(e)));
  };
  const stop = () => {
    if (!connectionId) return;
    api.stopRelations(connectionId).then(load, (e) => setError(message(e)));
  };
  // 一時停止した後か、まだ解析していない statement がある（続きから解析できる）
  const partial = !!result?.updatedAt && (!result.complete || (result.pending ?? 0) > 0);

  const links = result?.links ?? [];
  const oneSchema = useMemo(() => new Set(links.flatMap((l) => [l.a, l.b]).map((c) => c.slice(0, c.indexOf('.')))).size <= 1, [links]);
  const s = (col: string) => shorten(col, oneSchema);
  // DB の表（リレーションがまだない表にも、追加できるように）
  const [allTables, setAllTables] = useState<string[]>([]);
  useEffect(() => {
    if (!active || !connectionId) return;
    api.dbTables(connectionId).then(
      (r) => setAllTables(r.tables),
      () => setAllTables([]),
    );
  }, [active, connectionId]);
  const tables = useMemo(() => {
    const count = new Map<string, number>();
    for (const l of links) {
      for (const t of new Set([tableOf(l.a), tableOf(l.b)])) count.set(t, (count.get(t) ?? 0) + 1);
    }
    if (focus && !count.has(tableOf(focus))) count.set(tableOf(focus), 0);
    // リレーションのある表を先に、ほかの表を後に
    for (const t of allTables) if (!count.has(t)) count.set(t, 0);
    return [...count.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  }, [links, focus, allTables]);
  const shown = tables.filter(([t]) => t.toLowerCase().includes(filter.trim().toLowerCase()));
  const origins = useMemo(() => new Map((result?.origins ?? []).map((o) => [o.column, o.origins])), [result]);
  const details = result?.statementDetails ?? [];
  const keys = result?.keys ?? {};
  const names = result?.names ?? {};
  const q = filter.trim().toLowerCase();
  const shownStatements = details.filter((d) => d.id.toLowerCase().includes(q));
  const shownNames = Object.keys(names).filter((n) => n.toLowerCase().includes(q));
  const detailOf = useMemo(() => new Map(details.map((d) => [d.id, d])), [details]);

  return (
    <div className="relations">
      <div className="relations-head">
        <h2>リレーション</h2>
        <ConnectionPicker conn={conn} />
        <select
          aria-label="解析に使う共通パラメータ"
          title="選んだ共通パラメータの値（スキーマを決める ${schema} など）を、すべての statement の解析に使います。値のない項目は仮の値で埋めます"
          value={chosen ? chosen.id : ''}
          onChange={(e) => chooseSet(e.target.value)}
          disabled={running}
        >
          <option value="">共通パラメータなし</option>
          {globalSets.map((g) => (
            <option key={g.id} value={g.id}>
              {g.name}
            </option>
          ))}
        </select>
        {running ? (
          <button type="button" disabled={result?.progress?.stopping} onClick={stop}>
            {result?.progress?.stopping ? '止めています…' : '一時停止'}
          </button>
        ) : (
          <button type="button" className="run" disabled={!connectionId} onClick={start}>
            {partial ? '続きから解析する' : '解析する'}
          </button>
        )}
      </div>
      {error && <p className="error-text">{error}</p>}
      {result?.progress && (
        <p className="hint">
          {result.progress.done} / {result.progress.total}
          {result.progress.remainingMillis !== undefined && `　残り約 ${duration(result.progress.remainingMillis)}`}
          {perStatement(result.progress)}　{result.progress.current}
        </p>
      )}
      {result && !running && (
        <p className="hint">
          {result.updatedAt
            ? `${new Date(result.updatedAt).toLocaleString('ja-JP')} に解析　statement ${result.statements} 件${partial ? `（途中まで。残り ${result.pending} 件）` : ''}　つながり ${links.length} 組　表 ${tables.filter(([, n]) => n > 0).length}${result.lastRun && result.lastRun.analyzed > 0 ? `　前回 ${result.lastRun.analyzed} 件を ${duration(result.lastRun.elapsedMillis)}${perStatement(result.lastRun)}` : ''}`
            : 'まだ解析していません。statement を EXPLAIN（実行はしない）して、結合の条件を集めます。'}
        </p>
      )}
      {result?.errors && result.errors.length > 0 && !running && (
        <details className="relations-errors">
          <summary>解析できなかった statement（{result.errors.length} 件）</summary>
          <ul>
            {result.errors.map((e) => (
              <li key={e.id}>
                <button type="button" className="link" onClick={() => onOpen(e.id)}>
                  {e.id}
                </button>
                <span className="hint"> {e.error.split('\n')[0]}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
      {connectionId && !running && (
        <div className="relations-body" style={side.style}>
          <div className="relations-tables">
            <div className="relations-views" role="tablist">
              {(
                [
                  ['tables', '表と列'],
                  ['statements', 'statement'],
                  ['names', 'パラメータ名'],
                ] as const
              ).map(([v, label]) => (
                <button
                  key={v}
                  type="button"
                  role="tab"
                  aria-selected={view === v}
                  className={view === v ? 'active' : undefined}
                  onClick={() => setView(v)}
                >
                  {label}
                </button>
              ))}
            </div>
            <input
              type="search"
              placeholder={view === 'tables' ? '表を絞り込む' : view === 'statements' ? 'statement を絞り込む' : 'パラメータ名を絞り込む'}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            <ul>
              {view === 'tables' &&
                shown.map(([t, n]) => (
                  <li key={t}>
                    <button type="button" className={t === table ? 'selected' : undefined} onClick={() => setTable(t)}>
                      <span>{oneSchema ? t.slice(t.indexOf('.') + 1) : t}</span>
                      <span className="count">{n || ''}</span>
                    </button>
                  </li>
                ))}
              {view === 'statements' &&
                shownStatements.map((d) => (
                  <li key={d.id}>
                    <button
                      type="button"
                      className={d.id === statement ? 'selected' : undefined}
                      onClick={() => setStatement(d.id)}
                      title={d.id}
                    >
                      <span>{d.id}</span>
                      <span className="count">{d.error ? '⚠' : `${d.joins.length}・${Object.keys(d.params).length}`}</span>
                    </button>
                  </li>
                ))}
              {view === 'names' &&
                shownNames.map((n) => (
                  <li key={n}>
                    <button type="button" className={n === name ? 'selected' : undefined} onClick={() => setName(n)}>
                      <span>{n}</span>
                      <span className="count">{names[n].length}</span>
                    </button>
                  </li>
                ))}
            </ul>
            {view === 'statements' && <p className="hint">数は「結合の組・パラメータ」。⚠ は解析できなかったもの</p>}
          </div>
          <SideSplitter label="一覧の幅" side={side} />
          <div className="relations-detail">
            {view === 'tables' &&
              (table ? (
                <TableRelations
                  table={table}
                  links={links}
                  origins={origins}
                  keys={keys}
                  details={details}
                  detailOf={detailOf}
                  s={s}
                  onOpen={onOpen}
                  focus={column}
                  onGoColumn={goColumn}
                  onChange={change}
                  api={api}
                  connectionId={connectionId}
                  allTables={allTables}
                />
              ) : (
                <p className="hint">表を選んでください</p>
              ))}
            {view === 'statements' &&
              (statement && detailOf.get(statement) ? (
                <StatementDetail detail={detailOf.get(statement)!} keys={keys} s={s} onOpen={onOpen} onGoColumn={goColumn} />
              ) : (
                <p className="hint">statement を選んでください</p>
              ))}
            {view === 'names' &&
              (name && names[name] ? (
                <NameDetail name={name} uses={names[name]} minName={result?.minName ?? 3} s={s} onOpen={onOpen} onGoColumn={goColumn} />
              ) : (
                <p className="hint">パラメータ名を選んでください</p>
              ))}
          </div>
        </div>
      )}
    </div>
  );
}

/** 一意の順位を言葉にする（1 列の主キー 2、1 列の UNIQUE 3、n 列なら n × 2、UNIQUE は + 1）。一意でなければ null */
export function keyLabel(rank: number | undefined): string | null {
  if (rank === undefined) return null;
  const n = Math.floor(rank / 2);
  const kind = rank % 2 === 0 ? '主キー' : 'UNIQUE';
  return n === 1 ? kind : `${kind}（${n} 列）`;
}

/** 1 列で一意か（親になれる列か） */
const singleUnique = (rank: number | undefined) => rank !== undefined && rank <= 3;

/** つながりを、列の側から見た向きと、そう判定した理由 */
export function describeLink(
  l: RelationLink,
  col: string,
  keys: Record<string, number>,
  s: (c: string) => string,
): { label: string; reason: string } {
  const other = l.a === col ? l.b : l.a;
  const key = (c: string) => keyLabel(keys[c]) ?? '一意でない';
  // 1 列で一意でない列の書き方（複数の列で一意なら、そのことも書く）
  const notSingle = (c: string) => (keys[c] !== undefined ? ` 1 列では一意でない（${keyLabel(keys[c])}）` : '一意でない');
  if (l.manual) return l.a === col ? { label: '→ 親', reason: '手で設定' } : { label: '← 子', reason: '手で設定' };
  if (l.kind === 'parent' && l.a === col) return { label: '→ 親', reason: `${s(other)} が${key(other)}、この列は${notSingle(col)}` };
  if (l.kind === 'parent') return { label: '← 子', reason: `この列が${key(col)}、${s(other)} は${notSingle(other)}` };
  if (l.kind === 'one-to-one') return { label: '＝ 1 対 1', reason: `どちらも 1 列で一意（${key(col)}・${key(other)}）` };
  return { label: '＝ 兄弟', reason: 'どちらも 1 列では一意でない' };
}

/** 結合の組を拾った計画の項目（Hash Cond など） */
function joinKind(detail: RelationStatement | undefined, a: string, b: string): string | null {
  const j = detail?.joins.find((x) => (x[0] === a && x[1] === b) || (x[0] === b && x[1] === a));
  return j ? j[2] : null;
}

type Origin = { column: string; path: string[]; count: number; manual: boolean };

/** 表の列ごとに、一意かどうか・つながりと判定の理由・根拠・源流・比べているパラメータを出す */
function TableRelations({
  table,
  links,
  origins,
  keys,
  details,
  detailOf,
  s,
  onOpen,
  focus,
  onGoColumn,
  onChange,
  api,
  connectionId,
  allTables,
}: {
  api: Api;
  connectionId: string;
  /** DB の表（追加するときに選ぶ） */
  allTables: string[];
  table: string;
  links: RelationLink[];
  origins: Map<string, Origin[]>;
  keys: Record<string, number>;
  details: RelationStatement[];
  detailOf: Map<string, RelationStatement>;
  s: (col: string) => string;
  onOpen: (id: string) => void;
  /** 強調する列 */
  focus: string | null;
  onGoColumn: (col: string) => void;
  onChange: (c: Parameters<Api['changeManualRelation']>[0]) => Promise<void>;
}) {
  const mine = links.filter((l) => tableOf(l.a) === table || tableOf(l.b) === table);
  // 列ごとの、比べているパラメータ
  const params = new Map<string, { statement: string; property: string }[]>();
  for (const d of details) {
    for (const [property, cols] of Object.entries(d.params)) {
      for (const c of cols) {
        if (tableOf(c) !== table) continue;
        if (!params.has(c)) params.set(c, []);
        params.get(c)!.push({ statement: d.id, property });
      }
    }
  }
  const columns = [
    ...new Set([
      ...mine.flatMap((l) => [l.a, l.b]).filter((c) => tableOf(c) === table),
      ...Object.keys(keys).filter((c) => tableOf(c) === table),
      ...params.keys(),
      ...(focus && tableOf(focus) === table ? [focus] : []),
    ]),
  ].sort((x, y) => (x === focus ? -1 : y === focus ? 1 : x.localeCompare(y)));
  const name = (c: string) => c.slice(c.lastIndexOf('.') + 1);

  return (
    <>
      {columns.length === 0 && <p className="hint">この表の列は、解析で見つかったつながりやパラメータがありません。</p>}
      {columns.map((col) => {
        const ls = mine.filter((l) => l.a === col || l.b === col);
        const os = origins.get(col) ?? [];
        const ps = params.get(col) ?? [];
        const key = keyLabel(keys[col]);
        return (
          <section key={col} className={col === focus ? 'relations-column focus' : 'relations-column'}>
            <h3>
              <code>{name(col)}</code>
              {key && <span className="badge">{key}</span>}
            </h3>
            {ls.length > 0 && (
              <table className="relations-table">
                <tbody>
                  {ls.map((l) => {
                    const other = l.a === col ? l.b : l.a;
                    const d = describeLink(l, col, keys, s);
                    return (
                      <tr key={`${l.a} ${l.b}`} className={l.disabled ? 'disabled' : undefined}>
                        <td className="relation-kind">{d.label}</td>
                        <td>
                          <button type="button" className="link" onClick={() => onGoColumn(other)}>
                            <code>{s(other)}</code>
                          </button>
                          {l.manual && <span className="badge">手動</span>}
                          {l.disabled && <span className="badge muted">無効</span>}
                        </td>
                        <td className="hint">{d.reason}</td>
                        <td>
                          {l.statements.length > 0 && (
                            <Evidence
                              items={l.statements.map((id) => ({ id, note: joinKind(detailOf.get(id), l.a, l.b) }))}
                              onOpen={onOpen}
                            />
                          )}
                        </td>
                        <td className="relations-actions">
                          {l.manual ? (
                            <button type="button" className="link" onClick={() => onChange({ action: 'remove', child: l.a, parent: l.b })}>
                              削除
                            </button>
                          ) : l.disabled ? (
                            <button type="button" className="link" onClick={() => onChange({ action: 'enable', a: l.a, b: l.b })}>
                              有効に戻す
                            </button>
                          ) : (
                            <button
                              type="button"
                              className="link"
                              title="誤ったリレーションを、源流を探すときに使わないようにします"
                              onClick={() => onChange({ action: 'disable', a: l.a, b: l.b })}
                            >
                              無効にする
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
            <p className="relations-origin">
              <span className="relations-label">源流</span>
              {os.length > 0 ? (
                os.map((o) => (
                  <span key={o.column} className="relations-origin-item">
                    <button type="button" className="link" onClick={() => onGoColumn(o.column)}>
                      <code>{s(o.column)}</code>
                    </button>
                    <span className="hint">
                      {o.path.length > 1 ? `（${o.path.slice(1).map(s).join(' → ')} 経由、` : '（'}
                      {o.manual ? '手で設定' : `${o.count} 件の SQL`}）
                    </span>
                  </span>
                ))
              ) : singleUnique(keys[col]) ? (
                <span className="hint">この列が源流（{key}）</span>
              ) : (
                <span className="hint">なし</span>
              )}
            </p>
            {ps.length > 0 && (
              <div className="relations-params">
                <span className="relations-label">比べているパラメータ</span>
                <Evidence items={ps.map((p) => ({ id: p.statement, note: p.property }))} onOpen={onOpen} label={`${ps.length} 件`} />
              </div>
            )}
          </section>
        );
      })}
      <h3 className="relations-add-head">リレーションを追加</h3>
      <AddLink
        key={`parent ${table} ${focus}`}
        side="parent"
        api={api}
        connectionId={connectionId}
        allTables={allTables}
        table={table}
        initialColumn={focus && tableOf(focus) === table ? focus.slice(focus.lastIndexOf('.') + 1) : null}
        onAdd={(child, parent) => onChange({ action: 'add', child, parent })}
      />
      <AddLink
        key={`child ${table}`}
        side="child"
        api={api}
        connectionId={connectionId}
        allTables={allTables}
        table={table}
        initialColumn={null}
        onAdd={(child, parent) => onChange({ action: 'add', child, parent })}
      />
    </>
  );
}

/** 1 つの statement で、解析で検出したもの（結合の組、パラメータと比べている列） */
function StatementDetail({
  detail,
  keys,
  s,
  onOpen,
  onGoColumn,
}: {
  detail: RelationStatement;
  keys: Record<string, number>;
  s: (col: string) => string;
  onOpen: (id: string) => void;
  onGoColumn: (col: string) => void;
}) {
  const col = (c: string) => (
    <>
      <button type="button" className="link" onClick={() => onGoColumn(c)}>
        <code>{s(c)}</code>
      </button>
      {keyLabel(keys[c]) && <span className="badge">{keyLabel(keys[c])}</span>}
    </>
  );
  const params = Object.entries(detail.params);
  return (
    <>
      <h3>
        <button type="button" className="link" onClick={() => onOpen(detail.id)}>
          {detail.id}
        </button>
      </h3>
      {detail.error && <p className="error-text">解析できませんでした: {detail.error.split('\n')[0]}</p>}
      <h3>結合の組（{detail.joins.length}）</h3>
      {detail.joins.length > 0 ? (
        <table className="relations-table">
          <tbody>
            {detail.joins.map((j) => (
              <tr key={`${j[0]} ${j[1]}`}>
                <td>{col(j[0])}</td>
                <td className="arrow">＝</td>
                <td>{col(j[1])}</td>
                <td className="hint">{j[2]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="hint">なし</p>
      )}
      <h3>パラメータと比べている列（{params.length}）</h3>
      {params.length > 0 ? (
        <table className="relations-table">
          <tbody>
            {params.map(([property, cols]) => (
              <tr key={property}>
                <td>
                  <code>{property}</code>
                </td>
                <td className="arrow">→</td>
                <td>
                  {cols.map((c) => (
                    <span key={c} className="relations-origin-item">
                      {col(c)}
                    </span>
                  ))}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="hint">なし</p>
      )}
    </>
  );
}

/** パラメータ名ごとに、どの statement でどの列と比べられ、どの列を源流と判断したか */
function NameDetail({
  name,
  uses,
  minName,
  s,
  onOpen,
  onGoColumn,
}: {
  name: string;
  uses: { statement: string; property: string; compared: string[]; decided: string | null }[];
  minName: number;
  s: (col: string) => string;
  onOpen: (id: string) => void;
  onGoColumn: (col: string) => void;
}) {
  const col = (c: string) => (
    <button type="button" className="link" onClick={() => onGoColumn(c)}>
      <code>{s(c)}</code>
    </button>
  );
  return (
    <>
      <h3>
        <code>{name}</code>
      </h3>
      <p className="hint">
        {name.length >= minName
          ? '同じ名前のパラメータは同じものを指しているとみなし、ここで源流と判断した列を、ほかの statement の値の候補にも出します。'
          : `${minName} 文字未満の名前は、ほかの statement の値の候補には使いません。`}
      </p>
      <table className="relations-table">
        <thead>
          <tr>
            <th>statement</th>
            <th>項目</th>
            <th>比べている列</th>
            <th>源流と判断した列</th>
          </tr>
        </thead>
        <tbody>
          {uses.map((u) => (
            <tr key={`${u.statement} ${u.property}`}>
              <td>
                <button type="button" className="link" onClick={() => onOpen(u.statement)}>
                  {u.statement}
                </button>
              </td>
              <td>
                <code>{u.property}</code>
              </td>
              <td>
                {u.compared.map((c) => (
                  <span key={c} className="relations-origin-item">
                    {col(c)}
                  </span>
                ))}
              </td>
              <td>{u.decided ? col(u.decided) : <span className="hint">なし（源流も一意の列もない）</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

/** 表の列を選ぶ（表は DB の一覧から入力欄の候補で、列は表を選んだら一覧から） */
function ColumnPicker({
  api,
  connectionId,
  tables,
  label,
  value,
  onChange,
}: {
  api: Api;
  connectionId: string;
  tables: string[];
  label: string;
  /** schema.table.column（選びかけなら schema.table.） */
  value: { table: string; column: string };
  onChange: (v: { table: string; column: string }) => void;
}) {
  const [columns, setColumns] = useState<string[]>([]);
  const known = tables.includes(value.table);
  useEffect(() => {
    setColumns([]);
    if (!known) return;
    let active = true;
    api.dbColumns(connectionId, value.table).then(
      (r) => active && setColumns(r.columns),
      () => active && setColumns([]),
    );
    return () => {
      active = false;
    };
  }, [connectionId, value.table, known]);
  const listId = `relation-tables-${label}`;
  return (
    <span className="relation-column">
      <input
        aria-label={`${label}の表`}
        placeholder="schema.table"
        list={listId}
        value={value.table}
        onChange={(e) => onChange({ table: e.target.value, column: '' })}
      />
      <datalist id={listId}>
        {tables.map((t) => (
          <option key={t} value={t} />
        ))}
      </datalist>
      <select
        aria-label={`${label}の列`}
        value={value.column}
        disabled={!known}
        onChange={(e) => onChange({ ...value, column: e.target.value })}
      >
        <option value="">列</option>
        {columns.map((c) => (
          <option key={c} value={c}>
            {c}
          </option>
        ))}
      </select>
    </span>
  );
}

/** 表の列を選ぶ（表は決まっている） */
function OwnColumn({
  api,
  connectionId,
  table,
  label,
  value,
  onChange,
}: {
  api: Api;
  connectionId: string;
  table: string;
  label: string;
  value: string;
  onChange: (column: string) => void;
}) {
  const [columns, setColumns] = useState<string[]>([]);
  useEffect(() => {
    let active = true;
    api.dbColumns(connectionId, table).then(
      (r) => active && setColumns(r.columns),
      () => active && setColumns([]),
    );
    return () => {
      active = false;
    };
  }, [connectionId, table]);
  return (
    <select aria-label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">列</option>
      {columns.map((c) => (
        <option key={c} value={c}>
          {c}
        </option>
      ))}
    </select>
  );
}

/**
 * 選んだ表のリレーションを手で追加する。parent: この表の列 → 親の列、child: 子の列 → この表の列
 */
function AddLink({
  side,
  api,
  connectionId,
  allTables,
  table,
  initialColumn,
  onAdd,
}: {
  side: 'parent' | 'child';
  api: Api;
  connectionId: string;
  allTables: string[];
  /** 選んだ表（schema.table） */
  table: string;
  /** この表の列（パラメータから飛んできたとき） */
  initialColumn: string | null;
  onAdd: (child: string, parent: string) => Promise<void>;
}) {
  const [open, setOpen] = useState(!!initialColumn);
  const [own, setOwn] = useState(initialColumn ?? '');
  const [other, setOther] = useState({ table: '', column: '' });
  if (!open) {
    return (
      <p className="relations-add-open">
        <button type="button" className="link" onClick={() => setOpen(true)}>
          {side === 'parent' ? '＋ 親を追加' : '＋ 子を追加'}
        </button>
      </p>
    );
  }
  const ownColumn = (
    <OwnColumn
      api={api}
      connectionId={connectionId}
      table={table}
      label={side === 'parent' ? 'この表の列（子）' : 'この表の列（親）'}
      value={own}
      onChange={setOwn}
    />
  );
  const otherColumn = (
    <ColumnPicker
      api={api}
      connectionId={connectionId}
      tables={allTables}
      label={side === 'parent' ? '親' : '子'}
      value={other}
      onChange={setOther}
    />
  );
  const ready = own !== '' && other.column !== '';
  const mine = `${table}.${own}`;
  const theirs = `${other.table}.${other.column}`;
  return (
    <div className="relations-add">
      {side === 'parent' ? ownColumn : otherColumn}
      <span className="arrow">→</span>
      {side === 'parent' ? otherColumn : ownColumn}
      <button
        type="button"
        disabled={!ready}
        onClick={() =>
          (side === 'parent' ? onAdd(mine, theirs) : onAdd(theirs, mine)).then(() => {
            setOther({ table: '', column: '' });
            setOpen(false);
          })
        }
      >
        追加
      </button>
      <button type="button" className="link" onClick={() => setOpen(false)}>
        閉じる
      </button>
    </div>
  );
}

/** 根拠の statement。件数を出し、押すと一覧を開く（statement を押すと、その statement を開く） */
function Evidence({
  items,
  onOpen,
  label,
}: {
  items: { id: string; note: string | null }[];
  onOpen: (id: string) => void;
  label?: string;
}) {
  return (
    <details className="relations-evidence">
      <summary>{label ?? `${items.length} 件の SQL`}</summary>
      <ul>
        {items.map((it) => (
          <li key={`${it.id} ${it.note}`}>
            <button type="button" className="link" onClick={() => onOpen(it.id)}>
              {it.id}
            </button>
            {it.note && <span className="hint"> {it.note}</span>}
          </li>
        ))}
      </ul>
    </details>
  );
}
