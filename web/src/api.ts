// Java サーバの API クライアント
// リバースプロキシの配下（/proxy/7171/ など）でも動くよう、API は相対パスで呼ぶ
import type { ParamSchema, PValue } from './params';
import { fromJson, getAt, toJson } from './params';
import type { GlobalSet, Order } from './scope';
import type { StatementEntry } from './tree';

export interface LoadError {
  source: string;
  line: number | null;
  column: number | null;
  message: string;
}

export interface StatementsResponse {
  generation: number;
  statements: StatementEntry[];
  loadErrors: LoadError[];
}

export interface ParametersResponse {
  tree: ParamSchema;
  warnings: string[];
}

export interface IndexInfo {
  name: string;
  definition: string;
  bytes: number;
  /** インデックスを使って読んだ回数（統計を取り始めてから） */
  scans: number | null;
  unique: boolean;
  primary: boolean;
  valid: boolean;
}

export interface TableInfo {
  schema: string;
  name: string;
  /** 統計情報上の行数（ANALYZE や VACUUM で更新される） */
  estimatedRows: number;
  totalBytes: number;
  tableBytes: number;
  liveRows: number | null;
  deadRows: number | null;
  /** 統計情報を最後に更新した日時（手動・自動の新しいほう） */
  lastAnalyze: string | null;
  lastVacuum: string | null;
  seqScans: number | null;
  indexScans: number | null;
  indexes: IndexInfo[];
}

export type OpenTarget = { statementId: string } | { file: string; line?: number | null };

export interface TemplateResponse {
  /** 定義したファイル（基準からの相対パス） */
  file: string;
  /** 要素が始まる行 */
  line: number;
  /** 書かれたままの要素（共通の字下げは除く） */
  xml: string;
}

export interface Binding {
  index: number;
  property: string;
  value: unknown;
  literal: string;
}

export interface RenderResponse {
  placeholderSql: string;
  literalSql: string;
  bindings: Binding[];
}

export interface ConnectionProfile {
  id: string;
  name: string;
  host: string;
  port: number;
  database: string;
  user: string;
  /** JDBC URL に付ける接続パラメータ */
  params: string;
  /** パスワードがサーバのメモリにある（ファイルには保存しない） */
  hasPassword: boolean;
}

/** 接続先の保存。id がなければ追加。password は省略で今のまま、空文字で消す */
export interface ConnectionInput {
  id?: string;
  name: string;
  host: string;
  port: number;
  database: string;
  user: string;
  params: string;
  password?: string;
}

export interface ResultTable {
  columns: string[];
  columnTypes: string[];
  rows: (string | null)[][];
  truncated: boolean;
}

export interface ExecuteResponse {
  sql: string;
  resultSets: ResultTable[];
  updateCounts: number[];
  warnings: string[];
  committed: boolean;
  elapsedMillis: number;
}

/** API がエラーを返した。status 401 はトークンの問題 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export interface GlobalsResponse {
  sets: GlobalSet[];
  /** 場所ごとの適用順序 */
  order: Order;
}

/** 共通パラメータの候補（全 statement で使われている項目） */
export interface ParameterName {
  path: string;
  count: number;
  usages: string[];
  typeHints: string[];
  object: boolean;
  list: boolean;
}

/** 数値の精度を保つため、共通パラメータの応答は自前の JSON 解析で読む */
function parseGlobals(text: string): GlobalsResponse {
  const root = fromJson(text);
  const str = (v: PValue | undefined) => (v && v.t === 'string' ? v.v : null);
  const strs = (v: PValue | undefined) => (v && v.t === 'list' ? v.items.map((i) => str(i) ?? '').filter(Boolean) : []);
  const sets = getAt(root, ['sets']);
  return {
    sets:
      sets && sets.t === 'list'
        ? sets.items.map((s) => ({
            id: str(getAt(s, ['id'])) ?? '',
            name: str(getAt(s, ['name'])) ?? '',
            values: getAt(s, ['values']) ?? { t: 'object', fields: [] },
            scope: strs(getAt(s, ['scope'])),
            excludes: strs(getAt(s, ['excludes'])),
          }))
        : [],
    order: Object.fromEntries((() => {
      const o = getAt(root, ['order']);
      return o && o.t === 'object' ? o.fields.map(([place, ids]) => [place, strs(ids)]) : [];
    })()),
  };
}

const TOKEN_KEY = 'mybatis-pg.token';

/**
 * 起動時に表示された URL の ?token= を取り出してタブ内に保存し、URL からは消す
 * （画面の共有や履歴からトークンが漏れないようにする）。
 */
