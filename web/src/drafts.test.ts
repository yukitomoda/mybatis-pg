import { describe, expect, it } from 'vitest';
import { statementLine } from './drafts';

const xml = `<?xml version="1.0"?>
<mapper namespace="a.B">
  <select id="findAll">SELECT 1</select>
  <insert
      id='insert'>
    <selectKey keyProperty="id" order="BEFORE">SELECT 1</selectKey>
  </insert>
  <select id="find.by">SELECT 2</select>
</mapper>`;

describe('statementLine', () => {
  it('statement の開始行を探す（属性が別の行にあっても、selectKey は親の statement）', () => {
    expect(statementLine(xml, 'a.B.findAll')).toBe(3);
    expect(statementLine(xml, 'a.B.insert')).toBe(4);
    expect(statementLine(xml, 'a.B.insert!selectKey')).toBe(4);
    expect(statementLine(xml, 'a.B.nothing')).toBeNull();
  });
});

describe('detectIndent', () => {
  it('ファイルの字下げに合わせる', async () => {
    const { detectIndent } = await import('./XmlEditor');
    expect(detectIndent('<a>\n  <b>\n    <c/>\n  </b>\n</a>')).toBe('  ');
    expect(detectIndent('<a>\n    <b>\n        <c/>\n    </b>\n</a>')).toBe('    ');
    expect(detectIndent('<a>\n\t<b>\n\t\t<c/>\n\t</b>\n</a>')).toBe('\t');
  });
});
