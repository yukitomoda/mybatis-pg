// EXPLAIN の結果（JSON 形式・テキスト形式）を解析し、表示しやすい形にする

export interface Buffers {
  hit: number;
  read: number;
  dirtied: number;
  written: number;
  tempRead: number;
  tempWritten: number;
}

export interface PlanNode {
  /** 表示用の名前（例: Parallel Seq Scan on users u） */
  title: string;
  /** 読み書きする表（Seq Scan や Insert などの対象）。なければ null */
  relation: string | null;
  startupCost: number | null;
  totalCost: number | null;
  /** 見積もりの行数（1 回あたり） */
  planRows: number | null;
  /** 実績。ANALYZE なしなら null */
  actual: { startup: number; total: number; rows: number; loops: number } | null;
  /** ANALYZE で一度も実行されなかった */
  neverExecuted: boolean;
  buffers: Buffers | null;
  /** その他の情報（Filter、Sort Key など） */
  details: [string, string][];
  children: PlanNode[];
}

export interface Plan {
  root: PlanNode;
  planningMs: number | null;
  executionMs: number | null;
  /** ANALYZE の結果か */
  analyzed: boolean;
}

/** JSON 形式かテキスト形式かを見分けて解析する */
export function parsePlan(input: string): Plan {
  const text = input.trim();
  if (!text) throw new Error('EXPLAIN の結果が空です');
  if (text.startsWith('[') || text.startsWith('{')) return parseJsonPlan(text);
  return parseTextPlan(text);
}

// ---- JSON 形式 ----

/** 表示用の名前や数値として別に扱う項目（詳細には出さない） */
const JSON_HANDLED = new Set([
  'Node Type',
  'Plans',
  'Parent Relationship',
  'Parallel Aware',
  'Startup Cost',
  'Total Cost',
  'Plan Rows',
  'Plan Width',
  'Actual Startup Time',
  'Actual Total Time',
  'Actual Rows',
  'Actual Loops',
  'Relation Name',
  'Alias',
  'Index Name',
  'Scan Direction',
  'Strategy',
  'Partial Mode',
  'Join Type',
  'Operation',
  'Shared Hit Blocks',
  'Shared Read Blocks',
  'Shared Dirtied Blocks',
  'Shared Written Blocks',
  'Local Hit Blocks',
  'Local Read Blocks',
  'Local Dirtied Blocks',
  'Local Written Blocks',
  'Temp Read Blocks',
  'Temp Written Blocks',
  'I/O Read Time',
  'I/O Write Time',
  'Workers',
]);

type Json = Record<string, unknown>;

function parseJsonPlan(text: string): Plan {
  let data: unknown;
  try {
    data = JSON.parse(text);
  } catch (e) {
    throw new Error('JSON として解釈できません: ' + (e as Error).message);
  }
  const top = (Array.isArray(data) ? data[0] : data) as Json | undefined;
  if (!top || typeof top !== 'object' || !top['Plan']) throw new Error('EXPLAIN (FORMAT JSON) の結果ではありません（"Plan" がありません）');
  const root = jsonNode(top['Plan'] as Json);
  return {
    root,
    planningMs: num(top['Planning Time']),
    executionMs: num(top['Execution Time']),
    analyzed: root.actual !== null || root.neverExecuted,
  };
}

function num(v: unknown): number | null {
  return typeof v === 'number' ? v : null;
}

function jsonTitle(n: Json): string {
  let type = String(n['Node Type']);
  if (type === 'Aggregate') {
    type = n['Strategy'] === 'Sorted' ? 'GroupAggregate' : n['Strategy'] === 'Hashed' ? 'HashAggregate' : n['Strategy'] === 'Mixed' ? 'MixedAggregate' : 'Aggregate';
  }
  if (type === 'ModifyTable' && n['Operation']) type = String(n['Operation']);
  if (n['Join Type'] && n['Join Type'] !== 'Inner' && type.endsWith('Join')) {
    type = type.replace(/Join$/, `${n['Join Type']} Join`);
  }
  if (n['Scan Direction'] === 'Backward') type = type.replace(/Scan$/, 'Scan Backward');
  if (n['Partial Mode'] && n['Partial Mode'] !== 'Simple') type = `${n['Partial Mode']} ${type}`;
  if (n['Parallel Aware']) type = `Parallel ${type}`;
  let title = type;
  // テキスト形式に合わせる（Bitmap Index Scan だけは「on インデックス名」）
  if (n['Index Name']) title += String(n['Node Type']) === 'Bitmap Index Scan' ? ` on ${n['Index Name']}` : ` using ${n['Index Name']}`;
  if (n['Relation Name']) {
    title += ` on ${n['Relation Name']}`;
    if (n['Alias'] && n['Alias'] !== n['Relation Name']) title += ` ${n['Alias']}`;
  }
  return title;
}

