// statement の id（com.foo.bar.UserMapper.find）を、見分けられる範囲で短くする（UserMapper.find）。
// 違う名前空間に同じ名前の Mapper があるときだけ、見分けられるところまで名前空間をさかのぼる（order.UserMapper.find）
import { createContext, useContext } from 'react';

/** 名前空間（最後の . より前）と、statement の名前 */
function split(id: string): [string[], string] {
  const at = id.lastIndexOf('.');
  return at < 0 ? [[], id] : [id.slice(0, at).split('.'), id.slice(at + 1)];
}

/**
 * ids（ワークスペースの statement）を見て、id を短くする関数を作る。
 * 名前空間は、ほかの名前空間と見分けられる最短の末尾（少なくとも Mapper の名前）にする
 */
export function makeShortener(ids: Iterable<string>): (id: string) => string {
  const namespaces = new Set<string>();
  for (const id of ids) namespaces.add(split(id)[0].join('.'));
  const all = [...namespaces].map((n) => n.split('.'));
  const memo = new Map<string, string>();
  const shortNs = (ns: string[]): string => {
    const key = ns.join('.');
    const hit = memo.get(key);
    if (hit !== undefined) return hit;
    let m = 1;
    while (m < ns.length) {
      const tail = ns.slice(-m).join('.');
      const clash = all.some((o) => o.join('.') !== key && o.slice(-m).join('.') === tail);
      if (!clash) break;
      m++;
    }
    const out = ns.slice(-m).join('.');
    memo.set(key, out);
    return out;
  };
  return (id: string) => {
    const [ns, name] = split(id);
    return ns.length === 0 ? name : `${shortNs(ns)}.${name}`;
  };
}

/** 画面の中で使う、statement の id を短くする関数（ワークスペースの statement を読むまでは、最後の 2 つ） */
export const StatementNames = createContext<(id: string) => string>((id) => id.split('.').slice(-2).join('.'));

export const useStatementName = () => useContext(StatementNames);