export function takeToken(): string | null {
  const url = new URL(window.location.href);
  const fromUrl = url.searchParams.get('token');
  if (fromUrl) {
    try {
      sessionStorage.setItem(TOKEN_KEY, fromUrl);
    } catch {
      // 保存できなくても、この表示中はメモリ上のトークンで動く
    }
    url.searchParams.delete('token');
    window.history.replaceState(null, '', url.pathname + url.search + url.hash);
    return fromUrl;
  }
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function createApi(token: string) {
  async function call<T>(path: string, init?: RequestInit): Promise<T> {
    return JSON.parse(await callText(path, init)) as T;
  }

  /** 応答の本文をそのまま返す。エラーなら ApiError */
  async function callText(path: string, init?: RequestInit): Promise<string> {
    const res = await fetch(path, {
      ...init,
      headers: { ...(init?.headers ?? {}), 'X-Auth-Token': token },
    });
    const text = await res.text();
    if (!res.ok) {
      let message = text || res.statusText;
      try {
        message = (JSON.parse(text) as { error?: string }).error ?? res.statusText;
      } catch {
        // JSON でなければ本文をそのまま使う
      }
      throw new ApiError(res.status, message);
    }
    return text;
  }

  const post = (body: string): RequestInit => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body });
  const globalsCall = async (action: string, body: string) => parseGlobals(await callText(`api/globals/${action}`, post(body)));

  return {
    statements: () => call<StatementsResponse>('api/statements'),
    parameters: (id: string) => call<ParametersResponse>(`api/parameters?id=${encodeURIComponent(id)}`),
    globals: async () => parseGlobals(await callText('api/globals')),
    createGlobal: (name: string, copyFrom: string | null) =>
      globalsCall('create', JSON.stringify({ name, copyFrom })),
    renameGlobal: (id: string, name: string) => globalsCall('rename', JSON.stringify({ id, name })),
    deleteGlobal: (id: string) => globalsCall('delete', JSON.stringify({ id })),
    /** スコープ（対象とする場所）を決める */
    scopeGlobal: (id: string, places: string[]) => globalsCall('scope', JSON.stringify({ id, places })),
    /** statement で適用しないか */
    excludeGlobal: (id: string, statement: string, excluded: boolean) => globalsCall('exclude', JSON.stringify({ id, statement, excluded })),
    /** 場所の適用順序（下ほど優先） */
    orderGlobal: (place: string, ids: string[]) => globalsCall('order', JSON.stringify({ place, ids })),
    saveGlobalValues: (id: string, values: PValue) =>
      globalsCall('values', `{"id":${JSON.stringify(id)},"values":${toJson(values, '')}}`),
    parameterNames: () => call<{ names: ParameterName[] }>('api/parameter-names'),
    editor: () => call<{ available: boolean; command: string | null }>('api/editor'),
    /** mapper XML をエディタで開く（statement の開始行、またはファイルと行） */
    open: (target: OpenTarget) => call<object>('api/open', post(JSON.stringify(target))),
    template: (id: string) => call<TemplateResponse>(`api/template?id=${encodeURIComponent(id)}`),
    render: (statementId: string, parameters: PValue | undefined, signal?: AbortSignal) =>
      call<RenderResponse>('api/render', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // 数値の精度を保つため、JSON は自前で組み立てる
        body: `{"statementId":${JSON.stringify(statementId)},"parameters":${toJson(parameters, '')}}`,
        signal,
      }),
    connections: () => call<{ connections: ConnectionProfile[] }>('api/connections'),
    saveConnection: (input: ConnectionInput) =>
      call<ConnectionProfile>('api/connections/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      }),
    deleteConnection: (id: string) =>
      call<object>('api/connections/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      }),
    testConnection: (id: string) =>
      call<{ server: string }>('api/connections/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      }),
    /** 実行する。commit が true でなければロールバックする */
    execute: (statementId: string, parameters: PValue | undefined, connectionId: string, commit: boolean, maxRows: number) =>
      call<ExecuteResponse>('api/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: `{"statementId":${JSON.stringify(statementId)},"connectionId":${JSON.stringify(connectionId)},"commit":${commit},"maxRows":${maxRows},"parameters":${toJson(parameters, '')}}`,
      }),
    /** EXPLAIN を実行する。plan は FORMAT JSON の結果の文字列 */
    explain: (
      statementId: string,
      parameters: PValue | undefined,
      connectionId: string,
      options: { analyze: boolean; buffers: boolean; readOnly: boolean; timeoutSeconds: number; sixthExecution: boolean; repeat: number },
    ) =>
      call<{
        sql: string;
        plan: string;
        warnings: string[];
        elapsedMillis: number;
        analyze: boolean;
        generic: boolean | null;
        /** 繰り返したときの、各回の実行時間（ms） */
        runs?: number[];
      }>(
        'api/explain',
        post(
          `{"statementId":${JSON.stringify(statementId)},"connectionId":${JSON.stringify(connectionId)},` +
            `"analyze":${options.analyze},"buffers":${options.buffers},"readOnly":${options.readOnly},` +
            `"timeoutSeconds":${options.timeoutSeconds},"sixthExecution":${options.sixthExecution},"repeat":${options.repeat},"parameters":${toJson(parameters, '')}}`,
        ),
      ),
    tables: (connectionId: string, tables: string[]) =>
      call<{ tables: TableInfo[]; notFound: string[] }>('api/tables', post(JSON.stringify({ connectionId, tables }))),
    /** mapper が読み込み直されるまで待つ（最大 25 秒） */
    changes: (since: number, signal?: AbortSignal) =>
      call<{ generation: number }>(`api/changes?since=${since}`, { signal }),
  };
}

export type Api = ReturnType<typeof createApi>;
