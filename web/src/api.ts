// Java サーバの API クライアント
// リバースプロキシの配下（/proxy/7171/ など）でも動くよう、API は相対パスで呼ぶ
import type { ParamSchema, PValue } from './params';
import { toJson } from './params';

export interface LoadError {
  source: string;
  line: number | null;
  column: number | null;
  message: string;
}

export interface StatementsResponse {
  generation: number;
  statements: string[];
  loadErrors: LoadError[];
}

export interface ParametersResponse {
  tree: ParamSchema;
  warnings: string[];
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

/** API がエラーを返した。status 401 はトークンの問題 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

const TOKEN_KEY = 'mybatis-xml-editor.token';

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
    const res = await fetch(path, {
      ...init,
      headers: { ...(init?.headers ?? {}), 'X-Auth-Token': token },
    });
    const text = await res.text();
    let body: unknown;
    try {
      body = JSON.parse(text);
    } catch {
      throw new ApiError(res.status, text || res.statusText);
    }
    if (!res.ok) {
      const message = (body as { error?: string }).error ?? res.statusText;
      throw new ApiError(res.status, message);
    }
    return body as T;
  }

  return {
    statements: () => call<StatementsResponse>('api/statements'),
    parameters: (id: string) => call<ParametersResponse>(`api/parameters?id=${encodeURIComponent(id)}`),
    render: (statementId: string, parameters: PValue | undefined, signal?: AbortSignal) =>
      call<RenderResponse>('api/render', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // 数値の精度を保つため、JSON は自前で組み立てる
        body: `{"statementId":${JSON.stringify(statementId)},"parameters":${toJson(parameters, '')}}`,
        signal,
      }),
    /** mapper が読み込み直されるまで待つ（最大 25 秒） */
    changes: (since: number, signal?: AbortSignal) =>
      call<{ generation: number }>(`api/changes?since=${since}`, { signal }),
  };
}

export type Api = ReturnType<typeof createApi>;
