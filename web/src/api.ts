// Java サーバの API クライアント
// リバースプロキシの配下（/proxy/7171/ など）でも動くよう、API は相対パスで呼ぶ
import { track } from './busy';
import { notifyError } from './notices';
import type { ParamSchema, PValue } from './params';
import { fromJson, getAt, toJson } from './params';
import type { GlobalSet, StatementSettings, StatementsSettings } from './globalModel';
import type { StatementEntry } from './tree';

export interface LoadError {
  source: string;
  line: number | null;
  column: number | null;
  message: string;
}

/** プレイグラウンド（このアプリだけで使う mapper XML） */
export interface PlaygroundEntry {
  /** 画面のパス（playground:名前） */
  file: string;
  name: string;
  /** 読み込みのエラー */
  errors: LoadError[];
}

export interface StatementsResponse {
  generation: number;
  statements: StatementEntry[];
  playgrounds: PlaygroundEntry[];
  /** ファイルに保存できない（JAR から読み込んだときなど） */
  readOnly: boolean;
  /** ワークスペースで、保存できないソース（JAR）のファイルの頭（「ソースの名前/」） */
  readOnlyPrefixes?: string[];
  /** 開いているワークスペースの名前 */
  workspace?: string | null;
  /** ファイルにソースの名前を付けて見せるときの、ソースの名前（付けないなら空） */
  sources?: string[];
  loadErrors: LoadError[];
}

/** 関連データ: 隣の表 */
export interface RelatedNeighbor {
  /** parent: この表が参照する表（1 対 1 を含む）、child: この表を参照する表 */
  direction: 'parent' | 'child';
  table: string;
  /** この表の列 */
  columns: string[];
  /** 相手の表の列（columns と同じ順） */
  otherColumns: string[];
  manual: boolean;
  /** 子のとき、引く列にインデックスがあるか */
  indexed: boolean | null;
}

/** 関連データ: 引いた行 */
export interface RelatedRows {
  columns: string[];
  columnTypes: string[];
  rows: (string | null)[][];
  truncated: boolean;
  total: number | null;
  pk: string[];
}

/** ワークスペースのソース */
export interface WorkspaceSource {
  id?: string;
  name: string;
  /** project: Spring Boot のプロジェクト、jar: JAR・war、files: mapper XML のファイルかディレクトリ */
  type: 'project' | 'jar' | 'files';
  path: string;
  enabled: boolean;
  /** 使えない理由（パスがないなど） */
  error?: string | null;
  /** 読んだ mapper XML の数（無効なら null、読めなければ -1） */
  files?: number | null;
}

export interface WorkspaceSnapshot {
  current: {
    id: string;
    name: string;
    sources: WorkspaceSource[];
    /** リレーションの解析の結果を、すべての接続先でこの接続先のものを使う（null なら接続先ごと） */
    relationConnection: string | null;
    /** 画面に出す DB のスキーマ（null なら自動） */
    schemas: string[] | null;
  } | null;
  workspaces: { id: string; name: string; sources: number; current: boolean }[];
}

/** パラメータと比べられている列（推論） */
export interface SourceColumn {
  schema: string;
  table: string;
  column: string;
  /** 見つけた条件（例: (o.customer_id = $3)） */
  condition: string;
  /** 列そのものと比べている（関数などで包んでいない） */
  direct: boolean;
  /**
   * compared: パラメータと比べている列、origin: リレーションでたどった源流の列、
   * name: 同じ名前のパラメータが、ほかの statement で源流と判断した列
   */
  role: 'compared' | 'origin' | 'name';
  /** 源流のとき、比べている列から源流の手前までにたどった列（表.列） */
  via: string[];
  /** 源流のとき、根拠の SQL の数。name のとき、その列と判断した statement の数 */
  count: number;
  /** 源流を、手で設定したリレーションで見つけた */
  manual: boolean;
}

/** 保存していない mapper XML の内容（なければ null） */
export type DraftParam = { file: string; content: string } | null;

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

