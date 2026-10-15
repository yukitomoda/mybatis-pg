// テンプレート（mapper XML の statement 要素）の色分け

export type TokenKind = 'text' | 'comment' | 'cdata' | 'punct' | 'tag' | 'attr' | 'value' | 'param' | 'raw';

export interface Token {
  kind: TokenKind;
  text: string;
}

const TOP = /(<!--[\s\S]*?-->)|(<!\[CDATA\[|\]\]>)|(<\/?[\w-]+(?:\s+[\w:.-]+\s*=\s*(?:"[^"]*"|'[^']*'))*\s*\/?>)|([#$]\{[^}]*\})/g;
const TAG = /^(<\/?)([\w-]+)([\s\S]*?)(\/?>)$/;
const ATTR = /(\s+)([\w:.-]+)(\s*=\s*)("[^"]*"|'[^']*')/g;

/** 要素名・属性・#{}・${}・コメントを分ける。つなげると元の文字列に戻る */
export function tokenize(xml: string): Token[] {
  const tokens: Token[] = [];
  const push = (kind: TokenKind, text: string) => {
    if (text) tokens.push({ kind, text });
  };
  let pos = 0;
  for (const m of xml.matchAll(TOP)) {
    push('text', xml.slice(pos, m.index));
    if (m[1]) push('comment', m[1]);
    else if (m[2]) push('cdata', m[2]);
    else if (m[3]) tag(m[3], push);
    else push(m[4].startsWith('#') ? 'param' : 'raw', m[4]);
    pos = m.index + m[0].length;
  }
  push('text', xml.slice(pos));
  return tokens;
}

function tag(text: string, push: (kind: TokenKind, text: string) => void) {
  const m = TAG.exec(text);
  if (!m) {
    push('text', text);
    return;
  }
  push('punct', m[1]);
  push('tag', m[2]);
  let pos = 0;
  const attrs = m[3];
  for (const a of attrs.matchAll(ATTR)) {
    push('text', attrs.slice(pos, a.index) + a[1]);
    push('attr', a[2]);
    push('punct', a[3]);
    push('value', a[4]);
    pos = a.index + a[0].length;
  }
  push('text', attrs.slice(pos));
  push('punct', m[4]);
}
