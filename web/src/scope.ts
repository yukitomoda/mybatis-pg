// 共通パラメータを適用する場所: 場所ごとの「適用する・しない」から、statement に効く共通パラメータを決める
import type { Path, PValue } from './params';
import { getAt, mergeValues } from './params';

/** 適用する（on）・しない（off） */
export type RuleState = 'on' | 'off';

/** 全体を表す場所 */
export const ROOT = '*';

export interface GlobalSet {
  id: string;
  name: string;
  values: PValue;
  /** 場所ごとの適用する・しない。場所は ROOT、フォルダ（末尾が /）・ファイルの相対パス、statement の id */
  rules: Record<string, RuleState>;
}

/** 判定する statement（id と、定義したファイルのパス） */
export interface Target {
  id: string;
  file: string;
}

export type PlaceKind = 'all' | 'dir' | 'file' | 'statement';

export interface Place {
  key: string;
  kind: PlaceKind;
  /** 近さ。大きいほど statement に近い */
  rank: number;
}

/** statement から見た場所を、近い順に返す（statement、ファイル、深いフォルダから浅いフォルダ、全体） */
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

export interface Decision {
  state: RuleState;
  /** 決めている場所（statement から見て一番近い、設定のある場所） */
  place: Place;
}

/** statement での適用する・しない。どこにも設定がなければ null（適用しない） */
export function decide(set: GlobalSet, target: Target): Decision | null {
  for (const place of placesOf(target)) {
    const state = set.rules[place.key];
    if (state) return { state, place };
  }
  return null;
}

/** statement に関わる共通パラメータ（どこかの場所に設定があるもの）と、その判定 */
export function related(sets: GlobalSet[], target: Target | null): { set: GlobalSet; decision: Decision }[] {
  if (!target) return [];
  return sets.flatMap((set) => {
    const decision = decide(set, target);
    return decision ? [{ set, decision }] : [];
  });
}

/**
 * statement に適用される共通パラメータを、優先順位の低い順に返す。
 * 「適用する」を決めた場所が近いほうが優先。同じなら、一覧で下にあるほうが優先。
 */
export function applicable(sets: GlobalSet[], target: Target | null): GlobalSet[] {
  return related(sets, target)
    .map((r) => ({ ...r, index: sets.indexOf(r.set) }))
    .filter((r) => r.decision.state === 'on')
    .sort((a, b) => a.decision.place.rank - b.decision.place.rank || a.index - b.index)
    .map((r) => r.set);
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