/** 組み立てた SQL の、<if>・<foreach> から出た範囲（literalSql の中の位置） */
export interface SqlSpan {
  start: number;
  end: number;
  /** if、foreach（全体）、item（foreach の 1 回分）、param（#{} の値） */
  kind: 'if' | 'foreach' | 'item' | 'param';
  label: string;
  /** 入れ子の深さ（外側ほど小さい。範囲が同じとき、内と外を決める） */
  depth?: number;
}

export interface RenderResponse {
  placeholderSql: string;
  literalSql: string;
  /** <if>・<foreach> から出た範囲（前の版のサーバーにはない） */
  structure?: SqlSpan[];
  /** バインド変数を $1, $2, ... にした SQL */
  numberedSql: string;
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
  /** パスワードがサーバのメモリにある（使える） */
  hasPassword: boolean;
  /** パスワードを暗号化して保存している */
  passwordSaved: boolean;
  /** プロジェクトの設定（application.yml など）から読んだ接続先なら、そのファイル（画面では変えられない） */
  source: string | null;
  /** プロジェクトの設定から読んだ接続先が使えない理由 */
  error: string | null;
}

/** パスワードの保存（マスターパスワードで暗号化）の状態 */
export interface VaultStatus {
  /** マスターパスワードを設定済み */
  exists: boolean;
  /** 開いている（保存したパスワードを使える） */
  unlocked: boolean;
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
  /** true でパスワードを暗号化して保存、false で保存しているものを消す。なければ変えない */
  remember?: boolean;
}

export interface ResultTable {
  columns: string[];
  columnTypes: string[];
  /** 列の出どころ（schema.table.column。表の列をそのまま出した列だけ。式や集計の列は null。前の版の履歴にはない） */
  columnSources?: (string | null)[];
  rows: (string | null)[][];
  truncated: boolean;
}

export interface ExecuteResponse {
  sql: string;
  resultSets: ResultTable[];
  updateCounts: number[];
  warnings: string[];
  committed: boolean;
  /** 実行の履歴に残したときの id */
  historyId: string | null;
  elapsedMillis: number;
}

/** API がエラーを返した。status 401 はトークンの問題 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** 実行の履歴に残したときの id（失敗した実行も、結果ビューで見られるように） */
    readonly historyId: string | null = null,
    /** ログの行の id（画面からその行へ移るため） */
    readonly logId: string | null = null,
  ) {
    super(message);
  }
}