function jsonNode(n: Json): PlanNode {
  const loops = num(n['Actual Loops']);
  const hasActual = num(n['Actual Total Time']) !== null && loops !== null && loops > 0;
  const hasBuffers = 'Shared Hit Blocks' in n;
  const details: [string, string][] = [];
  for (const [k, v] of Object.entries(n)) {
    if (JSON_HANDLED.has(k)) continue;
    details.push([k, Array.isArray(v) ? v.join(', ') : typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)]);
  }
  if (Array.isArray(n['Workers'])) {
    (n['Workers'] as Json[]).forEach((w) => {
      const parts = Object.entries(w).filter(([k]) => k !== 'Worker Number').map(([k, v]) => `${k}: ${v}`);
      details.push([`Worker ${w['Worker Number']}`, parts.join(', ')]);
    });
  }
  return {
    title: jsonTitle(n),
    relation: typeof n['Relation Name'] === 'string' ? (n['Schema'] ? `${n['Schema']}.${n['Relation Name']}` : (n['Relation Name'] as string)) : null,
    startupCost: num(n['Startup Cost']),
    totalCost: num(n['Total Cost']),
    planRows: num(n['Plan Rows']),
    actual: hasActual
      ? { startup: num(n['Actual Startup Time']) ?? 0, total: num(n['Actual Total Time']) ?? 0, rows: num(n['Actual Rows']) ?? 0, loops: loops! }
      : null,
    neverExecuted: loops === 0,
    buffers: hasBuffers
      ? {
          hit: (num(n['Shared Hit Blocks']) ?? 0) + (num(n['Local Hit Blocks']) ?? 0),
          read: (num(n['Shared Read Blocks']) ?? 0) + (num(n['Local Read Blocks']) ?? 0),
          dirtied: (num(n['Shared Dirtied Blocks']) ?? 0) + (num(n['Local Dirtied Blocks']) ?? 0),
          written: (num(n['Shared Written Blocks']) ?? 0) + (num(n['Local Written Blocks']) ?? 0),
          tempRead: num(n['Temp Read Blocks']) ?? 0,
          tempWritten: num(n['Temp Written Blocks']) ?? 0,
        }
      : null,
    details,
    children: Array.isArray(n['Plans']) ? (n['Plans'] as Json[]).map(jsonNode) : [],
  };
}

// ---- テキスト形式 ----

const NODE_LINE =
  /^(.*?)\s+\(cost=([\d.]+)\.\.([\d.]+) rows=(\d+) width=\d+\)(?:\s+\(actual time=([\d.]+)\.\.([\d.]+) rows=(\d+) loops=(\d+)\)|\s+\((never executed)\))?\s*$/;

/** psql や A5M2 などで取ったテキスト形式を解析する。見出しや罫線、行末の + は読み飛ばす */
function parseTextPlan(text: string): Plan {
  const lines = text
    .split(/\r?\n/)
    .map((l) => l.replace(/\s*\+$/, '').replace(/^"|"$/g, ''))
    .filter((l) => l.trim() !== '' && !/^\s*QUERY PLAN\s*$/.test(l) && !/^[-+\s]+$/.test(l) && !/^\(\d+ rows?\)$/.test(l.trim()));
  let root: PlanNode | null = null;
  let planningMs: number | null = null;
  let executionMs: number | null = null;
  // 字下げの深さと、その位置のノード
  const stack: { indent: number; node: PlanNode }[] = [];
  for (const raw of lines) {
    const arrow = raw.indexOf('->');
    const isNode = arrow >= 0 && raw.slice(0, arrow).trim() === '';
    const body = isNode ? raw.slice(arrow + 2) : raw;
    const indent = isNode ? arrow : raw.length - raw.trimStart().length;
    const m = NODE_LINE.exec(body.trim());
    if (m && (isNode || root === null)) {
      const node: PlanNode = {
        title: m[1].trim(),
        relation: textRelation(m[1].trim()),
        startupCost: Number(m[2]),
        totalCost: Number(m[3]),
        planRows: Number(m[4]),
        actual: m[5] ? { startup: Number(m[5]), total: Number(m[6]), rows: Number(m[7]), loops: Number(m[8]) } : null,
        neverExecuted: m[9] === 'never executed',
        buffers: null,
        details: [],
        children: [],
      };
      if (root === null) {
        root = node;
        stack.push({ indent: -1, node });
      } else {
        while (stack.length > 1 && stack[stack.length - 1].indent >= indent) stack.pop();
        stack[stack.length - 1].node.children.push(node);
        stack.push({ indent, node });
      }
      continue;
    }
    const line = raw.trim();
    const time = /^(Planning|Execution) [Tt]ime:\s*([\d.]+)\s*ms$/.exec(line);
    if (time) {
      if (time[1] === 'Planning') planningMs = Number(time[2]);
      else executionMs = Number(time[2]);
      continue;
    }
    if (root === null) continue;
    // 詳細は、字下げがより浅いノードのうち最も近いものに付ける
    while (stack.length > 1 && stack[stack.length - 1].indent >= indent) stack.pop();
    const owner = stack[stack.length - 1].node;
    const colon = line.indexOf(': ');
    const key = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? '' : line.slice(colon + 2).trim();
    if (key === 'Buffers') {
      owner.buffers = parseBuffers(value);
    } else if (/^(Planning|Execution) [Tt]ime$/.test(key)) {
      // 上で処理済み
    } else {
      owner.details.push([key, value]);
    }
  }
  if (!root) throw new Error('EXPLAIN の結果として解釈できません（「(cost=... rows=... width=...)」のある行がありません）');
  return { root, planningMs, executionMs, analyzed: root.actual !== null || root.neverExecuted };
}

