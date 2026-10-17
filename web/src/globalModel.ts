// 共通パラメータ: 既定で入れるものと、statement ごとに追加・外す・並べ替えたもので、適用するものを決める
import type { Path, PValue } from './params';
import { getAt, mergeValues } from './params';

export interface GlobalSet {
  id: string;
  name: string;
  /** すべての statement に既定で入れる */
  isDefault: boolean;
  values: PValue;
}

/** statement ごとの設定 */
export interface StatementSettings {
  /** 追加した共通パラメータの id */
  added: string[];
  /** 既定で入るのに外した共通パラメータの id */
  removed: string[];
  /** その statement での順番（下ほど優先）。なければ既定の順番 */
  order?: string[];
}

export type StatementsSettings = Record<string, StatementSettings>;

export const NO_SETTINGS: StatementSettings = { added: [], removed: [] };

/**
 * statement に適用する共通パラメータ（優先順位の低い順）。
 * 既定で入るもの（外したものを除く）と追加したものを、statement での順番に並べる。
 * その順に入っていないもの（後から既定にしたものなど）は、既定の順番で最後に足す。
 */
export function applicable(sets: GlobalSet[], settings: StatementSettings | undefined): GlobalSet[] {
  const st = settings ?? NO_SETTINGS;
  const used = sets.filter((s) => (s.isDefault && !st.removed.includes(s.id)) || st.added.includes(s.id));
  const ids = st.order ?? [];
  const ordered = ids.map((id) => used.find((s) => s.id === id)).filter((s): s is GlobalSet => !!s);
  return [...ordered, ...used.filter((s) => !ids.includes(s.id))];
}

/** 追加する */
export function addTo(st: StatementSettings | undefined, set: GlobalSet, current: GlobalSet[]): StatementSettings {
  const s = st ?? NO_SETTINGS;
  return {
    added: set.isDefault ? s.added : [...s.added, set.id],
    removed: s.removed.filter((x) => x !== set.id),
    // 追加したものは一番下（一番優先）に置く
    order: [...current.map((x) => x.id), set.id],
  };
}

/** 外す */
export function removeFrom(st: StatementSettings | undefined, set: GlobalSet): StatementSettings {
  const s = st ?? NO_SETTINGS;
  return {
    added: s.added.filter((x) => x !== set.id),
    removed: set.isDefault ? [...s.removed, set.id] : s.removed,
    order: s.order?.filter((x) => x !== set.id),
  };
}

/** 既定から変えているか */
export function customized(st: StatementSettings | undefined): boolean {
  return !!st && (st.added.length > 0 || st.removed.length > 0 || !!st.order);
}

/** from の位置のものを to の位置へ移した並び */
export function moved<T>(list: T[], from: number, to: number): T[] {
  const out = [...list];
  const [x] = out.splice(from, 1);
  out.splice(to, 0, x);
  return out;
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
