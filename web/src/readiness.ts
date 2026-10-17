// 一括チェックの前に、statement に足りない値がないかを調べる（足りないものは実行しない）
import { resolveTemplates } from './gen';
import type { GlobalSet, StatementsSettings } from './globalModel';
import { applicable, combine } from './globalModel';
import { loadParams } from './paramStore';
import type { ParamSchema, Path, PValue } from './params';
import { effectiveValue, getAt, invalidNumbers, missingParams, pathLabel } from './params';

export interface Readiness {
  /** 実行に使う値（足りないものがなければ） */
  value: PValue | undefined;
  /** 値がない（NULL として扱われる）項目と、foreach で回すのに空のリスト */
  missing: string[];
  /** 式の誤り・数値の誤りなど */
  issues: string[];
}

/** 画面で開いたときと同じく、個別の値・共通パラメータ・既定の値・式から値を作り、足りないものを調べる */
export function readiness(id: string, schema: ParamSchema, globalSets: GlobalSet[], globalStatements: StatementsSettings): Readiness {
  const effective = effectiveValue(schema, combine(applicable(globalSets, globalStatements[id])), loadParams(id));
  const missing = [...missingParams(schema, effective), ...emptyLists(schema, effective)];
  const resolved = resolveTemplates(effective, new Map());
  const genPaths = new Set(resolved.issues.map((i) => i.path));
  const issues = [...resolved.issues, ...invalidNumbers(resolved.value).filter((i) => !genPaths.has(i.path))].map((i) => `${i.path}: ${i.message}`);
  return { value: resolved.value, missing, issues };
}

export function isReady(r: Readiness | undefined): boolean {
  return !!r && r.missing.length === 0 && r.issues.length === 0;
}

/** 足りないものの短い表示と、その詳しい内容 */
export function problemLabel(r: Readiness): { label: string; detail: string } {
  if (r.missing.length > 0) return { label: `未設定 ${r.missing.length}`, detail: `未設定: ${r.missing.join(', ')}` };
  return { label: '値の誤り', detail: r.issues.join('\n') };
}

/**
 * foreach で回すのに空のリスト。既定の値では空のリストを入れるが、
 * そのままでは IN () のような不完全な SQL になることが多いので、値が足りないものとして扱う。
 */
export function emptyLists(schema: ParamSchema, value: PValue | undefined, path: Path = []): string[] {
  const out: string[] = [];
  for (const child of schema.properties) {
    const childPath = [...path, child.name as string];
    const v = getAt(value, [child.name as string]);
    if (!v) continue;
    if (v.t === 'list' && v.items.length === 0 && child.usages.includes('ITERATE')) out.push(`${pathLabel(childPath)}（空のリスト）`);
    else out.push(...emptyLists(child, v, childPath));
  }
  if (schema.element && value && value.t === 'list') {
    value.items.forEach((item, i) => out.push(...emptyLists(schema.element as ParamSchema, item, [...path, i])));
  }
  return out;
}
