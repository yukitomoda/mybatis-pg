// SQL の中のサブクエリ・CTE を見つけ、部分ごとに実行できる SQL を作る（試作）
//
// 構文を完全に解析するのではなく、文字列・コメント・引用を読み飛ばしながら括弧の対応を取り、
// SELECT・WITH・VALUES で始まる括弧をサブクエリとして拾う。PostgreSQL 特有の書き方があっても壊れにくくするため。

export type PartKind = 'main' | 'cte' | 'from' | 'in' | 'exists' | 'select' | 'scalar';

export interface Part {
  /** 木の中での位置（例: 0.1.2） */
  key: string;
  kind: PartKind;
  /** 一覧に出す名前（CTE の名前・FROM の別名など） */
  name: string | null;
  /** SQL の中の範囲（括弧の内側） */
  start: number;
  end: number;
  children: Part[];
  /** 外側のクエリの列への参照（相関サブクエリ）。例: o.id */
  outerRefs: string[];
  /** INSERT・UPDATE・DELETE（実行しない） */
  writes: boolean;
}

interface Token {
  kind: 'word' | 'quoted' | 'other' | '(' | ')' | ',' | '.';
  text: string;
  /** 大文字にした語（word のときだけ） */
  upper: string;
  start: number;
  end: number;
}

/** 文字列・コメント・引用を読み飛ばして字句に分ける */
export function tokenize(sql: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  const n = sql.length;
  const push = (kind: Token['kind'], start: number, end: number) =>
    out.push({ kind, text: sql.slice(start, end), upper: kind === 'word' ? sql.slice(start, end).toUpperCase() : '', start, end });
  while (i < n) {
    const c = sql[i];
    const next = sql[i + 1];
    if (/\s/.test(c)) {
      i++;
    } else if (c === '-' && next === '-') {
      while (i < n && sql[i] !== '\n') i++;
    } else if (c === '/' && next === '*') {
      // PostgreSQL のブロックコメントは入れ子にできる
      let depth = 0;
      while (i < n) {
        if (sql[i] === '/' && sql[i + 1] === '*') {
          depth++;
          i += 2;
        } else if (sql[i] === '*' && sql[i + 1] === '/') {
          depth--;
          i += 2;
          if (depth === 0) break;
        } else i++;
      }
    } else if (c === "'" || ((c === 'E' || c === 'e') && next === "'")) {
      const start = i;
      const escapes = c !== "'";
      i += escapes ? 2 : 1;
      while (i < n) {
        if (escapes && sql[i] === '\\') i += 2;
        else if (sql[i] === "'" && sql[i + 1] === "'") i += 2;
        else if (sql[i] === "'") {
          i++;
          break;
        } else i++;
      }
      push('other', start, i);
    } else if (c === '"') {
      const start = i;
      i++;
      while (i < n) {
        if (sql[i] === '"' && sql[i + 1] === '"') i += 2;
        else if (sql[i] === '"') {
          i++;
          break;
        } else i++;
      }
      push('quoted', start, i);
    } else if (c === '$' && /^\$([A-Za-z_][A-Za-z0-9_]*)?\$/.test(sql.slice(i))) {
      const tag = /^\$([A-Za-z_][A-Za-z0-9_]*)?\$/.exec(sql.slice(i))![0];
      const start = i;
      const close = sql.indexOf(tag, i + tag.length);
      i = close < 0 ? n : close + tag.length;
      push('other', start, i);
    } else if (/[A-Za-z_\u0080-￿]/.test(c)) {
      const start = i;
      while (i < n && /[A-Za-z0-9_$\u0080-￿]/.test(sql[i])) i++;
      push('word', start, i);
    } else if (/[0-9]/.test(c)) {
      const start = i;
      while (i < n && /[0-9.eE]/.test(sql[i])) i++;
      push('other', start, i);
    } else if (c === '(' || c === ')' || c === ',' || c === '.') {
      push(c, i, i + 1);
      i++;
    } else {
      push('other', i, i + 1);
      i++;
    }
  }
  return out;
}

