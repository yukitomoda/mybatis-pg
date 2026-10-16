// 生成値（式）: 入力欄の {{関数:引数1|引数2}} を計算する
import type { Issue, Path, PValue } from './params';
import { isValidNumber, pathLabel } from './params';

/** 式の一部分 */
export interface Expr {
  /** 書かれたままの式（{{ }} を含む） */
  raw: string;
  fn: string;
  args: string[];
}

export type Part = string | Expr;

/** 式を含む文字列を、文字と式に分ける。誤りがあれば例外 */
export function parseTemplate(text: string): Part[] {
  const parts: Part[] = [];
  let buf = '';
  let i = 0;
  while (i < text.length) {
    if (text.startsWith('\\{{', i)) {
      buf += '{{';
      i += 3;
      continue;
    }
    if (text.startsWith('{{', i)) {
      const end = text.indexOf('}}', i + 2);
      if (end < 0) throw new Error('式が }} で閉じられていません');
      if (buf) parts.push(buf);
      buf = '';
      const raw = text.slice(i, end + 2);
      const body = text.slice(i + 2, end).trim();
      const colon = body.indexOf(':');
      const fn = (colon < 0 ? body : body.slice(0, colon)).trim();
      const args = colon < 0 ? [] : body.slice(colon + 1).split('|').map((a) => a.trim());
      if (!fn) throw new Error(`関数名がありません: ${raw}`);
      parts.push({ raw, fn, args });
      i = end + 2;
      continue;
    }
    buf += text[i];
    i++;
  }
  if (buf) parts.push(buf);
  return parts;
}

