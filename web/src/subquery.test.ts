import { describe, expect, it } from 'vitest';
import type { Part } from './subquery';
import { analyze, partLabel, partSql } from './subquery';

const SQL = `WITH paid AS (
  SELECT * FROM orders WHERE status = 'paid' -- (SELECT はコメント)
), big AS (
  SELECT customer_id, sum(total) AS amount FROM paid GROUP BY customer_id
)
SELECT c.id, c.name,
  (SELECT count(*) FROM orders o2 WHERE o2.customer_id = c.id) AS orders,
  'a(b' AS s
FROM customers c
JOIN (SELECT id FROM tenants WHERE active) t ON t.id = c.tenant_id
WHERE c.id IN (SELECT customer_id FROM big WHERE amount > 1000)
  AND EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = c.id AND i.qty > 0)
ORDER BY c.id`;

const flat = (p: Part): Part[] => [p, ...p.children.flatMap(flat)];

describe('analyze', () => {
  const a = analyze(SQL);
  const parts = flat(a.root);

  it('CTE・FROM・IN・EXISTS・SELECT の列のサブクエリを見つける（文字列・コメントの中は除く）', () => {
    expect(parts.map((p) => partLabel(p))).toEqual([
      'メインのクエリ',
      'WITH paid',
      'WITH big',
      'SELECT の列 (…)',
      'FROM (…) t',
      'IN (…)',
      'EXISTS (…)',
    ]);
  });

  it('外側の別名を参照するものを相関とする', () => {
    expect(parts.map((p) => p.outerRefs)).toEqual([[], [], [], ['c.id'], [], [], ['c.id']]);
  });

  it('CTE は手前の CTE を付けて実行する', () => {
    const big = parts[2];
    expect(partSql(a, big)).toBe(`WITH paid AS (
  SELECT * FROM orders WHERE status = 'paid' -- (SELECT はコメント)
),
big AS (
  SELECT customer_id, sum(total) AS amount FROM paid GROUP BY customer_id
)
SELECT * FROM big`);
    expect(partSql(a, parts[1]).endsWith('SELECT * FROM paid')).toBe(true);
  });

  it('メインの本体のサブクエリは、見える CTE を付けて実行する', () => {
    const inPart = parts[5];
    const sql = partSql(a, inPart);
    expect(sql.startsWith('WITH paid AS (')).toBe(true);
    expect(sql.endsWith('SELECT * FROM (\nSELECT customer_id FROM big WHERE amount > 1000\n) AS sub')).toBe(true);
    // CTE を使わないものはそのまま
    expect(partSql(a, parts[4])).toContain('SELECT id FROM tenants WHERE active');
  });

  it('相関サブクエリは、外側の参照を値に置き換えて実行する', () => {
    const exists = parts[6];
    expect(partSql(a, exists, { 'c.id': '42' })).toContain('WHERE i.order_id = 42 AND i.qty > 0');
    // 値がなければ置き換えない
    expect(partSql(a, exists)).toContain('i.order_id = c.id');
  });

  it('書き換える文と、データを書き換える CTE', () => {
    expect(analyze('UPDATE orders o SET total = (SELECT sum(x) FROM items i WHERE i.order_id = o.id)').root.writes).toBe(true);
    const upd = analyze('UPDATE orders o SET total = (SELECT sum(x) FROM items i WHERE i.order_id = o.id)');
    expect(upd.root.children[0].outerRefs).toEqual(['o.id']);
    const w = analyze('WITH d AS (DELETE FROM t RETURNING id), k AS (SELECT 1) SELECT * FROM k');
    expect(w.root.writes).toBe(false);
    expect(w.root.children[0].writes).toBe(true);
    // データを書き換える CTE は付けない
    expect(partSql(w, w.root.children[1])).toBe('WITH k AS (SELECT 1)\nSELECT * FROM k');
  });

  it('PostgreSQL の書き方（ドル記号の引用・::・入れ子のコメント・LATERAL）', () => {
    const b = analyze(`SELECT $q$ (SELECT 1) $q$::text, x.v /* a /* (SELECT) */ b */
FROM t, LATERAL (SELECT t.id + 1 AS v) x`);
    expect(flat(b.root).map((p) => partLabel(p))).toEqual(['メインのクエリ', 'FROM (…) x']);
    expect(b.root.children[0].outerRefs).toEqual(['t.id']);
  });

  it('再帰の CTE は自分を 1 回だけ付ける', () => {
    const r = analyze(`WITH RECURSIVE tree AS (
  SELECT id FROM categories WHERE id = 1
  UNION ALL
  SELECT c.id FROM categories c JOIN tree t ON c.parent_id = t.id
)
SELECT * FROM products p WHERE p.category_id IN (SELECT id FROM tree)`);
    const [cte, inPart] = r.root.children;
    const sql = partSql(r, cte);
    expect(sql.match(/tree AS \(/g)?.length).toBe(1);
    expect(sql.startsWith('WITH RECURSIVE tree AS (')).toBe(true);
    expect(sql.endsWith('SELECT * FROM tree')).toBe(true);
    expect(partSql(r, inPart).match(/tree AS \(/g)?.length).toBe(1);
    // CTE の中の別名 t は外側への参照ではない
    expect(cte.outerRefs).toEqual([]);
  });
});

describe('sqlLiteral', () => {
  it('型に合わせて SQL の書き方にする', async () => {
    const { sqlLiteral } = await import('./Subquery');
    expect(sqlLiteral({ t: 'number', v: '42' })).toBe('42');
    expect(sqlLiteral({ t: 'string', v: "O'Reilly" })).toBe("'O''Reilly'");
    expect(sqlLiteral({ t: 'timestamp', v: '2026-10-17 09:00:00' })).toBe("TIMESTAMP '2026-10-17 09:00:00'");
    expect(sqlLiteral({ t: 'date', v: '2026-10-17' })).toBe("DATE '2026-10-17'");
    expect(sqlLiteral({ t: 'pg', type: 'uuid', v: 'a-b' })).toBe("CAST('a-b' AS uuid)");
    expect(sqlLiteral({ t: 'boolean', v: true })).toBe('TRUE');
    expect(sqlLiteral({ t: 'null' })).toBe('NULL');
    expect(() => sqlLiteral({ t: 'number', v: 'abc' })).toThrow();
    expect(() => sqlLiteral({ t: 'list', items: [] })).toThrow();
  });
});
