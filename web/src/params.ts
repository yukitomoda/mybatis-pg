// パラメータの値の表現と、JSON との相互変換、木の操作

/** サーバが返すパラメータの木（ParamNode） */
export type Usage = 'BIND' | 'RAW' | 'CONDITION' | 'ITERATE';
export type TypeHint = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'TIMESTAMP' | 'DATE';
export interface ParamSchema {
  name: string | null;
  usages: Usage[];
  typeHints: TypeHint[];
  properties: ParamSchema[];
  element: ParamSchema | null;
}

/**
 * 入力値。数値は精度を落とさないよう、入力した文字列のまま持つ。
 * オブジェクトは項目の順序を保つため、[名前, 値] の配列で持つ。
 */
export type PValue =
  | { t: 'string'; v: string }
  | { t: 'number'; v: string }
  /** 日時（timestamp としてバインドする）。yyyy-MM-dd HH:mm:ss の形の文字列で持つ */
  | { t: 'timestamp'; v: string }
  /** 日付（date としてバインドする）。yyyy-MM-dd の形の文字列で持つ */
  | { t: 'date'; v: string }
  | { t: 'boolean'; v: boolean }
  | { t: 'null' }
  | { t: 'list'; items: PValue[] }
  | { t: 'object'; fields: [string, PValue][] };

export type PType = PValue['t'];

/** 木の中の位置。オブジェクトの項目名かリストの添字 */
export type PathSegment = string | number;
export type Path = PathSegment[];

export const TYPE_LABELS: Record<PType, string> = {
  string: '文字列',
  number: '数値',
  timestamp: '日時',
  date: '日付',
  boolean: '真偽値',
  null: 'NULL',
  list: 'リスト',
  object: 'オブジェクト',
};

export const EMPTY_ROOT: PValue = { t: 'object', fields: [] };

const NUMBER_PATTERN = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;

export function isValidNumber(text: string): boolean {
  return NUMBER_PATTERN.test(text.trim());
}

/** 木の形と型の推測から、項目の型の初期値を決める */
export function defaultType(schema: ParamSchema | null | undefined): PType {
  if (!schema) return 'string';
  if (schema.element) return 'list';
  if (schema.properties.length > 0) return 'object';
  if (schema.typeHints.includes('NUMBER')) return 'number';
  if (schema.typeHints.includes('BOOLEAN')) return 'boolean';
  if (schema.typeHints.includes('TIMESTAMP')) return 'timestamp';
  if (schema.typeHints.includes('DATE')) return 'date';
  return 'string';
}

export function emptyValue(t: PType): PValue {
  switch (t) {
    case 'string':
      return { t, v: '' };
    case 'number':
    case 'timestamp':
    case 'date':
      return { t, v: '' };
    case 'boolean':
      return { t, v: false };
    case 'null':
      return { t };
    case 'list':
      return { t, items: [] };
    case 'object':
      return { t, fields: [] };
  }
}

/** 文字で入力する型（文字列・数値・日時・日付） */
export type TextType = 'string' | 'number' | 'timestamp' | 'date';

export function isTextType(t: PType): t is TextType {
  return t === 'string' || t === 'number' || t === 'timestamp' || t === 'date';
}

/** 型を変える。文字で入力する型どうしでは、入力済みの文字を引き継ぐ */
export function convertType(value: PValue | undefined, t: PType): PValue {
  if (value && isTextType(value.t) && isTextType(t) && 'v' in value && typeof value.v === 'string') {
    return { t, v: value.v };
  }
  return emptyValue(t);
}

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?)?$/;

export function isValidDate(text: string): boolean {
  return DATE_PATTERN.test(text.trim());
}

export function isValidTimestamp(text: string): boolean {
  return TIMESTAMP_PATTERN.test(text.trim());
}

/**
 * 共通パラメータ（base）に個別の値（override）を重ねる。個別の値が優先する。
 * オブジェクトは項目ごとに重ね、それ以外（リストや NULL を含む）は個別の値で丸ごと置き換える。
 */