const QUERY_START = new Set(['SELECT', 'WITH', 'VALUES', 'TABLE']);
const WRITES = new Set(['INSERT', 'UPDATE', 'DELETE', 'MERGE']);
/** 別名にならない語 */
const NOT_ALIAS = new Set([
  'WHERE',
  'JOIN',
  'LEFT',
  'RIGHT',
  'INNER',
  'OUTER',
  'FULL',
  'CROSS',
  'NATURAL',
  'ON',
  'USING',
  'GROUP',
  'ORDER',
  'LIMIT',
  'OFFSET',
  'HAVING',
  'WINDOW',
  'UNION',
  'INTERSECT',
  'EXCEPT',
  'LATERAL',
  'FOR',
  'FETCH',
  'RETURNING',
  'SET',
  'VALUES',
  'SELECT',
  'AND',
  'OR',
  'TABLESAMPLE',
  'WITH',
  'AS',
  'FROM',
  'INTO',
  'DEFAULT',
  'DO',
  'CONFLICT',
  'WHEN',
  'THEN',
  'ELSE',
  'END',
]);
/** 句の始まり（どの句の中にいるかを覚える） */
const CLAUSES = new Set([
  'SELECT',
  'FROM',
  'WHERE',
  'GROUP',
  'HAVING',
  'ORDER',
  'LIMIT',
  'OFFSET',
  'WINDOW',
  'ON',
  'SET',
  'RETURNING',
  'VALUES',
  'USING',
  'INTO',
]);

/** 解析の途中で、部分ごとに持つもの */
interface Scope {
  part: Part;
  /** この部分の FROM などで定義した名前（表名・別名・CTE 名。小文字） */
  defined: Set<string>;
  /** この部分の中の、修飾付きの参照（x.y）。x は小文字 */
  refs: { qualifier: string; text: string; start: number; end: number }[];
  /** この部分の WITH の CTE（定義の範囲つき） */
  ctes: { part: Part; defStart: number; defEnd: number; scope: Scope }[];
  recursive: boolean;
  childScopes: Scope[];
  parent: Scope | null;
}

export interface Analysis {
  sql: string;
  root: Part;
  scopes: Map<string, Scope>;
}

const nameOf = (t: Token) => (t.kind === 'quoted' ? t.text.slice(1, -1).replace(/""/g, '"') : t.text.toLowerCase());