/** テキスト形式の名前から表を取り出す（「Index Scan using idx on users u」なら users）。Bitmap Index Scan の on はインデックス */
function textRelation(title: string): string | null {
  if (/^Bitmap Index Scan/.test(title)) return null;
  const m = / on (\S+)/.exec(title);
  return m ? m[1] : null;
}

/** 計画に出てくる表（重なりなし、出てきた順） */
export function relations(root: PlanNode): string[] {
  const out: string[] = [];
  for (const { node } of flatten(root)) {
    if (node.relation && !out.includes(node.relation)) out.push(node.relation);
  }
  return out;
}

/** 例: shared hit=4222 read=1, temp read=1636 written=2001 */
function parseBuffers(s: string): Buffers {
  const b: Buffers = { hit: 0, read: 0, dirtied: 0, written: 0, tempRead: 0, tempWritten: 0 };
  for (const part of s.split(',')) {
    const words = part.trim().split(/\s+/);
    const kind = words[0];
    for (const w of words.slice(1)) {
      const [k, v] = w.split('=');
      const n = Number(v);
      if (Number.isNaN(n)) continue;
      if (kind === 'temp') {
        if (k === 'read') b.tempRead += n;
        if (k === 'written') b.tempWritten += n;
      } else if (k === 'hit' || k === 'read' || k === 'dirtied' || k === 'written') {
        b[k] += n;
      }
    }
  }
  return b;
}

// ---- 計算 ----

export interface Timing {
  /** 子のノードを含む時間（ms） */
  inclusive: number;
  /** ノード自身の時間（ms）。合計から子の時間を引いたもの */
  self: number;
}

/**
 * 各ノードの時間を求める。actual time は 1 ループあたりの平均なので、ループ数を掛けて全体の時間にする。
 * ただし Gather の下（並列実行の部分）では、ループ数にプロセスの数が含まれ、同時に動いた時間を重ねて数えてしまう。
 * そこでその部分は、プロセスの数（Gather の直下のノードのループ数）で割って、経過時間の目安にする。
 */
export function timings(root: PlanNode): Map<PlanNode, Timing> {
  const out = new Map<PlanNode, Timing>();
  const inclusive = (n: PlanNode, processes: number) => (n.actual ? (n.actual.total * n.actual.loops) / processes : 0);
  const walk = (n: PlanNode, processes: number) => {
    const isGather = /^Gather( Merge)?$/.test(n.title);
    const childProcesses = (c: PlanNode) => (isGather && c.actual ? Math.max(1, c.actual.loops) : processes);
    const own = inclusive(n, processes);
    const children = n.children.reduce((sum, c) => sum + inclusive(c, childProcesses(c)), 0);
    out.set(n, { inclusive: own, self: Math.max(0, own - children) });
    n.children.forEach((c) => walk(c, childProcesses(c)));
  };
  walk(root, 1);
  return out;
}

/** 実績の行数が見積もりの何倍か（1 未満なら見積もりより少ない）。比べられなければ null */
export function estimateRatio(n: PlanNode): number | null {
  if (!n.actual || n.planRows === null) return null;
  return Math.max(n.actual.rows, 1) / Math.max(n.planRows, 1);
}

/** 木を、深さ付きの行の並びにする */
export function flatten(root: PlanNode): { node: PlanNode; depth: number; path: string }[] {
  const out: { node: PlanNode; depth: number; path: string }[] = [];
  const walk = (n: PlanNode, depth: number, path: string) => {
    out.push({ node: n, depth, path });
    n.children.forEach((c, i) => walk(c, depth + 1, `${path}.${i}`));
  };
  walk(root, 0, '0');
  return out;
}
