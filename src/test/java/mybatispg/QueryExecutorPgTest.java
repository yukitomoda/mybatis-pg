package mybatispg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 本物の PostgreSQL で確かめるテスト。環境変数 MYBATIS_PG_TEST_URL（例:
 * jdbc:postgresql://127.0.0.1:5432/test?user=postgres&password=...）があるときだけ動く。
 * items 表を作り直すので、テスト専用の DB を指定すること。
 */
class QueryExecutorPgTest {

  private static final String URL = System.getenv("MYBATIS_PG_TEST_URL");
  private static final SqlRenderer RENDERER =
      SqlRenderer.load(Collections.singletonList(Paths.get("src/test/resources/exec/ExecMapper.xml")));

  @BeforeEach
  void prepare() throws Exception {
    assumeTrue(URL != null, "MYBATIS_PG_TEST_URL がないため省略");
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
      s.execute("DROP TABLE IF EXISTS items");
      s.execute("CREATE TABLE items (id bigint PRIMARY KEY, name text)");
      s.execute("INSERT INTO items SELECT g, 'n' || g FROM generate_series(1, 1000) g");
      s.execute("ANALYZE items");
    }
  }

  private static Map<String, Object> params(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static QueryExecutor.ExplainResult explain(String id, Map<String, Object> p, QueryExecutor.ExplainOptions o)
      throws Exception {
    return QueryExecutor.explain(RENDERER, "exec.ItemMapper." + id, p, () -> DriverManager.getConnection(URL), o);
  }

  private static int count() throws Exception {
    try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT count(*) FROM items")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void バインド変数のままEXPLAINしJSONで返す() throws Exception {
    QueryExecutor.ExplainResult r = explain("find", params("ids", java.util.Arrays.asList(1L, 2L)), new QueryExecutor.ExplainOptions());
    assertTrue(r.plan.startsWith("[") && r.plan.contains("\"Plan\""), r.plan);
    assertTrue(r.plan.contains("\"Actual Rows\": 2"), r.plan);
    assertTrue(r.plan.contains("\"Shared Hit Blocks\""), r.plan);
    assertTrue(r.sql.contains("id IN (?,?)"), r.sql);
  }

  @Test
  void ANALYZEなしでは実行しない() throws Exception {
    QueryExecutor.ExplainOptions o = new QueryExecutor.ExplainOptions();
    o.analyze = false;
    QueryExecutor.ExplainResult r = explain("find", params(), o);
    assertFalse(r.plan.contains("Actual Rows"), r.plan);
  }

  @Test
  void 更新系のANALYZEはロールバックする() throws Exception {
    explain("insert", params("id", 5000L, "name", "x"), new QueryExecutor.ExplainOptions());
    assertEquals(1000, count());
  }

  @Test
  void 読み取り専用では更新系がエラーになる() {
    QueryExecutor.ExplainOptions o = new QueryExecutor.ExplainOptions();
    o.readOnly = true;
    QueryExecutor.ExecutionException e = assertThrows(QueryExecutor.ExecutionException.class,
        () -> explain("insert", params("id", 5000L, "name", "x"), o));
    assertTrue(e.getMessage().contains("read-only"), e.getMessage());
  }
}