export function analyze(sql: string): Analysis {
  const tokens = tokenize(sql);
  // 括弧の対応
  const match = new Map<number, number>();
  const stack: number[] = [];
  tokens.forEach((t, i) => {
    if (t.kind === '(') stack.push(i);
    else if (t.kind === ')' && stack.length > 0) match.set(stack.pop()!, i);
  });
  // 末尾の ; は除く
  let last = tokens.length;
  while (last > 0 && tokens[last - 1].text === ';') last--;

  const scopes = new Map<string, Scope>();
  const newScope = (part: Part, parent: Scope | null): Scope => {
    const s: Scope = { part, defined: new Set(), refs: [], ctes: [], recursive: false, childScopes: [], parent };
    scopes.set(part.key, s);
    parent?.childScopes.push(s);
    return s;
  };

  /** [from, to) の字句を 1 つの問い合わせとして読む */
  const query = (from: number, to: number, scope: Scope) => {
    let i = from;
    if (i < to && tokens[i].upper === 'WITH') {
      i++;
      if (tokens[i]?.upper === 'RECURSIVE') {
        scope.recursive = true;
        i++;
      }
      // name [(cols)] AS [NOT] [MATERIALIZED] ( body ) [, ...]
      while (i < to) {
        const nameTok = tokens[i];
        if (!nameTok || (nameTok.kind !== 'word' && nameTok.kind !== 'quoted')) break;
        let j = i + 1;
        if (tokens[j]?.kind === '(') j = (match.get(j) ?? to) + 1;
        if (tokens[j]?.upper !== 'AS') break;
        j++;
        if (tokens[j]?.upper === 'NOT') j++;
        if (tokens[j]?.upper === 'MATERIALIZED') j++;
        if (tokens[j]?.kind !== '(') break;
        const close = match.get(j) ?? to;
        const part = makePart('cte', nameOf(nameTok), j, close, scope);
        scope.ctes.push({ part, defStart: nameTok.start, defEnd: tokens[close].end, scope: scopes.get(part.key)! });
        scope.defined.add(nameOf(nameTok));
        i = close + 1;
        if (tokens[i]?.kind === ',') i++;
        else break;
      }
    }
    scan(i, to, scope);
  };

  /** 問い合わせの本体を読む。括弧の中も同じ部分として読み、サブクエリは子にする */
  const scan = (from: number, to: number, scope: Scope, clauseIn = '') => {
    let clause = clauseIn;
    /** FROM の項目を読み始める位置（表名・別名を拾う） */
    let fromItemAt = -1;
    for (let i = from; i < to; i++) {
      const t = tokens[i];
      if (t.kind === 'word' && CLAUSES.has(t.upper)) clause = t.upper;
      if (
        t.kind === 'word' &&
        (t.upper === 'FROM' ||
          t.upper === 'JOIN' ||
          t.upper === 'UPDATE' ||
          t.upper === 'INTO' ||
          t.upper === 'USING' ||
          (t.upper === 'LATERAL' && clause === 'FROM'))
      ) {
        if (t.upper !== 'USING' || clause !== 'ON') fromItemAt = i + 1;
        if (t.upper === 'JOIN' || t.upper === 'UPDATE') clause = 'FROM';
      } else if (t.kind === ',' && clause === 'FROM') {
        fromItemAt = i + 1;
      }
      if (i === fromItemAt) {
        const consumed = fromItem(i, to, scope);
        if (consumed > i) {
          i = consumed - 1;
          fromItemAt = -1;
          continue;
        }
      }
      if (t.kind === '(') {
        const close = match.get(i) ?? to;
        const head = tokens[i + 1];
        if (head && head.kind === 'word' && (QUERY_START.has(head.upper) || WRITES.has(head.upper))) {
          makePart(kindAt(i, clause), null, i, close, scope);
        } else {
          scan(i + 1, close, scope, clause);
        }
        i = close;
        continue;
      }
      // 修飾付きの参照 x.y（関数の schema.func( は除く）
      if ((t.kind === 'word' || t.kind === 'quoted') && tokens[i + 1]?.kind === '.' && tokens[i + 2] && tokens[i + 2].kind !== '(') {
        const col = tokens[i + 2];
        if ((col.kind === 'word' || col.kind === 'quoted') && tokens[i + 3]?.kind !== '(' && tokens[i - 1]?.kind !== '.') {
          scope.refs.push({ qualifier: nameOf(t), text: sql.slice(t.start, col.end), start: t.start, end: col.end });
          i += 2;
        }
      }
    }
  };

  /** FROM の項目（表名・関数・括弧）を読み、別名を defined に入れる。読み終えた位置を返す */
  const fromItem = (i: number, to: number, scope: Scope): number => {
    let j = i;
    if (tokens[j]?.upper === 'ONLY' || tokens[j]?.upper === 'LATERAL') j++;
    const t = tokens[j];
    if (!t) return i;
    if (t.kind === '(') {
      // サブクエリや括弧で囲んだ結合は scan で読む。別名だけをここで拾う
      const close = match.get(j) ?? to;
      const head = tokens[j + 1];
      if (head && head.kind === 'word' && QUERY_START.has(head.upper)) {
        const part = makePart('from', null, j, close, scope);
        const alias = aliasAt(close + 1);
        if (alias) {
          part.name = alias.name;
          scope.defined.add(alias.name);
          return alias.next;
        }
        return close + 1;
      }
      return i;
    }
    if (t.kind !== 'word' && t.kind !== 'quoted') return i;
    let name = nameOf(t);
    j++;
    while (tokens[j]?.kind === '.' && tokens[j + 1] && (tokens[j + 1].kind === 'word' || tokens[j + 1].kind === 'quoted')) {
      name = nameOf(tokens[j + 1]);
      j += 2;
    }
    if (tokens[j]?.kind === '(') {
      // 関数（generate_series(...) など）。引数の中のサブクエリも拾う
      const close = match.get(j) ?? to;
      scan(j + 1, close, scope, 'FROM');
      j = close + 1;
    }
    const alias = aliasAt(j);
    scope.defined.add(alias ? alias.name : name);
    return alias ? alias.next : j;
  };

  /** [AS] 別名 [(列, ...)] */
  const aliasAt = (j: number): { name: string; next: number } | null => {
    if (tokens[j]?.upper === 'AS') j++;
    const a = tokens[j];
    if (!a || (a.kind !== 'word' && a.kind !== 'quoted') || (a.kind === 'word' && NOT_ALIAS.has(a.upper))) return null;
    let next = j + 1;
    if (tokens[next]?.kind === '(') next = (match.get(next) ?? next) + 1;
    return { name: nameOf(a), next };
  };

  /** 括弧の前の語から、サブクエリの種類を決める */
  const kindAt = (open: number, clause: string): PartKind => {
    const prev = tokens[open - 1];
    if (prev?.upper === 'EXISTS') return 'exists';
    if (prev?.upper === 'IN' || prev?.upper === 'ANY' || prev?.upper === 'ALL' || prev?.upper === 'SOME') return 'in';
    if (clause === 'FROM' || prev?.upper === 'LATERAL') return 'from';
    if (clause === 'SELECT') return 'select';
    return 'scalar';
  };

  let counter = 0;
  const makePart = (kind: PartKind, name: string | null, open: number, close: number, parent: Scope): Part => {
    const part: Part = {
      key: `${parent.part.key}.${counter++}`,
      kind,
      name,
      start: tokens[open + 1]?.start ?? tokens[open].end,
      end: tokens[close]?.start ?? sql.length,
      children: [],
      outerRefs: [],
      writes: false,
    };
    parent.part.children.push(part);
    const scope = newScope(part, parent);
    const head = tokens[open + 1];
    if (head && WRITES.has(head.upper)) part.writes = true;
    query(open + 1, close, scope);
    return part;
  };

  const root: Part = {
    key: '0',
    kind: 'main',
    name: null,
    start: 0,
    end: tokens[last - 1]?.end ?? 0,
    children: [],
    outerRefs: [],
    writes: false,
  };
  const rootScope = newScope(root, null);
  query(0, last, rootScope);
  root.writes = !!tokens[0] && !QUERY_START.has(tokens[0].upper);
  if (tokens[0]?.upper === 'WITH') {
    // WITH の後の本体が INSERT などなら書き換え
    const body = tokens.find((t, i) => i > 0 && (WRITES.has(t.upper) || t.upper === 'SELECT') && !insideAny(rootScope, t.start));
    root.writes = !!body && WRITES.has(body.upper);
  }

  // 相関: 部分（その中の部分を含む）で定義されていない名前で修飾した参照のうち、外側で定義された名前のもの
  for (const scope of scopes.values()) {
    if (scope.part.kind === 'main') continue;
    const defined = new Set<string>();
    const refs: Scope['refs'] = [];
    const collect = (s: Scope) => {
      s.defined.forEach((d) => defined.add(d));
      refs.push(...s.refs);
      s.childScopes.forEach(collect);
    };
    collect(scope);
    const outer = new Set<string>();
    for (let p = scope.parent; p; p = p.parent) p.defined.forEach((d) => outer.add(d));
    const found = refs.filter((r) => !defined.has(r.qualifier) && outer.has(r.qualifier));
    scope.part.outerRefs = [...new Set(found.map((r) => r.text))];
  }
  return { sql, root, scopes };
}

