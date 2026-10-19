package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ParamSourcesTest {

  @Test
  void 計画の条件から目印と比べている列を探す() {
    String plan = "[{\"Plan\": {\"Node Type\": \"Hash Join\", \"Hash Cond\": \"(o.customer_id = c.id)\", \"Plans\": ["
        + "{\"Node Type\": \"Index Scan\", \"Relation Name\": \"orders\", \"Schema\": \"shop\", \"Alias\": \"o\","
        + " \"Index Cond\": \"(o.tenant_id = 987654301)\", \"Filter\": \"((o.status = 'mbpg_p2_x'::text) AND (date_part('month'::text, o.ordered_at) = '987654303'::double precision))\"},"
        + "{\"Node Type\": \"Seq Scan\", \"Relation Name\": \"customers\", \"Schema\": \"shop\", \"Alias\": \"c\","
        + " \"Filter\": \"(c.id = ANY ('{987654304,987654305}'::bigint[]))\"}]}}]";
    Map<Integer, List<ParamSources.Source>> r = ParamSources.fromPlan(plan,
        Arrays.asList("987654301", "mbpg_p2_x", "987654303", "987654304", "987654305", null));
    assertEquals("shop.orders.tenant_id", name(r.get(1).get(0)));
    assertTrue(r.get(1).get(0).direct);
    assertEquals("(o.tenant_id = $1)", r.get(1).get(0).condition);
    assertEquals("shop.orders.status", name(r.get(2).get(0)));
    assertTrue(r.get(2).get(0).direct);
    // 関数で包んだ列は、列は分かるが「直接ではない」
    assertEquals("shop.orders.ordered_at", name(r.get(3).get(0)));
    assertFalse(r.get(3).get(0).direct);
    // foreach の IN（= ANY）
    assertEquals("shop.customers.id", name(r.get(4).get(0)));
    assertTrue(r.get(4).get(0).direct);
    assertNull(r.get(6));
  }

  @Test
  void 型ごとの目印() {
    assertEquals("987654303", ParamSources.marker(3, "bigint", 0));
    assertEquals("73", ParamSources.marker(3, "numeric(10,2)", 1));
    assertNull(ParamSources.marker(3, "integer", 2));
    assertEquals("31003", ParamSources.marker(3, "smallint", 0));
    assertEquals("mbpg_p3_x", ParamSources.marker(3, "text", 2));
    assertEquals("2999-12-31 00:00:03", ParamSources.marker(3, "timestamp without time zone", 0));
    assertEquals("2999-01-04", ParamSources.marker(3, "date", 0));
    assertNull(ParamSources.marker(3, "boolean", 0));
  }

  @Test
  @SuppressWarnings("unchecked")
  void 推論のための仮の値はifをすべて通す_今の値はそのまま() {
    SqlRenderer r = SqlRenderer.load(java.util.Arrays.asList(java.nio.file.Paths.get("src/test/resources/mappers/Common.xml"),
        java.nio.file.Paths.get("src/test/resources/mappers/UserMapper.xml")));
    java.util.Map<String, Object> actual = new java.util.HashMap<>();
    java.util.Map<String, Object> cond = new java.util.HashMap<>();
    cond.put("name", "taro");
    actual.put("cond", cond);
    Object probe = ParamSources.probe(r.parameters("sample.UserMapper.search").root, actual);
    java.util.Map<String, Object> p = (java.util.Map<String, Object>) probe;
    assertEquals("taro", ((java.util.Map<String, Object>) p.get("cond")).get("name"));
    // 仮の値なら、if の中の条件もすべて SQL に出る
    String withProbe = r.render("sample.UserMapper.search", probe).placeholderSql;
    String withActual = r.render("sample.UserMapper.search", actual).placeholderSql;
    assertTrue(withProbe.length() > withActual.length(), withProbe);
    assertTrue(withProbe.chars().filter(ch -> ch == '?').count() > withActual.chars().filter(ch -> ch == '?').count(), withProbe);
  }

  private static String name(ParamSources.Source s) {
    return s.schema + "." + s.table + "." + s.column;
  }
}