export function mergeValues(base: PValue | undefined, override: PValue | undefined): PValue | undefined {
  if (override === undefined) return base;
  if (base === undefined || base.t !== 'object' || override.t !== 'object') return override;
  const fields: [string, PValue][] = base.fields.map(([k, v]) => {
    const o = override.fields.find(([name]) => name === k);
    return [k, o ? (mergeValues(v, o[1]) as PValue) : v];
  });
  for (const [k, v] of override.fields) {
    if (!base.fields.some(([name]) => name === k)) fields.push([k, v]);
  }
  return { t: 'object', fields };
}

/** レンダリングと実行に使う値（共通パラメータ + 個別の値 + 既定の値） */
export function effectiveValue(
  schema: ParamSchema | null,
  base: PValue | undefined,
  override: PValue | undefined,
): PValue | undefined {
  const merged = mergeValues(base, override);
  return schema ? withDefaults(schema, merged) : merged;
}

/**
 * 未設定の項目に、XML での使われ方から決まる既定の値を入れる。
 * - foreach で回す項目（要素を持つ項目）は空のリスト
 * - 子の項目を持つ項目は空のオブジェクト（その中にも同じように既定の値を入れる）
 * 変える必要がなければ、受け取った値をそのまま返す。
 */
export function withDefaults(schema: ParamSchema, value: PValue | undefined): PValue | undefined {
  const filled = fill(schema, value ?? EMPTY_ROOT);
  return filled === (value ?? EMPTY_ROOT) ? value : filled;
}

function fill(schema: ParamSchema, value: PValue): PValue {
  if (value.t === 'object') {
    let changed = false;
    const fields = value.fields.map(([k, v]): [string, PValue] => {
      const child = schema.properties.find((p) => p.name === k);
      const next = child ? fill(child, v) : v;
      if (next !== v) changed = true;
      return [k, next];
    });
    for (const p of schema.properties) {
      if (p.name === null || fields.some(([k]) => k === p.name)) continue;
      const initial = defaultContainer(p);
      if (initial) {
        fields.push([p.name, fill(p, initial)]);
        changed = true;
      }
    }
    return changed ? { t: 'object', fields } : value;
  }
  if (value.t === 'list' && schema.element) {
    const element = schema.element;
    const items = value.items.map((item) => fill(element, item));
    return items.some((item, i) => item !== value.items[i]) ? { t: 'list', items } : value;
  }
  return value;
}

/** 既定の値を入れる項目なら、その空の値 */
function defaultContainer(schema: ParamSchema): PValue | null {
  if (schema.usages.includes('ITERATE') || schema.element) return { t: 'list', items: [] };
  if (schema.properties.length > 0) return { t: 'object', fields: [] };
  return null;
}

// ---- 木の操作 ----

export function getAt(root: PValue | undefined, path: Path): PValue | undefined {
  let cur = root;
  for (const seg of path) {
    if (!cur) return undefined;
    if (typeof seg === 'number') {
      cur = cur.t === 'list' ? cur.items[seg] : undefined;
    } else {
      cur = cur.t === 'object' ? cur.fields.find(([k]) => k === seg)?.[1] : undefined;
    }
  }
  return cur;
}

/**
 * path の位置を value にした新しい木を返す。value が undefined なら削除する。
 * 途中のオブジェクトやリストがなければ作る（子の項目に入力すると親も設定される）。
 */
export function setAt(root: PValue | undefined, path: Path, value: PValue | undefined): PValue | undefined {
  if (path.length === 0) return value;
  const [seg, ...rest] = path;
  if (typeof seg === 'number') {
    const items = root && root.t === 'list' ? [...root.items] : [];
    const child = setAt(items[seg], rest, value);
    if (child === undefined) {
      items.splice(seg, 1);
    } else {
      while (items.length < seg) items.push({ t: 'null' });
      items[seg] = child;
    }
    return { t: 'list', items };
  }
  const fields: [string, PValue][] = root && root.t === 'object' ? [...root.fields] : [];
  const i = fields.findIndex(([k]) => k === seg);
  const child = setAt(i >= 0 ? fields[i][1] : undefined, rest, value);
  if (child === undefined) {
    if (i >= 0) fields.splice(i, 1);
  } else if (i >= 0) {
    fields[i] = [seg, child];
  } else {
    fields.push([seg, child]);
  }
  return { t: 'object', fields };
}

