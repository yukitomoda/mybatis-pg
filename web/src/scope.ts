// 共通パラメータの適用範囲: statement ごとに、効く共通パラメータを選んで優先順に重ねる
import type { Path, PValue } from './params';
import { getAt, mergeValues } from './params';

export interface Scope {
  /** 全体に効く */
  all: boolean;
  /** all が false のときに効く範囲。フォルダ（末尾が /）・ファイルの相対パスか、statement の id */
  items: string[];
}

export interface GlobalSet {
  id: string;
  name: string;
  values: PValue;
  enabled: boolean;
  scope: Scope;
}

/** 範囲を比べる statement（id と、定義したファイルのパス） */
export interface Target {
  id: string;
  file: string;
}

/**
 * 範囲がどれだけ狭く statement に当たるか。当たらなければ null。
 * statement ＞ ファイル ＞ フォルダ（深いほうが狭い） ＞ 全体。
 */
export function specificity(scope: Scope, target: Target): number | null {
  if (scope.all) return 0;
  let best: number | null = null;
  for (const item of scope.items) {
    let s: number | null = null;
    if (item === target.id) s = 3000;
    else if (item === target.file) s = 2000;
    else if (item.endsWith('/') && target.file.startsWith(item)) s = 1000 + item.split('/').length;
    if (s !== null && (best === null || s > best)) best = s;
  }
  return best;
}

/** statement に効く共通パラメータ（オンで、範囲に当たるもの）を、優先順位の低い順に返す */
export function applicable(sets: GlobalSet[], target: Target | null): GlobalSet[] {
  if (!target) return [];
  return sets
    .map((set, index) => ({ set, index, s: set.enabled ? specificity(set.scope, target) : null }))
    .filter((x): x is { set: GlobalSet; index: number; s: number } => x.s !== null)
    // 範囲が同じ狭さなら、一覧で下にあるほうを優先（後に重ねる）
    .sort((a, b) => a.s - b.s || a.index - b.index)
    .map((x) => x.set);
}

/** 共通パラメータを優先順位の低い順に重ねる */
export function combine(sets: GlobalSet[]): PValue | undefined {
  return sets.reduce<PValue | undefined>((acc, set) => mergeValues(acc, set.values), undefined);
}

/** path の値を決めている共通パラメータ（最も優先順位の高いもの）の名前 */
export function originAt(sets: GlobalSet[], path: Path): string | null {
  for (let i = sets.length - 1; i >= 0; i--) {
    if (getAt(sets[i].values, path) !== undefined) {
      // 途中に値のないオブジェクトがある場合などは、上の階層で決まっていることもあるが、名前の表示には十分
      return sets[i].name;
    }
  }
  return null;
}