export interface GlobalsResponse {
  /** 一覧の順が既定の順番（下ほど優先） */
  sets: GlobalSet[];
  /** statement ごとの設定（追加したもの、外したもの、並べ替えた順番） */
  statements: StatementsSettings;
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
            isDefault: (() => {
              const d = getAt(s, ['default']);
              return d?.t === 'boolean' && d.v;
            })(),
            values: getAt(s, ['values']) ?? { t: 'object', fields: [] },
          }))
        : [],
    statements: Object.fromEntries(
      (() => {
        const st = getAt(root, ['statements']);
        if (!st || st.t !== 'object') return [];
        return st.fields.map(([id, v]) => {
          const order = getAt(v, ['order']);
          return [
            id,
            {
              added: strs(getAt(v, ['added'])),
              removed: strs(getAt(v, ['removed'])),
              order: order && order.t === 'list' ? strs(order) : undefined,
            },
          ];
        });
      })(),
    ),
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
  /** エラーを右下に知らせない問い合わせ（入力に合わせて裏で呼ぶもの、その場で知らせるもの） */
  const QUIET = [
    'api/render',
    'api/parameters',
    'api/param-sources',
    'api/draft/check',
    'api/relations/trace',
    'api/relations/statement-tables',
    'api/db-comments',
    'api/vault',
    'api/log',
  ];

  async function callText(path: string, init?: RequestInit): Promise<string> {
    // 変更の通知を待つ問い合わせは、ずっと開いたままなので「読み込み中」に数えない
    return path.startsWith('api/changes') ? send(path, init) : track(send(path, init));
  }

  async function send(path: string, init?: RequestInit): Promise<string> {
    const res = await fetch(path, {
      ...init,
      headers: { ...(init?.headers ?? {}), 'X-Auth-Token': token },
    });
    const text = await res.text();
    if (!res.ok) {
      let message = text || res.statusText;
      let historyId: string | null = null;
      let logId: string | null = null;
      try {
        const body = JSON.parse(text) as { error?: string; historyId?: string | null; logId?: string | null };
        message = body.error ?? res.statusText;
        historyId = body.historyId ?? null;
        logId = body.logId ?? null;
      } catch {
        // JSON でなければ本文をそのまま使う
      }
      // 画面の右下に知らせる（入力に合わせて裏で呼ぶ問い合わせなどは、ログの行だけ覚えて、その場の印で見せる）
      if (res.status !== 401) notifyError(message, logId, !QUIET.some((q) => path.startsWith(q)));
      throw new ApiError(res.status, message, historyId, logId);
    }
    return text;
  }

  const post = (body: string): RequestInit => ({
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  const globalsCall = async (action: string, body: string) => parseGlobals(await callText(`api/globals/${action}`, post(body)));

  return {
    statements: () => call<StatementsResponse>('api/statements'),
    /** 開いているワークスペースと、ワークスペースの一覧 */
    workspace: () => call<WorkspaceSnapshot>('api/workspace'),
    /** 開いているワークスペースの名前とソースを保存する（読み込み直す） */
    saveWorkspace: (name: string, sources: WorkspaceSource[], relationConnection: string | null) =>
      call<WorkspaceSnapshot>(
        'api/workspace/save',
        post(
          JSON.stringify({
            name,
            sources: sources.map(({ id, name, type, path, enabled }) => ({ id, name, type, path, enabled })),
            relationConnection,
          }),
        ),
      ),
    /** 画面に出す DB のスキーマを保存する（null なら自動） */
    saveWorkspaceSchemas: (schemas: string[] | null) => call<WorkspaceSnapshot>('api/workspace/schemas', post(JSON.stringify({ schemas }))),
    /** DB のスキーマと表の数（used: リレーションの解析で使った） */
    dbSchemas: (connectionId: string) =>
      call<{
        schemas: { name: string; tables: number; searchPath: boolean; used: boolean }[];
        selected: string[] | null;
        shown: string[] | null;
      }>('api/db-schemas', post(JSON.stringify({ connectionId }))),
    /** ワークスペースを開く */
    openWorkspace: (id: string) => call<WorkspaceSnapshot>('api/workspace/open', post(JSON.stringify({ id }))),
    /** ワークスペースを作って開く */
    createWorkspace: (name: string) => call<WorkspaceSnapshot>('api/workspace/create', post(JSON.stringify({ name }))),
    /** 書き出した定義から、新しいワークスペースを作る（開かない） */
    importWorkspace: (definition: unknown) => call<WorkspaceSnapshot>('api/workspace/import', post(JSON.stringify({ definition }))),
    /** ワークスペースを消す（開いているものは消せない） */
    deleteWorkspace: (id: string) => call<WorkspaceSnapshot>('api/workspace/delete', post(JSON.stringify({ id }))),
    parameters: (id: string, draft: DraftParam = null) =>
      draft
        ? call<ParametersResponse>('api/parameters', post(JSON.stringify({ id, draft })))
        : call<ParametersResponse>(`api/parameters?id=${encodeURIComponent(id)}`),
    /** mapper XML の中身 */
    file: (path: string) => call<{ file: string; content: string }>(`api/file?path=${encodeURIComponent(path)}`),
    /** 編集した内容をファイルに保存する。編集を始めた後にファイルが変わっていたら、force がなければ 409 */
    saveFile: (file: string, base: string, content: string, force: boolean) =>
      call<object>('api/file/save', post(JSON.stringify({ file, base, content, force }))),
    createPlayground: (name: string) => call<object>('api/playground/create', post(JSON.stringify({ name }))),
    renamePlayground: (name: string, newName: string) => call<object>('api/playground/rename', post(JSON.stringify({ name, newName }))),
    deletePlayground: (name: string) => call<object>('api/playground/delete', post(JSON.stringify({ name }))),
    /** 保存してある下書き（更新の新しい順） */
    drafts: () =>
      call<{
        drafts: {
          file: string;
          base: string;
          content: string;
          updatedAt: number;
          loaded: boolean;
          changed: boolean;
        }[];
      }>('api/drafts'),
    saveDraft: (file: string, base: string, content: string) =>
      call<object>('api/drafts/save', post(JSON.stringify({ file, base, content }))),
    discardDrafts: (files: string[]) => call<object>('api/drafts/discard', post(JSON.stringify({ files }))),
    /** 下書きを読み込んでみる */
    checkDraft: (file: string, content: string, signal?: AbortSignal) =>
      call<{
        statements: string[];
        errors: {
          source: string;
          line: number | null;
          column: number | null;
          message: string;
        }[];
      }>('api/draft/check', {
        ...post(JSON.stringify({ file, content })),
        signal,
      }),
    /** すべての statement のパラメータの木（抜き出せなかったものは errors に理由） */
    allParameters: () =>
      call<{
        trees: Record<string, ParamSchema>;
        errors: Record<string, string>;
      }>('api/parameters-all'),
    globals: async () => parseGlobals(await callText('api/globals')),
    createGlobal: (name: string, copyFrom: string | null) => globalsCall('create', JSON.stringify({ name, copyFrom })),
    renameGlobal: (id: string, name: string) => globalsCall('rename', JSON.stringify({ id, name })),
    deleteGlobal: (id: string) => globalsCall('delete', JSON.stringify({ id })),
    /** すべての statement に既定で入れるか */
    defaultGlobal: (id: string, on: boolean) => globalsCall('default', JSON.stringify({ id, on })),
    /** 既定の順番（すべての id を並べる） */
    reorderGlobals: (ids: string[]) => globalsCall('reorder', JSON.stringify({ ids })),
    /** statement ごとの設定（追加するもの、外すもの、順番。順番が null なら既定の順番） */
    statementGlobals: (statement: string, settings: StatementSettings) =>
      globalsCall(
        'statement',
        JSON.stringify({
          statement,
          added: settings.added,
          removed: settings.removed,
          order: settings.order ?? null,
        }),
      ),
    saveGlobalValues: (id: string, values: PValue) => globalsCall('values', `{"id":${JSON.stringify(id)},"values":${toJson(values, '')}}`),
    parameterNames: () => call<{ names: ParameterName[] }>('api/parameter-names'),
    editor: () => call<{ available: boolean; command: string | null }>('api/editor'),
    /** mapper XML をエディタで開く（statement の開始行、またはファイルと行） */
    open: (target: OpenTarget) => call<object>('api/open', post(JSON.stringify(target))),
    template: (id: string) => call<TemplateResponse>(`api/template?id=${encodeURIComponent(id)}`),
    render: (statementId: string, parameters: PValue | undefined, signal?: AbortSignal, draft: DraftParam = null) =>
      call<RenderResponse>('api/render', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // 数値の精度を保つため、JSON は自前で組み立てる
        body: `{"statementId":${JSON.stringify(statementId)},"draft":${JSON.stringify(draft)},"parameters":${toJson(parameters, '')}}`,
        signal,
      }),
    connections: () => call<{ connections: ConnectionProfile[] }>('api/connections'),
    vault: () => call<VaultStatus>('api/vault'),
    vaultSetup: (password: string) => call<VaultStatus>('api/vault/setup', post(JSON.stringify({ password }))),
    vaultUnlock: (password: string) => call<VaultStatus>('api/vault/unlock', post(JSON.stringify({ password }))),
    vaultLock: () => call<VaultStatus>('api/vault/lock', post('{}')),
    vaultChange: (current: string, password: string) => call<VaultStatus>('api/vault/change', post(JSON.stringify({ current, password }))),
    vaultReset: () => call<VaultStatus>('api/vault/reset', post('{}')),
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
    execute: (
      statementId: string,
      parameters: PValue | undefined,
      connectionId: string,
      commit: boolean,
      maxRows: number,
      draft: DraftParam = null,
    ) =>
      call<ExecuteResponse>('api/execute', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: `{"statementId":${JSON.stringify(statementId)},"draft":${JSON.stringify(draft)},"connectionId":${JSON.stringify(connectionId)},"commit":${commit},"maxRows":${maxRows},"parameters":${toJson(parameters, '')}}`,
      }),
    /** EXPLAIN を実行する。plan は FORMAT JSON の結果の文字列 */
    explain: (
      statementId: string,
      parameters: PValue | undefined,
      connectionId: string,
      options: {
        analyze: boolean;
        buffers: boolean;
        readOnly: boolean;
        timeoutSeconds: number;
        sixthExecution: boolean;
        repeat: number;
      },
      draft: DraftParam = null,
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
          `{"statementId":${JSON.stringify(statementId)},"draft":${JSON.stringify(draft)},"connectionId":${JSON.stringify(connectionId)},` +
            `"analyze":${options.analyze},"buffers":${options.buffers},"readOnly":${options.readOnly},` +
            `"timeoutSeconds":${options.timeoutSeconds},"sixthExecution":${options.sixthExecution},"repeat":${options.repeat},"parameters":${toJson(parameters, '')}}`,
        ),
      ),
    /** パラメータがどの表のどの列と比べられているかを推論する（試作。SQL は実行しない） */
    paramSources: (statementId: string, parameters: PValue | undefined, draft: DraftParam, connectionId: string, signal?: AbortSignal) =>
      call<{
        sources: { index: number; property: string; columns: SourceColumn[] }[];
        /** 推論するには値を入れる必要がある ${} の項目 */
        needs: string[];
        /** 推論できなかった理由 */
        error: string | null;
        /** リレーションを解析してあるか */
        relations: boolean;
      }>('api/param-sources', {
        ...post(
          `{"statementId":${JSON.stringify(statementId)},"draft":${JSON.stringify(draft)},"connectionId":${JSON.stringify(connectionId)},"parameters":${toJson(parameters, '')}}`,
        ),
        signal,
      }),
    /** 候補の値ごとの行（値ごとに 1 行）と、入力中の値に当たる行（表のすべての列） */
    columnRows: (
      connectionId: string,
      c: { schema: string; table: string; column: string },
      values: (string | null)[],
      current: string | null,
    ) =>
      call<{
        columns: string[];
        /** 列の型（セルの値をパラメータの型に直すため） */
        columnTypes: string[];
        rows: (string | null)[][];
        currentRows: (string | null)[][];
        currentError: string | null;
      }>(
        'api/column-rows',
        post(
          JSON.stringify({
            connectionId,
            schema: c.schema,
            table: c.table,
            column: c.column,
            values,
            current,
          }),
        ),
      ),
    /** 手で設定したリレーションを変える（列は schema.table.column） */
    changeManualRelation: (
      change:
        | { action: 'add' | 'remove'; a: string; b: string; connectionId?: string }
        | { action: 'table'; child: string; parent: string; kind: 'parent' | 'one-to-one' | 'sibling' | null }
        | { action: 'disable' | 'enable'; a: string; b: string },
    ) => call<{ ok: boolean }>('api/relations/manual', post(JSON.stringify(change))),
    /** DB の表（schema.table）の一覧 */
    dbTables: (connectionId: string) => call<{ tables: string[] }>('api/db-columns', post(JSON.stringify({ connectionId }))),
    /** 関連データ: 表の隣の表（親・子） */
    relatedNeighbors: (connectionId: string, table: string) =>
      call<{ neighbors: RelatedNeighbor[] }>('api/related/neighbors', post(JSON.stringify({ connectionId, table }))),
    /** 関連データ: 列の値の組に当たる行（row を渡すと、その行を主キーで引く） */
    relatedRows: (
      connectionId: string,
      table: string,
      match: { columns: string[]; values: (string | null)[][] } | { row: Record<string, string | null> },
      limit = 100,
    ) => call<RelatedRows>('api/related/rows', post(JSON.stringify({ connectionId, table, limit, ...match }))),
    /** 表の中身を読んで、実行の履歴に残す（結果ビューで見る） */
    tableRows: (connectionId: string, table: string) =>
      call<{ historyId: string | null }>('api/table-rows', post(JSON.stringify({ connectionId, table }))),
    /** DB の表と列のコメント（COMMENT ON）。キーは schema.table と schema.table.column */
    dbComments: (connectionId: string) =>
      call<{ tables: Record<string, string>; columns: Record<string, string> }>('api/db-comments', post(JSON.stringify({ connectionId }))),
    /** 表（schema.table）の列の一覧 */
    dbColumns: (connectionId: string, table: string) =>
      call<{ columns: string[] }>('api/db-columns', post(JSON.stringify({ connectionId, table }))),
    /** リレーション（試作）の解析の結果。解析中なら進み具合だけ */
    relations: (connectionId: string) => call<RelationsResult>('api/relations', post(JSON.stringify({ connectionId }))),
    /** ログ（DB への操作と、画面の操作で起きたエラー）を新しい順に読む */
    log: (q: { cursor?: string | null; focus?: string | null; limit?: number; errors?: boolean; analysis?: boolean; q?: string }) =>
      call<LogPage>('api/log', post(JSON.stringify(q))),
    /** statement が使う表（リレーションの解析で、計画に出てきた表。解析していなければ null） */
    statementTables: (connectionId: string, statement: string) =>
      call<{ tables: string[] | null }>('api/relations/statement-tables', post(JSON.stringify({ connectionId, statement }))),
    /** 推定の根拠: その statement で列の組を拾った計画の場所と、別名を表の列に読み替えた経路 */
    relationTrace: (connectionId: string, statement: string, a: string, b: string) =>
      call<{ traces: JoinTrace[] }>('api/relations/trace', post(JSON.stringify({ connectionId, statement, a, b }))),
    /** リレーションの解析を始める（終わるまで待たない） */
    /** リレーションの解析を一時停止する（そこまでの結果は保存する） */
    stopRelations: (connectionId: string) => call<{ stopping: boolean }>('api/relations/stop', post(JSON.stringify({ connectionId }))),
    analyzeRelations: (connectionId: string, parameters?: PValue) =>
      call<{ started: boolean }>(
        'api/relations/analyze',
        post(`{"connectionId":${JSON.stringify(connectionId)}${parameters ? `,"parameters":${toJson(parameters, '')}` : ''}}`),
      ),
    /** 列の値の候補（多く出てくる順） */
    columnValues: (connectionId: string, c: { schema: string; table: string; column: string }, scan = false, offset = 0, limit = 30) =>
      call<{
        type: string;
        estimatedRows: number;
        method: 'stats' | 'scan' | 'sample' | 'none';
        indexed: boolean;
        values: { value: string | null; count: number }[];
        /** まだ続きがありそうか */
        more: boolean;
      }>(
        'api/column-values',
        post(
          JSON.stringify({
            connectionId,
            schema: c.schema,
            table: c.table,
            column: c.column,
            scan,
            offset,
            limit,
          }),
        ),
      ),
    /**
     * statement の SQL の一部（サブクエリ・CTE など）を、本番と同じバインド変数で、読み取り専用で実行する（必ずロールバックする）。
     * sql は番号付き（$1, $2, ...。render の numberedSql と同じ番号）
     */
    /**
     * @param label 部分の名前（実行の履歴に出す）
     * @param history 実行の履歴に残すか（「すべての部分を実行」では残さない）
     */
    runPart: (
      statementId: string,
      parameters: PValue | undefined,
      draft: DraftParam,
      connectionId: string,
      sql: string,
      label: string,
      history = true,
      maxRows = 1000,
    ) =>
      call<ResultTable & { total: number | null; elapsedMillis: number; historyId: string | null }>(
        'api/part',
        post(
          `{"statementId":${JSON.stringify(statementId)},"draft":${JSON.stringify(draft)},"connectionId":${JSON.stringify(connectionId)},` +
            `"sql":${JSON.stringify(sql)},"label":${JSON.stringify(label)},"history":${history},"maxRows":${maxRows},` +
            `"parameters":${toJson(parameters, '')}}`,
        ),
      ),
    /** 実行の履歴の一覧（新しい順） */
    results: () => call<{ entries: HistorySummary[] }>('api/results', post('{}')),
    /** 実行の履歴の 1 件 */
    result: (id: string) => call<HistoryEntry>('api/results', post(JSON.stringify({ id }))),
    /** 履歴の実行を、同じ条件（statement・接続先・値・下書き・コミットするか）でやり直す。新しい履歴に残る */
    rerunResult: (id: string) => call<ExecuteResponse>('api/results/rerun', post(JSON.stringify({ id }))),
    /** 実行の履歴をすべて消す */
    clearResults: () => call<{ ok: boolean }>('api/results', post(JSON.stringify({ clear: true }))),
    /** 履歴の結果の続きを取る（refetch なら最初から取り直す）。書き換えた履歴の 1 件を返す */
    moreResult: (id: string, resultSet: number, refetch: boolean) =>
      call<HistoryEntry>('api/results/more', post(JSON.stringify({ id, resultSet, refetch }))),
    tables: (connectionId: string, tables: string[]) =>
      call<{ tables: TableInfo[]; notFound: string[] }>('api/tables', post(JSON.stringify({ connectionId, tables }))),
    /** mapper が読み込み直されるまで待つ（最大 25 秒） */
    changes: (since: number, signal?: AbortSignal) => call<{ generation: number }>(`api/changes?since=${since}`, { signal }),
  };
}