/**
 * 共通の値の上に、個別の値を path の位置で設定する（value が undefined なら個別の値を消す）。
 * 共通から来ているリストの中を変えるときは、リストは丸ごと置き換わるため、先にリストを個別の値に写す。
 */
export function overrideAt(
  local: PValue | undefined,
  effective: PValue | undefined,
  path: Path,
  value: PValue | undefined,
): PValue | undefined {
  let next = local;
  for (let i = 1; i < path.length; i++) {
    const prefix = path.slice(0, i);
    const own = getAt(next, prefix);
    if (own !== undefined && own.t !== 'object') break;
    if (own === undefined) {
      const shown = getAt(effective, prefix);
      if (shown && shown.t === 'list') {
        next = setAt(next, prefix, shown);
        break;
      }
    }
  }
  return setAt(next, path, value);
}

/**
 * 個別の値を消したときに path の位置に来る値（共通の値や foreach の空リスト）。
 * 途中に個別のリストや NULL などがあると、その下は共通の値と重ならないので undefined。
 */
export function inheritedAt(local: PValue | undefined, inherited: PValue | undefined, path: Path): PValue | undefined {
  for (let i = 1; i < path.length; i++) {
    const own = getAt(local, path.slice(0, i));
    if (own !== undefined && own.t !== 'object') return undefined;
  }
  return getAt(inherited, path);
}

export function pathLabel(path: Path): string {
  let s = '';
  for (const seg of path) {
    s += typeof seg === 'number' ? `[${seg}]` : s === '' ? seg : `.${seg}`;
  }
  return s;
}

// ---- 検査 ----

export interface Issue {
  path: string;
  message: string;
}

/** 数値・日時・日付として不正な入力 */
export function invalidNumbers(value: PValue | undefined, path: Path = []): Issue[] {
  if (!value) return [];
  switch (value.t) {
    case 'number':
      return isValidNumber(value.v) ? [] : [{ path: pathLabel(path), message: `数値として解釈できません: "${value.v}"` }];
    case 'timestamp':
      return isValidTimestamp(value.v) ? [] : [{ path: pathLabel(path), message: `日時として解釈できません: "${value.v}"（例: 2026-01-03 23:35:00）` }];
    case 'date':
      return isValidDate(value.v) ? [] : [{ path: pathLabel(path), message: `日付として解釈できません: "${value.v}"（例: 2026-01-03）` }];
    case 'list':
      return value.items.flatMap((item, i) => invalidNumbers(item, [...path, i]));
    case 'object':
      return value.fields.flatMap(([k, v]) => invalidNumbers(v, [...path, k]));
    default:
      return [];
  }
}

/**
 * XML で参照されているのに値がない項目。MyBatis では null として扱われる。
 * リストの要素の項目は、リストに要素があるときだけ要素ごとに調べる。
 */
export function missingParams(schema: ParamSchema, value: PValue | undefined, path: Path = []): string[] {
  const out: string[] = [];
  for (const child of schema.properties) {
    const childPath = [...path, child.name as string];
    const v = getAt(value, [child.name as string]);
    if (v === undefined) {
      if (child.usages.length > 0) out.push(pathLabel(childPath));
      // 親が未設定なら、子の参照も評価されないことが多いので、それ以上は調べない
      continue;
    }
    out.push(...missingParams(child, v, childPath));
  }
  if (schema.element && value && value.t === 'list') {
    value.items.forEach((item, i) => out.push(...missingParams(schema.element as ParamSchema, item, [...path, i])));
  }
  return out;
}

// ---- JSON ----

/** 型付きの値（サーバの TypedValues と同じ形） */
function typed(type: string, text: string): string {
  return `{"$type":${JSON.stringify(type)},"value":${JSON.stringify(text)}}`;
}

/** 型付きの値なら、その値にする */
function fromTyped(fields: [string, PValue][]): PValue | null {
  if (fields.length !== 2) return null;
  const type = fields.find(([k]) => k === '$type')?.[1];
  const value = fields.find(([k]) => k === 'value')?.[1];
  if (!type || type.t !== 'string' || !value || value.t !== 'string') return null;
  if (type.v === 'timestamp' || type.v === 'date' || type.v === 'number') return { t: type.v, v: value.v };
  return null;
}

