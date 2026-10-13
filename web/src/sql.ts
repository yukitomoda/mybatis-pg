// 表示・コピー用の SQL の整形

/**
 * MyBatis の展開結果に残る空白だけの行を除き、共通の字下げを外す。
 * 文字列リテラルの中に改行があると、その中の空白だけの行も消えるが、実用上はまれなため許容する。
 */
export function tidySql(sql: string): string {
  const lines = sql.split(/\r?\n/).filter((line) => line.trim() !== '');
  if (lines.length === 0) return '';
  // 1 行目は MyBatis が字下げを消していることが多いため、2 行目以降で共通の字下げを求める
  const rest = lines.slice(1);
  const indent = rest.length === 0 ? 0 : Math.min(...rest.map((line) => line.match(/^[ \t]*/)![0].length));
  return [lines[0].trim(), ...rest.map((line) => line.slice(indent).trimEnd())].join('\n');
}
