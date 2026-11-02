import { describe, expect, it } from 'vitest';
import { findJoinRanges } from './joinMarks';

const xml = `<mapper namespace="erp.M">
  <sql id="joinOwner">JOIN erp.employees e ON e.id = c.owner_employee_id</sql>
  <select id="a" resultType="map">
    SELECT * FROM erp.clients c
    JOIN erp.projects p
      ON p.id =
         c.owner_employee_id
  </select>
  <select id="b" resultType="map">
    SELECT * FROM erp.clients c <include refid="joinOwner"/>
  </select>
  <select id="c" resultType="map">
    SELECT * FROM (SELECT * FROM erp.clients x) sub_c JOIN erp.projects p ON sub_c.id = p.client_id
  </select>
</mapper>`;
const text = (r: { from: number; to: number }) => xml.slice(r.from, r.to);

describe('findJoinRanges', () => {
  it('計画の式（左右が逆、改行をはさむ）で、その statement の中だけを探す', () => {
    const r = findJoinRanges(xml, {
      statementId: 'erp.M.a',
      a: 'erp.clients.owner_employee_id',
      b: 'erp.projects.id',
      expr: 'c.owner_employee_id = p.id',
    });
    expect(r.map(text)).toEqual(['p.id =\n         c.owner_employee_id']);
  });
  it('include している同じファイルの <sql> の中も探す', () => {
    const r = findJoinRanges(xml, {
      statementId: 'erp.M.b',
      a: 'erp.clients.owner_employee_id',
      b: 'erp.employees.id',
      expr: 'c.owner_employee_id = e.id',
    });
    expect(r.map(text)).toEqual(['e.id = c.owner_employee_id']);
  });
  it('計画の別名が SQL にない（"*SELECT* 1" など）ときは、列の名前だけで探す', () => {
    const r = findJoinRanges(xml, {
      statementId: 'erp.M.c',
      a: 'erp.projects.client_id',
      b: 'erp.clients.id',
      expr: '"*SELECT* 1".client_id = x.id',
    });
    expect(r.map(text)).toEqual(['sub_c.id = p.client_id']);
  });
  it('見つからなければ空', () => {
    expect(findJoinRanges(xml, { statementId: 'erp.M.a', a: 'erp.x.y', b: 'erp.z.w', expr: '' })).toEqual([]);
  });
});