/** 位置が、WITH の CTE の中か */
function insideAny(scope: Scope, pos: number): boolean {
  return scope.ctes.some((c) => pos >= c.defStart && pos < c.defEnd);
}

/** 部分を単独で実行する SQL。相関サブクエリは、外側の参照を values の値（SQL のリテラル）に置き換える */
export function partSql(a: Analysis, part: Part, values: Record<string, string> = {}): string {
  const scope = a.scopes.get(part.key)!;
  // 見える CTE: 外側の WITH のうち、この部分より前に定義されたもの（データを書き換えるものは除く）
  const visible: { scope: Scope; defStart: number; defEnd: number }[] = [];
  let recursive = false;
  for (let s: Scope | null = scope.parent, child: Scope = scope; s; child = s, s = s.parent) {
    const idx = s.ctes.findIndex((c) => c.scope === child);
    const upto = idx < 0 ? s.ctes.length : s.recursive ? idx + 1 : idx;
    const own = s.ctes.slice(0, upto).filter((c) => !c.part.writes);
    if (own.length > 0) {
      visible.unshift(...own);
      recursive ||= s.recursive;
    }
  }
  const scopeScopes: Scope[] = [];
  const collect = (s: Scope) => {
    scopeScopes.push(s);
    s.childScopes.forEach(collect);
  };
  collect(scope);
  const replace = (start: number, end: number) => {
    // 外側の参照を値に置き換える（後ろから）
    const refs = scopeScopes
      .flatMap((s) => s.refs)
      .filter((r) => r.start >= start && r.end <= end && values[r.text] !== undefined && values[r.text] !== '')
      .sort((x, y) => y.start - x.start);
    let text = a.sql.slice(start, end);
    for (const r of refs) text = text.slice(0, r.start - start) + values[r.text] + text.slice(r.end - start);
    return text;
  };
  let body: string;
  if (part.kind === 'cte') {
    const own = scope.parent!.ctes.find((c) => c.scope === scope)!;
    // RECURSIVE のときは、上で自分も含めている
    if (!visible.includes(own)) visible.push(own);
    recursive ||= scope.parent!.recursive;
    body = `SELECT * FROM ${a.sql.slice(own.defStart, own.defEnd).match(/^("(?:[^"]|"")*"|[^\s(]+)/)![0]}`;
  } else {
    body = replace(part.start, part.end).trim();
  }
  if (visible.length === 0) return body;
  const ctes = visible.map((c) => (c.scope === scope ? replace(c.defStart, c.defEnd) : a.sql.slice(c.defStart, c.defEnd))).join(',\n');
  const wrapped = part.kind === 'cte' ? body : `SELECT * FROM (\n${body}\n) AS sub`;
  return `WITH ${recursive ? 'RECURSIVE ' : ''}${ctes}\n${wrapped}`;
}