/** 式を含むか（\{{ だけなら含まない） */
export function hasTemplate(text: string): boolean {
  return /(^|[^\\])\{\{/.test(text);
}

/** 文字として書いた \{{ を {{ に戻す */
export function unescape(text: string): string {
  return text.split('\\{{').join('{{');
}

// ---- 関数 ----

export interface ArgSpec {
  label: string;
  placeholder: string;
  /** 省略したときの値（説明用） */
  hint?: string;
}

export interface FnSpec {
  name: string;
  label: string;
  /** 結果が数値か */
  numeric: boolean;
  /** 作るたびに値が変わりうる（固定して ↻ で作り直す） */
  volatile: (args: string[]) => boolean;
  args: ArgSpec[];
  run: (args: string[], now: Date) => string;
}

const BASE_ARG: ArgSpec = {
  label: '基準',
  placeholder: 'now、today、2026-01-03 23:35、now-7d など',
  hint: '省略すると now',
};

const usesNow = (args: string[]) => !args[0] || /^(now|today)/i.test(args[0].trim());

export const FUNCTIONS: FnSpec[] = [
  {
    name: 'uuid',
    label: 'UUIDv4',
    numeric: false,
    volatile: () => true,
    args: [],
    run: () => uuidv4(),
  },
  {
    name: 'date',
    label: '日時の文字列',
    numeric: false,
    volatile: usesNow,
    args: [BASE_ARG, { label: '書式', placeholder: 'yyyy-MM-dd HH:mm:ss', hint: '省略すると yyyy-MM-dd HH:mm:ss' }],
    run: ([base, format], now) => formatDate(parseDate(base ?? '', now), format || 'yyyy-MM-dd HH:mm:ss'),
  },
  {
    name: 'unixtime',
    label: 'UNIX 時刻',
    numeric: true,
    volatile: usesNow,
    args: [BASE_ARG, { label: '単位', placeholder: 's または ms', hint: '省略すると s（秒）' }],
    run: ([base, unit], now) => {
      const ms = parseDate(base ?? '', now).getTime();
      const u = (unit || 's').trim();
      if (u === 'ms') return String(ms);
      if (u === 's') return String(Math.floor(ms / 1000));
      throw new Error(`単位は s か ms にしてください: ${u}`);
    },
  },
  {
    name: 'random',
    label: '乱数（整数）',
    numeric: true,
    volatile: () => true,
    args: [{ label: '範囲', placeholder: '1..100' }],
    run: ([range]) => {
      const m = /^\s*(-?\d+)\s*\.\.\s*(-?\d+)\s*$/.exec(range ?? '');
      if (!m) throw new Error('範囲は 1..100 のように書いてください');
      const lo = Number(m[1]);
      const hi = Number(m[2]);
      if (lo > hi) throw new Error('範囲は小さい方から書いてください');
      return String(lo + Math.floor(randomFraction() * (hi - lo + 1)));
    },
  },
  {
    name: 'randstr',
    label: 'ランダムな文字列',
    numeric: false,
    volatile: () => true,
    args: [{ label: '長さ', placeholder: '16', hint: '省略すると 16' }],
    run: ([length]) => {
      const n = length ? Number(length) : 16;
      if (!Number.isInteger(n) || n < 1 || n > 1000) throw new Error('長さは 1〜1000 の整数にしてください');
      const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
      let s = '';
      for (let i = 0; i < n; i++) s += chars[Math.floor(randomFraction() * chars.length)];
      return s;
    },
  },
];

export function findFunction(name: string): FnSpec | undefined {
  return FUNCTIONS.find((f) => f.name === name);
}

/** 式を組み立てる（ƒ パネルから挿入する文字） */
export function buildExpr(fn: string, args: string[]): string {
  // 末尾の空の引数は省く
  const trimmed = [...args];
  while (trimmed.length > 0 && trimmed[trimmed.length - 1].trim() === '') trimmed.pop();
  return trimmed.length === 0 ? `{{${fn}}}` : `{{${fn}:${trimmed.join('|')}}}`;
}

// ---- 計算 ----

/** 作った値を覚えておく場所。key は項目の位置と式 */
export type GenCache = Map<string, string>;

export interface Evaluated {
  /** 計算した文字列 */
  text: string;
  /** 作るたびに変わる式を含む */
  volatile: boolean;
}

/**
 * 式を計算する。作るたびに変わる値は cache から取り出し、なければ作って覚える。
 * @param key 項目の位置（作り直すときの単位）
 */
export function evaluateTemplate(text: string, key: string, cache: GenCache, numeric: boolean, now = new Date()): Evaluated {
  const parts = parseTemplate(text);
  let volatile = false;
  let out = '';
  parts.forEach((p, i) => {
    if (typeof p === 'string') {
      out += p;
      return;
    }
    const spec = findFunction(p.fn);
    if (!spec) throw new Error(`知らない関数です: ${p.fn}（使えるもの: ${FUNCTIONS.map((f) => f.name).join(', ')}）`);
    if (numeric && !spec.numeric) throw new Error(`数値の欄では ${p.fn} は使えません（結果が文字列のため）`);
    if (spec.volatile(p.args)) {
      volatile = true;
      const k = `${key}#${i}:${p.raw}`;
      let v = cache.get(k);
      if (v === undefined) {
        v = spec.run(p.args, now);
        cache.set(k, v);
      }
      out += v;
    } else {
      out += spec.run(p.args, now);
    }
  });
  return { text: out, volatile };
}

/** key の項目で作った値を忘れる（次に計算するときに作り直す） */
export function forget(cache: GenCache, key: string) {
  for (const k of [...cache.keys()]) {
    if (k.startsWith(key + '#')) cache.delete(k);
  }
}

// ---- 日時 ----

const ABSOLUTE = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?)?\s*(Z|[+-]\d{2}:?\d{2})?/;
const RELATIVE = /^([+-])\s*(\d+)\s*([yMdhms])/;

/** 基準の日時を解釈する。now / today / 日時に、-7d のような相対指定を続けられる */
export function parseDate(text: string, now: Date): Date {
  let rest = text.trim();
  let date: Date;
  const lower = rest.toLowerCase();
  if (rest === '' || lower.startsWith('now')) {
    date = new Date(now.getTime());
    rest = rest.slice(3);
  } else if (lower.startsWith('today')) {
    date = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    rest = rest.slice(5);
  } else {
    const m = ABSOLUTE.exec(rest);
    if (!m) throw new Error(`日時として解釈できません: ${text}（例: 2026-01-03 23:35、now-7d）`);
    const [y, mo, d, h, mi, s, ms] = [m[1], m[2], m[3], m[4], m[5], m[6], m[7]].map((v) => (v ? Number(v) : 0));
    const millis = m[7] ? Number(m[7].padEnd(3, '0')) : ms;
    if (m[8]) {
      const offset = m[8] === 'Z' ? 0 : offsetMinutes(m[8]);
      date = new Date(Date.UTC(y, mo - 1, d, h, mi, s, millis) - offset * 60_000);
    } else {
      date = new Date(y, mo - 1, d, h, mi, s, millis);
    }
    // 2026-02-30 のような存在しない日付は受け付けない
    if (!m[8] && (date.getMonth() !== mo - 1 || date.getDate() !== d)) throw new Error(`存在しない日付です: ${m[0].trim()}`);
    rest = rest.slice(m[0].length);
  }
  rest = rest.trim();
  while (rest) {
    const m = RELATIVE.exec(rest);
    if (!m) throw new Error(`相対指定は -7d や +1M のように書いてください: ${rest}`);
    const n = Number(m[2]) * (m[1] === '-' ? -1 : 1);
    date = addUnit(date, n, m[3]);
    rest = rest.slice(m[0].length).trim();
  }
  return date;
}

function offsetMinutes(s: string): number {
  const m = /^([+-])(\d{2}):?(\d{2})$/.exec(s)!;
  return (m[1] === '-' ? -1 : 1) * (Number(m[2]) * 60 + Number(m[3]));
}

function addUnit(date: Date, n: number, unit: string): Date {
  const d = new Date(date.getTime());
  switch (unit) {
    case 'y':
      d.setFullYear(d.getFullYear() + n);
      break;
    case 'M':
      d.setMonth(d.getMonth() + n);
      break;
    case 'd':
      d.setDate(d.getDate() + n);
      break;
    case 'h':
      d.setHours(d.getHours() + n);
      break;
    case 'm':
      d.setMinutes(d.getMinutes() + n);
      break;
    case 's':
      d.setSeconds(d.getSeconds() + n);
      break;
  }
  return d;
}

/** Java と同じ書き方の書式で、ブラウザのタイムゾーンの日時にする */
export function formatDate(date: Date, format: string): string {
  const pad = (n: number, w: number) => String(n).padStart(w, '0');
  const fields: Record<string, (len: number) => string> = {
    y: (len) => (len === 2 ? pad(date.getFullYear() % 100, 2) : pad(date.getFullYear(), len)),
    M: (len) => pad(date.getMonth() + 1, len),
    d: (len) => pad(date.getDate(), len),
    H: (len) => pad(date.getHours(), len),
    m: (len) => pad(date.getMinutes(), len),
    s: (len) => pad(date.getSeconds(), len),
    S: (len) => pad(date.getMilliseconds(), 3).slice(0, len),
  };
  let out = '';
  let i = 0;
  while (i < format.length) {
    const c = format[i];
    if (c === "'") {
      const end = format.indexOf("'", i + 1);
      if (end < 0) throw new Error("書式の ' が閉じられていません");
      out += end === i + 1 ? "'" : format.slice(i + 1, end);
      i = end + 1;
      continue;
    }
    if (fields[c]) {
      let len = 1;
      while (format[i + len] === c) len++;
      out += fields[c](len);
      i += len;
      continue;
    }
    if (/[A-Za-z]/.test(c)) throw new Error(`書式に使えない文字です: ${c}（'${c}' のように囲むとそのまま出ます）`);
    out += c;
    i++;
  }
  return out;
}

// ---- 乱数 ----

function randomFraction(): number {
  const a = new Uint32Array(1);
  crypto.getRandomValues(a);
  return a[0] / 2 ** 32;
}

function uuidv4(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

// ---- パラメータの木 ----

export interface Generated {
  /** 項目の位置（例: cond.ids[0]） */
  path: string;
  /** 書かれた式 */
  source: string;
  /** 計算した値 */
  result: string;
  volatile: boolean;
}

export interface Resolved {
  value: PValue | undefined;
  /** 式の誤り */
  issues: Issue[];
  generated: Generated[];
}

/**
 * パラメータの木の中の式を計算し、計算後の値に置き換える。
 * 式のない値はそのまま。数値の欄の式は、結果が数値でなければ誤りにする。
 */
export function resolveTemplates(value: PValue | undefined, cache: GenCache, now = new Date()): Resolved {
  const issues: Issue[] = [];
  const generated: Generated[] = [];
  const walk = (v: PValue, path: Path): PValue => {
    switch (v.t) {
      case 'string':
      case 'number':
      case 'timestamp':
      case 'date':
      case 'pg': {
        if (!hasTemplate(v.v)) return v.v.includes('\\{{') ? { ...v, v: unescape(v.v) } : v;
        const label = pathLabel(path);
        try {
          const r = evaluateTemplate(v.v, label, cache, v.t === 'number', now);
          if (v.t === 'number' && !isValidNumber(r.text)) throw new Error(`結果が数値になりません: ${r.text}`);
          generated.push({ path: label, source: v.v, result: r.text, volatile: r.volatile });
          return { ...v, v: r.text };
        } catch (e) {
          issues.push({ path: label, message: (e as Error).message });
          return v;
        }
      }
      case 'list': {
        const items = v.items.map((item, i) => walk(item, [...path, i]));
        return items.some((item, i) => item !== v.items[i]) ? { t: 'list', items } : v;
      }
      case 'object': {
        const fields = v.fields.map(([k, f]): [string, PValue] => [k, walk(f, [...path, k])]);
        return fields.some(([, f], i) => f !== v.fields[i][1]) ? { t: 'object', fields } : v;
      }
      default:
        return v;
    }
  };
  return { value: value === undefined ? undefined : walk(value, []), issues, generated };
}
