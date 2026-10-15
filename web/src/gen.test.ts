import { describe, expect, it } from 'vitest';
import { buildExpr, evaluateTemplate, forget, formatDate, parseDate, parseTemplate, resolveTemplates } from './gen';
import { fromJson, toJson } from './params';

// テストはブラウザのタイムゾーンに依らないよう、オフセット付きの日時か、ローカル時刻どうしで比べる
const NOW = new Date(2026, 9, 15, 14, 2, 11, 123); // 2026-10-15 14:02:11.123（ローカル）

describe('parseTemplate', () => {
  it('文字と式に分ける。\\{{ は文字の {{ にする', () => {
    expect(parseTemplate('user-{{uuid}}-{{date:now-7d|yyyy-MM-dd}} \\{{x}}')).toEqual([
      'user-',
      { raw: '{{uuid}}', fn: 'uuid', args: [] },
      '-',
      { raw: '{{date:now-7d|yyyy-MM-dd}}', fn: 'date', args: ['now-7d', 'yyyy-MM-dd'] },
      ' {{x}}',
    ]);
  });

  it('書式の : は引数の一部として扱う', () => {
    expect(parseTemplate('{{date:now|HH:mm}}')).toEqual([{ raw: '{{date:now|HH:mm}}', fn: 'date', args: ['now', 'HH:mm'] }]);
  });

  it('閉じていない式は誤り', () => {
    expect(() => parseTemplate('{{uuid')).toThrow('}}');
  });
});

describe('日時', () => {
  it('基準の書き方', () => {
    expect(formatDate(parseDate('now', NOW), 'yyyy-MM-dd HH:mm:ss.SSS')).toBe('2026-10-15 14:02:11.123');
    expect(formatDate(parseDate('', NOW), 'yyyy-MM-dd HH:mm:ss')).toBe('2026-10-15 14:02:11');
    expect(formatDate(parseDate('today', NOW), 'yyyy-MM-dd HH:mm:ss')).toBe('2026-10-15 00:00:00');
    expect(formatDate(parseDate('now-7d', NOW), 'yyyy-MM-dd')).toBe('2026-10-08');
    expect(formatDate(parseDate('today+1M-1d', NOW), 'yyyy-MM-dd')).toBe('2026-11-14');
    expect(formatDate(parseDate('2026-01-03 23:35', NOW), 'yyyy/M/d H:mm')).toBe('2026/1/3 23:35');
    expect(formatDate(parseDate('2026-01-03', NOW), "yyyy-MM-dd'T'HH:mm")).toBe('2026-01-03T00:00');
  });

  it('オフセット付きの日時は、その時刻として解釈する', () => {
    expect(parseDate('2026-01-03 23:35+09:00', NOW).getTime()).toBe(Date.UTC(2026, 0, 3, 14, 35));
    expect(parseDate('2026-01-03T14:35:00Z', NOW).getTime()).toBe(Date.UTC(2026, 0, 3, 14, 35));
  });

  it('誤り', () => {
    expect(() => parseDate('2026-02-30', NOW)).toThrow('存在しない日付');
    expect(() => parseDate('yesterday', NOW)).toThrow('日時として解釈できません');
    expect(() => parseDate('now-7x', NOW)).toThrow('相対指定');
    expect(() => formatDate(NOW, 'yyyy-MM-dd hh')).toThrow('書式に使えない文字');
  });
});

describe('evaluateTemplate', () => {
  it('UNIX 時刻', () => {
    const cache = new Map<string, string>();
    expect(evaluateTemplate('{{unixtime:2026-01-03 23:35+09:00}}', 'ts', cache, true, NOW).text).toBe('1767450900');
    expect(evaluateTemplate('{{unixtime:2026-01-03 23:35+09:00|ms}}', 'ts', cache, true, NOW).text).toBe('1767450900000');
  });

  it('作るたびに変わる値は覚えておき、forget で作り直す', () => {
    const cache = new Map<string, string>();
    const a = evaluateTemplate('id-{{uuid}}', 'id', cache, false, NOW);
    expect(a.text).toMatch(/^id-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(a.volatile).toBe(true);
    expect(evaluateTemplate('id-{{uuid}}', 'id', cache, false, NOW).text).toBe(a.text);
    // 別の項目では別の値
    expect(evaluateTemplate('id-{{uuid}}', 'other', cache, false, NOW).text).not.toBe(a.text);
    forget(cache, 'id');
    expect(evaluateTemplate('id-{{uuid}}', 'id', cache, false, NOW).text).not.toBe(a.text);
  });

  it('乱数とランダムな文字列', () => {
    const cache = new Map<string, string>();
    const n = Number(evaluateTemplate('{{random:1..3}}', 'r', cache, true, NOW).text);
    expect([1, 2, 3]).toContain(n);
    expect(evaluateTemplate('{{randstr:8}}', 's', cache, false, NOW).text).toMatch(/^[A-Za-z0-9]{8}$/);
  });

  it('知らない関数や、数値の欄での文字列の関数は誤り', () => {
    const cache = new Map<string, string>();
    expect(() => evaluateTemplate('{{nope}}', 'x', cache, false, NOW)).toThrow('知らない関数');
    expect(() => evaluateTemplate('{{uuid}}', 'x', cache, true, NOW)).toThrow('数値の欄');
  });
});

describe('resolveTemplates', () => {
  it('木の中の式を計算し、誤りと使った式を集める', () => {
    const v = fromJson('{"a":"{{date:2026-01-03|yyyy/MM/dd}}","b":[{"t":"\\\\{{x}}"}],"c":"plain","d":"{{bad}}"}');
    const r = resolveTemplates(v, new Map(), NOW);
    expect(toJson(r.value, '')).toBe('{"a":"2026/01/03","b":[{"t":"{{x}}"}],"c":"plain","d":"{{bad}}"}');
    expect(r.generated).toEqual([{ path: 'a', source: '{{date:2026-01-03|yyyy/MM/dd}}', result: '2026/01/03', volatile: false }]);
    expect(r.issues.map((i) => i.path)).toEqual(['d']);
  });

  it('数値の欄は結果が数値でなければ誤り', () => {
    const v = { t: 'object' as const, fields: [['n', { t: 'number' as const, v: '1{{unixtime:2026-01-03+09:00}}x' }]] as [string, never][] };
    const r = resolveTemplates(v, new Map(), NOW);
    expect(r.issues[0].message).toContain('結果が数値になりません');
  });
});

describe('buildExpr', () => {
  it('末尾の空の引数を省く', () => {
    expect(buildExpr('uuid', [])).toBe('{{uuid}}');
    expect(buildExpr('date', ['now-7d', ''])).toBe('{{date:now-7d}}');
    expect(buildExpr('date', ['', 'yyyy'])).toBe('{{date:|yyyy}}');
  });
});