/** 一覧に出す説明 */
export function partLabel(part: Part): string {
  switch (part.kind) {
    case 'main':
      return 'メインのクエリ';
    case 'cte':
      return `WITH ${part.name}`;
    case 'from':
      return part.name ? `FROM (…) ${part.name}` : 'FROM (…)';
    case 'in':
      return 'IN (…)';
    case 'exists':
      return 'EXISTS (…)';
    case 'select':
      return 'SELECT の列 (…)';
    default:
      return '(…)';
  }
}

/** SQL で使っているバインド変数の番号（$1, $2, ...。文字列・コメントの中は除く） */
export function bindNumbers(sql: string): number[] {
  const tokens = tokenize(sql);
  const out: number[] = [];
  tokens.forEach((t, i) => {
    const next = tokens[i + 1];
    if (t.text === '$' && next && next.start === t.end && /^\d+$/.test(next.text)) {
      const n = Number(next.text);
      if (!out.includes(n)) out.push(n);
    }
  });
  return out;
}

/**
 * 部分で使っているバインド変数のうち、値が未設定のもの（項目の名前）。
 * @param properties バインド変数の番号順の項目（render の bindings の property）
 * @param missing 値が未設定の項目（cond.name など。親が未設定なら親だけ）
 */
export function missingIn(sql: string, properties: string[], missing: string[]): string[] {
  const out: string[] = [];
  for (const n of bindNumbers(sql)) {
    const prop = properties[n - 1];
    if (prop === undefined) continue;
    const m = missing.find((x) => prop === x || prop.startsWith(`${x}.`) || prop.startsWith(`${x}[`));
    if (m && !out.includes(m)) out.push(m);
  }
  return out;
}
