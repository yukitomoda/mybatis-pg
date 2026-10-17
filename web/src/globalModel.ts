// 共通パラメータ: すべての statement に適用し、statement ごとに無効にしたり順番を入れ替えたりする
import type { Path, PValue } from './params';
import { getAt, mergeValues } from './params';

export interface GlobalSet {
  id: string;
  name: string;
  values: PValue;
}

/** statement ごとの設定 */
export interface StatementSettings {
  /** その statement で無効にした共通パラメータの id */
  disabled: string[];
  /** その statement での順番（下ほど優先）。なければ既定の順番 */
  order?: string[];
}

export type StatementsSettings = Record<string, StatementSettings>;

export interface Entry {
  set: GlobalSet;
  disabled: boolean;
}

/**
 * statement での順番（下ほど優先）に並べる。順番を入れ替えていなければ既定の順番（一覧の順）。
 * 入れ替えた順に入っていないもの（後から作ったものなど）は、最後に足す。
 */
export function entries(sets: GlobalSet[], settings: StatementSettings | undefined): Entry[] {
  const disabled = new Set(settings?.disabled ?? []);
  const ids = settings?.order ?? [];
  const ordered = ids.map((id) => sets.find((s) => s.id === id)).filter((s): s is GlobalSet => !!s);
  const rest = sets.filter((s) => !ids.includes(s.id));
  return [...ordered, ...rest].map((set) => ({ set, disabled: disabled.has(set.id) }));
}

/** statement に適用される共通パラメータ（優先順位の低い順） */
export function applicable(sets: GlobalSet[], settings: StatementSettings | undefined): GlobalSet[] {
  return entries(sets, settings)
    .filter((e) => !e.disabled)
    .map((e) => e.set);
}

/** 共通パラメータを優先順位の低い順に重ねる */
export function combine(sets: GlobalSet[]): PValue | undefined {
  return sets.reduce<PValue | undefined>((acc, set) => mergeValues(acc, set.values), undefined);
}

/** path の値を決めている共通パラメータ（最も優先順位の高いもの）の名前 */
export function originAt(sets: GlobalSet[], path: Path): string | null {
  for (let i = sets.length - 1; i >= 0; i--) {
    if (getAt(sets[i].values, path) !== undefined) return sets[i].name;
  }
  return null;
}