export type Api = ReturnType<typeof createApi>;

/** リレーション（試作）。列は schema.table.column */
export interface RelationLink {
  a: string;
  b: string;
  /** parent: b が親（a が子）、one-to-one: どちらも一意、sibling: どちらも一意でない */
  kind: 'parent' | 'one-to-one' | 'sibling';
  /** 根拠の statement */
  statements: string[];
  /** 手で追加したリレーション（向きは、一意かどうかと表の関係で決まる） */
  manual: boolean;
  /** 手で決めた表の関係で、向きと種類を決めた */
  byTable?: boolean;
  /** 手で無効にした */
  disabled: boolean;
  /** 複数の列でできた鍵で親子（1 対 1）と決めたとき、親の表と鍵（schema.table の主キー（a, b））。そうでなければ null */
  compositeKey?: string | null;
}

export interface RelationsResult {
  /** 手で決めた表の関係（parent: parent が child の親） */
  tableKinds?: { child: string; parent: string; kind: 'parent' | 'one-to-one' | 'sibling' }[];
  /** ほかの接続先の解析の結果を使っているとき、その接続先の id（ワークスペースの設定） */
  sharedFrom?: string | null;
  /** ワークスペースのソースの名前（ソースで絞り込む。ソースが 1 つなら空） */
  sources?: string[];
  progress?: {
    done: number;
    /** statement の数 */
    total: number;
    /** prepare: SQL を組み立てて変わったものを数える、plan: 変わったものの計画を取る、finish: 主キー・保存・結果をまとめる */
    phase?: 'prepare' | 'plan' | 'finish';
    /** 準備で組み立てた数 */
    prepared?: number;
    /** 計画を取る statement の数（前から変わったもの） */
    toAnalyze?: number;
    /** 仕上げの今の作業 */
    step?: string | null;
    current: string | null;
    stopping: boolean;
  } & RelationsTiming;
  /** 最後に終えた（一時停止を含む）解析の時間（アプリを起動してから） */
  lastRun?: RelationsTiming;
  /** 最後の解析を最後まで終えたか（一時停止したら false） */
  complete?: boolean;
  /** まだ解析していない statement の数 */
  pending?: number;
  updatedAt?: number | null;
  statements?: number;
  errors?: { id: string; error: string }[];
  links?: RelationLink[];
  /** 一意でない列ごとの源流（path はたどった列） */
  origins?: {
    column: string;
    origins: {
      column: string;
      path: string[];
      count: number;
      manual: boolean;
    }[];
  }[];
  /** 1 列で一意の列 */
  unique?: string[];
  /** 列ごとの一意の順位（1 列の主キー 2、1 列の UNIQUE 3、n 列なら n × 2（UNIQUE は + 1）） */
  keys?: Record<string, number>;
  /** 解析で検出したもの（statement ごと） */
  statementDetails?: RelationStatement[];
  /** パラメータ名ごとの、statement・項目・比べている列・源流と判断した列 */
  names?: Record<string, { statement: string; property: string; compared: string[]; decided: string | null }[]>;
  /** 同じ名前のパラメータを同じものとみなす、名前の最短の長さ */
  minName?: number;
}

