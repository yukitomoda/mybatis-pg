// 実行計画から、よくある性能の問題を見つける（断定はせず、確かめるべきこととして出す）
import type { Plan, PlanNode, Timing } from './plan';
import { estimateRatio, flatten, timings } from './plan';

export type Severity = 'high' | 'medium' | 'info';

export interface Finding {
  severity: Severity;
  /** 対象のノードの位置（flatten の path） */
  path: string;
  /** 対象のノードの名前 */
  node: string;
  title: string;
  /** 根拠（計画から読み取った値） */
  evidence: string;
  /** 確かめること・対処の候補 */
  advice: string;
}

/** ノードの詳細の値（JSON 形式とテキスト形式で項目名が同じもの） */
function detail(n: PlanNode, key: string): string | null {
  const d = n.details.find(([k]) => k === key);
  return d ? d[1] : null;
}

function detailNumber(n: PlanNode, key: string): number | null {
  const v = detail(n, key);
  if (v === null) return null;
  const m = /-?\d+(\.\d+)?/.exec(v);
  return m ? Number(m[0]) : null;
}

/** ハッシュの分割数。JSON は Hash Batches、テキストは「Buckets: 1024  Batches: 32 ...」 */
function hashBatches(n: PlanNode): number | null {
  const v = detailNumber(n, 'Hash Batches');
  if (v !== null) return v;
  const m = /Batches:\s*(\d+)/.exec(detail(n, 'Buckets') ?? '');
  return m ? Number(m[1]) : null;
}

/** 行数の目安を読みやすくする */
function fmt(n: number): string {
  return Math.round(n).toLocaleString('ja-JP');
}

const SEVERITY_ORDER: Record<Severity, number> = { high: 0, medium: 1, info: 2 };

/** 割合がこれ以上のノードを「時間の多くを占める」とする */
const HOT_SHARE = 0.4;
/** 見積もりと実績のずれを問題にする倍率 */
const MISESTIMATE = 10;