/** 数値の文字列をそのまま書き出す JSON 変換 */
export function toJson(value: PValue | undefined, indent = '  ', depth = 0): string {
  if (value === undefined) return 'null';
  const pad = indent ? '\n' + indent.repeat(depth + 1) : '';
  const end = indent ? '\n' + indent.repeat(depth) : '';
  const sep = indent ? ': ' : ':';
  switch (value.t) {
    case 'string':
      return JSON.stringify(value.v);
    case 'number':
      // 数値として書けない値（計算前の式など）は、型付きの値として文字のまま残す
      return isValidNumber(value.v) ? value.v.trim() : typed('number', value.v);
    case 'timestamp':
    case 'date':
      // JSON には日時の型がないので、型付きの値として送る（サーバで timestamp・date に直す）
      return typed(value.t, value.v);
    case 'boolean':
      return value.v ? 'true' : 'false';
    case 'null':
      return 'null';
    case 'list':
      if (value.items.length === 0) return '[]';
      return '[' + value.items.map((item) => pad + toJson(item, indent, depth + 1)).join(',') + end + ']';
    case 'object':
      if (value.fields.length === 0) return '{}';
      return (
        '{' +
        value.fields.map(([k, v]) => pad + JSON.stringify(k) + sep + toJson(v, indent, depth + 1)).join(',') +
        end +
        '}'
      );
  }
}

/** 数値を文字列のまま取り出す JSON の解析。不正な JSON なら例外 */
export function fromJson(text: string): PValue {
  let pos = 0;
  const fail = (msg: string): never => {
    throw new Error(`${msg}（${pos + 1} 文字目）`);
  };
  const ws = () => {
    while (pos < text.length && ' \t\r\n'.includes(text[pos])) pos++;
  };
  const expect = (s: string) => {
    if (text.startsWith(s, pos)) pos += s.length;
    else fail(`"${s}" が必要です`);
  };
  const str = (): string => {
    const m = /^"(?:[^"\\\u0000-\u001f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/.exec(text.slice(pos));
    if (!m) fail('文字列が不正です');
    pos += m![0].length;
    return JSON.parse(m![0]) as string;
  };
  const val = (): PValue => {
    ws();
    const c = text[pos];
    if (c === '{') {
      pos++;
      const fields: [string, PValue][] = [];
      ws();
      if (text[pos] === '}') {
        pos++;
        return { t: 'object', fields };
      }
      for (;;) {
        ws();
        const k = str();
        ws();
        expect(':');
        const v = val();
        const i = fields.findIndex(([name]) => name === k);
        if (i >= 0) fields[i] = [k, v];
        else fields.push([k, v]);
        ws();
        if (text[pos] === ',') pos++;
        else if (text[pos] === '}') {
          pos++;
          return fromTyped(fields) ?? { t: 'object', fields };
        } else fail('"," か "}" が必要です');
      }
    }
    if (c === '[') {
      pos++;
      const items: PValue[] = [];
      ws();
      if (text[pos] === ']') {
        pos++;
        return { t: 'list', items };
      }
      for (;;) {
        items.push(val());
        ws();
        if (text[pos] === ',') pos++;
        else if (text[pos] === ']') {
          pos++;
          return { t: 'list', items };
        } else fail('"," か "]" が必要です');
      }
    }
    if (c === '"') return { t: 'string', v: str() };
    if (text.startsWith('true', pos)) {
      pos += 4;
      return { t: 'boolean', v: true };
    }
    if (text.startsWith('false', pos)) {
      pos += 5;
      return { t: 'boolean', v: false };
    }
    if (text.startsWith('null', pos)) {
      pos += 4;
      return { t: 'null' };
    }
    const m = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?/.exec(text.slice(pos));
    if (m && m[0] !== '-') {
      pos += m[0].length;
      return { t: 'number', v: m[0] };
    }
    return fail('値が必要です');
  };
  const result = val();
  ws();
  if (pos < text.length) fail('余分な文字があります');
  return result;
}
