import { describe, expect, it } from 'vitest';
import { tokenize } from './template';

describe('tokenize', () => {
  it('要素・属性・#{}・${} を分け、つなげると元に戻る', () => {
    const xml = '<if test="a != null">\n  a = #{a} ORDER BY ${o}\n</if><!-- x --><![CDATA[ < ]]>';
    const tokens = tokenize(xml);
    expect(tokens.map((t) => t.text).join('')).toBe(xml);
    expect(tokens.filter((t) => t.kind !== 'text' && t.kind !== 'punct').map((t) => `${t.kind}:${t.text}`)).toEqual([
      'tag:if',
      'attr:test',
      'value:"a != null"',
      'param:#{a}',
      'raw:${o}',
      'tag:if',
      'comment:<!-- x -->',
      'cdata:<![CDATA[',
      'cdata:]]>',
    ]);
  });
});