/** 1 つの statement で、解析で検出したもの */
/** ログの 1 件 */
export interface LogEntry {
  /** ファイル名:位置 */
  id: string;
  /** 時刻（yyyy-MM-dd HH:mm:ss.SSS） */
  t: string;
  /** 画面・解析（前の版のログにはない） */
  src?: string;
  /** connect・query・execute・update・commit・rollback・event・api・text（前の版のログ）など */
  kind: string;
  conn?: string;
  /** 接続の通し番号 */
  n?: number;
  ms?: number;
  sql?: string;
  values?: (string | null)[];
  err?: string;
  state?: string;
  who?: string;
  msg?: string;
  path?: string;
  status?: number;
}

export interface LogPage {
  entries: LogEntry[];
  /** 続きの位置（もうなければ null） */
  next: string | null;
  /** 読む行数の上限で止めた */
  capped: boolean;
  scanned: number;
}

/** 推定の根拠（1 か所） */
export interface JoinTrace {
  /** 計画の根からの段 */
  path: string[];
  /** その段（子の段を除く） */
  node: unknown;
  /** 計画の項目（Hash Cond など） */
  key: string;
  /** 計画に書かれた条件 */
  condition: string;
  /** 拾った式 */
  expr: string;
  /** 式の左右を、表の列に読み替えた経路 */
  left: string[];
  right: string[];
}