export function diagnose(plan: Plan): Finding[] {
  const out: Finding[] = [];
  const times = timings(plan.root);
  const total = plan.executionMs ?? times.get(plan.root)!.inclusive;
  const rows = flatten(plan.root);
  // Limit の下のノードは途中で読むのをやめるため、実績が見積もりより少なくても問題ではない
  const underLimit = new Set<PlanNode>();
  const mark = (n: PlanNode, limited: boolean) => {
    if (limited) underLimit.add(n);
    n.children.forEach((c) => mark(c, limited || /^Limit$/.test(n.title)));
  };
  mark(plan.root, false);

  for (const { node, path } of rows) {
    const t: Timing = times.get(node)!;
    const share = total > 0 ? t.self / total : 0;
    const add = (severity: Severity, title: string, evidence: string, advice: string) =>
      out.push({ severity, path, node: node.title, title, evidence, advice });
    const loops = node.actual?.loops ?? 1;

    if (plan.analyzed && node.actual && share >= HOT_SHARE) {
      add('info', '時間の多くをこのノードが占めています', `自身の時間 ${t.self.toFixed(1)} ms（全体の ${Math.round(share * 100)}%）`, 'まずこのノードを速くできないかを考えます。下の指摘があれば、それが手がかりになります。');
    }

    // 見積もりのずれ
    const ratio = estimateRatio(node);
    if (ratio !== null && node.planRows !== null && node.actual) {
      const big = Math.max(node.actual.rows, node.planRows) >= 100;
      if (big && ratio >= MISESTIMATE) {
        add(
          'medium',
          '実際の行数が見積もりよりずっと多い',
          `実績 ${fmt(node.actual.rows)} 行 / 見積もり ${fmt(node.planRows)} 行（${ratio.toFixed(0)} 倍）`,
          '見積もりが少ないと、少ない行向けの方法（入れ子ループなど）が選ばれて遅くなることがあります。表の統計情報が古くないか（ANALYZE 表名）、関係のある列どうしの条件なら拡張統計（CREATE STATISTICS）で直るかを確かめます。',
        );
      } else if (big && ratio <= 1 / MISESTIMATE && !underLimit.has(node)) {
        add(
          'medium',
          '実際の行数が見積もりよりずっと少ない',
          `実績 ${fmt(node.actual.rows)} 行 / 見積もり ${fmt(node.planRows)} 行（1/${(1 / ratio).toFixed(0)}）`,
          '見積もりが多いと、インデックスを使わずに全件を読む方法が選ばれることがあります。統計情報が古くないか（ANALYZE 表名）、条件の書き方（関数や型の変換を挟んでいないか）を確かめます。',
        );
      }
    }

    // 条件で捨てている行
    const removed = detailNumber(node, 'Rows Removed by Filter');
    if (removed !== null && node.actual) {
      const removedTotal = removed * loops;
      const kept = node.actual.rows * loops;
      const wasted = removedTotal / Math.max(1, removedTotal + kept);
      const filter = detail(node, 'Filter') ?? '';
      if (removedTotal >= 1000 && wasted >= 0.9) {
        if (/Seq Scan/.test(node.title)) {
          add(
            'high',
            '表を全件読んで、大半の行を条件で捨てています',
            `読んだ ${fmt(removedTotal + kept)} 行のうち ${fmt(removedTotal)} 行（${Math.round(wasted * 100)}%）を捨てた。条件: ${filter}`,
            'この条件の列にインデックスがあれば、必要な行だけを読めるかもしれません。LIKE の前方一致でない検索（%...%）や、列に関数を掛けた条件では、普通のインデックスは使えません。',
          );
        } else if (/Index|Bitmap Heap/.test(node.title)) {
          add(
            'medium',
            'インデックスで読んだ行の大半を、条件で捨てています',
            `読んだ ${fmt(removedTotal + kept)} 行のうち ${fmt(removedTotal)} 行（${Math.round(wasted * 100)}%）を捨てた。条件: ${filter}`,
            'インデックスに含まれない条件で絞り込んでいます。この条件の列も含めた複合インデックスで、読む行を減らせるかもしれません。',
          );
        }
      }
    }

    // 並べ替えがメモリに収まらない
    const sortMethod = detail(node, 'Sort Method') ?? '';
    if (/external/.test(sortMethod)) {
      const space = detail(node, 'Sort Space Used');
      add(
        'medium',
        '並べ替えがメモリに収まらず、ディスクを使っています',
        `Sort Method: ${sortMethod}${space ? `（${space} kB）` : ''}`,
        'work_mem を増やすと、メモリの中で並べ替えられるかもしれません（SET work_mem = \'64MB\' などで試せます）。ORDER BY の列のインデックスで、並べ替え自体を省けることもあります。',
      );
    }

    // ハッシュがメモリに収まらない
    const batches = hashBatches(node);
    if (batches !== null && batches > 1) {
      add(
        'medium',
        'ハッシュがメモリに収まらず、分割して処理しています',
        `Batches: ${batches}`,
        'work_mem を増やすと、1 回で処理できるかもしれません。結合する前に行を絞れないかも確かめます。',
      );
    }

    // ビットマップがメモリに収まらない
    const heapBlocks = detail(node, 'Heap Blocks') ?? '';
    const recheck = detailNumber(node, 'Rows Removed by Index Recheck');
    if (/lossy/.test(heapBlocks) || (recheck !== null && recheck * loops >= 1000)) {
      add(
        'medium',
        'ビットマップがメモリに収まらず、ブロック単位で読み直しています',
        [heapBlocks && `Heap Blocks: ${heapBlocks}`, recheck !== null && `Rows Removed by Index Recheck: ${fmt(recheck * loops)}`].filter(Boolean).join('、'),
        'work_mem を増やすと、行単位で読めるようになるかもしれません。',
      );
    }

    // 入れ子ループの内側
    if (/Nested Loop/.test(node.title) && node.children.length >= 2) {
      const inner = node.children[1];
      const innerLoops = inner.actual?.loops ?? 0;
      if (innerLoops >= 1000) {
        const innerTime = times.get(inner)!.inclusive;
        if (/Seq Scan/.test(inner.title)) {
          add(
            'high',
            '入れ子ループの内側で、表を何度も全件読んでいます',
            `内側の ${inner.title} を ${fmt(innerLoops)} 回実行`,
            '結合の条件の列（内側の表の列）にインデックスがあれば、1 回ごとに必要な行だけを読めます。',
          );
        } else if (total > 0 && innerTime / total >= 0.3) {
          add(
            'medium',
            '入れ子ループの内側を何度も実行しています',
            `内側の ${inner.title} を ${fmt(innerLoops)} 回実行（合計 ${innerTime.toFixed(1)} ms）`,
            '外側の行数の見積もりが少なすぎて入れ子ループが選ばれていないかを確かめます（上の見積もりの指摘も参照）。ハッシュ結合のほうが速い場合があります。',
          );
        }
      }
    }

    // ディスクからの読み込み
    const b = node.buffers;
    if (b && node.children.length === 0 && b.read >= 1000 && b.read / Math.max(1, b.hit + b.read) >= 0.5) {
      add(
        'info',
        'ディスクから多く読んでいます',
        `ディスク ${fmt(b.read)} ブロック / キャッシュ ${fmt(b.hit)} ブロック`,
        '初めて実行したときはキャッシュに乗っていないため遅くなります。もう一度実行して速くなるなら、キャッシュの有無による差です。',
      );
    }
  }
  return out.sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]);
}
