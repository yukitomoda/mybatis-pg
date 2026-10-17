// 共通パラメータのスコープ: statement に当たる共通パラメータと、その優先順位を決める
import type { Path, PValue } from './params';
import { getAt, mergeValues } from './params';

/** 全体を表す場所 */
export const ROOT = '*';

export interface GlobalSet {
  id: string;
  name: string;
  values: PValue;
  /** 対象とする場所。ROOT、フォルダ（末尾が /）・ファイルの相対パス、statement の id */
  scope: string[];
  /** スコープに入っていても適用しない statement の id */
  excludes: string[];
}

/** 場所ごとの適用順序（同じ場所をスコープにした共通パラメータの id。下ほど優先） */
export type Order = Record<string, string[]>;

/** 判定する statement（id と、定義したファイルのパス） */
export interface Target {
  id: string;
  file: string;
}

export type PlaceKind = 'all' | 'dir' | 'file' | 'statement';

export interface Place {
  key: string;
  kind: PlaceKind;
  /** 狭さ。大きいほど狭い */
  rank: number;
}

/** statement を含む場所を、狭い順に返す（statement、ファイル、深いフォルダから浅いフォルダ、全体） */
export function placesOf(target: Target): Place[] {
  const out: Place[] = [
    { key: target.id, kind: 'statement', rank: 3000 },
    { key: target.file, kind: 'file', rank: 2000 },
  ];
  const parts = target.file.split('/').slice(0, -1);
  for (let i = parts.length; i >= 1; i--) {
    out.push({ key: parts.slice(0, i).join('/') + '/', kind: 'dir', rank: 1000 + i });
  }
  out.push({ key: ROOT, kind: 'all', rank: 0 });
  return out;
}

/** 共通パラメータのスコープのうち、statement に当たる一番狭い場所。当たらなければ null */
export function matchPlace(set: GlobalSet, target: Target): Place | null {
  return placesOf(target).find((p) => set.scope.includes(p.key)) ?? null;
}

/**
 * 場所をスコープにした共通パラメータを、適用順序（下ほど優先）で並べる。
 * 順序に入っていないもの（後からスコープに足したものなど）は、一覧の順で最後に置く。
 */
export function orderAt(sets: GlobalSet[], order: Order, place: string): GlobalSet[] {
  const here = sets.filter((s) => s.scope.includes(place));
  const ids = order[place] ?? [];
  const ordered = ids.map((id) => here.find((s) => s.id === id)).filter((s): s is GlobalSet => !!s);
  return [...ordered, ...here.filter((s) => !ids.includes(s.id))];
}

export interface Match {
  set: GlobalSet;
  /** statement に当たる一番狭いスコープ */
  place: Place;
  /** この statement では適用しない */
  excluded: boolean;
}

/**
 * statement がスコープに入っている共通パラメータを、優先順位の低い順に返す。
 * スコープの狭いほうが必ず優先。同じ場所なら、その場所の適用順序で下にあるほうが優先。
 */
export function related(sets: GlobalSet[], order: Order, target: Target | null): Match[] {
  if (!target) return [];
  const matches = sets.flatMap((set): Match[] => {
    const place = matchPlace(set, target);
    return place ? [{ set, place, excluded: set.excludes.includes(target.id) }] : [];
  });
  const position = (m: Match) => orderAt(sets, order, m.place.key).indexOf(m.set);
  return matches.sort((a, b) => a.place.rank - b.place.rank || position(a) - position(b));
}

/** statement に適用される共通パラメータ（優先順位の低い順） */
export function applicable(sets: GlobalSet[], order: Order, target: Target | null): GlobalSet[] {
  return related(sets, order, target)
    .filter((m) => !m.excluded)
    .map((m) => m.set);
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

/** 場所を短く表す（全体、order/、OrderMapper.xml、recent） */
export function placeLabel(place: Place): string {
  if (place.kind === 'all') return '全体';
  if (place.kind === 'statement') return place.key.slice(place.key.lastIndexOf('.') + 1);
  const trimmed = place.kind === 'dir' ? place.key.slice(0, -1) : place.key;
  const name = trimmed.slice(trimmed.lastIndexOf('/') + 1);
  return place.kind === 'dir' ? `${name}/` : name;
}