export interface RelationStatement {
  id: string;
  /** 計画に出てきた表（schema.table。前の版の解析の結果にはない） */
  tables?: string[];
  /** 結合の組 [列, 列, 拾った計画の項目（Hash Cond など）] */
  joins: string[][];
  /** パラメータ（項目のパス）→ 比べている列 */
  params: Record<string, string[]>;
  error: string | null;
  /** ワークスペースのソースの名前（ソースが 1 つか、今は読み込んでいない statement なら null） */
  source?: string | null;
}

/** 解析にかかった時間 */
export interface RelationsTiming {
  /** 解析した件数（解析し直さなかった件は数えない） */
  analyzed: number;
  elapsedMillis: number;
  /** 1 件あたり（DB との往復を含む） */
  perStatementMillis?: number;
  /** そのうち、PostgreSQL が計画を作った時間 */
  planPerStatementMillis?: number;
  /** 残りの推定時間（多めに出る） */
  remainingMillis?: number;
}

/** 実行の履歴の一覧の 1 件 */
export interface HistorySummary {
  id: string;
  /** execute: 実行、part: サブクエリの部分、table: 表の中身（リレーションの画面から） */
  kind: 'execute' | 'part' | 'table';
  /** 部分の名前（サブクエリの部分のとき）、表（schema.table。表の中身のとき） */
  label: string | null;
  /** 表の中身なら null */
  statementId: string | null;
  connectionName: string;
  executedAt: number;
  elapsedMillis: number;
  committed: boolean | null;
  rows: number;
  /** 失敗したときの理由の 1 行目 */
  error: string | null;
}

/** 実行の履歴の 1 件の中身 */
export interface HistoryEntry {
  id: string;
  kind: 'execute' | 'part' | 'table';
  label: string | null;
  /** 表の中身なら null */
  statementId: string | null;
  /** 編集中の下書きで実行した */
  draft: boolean;
  /** コミットする実行だった（前の版の履歴にはない） */
  commit?: boolean;
  connectionId: string;
  connectionName: string;
  executedAt: number;
  elapsedMillis: number;
  /** 送った値（型付きの JSON） */
  parameters: unknown;
  sql: string | null;
  resultSets: ResultTable[];
  updateCounts: number[];
  warnings: string[];
  committed: boolean | null;
  /** サブクエリの部分の全体の件数 */
  total: number | null;
  error: string | null;
}
