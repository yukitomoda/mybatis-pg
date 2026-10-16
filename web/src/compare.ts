// EXPLAIN の結果を残して比べる
import { diagnose } from './diagnose';
import type { Plan } from './plan';
import { flatten, parsePlan, timings } from './plan';

/** 比較用に残した結果 */
export interface Snapshot {
  id: string;
  name: string;
  /** 残した時刻（ISO 形式） */
  createdAt: string;
  /** どこで取ったか（接続先やオプション） */
  source: string;
  /** EXPLAIN の結果そのもの */
  raw: string;
  /** 取ったときのパラメータ（JSON） */
  parameters: string;
  generic: boolean | null;
}

export interface Summary {
  executionMs: number | null;
  planningMs: number | null;
  /** 一番上のノードの見積もりのコスト */
  totalCost: number | null;
  /** 結果の行数（ANALYZE のとき） */
  rows: number | null;
  /** 読んだブロック（一番上のノードは下のノードの分を含む） */
  hit: number | null;
  read: number | null;
  temp: number | null;
  /** 自身の時間が全体の 10% 以上のノード */
  hotNodes: { title: string; share: number }[];
  /** 表を読むノード（Seq Scan、Index Scan など） */
  scans: string[];
  findings: { high: number; medium: number; info: number };
}

export function summarize(plan: Plan): Summary {
  const times = timings(plan.root);
  const total = plan.executionMs ?? times.get(plan.root)!.inclusive;
  const rows = flatten(plan.root);
  const b = plan.root.buffers;
  const counts = { high: 0, medium: 0, info: 0 };
  for (const f of diagnose(plan)) counts[f.severity]++;
  return {
    executionMs: plan.executionMs,
    planningMs: plan.planningMs,
    totalCost: plan.root.totalCost,
    rows: plan.root.actual ? plan.root.actual.rows : null,
    hit: b ? b.hit : null,
    read: b ? b.read : null,
    temp: b ? b.tempRead + b.tempWritten : null,
    hotNodes: plan.analyzed && total > 0
      ? rows
          .map(({ node }) => ({ title: node.title, share: times.get(node)!.self / total }))
          .filter((h) => h.share >= 0.1)
          .sort((x, y) => y.share - x.share)
      : [],
    scans: rows.map(({ node }) => node.title).filter((t) => /Scan/.test(t)),
    findings: counts,
  };
}

/** 比べる値の、1 つ目に対する倍率（1 つ目が 0 や値がなければ null） */
export function ratioTo(base: number | null, value: number | null): number | null {
  if (base === null || value === null || base === 0) return null;
  return value / base;
}

// ---- 保存（statement ごとにブラウザへ） ----

const PREFIX = 'mybatis-pg.plans:';
/** 1 つの statement に残せる数。超えたら古いものから消す */
export const MAX_SNAPSHOTS = 20;

export function loadSnapshots(statementId: string): Snapshot[] {
  try {
    const list = JSON.parse(localStorage.getItem(PREFIX + statementId) ?? '[]') as Snapshot[];
    // 壊れたものは読み飛ばす
    return list.filter((s) => {
      try {
        parsePlan(s.raw);
        return true;
      } catch {
        return false;
      }
    });
  } catch {
    return [];
  }
}

export function saveSnapshots(statementId: string, list: Snapshot[]): string | null {
  try {
    localStorage.setItem(PREFIX + statementId, JSON.stringify(list.slice(-MAX_SNAPSHOTS)));
    return null;
  } catch (e) {
    return 'ブラウザに保存できません（容量の上限の可能性があります）: ' + (e as Error).message;
  }
}
