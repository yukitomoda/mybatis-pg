// リレーションの画面から statement を開いたときに、XML の中で、その結合をしている場所（c.user_id = u.id）を探す
import { statementLine } from './drafts';

/** 探す結合（列は schema.table.column、式は計画に書かれた別名のまま。改行で区切って複数） */
export type JoinFocus = { statementId: string; a: string; b: string; expr: string };

const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** 名前（引用符で囲んでもよい。大文字・小文字は区別しない） */
const name = (n: string) => `"?${esc(n.replace(/^"|"$/g, ''))}"?`;
const ANY_ALIAS = '(?:"[^"\\n]+"|[A-Za-z_][\\w$]*)';

/** x.c1 = y.c2（左右を入れ替えたものも）。別名を null にすると、どの別名でもよい */
function patterns(x: string | null, c1: string, y: string | null, c2: string): RegExp[] {
  const side = (alias: string | null, col: string) =>
    `(?<![\\w$."])${alias === null ? ANY_ALIAS : name(alias)}\\s*\\.\\s*${name(col)}(?![\\w$"])`;
  return [new RegExp(`${side(x, c1)}\\s*=\\s*${side(y, c2)}`, 'gi'), new RegExp(`${side(y, c2)}\\s*=\\s*${side(x, c1)}`, 'gi')];
}

/** 式（x.c1 = y.c2）を分ける */
function parse(expr: string): [string, string, string, string] | null {
  const m = /^\s*("[^"]+"|[^\s.=]+)\s*\.\s*("[^"]+"|[^\s.=]+)\s*=\s*("[^"]+"|[^\s.=]+)\s*\.\s*("[^"]+"|[^\s.=]+)\s*$/.exec(expr);
  return m ? [m[1], m[2], m[3], m[4]] : null;
}

/** statement（select など）の要素の範囲。見つからなければ null */
function statementRange(content: string, statementId: string): [number, number] | null {
  const line = statementLine(content, statementId);
  if (line === null) return null;
  const from =
    content
      .split('\n')
      .slice(0, line - 1)
      .join('\n').length + (line > 1 ? 1 : 0);
  const close = /<\/(select|insert|update|delete)\s*>/g;
  close.lastIndex = from;
  const m = close.exec(content);
  return [from, m ? m.index + m[0].length : content.length];
}

/** 範囲の中の <include refid="x"> が指す、同じファイルの <sql id="x"> の範囲 */
function includedRanges(content: string, [from, to]: [number, number]): [number, number][] {
  const out: [number, number][] = [];
  const inc = /<include\b[^>]*\brefid\s*=\s*["']([^"']+)["']/g;
  const body = content.slice(from, to);
  for (let m = inc.exec(body); m; m = inc.exec(body)) {
    const id = m[1].slice(m[1].lastIndexOf('.') + 1);
    const sql = new RegExp(`<sql\\b[^>]*\\bid\\s*=\\s*["']${esc(id)}["'][^>]*>([\\s\\S]*?)</sql\\s*>`).exec(content);
    if (sql) out.push([sql.index, sql.index + sql[0].length]);
  }
  return out;
}

/**
 * XML の中で結合をしている場所。計画の式の別名のままで探し、なければ列の名前だけで探す。
 * 探すのは statement の要素と、そこから include している同じファイルの <sql> の中
 */
export function findJoinRanges(content: string, focus: JoinFocus): { from: number; to: number }[] {
  const range = statementRange(content, focus.statementId);
  if (!range) return [];
  const scopes = [range, ...includedRanges(content, range)];
  const exprs = focus.expr
    .split('\n')
    .map(parse)
    .filter((x): x is [string, string, string, string] => x !== null);
  const col = (k: string) => k.slice(k.lastIndexOf('.') + 1);
  const tries: RegExp[][] = [
    exprs.flatMap(([x, c1, y, c2]) => patterns(x, c1, y, c2)),
    [...exprs.flatMap(([, c1, , c2]) => patterns(null, c1, null, c2)), ...patterns(null, col(focus.a), null, col(focus.b))],
  ];
  for (const res of tries) {
    const found = new Map<number, number>();
    for (const [from, to] of scopes) {
      const text = content.slice(from, to);
      for (const re of res) {
        re.lastIndex = 0;
        for (let m = re.exec(text); m; m = re.exec(text)) found.set(from + m.index, from + m.index + m[0].length);
      }
    }
    if (found.size > 0) return [...found].sort((p, q) => p[0] - q[0]).map(([from, to]) => ({ from, to }));
  }
  return [];
}
