// パラメータの値の表現と、JSON との相互変換、木の操作

/** サーバが返すパラメータの木（ParamNode） */
export type Usage = 'BIND' | 'RAW' | 'CONDITION' | 'ITERATE';
export type TypeHint = 'STRING' | 'NUMBER' | 'BOOLEAN';
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
  return 'string';
}

export function emptyValue(t: PType): PValue {
  switch (t) {
    case 'string':
      return { t, v: '' };
    case 'number':
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

/** 型を変える。文字列と数値の間では入力済みの文字を引き継ぐ */
export function convertType(value: PValue | undefined, t: PType): PValue {
  if (value && (value.t === 'string' || value.t === 'number') && (t === 'string' || t === 'number')) {
    return { t, v: value.v };
  }
  return emptyValue(t);
}

/**
 * foreach で回す項目のうち未設定のものを、空のリストにする。
 * 対象は値のあるオブジェクトの中（一番外側は常に対象）で、未設定の親を作ってまでは入れない。
 * 変える必要がなければ、受け取った値をそのまま返す。
 */
export function withListDefaults(schema: ParamSchema, value: PValue | undefined): PValue | undefined {
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
      if (p.name !== null && p.usages.includes('ITERATE') && !fields.some(([k]) => k === p.name)) {
        fields.push([p.name, { t: 'list', items: [] }]);
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

/** 数値として不正な入力 */
export function invalidNumbers(value: PValue | undefined, path: Path = []): Issue[] {
  if (!value) return [];
  switch (value.t) {
    case 'number':
      return isValidNumber(value.v) ? [] : [{ path: pathLabel(path), message: `数値として解釈できません: "${value.v}"` }];
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
      return isValidNumber(value.v) ? value.v.trim() : 'null';
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
          return { t: 'object', fields };
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
